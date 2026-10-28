package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook SystemUI 状态栏，注入电池功率 TextView。
 *
 * 彻底解决开机重启后位置丢失的四大机制：
 * 1. 测绘就绪保护（OnGlobalLayoutListener）：在时钟与根容器未完成 measure/layout（宽度为0）前挂起等待，
 *    杜绝开机瞬间计算出 0 边距导致文字贴在屏幕极右侧。
 * 2. 动态双向锚点算法：自动识别时钟物理方位（左侧挖孔屏 / 右侧原生屏），根据用户设定的锚点
 *    （时钟左侧/右侧/状态栏左右端）自适应匹配 Gravity.START 或 Gravity.END，杜绝反推 900+ 像素边距导致的开机偏移。
 * 3. 垂直与微调位移采用 translationY/translationX 双保险，抗系统重排重绘干扰。
 * 4. 注册 ContentObserver 实时监听，主应用调参即刻生效；开机 1s/3s 延迟校验，平稳度过系统冷启阶段。
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
        val root = findStatusBarRoot(parent) ?: parent
        if (root !is FrameLayout) {
            attachLegacy(clock, parent)
            return
        }

        // 避免重复注入
        val existingView = root.findViewWithTag<TextView>(POWER_TAG)
        if (existingView != null) {
            return
        }

        val ctx = clock.context
        val tv = TextView(ctx).apply {
            tag = POWER_TAG
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            elevation = ELEVATION
            translationZ = ELEVATION
            text = "--"
        }

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        tv.layoutParams = lp
        root.addView(tv)

        // 测绘就绪监听：必须等待时钟和根容器测量完毕（width > 0）才计算初始绝对位置
        val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (clock.width > 0 && root.width > 0) {
                    val cfg = ConfigBridge.query(ctx)
                    applyPositionAndStyle(tv, clock, root, cfg)
                }
            }
        }
        clock.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)

        // 注册 ContentObserver 实时刷新配置（主 app 改配置毫秒级生效）
        ConfigBridge.ensureObserver(ctx) { freshCfg ->
            applyPositionAndStyle(tv, clock, root, freshCfg)
            tv.text = readPower(ctx, freshCfg)
        }

        // 启动主轮询刷新循环
        val handler = Handler(Looper.getMainLooper())
        val refreshRunnable = object : Runnable {
            override fun run() {
                if (!tv.isAttachedToWindow) return
                val cfg = ConfigBridge.query(ctx)
                applyPositionAndStyle(tv, clock, root, cfg)
                tv.text = readPower(ctx, cfg)
                val refreshMs = ConfigBridge.longOf(cfg, "hook_refresh_ms", 1000L).coerceIn(500, 5000)
                handler.postDelayed(this, refreshMs)
            }
        }
        tv.post(refreshRunnable)

        // 开机平稳度过：系统启动 1 秒和 3 秒后分别再次校对，确保系统通知栏与图标完全排版稳定
        handler.postDelayed({ if (tv.isAttachedToWindow) applyPositionAndStyle(tv, clock, root, ConfigBridge.query(ctx)) }, 1200)
        handler.postDelayed({ if (tv.isAttachedToWindow) applyPositionAndStyle(tv, clock, root, ConfigBridge.query(ctx)) }, 3200)
    }

    /** 降级方案：插入时钟父容器 */
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

    /**
     * 位置计算核心：动态识别时钟方向，应用相对锚点与偏移，彻底根治开机位置丢失。
     */
    private fun applyPositionAndStyle(
        tv: TextView,
        clock: View,
        root: FrameLayout,
        cfg: Map<String, String>
    ) {
        val ctx = tv.context

        // 1. 显示开关
        val enabled = ConfigBridge.boolOf(cfg, "hook_enabled", true)
        tv.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return

        // 2. 颜色与字号
        tv.textSize = ConfigBridge.floatOf(cfg, "hook_text_size", 11f)
        val customColor = ConfigBridge.intOf(cfg, "hook_text_color", Color.WHITE)
        val adaptColor = ConfigBridge.boolOf(cfg, "hook_adapt_color", false)
        if (adaptColor && clock is TextView) {
            tv.setTextColor(clock.currentTextColor)
        } else {
            tv.setTextColor(customColor)
        }

        // 3. 测量数据保护：根容器或时钟未测绘完成时暂不改动 layoutParams，防止被清零
        if (root.width <= 0 || clock.width <= 0) {
            return
        }

        // 4. 计算屏幕真实绝对坐标
        val rootLoc = IntArray(2)
        val clockLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        clock.getLocationOnScreen(clockLoc)

        val rootWidth = root.width
        val clockRelativeX = clockLoc[0] - rootLoc[0] // 时钟相对于 root 的 X 坐标
        val isClockOnLeftSide = clockRelativeX < (rootWidth / 2) // 时钟是否在左半边（全面屏挖孔屏主流）

        val side = ConfigBridge.intOf(cfg, "hook_side", 0) // 0=时钟右侧, 1=时钟左侧, 2=状态栏最右, 3=状态栏最左
        val ox = ConfigBridge.intOf(cfg, "hook_offset_x", 0)
        val oy = ConfigBridge.intOf(cfg, "hook_offset_y", 0)

        val lp = tv.layoutParams as? FrameLayout.LayoutParams ?: return

        // 垂直偏移统一使用 translationY，平滑且不破坏原有重力排列
        tv.translationY = oy.toFloat()

        when (side) {
            1 -> { // 锚点：时钟左侧
                if (isClockOnLeftSide) {
                    lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    val targetLeft = (clockRelativeX - tv.width - dp(ctx, 4) + ox).coerceAtLeast(0)
                    lp.leftMargin = targetLeft
                    lp.rightMargin = 0
                } else {
                    lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    val clockRightDist = rootWidth - (clockRelativeX + clock.width)
                    val targetRight = (clockRightDist + clock.width + dp(ctx, 4) - ox).coerceAtLeast(0)
                    lp.rightMargin = targetRight
                    lp.leftMargin = 0
                }
            }
            2 -> { // 锚点：状态栏最右侧
                lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                lp.rightMargin = (dp(ctx, 8) - ox).coerceAtLeast(0)
                lp.leftMargin = 0
            }
            3 -> { // 锚点：状态栏最左侧
                lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                lp.leftMargin = (dp(ctx, 8) + ox).coerceAtLeast(0)
                lp.rightMargin = 0
            }
            else -> { // 0：时钟右侧（默认）
                if (isClockOnLeftSide) {
                    lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    val targetLeft = (clockRelativeX + clock.width + dp(ctx, 4) + ox).coerceAtLeast(0)
                    lp.leftMargin = targetLeft
                    lp.rightMargin = 0
                } else {
                    lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    val clockRightDist = rootWidth - (clockRelativeX + clock.width)
                    val targetRight = (clockRightDist - tv.width - dp(ctx, 4) - ox).coerceAtLeast(0)
                    lp.rightMargin = targetRight
                    lp.leftMargin = 0
                }
            }
        }

        runCatching { tv.layoutParams = lp }
    }

    /** 状态栏根容器（向上查找顶层 FrameLayout） */
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

    /** SystemUI 进程内使用系统签名权限读取 BatteryManager 与广播 */
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
                parts.add(
                    if (unit) {
                        if (kotlin.math.abs(w) >= 10) String.format("%.1fW", w) else String.format("%.2fW", w)
                    } else {
                        if (kotlin.math.abs(w) >= 10) String.format("%.1f", w) else String.format("%.2f", w)
                    }
                )
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_current", false)) {
                parts.add(
                    if (unit) {
                        if (kotlin.math.abs(ma) >= 1000) String.format("%.2fA", ma / 1000.0) else "${ma}mA"
                    } else {
                        if (kotlin.math.abs(ma) >= 1000) String.format("%.2f", ma / 1000.0) else "$ma"
                    }
                )
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
        } catch (_: Exception) {
            "--W"
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
