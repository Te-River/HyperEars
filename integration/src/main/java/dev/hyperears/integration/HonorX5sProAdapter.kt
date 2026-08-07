package dev.hyperears.integration

import dev.hyperears.protocol.honor.HonorX5sAtCodec

/**
 * Concrete adapter for the Honor X5s Pro (BTV-ME10).
 *
 * Battery telemetry comes from the system HFP aggregate (the ROM parses the standard
 * IPHONEACCEV report into RemoteDevices). Noise modes are controlled through the device's
 * private RFCOMM SPP channel using `5A 00` framed commands captured from the vendor app:
 * `5A 00 07 00 2B 04 01 02 <mode> <depth> <crc>` with mode 01=ANC, 02=transparency, 00=off and
 * ANC depth 01=smart, 02=light, 03=medium, 00=deep. The earphone reports its state back as
 * `5A 00 07 00 2B 2A 01 02 <x> <y> <crc>`.
 *
 * ANC depth levels are read-compatible like the OPPO intensity values: the earphone's depth is
 * tracked from its state reports and carried by ANC commands, but no depth-switching UI is
 * exposed until the common domain gains a distinct adaptive-mode model.
 */
class HonorX5sProAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "荣耀亲选耳机 X5s Pro"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val batterySource: BatterySource = BatterySource.PRIVATE_PROTOCOL
    override val noiseControlConfirmation: ControlConfirmationPolicy =
        ControlConfirmationPolicy.PUBLISH_AFTER_WRITE
    override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        noiseControl = true,
        audioHandoff = true,
    )
    override val supportedNoiseModes: Set<NoiseMode> =
        setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY)
    override val miLinkCardPresentationId: MiLinkCardPresentationId = PRESENTATION_ID

    /** Defaults to the vendor app's smart level until the earphone reports its own state. */
    @Volatile
    private var ancDepth: HonorX5sAtCodec.AncDepth = HonorX5sAtCodec.AncDepth.SMART

    override fun matches(identity: EarbudIdentity): Boolean =
        normalizeDeviceName(identity.deviceName.orEmpty()) == "荣耀亲选耳机x5spro"

    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "honor-x5spro-spp",
        ),
    )

    override fun createProtocolSession(): ProtocolSession =
        HonorX5sProProtocolSession(
            depthProvider = { ancDepth },
            depthSink = { ancDepth = it },
        )

    companion object {
        const val ID = "honor-x5spro"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        val PRESENTATION_ID = MiLinkCardPresentationId(ID)
    }
}

private class HonorX5sProProtocolSession(
    private val depthProvider: () -> HonorX5sAtCodec.AncDepth,
    private val depthSink: (HonorX5sAtCodec.AncDepth) -> Unit,
) : ProtocolSession {

    override fun initialReadCommands(): List<ByteArray> = listOf(HonorX5sAtCodec.queryBattery)

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> listOf(HonorX5sAtCodec.queryBattery)
        is ControlRequest.SetNoiseMode -> listOf(
            HonorX5sAtCodec.modeCommand(request.mode.toWireMode(), depthProvider()),
        )
    }

    override fun readback(request: ControlRequest): List<ByteArray> = emptyList()

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        if (HonorX5sAtCodec.isHeartbeat(bytes)) return@buildList
        HonorX5sAtCodec.parseBatteryFrame(bytes)?.let { battery ->
            add(ProtocolEvent.CapabilitiesIdentified(battery = true))
            add(
                ProtocolEvent.BatteryChanged(
                    EarbudBattery(
                        left = BatteryReading(battery.leftPercent, charging = false),
                        right = BatteryReading(battery.rightPercent, charging = false),
                        case = BatteryReading(battery.casePercent, charging = false),
                    ),
                ),
            )
            return@buildList
        }
        HonorX5sAtCodec.parseHuaweiBattery(String(bytes, Charsets.US_ASCII))?.let { battery ->
            add(ProtocolEvent.CapabilitiesIdentified(battery = true))
            add(
                ProtocolEvent.BatteryChanged(
                    EarbudBattery(
                        left = BatteryReading(battery.leftPercent, charging = false),
                        right = BatteryReading(battery.rightPercent, charging = false),
                        case = BatteryReading(battery.casePercent, charging = false),
                    ),
                ),
            )
            return@buildList
        }
        HonorX5sAtCodec.stateFromFrame(bytes)?.let { state ->
            state.depth?.let(depthSink)
            add(
                ProtocolEvent.CapabilitiesIdentified(
                    battery = false,
                    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
                ),
            )
            add(ProtocolEvent.NoiseModeChanged(state.mode.toDomainMode()))
        }
    }

    override fun reset() = Unit

    private fun NoiseMode.toWireMode(): HonorX5sAtCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> HonorX5sAtCodec.NoiseMode.ANC
        NoiseMode.OFF -> HonorX5sAtCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> HonorX5sAtCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> error("X5s Pro does not expose a wind-noise mode")
    }

    private fun HonorX5sAtCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        HonorX5sAtCodec.NoiseMode.ANC -> NoiseMode.ANC
        HonorX5sAtCodec.NoiseMode.OFF -> NoiseMode.OFF
        HonorX5sAtCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }
}
