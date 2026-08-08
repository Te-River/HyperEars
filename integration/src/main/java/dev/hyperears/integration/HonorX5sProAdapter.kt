package dev.hyperears.integration

import dev.hyperears.protocol.honor.HonorX5sAtCodec

/**
 * Concrete adapter for the Honor X5s Pro (BTV-ME10).
 *
 * The first release starts from standard capabilities (system aggregate battery, media
 * handoff, native MiLink three-state card) and opens private capabilities only on valid
 * protocol evidence: component battery after a well-formed battery report, noise modes after
 * a well-formed mode-state report (see [batterySourceAfterProtocolEvidence]). Mode writes are
 * confirmed by the earphone's state report (`DEVICE_REPORT`, the adapter base default).
 */
class HonorX5sProAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "荣耀亲选耳机 X5s Pro"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "honor-x5spro-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) == "荣耀亲选耳机x5spro"

    override fun createProtocolSession(): ProtocolSession = HonorX5sProProtocolSession()

    override fun batterySourceAfterProtocolEvidence(): BatterySource = BatterySource.PRIVATE_PROTOCOL

    companion object {
        const val ID = "honor-x5spro"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}

/**
 * Family protocol session over the `5A 00` private RFCOMM SPP channel.
 *
 * The session owns the streaming decoder buffer, the single-worn-eardrum flag and every
 * protocol exchange; the adapter only supplies identity, transport and confirmed capabilities.
 */
private class HonorX5sProProtocolSession : ProtocolSession {
    private val decoder = HonorX5sAtCodec.Decoder()
    private var singleEarbud = false

    override fun initialReadCommands(): List<ByteArray> = listOf(HonorX5sAtCodec.queryBattery)

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> listOf(HonorX5sAtCodec.queryBattery)
        is ControlRequest.SetNoiseMode -> listOf(
            HonorX5sAtCodec.modeCommand(request.mode.toWireMode(), ANC_DEPTH),
        )
    }

    override fun readback(request: ControlRequest): List<ByteArray> = emptyList()

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            if (HonorX5sAtCodec.isHeartbeat(frame)) return@forEach
            HonorX5sAtCodec.parseBatteryFrame(frame)?.let { battery ->
                singleEarbud = battery.leftPercent == null || battery.rightPercent == null
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.BatteryChanged(
                        EarbudBattery(
                            left = BatteryReading(battery.leftPercent, charging = false),
                            right = BatteryReading(battery.rightPercent, charging = false),
                            case = BatteryReading(battery.casePercent, charging = battery.caseCharging),
                        ),
                    ),
                )
                return@forEach
            }
            HonorX5sAtCodec.stateFromFrame(frame, singleEarbud)?.let { state ->
                add(
                    ProtocolEvent.CapabilitiesIdentified(
                        battery = false,
                        noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
                    ),
                )
                add(ProtocolEvent.NoiseModeChanged(state.mode.toDomainMode()))
                return@forEach
            }
        }
    }

    override fun reset() {
        decoder.reset()
        singleEarbud = false
    }

    private fun NoiseMode.toWireMode(): HonorX5sAtCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> HonorX5sAtCodec.NoiseMode.ANC
        NoiseMode.OFF -> HonorX5sAtCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> HonorX5sAtCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> error("The Honor X5s Pro protocol has no wind-noise mode")
    }

    private fun HonorX5sAtCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        HonorX5sAtCodec.NoiseMode.ANC -> NoiseMode.ANC
        HonorX5sAtCodec.NoiseMode.OFF -> NoiseMode.OFF
        HonorX5sAtCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }

    private companion object {
        // ANC commands always request the vendor app's smart level.
        val ANC_DEPTH = HonorX5sAtCodec.AncDepth.SMART
    }
}
