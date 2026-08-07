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
    fun declaresSppTransportAndThreeModes() {
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.effectiveSupportedNoiseModes(),
        )
        assertTrue(adapter.effectiveCapabilities().battery)
        assertTrue(adapter.effectiveCapabilities().noiseControl)
        // System parses the standard HFP IPHONEACCEV report into RemoteDevices; component-level
        // HUAWEIBATTERY values are consumed by the vendor app's private channel and stay unavailable.
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        val transport = adapter.transports.single() as RfcommEndpointSpec.ServiceUuid
        assertEquals(HonorX5sProAdapter.SPP_UUID, transport.uuid)
    }

    @Test
    fun ancCommandUsesCapturedVendorFrame() {
        val result = adapter.executeControl(ControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(result.accepted)
        assertEquals(1, result.commands.size)
        assertArrayEquals(
            HonorX5sAtCodec.modeCommand(HonorX5sAtCodec.NoiseMode.ANC),
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
                HonorX5sAtCodec.modeCommand(wireMode),
                result.commands[0],
            )
        }
    }

    @Test
    fun windNoiseModeIsRejected() {
        val result = adapter.executeControl(ControlRequest.SetNoiseMode(NoiseMode.WIND))
        assertFalse(result.accepted)
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
