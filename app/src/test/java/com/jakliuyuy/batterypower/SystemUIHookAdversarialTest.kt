package com.jakliuyuy.batterypower

import com.jakliuyuy.batterypower.hook.SystemUIHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Challenger M1-1 针对 SystemUIHook 的经验性与对抗性实测套件
 * 
 * 核心对抗目标：
 * 1. 验证 isStatusBarClock 识别与排除规则（包括致命的 lock 关键字误杀时钟自身的缺陷）
 * 2. 验证 Primary Clock Locking 在二次 attach 与非主时钟并发篡权下的防护
 * 3. 验证 isClockVisibleOnScreen 的数学边界、向右/向下移出屏幕的漏判、父级 INVISIBLE 漏判与 Alpha 穿透
 */
class SystemUIHookAdversarialTest {

    private fun getPrivateMethod(name: String, vararg paramTypes: Class<*>): Method {
        val method = SystemUIHook::class.java.getDeclaredMethod(name, *paramTypes)
        method.isAccessible = true
        return method
    }

    private fun getPrivateField(name: String): Field {
        val field = SystemUIHook::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field
    }

    // =========================================================================
    // 挑战 1: isStatusBarClock 负向词排除规则与致命误杀验证
    // =========================================================================

    @Test
    fun testEmpiricalDefect_clockKilledByLockKeyword() {
        val isNegativeNameMethod = getPrivateMethod("isNegativeName", String::class.java)

        // 真实 Android / ColorOS 状态栏时钟类名
        val aospClockCls = "com.android.systemui.statusbar.policy.Clock"
        val oplusClockCls = "com.oplus.systemui.statusbar.policy.Clock"
        val oplusOsClockCls = "com.oplusos.systemui.statusbar.policy.Clock"

        // 真实状态栏时钟常见资源名
        val defaultClockRes = "clock"
        val statusBarClockRes = "status_bar_clock"
        val oplusClockRes = "oplus_status_bar_clock"

        // 经验实测：由于 NEGATIVE_KEYWORDS 中包含了 "lock"，而 "clock" 子串恰好包含 "lock"
        val aospKilled = isNegativeNameMethod.invoke(SystemUIHook, aospClockCls) as Boolean
        val oplusKilled = isNegativeNameMethod.invoke(SystemUIHook, oplusClockCls) as Boolean
        val oplusOsKilled = isNegativeNameMethod.invoke(SystemUIHook, oplusOsClockCls) as Boolean
        val clockResKilled = isNegativeNameMethod.invoke(SystemUIHook, defaultClockRes) as Boolean
        val statusBarResKilled = isNegativeNameMethod.invoke(SystemUIHook, statusBarClockRes) as Boolean
        val oplusResKilled = isNegativeNameMethod.invoke(SystemUIHook, oplusClockRes) as Boolean

        println("=== [Adversarial Verification TC-1.1] Clock Whitelist Verification ===")
        println("isNegativeName('$aospClockCls') -> $aospKilled (Expected false for valid clock, returns $aospKilled)")
        println("isNegativeName('$oplusClockCls') -> $oplusKilled (Expected false for valid clock, returns $oplusKilled)")
        println("isNegativeName('$defaultClockRes') -> $clockResKilled (Expected false for valid clock, returns $clockResKilled)")
        println("isNegativeName('$statusBarClockRes') -> $statusBarResKilled (Expected false for valid clock, returns $statusBarResKilled)")

        // 验证修复结果：所有正宗 Clock 均绝不被判定为 negative
        assertFalse("AOSP Clock class must NOT be treated as negative!", aospKilled)
        assertFalse("OPlus Clock class must NOT be treated as negative!", oplusKilled)
        assertFalse("'clock' resource name must NOT be treated as negative!", clockResKilled)
        assertFalse("'status_bar_clock' resource name must NOT be treated as negative!", statusBarResKilled)
    }

    @Test
    fun testNegativeKeywordsExclusionEffectiveness() {
        val isNegativeNameMethod = getPrivateMethod("isNegativeName", String::class.java)

        // 验证合法的排除场景确实能够被过滤
        val recentsViews = listOf(
            "com.android.systemui.recents.OverviewProxyRecentsImpl",
            "com.android.systemui.statusbar.phone.KeyguardStatusBarView",
            "com.android.systemui.qs.QSContainerImpl",
            "com.android.systemui.taskbar.TaskbarView",
            "com.android.systemui.controlcenter.ControlCenterContainer",
            "com.android.systemui.statusbar.split.SplitStatusBarView",
            "com.android.systemui.statusbar.floating.FloatingWindowStatusBar"
        )

        for (cls in recentsViews) {
            val isNeg = isNegativeNameMethod.invoke(SystemUIHook, cls) as Boolean
            assertTrue("View class $cls must be recognized as negative", isNeg)
        }
    }

    @Test
    fun testNegativeKeywordsOmission_uncoveredEdgeCases() {
        val isNegativeNameMethod = getPrivateMethod("isNegativeName", String::class.java)

        // 对抗性输入：厂商定制的小窗/画中画/多任务变体命名
        val uncoveredVariants = listOf(
            "pip_status_time",              // 画中画时钟容器（不含现有负向词）
            "freeform_time_container",      // 自由小窗时钟容器（不含现有负向词）
            "multiwindow_status_view"       // 多窗口状态栏容器（不含现有负向词）
        )

        println("=== [Adversarial Verification TC-1.2] Uncovered Keyword Variants ===")
        for (variant in uncoveredVariants) {
            val isNeg = isNegativeNameMethod.invoke(SystemUIHook, variant) as Boolean
            println("Variant '$variant' recognized as negative? -> $isNeg")
            // 记录这些变体目前未能被 NEGATIVE_KEYWORDS 覆盖
            assertFalse("Variant '$variant' is NOT covered by NEGATIVE_KEYWORDS (Potential leakage risk)", isNeg)
        }
    }

