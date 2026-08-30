package com.jakliuyuy.batterypower.battery

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * root 通道：直接读取内核电池节点，精度最高。
 * 所有读取带超时 + 静默降级，任何异常返回 null。
 */
object RootHelper {

    data class BatteryRaw(
        val currentUa: Int,   // 微安，正值充电 负值放电
        val voltageUv: Int,   // 微伏
        val capacity: Int     // 百分比
    )

    private const val CURRENT_PATH = "/sys/class/power_supply/battery/current_now"
    private const val VOLTAGE_PATH = "/sys/class/power_supply/battery/voltage_now"
    private const val CAPACITY_PATH = "/sys/class/power_supply/battery/capacity"

    /** 尝试 su 读取；无 root 或失败返回 null */
    fun read(): BatteryRaw? {
        return try {
            val out = exec("cat $CURRENT_PATH; cat $VOLTAGE_PATH; cat $CAPACITY_PATH") ?: return null
            val lines = out.lines().filter { it.isNotBlank() && it.all { c -> c.isDigit() || c == '-' } }
            if (lines.size < 3) return null
            BatteryRaw(
                currentUa = lines[0].toIntOrNull() ?: return null,
                voltageUv = lines[1].toIntOrNull() ?: return null,
                capacity = lines[2].toIntOrNull() ?: return null
            )
        } catch (e: Exception) {
            null
        }
    }

    fun isAvailable(): Boolean = read() != null

    private fun exec(cmd: String): String? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText().trim()
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroy()
                null
            } else out
        } catch (e: Exception) {
            null
        }
    }
}
