package com.jakliuyuy.batterypower.hook

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jakliuyuy.batterypower.battery.BatteryReader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference

/**
 * Hook SystemUI 状态栏时钟，注入实时电池功率监视。
 *
 * 核心架构特性：
 * 1. 模式 A：【独立覆盖图层（默认推荐）】
 *    - 挂载于状态栏顶层 FrameLayout 根容器（elevation=200f），悬浮于通知图层上方。
 *    - 时钟保持原生父级不动，绝对不挤占通知图标物理空间，微调时直接覆盖在通知上方。
 * 2. 模式 B：【并排流式图层】
 *    - 与时钟在同一水平流式包装器内紧密并排，会挤压通知图标位置。
 * 3. 【横屏与全屏沉浸接管引擎（100% SystemUI 核心进程原生驻留）】
 *    - 毫秒级检测状态栏显隐切换（进入游戏全屏、全屏视频、测试工具或状态栏沉浸隐藏）。
 *    - 状态栏可见时：全屏浮层 100% 隐藏，状态栏时钟文字正常显示。
 *    - 状态栏全屏隐藏时：全屏浮层毫秒级自动浮现在当前屏幕最顶居中（默认）或用户偏好位置。
 *    - 注入 FLAG_NOT_TOUCHABLE 与 FLAG_NOT_FOCUSABLE：触控操作 100% 穿透至底层游戏，零按键阻挡与误触。
 *    - 划掉主 App 依然在系统 SystemUI 中永久驻留自适应生效。
 * 4. 字体 1:1 克隆 ColorOS 17 原生 Typeface（OPlus Sans）、字重、字号与基线。
 * 5. 颜色与深浅模式 0 延迟秒级自适应。
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

    // 全局唯一生命周期与调度守护器（彻底杜绝屏幕旋转或多次 attach 导致的 Handler/Runnable 泄漏）
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRefreshRunnable: Runnable? = null
    private var activeSyncRunnable: Runnable? = null
    private var activeClockRef: WeakReference<View>? = null
    private var activeTvRef: WeakReference<TextView>? = null
    private var activePreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var activeConfigObserver: ((Map<String, String>) -> Unit)? = null
    private var cachedClockTypeface: android.graphics.Typeface? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clockClass = CANDIDATES.firstNotNullOfOrNull {
            runCatching { XposedHelpers.findClass(it, lpparam.classLoader) }.getOrNull()
        } ?: return

        // 1. Hook onAttachedToWindow 实现主时钟精准识别与单例锁定挂载（Primary Clock Locking）
        XposedHelpers.findAndHookMethod(
            clockClass,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val clock = param.thisObject as? View ?: return
                    onClockAttached(clock)
                }
            }
        )

        // 2. 毫秒级原生系统可见性拦截（仅响应主时钟的可见性变更，防止非主时钟扰动）
        val visibilityHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val clock = param.thisObject as? View ?: return
                if (!isStatusBarClock(clock)) return
                val currentPrimary = activeClockRef?.get()
                if (currentPrimary == null || currentPrimary === clock) {
                    triggerSyncNow()
                }
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                clockClass,
                "onVisibilityAggregated",
                Boolean::class.javaPrimitiveType,
                visibilityHook
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                clockClass,
                "onWindowVisibilityChanged",
                Int::class.javaPrimitiveType,
                visibilityHook
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                clockClass,
                "setVisibility",
                Int::class.javaPrimitiveType,
                visibilityHook
            )
        }
    }

    private fun triggerSyncNow() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            activeSyncRunnable?.run()
        } else {
            mainHandler.post { activeSyncRunnable?.run() }
        }
    }

    private val NEGATIVE_KEYWORDS = listOf(
        "recent",
        "overview",
        "taskbar",
        "keyguard",
        "lockscreen",
        "keyguard_lock",
        "qs",
        "header",
        "quicksettings",
        "controlcenter",
        "split",
        "floating"
    )

    private fun isNegativeName(name: String): Boolean {
        if (name.isEmpty()) return false
        val lower = name.lowercase()
        // 关键白名单豁免：若名称属于合法的时钟命名（包含 "clock"），
        // 且不包含明确的负向词（如 keyguard, lockscreen, recent, overview, qs 等），严禁误杀
        if (lower.contains("clock")) {
            return lower.contains("keyguard") ||
                    lower.contains("lockscreen") ||
                    lower.contains("recent") ||
                    lower.contains("overview") ||
                    lower.contains("taskbar") ||
                    lower.contains("qs") ||
                    lower.contains("header") ||
                    lower.contains("quicksettings") ||
                    lower.contains("controlcenter") ||
                    lower.contains("split") ||
                    lower.contains("floating")
        }
        for (kw in NEGATIVE_KEYWORDS) {
            if (lower.contains(kw)) return true
        }
        return false
    }

    /**
     * 检测视图是否具备原生状态栏容器特征（PhoneStatusBarView 或 status_bar 资源标识）
     */
    private fun isPhoneStatusBarAncestor(view: View): Boolean {
        val cls = view.javaClass.name.lowercase()
        if (cls.contains("phonestatusbarview") || cls.contains("statusbar.phone")) return true
        val res = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()?.lowercase() ?: ""
        if (res.isNotEmpty() && !isNegativeName(res)) {
            if (res == "status_bar" || res == "status_bar_contents" || res == "status_bar_left_side" || res == "phone_status_bar" || res == "status_bar_area") {
                return true
            }
        }
        return false
    }

    /**
     * 判断视图祖先链中是否存在原生 PhoneStatusBarView 或合法的 status_bar 资源标识
     */
    private fun hasPhoneStatusBarAncestor(view: View): Boolean {
        var p: View? = view.parent as? View
        var depth = 0
        while (p != null && depth++ < 16) {
            if (isPhoneStatusBarAncestor(p)) return true
            p = p.parent as? View
        }
        return false
    }

    /**
     * 处理时钟 Attach 事件：实现主状态栏时钟单例锁定（Primary Clock Locking），
     * 杜绝后续多任务/二级时钟破坏性覆盖。
     */
    private fun onClockAttached(clock: View) {
        if (!isStatusBarClock(clock)) return

        val current = activeClockRef?.get()
        if (current != null && current.isAttachedToWindow) {
            // 同一时钟实例再次触发 attach，保持锁定并刷新
            if (current === clock) {
                triggerSyncNow()
                return
            }

            // 主时钟锁定保护：若已有主时钟是优质原生主时钟，绝对禁止后续非主时钟篡改单例
            val curHasPhoneBar = hasPhoneStatusBarAncestor(current)
            val newHasPhoneBar = hasPhoneStatusBarAncestor(clock)

            if (curHasPhoneBar && !newHasPhoneBar) {
                return
            }
            if (curHasPhoneBar && newHasPhoneBar) {
                // 已有正宗主状态栏时钟保持存活，维持锁定
                return
            }
        }

        // 锁定主状态栏时钟并完成注入挂载
        inject(clock)
    }

    /**
     * 判断是否为常驻状态栏的时钟：
     * 正向精准识别：祖先链中具备 PhoneStatusBarView 或 status_bar 资源标识
     * 负向全面排除：自身及祖先链排除 recent, overview, taskbar, keyguard, lock, qs, header, quicksettings, controlcenter, split, floating
     */
    private fun isStatusBarClock(clock: View): Boolean {
        return try {
            // 1. 检查时钟自身类名与资源名
            val clockCls = clock.javaClass.name.lowercase()
            if (isNegativeName(clockCls)) return false

            val resName = runCatching {
                clock.resources.getResourceEntryName(clock.id)
            }.getOrNull() ?: ""
            if (isNegativeName(resName)) return false

            // 2. 检查祖先视图链（负向全面排除 + 正向特征检测）
            var p: View? = clock.parent as? View
            var depth = 0
            var hasPositiveAncestor = false

            while (p != null && depth++ < 16) {
                val pCls = p.javaClass.name.lowercase()
                if (isNegativeName(pCls)) return false

                val pRes = runCatching { p.resources.getResourceEntryName(p.id) }.getOrNull()?.lowercase() ?: ""
                if (pRes.isNotEmpty() && isNegativeName(pRes)) return false

                if (isPhoneStatusBarAncestor(p)) {
                    hasPositiveAncestor = true
                }

                p = p.parent as? View
            }

            hasPositiveAncestor
        } catch (_: Exception) {
            false
        }
    }

    // 施密特触发器物理边界状态（迟滞区间防抖，消除边缘 1px 抖动引发的高频频闪）
    private var lastPhysicalVisible = true

    /**
     * 健壮检测主状态栏时钟是否在当前屏幕物理可见：
     * 1. 消除未排版尺寸误杀（width/height <= 0 时不判定为越界）；
     * 2. 解除 isShown 假阴性连坐（仅检查直接与紧邻父级非 GONE/INVISIBLE，且透明度有效）；
     * 3. 物理视口 4 边界检测（左/上/右/下）与施密特迟滞区间（Hysteresis）；
     * 4. 保障普通桌面与常规前台应用下 100% 稳定常驻。
     */
    private fun isClockVisibleOnScreen(clock: View): Boolean {
        if (!clock.isAttachedToWindow) {
            lastPhysicalVisible = false
            return false
        }
        if (clock.visibility != View.VISIBLE) {
            lastPhysicalVisible = false
            return false
        }
        if (clock.windowVisibility != View.VISIBLE) {
            lastPhysicalVisible = false
            return false
        }

        // 祖先链检测：必须全为 View.VISIBLE，且透明度不能淡出为 0
        // 同时拦截 View.GONE 与 View.INVISIBLE（值为 4），杜绝祖先隐藏穿透
        var p: View? = clock.parent as? View
        var depth = 0
        while (p != null && depth++ < 5) {
            if (p.visibility != View.VISIBLE) {
                lastPhysicalVisible = false
                return false
            }
            if (p.alpha <= 0.01f) {
                lastPhysicalVisible = false
                return false
            }
            p = p.parent as? View
        }

        // 时钟自身透明度检查：淡出动画完成则不可见
        if (clock.alpha <= 0.01f) {
            lastPhysicalVisible = false
            return false
        }

        // 物理视口 4 边界检测与施密特迟滞防抖区间
        // 仅在完成 Measure/Layout 且尺寸有效时检测，消除未排版尺寸误杀
        if (clock.width > 0 && clock.height > 0) {
            val loc = IntArray(2)
            clock.getLocationOnScreen(loc)
            val clockX = loc[0]
            val clockY = loc[1]
            val clockW = clock.width
            val clockH = clock.height

            val dm = clock.resources?.displayMetrics
            val screenW = if (dm != null && dm.widthPixels > 0) dm.widthPixels else 1080
            val screenH = if (dm != null && dm.heightPixels > 0) dm.heightPixels else 2400

            // 施密特双阈值迟滞（Hysteresis Deadband）：
            // 移出屏幕判定使用深层阈值 (-12px)，重新进入屏幕使用安全门禁 (0px)
            val isPhysicalVisible = if (lastPhysicalVisible) {
                // 当前为可见状态：只有当完全滑出 4 个物理屏幕边界各 12px 以外时，才判定为不可见
                val outLeft = clockX + clockW < -12
                val outTop = clockY + clockH < -12
                val outRight = clockX > screenW + 12
                val outBottom = clockY > screenH + 12
                !(outLeft || outTop || outRight || outBottom)
            } else {
                // 当前为不可见状态：只有当重新回到屏幕物理边界内时，才恢复为可见
                val inHorizontal = clockX + clockW >= 0 && clockX <= screenW
                val inVertical = clockY + clockH >= 0 && clockY <= screenH
                inHorizontal && inVertical
            }

            lastPhysicalVisible = isPhysicalVisible
            if (!isPhysicalVisible) {
                return false
            }
        }

        return true
    }

    private fun inject(clock: View) {
        val rawParent = clock.parent as? ViewGroup ?: return
        val rootFrame = findStatusBarRoot(clock) ?: (rawParent as? FrameLayout) ?: return
        val ctx = clock.context

        // 查找或创建共享的 TextView
        var tv = rootFrame.findViewWithTag<TextView>(POWER_VIEW_TAG)
            ?: (clock.parent as? ViewGroup)?.findViewWithTag(POWER_VIEW_TAG)

        if (tv == null) {
            tv = TextView(ctx).apply {
                tag = POWER_VIEW_TAG
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = (clock as? TextView)?.includeFontPadding ?: false
                text = "--"
            }
        }

        // 同步并缓存时钟字体风格
        cloneClockTypography(tv, clock)
        (clock as? TextView)?.typeface?.let { cachedClockTypeface = it }

        // 解绑旧时钟的 PreDraw 监听
        activePreDrawListener?.let { oldListener ->
            runCatching {
                activeClockRef?.get()?.viewTreeObserver?.let { vto ->
                    if (vto.isAlive) vto.removeOnPreDrawListener(oldListener)
                }
            }
        }

        activeClockRef = WeakReference(clock)
        var lastClockVisible = isClockVisibleOnScreen(clock)
        var lastClockWidth = clock.width
        var lastClockHeight = clock.height

        // 核心同步刷新机制：同时维护状态栏文字与全屏沉浸浮层
        val checkSync = Runnable {
            val currentClock = activeClockRef?.get()
            val currentTv = activeTvRef?.get()
            val currentCtx = currentClock?.context ?: ctx
            val cfg = ConfigBridge.query(currentCtx)
            val isVis = if (currentClock != null) isClockVisibleOnScreen(currentClock) else false
            val powerText = readSystemPower(currentCtx, cfg)

            // 1. 状态栏文字排版与更新（仅当 clock 与 tv 活跃且在树中时）
            if (currentClock != null && currentTv != null) {
                val currentRawParent = currentClock.parent as? ViewGroup
                val currentRootFrame = findStatusBarRoot(currentClock) ?: (currentRawParent as? FrameLayout)
                if (currentRawParent != null && currentRootFrame != null) {
                    applyLayerLayout(currentTv, currentClock, currentRawParent, currentRootFrame, cfg, isVis)
                    currentTv.text = powerText
                }
            }

            // 2. 全屏沉浸接管浮层状态机
            val hookEnabled = ConfigBridge.boolOf(cfg, "hook_enabled", true)
            val immersiveEnabled = ConfigBridge.boolOf(cfg, "hook_immersive_enabled", false)
            val pm = currentCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = pm?.isInteractive != false
            val km = currentCtx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isLocked = km?.isKeyguardLocked == true

            // 严防锁屏幽灵浮层：仅在非锁屏、屏幕交互亮屏且状态栏全屏隐藏时触发沉浸浮层
            val shouldShowImmersive = !isVis && hookEnabled && immersiveEnabled && isInteractive && !isLocked

            ImmersiveOverlayManager.update(currentCtx, powerText, cfg, currentClock, shouldShowImmersive)
            lastClockVisible = isVis
            if (currentClock != null) {
                lastClockWidth = currentClock.width
                lastClockHeight = currentClock.height
            }
        }
        activeSyncRunnable = checkSync

        // 毫秒级重绘与排版感知监听（兼顾可见性切换与未排版尺寸突变，杜绝 0 宽排版锁死与突跳）
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            val curClock = activeClockRef?.get()
            if (curClock != null) {
                val visibleNow = isClockVisibleOnScreen(curClock)
                val widthNow = curClock.width
                val heightNow = curClock.height
                val sizeChanged = (lastClockWidth <= 0 && widthNow > 0) ||
                        (lastClockHeight <= 0 && heightNow > 0) ||
                        (widthNow != lastClockWidth) ||
                        (heightNow != lastClockHeight)

                if (visibleNow != lastClockVisible || sizeChanged) {
                    lastClockVisible = visibleNow
                    lastClockWidth = widthNow
                    lastClockHeight = heightNow
                    checkSync.run()
                }
            }
            true
        }
        activePreDrawListener = preDrawListener
        if (clock.viewTreeObserver.isAlive) {
            clock.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }

        // 全局唯一主轮询循环管理：清理旧循环，确保任何时刻只有一个任务活跃
        activeRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        val refreshRunnable = object : Runnable {
            override fun run() {
                checkSync.run()
                val curClock = activeClockRef?.get()
                val curCtx = curClock?.context ?: ctx
                val cfg = ConfigBridge.query(curCtx)
                val refreshMs = ConfigBridge.longOf(
                    cfg, "hook_refresh_ms",
                    ConfigBridge.longOf(cfg, "refresh_ms", 1000L)
                ).coerceIn(300, 5000)
                mainHandler.postDelayed(this, refreshMs)
            }
        }
        activeRefreshRunnable = refreshRunnable

        // 注册实时配置监听（使用安全列表分发，防止旋转后失效）
        activeConfigObserver?.let { ConfigBridge.removeObserver(it) }
        val configObserver: (Map<String, String>) -> Unit = { freshCfg ->
            checkSync.run()
            activeRefreshRunnable?.let { r ->
                mainHandler.removeCallbacks(r)
                val freshMs = ConfigBridge.longOf(
                    freshCfg, "hook_refresh_ms",
                    ConfigBridge.longOf(freshCfg, "refresh_ms", 1000L)
                ).coerceIn(300, 5000)
                mainHandler.postDelayed(r, freshMs)
            }
        }
        activeConfigObserver = configObserver
        ConfigBridge.addObserver(ctx, configObserver)

        // 息屏/全屏/Attach 恢复监听
        clock.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (clock.viewTreeObserver.isAlive) {
                    clock.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
                    clock.viewTreeObserver.addOnPreDrawListener(preDrawListener)
                }
                triggerSyncNow()
            }

            override fun onViewDetachedFromWindow(v: View) {
                triggerSyncNow()
            }
        })

        // 首次同步执行与启动轮询
        checkSync.run()
        mainHandler.post(refreshRunnable)
    }

    /**
     * 核心排版引擎：支持“覆盖图层（不占通知，推荐）”与“并排流式图层（占用通知）”两种模式
     */
    private fun applyLayerLayout(
        tv: TextView,
        clock: View,
        rawParent: ViewGroup,
        rootFrame: FrameLayout,
        cfg: Map<String, String>,
        isClockVisible: Boolean
    ) {
        val enabled = ConfigBridge.boolOf(cfg, "hook_enabled", true)
        if (!enabled || !isClockVisible) {
            tv.visibility = View.GONE
            return
        }
        // 若时钟尚未排版完成（width <= 0 或 height <= 0），悬浮文字暂时隐藏以防未排版错位
        if (clock.width <= 0 || clock.height <= 0) {
            tv.visibility = View.INVISIBLE
            return
        }
        tv.visibility = View.VISIBLE

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

        val layerMode = ConfigBridge.intOf(cfg, "hook_layer_mode", 0) // 0=覆盖图层(不占通知), 1=并排图层(占通知)
        val side = ConfigBridge.intOf(cfg, "hook_side", 0)           // 0=时钟右侧, 1=时钟左侧
        val ox = ConfigBridge.intOf(cfg, "hook_offset_x", 0)
        val oy = ConfigBridge.intOf(cfg, "hook_offset_y", 0)
        val ctx = tv.context

        if (layerMode == 0) {
            // ==========================================
            // 模式 A：独立覆盖图层（绝不挤占通知空间）
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

            // 精准预测宽高，避免布局完成前出现 0 宽偏移
            if (tv.width == 0 || tv.height == 0) {
                tv.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
            val tvW = if (tv.width > 0) tv.width else tv.measuredWidth
            val tvH = if (tv.height > 0) tv.height else tv.measuredHeight

            // 计算 clock 在 rootFrame 坐标系中的位置
            val rootLoc = IntArray(2)
            val clockLoc = IntArray(2)
            rootFrame.getLocationOnScreen(rootLoc)
            clock.getLocationOnScreen(clockLoc)

            val clockXInRoot = clockLoc[0] - rootLoc[0]
            val clockYInRoot = clockLoc[1] - rootLoc[1]

            val targetX = if (side == 1) {
                // 时钟左侧悬浮
                clockXInRoot - tvW - dp(ctx, 4) + ox
            } else {
                // 时钟右侧悬浮（覆盖在通知图标层上方）
                clockXInRoot + clock.width + dp(ctx, 4) + ox
            }

            val targetY = (clockYInRoot + (clock.height - tvH) / 2 + oy).coerceAtLeast(0)

            tv.translationX = targetX.toFloat()
            tv.translationY = targetY.toFloat()

        } else {
            // ==========================================
            // 模式 B：并排流式图层（占用通知空间）
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
                // 已有 wrapper，确保 tv 在其中
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

    /**
     * 全屏与横屏沉浸接管管理器（运行在 SystemUI 进程内通过 WindowManager 维护顶层浮层）
     */
    internal object ImmersiveOverlayManager {
        private var wm: WindowManager? = null
        private var container: FrameLayout? = null
        private var tv: TextView? = null
        private var isAdded = false
        private var lastGravity: Int = -1
        private var lastX: Int = Int.MIN_VALUE
        private var lastY: Int = Int.MIN_VALUE
        private var lastStyle: Int = -1

        fun update(
            ctx: Context,
            powerText: String,
            cfg: Map<String, String>,
            clock: View?,
            show: Boolean
        ) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post {
                    update(ctx, powerText, cfg, clock, show)
                }
                return
            }

            if (!show) {
                hide()
                return
            }

            ensureView(ctx, clock)
            val c = container ?: return
            val textV = tv ?: return
            val windowManager = wm ?: return

            textV.text = powerText

            // 样式同步与更新
            val style = ConfigBridge.intOf(cfg, "hook_immersive_style", 1)
            if (style != lastStyle) {
                lastStyle = style
                applyStyle(ctx, textV, style, clock)
            }

            // 布局位置偏好与 X/Y 微调偏移
            val gravityPref = ConfigBridge.intOf(cfg, "hook_immersive_gravity", 1)
            val targetGravity = when (gravityPref) {
                0 -> Gravity.TOP or Gravity.START
                2 -> Gravity.TOP or Gravity.END
                else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            val ox = ConfigBridge.intOf(cfg, "hook_immersive_offset_x", 0)
            val oy = ConfigBridge.intOf(cfg, "hook_immersive_offset_y", 0)

            // 左/右偏好注入 16dp 安全边距，防止贴紧屏幕物理边缘被大圆角截断
            val targetX = when (gravityPref) {
                0 -> dp(ctx, 16) + ox
                2 -> dp(ctx, 16) - ox
                else -> ox
            }
            val targetY = oy

            val lp = c.layoutParams as? WindowManager.LayoutParams
            if (lp != null) {
                var changed = false
                if (lp.gravity != targetGravity) {
                    lp.gravity = targetGravity
                    changed = true
                }
                if (lp.x != targetX) {
                    lp.x = targetX
                    changed = true
                }
                if (lp.y != targetY) {
                    lp.y = targetY
                    changed = true
                }
                if (changed) {
                    runCatching { windowManager.updateViewLayout(c, lp) }
                }
            }

            if (c.visibility != View.VISIBLE) {
                c.visibility = View.VISIBLE
            }
        }

        fun hide() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post { hide() }
                return
            }
            if (container?.visibility == View.VISIBLE) {
                container?.visibility = View.GONE
            }
        }

        private fun ensureView(ctx: Context, clock: View?) {
            if (isAdded && container != null) return

            val windowManager = (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?: (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?: return
            wm = windowManager

            val root = FrameLayout(ctx)
            val textV = TextView(ctx).apply {
                includeFontPadding = false
                paint.isAntiAlias = true
                paint.isSubpixelText = true
                paint.isFakeBoldText = true
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }

            applyStyle(ctx, textV, 1, clock)

            val childLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            root.addView(textV, childLp)
            root.visibility = View.GONE

            // 候选窗口类型梯队：首选独立顶层 TYPE_APPLICATION_OVERLAY (2038)，彻底剔除 2041 (TYPE_STATUS_BAR_ADDITIONAL)
            // 2038 独立于状态栏 Insets 隐藏管控，在全屏横屏游戏中稳定渲染，杜绝多任务划起反向冒出
            val candidateTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                listOf(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    2006, // TYPE_SYSTEM_OVERLAY
                    2003  // TYPE_SYSTEM_ALERT
                )
            } else {
                @Suppress("DEPRECATION")
                listOf(
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.TYPE_PHONE
                )
            }

            var lpFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lpFlags = lpFlags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            }

            for (type in candidateTypes) {
                try {
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        lpFlags,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        x = 0
                        y = 0
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            // 关键适配：允许延伸至挖孔/切口区域，杜绝横屏游戏下切口避让导致的水平居中偏位数十像素！
                            layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }

                    windowManager.addView(root, lp)
                    container = root
                    tv = textV
                    isAdded = true
                    break
                } catch (_: Throwable) {
                    // 尝试下一个候选类型
                }
            }
        }

        private fun applyStyle(ctx: Context, textV: TextView, style: Int, clock: View?) {
            if (clock != null) {
                cloneClockTypography(textV, clock)
            } else if (cachedClockTypeface != null) {
                textV.typeface = cachedClockTypeface
            }
            when (style) {
                0 -> {
                    // 极简纯文字
                    textV.background = null
                    val padH = dp(ctx, 4)
                    val padV = dp(ctx, 2)
                    textV.setPadding(padH, padV, padH, padV)
                    textV.setTextColor(Color.WHITE)
                    textV.setShadowLayer(4f, 1f, 1f, 0xCC000000.toInt())
                }
                else -> {
                    // 雅致微胶囊 (默认推荐)
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(ctx, 10).toFloat()
                        setColor(0xCC1A1C1E.toInt()) // 高对比暗黑背景
                        setStroke(dp(ctx, 1), 0x33FFFFFF.toInt()) // 细微发光边框
                    }
                    textV.background = bg
                    val ph = dp(ctx, 8)
                    val pv = dp(ctx, 3)
                    textV.setPadding(ph, pv, ph, pv)
                    textV.setTextColor(Color.WHITE)
                    textV.setShadowLayer(0f, 0f, 0f, 0)
                }
            }
        }
    }

    /**
     * 状态栏根容器（优先返回 Native 状态栏容器 PhoneStatusBarView，严防越界挂载到 NotificationShadeWindowView）
     */
    private fun findStatusBarRoot(v: View): FrameLayout? {
        var cur: View? = v
        var candidate: FrameLayout? = null
        var phoneStatusBarView: FrameLayout? = null
        var guard = 0
        while (cur != null && guard++ < 20) {
            val clsName = cur.javaClass.name.lowercase()
            // 严禁攀爬至全屏下拉通知栏窗口或遮罩层，避免跨图层裁剪与 Insets 偏移
            if (clsName.contains("notificationshadewindowview") ||
                clsName.contains("statusbarnowindowview") ||
                clsName.contains("trackingframelayout") ||
                clsName.contains("scrimview")
            ) {
                break
            }

            if (cur is FrameLayout) {
                if (clsName.contains("phonestatusbarview")) {
                    phoneStatusBarView = cur
                    break // 找到真正的 PhoneStatusBarView，立即以此作为根容器
                }
                val resName = runCatching { cur.resources.getResourceEntryName(cur.id) }.getOrNull()?.lowercase() ?: ""
                if (resName == "status_bar" || (resName.contains("status_bar") && !isNegativeName(resName))) {
                    candidate = cur
                } else if (candidate == null) {
                    candidate = cur
                }
            }
            cur = cur.parent as? View
        }
        return phoneStatusBarView ?: candidate
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