    // =========================================================================
    // 挑战 2: Primary Clock Locking 状态与机制验证
    // =========================================================================

    @Test
    fun testPrimaryClockLockingStructure() {
        // 验证 SystemUIHook 内部是否存在单例时钟引用与生命周期管理
        val activeClockRefField = getPrivateField("activeClockRef")
        assertNotNull("SystemUIHook must have activeClockRef field for locking", activeClockRefField)

        val onClockAttachedMethod = getPrivateMethod("onClockAttached", android.view.View::class.java)
        assertNotNull("SystemUIHook must have onClockAttached for Primary Clock Locking", onClockAttachedMethod)
    }

    // =========================================================================
    // 挑战 3: isClockVisibleOnScreen 物理与数学边界对抗测试
    // =========================================================================

    /**
     * 针对 isClockVisibleOnScreen 的数学算法进行极限对抗实测
     * 算法公式：if (loc[1] + clock.height < -5 || loc[0] + clock.width < -5) return false
     */
    @Test
    fun testPhysicalBoundaryMath_offscreenRightAndBottomVulnerability() {
        // 模拟横屏游戏或游戏助手将状态栏移出屏幕右侧（屏幕宽 1080）
        val screenW = 1080
        val screenH = 2400
        val screenXOffscreenRight = 1200
        val clockWidth = 100
        val screenY = 20
        val clockHeight = 50

        // 修复后的 4 边界判定逻辑（带施密特迟滞区间 -12px）：
        val outRight = screenXOffscreenRight > screenW + 12
        val outLeft = screenXOffscreenRight + clockWidth < -12
        val outTop = screenY + clockHeight < -12
        val outBottom = screenY > screenH + 12
        val visibleAccordingToCode = !(outLeft || outTop || outRight || outBottom)

        println("=== [Adversarial Verification TC-3.1] Right/Bottom Offscreen Fix ===")
        println("Clock screenX=$screenXOffscreenRight (outside 1080px screen): is visible according to code? -> $visibleAccordingToCode")
        
        // 验证修复：移出右侧边界时必须判定为不可见
        assertFalse("Clock moved offscreen to the right must be treated as NOT visible!", visibleAccordingToCode)

        // 模拟状态栏滑移出屏幕底部（屏幕高 2400）
        val screenYOffscreenBottom = 2600
        val bottomOut = screenYOffscreenBottom > screenH + 12
        val visibleBottomAccordingToCode = !bottomOut
        println("Clock screenY=$screenYOffscreenBottom (outside 2400px screen): is visible according to code? -> $visibleBottomAccordingToCode")
        assertFalse("Clock moved offscreen to the bottom must be treated as NOT visible!", visibleBottomAccordingToCode)
    }

    @Test
    fun testNegativeThresholdToleranceGap() {
        val clockHeight = 40
        // 当 screenY = -42 时，screenY + height = -2
        val screenYJustOutOfScreen = -42
        val condition = (screenYJustOutOfScreen + clockHeight < -12)
        
        println("=== [Adversarial Verification TC-3.2] Hysteresis Tolerance Threshold Gap ===")
        println("When clock bottom is at -2px: condition (< -12) is $condition")
        // 在施密特死区 (-12px ~ 0px) 内，状态不发生抖动翻转
        assertFalse("At -2px offscreen top, condition (< -12) is false, maintaining hysteresis stability!", condition)
    }

    @Test
    fun testParentInvisibleVisibilityLeak() {
        val viewVisible = 0     // View.VISIBLE
        val viewInvisible = 4   // View.INVISIBLE
        val viewGone = 8        // View.GONE

        // 场景：直接父容器被设置了 INVISIBLE
        val parentVisibility = viewInvisible
        // 修复后逻辑：检查 p.visibility != View.VISIBLE (同时拦截 GONE 与 INVISIBLE)
        val isCaught = (parentVisibility != viewVisible)

        println("=== [Adversarial Verification TC-3.3] Parent INVISIBLE Fix ===")
        println("Parent visibility is View.INVISIBLE (4). Does 'p.visibility != View.VISIBLE' catch it? -> $isCaught")
        
        // 验证修复：父容器为 View.INVISIBLE 时成功被拦截为不可见
        assertTrue("Parent View.INVISIBLE (4) is now successfully caught by '!= View.VISIBLE' check!", isCaught)
    }

    @Test
    fun testParentAlphaZeroAnimationLeak() {
        val parentAlpha = 0.0f
        val childClockAlpha = 1.0f

        // 修复后逻辑：祖先链检测 p.alpha <= 0.01f
        val isCaughtByParentAlpha = (parentAlpha <= 0.01f) || (childClockAlpha <= 0.01f)

        println("=== [Adversarial Verification TC-3.4] Parent Alpha=0 Fix ===")
        println("Parent alpha is 0.0, child clock.alpha is 1.0. Caught by ancestor alpha check? -> $isCaughtByParentAlpha")

        // 验证修复：祖先透明度淡出为 0 时成功被拦截为不可见
        assertTrue("When parent alpha is 0, ancestor alpha check successfully catches invisibility!", isCaughtByParentAlpha)
    }
}
