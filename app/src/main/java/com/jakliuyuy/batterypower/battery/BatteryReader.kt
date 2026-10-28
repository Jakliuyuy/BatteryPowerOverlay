package com.jakliuyuy.batterypower.battery

import android.content.Context
import android.os.BatteryManager
import com.jakliuyuy.batterypower.settings.SettingsStore
import kotlin.math.abs

/**
 * 统一电池数据采集引擎：
 * 1. root 通道：内核 power_now / current_now 直读（高精度，支持极性智能校准与双电芯计算）
 * 2. 系统降级通道：BatteryManager 接口 + ACTION_BATTERY_CHANGED 广播
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

        // 1. 尝试通过 root 读取内核节点
        RootHelper.read()?.let { raw ->
            lastRootHealthy = true
            val voltageV = (raw.voltageUv ?: 0) / 1_000_000.0
            var rawW = raw.powerUw?.let { it / 1_000_000.0 }
                ?: ((raw.currentMa ?: 0) / 1000.0 * voltageV)

            if (doubleCell) {
                rawW *= 2.0
            }

            var isCharging = raw.status.contains("Charg", ignoreCase = true) ||
                    raw.status.contains("Full", ignoreCase = true)

            if (invert) {
                isCharging = !isCharging
            }

            val finalPowerW = if (isCharging) abs(rawW) else -abs(rawW)
            val currentMa = raw.currentMa ?: 0

            return Sample(
                powerW = finalPowerW,
                currentMa = if (doubleCell) currentMa * 2 else currentMa,
                voltageMv = (raw.voltageUv ?: 0) / 1000,
                level = raw.capacity,
                tempC = raw.tempC,
                status = raw.status,
                isCharging = isCharging,
                source = "root"
            )
        }

        lastRootHealthy = false

        // 2. 降级为系统 API 读取
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

            val ma = if (cur == Int.MIN_VALUE) 0 else cur
            var calcW = abs(ma / 1000.0 * (mv / 1000.0))
            if (doubleCell) calcW *= 2.0

            Sample(
                powerW = if (isCharging) calcW else -calcW,
                currentMa = if (doubleCell) ma * 2 else ma,
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
