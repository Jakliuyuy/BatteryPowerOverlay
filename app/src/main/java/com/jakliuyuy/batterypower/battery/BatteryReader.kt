package com.jakliuyuy.batterypower.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.jakliuyuy.batterypower.settings.SettingsStore
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 统一电池动力数据采集中心：
 * 1. 异步快照缓存引擎：后台线程定期/触发式采集，主线程 UI 0ms 零阻塞读取，彻底杜绝 ANR 与卡顿。
 * 2. Root 通道优先：内核 power_now / current_now 直读（支持双电芯与充放电极性校准）。
 * 3. 系统 API 通道：BatteryManager + ACTION_BATTERY_CHANGED 系统粘性广播。
 * 4. 统一标准的格式化输出（功率、电流、电压、温度、电量）。
 */
object BatteryReader {

    data class Sample(
        val powerW: Double,
        val currentMa: Int,
        val voltageMv: Int,
        val level: Int,
        val tempC: Double,
        val status: String,     // "Charging", "Discharging", "Full"
        val isCharging: Boolean,
        val source: String      // "root" / "system" / "failed"
    ) {
        fun powerText(showUnit: Boolean = true): String {
            val prefix = if (isCharging) "+" else "-"
            val absW = abs(powerW)
            val valStr = if (absW >= 10) String.format("%.1f", absW) else String.format("%.2f", absW)
            return if (showUnit) "$prefix${valStr}W" else "$prefix$valStr"
        }

        fun currentText(showUnit: Boolean = true): String {
            val prefix = if (isCharging) "+" else "-"
            val absMa = abs(currentMa)
            val str = if (absMa >= 1000) String.format("%.2fA", absMa / 1000.0) else "${absMa}mA"
            return if (showUnit) "$prefix$str" else "$prefix${str.replace("mA", "").replace("A", "")}"
        }

        fun voltageText(showUnit: Boolean = true): String {
            val vStr = String.format("%.2f", voltageMv / 1000.0)
            return if (showUnit) "${vStr}V" else vStr
        }

        fun tempText(showUnit: Boolean = true): String {
            val tStr = String.format("%.1f", tempC)
            return if (showUnit) "$tStr°C" else tStr
        }

        fun levelText(showUnit: Boolean = true): String {
            return if (showUnit) "$level%" else "$level"
        }
    }

    @Volatile
    private var cachedSample: Sample? = null

    @Volatile
    private var lastSampleTime = 0L

    @Volatile
    private var lastRootHealthy = false

    private val asyncExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "battery-sampler-worker").apply { isDaemon = true }
    }

    private var isWorkerRunning = false

    /**
     * 读取电池数据：优先返回内存快照，后台自驱刷新，主线程零延迟
     */
    fun read(context: Context): Sample {
        val now = SystemClock.elapsedRealtime()
        val current = cachedSample

        // 启动后台自驱轮询 Worker
        ensureWorker(context.applicationContext ?: context)

        if (current != null && (now - lastSampleTime < 1800L)) {
            return current
        }

        // 若首次启动无缓存，同步采集一次（系统API或轻量探测）
        if (current == null) {
            val immediate = readDirectSync(context.applicationContext ?: context)
            cachedSample = immediate
            lastSampleTime = now
            return immediate
        }

        return current
    }

    @Synchronized
    private fun ensureWorker(appContext: Context) {
        if (isWorkerRunning) return
        isWorkerRunning = true
        asyncExecutor.execute {
            while (true) {
                try {
                    val s = readDirectSync(appContext)
                    cachedSample = s
                    lastSampleTime = SystemClock.elapsedRealtime()
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(800L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    /**
     * 实际执行底层读取与解析
     */
    fun readDirectSync(context: Context): Sample {
        val invert = try { SettingsStore.polarityInvert } catch (_: Exception) { false }
        val doubleCell = try { SettingsStore.doubleCellFix } catch (_: Exception) { false }

        // 1. 尝试通过 Root 通道读取
        RootHelper.read()?.let { raw ->
            lastRootHealthy = true
            val voltageV = (raw.voltageUv ?: 0) / 1_000_000.0
            val currentMaRaw = raw.currentMa ?: 0

            var rawW = raw.powerUw?.let { it / 1_000_000.0 }
                ?: (abs(currentMaRaw) / 1000.0 * voltageV)

            if (doubleCell) {
                rawW *= 2.0
            }

            var isCharging = raw.status.contains("Charg", ignoreCase = true) ||
                    raw.status.contains("Full", ignoreCase = true) ||
                    (raw.status.equals("Unknown", ignoreCase = true) && currentMaRaw > 0)

            if (invert) {
                isCharging = !isCharging
            }

            val finalPowerW = if (isCharging) abs(rawW) else -abs(rawW)
            val finalCurrentMa = if (doubleCell) currentMaRaw * 2 else currentMaRaw

            return Sample(
                powerW = finalPowerW,
                currentMa = finalCurrentMa,
                voltageMv = (raw.voltageUv ?: 0) / 1000,
                level = raw.capacity,
                tempC = raw.tempC,
                status = raw.status,
                isCharging = isCharging,
                source = "root"
            )
        }

        lastRootHealthy = false

        // 2. 降级为系统 BatteryManager API
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val bintent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val mv = bintent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val cap = bintent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val temp = bintent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val statusExtra = bintent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: 0

            var isCharging = statusExtra == BatteryManager.BATTERY_STATUS_CHARGING ||
                    statusExtra == BatteryManager.BATTERY_STATUS_FULL

            if (invert) {
                isCharging = !isCharging
            }

            val normalizedCurrentMa = if (cur == Int.MIN_VALUE) {
                0
            } else if (abs(cur) > 50_000) {
                cur / 1000
            } else {
                cur
            }

            var calcW = abs(normalizedCurrentMa / 1000.0 * (mv / 1000.0))
            if (doubleCell) calcW *= 2.0

            Sample(
                powerW = if (isCharging) calcW else -calcW,
                currentMa = if (doubleCell) normalizedCurrentMa * 2 else normalizedCurrentMa,
                voltageMv = mv,
                level = cap.coerceIn(0, 100),
                tempC = temp / 10.0,
                status = if (isCharging) "Charging" else "Discharging",
                isCharging = isCharging,
                source = "system"
            )
        } catch (_: Exception) {
            Sample(0.0, 0, 0, 0, 0.0, "Unknown", false, "failed")
        }
    }

    fun isRootHealthy(): Boolean = lastRootHealthy
}
