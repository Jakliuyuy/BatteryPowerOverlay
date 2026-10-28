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
import android.widget.LinearLayout
import android.widget.TextView
import com.jakliuyuy.batterypower.battery.BatteryReader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook SystemUI 状态栏时钟，注入实时电池功率监视。
 *
 * 核心优化（特别针对 ColorOS 17 / 一加 Ace 6T）：
 * 1. 字体风格 1:1 克隆：完美同步时钟原生 Typeface（OPlus Sans）、字重（FakeBold / Weight）、字间距与字体特性。
 * 2. 默认字号 1:1 等大对齐：直接继承 clock.textSize（单位 PX），使功率高度与时钟分毫不差。
 * 3. 颜色零延迟自适应：支持 ColorStateList 整体克隆与 PreDraw 监听，浅色界面秒变深黑，深色界面秒变纯白，彻底解决浅色模式下泛白看不清。
 * 4. 垂直基线对齐：isBaselineAligned 开启，文本底端完全对齐。
 * 5. 左右锚点热切换、息屏假死唤醒守护与精准时钟过滤。
 */
object SystemUIHook {

    private val CANDIDATES = listOf(
        "com.oplus.systemui.statusbar.policy.Clock",
        "com.android.systemui.statusbar.policy.Clock",
        "com.oplusos.systemui.statusbar.policy.Clock"
    )

    private const val WRAPPER_TAG = "bp_clock_wrapper_tag"
    private const val POWER_VIEW_TAG = "bp_power_tv_tag"

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
            while (p != null && depth++ < 10) {
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
        val ctx = clock.context

        val wrapper: LinearLayout
        val tv: TextView

        // 检查是否已经包裹过
        if (rawParent.tag == WRAPPER_TAG) {
            wrapper = rawParent as LinearLayout
            tv = wrapper.findViewWithTag(POWER_VIEW_TAG) ?: return
        } else {
            // 避免重复包裹
            val existingWrapper = rawParent.findViewWithTag<LinearLayout>(WRAPPER_TAG)
            if (existingWrapper != null && existingWrapper.indexOfChild(clock) != -1) {
                wrapper = existingWrapper
                tv = wrapper.findViewWithTag(POWER_VIEW_TAG) ?: return
            } else {
                // 构建流式自适应包装器，原位替换时钟并继承所有 LayoutParams
                val originalLp = clock.layoutParams
                val clockIndex = rawParent.indexOfChild(clock)

                wrapper = LinearLayout(ctx).apply {
                    tag = WRAPPER_TAG
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isBaselineAligned = true
                    layoutParams = originalLp
                }

                tv = TextView(ctx).apply {
                    tag = POWER_VIEW_TAG
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = (clock as? TextView)?.includeFontPadding ?: false
                    text = "--"
                }

                // 首次将字体与时钟完全同步
                cloneClockTypography(tv, clock)

                // 从原父级移出并移入包装器
                rawParent.removeView(clock)
                val clockLp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }

                val cfg = ConfigBridge.query(ctx)
                val side = ConfigBridge.intOf(cfg, "hook_side", 0) // 0=时钟右侧, 1=时钟左侧

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
            }
        }

        // 首次渲染与配置
        val initialCfg = ConfigBridge.query(ctx)
        applyStyleAndPosition(tv, clock, wrapper, initialCfg)
        tv.text = readSystemPower(ctx, initialCfg)

        // 注册实时监听，支持动态切换左右侧与微调
        ConfigBridge.ensureObserver(ctx) { freshCfg ->
            applyStyleAndPosition(tv, clock, wrapper, freshCfg)
            tv.text = readSystemPower(ctx, freshCfg)
        }

