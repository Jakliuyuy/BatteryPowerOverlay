package com.jakliuyuy.batterypower.battery

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * root 通道（KernelSU/Magisk 兼容）：**常驻 su shell 会话**，避免每秒拉起 su 进程被限流。
 *
 * 实测（PLR110 / Android 16 / 内核 6.12）：
 * - current_now 单位是 **mA**（不是 μA！）
 * - voltage_now 单位 μV
 * - temp 单位 0.1°C
 * - power_now 节点存在（μW），直接就是功率，优先使用
 */
object RootHelper {

    data class BatteryRaw(
        val powerUw: Long?,    // μW，power_now 直读（最优，充电为负）
        val currentMa: Int?,   // mA（充电为负）
        val voltageUv: Int?,   // μV
        val capacity: Int,     // %
        val tempC: Double,     // °C
        val status: String     // Charging / Discharging / Full ...
    )

    private const val BASE = "/sys/class/power_supply/battery"

    @Volatile
    private var suProcess: Process? = null
    @Volatile
    private var suWriter: java.io.OutputStream? = null
    private var sessionFailed = false

    /** 拉起常驻 su 会话（首次调用时），返回是否可用 */
    @Synchronized
    fun ensureSession(): Boolean {
        if (sessionFailed) return false
        if (suProcess?.isAlive == true && suWriter != null) return true
        return try {
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            suProcess = p
            suWriter = p.outputStream
            // 验证会话可用
            val ok = exec("id")?.contains("uid=0") == true
            if (!ok) {
                sessionFailed = true
                destroy()
            }
            ok
        } catch (e: Exception) {
            sessionFailed = true
            false
        }
    }

    /**
     * 常驻会话内执行命令，返回 stdout（去除命令回显）。
     * 失败时销毁会话，下次自动重建。
     */
    @Synchronized
    fun exec(cmd: String): String? {
        if (!ensureSession()) return null
        return try {
            val w = suWriter ?: return null
            val marker = "KOMARI_DONE_${System.nanoTime()}"
            val full = "$cmd; echo $marker\n"
            w.write(full.toByteArray())
            w.flush()
            val r = BufferedReader(InputStreamReader(suProcess!!.inputStream))
            val sb = StringBuilder()
            var line: String?
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                if (r.ready()) {
                    line = r.readLine() ?: break
                    if (line.contains(marker)) return sb.toString()
                    sb.appendLine(line)
                } else {
                    Thread.sleep(20)
                }
            }
            null // 超时
        } catch (e: Exception) {
            destroy()
            null
        }
    }

    /** 读取电池数据；power_now 优先，current×voltage 兜底 */
    fun read(): BatteryRaw? {
        val out = exec(
            "cat $BASE/power_now $BASE/current_now $BASE/voltage_now $BASE/temp $BASE/capacity $BASE/status 2>/dev/null"
        ) ?: return null
        val lines = out.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        // 数字行按序取前 5 个，非数字行是 status
        val nums = lines.mapNotNull { it.trim().toLongOrNull() }
        if (nums.size < 4) return null
        val statusLine = lines.lastOrNull { it.trim().toLongOrNull() == null }?.trim() ?: ""

        val power = nums.getOrNull(0)
        val current = nums.getOrNull(1)
        val voltage = nums.getOrNull(2)
        val temp = nums.getOrNull(3)
        val cap = nums.getOrNull(4) ?: 0

        return BatteryRaw(
            powerUw = power?.takeIf { it != 0L },
            currentMa = current?.toInt(),
            voltageUv = voltage?.toInt(),
            capacity = cap.toInt().coerceIn(0, 100),
            tempC = (temp ?: 0) / 10.0,
            status = statusLine
        )
    }

    fun isAvailable(): Boolean {
        if (!ensureSession()) return false
        return read() != null
    }

    fun destroy() {
        try {
            suWriter?.close()
        } catch (_: Exception) {
        }
        try {
            suProcess?.destroy()
        } catch (_: Exception) {
        }
        suProcess = null
        suWriter = null
        sessionFailed = false
    }
}
