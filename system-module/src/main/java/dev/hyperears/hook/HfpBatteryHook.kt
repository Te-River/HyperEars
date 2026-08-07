package dev.hyperears.hook

import android.bluetooth.BluetoothDevice
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
     * Logs every String-parameter invocation that carries an AT line so a renamed ROM entry
     * point can be identified from module logs; the primary hook is preferred on AOSP.
     */
    private fun installProbeFallback() {
        val methods = runCatching {
            findClass("com.android.bluetooth.hfp.HeadsetStateMachine").declaredMethods
        }.getOrElse {
            ModuleLog.warn("Hfp", "HeadsetStateMachine unavailable for probe", it)
            return
        }
        var installed = 0
        methods.filter { method ->
            method.parameterTypes.any { it == String::class.java } &&
                method.returnType == Void.TYPE
        }.forEach { method: Method ->
            runCatching {
                hookAfter(method) {
                    args.filterIsInstance<String>()
                        .filter { it.contains(AT_MARKER) }
                        .forEach { line ->
                            ModuleLog.debug("Hfp", "probe ${method.name} AT=$line")
                            if (HonorX5sAtCodec.isHuaweiBattery(line)) {
                                val device = runCatching {
                                    getObjectField(instance, "mDevice") as? BluetoothDevice
                                }.getOrNull()
                                if (device != null) forward(device, line)
                            }
                        }
                }
                installed += 1
            }.onFailure {
                ModuleLog.debug("Hfp", "probe ${method.name} unavailable")
            }
        }
        ModuleLog.debug("Hfp", "probe fallback installed on $installed methods")
    }

    private companion object {
        const val AT_MARKER = "AT+"
    }
}
