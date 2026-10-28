package com.jakliuyuy.batterypower.battery

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.math.abs

/**
 * 高性能 Root 通道（KernelSU / APatch / Magisk 兼容）：
 * 1. 采用常驻 su shell 会话复用，彻底避免频繁 fork su 进程引发的高负载和系统限流。
 * 2. 键值标签式（Key-Value）结构化输出，即使部分内核节点缺失也绝不错位。
 * 3. 自动探测多种电池节点路径（battery, qcom-battery, bms 等）。
 * 4. 电流单位智能自适应（自动识别 μA 与 mA）。
 */
object RootHelper {

    data class BatteryRaw(
        val powerUw: Long?,    // μW，power_now 直读
        val currentMa: Int?,   // 经过智能识别换算后的 mA
        val voltageUv: Int?,   // μV
        val capacity: Int,     // %
        val tempC: Double,     // °C
        val status: String     // Charging / Discharging / Full
    )

    private val BATTERY_CANDIDATE_PATHS = listOf(
        "/sys/class/power_supply/battery",
        "/sys/class/power_supply/qcom-battery",
        "/sys/class/power_supply/bms",
        "/sys/class/power_supply/main-battery"
    )

    @Volatile
    private var detectedBasePath: String = BATTERY_CANDIDATE_PATHS[0]

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
        if (sessionFailed && now - lastFailTime < 4000) {
            return false
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

            val idCheck = execInternal("id", 2500)
            val ok = idCheck?.contains("uid=0") == true
            if (!ok) {
                sessionFailed = true
                lastFailTime = now
                destroy()
                false
            } else {
                sessionFailed = false
                probeWorkingPath()
                true
            }
        } catch (_: Exception) {
            sessionFailed = true
            lastFailTime = now
            destroy()
            false
        }
    }

    private fun probeWorkingPath() {
        for (path in BATTERY_CANDIDATE_PATHS) {
            val out = execInternal("[ -d $path ] && echo OK", 1000)
            if (out?.contains("OK") == true) {
                detectedBasePath = path
                break
            }
        }
    }

    @Synchronized
    fun exec(cmd: String): String? {
        if (!ensureSession()) return null
        return execInternal(cmd, 2500)
    }

    private fun execInternal(cmd: String, timeoutMs: Long): String? {
        val w = suWriter ?: return null
        val r = suReader ?: return null
        return try {
            val marker = "BP_MARKER_${System.nanoTime()}"
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
                    Thread.sleep(10)
                }
            }
            destroy()
            null
        } catch (_: Exception) {
            destroy()
            null
        }
    }

    /**
     * 结构化读取电池基础数据
     */
    fun read(): BatteryRaw? {
        val path = detectedBasePath
        // 采用标签输出，彻底根绝缺行导致的字段错位
        val cmd = "for f in power_now current_now voltage_now temp capacity status; do " +
                "v=\$(cat $path/\$f 2>/dev/null); echo \"\$f:\$v\"; done"
        val out = exec(cmd) ?: return null

        val map = HashMap<String, String>()
        for (line in out.lines()) {
            val idx = line.indexOf(':')
            if (idx != -1) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                map[k] = v
            }
        }

        val rawCurrent = map["current_now"]?.toLongOrNull()
        val rawVoltage = map["voltage_now"]?.toIntOrNull()
        val rawPower = map["power_now"]?.toLongOrNull()
        val rawTemp = map["temp"]?.toDoubleOrNull() ?: 0.0
        val rawCap = map["capacity"]?.toIntOrNull() ?: 0
        val rawStatus = map["status"]?.takeIf { it.isNotBlank() } ?: "Discharging"

        // 必须至少能读到电压或电流或电量
        if (rawVoltage == null && rawCurrent == null && map["capacity"] == null) {
            return null
        }

        // 智能自适应电流单位：判断是 μA 还是 mA
        // 大于 50000 的数值必定是 μA（50000 μA = 50mA），反之是一加等设备的 mA
        val normalizedCurrentMa: Int? = rawCurrent?.let { raw ->
            val absRaw = abs(raw)
            if (absRaw > 50_000) {
                (raw / 1000).toInt()
            } else {
                raw.toInt()
            }
        }

        val tempCelsius = if (rawTemp > 1000) rawTemp / 10.0 else rawTemp / 10.0

        return BatteryRaw(
            powerUw = rawPower?.takeIf { it != 0L },
            currentMa = normalizedCurrentMa,
            voltageUv = rawVoltage,
            capacity = rawCap.coerceIn(0, 100),
            tempC = tempCelsius,
            status = rawStatus
        )
    }

    fun isAvailable(): Boolean {
        if (!ensureSession()) return false
        return read() != null
    }

    fun getDetectedPath(): String = detectedBasePath

    @Synchronized
    fun destroy() {
        try { suWriter?.close() } catch (_: Exception) {}
        try { suReader?.close() } catch (_: Exception) {}
        try { suProcess?.destroy() } catch (_: Exception) {}
        suWriter = null
        suReader = null
        suProcess = null
    }
}
