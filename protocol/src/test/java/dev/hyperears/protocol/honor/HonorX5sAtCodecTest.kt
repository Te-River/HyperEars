package dev.hyperears.protocol.honor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // Single-bud connection still reports left/right; other indices are placeholders.
        val state = HonorX5sAtCodec.parseHuaweiBattery(
            "AT+HUAWEIBATTERY=1,2,100,3,0,5,0,7,0",
        )!!
        assertEquals(100, state.leftPercent)
        assertEquals(null, state.rightPercent)
        assertEquals(null, state.casePercent)
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
    fun exposesTheThreeModePayloads() {
        assertEquals(19, HonorX5sAtCodec.modePayload(HonorX5sAtCodec.NoiseMode.ANC).size)
        assertEquals(5, HonorX5sAtCodec.modePayload(HonorX5sAtCodec.NoiseMode.TRANSPARENCY).size)
        assertEquals(17, HonorX5sAtCodec.modePayload(HonorX5sAtCodec.NoiseMode.OFF).size)
        assertEquals(
            "00 9C 00 B5 F3 64 31 4F 2E 91 82 74 4E 1B EF 01 00 3E E7",
            HonorX5sAtCodec.modePayload(HonorX5sAtCodec.NoiseMode.ANC).hex(),
        )
        assertEquals(
            "00 FF FF AA FD",
            HonorX5sAtCodec.modePayload(HonorX5sAtCodec.NoiseMode.TRANSPARENCY).hex(),
        )
        assertEquals(
            "00 09 00 01 18 14 00 1D 00 00 18 28 00 2D 00 C0 FC",
            HonorX5sAtCodec.modePayload(HonorX5sAtCodec.NoiseMode.OFF).hex(),
        )
    }

    @Test
    fun matchesNotificationPayloadBackToMode() {
        assertEquals(
            HonorX5sAtCodec.NoiseMode.ANC,
            HonorX5sAtCodec.noiseModeForPayload(
                HonorX5sAtCodec.modePayload(HonorX5sAtCodec.NoiseMode.ANC),
            ),
        )
        assertEquals(
            HonorX5sAtCodec.NoiseMode.TRANSPARENCY,
            HonorX5sAtCodec.noiseModeForPayload(
                HonorX5sAtCodec.modePayload(HonorX5sAtCodec.NoiseMode.TRANSPARENCY),
            ),
        )
        assertEquals(
            HonorX5sAtCodec.NoiseMode.OFF,
            HonorX5sAtCodec.noiseModeForPayload(
                HonorX5sAtCodec.modePayload(HonorX5sAtCodec.NoiseMode.OFF),
            ),
        )
        assertNull(HonorX5sAtCodec.noiseModeForPayload(byteArrayOf(0x00, 0x61, 0x01)))
    }

    private fun ByteArray.hex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
