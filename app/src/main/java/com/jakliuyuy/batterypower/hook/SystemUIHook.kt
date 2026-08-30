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
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook SystemUI 状态栏时钟，在时钟旁注入电池功率 TextView。
 *
 * 时钟类兼容：ColorOS(一加) 用 com.oplus.systemui.statusbar.policy.Clock，
 * AOSP 用 com.android.systemui.statusbar.policy.Clock，自动探测。
 *
 * 数据源：代码运行在 SystemUI 进程（系统签名），BatteryManager 可取真实瞬时电流。
 */
object SystemUIHook {

    private val CANDIDATES = listOf(
        "com.oplus.systemui.statusbar.policy.Clock",
        "com.android.systemui.statusbar.policy.Clock",
        "com.oplusos.systemui.statusbar.policy.Clock"
    )

    private val POWER_TAG = "battery_power_overlay_tag"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clockClass = CANDIDATES.firstNotNullOfOrNull {
            runCatching { XposedHelpers.findClass(it, lpparam.classLoader) }.getOrNull()
        } ?: run {
            // 最后手段：按类名搜索
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
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 3), 0, dp(ctx, 1), 0)
        }

        // 插入到时钟右侧
        val index = parent.indexOfChild(clock)
        parent.addView(tv, index + 1)

        // 每 1s 刷新（post 到主线程，随视图生命周期自动停止）
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!tv.isAttachedToWindow) return
                tv.text = readPower(ctx)
                handler.postDelayed(this, 1000L)
            }
        }
        tv.post(runnable)
    }

    /** SystemUI 进程内用系统权限读 BatteryManager，无需 root */
    private fun readPower(ctx: Context): String {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val bintent = ctx.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val mv = bintent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val cap = bintent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val ma = if (cur == Int.MIN_VALUE) 0 else cur
            val w = ma / 1000.0 * (mv / 1000.0)
            val wText = if (w >= 10) String.format("%.1fW", w) else String.format("%.2fW", w)
            "$wText $cap%"
        } catch (e: Exception) {
            "--W"
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
