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
    fun matchesRejectsNonStandardHeadset() {
        // System-native earbuds and non-headset profiles stay out of the adapter chain.
        val systemNative = EarbudIdentity(
            deviceName = "荣耀亲选耳机X5s Pro",
            standardHeadset = true,
            nativeSystemEarbud = true,
        )
        assertFalse(adapter.matches(systemNative))
        val nonStandard = EarbudIdentity(
            deviceName = "荣耀亲选耳机X5s Pro",
            standardHeadset = false,
        )
        assertFalse(adapter.matches(nonStandard))
    }

    @Test
    fun declaresSppTransportAndInitialStandardCapabilities() {
        val transport = adapter.transports.single() as RfcommEndpointSpec.ServiceUuid
        assertEquals(HonorX5sProAdapter.SPP_UUID, transport.uuid)
        // Private capabilities open only on valid protocol evidence.
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertTrue(adapter.effectiveSupportedNoiseModes().isEmpty())
    }

    @Test
    fun batteryEvidencePromotesToPrivateSource() {
        adapter.receive(hex("5A 00 10 00 01 27 01 01 47 02 03 64 64 47 03 03 64 64 00 EF 9F"))
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
        assertTrue(adapter.effectiveCapabilities().battery)
    }

    @Test
    fun stateFrameEvidenceOpensNoiseModes() {
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"))
        assertTrue(adapter.effectiveCapabilities().noiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.effectiveSupportedNoiseModes(),
        )
    }

    @Test
    fun ancCommandAlwaysUsesSmartDepth() {
        confirmNoiseModes()
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
    fun everyModeEncodesToItsCapturedCommand() {
        confirmNoiseModes()
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
    fun windNoiseModeIsRejected() {
        val result = adapter.executeControl(ControlRequest.SetNoiseMode(NoiseMode.WIND))
        assertFalse(result.accepted)
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
    fun zeroPercentBatteryComponentIsNotConnected() {
        val result = adapter.receive(hex("5A 00 10 00 01 27 01 01 47 02 03 00 64 47 03 03 64 64 00 EF 9F"))
        val battery = adapter.runtimeState().battery
        assertEquals(null, battery.left.percent)
        assertEquals(100, battery.right.percent)
        assertEquals(71, battery.case.percent)
        assertTrue(result.stateChanged)
    }

    @Test
    fun chargingCaseFlagReachesBatteryState() {
        adapter.receive(hex("5A 00 10 00 01 27 01 01 49 02 03 64 64 49 03 03 64 64 01 2C 67"))
        val battery = adapter.runtimeState().battery
        assertEquals(73, battery.case.percent)
        assertTrue(battery.case.charging)
    }

    @Test
    fun singleEarbudOffStateIsNotMistakenForAnc() {
        // Battery report marks single-eardrum mode (left side 0), so (0x01, 0x00) decodes as off.
        adapter.receive(hex("5A 00 10 00 01 27 01 01 00 02 03 00 49 00 03 03 64 64 00 7F 36"))
        val result = adapter.receive(hex("5A 00 07 00 2B 2A 01 02 01 00 26 00"))
        assertTrue(result.stateChanged)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
    }

    @Test
    fun stateFrameUpdatesNoiseMode() {
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 01 05 10"))
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)

        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 02 35 73"))
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)

        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"))
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
    }

    @Test
    fun partialFrameIsBufferedAcrossOffers() {
        val full = hex("5A 00 07 00 2B 2A 01 02 01 01 36 21")
        adapter.receive(full.copyOfRange(0, 5))
        assertEquals(null, adapter.runtimeState().noiseMode)
        val result = adapter.receive(full.copyOfRange(5, full.size))
        assertTrue(result.stateChanged)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
    }

    @Test
    fun heartbeatFrameIsIgnoredWithoutStateChange() {
        val before = adapter.runtimeState()
        val result = adapter.receive(hex("5A 00 05 00 2B 79 01 00 45 E0"))
        assertFalse(result.stateChanged)
        assertEquals(before, adapter.runtimeState())
    }

    @Test
    fun refreshEncodesBatteryQuery() {
        val result = adapter.executeControl(ControlRequest.Refresh)
        assertTrue(result.accepted)
        assertArrayEquals(HonorX5sAtCodec.queryBattery, result.commands[0])
    }

    @Test
    fun controlRejectedBeforeNoiseEvidence() {
        val result = adapter.executeControl(ControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertFalse(result.accepted)
    }

    private fun confirmNoiseModes() {
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"))
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
