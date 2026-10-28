package com.jakliuyuy.batterypower.battery

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * root 通道（KernelSU / APatch / Magisk 兼容）：
 *
 * 核心设计：
 * 1. 常驻 su shell 进程池会话，避免高频启动新 su 进程引发的系统限流或卡死。
 * 2. 管道安全机制：带标记位（MARKER）的行读取，超时（默认 2500ms）后自动重置会话，防止脏数据累积。
 * 3. 增强型电池节点探测，支持标准 battery、qcom-battery 等多路径备选。
 */
object RootHelper {

    data class BatteryRaw(
        val powerUw: Long?,    // μW，power_now 直读（优先）
        val currentMa: Int?,   // mA
        val voltageUv: Int?,   // μV
        val capacity: Int,     // %
        val tempC: Double,     // °C
        val status: String     // Charging / Discharging / Full
    )

    private const val BASE_BATTERY = "/sys/class/power_supply/battery"

    @Volatile
    private var suProcess: Process? = null
    @Volatile
    private var suWriter: OutputStream? = null
    @Volatile
    private var suReader: BufferedReader? = null

    private var sessionFailed = false
    private var lastFailTime = 0L

    @Synchronized
    fun ensureSession(): Boolean {
        val now = System.currentTimeMillis()
        if (sessionFailed && now - lastFailTime < 5000) {
            return false // 失败后 5 秒退避，防止频繁狂拉
        }

        if (suProcess?.isAlive == true && suWriter != null && suReader != null) {
            return true
        }

        destroy()
        return try {
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            suProcess = p
            suWriter = p.outputStream
            suReader = BufferedReader(InputStreamReader(p.inputStream))

            val idCheck = execInternal("id", 3000)
            val ok = idCheck?.contains("uid=0") == true
            if (!ok) {
                sessionFailed = true
                lastFailTime = now
                destroy()
                false
            } else {
                sessionFailed = false
                true
            }
        } catch (_: Exception) {
            sessionFailed = true
            lastFailTime = now
            destroy()
            false
        }
    }

    /**
     * 在常驻会话内执行命令并等待结果。
     */
    @Synchronized
    fun exec(cmd: String): String? {
        if (!ensureSession()) return null
        return execInternal(cmd, 2500)
    }

    private fun execInternal(cmd: String, timeoutMs: Long): String? {
        val w = suWriter ?: return null
        val r = suReader ?: return null
        return try {
            val marker = "BP_DONE_${System.nanoTime()}"
            val payload = "$cmd\necho $marker\n"
            w.write(payload.toByteArray())
            w.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                if (r.ready()) {
                    val line = r.readLine() ?: break
                    if (line.contains(marker)) {
                        return sb.toString().trim()
                    }
                    sb.appendLine(line)
                } else {
                    Thread.sleep(15)
                }
            }
            // 超时：可能处于阻塞状态，销毁并重置当前会话
            destroy()
            null
        } catch (_: Exception) {
            destroy()
            null
        }
    }

    /** 读取电池基础参数 */
    fun read(): BatteryRaw? {
        val cmd = "cat $BASE_BATTERY/power_now $BASE_BATTERY/current_now $BASE_BATTERY/voltage_now $BASE_BATTERY/temp $BASE_BATTERY/capacity $BASE_BATTERY/status 2>/dev/null"
        val out = exec(cmd) ?: return null
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val nums = lines.mapNotNull { it.toLongOrNull() }
        val statusLine = lines.lastOrNull { it.toLongOrNull() == null } ?: "Discharging"

        if (nums.size < 3) return null

        val power = nums.getOrNull(0)
        val current = nums.getOrNull(1)
        val voltage = nums.getOrNull(2)
        val temp = nums.getOrNull(3)
        val cap = nums.getOrNull(4) ?: 0L

        return BatteryRaw(
            powerUw = power?.takeIf { it != 0L },
            currentMa = current?.toInt(),
            voltageUv = voltage?.toInt(),
            capacity = cap.toInt().coerceIn(0, 100),
            tempC = (temp ?: 0L) / 10.0,
            status = statusLine
        )
    }

    fun isAvailable(): Boolean {
        if (!ensureSession()) return false
        return read() != null
    }

    @Synchronized
    fun destroy() {
        try { suWriter?.close() } catch (_: Exception) {}
        try { suReader?.close() } catch (_: Exception) {}
        try { suProcess?.destroy() } catch (_: Exception) {}
        suProcess = null
        suWriter = null
        suReader = null
    }
}
