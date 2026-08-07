package dev.hyperears.integration

import dev.hyperears.protocol.honor.HonorX5sAtCodec

/**
 * Concrete adapter for the Honor X5s Pro (BTV-ME10).
 *
 * Battery telemetry arrives over the system HFP channel (`AT+HUAWEIBATTERY`) and is injected
 * into the session by the HFP hook. Noise modes are controlled through the device's private
 * RFCOMM SPP channel using `5A 00` framed commands captured from the vendor app:
 * `5A 00 07 00 2B 04 01 02 <mode> 00 <crc>` with mode 01=ANC, 02=transparency, 00=off.
 * The earphone reports its current mode back as `5A 00 07 00 2B 2A 01 02 00 <mode> <crc>`.
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

    override fun matches(identity: EarbudIdentity): Boolean =
        normalizeDeviceName(identity.deviceName.orEmpty()) == "荣耀亲选耳机x5spro"

    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "honor-x5spro-spp",
        ),
    )

    override fun createProtocolSession(): ProtocolSession = HonorX5sProProtocolSession()

    companion object {
        const val ID = "honor-x5spro"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}

private class HonorX5sProProtocolSession : ProtocolSession {

    override fun initialReadCommands(): List<ByteArray> = emptyList()

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> emptyList()
        is ControlRequest.SetNoiseMode -> listOf(
            HonorX5sAtCodec.modeCommand(request.mode.toWireMode()),
        )
    }

    override fun readback(request: ControlRequest): List<ByteArray> = emptyList()

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        if (HonorX5sAtCodec.isHeartbeat(bytes)) return@buildList
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
        HonorX5sAtCodec.modeFromStateFrame(bytes)?.let { mode ->
            add(
                ProtocolEvent.CapabilitiesIdentified(
                    battery = false,
                    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
                ),
            )
            add(ProtocolEvent.NoiseModeChanged(mode.toDomainMode()))
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
