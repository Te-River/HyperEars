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
    fun declaresTheThreeNativeModesWithoutWind() {
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.effectiveSupportedNoiseModes(),
        )
        assertTrue(adapter.effectiveCapabilities().battery)
        assertTrue(adapter.effectiveCapabilities().noiseControl)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
        assertEquals(1, adapter.transports.size)
        val transport = adapter.transports.single() as GattTransportSpec
        assertEquals(3, transport.modeWriteTargets.size)
    }

    @Test
    fun ancCommandTargetsItsDedicatedCharacteristic() {
        val result = adapter.executeControl(ControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(result.accepted)
        assertEquals(1, result.targetedCommands.size)
        assertArrayEquals(
            HonorX5sAtCodec.modePayload(HonorX5sAtCodec.NoiseMode.ANC),
            result.targetedCommands[0].bytes,
        )
        assertEquals("ANC", result.targetedCommands[0].targetId)
        assertTrue(result.stateChanged)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
    }

    @Test
    fun everyModeRoutesToItsOwnTarget() {
        mapOf(
            NoiseMode.ANC to "ANC",
            NoiseMode.TRANSPARENCY to "TRANSPARENCY",
            NoiseMode.OFF to "OFF",
        ).forEach { (mode, expectedTarget) ->
            val result = adapter.executeControl(ControlRequest.SetNoiseMode(mode))
            assertTrue("$mode rejected", result.accepted)
            assertEquals("$mode target", expectedTarget, result.targetedCommands[0].targetId)
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
            "AT+HUAWEIBATTERY=3,2,100,4,88,6,75\r\n".toByteArray(Charsets.US_ASCII),
        )
        val battery = adapter.runtimeState().battery
        assertEquals(100, battery.left.percent)
        assertEquals(88, battery.right.percent)
        assertEquals(75, battery.case.percent)
        assertTrue(result.stateChanged)
    }

    @Test
    fun unknownAtLineIsIgnoredWithoutStateChange() {
        val before = adapter.runtimeState()
        val result = adapter.receive("AT+IPHONEACCEV=1,1,5\r\n".toByteArray(Charsets.US_ASCII))
        assertFalse(result.stateChanged)
        assertEquals(before, adapter.runtimeState())
    }

    private fun identity(name: String): EarbudIdentity =
        EarbudIdentity(deviceName = name, standardHeadset = true)
}
