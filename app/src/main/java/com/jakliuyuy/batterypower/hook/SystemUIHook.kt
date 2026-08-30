package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook SystemUI 状态栏，注入电池功率 TextView（Scene 迷你监视器风格）。
 *
 * - 时钟类兼容：ColorOS(oplus) / AOSP 双类名探测
 * - 配置：ConfigBridge（ContentProvider IPC + 磁盘缓存）实时拉取
 * - 优先级：注入到**状态栏根容器**并抬高 elevation，功率显示在**所有通知图标之上**，
 *   通知再多也不会覆盖功率；默认位置在时钟左侧（不盖时钟），可 x/y 偏移微调
 */
object SystemUIHook {

    private val CANDIDATES = listOf(
        "com.oplus.systemui.statusbar.policy.Clock",
        "com.android.systemui.statusbar.policy.Clock",
        "com.oplusos.systemui.statusbar.policy.Clock"
    )

    private const val POWER_TAG = "battery_power_overlay_tag"
    private const val ELEVATION = 100f

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clockClass = CANDIDATES.firstNotNullOfOrNull {
            runCatching { XposedHelpers.findClass(it, lpparam.classLoader) }.getOrNull()
        } ?: return  // 三个候选都没找到，ColorOS 类名变更，需要远程日志定位

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
        // 状态栏根容器（FrameLayout，支持 gravity + elevation 层级）
        val root = findStatusBarRoot(parent) ?: parent
        if (root !is FrameLayout) {
            // 兜底：挂到时钟父容器（旧方案，无覆盖优先级）
            attachLegacy(clock, parent)
            return
        }
        if (root.findViewWithTag<View>(POWER_TAG) != null) return

        val ctx = clock.context
        val tv = TextView(ctx).apply {
            tag = POWER_TAG
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            text = "--"
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        )
        tv.layoutParams = lp
        tv.elevation = ELEVATION
        tv.translationZ = ELEVATION
        root.addView(tv)

        // 刷新循环（频率可配置 0.5s~5s）
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!tv.isAttachedToWindow) return
                val cfg = ConfigBridge.query(ctx)
                applyConfig(tv, clock, root, cfg)
                tv.text = readPower(ctx, cfg)
                val refreshMs = ConfigBridge.longOf(cfg, "hook_refresh_ms", 1000L).coerceIn(500, 5000)
                handler.postDelayed(this, refreshMs)
            }
        }
        tv.post(runnable)
    }

    /** 兜底方案：插入时钟父容器（无 Z 序提升） */
    private fun attachLegacy(clock: View, parent: ViewGroup) {
        if (parent.findViewWithTag<View>(POWER_TAG) != null) return
        val ctx = clock.context
        val tv = TextView(ctx).apply {
            tag = POWER_TAG
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            textSize = 11f
            text = "--"
        }
        val idx = parent.indexOfChild(clock)
        parent.addView(tv, idx + 1)
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!tv.isAttachedToWindow) return
                val cfg = ConfigBridge.query(ctx)
                tv.textSize = ConfigBridge.floatOf(cfg, "hook_text_size", 11f)
                tv.setTextColor(ConfigBridge.intOf(cfg, "hook_text_color", Color.WHITE))
                tv.text = readPower(ctx, cfg)
                val refreshMs = ConfigBridge.longOf(cfg, "hook_refresh_ms", 1000L).coerceIn(500, 5000)
                handler.postDelayed(this, refreshMs)
            }
        }
        tv.post(runnable)
    }

    /** 位置（margin 控制，默认时钟左侧）+ 颜色/字号/显示开关 实时应用 */
    private fun applyConfig(tv: TextView, clock: View, root: FrameLayout, cfg: Map<String, String>) {
        val ctx = tv.context

        // 显示开关
        val enabled = ConfigBridge.boolOf(cfg, "hook_enabled", true)
        tv.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return

        // 颜色/字号
        tv.textSize = ConfigBridge.floatOf(cfg, "hook_text_size", 11f)
        tv.setTextColor(ConfigBridge.intOf(cfg, "hook_text_color", Color.WHITE))

        // 位置：以时钟为锚点
        val side = ConfigBridge.intOf(cfg, "hook_side", 0)  // 0=时钟右侧 1=时钟左侧
        val ox = ConfigBridge.intOf(cfg, "hook_offset_x", 0)
        val oy = ConfigBridge.intOf(cfg, "hook_offset_y", 0)

        val lp = tv.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL

        // 用屏幕坐标计算锚点（clock 与 root 坐标系不同，不能直接用 right）
        val rootLoc = IntArray(2)
        val clockLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        clock.getLocationOnScreen(clockLoc)
        val rootRightScreen = rootLoc[0] + root.width - root.paddingRight
        val clockLeftScreen = clockLoc[0]
        val clockRightScreen = clockLoc[0] + clock.width

        val marginEnd = if (side == 1) {
            // 时钟左侧：右边缘 = 时钟左边缘 - 间距 - 偏移
            (rootRightScreen - clockLeftScreen + dp(ctx, 4) - ox).coerceAtLeast(0)
        } else {
            // 时钟右侧：贴着时钟右边缘（时钟通常在状态栏最右）
            (rootRightScreen - clockRightScreen + dp(ctx, 4) - ox).coerceAtLeast(0)
        }
        lp.marginEnd = marginEnd
        lp.topMargin = oy
        runCatching { tv.layoutParams = lp }
    }

    /** 状态栏根容器（向上找到最外层 FrameLayout） */
    private fun findStatusBarRoot(v: ViewGroup): FrameLayout? {
        var cur: ViewGroup? = v
        var lastFrame: FrameLayout? = null
        var guard = 0
        while (cur != null && guard++ < 12) {
            if (cur is FrameLayout) lastFrame = cur
            cur = cur.parent as? ViewGroup
        }
        return lastFrame
    }

    /** SystemUI 进程内用系统权限读 BatteryManager，无需 root */
    private fun readPower(ctx: Context, cfg: Map<String, String>): String {
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
            val unit = ConfigBridge.boolOf(cfg, "show_unit", true)

            val parts = mutableListOf<String>()
            if (ConfigBridge.boolOf(cfg, "hook_show_power", true)) {
                parts.add(if (unit) {
                    if (w >= 10) String.format("%.1fW", w) else String.format("%.2fW", w)
                } else {
                    if (w >= 10) String.format("%.1f", w) else String.format("%.2f", w)
                })
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_current", false)) {
                parts.add(if (unit) {
                    if (kotlin.math.abs(ma) >= 1000) String.format("%.2fA", ma / 1000.0) else "${ma}mA"
                } else {
                    if (kotlin.math.abs(ma) >= 1000) String.format("%.2f", ma / 1000.0) else "$ma"
                })
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_voltage", false)) {
                parts.add(if (unit) String.format("%.2fV", mv / 1000.0) else String.format("%.2f", mv / 1000.0))
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_temp", false) && temp > 0) {
                parts.add(if (unit) String.format("%.1f°C", temp / 10.0) else String.format("%.1f", temp / 10.0))
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_level", true)) {
                parts.add(if (unit) "$cap%" else "$cap")
            }
            if (parts.isEmpty()) "--" else parts.joinToString(" ")
        } catch (e: Exception) {
            "--W"
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
