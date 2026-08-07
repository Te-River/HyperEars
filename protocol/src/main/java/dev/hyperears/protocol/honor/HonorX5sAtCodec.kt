package dev.hyperears.protocol.honor

/**
 * Wire codec for the Honor X5s Pro (BTV-ME10) private control protocol.
 *
 * Battery telemetry arrives over the system HFP channel as the AT line
 * `AT+HUAWEIBATTERY=<pair_count>,<idx1>,<val1>,...` where indices 2, 4 and 6
 * carry the left bud, right bud and charging case. Noise modes are controlled
 * through the device's private RFCOMM SPP channel with `5A 00` framed commands
 * captured from the vendor app; the command and state frames stay versioned here.
 */
object HonorX5sAtCodec {
    enum class NoiseMode {
        ANC,
        TRANSPARENCY,
        OFF,
    }

    data class BatteryState(
        val leftPercent: Int?,
        val rightPercent: Int?,
        val casePercent: Int?,
    )

    fun isHuaweiBattery(line: String): Boolean =
        line.trim().startsWith(AT_PREFIX)

    fun parseHuaweiBattery(line: String): BatteryState? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(AT_PREFIX)) return null
        val tokens = trimmed.removePrefix(AT_PREFIX).split(',')
        if (tokens.firstOrNull()?.toIntOrNull() == null) return null
        val pairs = tokens.drop(1)
        if (pairs.size % 2 != 0) return null

        var left: Int? = null
        var right: Int? = null
        var case: Int? = null
        pairs.chunked(2).forEach { (indexToken, valueToken) ->
            val index = indexToken.toIntOrNull() ?: return@forEach
            val value = valueToken.toIntOrNull() ?: return@forEach
            if (value !in 0..100) return@forEach
            when (index) {
                INDEX_LEFT -> left = value
                INDEX_RIGHT -> right = value
                INDEX_CASE -> case = value
            }
        }
        if (left == null && right == null && case == null) return null
        return BatteryState(left, right, case)
    }

    /** Captured vendor ANC-set command frame for each mode. */
    fun modeCommand(mode: NoiseMode): ByteArray = when (mode) {
        NoiseMode.ANC -> hex("5A 00 07 00 2B 04 01 02 01 00 E1 1C")
        NoiseMode.TRANSPARENCY -> hex("5A 00 07 00 2B 04 01 02 02 00 B4 4F")
        NoiseMode.OFF -> hex("5A 00 07 00 2B 04 01 02 00 00 D2 2D")
    }

    /**
     * Decodes the earphone's mode-state frame `5A 00 07 00 2B 2A 01 02 00 <mode> <crc>`.
     * The trailing checksum is opaque and not validated; the mode byte is authoritative.
     */
    fun modeFromStateFrame(bytes: ByteArray): NoiseMode? {
        if (bytes.size != STATE_FRAME_SIZE) return null
        if (!bytes.copyOfRange(0, STATE_FRAME_PREFIX_SIZE).contentEquals(STATE_FRAME_PREFIX)) {
            return null
        }
        return when (bytes[STATE_FRAME_MODE_OFFSET]) {
            0x01.toByte() -> NoiseMode.ANC
            0x02.toByte() -> NoiseMode.TRANSPARENCY
            0x00.toByte() -> NoiseMode.OFF
            else -> null
        }
    }

    /** 3-second vendor keepalive; must not be surfaced as an unknown frame. */
    fun isHeartbeat(bytes: ByteArray): Boolean = bytes.contentEquals(HEARTBEAT_FRAME)

    private fun hex(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private const val AT_PREFIX = "AT+HUAWEIBATTERY="
    private const val INDEX_LEFT = 2
    private const val INDEX_RIGHT = 4
    private const val INDEX_CASE = 6
    private const val STATE_FRAME_SIZE = 12
    private const val STATE_FRAME_PREFIX_SIZE = 9
    private const val STATE_FRAME_MODE_OFFSET = 9

    private val STATE_FRAME_PREFIX = hex("5A 00 07 00 2B 2A 01 02 00")
    private val HEARTBEAT_FRAME = hex("5A 00 05 00 2B 79 01 00 45 E0")
}
