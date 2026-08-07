package dev.hyperears.integration

import dev.hyperears.protocol.honor.HonorX5sAtCodec

/**
 * Concrete adapter for the Honor X5s Pro (BTV-ME10).
 *
 * Battery telemetry arrives out-of-band through the system HFP channel (`AT+HUAWEIBATTERY`)
 * and is injected into the session by the HFP hook; noise modes are written as fixed payloads
 * to three distinct GATT characteristics declared in [GattTransportSpec.modeWriteTargets].
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
        GattTransportSpec(
            writeCharacteristicUuid = DEFAULT_WRITE_UUID,
            notifyCharacteristicUuid = NOTIFY_UUID,
            modeWriteTargets = mapOf(
                NoiseMode.ANC to GattWriteTarget(ANC_WRITE_UUID),
                NoiseMode.TRANSPARENCY to GattWriteTarget(TRANSPARENCY_WRITE_UUID),
                NoiseMode.OFF to GattWriteTarget(NORMAL_WRITE_UUID),
            ),
            id = "honor-x5spro-gatt",
        ),
    )

    override fun createProtocolSession(): ProtocolSession = HonorX5sProProtocolSession()

    companion object {
        const val ID = "honor-x5spro"

        // UUIDs are placeholders pending GATT service-table capture. The captured attribute
        // handles are 0x9A14 (ANC), 0x9D06 (transparency) and 0x0106 (normal); the runtime
        // resolves characteristics by UUID and never by handle.
        const val DEFAULT_WRITE_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"
        const val ANC_WRITE_UUID = "0000FFF2-0000-1000-8000-00805F9B34FB"
        const val TRANSPARENCY_WRITE_UUID = "0000FFF3-0000-1000-8000-00805F9B34FB"
        const val NORMAL_WRITE_UUID = "0000FFF4-0000-1000-8000-00805F9B34FB"
        const val NOTIFY_UUID = "0000FFF5-0000-1000-8000-00805F9B34FB"
    }
}

private class HonorX5sProProtocolSession : TargetedProtocolSession {

    override fun initialReadCommands(): List<ByteArray> = emptyList()

    override fun encode(request: ControlRequest): List<ByteArray> = emptyList()

    override fun encodeTargeted(request: ControlRequest): List<TargetedCommand> = when (request) {
        ControlRequest.Refresh -> emptyList()
        is ControlRequest.SetNoiseMode -> listOf(
            TargetedCommand(
                bytes = HonorX5sAtCodec.modePayload(request.mode.toWireMode()),
                targetId = request.mode.name,
            ),
        )
    }

    override fun readback(request: ControlRequest): List<ByteArray> = emptyList()

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
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
        HonorX5sAtCodec.noiseModeForPayload(bytes)?.let { mode ->
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
