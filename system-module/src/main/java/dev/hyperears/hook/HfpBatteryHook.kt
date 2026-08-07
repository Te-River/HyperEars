package dev.hyperears.hook

import android.bluetooth.BluetoothDevice
import android.os.Message
import dev.hyperears.protocol.honor.HonorX5sAtCodec
import dev.hyperears.runtime.EarbudSessionService
import java.lang.reflect.Method

/**
 * Captures Honor battery reports that arrive over the system HFP channel.
 *
 * `HeadsetStateMachine.processBatteryLevel` is the AOSP entry for HF-originated battery AT
 * lines (AT+IPHONEACCEV and vendor variants). The probe fallback logs every String-parameter
 * invocation that carries an AT line, so a ROM with a renamed entry point can be mapped from
 * module logs before the primary hook target is adjusted.
 */
internal class HfpBatteryHook : HookContext() {

    override fun install() {
        runCatching {
            hookAfter(
                findMethod(
                    "com.android.bluetooth.hfp.HeadsetStateMachine",
                    "processBatteryLevel",
                    String::class.java,
                ),
            ) {
                val atString = args[0] as? String ?: return@hookAfter
                if (!HonorX5sAtCodec.isHuaweiBattery(atString)) return@hookAfter
                val device = runCatching {
                    getObjectField(instance, "mDevice") as? BluetoothDevice
                }.getOrNull() ?: return@hookAfter
                forward(device, atString)
            }
            ModuleLog.debug("Hfp", "battery AT hook installed on processBatteryLevel")
        }.onFailure {
            ModuleLog.warn("Hfp", "processBatteryLevel hook unavailable", it)
            installProbeFallback()
        }
    }

    private fun forward(device: BluetoothDevice, atString: String) {
        EarbudSessionService.onHfpAtReport(device, atString.toByteArray(Charsets.US_ASCII))
        ModuleLog.debug("Hfp", "forwarded Huawei battery AT report")
    }

    /**
     * Logs every candidate invocation that carries an AT line so a renamed ROM entry point can be
     * identified from module logs. AT payloads may arrive as String, byte[] or Message.obj, so the
     * probe covers all three shapes; the primary hook is preferred on AOSP. The overridden `log`
     * method is dumped verbatim because ROMs often trace AT processing through it.
     */
    private fun installProbeFallback() {
        var installed = 0
        listOf("com.android.bluetooth.hfp.HeadsetStateMachine", "com.android.bluetooth.hfp.HeadsetService")
            .forEach { className ->
                val methods = runCatching {
                    findClass(className).declaredMethods
                }.getOrElse {
                    ModuleLog.warn("Hfp", "$className unavailable for probe", it)
                    return@forEach
                }
                methods.filter { method ->
                    method.parameterTypes.any { parameter ->
                        parameter == String::class.java ||
                            parameter == ByteArray::class.java ||
                            Message::class.java.isAssignableFrom(parameter)
                    }
                }.forEach { method: Method ->
                    runCatching {
                        hookAfter(method) {
                            ModuleLog.debug("Hfp", "probe method ${method.name} invoked")
                            val text = args.joinToString("|") { arg ->
                                when (arg) {
                                    is String -> arg
                                    is ByteArray -> String(arg, Charsets.US_ASCII)
                                    is Message -> arg.obj?.toString().orEmpty()
                                    else -> arg?.toString().orEmpty()
                                }
                            }
                            if (method.name == "log") {
                                ModuleLog.debug("Hfp", "probe log text=${text.take(MAX_LOG_CHARS)}")
                            }
                            if (text.contains(AT_MARKER) ||
                                text.contains(BATTERY_MARKER, ignoreCase = true)
                            ) {
                                ModuleLog.debug("Hfp", "probe ${method.name} AT=$text")
                                if (HonorX5sAtCodec.isHuaweiBattery(text)) {
                                    val device = runCatching {
                                        getObjectField(instance, "mDevice") as? BluetoothDevice
                                    }.getOrNull()
                                    if (device != null) forward(device, text)
                                }
                            }
                        }
                        installed += 1
                    }.onFailure {
                        ModuleLog.debug("Hfp", "probe ${method.name} unavailable")
                    }
                }
            }
        ModuleLog.debug("Hfp", "probe fallback installed on $installed methods")
    }

    private companion object {
        const val AT_MARKER = "AT+"
        const val BATTERY_MARKER = "BATTERY"
        const val MAX_LOG_CHARS = 200
    }
}
