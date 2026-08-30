package com.jakliuyuy.batterypower.battery

import android.content.Context
import android.os.BatteryManager

/**
 * 统一电池数据源。
 *
 * root 路径（实测 PLR110）：power_now 直读（μW）→ 功率；current_now 单位 mA。
 * 降级路径：BatteryManager 电流（普通 app 可能为 0）+ sticky 广播电压/温度/电量。
 */
object BatteryReader {

    data class Sample(
        val powerW: Double,
        val currentMa: Int,
        val voltageMv: Int,
        val level: Int,
        val tempC: Double,
        val source: String  // "root" / "system" / "failed"
    ) {
        fun powerText(): String = if (powerW >= 0) {
            if (powerW >= 10) String.format("%.1fW", powerW) else String.format("%.2fW", powerW)
        } else {
            String.format("-%.2fW", -powerW)
        }
    }

    private var lastRootOk = false

    fun read(context: Context): Sample {
        // 1) root：power_now 优先，current×voltage 兜底
        RootHelper.read()?.let { raw ->
            lastRootOk = true
            val voltageV = (raw.voltageUv ?: 0) / 1_000_000.0
            val powerW = raw.powerUw?.let { it / 1_000_000.0 }
                ?: (raw.currentMa ?: 0) / 1000.0 * voltageV
            return Sample(
                powerW = powerW,
                currentMa = raw.currentMa ?: 0,
                voltageMv = (raw.voltageUv ?: 0) / 1000,
                level = raw.capacity,
                tempC = raw.tempC,
                source = "root"
            )
        }
        lastRootOk = false

        // 2) 降级 BatteryManager + sticky 广播
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
            val ma = if (cur == Integer.MIN_VALUE) 0 else cur
            Sample(
                powerW = ma / 1000.0 * (mv / 1000.0),
                currentMa = ma,
                voltageMv = mv,
                level = if (cap in 0..100) cap else 0,
                tempC = temp / 10.0,
                source = "system"
            )
        } catch (e: Exception) {
            Sample(0.0, 0, 0, 0, 0.0, "failed")
        }
    }

    fun rootHealthy(): Boolean = lastRootOk
}
