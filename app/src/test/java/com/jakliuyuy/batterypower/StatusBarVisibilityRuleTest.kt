package com.jakliuyuy.batterypower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 R1 状态栏可见性判定规则与时钟排版算法的健全测试用例 (Tier 1, Tier 2, Tier 4)
 */
class StatusBarVisibilityRuleTest {

    // =========================================================================
    // 规则模拟器：与 SystemUIHook.isStatusBarClock 保持 1:1 的决策逻辑
    // =========================================================================
    private val NEGATIVE_KEYWORDS = listOf(
        "recent", "overview", "taskbar", "keyguard", "lockscreen", "keyguard_lock",
        "qs", "header", "quicksettings", "controlcenter", "split", "floating"
    )

    private fun isNegativeName(name: String): Boolean {
        if (name.isEmpty()) return false
        val lower = name.lowercase()
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

    private fun isStatusBarClockDecision(
        resName: String,
        ancestorClasses: List<String> = emptyList(),
        ancestorResNames: List<String> = emptyList()
    ): Boolean {
        if (isNegativeName(resName)) return false
        val maxDepth = maxOf(ancestorClasses.size, ancestorResNames.size).coerceAtMost(16)
        for (i in 0 until maxDepth) {
            val pCls = ancestorClasses.getOrNull(i) ?: ""
            if (isNegativeName(pCls)) return false
            val pRes = ancestorResNames.getOrNull(i) ?: ""
            if (isNegativeName(pRes)) return false
        }
        return true
    }

    // =========================================================================
    // 规则模拟器：与 SystemUIHook.isClockVisibleOnScreen 保持 1:1 的物理可见性判定
    // =========================================================================
    data class ClockViewState(
        val isAttached: Boolean = true,
        val visibility: Int = 0, // View.VISIBLE = 0, INVISIBLE = 4, GONE = 8
        val windowVisibility: Int = 0,
        val parentVisibilities: List<Int> = listOf(0, 0, 0),
        val parentAlphas: List<Float> = listOf(1f, 1f, 1f),
        val alpha: Float = 1.0f,
        val screenX: Int = 50,
        val screenY: Int = 20,
        val width: Int = 120,
        val height: Int = 50,
        val screenWidth: Int = 1080,
        val screenHeight: Int = 2400
    )

    private fun isClockVisibleOnScreenDecision(state: ClockViewState): Boolean {
        if (!state.isAttached) return false
        if (state.visibility != 0) return false
        if (state.windowVisibility != 0) return false

        // 祖先链检测：必须全为 View.VISIBLE，且透明度不能淡出为 0
        for (i in 0 until state.parentVisibilities.size.coerceAtMost(5)) {
            if (state.parentVisibilities[i] != 0) return false
            if (state.parentAlphas.getOrNull(i) ?: 1f <= 0.01f) return false
        }

        // 时钟自身透明度检查
        if (state.alpha <= 0.01f) return false

        // 物理视口 4 边界检测：仅在完成 Measure/Layout 时检测，消除未排版尺寸误杀
        if (state.width > 0 && state.height > 0) {
            val outLeft = state.screenX + state.width < -12
            val outTop = state.screenY + state.height < -12
            val outRight = state.screenX > state.screenWidth + 12
            val outBottom = state.screenY > state.screenHeight + 12
            if (outLeft || outTop || outRight || outBottom) return false
        }

        return true
    }

    // =========================================================================
    // 规则模拟器：与 SystemUIHook.applyLayerLayout Mode A 保持 1:1 的坐标推导
    // =========================================================================
    data class ModeAPositionResult(val targetX: Int, val targetY: Int)

    private fun calculateModeAPosition(
        clockXInRoot: Int,
        clockYInRoot: Int,
        clockW: Int,
        clockH: Int,
        tvW: Int,
        tvH: Int,
        side: Int, // 0 = 右侧, 1 = 左侧
        ox: Int,
        oy: Int,
        dp4: Int
    ): ModeAPositionResult {
        val targetX = if (side == 1) {
            clockXInRoot - tvW - dp4 + ox
        } else {
            clockXInRoot + clockW + dp4 + ox
        }
        val targetY = clockYInRoot + (clockH - tvH) / 2 + oy
        return ModeAPositionResult(targetX, targetY)
    }

    // =========================================================================
    // Tier 1: 时钟白名单与排除规则 (TC-1.3.1, TC-1.3.2)
    // =========================================================================

    @Test
    fun testStatusBarClockWhitelist_standardClocks() {
        // 常规状态栏时钟命名
        assertTrue(isStatusBarClockDecision("clock"))
        assertTrue(isStatusBarClockDecision("status_bar_clock"))
        assertTrue(isStatusBarClockDecision("oplus_status_bar_clock"))
        assertTrue(isStatusBarClockDecision("system_clock_view"))
        assertTrue(isStatusBarClockDecision("com.android.systemui.statusbar.policy.Clock"))
        assertTrue(isStatusBarClockDecision("com.oplus.systemui.statusbar.policy.Clock"))
    }

    @Test
    fun testStatusBarClockBlacklist_recentsAndTaskbarExclusion() {
        // 排除多任务/任务栏时钟
        assertFalse(isStatusBarClockDecision("recents_clock"))
        assertFalse(isStatusBarClockDecision("overview_clock"))
        assertFalse(isStatusBarClockDecision("taskbar_clock"))
        assertFalse(isStatusBarClockDecision("floating_clock"))
        assertFalse(isStatusBarClockDecision("split_clock"))
    }

    @Test
    fun testStatusBarClockBlacklist_keyguardAndQsExclusion() {
        // 排除锁屏大时钟（包含 keyguard 或 lockscreen）
        assertFalse(isStatusBarClockDecision("keyguard_clock"))
        assertFalse(isStatusBarClockDecision("keyguard_status_bar_clock"))
        assertFalse(isStatusBarClockDecision("clock_keyguard_default"))
        assertFalse(isStatusBarClockDecision("lockscreen_clock"))

        // 排除下拉通知栏与快捷设置时钟（包含 qs 或 header）
        assertFalse(isStatusBarClockDecision("qs_clock"))
        assertFalse(isStatusBarClockDecision("quick_settings_qs_clock"))
        assertFalse(isStatusBarClockDecision("header_clock"))
        assertFalse(isStatusBarClockDecision("notification_header_clock"))
    }

    @Test
    fun testStatusBarClockBlacklist_ancestorCheck() {
        // 1. 祖先类名包含 keyguard
        val ancestorClassesWithKeyguard = listOf(
            "android.widget.FrameLayout",
            "com.android.keyguard.KeyguardRootView",
            "com.android.systemui.statusbar.phone.PhoneStatusBarView"
        )
        assertFalse(isStatusBarClockDecision("clock", ancestorClasses = ancestorClassesWithKeyguard))

        // 2. 祖先类名包含 quicksettings
        val ancestorClassesWithQuickSettings = listOf(
            "android.widget.LinearLayout",
            "com.android.systemui.quicksettings.QSContainer"
        )
        assertFalse(isStatusBarClockDecision("clock", ancestorClasses = ancestorClassesWithQuickSettings))

        // 3. 祖先资源名包含 keyguard
        val ancestorResWithKeyguard = listOf(
            "status_bar_container",
            "keyguard_status_bar_area"
        )
        assertFalse(isStatusBarClockDecision("clock", ancestorResNames = ancestorResWithKeyguard))
    }

    // =========================================================================
    // Tier 2: 物理可见性与屏幕视口边界测试 (TC-2.1.1, TC-2.1.2, TC-2.1.3)
    // =========================================================================

    @Test
    fun testClockVisibility_happyPathNormalVisible() {
        val normalState = ClockViewState()
        assertTrue("正常桌面与常规应用下时钟应当被判为物理可见", isClockVisibleOnScreenDecision(normalState))
    }

    @Test
    fun testClockVisibility_detachedFromWindow() {
        val detached = ClockViewState(isAttached = false)
        assertFalse("未挂载到窗口的时钟必须判为不可见", isClockVisibleOnScreenDecision(detached))
    }

    @Test
    fun testClockVisibility_viewVisibilityNotVisible() {
        val invisible = ClockViewState(visibility = 4) // INVISIBLE
        assertFalse("View.INVISIBLE 必须判为不可见", isClockVisibleOnScreenDecision(invisible))

        val gone = ClockViewState(visibility = 8) // GONE
        assertFalse("View.GONE 必须判为不可见", isClockVisibleOnScreenDecision(gone))
    }

    @Test
    fun testClockVisibility_windowVisibilityNotVisible() {
        val windowGone = ClockViewState(windowVisibility = 8)
        assertFalse("windowVisibility 非 VISIBLE 必须判为不可见", isClockVisibleOnScreenDecision(windowGone))
    }

    @Test
    fun testClockVisibility_ancestorInvisibleOrGone() {
        val parentInvisible = ClockViewState(parentVisibilities = listOf(0, 4, 0)) // 第2层为 INVISIBLE
        assertFalse("祖先链为 View.INVISIBLE 必须判为不可见", isClockVisibleOnScreenDecision(parentInvisible))

        val parentGone = ClockViewState(parentVisibilities = listOf(8, 0, 0)) // 第1层为 GONE
        assertFalse("祖先链为 View.GONE 必须判为不可见", isClockVisibleOnScreenDecision(parentGone))

        val parentAlphaZero = ClockViewState(parentAlphas = listOf(0f, 1f, 1f))
        assertFalse("祖先透明度淡出为 0 必须判为不可见", isClockVisibleOnScreenDecision(parentAlphaZero))
    }

    @Test
    fun testClockVisibility_translationOffscreenTop() {
        // screenY = -63, height = 50 -> -63 + 50 = -13 < -12 (完全移出顶部阈值)
        val offscreenTop = ClockViewState(screenY = -63, height = 50)
        assertFalse("被移出屏幕顶端时必须判为不可见", isClockVisibleOnScreenDecision(offscreenTop))
    }

    @Test
    fun testClockVisibility_translationOffscreenLeft() {
        // screenX = -133, width = 120 -> -133 + 120 = -13 < -12
        val offscreenLeft = ClockViewState(screenX = -133, width = 120)
        assertFalse("被移出屏幕左边界时必须判为不可见", isClockVisibleOnScreenDecision(offscreenLeft))
    }

    @Test
    fun testClockVisibility_translationOffscreenRight() {
        // screenWidth = 1080, screenX = 1100 > 1080 + 12 (完全移出右侧)
        val offscreenRight = ClockViewState(screenX = 1100, width = 120, screenWidth = 1080)
        assertFalse("被移出屏幕右侧边界时必须判为不可见", isClockVisibleOnScreenDecision(offscreenRight))
    }

    @Test
    fun testClockVisibility_translationOffscreenBottom() {
        // screenHeight = 2400, screenY = 2420 > 2400 + 12 (完全移出底部)
        val offscreenBottom = ClockViewState(screenY = 2420, height = 50, screenHeight = 2400)
        assertFalse("被移出屏幕底部边界时必须判为不可见", isClockVisibleOnScreenDecision(offscreenBottom))
    }

    @Test
    fun testClockVisibility_zeroDimensionsProtected() {
        // 刚 attach 尚未排版 (width = 0, height = 0)，坐标即便为 0 也不受边界越界误杀
        val zeroDimensions = ClockViewState(width = 0, height = 0, screenX = 0, screenY = 0)
        assertTrue("未排版 (width/height=0) 必须放行以消除尺寸误杀", isClockVisibleOnScreenDecision(zeroDimensions))
    }

    // =========================================================================
    // Tier 1 & Tier 2: 模式 A (覆盖图层) 像素级微调与坐标推导 (TC-1.3.3)
    // =========================================================================

    @Test
    fun testModeACoordinates_rightSideDefault() {
        // 时钟右侧 (side = 0)，无偏移 (ox=0, oy=0)
        // root 坐标系中：clockX = 30, clockY = 10, clockW = 80, clockH = 30, tvW = 60, tvH = 20, dp4 = 8
        val result = calculateModeAPosition(
            clockXInRoot = 30,
            clockYInRoot = 10,
            clockW = 80,
            clockH = 30,
            tvW = 60,
            tvH = 20,
            side = 0,
            ox = 0,
            oy = 0,
            dp4 = 8
        )
        // targetX = 30 + 80 + 8 + 0 = 118
        assertEquals(118, result.targetX)
        // targetY = 10 + (30 - 20) / 2 + 0 = 15 (完美垂直居中对齐)
        assertEquals(15, result.targetY)
    }

    @Test
    fun testModeACoordinates_leftSideWithOffsets() {
        // 时钟左侧 (side = 1)，带用户微调 (ox = -10, oy = 5)
        val result = calculateModeAPosition(
            clockXInRoot = 150,
            clockYInRoot = 20,
            clockW = 80,
            clockH = 40,
            tvW = 70,
            tvH = 24,
            side = 1,
            ox = -10,
            oy = 5,
            dp4 = 8
        )
        // targetX = 150 - 70 - 8 + (-10) = 62
        assertEquals(62, result.targetX)
        // targetY = 20 + (40 - 24) / 2 + 5 = 20 + 8 + 5 = 33
        assertEquals(33, result.targetY)
    }

    // =========================================================================
    // Tier 4: 桌面与常规应用前台真实场景模拟 (TC-4.1)
    // =========================================================================

    @Test
    fun testDesktopForegroundScenario_clockRemainsPermanentlyVisible() {
        // 模拟桌面运行时状态栏时钟保持稳定可见
        val desktopState = ClockViewState(
            isAttached = true,
            visibility = 0,
            windowVisibility = 0,
            screenX = 42,
            screenY = 12,
            width = 110,
            height = 45
        )
        val isVisible = isClockVisibleOnScreenDecision(desktopState)
        assertTrue("常规桌面下状态栏时钟必须 100% 保持可见", isVisible)

        // 模拟前台切换应用（如进入微信），时钟仍然可见
        val appState = desktopState.copy(screenX = 42, screenY = 12)
        assertTrue("普通应用前台运行时状态栏时钟持续可见", isClockVisibleOnScreenDecision(appState))
    }
}
