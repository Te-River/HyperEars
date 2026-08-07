package dev.hyperears.protocol.honor

/**
 * Wire codec for the Honor X5s Pro (BTV-ME10) private control protocol.
 *
 * Battery telemetry arrives over the system HFP channel as the AT line
 * `AT+HUAWEIBATTERY=<pair_count>,<idx1>,<val1>,...` where indices 2, 4 and 6
 * carry the left bud, right bud and charging case. Noise modes are written as
 * fixed payloads to three distinct BLE GATT characteristics; the payload table
 * is captured from the device and stays versioned with this codec.
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

    fun modePayload(mode: NoiseMode): ByteArray = when (mode) {
        NoiseMode.ANC -> ANC_PAYLOAD
        NoiseMode.TRANSPARENCY -> TRANSPARENCY_PAYLOAD
        NoiseMode.OFF -> NORMAL_PAYLOAD
    }

    fun noiseModeForPayload(bytes: ByteArray): NoiseMode? = when {
        bytes.contentEquals(ANC_PAYLOAD) -> NoiseMode.ANC
        bytes.contentEquals(TRANSPARENCY_PAYLOAD) -> NoiseMode.TRANSPARENCY
        bytes.contentEquals(NORMAL_PAYLOAD) -> NoiseMode.OFF
        else -> null
    }

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

    private val ANC_PAYLOAD =
        hex("00 9c 00 b5 f3 64 31 4f 2e 91 82 74 4e 1b ef 01 00 3e e7")
    private val TRANSPARENCY_PAYLOAD = hex("00 ff ff aa fd")
    private val NORMAL_PAYLOAD = hex("00 09 00 01 18 14 00 1d 00 00 18 28 00 2d 00 c0 fc")
}
