package com.jakliuyuy.batterypower.battery

import android.content.Context
import android.os.BatteryManager
import com.jakliuyuy.batterypower.settings.SettingsStore
import kotlin.math.abs

/**
 * 统一电池动力数据采集中心：
 * 1. Root 通道优先：内核 power_now / current_now 直读（支持双电芯与充放电极性校准）
 * 2. 系统 API 通道：BatteryManager + ACTION_BATTERY_CHANGED 系统粘性广播
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
        fun powerText(): String {
            val prefix = if (isCharging) "+" else "-"
            val absW = abs(powerW)
            val formatted = if (absW >= 10) String.format("%.1fW", absW) else String.format("%.2fW", absW)
            return "$prefix$formatted"
        }
    }

    private var lastRootHealthy = false

    fun read(context: Context): Sample {
        val invert = SettingsStore.polarityInvert
        val doubleCell = SettingsStore.doubleCellFix

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
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
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

            // 系统 BatteryManager 的 CURRENT_NOW：
            // 大多数设备返回 μA（绝对值 > 50000），少数返回 mA
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
