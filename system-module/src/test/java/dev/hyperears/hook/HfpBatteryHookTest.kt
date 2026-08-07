package dev.hyperears.hook

import dev.hyperears.protocol.honor.HonorX5sAtCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class HfpBatteryHookTest {

    @Test
    fun usAsciiRoundTripPreservesHuaweiBatteryParsing() {
        // Mirrors the hook -> session byte path: US_ASCII encode, then decoder round trip.
        val line = "AT+HUAWEIBATTERY=3,2,100,4,88,6,75\r\n"
        val bytes = line.toByteArray(Charsets.US_ASCII)
        val parsed = HonorX5sAtCodec.parseHuaweiBattery(
            String(bytes, Charsets.US_ASCII),
        )!!
        assertEquals(100, parsed.leftPercent)
        assertEquals(88, parsed.rightPercent)
        assertEquals(75, parsed.casePercent)
    }

    @Test
    fun nonHuaweiAtLinesAreIgnoredByTheForwardingGuard() {
        listOf(
            "AT+IPHONEACCEV=1,1,5",
            "AT+XAPL=ABCD-1234-0100,10",
            "AT+BRSF=1024",
        ).forEach { line ->
            assertEquals(false, HonorX5sAtCodec.isHuaweiBattery(line))
        }
    }
}
