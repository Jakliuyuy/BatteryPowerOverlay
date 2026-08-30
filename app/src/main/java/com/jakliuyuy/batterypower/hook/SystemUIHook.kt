package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook SystemUI 状态栏时钟，注入电池功率 TextView（Scene 迷你监视器风格）。
 *
 * 时钟类兼容：ColorOS(一加) 用 com.oplus.systemui.statusbar.policy.Clock，
 * AOSP 用 com.android.systemui.statusbar.policy.Clock，自动探测。
 *
 * 配置：XSharedPreferences 跨进程读取主 app 设置（显示项/颜色/位置/字号），
 * 每帧刷新前 reload，设置改动实时生效（无需重启 SystemUI）。
 */
object SystemUIHook {

    private val CANDIDATES = listOf(
        "com.oplus.systemui.statusbar.policy.Clock",
        "com.android.systemui.statusbar.policy.Clock",
        "com.oplusos.systemui.statusbar.policy.Clock"
    )

    private const val POWER_TAG = "battery_power_overlay_tag"
    private const val APP_PKG = "com.jakliuyuy.batterypower"

    @Volatile
    private var xsp: XSharedPreferences? = null

    private fun prefs(): XSharedPreferences {
        val p = xsp ?: XSharedPreferences(APP_PKG, "battery_power_prefs").also { xsp = it }
        p.reload()
        return p
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clockClass = CANDIDATES.firstNotNullOfOrNull {
            runCatching { XposedHelpers.findClass(it, lpparam.classLoader) }.getOrNull()
        } ?: run {
            null
        }
        if (clockClass == null) {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.statusbar.policy.Clock",
                lpparam.classLoader,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        inject(param.thisObject as View)
                    }
                }
            )
            return
        }

        XposedHelpers.findAndHookMethod(
            clockClass,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    inject(param.thisObject as View)
                }
            }
        )
    }

    private fun inject(clock: View) {
        val parent = clock.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(POWER_TAG) != null) return

        val ctx = clock.context
        val tv = TextView(ctx).apply {
            tag = POWER_TAG
            gravity = Gravity.CENTER_VERTICAL
        }
        // 默认插时钟右侧
        val index = parent.indexOfChild(clock)
        parent.addView(tv, index + 1)

        // 1s 刷新（随视图生命周期自动停止）
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!tv.isAttachedToWindow) return
                applyConfig(tv, parent, clock)
                tv.text = readPower(ctx, prefs())
                handler.postDelayed(this, 1000L)
            }
        }
        tv.post(runnable)
    }

    /** 位置/颜色/字号实时同步（Scene 风格：紧凑无背景） */
    private fun applyConfig(tv: TextView, parent: ViewGroup, clock: View) {
        val p = prefs()
        tv.textSize = p.getFloat("hook_text_size", 11f)
        tv.setTextColor(p.getInt("hook_text_color", Color.WHITE))
        tv.setPadding(dp(tv.context, 3), 0, dp(tv.context, 2), 0)
        // 位置：0=时钟右侧 1=时钟左侧
        val side = p.getInt("hook_side", 0)
        val target = parent.indexOfChild(clock) + if (side == 1) 0 else 1
        if (parent.indexOfChild(tv) != target) {
            runCatching { parent.removeView(tv) }
            runCatching { parent.addView(tv, target) }
        }
    }

    /** SystemUI 进程内用系统权限读 BatteryManager，无需 root */
    private fun readPower(ctx: Context, p: XSharedPreferences): String {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val bintent = ctx.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val mv = bintent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val cap = bintent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val temp = bintent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val ma = if (cur == Int.MIN_VALUE) 0 else cur
            val w = ma / 1000.0 * (mv / 1000.0)

            val parts = mutableListOf<String>()
            if (p.getBoolean("hook_show_power", true)) {
                parts.add(if (w >= 10) String.format("%.1fW", w) else String.format("%.2fW", w))
            }
            if (p.getBoolean("hook_show_current", false)) {
                parts.add(if (kotlin.math.abs(ma) >= 1000) String.format("%.2fA", ma / 1000.0) else "${ma}mA")
            }
            if (p.getBoolean("hook_show_voltage", false)) {
                parts.add(String.format("%.2fV", mv / 1000.0))
            }
            if (p.getBoolean("hook_show_temp", false) && temp > 0) {
                parts.add(String.format("%.1f°C", temp / 10.0))
            }
            if (p.getBoolean("hook_show_level", true)) {
                parts.add("$cap%")
            }
            if (parts.isEmpty()) "--" else parts.joinToString(" ")
        } catch (e: Exception) {
            "--W"
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
