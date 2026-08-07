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

    private fun hex(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.hex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
