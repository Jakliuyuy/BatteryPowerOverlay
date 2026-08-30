package com.jakliuyuy.batterypower.battery

import android.content.Context
import android.os.BatteryManager

/**
 * 统一电池数据源：root 节点优先，失败降级 BatteryManager。
 *
 * 功率 = 电流 × 电压。root 模式电流单位 μA、电压 μV；
 * BatteryManager 的 CURRENT_NOW 单位 mA、VOLTAGE 单位 mV。
 */
object BatteryReader {

    data class Sample(
        val powerW: Double,
        val currentMa: Int,
        val voltageMv: Int,
        val level: Int,
        val source: String  // "root" / "system"
    ) {
        fun powerText(): String = if (powerW >= 0) {
            if (powerW >= 10) String.format("%.1fW", powerW) else String.format("%.2fW", powerW)
        } else {
            String.format("-%.2fW", -powerW)
        }
    }

    private var lastRootOk = false

    fun read(context: Context): Sample {
        // 1) root 读内核节点
        RootHelper.read()?.let { raw ->
            lastRootOk = true
            val w = raw.currentUa / 1_000_000.0 * (raw.voltageUv / 1_000_000.0)
            return Sample(
                powerW = w,
                currentMa = raw.currentUa / 1000,
                voltageMv = raw.voltageUv / 1000,
                level = raw.capacity,
                source = "root"
            )
        }
        lastRootOk = false

        // 2) 降级 BatteryManager（电流仍可取；电压/电量用 sticky 广播，公共 API）
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val bintent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val mv = bintent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val cap = bintent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val ma = if (cur == Integer.MIN_VALUE) 0 else cur
            Sample(
                powerW = ma / 1000.0 * (mv / 1000.0),
                currentMa = ma,
                voltageMv = mv,
                level = if (cap in 0..100) cap else 0,
                source = "system"
            )
        } catch (e: Exception) {
            Sample(0.0, 0, 0, 0, "none")
        }
    }
}
