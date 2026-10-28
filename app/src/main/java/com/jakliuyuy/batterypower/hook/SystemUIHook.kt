package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jakliuyuy.batterypower.battery.BatteryReader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook SystemUI 状态栏时钟，注入实时电池功率监视。
 *
 * 核心升级（满足用户二选一需求）：
 * 1. 模式 A：【独立覆盖图层（默认推荐）】
 *    - 挂载于状态栏顶层 FrameLayout 根容器（elevation=200f），悬浮于通知图层上方。
 *    - 时钟保持原生父级不动，绝对不挤占通知图标物理空间，微调时直接覆盖在通知上方。
 * 2. 模式 B：【并排流式图层】
 *    - 与时钟在同一水平流式包装器内紧密并排，会挤压通知图标位置。
 * 3. 字体 1:1 克隆 ColorOS 17 原生 Typeface（OPlus Sans）、字重、字号与基线。
 * 4. 颜色与深浅模式 0 延迟秒级自适应。
 */
object SystemUIHook {

    private val CANDIDATES = listOf(
        "com.oplus.systemui.statusbar.policy.Clock",
        "com.android.systemui.statusbar.policy.Clock",
        "com.oplusos.systemui.statusbar.policy.Clock"
    )

    private const val POWER_VIEW_TAG = "bp_power_tv_tag"
    private const val WRAPPER_TAG = "bp_clock_wrapper_tag"
    private const val ELEVATION_TOP = 200f

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
                    if (!isStatusBarClock(clock)) return
                    inject(clock)
                }
            }
        )
    }

    /**
     * 判断是否为常驻状态栏的时钟，排除锁屏与下拉快捷设置时钟
     */
    private fun isStatusBarClock(clock: View): Boolean {
        return try {
            val resName = runCatching {
                clock.resources.getResourceEntryName(clock.id)
            }.getOrNull() ?: ""

            // 锁屏与下拉大时钟排除
            val lowerRes = resName.lowercase()
            if (lowerRes.contains("keyguard") || lowerRes.contains("qs") || lowerRes.contains("header")) {
                return false
            }

            // 检查祖先视图链
            var p: View? = clock.parent as? View
            var depth = 0
            while (p != null && depth++ < 12) {
                val pCls = p.javaClass.name.lowercase()
                val pRes = runCatching { p.resources.getResourceEntryName(p.id) }.getOrNull()?.lowercase() ?: ""
                if (pCls.contains("keyguard") || pRes.contains("keyguard") || pCls.contains("quicksettings")) {
                    return false
                }
                p = p.parent as? View
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun inject(clock: View) {
        val rawParent = clock.parent as? ViewGroup ?: return
        val rootFrame = findStatusBarRoot(clock) ?: (rawParent as? FrameLayout)
        val ctx = clock.context

        // 查找或创建共享的 TextView
        var tv = rootFrame?.findViewWithTag<TextView>(POWER_VIEW_TAG)
            ?: (clock.parent as? ViewGroup)?.findViewWithTag(POWER_VIEW_TAG)

        if (tv == null) {
            tv = TextView(ctx).apply {
                tag = POWER_VIEW_TAG
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = (clock as? TextView)?.includeFontPadding ?: false
                text = "--"
            }
        }

        // 同步时钟字体风格
        cloneClockTypography(tv, clock)

        // 首次渲染与配置
        val initialCfg = ConfigBridge.query(ctx)
        applyLayerLayout(tv, clock, rawParent, rootFrame, initialCfg)
        tv.text = readSystemPower(ctx, initialCfg)

        // 主轮询循环（生命周期守护）
        val handler = Handler(Looper.getMainLooper())
        val refreshRunnable = object : Runnable {
            override fun run() {
                val cfg = ConfigBridge.query(ctx)
                applyLayerLayout(tv, clock, rawParent, rootFrame, cfg)
                tv.text = readSystemPower(ctx, cfg)

                val refreshMs = ConfigBridge.longOf(
                    cfg, "hook_refresh_ms",
                    ConfigBridge.longOf(cfg, "refresh_ms", 1000L)
                ).coerceIn(300, 5000)
                handler.postDelayed(this, refreshMs)
            }
        }

        // 注册实时配置监听，支持图层模式/左右侧/偏移/颜色/全局刷新频率热更新
        ConfigBridge.ensureObserver(ctx) { freshCfg ->
            applyLayerLayout(tv, clock, rawParent, rootFrame, freshCfg)
            tv.text = readSystemPower(ctx, freshCfg)
            // 立即以最新刷新频率重置主轮询节奏
            handler.removeCallbacks(refreshRunnable)
            val freshMs = ConfigBridge.longOf(
                freshCfg, "hook_refresh_ms",
                ConfigBridge.longOf(freshCfg, "refresh_ms", 1000L)
            ).coerceIn(300, 5000)
            handler.postDelayed(refreshRunnable, freshMs)
        }

        // 实时时钟颜色与字体自适应守护（深浅色模式秒级自适应）
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (!tv.isAttachedToWindow || !clock.isAttachedToWindow) return@OnPreDrawListener true
            if (clock is TextView) {
                cloneClockTypography(tv, clock)
                val cfg = ConfigBridge.query(ctx)
                val adapt = ConfigBridge.boolOf(cfg, "hook_adapt_color", true)
                if (adapt) {
                    syncClockColors(tv, clock)
                }
            }
            true
        }
        clock.viewTreeObserver?.addOnPreDrawListener(preDrawListener)

        // 监听 layout 变化动态修正坐标与排版
        clock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val cfg = ConfigBridge.query(ctx)
            applyLayerLayout(tv, clock, rawParent, rootFrame, cfg)
        }

        // 息屏/全屏恢复监听
        clock.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                handler.removeCallbacks(refreshRunnable)
                handler.post(refreshRunnable)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })

        handler.post(refreshRunnable)
    }

    /**
     * 核心排版引擎：支持“覆盖图层（不占通知，推荐）”与“并排流式图层（占用通知）”两种模式
     */
    private fun applyLayerLayout(
        tv: TextView,
        clock: View,
        rawParent: ViewGroup,
        rootFrame: FrameLayout?,
        cfg: Map<String, String>
    ) {
        val enabled = ConfigBridge.boolOf(cfg, "hook_enabled", true)
        if (!enabled) {
            tv.visibility = View.GONE
            return
        } else {
            tv.visibility = View.VISIBLE
        }

        // 字体与字号克隆（支持跟随系统时钟等大）
        cloneClockTypography(tv, clock)
        val followClockSize = ConfigBridge.boolOf(cfg, "hook_follow_clock_size", true)
        val customTextSize = ConfigBridge.floatOf(cfg, "hook_text_size", 0f)
        if (followClockSize && clock is TextView && clock.textSize > 0f) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.textSize)
        } else if (customTextSize > 0f) {
            tv.textSize = customTextSize
        } else if (clock is TextView && clock.textSize > 0f) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.textSize)
        }

        // 颜色自适应
        val adaptColor = ConfigBridge.boolOf(cfg, "hook_adapt_color", true)
        if (adaptColor && clock is TextView) {
            syncClockColors(tv, clock)
        } else {
            val customColor = ConfigBridge.intOf(cfg, "hook_text_color", Color.WHITE)
            tv.setTextColor(customColor)
        }

        val layerMode = ConfigBridge.intOf(cfg, "hook_layer_mode", 1) // 默认 1=并排图层(最稳)，0=覆盖图层
        val side = ConfigBridge.intOf(cfg, "hook_side", 0)           // 0=时钟右侧, 1=时钟左侧
        val ox = ConfigBridge.intOf(cfg, "hook_offset_x", 0)
        val oy = ConfigBridge.intOf(cfg, "hook_offset_y", 0)
        val ctx = tv.context

        if (layerMode == 0 && rootFrame != null) {
            // ==========================================
            // 模式 A：独立覆盖图层（悬浮在状态栏顶层）
            // ==========================================
            val currentParent = tv.parent as? ViewGroup
            if (currentParent != rootFrame) {
                currentParent?.removeView(tv)
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
                tv.elevation = ELEVATION_TOP
                tv.translationZ = ELEVATION_TOP
                rootFrame.addView(tv, lp)
            }

            if (tv.width == 0 || tv.height == 0) {
                tv.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
            val tvW = if (tv.width > 0) tv.width else tv.measuredWidth
            val tvH = if (tv.height > 0) tv.height else tv.measuredHeight

            val rootLoc = IntArray(2)
            val clockLoc = IntArray(2)
            rootFrame.getLocationOnScreen(rootLoc)
            clock.getLocationOnScreen(clockLoc)

            val clockXInRoot = clockLoc[0] - rootLoc[0]
            val clockYInRoot = clockLoc[1] - rootLoc[1]

            val targetX = if (side == 1) {
                clockXInRoot - tvW - dp(ctx, 4) + ox
            } else {
                clockXInRoot + clock.width + dp(ctx, 4) + ox
            }

            val targetY = clockYInRoot + (clock.height - tvH) / 2 + oy

            tv.translationX = targetX.toFloat()
            tv.translationY = targetY.toFloat()

        } else {
            // ==========================================
            // 模式 B：原生流式并排（100% 稳定，同生共死）
            // ==========================================
            var wrapper = rawParent.findViewWithTag<LinearLayout>(WRAPPER_TAG)
            if (wrapper == null && clock.parent is LinearLayout && (clock.parent as View).tag == WRAPPER_TAG) {
                wrapper = clock.parent as LinearLayout
            }

            if (wrapper == null) {
                val originalLp = clock.layoutParams
                val clockIndex = rawParent.indexOfChild(clock)

                wrapper = LinearLayout(ctx).apply {
                    tag = WRAPPER_TAG
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isBaselineAligned = true
                    layoutParams = originalLp
                }

                rawParent.removeView(clock)
                val clockLp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }

                (tv.parent as? ViewGroup)?.removeView(tv)
                val tvLp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val m = dp(ctx, 4)
                    if (side == 1) rightMargin = m else leftMargin = m
                }

                if (side == 1) {
                    wrapper.addView(tv, tvLp)
                    wrapper.addView(clock, clockLp)
                } else {
                    wrapper.addView(clock, clockLp)
                    wrapper.addView(tv, tvLp)
                }

                rawParent.addView(wrapper, clockIndex)
            } else {
                if (tv.parent != wrapper) {
                    (tv.parent as? ViewGroup)?.removeView(tv)
                    val tvLp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        val m = dp(ctx, 4)
                        if (side == 1) rightMargin = m else leftMargin = m
                    }
                    val desiredIdx = if (side == 1) 0 else 1
                    wrapper.addView(tv, desiredIdx.coerceAtMost(wrapper.childCount), tvLp)
                } else {
                    val currentTvIdx = wrapper.indexOfChild(tv)
                    val desiredTvIdx = if (side == 1) 0 else 1
                    if (currentTvIdx != desiredTvIdx) {
                        wrapper.removeView(tv)
                        val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            val m = dp(ctx, 4)
                            if (side == 1) rightMargin = m else leftMargin = m
                        }
                        wrapper.addView(tv, desiredTvIdx.coerceAtMost(wrapper.childCount), lp)
                    }
                }
            }

            tv.translationX = ox.toFloat()
            tv.translationY = oy.toFloat()
        }
    }

    /** 状态栏根容器（向上查找顶层 FrameLayout） */
    private fun findStatusBarRoot(v: View): FrameLayout? {
        var cur: View? = v
        var lastFrame: FrameLayout? = null
        var guard = 0
        while (cur != null && guard++ < 15) {
            if (cur is FrameLayout) lastFrame = cur
            cur = cur.parent as? View
        }
        return lastFrame
    }

    /** 1:1 像素级拟合 ColorOS 17 时钟字体与排版特征 */
    private fun cloneClockTypography(tv: TextView, clock: View) {
        if (clock !is TextView) return
        try {
            val tf = clock.typeface ?: clock.paint.typeface
            if (tf != null && tv.typeface != tf) {
                tv.typeface = tf
            }
            tv.paint.isFakeBoldText = clock.paint.isFakeBoldText
            if (tf?.isBold == true) {
                tv.paint.isFakeBoldText = true
            }
            tv.paintFlags = clock.paintFlags
            tv.letterSpacing = clock.letterSpacing
            tv.includeFontPadding = clock.includeFontPadding
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tv.fontFeatureSettings = clock.fontFeatureSettings
            }
            tv.paint.isAntiAlias = true
            tv.paint.isSubpixelText = true
        } catch (_: Exception) {
        }
    }

    /** 实时同步时钟颜色与透明度，深浅色模式秒级自适应 */
    private fun syncClockColors(tv: TextView, clock: TextView) {
        try {
            if (clock.textColors != null && tv.textColors != clock.textColors) {
                tv.setTextColor(clock.textColors)
            } else {
                val c = clock.currentTextColor
                if (c != 0 && tv.currentTextColor != c) {
                    tv.setTextColor(c)
                }
            }
            tv.alpha = clock.alpha
        } catch (_: Exception) {
        }
    }

    /** 读取系统电池数据 */
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

            var isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val invert = ConfigBridge.boolOf(cfg, "polarity_invert", false)
            if (invert) isCharging = !isCharging

            val doubleCell = ConfigBridge.boolOf(cfg, "double_cell_fix", false)

            val ma = if (cur == Int.MIN_VALUE) 0 else cur
            var calcW = kotlin.math.abs(ma / 1000.0 * (mv / 1000.0))
            if (doubleCell) calcW *= 2.0

            val sample = BatteryReader.Sample(
                powerW = if (isCharging) calcW else -calcW,
                currentMa = if (doubleCell) ma * 2 else ma,
                voltageMv = mv,
                level = cap.coerceIn(0, 100),
                tempC = temp / 10.0,
                status = if (isCharging) "Charging" else "Discharging",
                isCharging = isCharging,
                source = "system"
            )

            val unit = ConfigBridge.boolOf(cfg, "hook_show_unit", ConfigBridge.boolOf(cfg, "show_unit", true))
            val parts = mutableListOf<String>()

            if (ConfigBridge.boolOf(cfg, "hook_show_power", true)) {
                parts.add(sample.powerText(unit))
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_current", false)) {
                parts.add(sample.currentText(unit))
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_voltage", false)) {
                parts.add(sample.voltageText(unit))
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_temp", false) && temp > 0) {
                parts.add(sample.tempText(unit))
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_level", false)) {
                parts.add(sample.levelText(unit))
            }

            if (parts.isEmpty()) sample.powerText(unit) else parts.joinToString(" ")
        } catch (_: Exception) {
            "--W"
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
