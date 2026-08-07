package dev.hyperears.integration

import dev.hyperears.protocol.honor.HonorX5sAtCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HonorX5sProAdapterTest {

    private val adapter = HonorX5sProAdapter()

    @Test
    fun registryResolvesByNormalizedDeviceName() {
        val identity = EarbudIdentity(
            deviceName = "荣耀亲选耳机X5s Pro",
            standardHeadset = true,
        )
        assertTrue(EarbudAdapterRegistry.resolve(identity) is HonorX5sProAdapter)
        // Unknown devices keep the standard fallback and never reach the Honor adapter.
        assertFalse(
            EarbudAdapterRegistry.resolve(identity("AirPods Pro")) is HonorX5sProAdapter,
        )
    }

    @Test
    fun declaresSppTransportAndSupportedModes() {
        // Like the OPPO and vivo adapters, only MiLink's native three-state ANC is exposed.
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.effectiveSupportedNoiseModes(),
        )
        assertTrue(adapter.effectiveCapabilities().battery)
        assertTrue(adapter.effectiveCapabilities().noiseControl)
        // SPP battery report frames carry component levels; the system HFP aggregate is a fallback.
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
        val transport = adapter.transports.single() as RfcommEndpointSpec.ServiceUuid
        assertEquals(HonorX5sProAdapter.SPP_UUID, transport.uuid)
    }

    @Test
    fun ancCommandUsesCapturedVendorFrame() {
        val result = adapter.executeControl(ControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(result.accepted)
        assertEquals(1, result.commands.size)
        assertArrayEquals(
            // Default depth is smart until the earphone reports its own state.
            HonorX5sAtCodec.modeCommand(
                HonorX5sAtCodec.NoiseMode.ANC,
                HonorX5sAtCodec.AncDepth.SMART,
            ),
            result.commands[0],
        )
        assertTrue(result.stateChanged)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
    }

    @Test
    fun everyModeEncodesToItsCapturedCommand() {
        mapOf(
            NoiseMode.ANC to HonorX5sAtCodec.NoiseMode.ANC,
            NoiseMode.TRANSPARENCY to HonorX5sAtCodec.NoiseMode.TRANSPARENCY,
            NoiseMode.OFF to HonorX5sAtCodec.NoiseMode.OFF,
        ).forEach { (mode, wireMode) ->
            val result = adapter.executeControl(ControlRequest.SetNoiseMode(mode))
            assertTrue("$mode rejected", result.accepted)
            assertArrayEquals(
                "captured command for $mode",
                when (mode) {
                    NoiseMode.ANC -> HonorX5sAtCodec.modeCommand(
                        wireMode,
                        HonorX5sAtCodec.AncDepth.SMART,
                    )
                    else -> HonorX5sAtCodec.modeCommand(wireMode)
                },
                result.commands[0],
            )
        }
    }

    @Test
    fun sppBatteryReportProducesComponentBattery() {
        val result = adapter.receive(hex("5A 00 10 00 01 27 01 01 47 02 03 64 64 47 03 03 64 64 00 EF 9F"))
        val battery = adapter.runtimeState().battery
        assertEquals(100, battery.left.percent)
        assertEquals(100, battery.right.percent)
        assertEquals(71, battery.case.percent)
        assertTrue(result.stateChanged)
    }

    @Test
    fun refreshEncodesBatteryQuery() {
        val result = adapter.executeControl(ControlRequest.Refresh)
        assertTrue(result.accepted)
        assertArrayEquals(HonorX5sAtCodec.queryBattery, result.commands[0])
    }

    @Test
    fun hfpAtBatteryLineProducesBatteryEvent() {
        val result = adapter.receive(
            "AT+HUAWEIBATTERY=6,2,100,3,0,4,100,5,0,6,71,7,0\r\n".toByteArray(Charsets.US_ASCII),
        )
        val battery = adapter.runtimeState().battery
        assertEquals(100, battery.left.percent)
        assertEquals(100, battery.right.percent)
        assertEquals(71, battery.case.percent)
        assertTrue(result.stateChanged)
    }

    @Test
    fun stateFrameUpdatesNoiseMode() {
        val result = adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 01 05 10"))
        assertTrue(result.stateChanged)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)

        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 02 35 73"))
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)

        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"))
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
    }

    @Test
    fun ancCommandAlwaysUsesSmartDepth() {
        // Even when the earphone reports a non-smart depth, ANC commands stay on the smart level
        // so the card never overrides the depth chosen in the vendor app.
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 01 02 63 72"))
        val result = adapter.executeControl(ControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(result.accepted)
        assertArrayEquals(
            HonorX5sAtCodec.modeCommand(
                HonorX5sAtCodec.NoiseMode.ANC,
                HonorX5sAtCodec.AncDepth.SMART,
            ),
            result.commands[0],
        )
    }

    @Test
    fun windNoiseModeIsRejected() {
        val result = adapter.executeControl(ControlRequest.SetNoiseMode(NoiseMode.WIND))
        assertFalse(result.accepted)
    }

    @Test
    fun heartbeatFrameIsIgnoredWithoutStateChange() {
        val before = adapter.runtimeState()
        val result = adapter.receive(hex("5A 00 05 00 2B 79 01 00 45 E0"))
        assertFalse(result.stateChanged)
        assertEquals(before, adapter.runtimeState())
    }

    private fun hex(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun identity(name: String): EarbudIdentity =
        EarbudIdentity(deviceName = name, standardHeadset = true)
}