        // 实时时钟颜色与字体自适应守护（浅色应用变黑，深色应用变白瞬间完全同步）
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (!tv.isAttachedToWindow || !clock.isAttachedToWindow) return@OnPreDrawListener true
            if (clock is TextView) {
                // 实时克隆字体与属性
                cloneClockTypography(tv, clock)

                val cfg = ConfigBridge.query(ctx)
                val adapt = ConfigBridge.boolOf(cfg, "hook_adapt_color", true)
                if (adapt) {
                    if (clock.textColors != null && tv.textColors != clock.textColors) {
                        tv.setTextColor(clock.textColors)
                    } else if (tv.currentTextColor != clock.currentTextColor) {
                        tv.setTextColor(clock.currentTextColor)
                    }
                }
            }
            true
        }
        clock.viewTreeObserver?.addOnPreDrawListener(preDrawListener)

        // 监听 layout 变化同步颜色与基准尺寸
        clock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (clock is TextView) {
                cloneClockTypography(tv, clock)
                val cfg = ConfigBridge.query(ctx)
                if (ConfigBridge.boolOf(cfg, "hook_adapt_color", true)) {
                    if (clock.textColors != null) tv.setTextColor(clock.textColors)
                }
            }
        }

        // 启动主轮询循环（生命周期守护，防止息屏/旋转/切后台后轮询死亡）
        val handler = Handler(Looper.getMainLooper())
        val refreshRunnable = object : Runnable {
            override fun run() {
                val cfg = ConfigBridge.query(ctx)
                applyStyleAndPosition(tv, clock, wrapper, cfg)
                tv.text = readSystemPower(ctx, cfg)

                val refreshMs = ConfigBridge.longOf(cfg, "hook_refresh_ms", 1000L).coerceIn(500, 5000)
                handler.postDelayed(this, refreshMs)
            }
        }

        // 注册 Attach/Detach 监听，息屏灭屏或全屏退出后即刻唤醒
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
     * 1:1 克隆系统时钟字体与排版特征（ColorOS 17 / 一加 OPlus Sans 专属中等字重）
     */
    private fun cloneClockTypography(tv: TextView, clock: View) {
        if (clock !is TextView) return
        try {
            if (tv.typeface != clock.typeface) {
                tv.typeface = clock.typeface
            }
            tv.paint.isFakeBoldText = clock.paint.isFakeBoldText
            tv.paintFlags = clock.paintFlags
            tv.letterSpacing = clock.letterSpacing
            tv.includeFontPadding = clock.includeFontPadding
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tv.fontFeatureSettings = clock.fontFeatureSettings
            }
        } catch (_: Exception) {
        }
    }

    private fun applyStyleAndPosition(
        tv: TextView,
        clock: View,
        wrapper: LinearLayout,
        cfg: Map<String, String>
    ) {
        val enabled = ConfigBridge.boolOf(cfg, "hook_enabled", true)
        if (!enabled) {
            tv.visibility = View.GONE
            return
        }

        // 状态联动：时钟隐藏（如全屏游戏/视频）时，功率文本同步隐藏
        if (clock.visibility != View.VISIBLE) {
            tv.visibility = View.GONE
            return
        } else {
            tv.visibility = View.VISIBLE
        }

        // 字体风格克隆
        cloneClockTypography(tv, clock)

        // 字号设置：若用户选择跟随系统（0 或默认），则直接使用时钟真实像素大小；否则采用自定义字号
        val customTextSize = ConfigBridge.floatOf(cfg, "hook_text_size", 0f)
        if (customTextSize <= 0f && clock is TextView && clock.textSize > 0f) {
            // 分毫不差，直接对齐时钟原生字号
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.textSize)
        } else if (customTextSize > 0f) {
            tv.textSize = customTextSize
        } else if (clock is TextView) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.textSize)
        } else {
            tv.textSize = 13f
        }

        // 动态调整左右顺序（支持实时热生效）
        val targetSide = ConfigBridge.intOf(cfg, "hook_side", 0) // 0=右侧, 1=左侧
        val currentTvIdx = wrapper.indexOfChild(tv)
        val currentClockIdx = wrapper.indexOfChild(clock)

        val desiredTvIdx = if (targetSide == 1) 0 else 1
        if (currentTvIdx != -1 && currentClockIdx != -1 && currentTvIdx != desiredTvIdx) {
            wrapper.removeView(tv)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                val m = dp(tv.context, 4)
                if (targetSide == 1) rightMargin = m else leftMargin = m
            }
            wrapper.addView(tv, desiredTvIdx.coerceAtMost(wrapper.childCount), lp)
        }

        // 颜色自适应（默认跟随系统时钟当前文本色）
        val adaptColor = ConfigBridge.boolOf(cfg, "hook_adapt_color", true)
        if (adaptColor && clock is TextView) {
            if (clock.textColors != null) {
                tv.setTextColor(clock.textColors)
            } else {
                tv.setTextColor(clock.currentTextColor)
            }
        } else {
            val customColor = ConfigBridge.intOf(cfg, "hook_text_color", Color.WHITE)
            tv.setTextColor(customColor)
        }

        // 硬件加速平移微调
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
