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

    /** ANC strength captured from the vendor app: smart / light / medium / deep (00 = deepest). */
    enum class AncDepth(val wire: Int) {
        DEEP(0x00),
        SMART(0x01),
        LIGHT(0x02),
        MEDIUM(0x03),
    }

    data class BatteryState(
        val leftPercent: Int?,
        val rightPercent: Int?,
        val casePercent: Int?,
    )

    /** Decoded mode-state frame content; depth is absent for transparency and off. */
    data class State(
        val mode: NoiseMode,
        val depth: AncDepth?,
    )

    fun isHuaweiBattery(line: String): Boolean =
        line.trim().startsWith(AT_PREFIX)

    /** Captured vendor battery query sent on the SPP channel. */
    val queryBattery: ByteArray = hex("5A 00 09 00 01 08 01 00 02 00 03 00 FB B9")

    /**
     * Decodes an SPP battery report frame
     * `5A 00 10 00 01 27/08 01 01 <case> 02 03 <left> <right> <case> 03 03 <left> <right> 00 <crc>`.
     * The frame is both pushed by the earphone (command 0x27) and returned for the query (0x08);
     * the checksum is opaque and not validated.
     */
    fun parseBatteryFrame(bytes: ByteArray): BatteryState? {
        if (bytes.size != BATTERY_FRAME_SIZE) return null
        if (bytes[0] != 0x5A.toByte() || bytes[1] != 0x00.toByte()) return null
        if (bytes[4] != 0x01.toByte()) return null
        val command = bytes[5].unsigned()
        if (command != 0x27 && command != 0x08) return null
        if (bytes[6] != 0x01.toByte() || bytes[7] != 0x01.toByte()) return null
        if (bytes[9] != 0x02.toByte() || bytes[10] != 0x03.toByte()) return null
        val case = bytes[8].unsigned().percentOrNull() ?: return null
        val left = bytes[11].unsigned().percentOrNull() ?: return null
        val right = bytes[12].unsigned().percentOrNull() ?: return null
        return BatteryState(left, right, case)
    }

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

    /**
     * Captured vendor ANC command frame: `2B 04 01 02 <mode> <depth> <crc>`, where the
     * depth byte applies to ANC and stays 0x00 for transparency and off. Defaults to
     * [AncDepth.DEEP] (the "deep" level), matching the vendor app's default.
     */
    fun modeCommand(mode: NoiseMode, depth: AncDepth = AncDepth.DEEP): ByteArray = when (mode) {
        NoiseMode.ANC -> hex("5A 00 07 00 2B 04 01 02 01 ${depth.wire.toString(16).padStart(2, '0')}")
            .let { prefix -> prefix + requireNotNull(COMMAND_CRC[1 to depth]) }
        NoiseMode.TRANSPARENCY -> hex("5A 00 07 00 2B 04 01 02 02 00 B4 4F")
        NoiseMode.OFF -> hex("5A 00 07 00 2B 04 01 02 00 00 D2 2D")
    }

    /**
     * Decodes the earphone's state frame `2B 2A 01 02 <x> <y> <crc>`: x=0x01 marks ANC active
     * with y carrying the depth; otherwise y carries the mode (0x00=off, 0x02=transparency).
     * The trailing checksum is opaque and not validated.
     */
    fun stateFromFrame(bytes: ByteArray): State? {
        if (bytes.size != STATE_FRAME_SIZE) return null
        if (!bytes.copyOfRange(0, STATE_FRAME_PREFIX_SIZE).contentEquals(STATE_FRAME_PREFIX)) {
            return null
        }
        val x = bytes[STATE_FRAME_X_OFFSET].unsigned()
        val y = bytes[STATE_FRAME_Y_OFFSET].unsigned()
        if (x == ANC_MARKER) {
            val depth = AncDepth.entries.firstOrNull { it.wire == y } ?: return null
            return State(NoiseMode.ANC, depth)
        }
        return when (y) {
            // Captured on connect-init: the earphone reports smart ANC as (0x00, 0x01).
            0x01 -> State(NoiseMode.ANC, AncDepth.SMART)
            0x02 -> State(NoiseMode.TRANSPARENCY, null)
            0x00 -> State(NoiseMode.OFF, null)
            else -> null
        }
    }

    /** Mode component of a state frame; kept for sessions that only track the mode. */
    fun modeFromStateFrame(bytes: ByteArray): NoiseMode? = stateFromFrame(bytes)?.mode

    /** 3-second vendor keepalive; must not be surfaced as an unknown frame. */
    fun isHeartbeat(bytes: ByteArray): Boolean = bytes.contentEquals(HEARTBEAT_FRAME)

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private fun Int.percentOrNull(): Int? = takeIf { it in 0..100 }

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
    private const val STATE_FRAME_PREFIX_SIZE = 8
    private const val STATE_FRAME_X_OFFSET = 8
    private const val STATE_FRAME_Y_OFFSET = 9
    private const val ANC_MARKER = 0x01
    private const val BATTERY_FRAME_SIZE = 21

    private val STATE_FRAME_PREFIX = hex("5A 00 07 00 2B 2A 01 02")
    private val HEARTBEAT_FRAME = hex("5A 00 05 00 2B 79 01 00 45 E0")

    private val COMMAND_CRC = mapOf(
        (1 to AncDepth.DEEP) to hex("E1 1C"),
        (1 to AncDepth.SMART) to hex("F1 3D"),
        (1 to AncDepth.LIGHT) to hex("C1 5E"),
        (1 to AncDepth.MEDIUM) to hex("D1 7F"),
    )
}
