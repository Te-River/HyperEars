package dev.hyperears.protocol.honor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HonorX5sAtCodecTest {

    @Test
    fun parsesFullBatteryReport() {
        val state = HonorX5sAtCodec.parseHuaweiBattery(
            "AT+HUAWEIBATTERY=3,2,100,4,88,6,75",
        )!!
        assertEquals(100, state.leftPercent)
        assertEquals(88, state.rightPercent)
        assertEquals(75, state.casePercent)
    }

    @Test
    fun ignoresPlaceholderIndices() {
        // Vendor frames carry placeholder pairs (idx 3/5/7 = 0) between the real values.
        val state = HonorX5sAtCodec.parseHuaweiBattery(
            "AT+HUAWEIBATTERY=6,2,100,3,0,4,100,5,0,6,71,7,0",
        )!!
        assertEquals(100, state.leftPercent)
        assertEquals(100, state.rightPercent)
        assertEquals(71, state.casePercent)
    }

    @Test
    fun rejectsUnknownAndMalformedCommands() {
        assertNull(HonorX5sAtCodec.parseHuaweiBattery("AT+IPHONEACCEV=1,1,5"))
        assertNull(HonorX5sAtCodec.parseHuaweiBattery("AT+HUAWEIBATTERY="))
        assertNull(HonorX5sAtCodec.parseHuaweiBattery("AT+HUAWEIBATTERY=1,2,999"))
    }

    @Test
    fun detectsHuaweiBatteryLine() {
        assertEquals(true, HonorX5sAtCodec.isHuaweiBattery("AT+HUAWEIBATTERY=3,2,100,4,100,6,75\r\n"))
        assertEquals(false, HonorX5sAtCodec.isHuaweiBattery("AT+IPHONEACCEV=1,1,5"))
    }

    @Test
    fun modeCommandsMatchCapturedVendorFrames() {
        assertEquals(
            "5A 00 07 00 2B 04 01 02 01 00 E1 1C",
            HonorX5sAtCodec.modeCommand(HonorX5sAtCodec.NoiseMode.ANC).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 02 00 B4 4F",
            HonorX5sAtCodec.modeCommand(HonorX5sAtCodec.NoiseMode.TRANSPARENCY).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 00 00 D2 2D",
            HonorX5sAtCodec.modeCommand(HonorX5sAtCodec.NoiseMode.OFF).hex(),
        )
    }

    @Test
    fun ancDepthCommandsMatchCapturedVendorFrames() {
        assertEquals(
            "5A 00 07 00 2B 04 01 02 01 01 F1 3D",
            HonorX5sAtCodec.modeCommand(
                HonorX5sAtCodec.NoiseMode.ANC,
                HonorX5sAtCodec.AncDepth.SMART,
            ).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 01 02 C1 5E",
            HonorX5sAtCodec.modeCommand(
                HonorX5sAtCodec.NoiseMode.ANC,
                HonorX5sAtCodec.AncDepth.LIGHT,
            ).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 01 03 D1 7F",
            HonorX5sAtCodec.modeCommand(
                HonorX5sAtCodec.NoiseMode.ANC,
                HonorX5sAtCodec.AncDepth.MEDIUM,
            ).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 01 00 E1 1C",
            HonorX5sAtCodec.modeCommand(
                HonorX5sAtCodec.NoiseMode.ANC,
                HonorX5sAtCodec.AncDepth.DEEP,
            ).hex(),
        )
    }

    @Test
    fun stateFramesDecodeDepthAndMode() {
        assertEquals(
            HonorX5sAtCodec.State(HonorX5sAtCodec.NoiseMode.ANC, HonorX5sAtCodec.AncDepth.SMART),
            HonorX5sAtCodec.stateFromFrame(hex("5A 00 07 00 2B 2A 01 02 01 01 36 21")),
        )
        assertEquals(
            HonorX5sAtCodec.State(HonorX5sAtCodec.NoiseMode.ANC, HonorX5sAtCodec.AncDepth.LIGHT),
            HonorX5sAtCodec.stateFromFrame(hex("5A 00 07 00 2B 2A 01 02 01 02 63 72")),
        )
        assertEquals(
            HonorX5sAtCodec.State(HonorX5sAtCodec.NoiseMode.ANC, HonorX5sAtCodec.AncDepth.MEDIUM),
            HonorX5sAtCodec.stateFromFrame(hex("5A 00 07 00 2B 2A 01 02 01 03 50 43")),
        )
        assertEquals(
            HonorX5sAtCodec.State(HonorX5sAtCodec.NoiseMode.ANC, HonorX5sAtCodec.AncDepth.DEEP),
            HonorX5sAtCodec.stateFromFrame(hex("5A 00 07 00 2B 2A 01 02 01 00 05 10")),
        )
        assertEquals(
            HonorX5sAtCodec.State(HonorX5sAtCodec.NoiseMode.TRANSPARENCY, null),
            HonorX5sAtCodec.stateFromFrame(hex("5A 00 07 00 2B 2A 01 02 00 02 35 73")),
        )
        assertEquals(
            HonorX5sAtCodec.State(HonorX5sAtCodec.NoiseMode.OFF, null),
            HonorX5sAtCodec.stateFromFrame(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31")),
        )
        // Connect-init frame (0x00, 0x01) and post-deep-command frames decode as deep ANC.
        assertEquals(
            HonorX5sAtCodec.State(HonorX5sAtCodec.NoiseMode.ANC, HonorX5sAtCodec.AncDepth.DEEP),
            HonorX5sAtCodec.stateFromFrame(hex("5A 00 07 00 2B 2A 01 02 00 01 05 10")),
        )
    }

    @Test
    fun stateFramesDecodeBackToModes() {
        assertEquals(
            HonorX5sAtCodec.NoiseMode.ANC,
            HonorX5sAtCodec.modeFromStateFrame(hex("5A 00 07 00 2B 2A 01 02 00 01 05 10")),
        )
        assertEquals(
            HonorX5sAtCodec.NoiseMode.TRANSPARENCY,
            HonorX5sAtCodec.modeFromStateFrame(hex("5A 00 07 00 2B 2A 01 02 00 02 35 73")),
        )
        assertEquals(
            HonorX5sAtCodec.NoiseMode.OFF,
            HonorX5sAtCodec.modeFromStateFrame(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31")),
        )
        assertNull(HonorX5sAtCodec.modeFromStateFrame(hex("5A 00 05 00 2B 79 01 00 45 E0")))
        assertNull(HonorX5sAtCodec.modeFromStateFrame(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun heartbeatFramesAreRecognized() {
        assertTrue(HonorX5sAtCodec.isHeartbeat(hex("5A 00 05 00 2B 79 01 00 45 E0")))
        assertFalse(HonorX5sAtCodec.isHeartbeat(hex("5A 00 07 00 2B 04 01 02 01 00 E1 1C")))
        assertFalse(HonorX5sAtCodec.isHeartbeat(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun batteryReportFramesDecodeComponents() {
        // Captured 15:53: case 71, buds 100/100 (0x47 0x64 0x64).
        val report = HonorX5sAtCodec.parseBatteryFrame(
            hex("5A 00 10 00 01 27 01 01 47 02 03 64 64 47 03 03 64 64 00 EF 9F"),
        )!!
        assertEquals(100, report.leftPercent)
        assertEquals(100, report.rightPercent)
        assertEquals(71, report.casePercent)

        // Captured 17:26: case 70, buds 100/100 (0x46 0x64 0x64).
        val report2 = HonorX5sAtCodec.parseBatteryFrame(
            hex("5A 00 10 00 01 08 01 01 46 02 03 64 64 46 03 03 64 64 00 74 D4"),
        )!!
        assertEquals(100, report2.leftPercent)
        assertEquals(100, report2.rightPercent)
        assertEquals(70, report2.casePercent)
    }

    @Test
    fun batteryQueryMatchesCapturedVendorFrame() {
        assertEquals(
            "5A 00 09 00 01 08 01 00 02 00 03 00 FB B9",
            HonorX5sAtCodec.queryBattery.hex(),
        )
        assertEquals(
            null,
            HonorX5sAtCodec.parseBatteryFrame(hex("5A 00 07 00 2B 2A 01 02 01 00 05 10")),
        )
    }

    private fun hex(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.hex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
