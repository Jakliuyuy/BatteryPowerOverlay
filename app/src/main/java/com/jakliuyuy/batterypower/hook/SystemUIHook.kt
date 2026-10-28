package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.abs

/**
 * 状态栏 LSPosed Hook 引擎：
 *
 * 核心创新 —— 原生兄弟节点内嵌架构（彻底杜绝开机漂移）：
 * 1. 彻底摒弃将控件挂载在顶层 FrameLayout 并依靠绝对像素 Margin 动态追踪时钟的脆弱做法。
 * 2. 直接将电池功率 TextView 作为状态栏 Clock 的原生兄弟视图（Sibling View）注入到时钟父容器中。
 * 3. 几何级天然防漂移：时钟在开机冷启动、DisplayCutout (刘海/挖孔) 避让、通知图标增减、屏幕旋转时，
 *    功率视图与时钟完全共享相同的流式排版上下文，永远如影随形，漂移率直接归零。
 * 4. 采用硬件加速的 translationX / translationY 进行像素级微调，完全不干涉父容器 Measure/Layout。
 * 5. 颜色自适应：自动同步时钟当前的 textColor，浅色状态栏（黑字）与深色状态栏（白字）无感平滑切换。
 */
object SystemUIHook {

    private val CLOCK_CANDIDATES = listOf(
        "com.oplus.systemui.statusbar.policy.Clock",
        "com.android.systemui.statusbar.policy.Clock",
        "com.oplusos.systemui.statusbar.policy.Clock"
    )

    private const val POWER_VIEW_TAG = "battery_power_native_tag"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clockClass = CLOCK_CANDIDATES.firstNotNullOfOrNull {
            runCatching { XposedHelpers.findClass(it, lpparam.classLoader) }.getOrNull()
        } ?: return

        XposedHelpers.findAndHookMethod(
            clockClass,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val clock = param.thisObject as? View ?: return
                    inject(clock)
                }
            }
        )
    }

    private fun inject(clock: View) {
        val parent = clock.parent as? ViewGroup ?: return
        val ctx = clock.context

        // 避免重复创建注入
        var tv = parent.findViewWithTag<TextView>(POWER_VIEW_TAG)
        if (tv == null) {
            tv = TextView(ctx).apply {
                tag = POWER_VIEW_TAG
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                text = "--"
            }

            val cfg = ConfigBridge.query(ctx)
            val side = ConfigBridge.intOf(cfg, "hook_side", 0) // 0=时钟右侧, 1=时钟左侧
            val clockIndex = parent.indexOfChild(clock)

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                val marginH = dp(ctx, 4)
                if (side == 1) {
                    rightMargin = marginH
                } else {
                    leftMargin = marginH
                }
            }

            if (side == 1) {
                parent.addView(tv, clockIndex.coerceAtLeast(0), lp)
            } else {
                parent.addView(tv, (clockIndex + 1).coerceAtMost(parent.childCount), lp)
            }
        }

        // 首次应用配置与渲染
        val initialCfg = ConfigBridge.query(ctx)
        applyStyleAndPosition(tv, clock, initialCfg)
        tv.text = readSystemPower(ctx, initialCfg)

        // 注册 ContentObserver 实时热重载（主 App 改动毫秒级即时生效）
        ConfigBridge.ensureObserver(ctx) { freshCfg ->
            applyStyleAndPosition(tv, clock, freshCfg)
            tv.text = readSystemPower(ctx, freshCfg)
        }

        // 启动主轮询循环
        val handler = Handler(Looper.getMainLooper())
        val refreshRunnable = object : Runnable {
            override fun run() {
                if (!tv.isAttachedToWindow || !clock.isAttachedToWindow) return
                val cfg = ConfigBridge.query(ctx)
                applyStyleAndPosition(tv, clock, cfg)
                tv.text = readSystemPower(ctx, cfg)
                val refreshMs = ConfigBridge.longOf(cfg, "hook_refresh_ms", 1000L).coerceIn(400, 5000)
                handler.postDelayed(this, refreshMs)
            }
        }
        tv.post(refreshRunnable)
    }

    private fun applyStyleAndPosition(
        tv: TextView,
        clock: View,
        cfg: Map<String, String>
    ) {
        val enabled = ConfigBridge.boolOf(cfg, "hook_enabled", true)
        if (!enabled) {
            tv.visibility = View.GONE
            return
        }

        // 状态联动：如果时钟隐藏（如全屏游戏/视频），功率文本同步隐藏
        if (clock.visibility != View.VISIBLE) {
            tv.visibility = View.GONE
            return
        } else {
            tv.visibility = View.VISIBLE
        }

        // 字号设置
        val textSize = ConfigBridge.floatOf(cfg, "hook_text_size", 11f)
        tv.textSize = textSize

        // 颜色自适应（默认跟随系统时钟文本色）
        val adaptColor = ConfigBridge.boolOf(cfg, "hook_adapt_color", true)
        if (adaptColor && clock is TextView) {
            tv.setTextColor(clock.currentTextColor)
        } else {
            val customColor = ConfigBridge.intOf(cfg, "hook_text_color", Color.WHITE)
            tv.setTextColor(customColor)
        }

        // 硬件加速微调平移（绝对不触发二次 layout，杜绝开机晃动与死循环）
        val ox = ConfigBridge.intOf(cfg, "hook_offset_x", 0)
        val oy = ConfigBridge.intOf(cfg, "hook_offset_y", 0)
        tv.translationX = ox.toFloat()
        tv.translationY = oy.toFloat()
    }

    /**
     * 在 SystemUI 进程内直接通过系统服务和广播获取准确的电池数据
     */
    private fun readSystemPower(ctx: Context, cfg: Map<String, String>): String {
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
            val status = bintent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: 0

            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            // 电流单位智能自适应：μA -> mA
            val normalizedMa = if (cur == Int.MIN_VALUE) {
                0
            } else if (abs(cur) > 50_000) {
                cur / 1000
            } else {
                cur
            }

            val w = abs(normalizedMa / 1000.0 * (mv / 1000.0))
            val signedW = if (isCharging) w else -w
            val unit = ConfigBridge.boolOf(cfg, "show_unit", true)

            val parts = mutableListOf<String>()

            // 1. 功率
            if (ConfigBridge.boolOf(cfg, "hook_show_power", true)) {
                val prefix = if (isCharging) "+" else "-"
                val valStr = if (abs(signedW) >= 10) String.format("%.1f", abs(signedW)) else String.format("%.2f", abs(signedW))
                parts.add(if (unit) "$prefix${valStr}W" else "$prefix$valStr")
            }

            // 2. 电流
            if (ConfigBridge.boolOf(cfg, "hook_show_current", false)) {
                val absMa = abs(normalizedMa)
                val str = if (absMa >= 1000) String.format("%.2fA", absMa / 1000.0) else "${absMa}mA"
                parts.add(if (unit) str else str.replace("mA", "").replace("A", ""))
            }

            // 3. 电压
            if (ConfigBridge.boolOf(cfg, "hook_show_voltage", false)) {
                val vStr = String.format("%.2f", mv / 1000.0)
                parts.add(if (unit) "${vStr}V" else vStr)
            }

            // 4. 温度
            if (ConfigBridge.boolOf(cfg, "hook_show_temp", false) && temp > 0) {
                val tStr = String.format("%.1f", temp / 10.0)
                parts.add(if (unit) "$tStr°C" else tStr)
            }

            // 5. 电量
            if (ConfigBridge.boolOf(cfg, "hook_show_level", true)) {
                parts.add(if (unit) "$cap%" else "$cap")
            }

            if (parts.isEmpty()) "--" else parts.joinToString(" ")
        } catch (_: Exception) {
            "--"
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
