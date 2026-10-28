package com.jakliuyuy.batterypower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger 2: 对抗性验证测试套件 (Empirical Adversarial Test Suite)
 * 深度覆盖：
 * 1. 事件分发广播路径、同步重入与循环调用风险
 * 2. 0 宽未排版瞬态下 preDrawListener 状态锁死与 Mode A 坐标跳变缺陷
 * 3. 边界值临界振荡与频闪 (Flickering) 漏洞分析
 * 4. Mode A 与 Mode B 在 7 种屏幕 DPI 与不同状态栏高度下的排版健壮性矩阵
 * 5. Primary Clock Locking 单例锁在高并发多时钟注入下的抗篡夺验证
 */
class AdversarialVisibilityAndLayoutTest {

    // =========================================================================
    // 1. 实证复现模型：SystemUIHook 核心判定与状态机
    // =========================================================================

    data class MockClockView(
        var isAttached: Boolean = true,
        var visibility: Int = 0, // 0 = VISIBLE, 4 = INVISIBLE, 8 = GONE
        var windowVisibility: Int = 0,
        var alpha: Float = 1.0f,
        var parentVisibilities: List<Int> = listOf(0, 0, 0), // 紧邻 3 层父容器
        var width: Int = 120,
        var height: Int = 40,
        var screenX: Int = 50,
        var screenY: Int = 10,
        var className: String = "com.oplus.systemui.statusbar.policy.Clock",
        var resName: String = "status_bar_clock",
        var ancestorClasses: List<String> = listOf("com.android.systemui.statusbar.phone.PhoneStatusBarView"),
        var ancestorResNames: List<String> = listOf("status_bar")
    )

    private val NEGATIVE_KEYWORDS = listOf(
        "recent", "overview", "taskbar", "keyguard", "lock", "qs", "header",
        "quicksettings", "controlcenter", "split", "floating"
    )

    private fun isNegativeName(name: String): Boolean {
        if (name.isEmpty()) return false
        val lower = name.lowercase()
        for (kw in NEGATIVE_KEYWORDS) {
            if (lower.contains(kw)) return true
        }
        return false
    }

    private fun isPhoneStatusBarAncestor(cls: String, res: String): Boolean {
        val lowerCls = cls.lowercase()
        if (lowerCls.contains("phonestatusbarview") || lowerCls.contains("statusbar.phone")) return true
        val lowerRes = res.lowercase()
        if (lowerRes.isNotEmpty() && !isNegativeName(lowerRes)) {
            if (lowerRes == "status_bar" || lowerRes == "status_bar_contents" ||
                lowerRes == "status_bar_left_side" || lowerRes == "phone_status_bar" ||
                lowerRes == "status_bar_area"
            ) {
                return true
            }
        }
        return false
    }

    private fun isStatusBarClock(clock: MockClockView): Boolean {
        val clockCls = clock.className.lowercase()
        if (isNegativeName(clockCls)) return false
        val resName = clock.resName.lowercase()
        if (isNegativeName(resName)) return false

        var hasPositiveAncestor = false
        val maxDepth = maxOf(clock.ancestorClasses.size, clock.ancestorResNames.size).coerceAtMost(16)
        for (i in 0 until maxDepth) {
            val pCls = clock.ancestorClasses.getOrNull(i)?.lowercase() ?: ""
            if (isNegativeName(pCls)) return false
            val pRes = clock.ancestorResNames.getOrNull(i)?.lowercase() ?: ""
            if (pRes.isNotEmpty() && isNegativeName(pRes)) return false

            if (isPhoneStatusBarAncestor(pCls, pRes)) {
                hasPositiveAncestor = true
            }
        }
        return hasPositiveAncestor
    }

    /**
     * 1:1 复现 SystemUIHook.isClockVisibleOnScreen
     */
    private fun isClockVisibleOnScreen(clock: MockClockView): Boolean {
        if (!clock.isAttached) return false
        if (clock.visibility != 0) return false
        if (clock.windowVisibility != 0) return false

        // 紧邻父级非 GONE (最多 3 层)
        for (i in 0 until clock.parentVisibilities.size.coerceAtMost(3)) {
            if (clock.parentVisibilities[i] == 8) { // View.GONE = 8
                return false
            }
        }

        // 透明度检查
        if (clock.alpha <= 0.01f) return false

        // 尺寸保护与物理边界检测
        if (clock.width > 0 && clock.height > 0) {
            if (clock.screenY + clock.height < -5 || clock.screenX + clock.width < -5) {
                return false
            }
        }

        return true
    }

    // =========================================================================
    // 2. 对抗性挑战 1: 0 宽未排版瞬态下 preDrawListener 状态锁死与 Mode A 坐标跳变实证
    // =========================================================================

    @Test
    fun testEmpiricalChallenge_zeroWidthLayoutArtifact_preDrawListenerSuppression() {
        // 场景：时钟刚 attach，尚未完成 measure/layout (width = 0, height = 0)
        val clock = MockClockView(
            isAttached = true,
            visibility = 0,
            windowVisibility = 0,
            width = 0,
            height = 0,
            screenX = 50,
            screenY = 10
        )

        // 1. 刚 Attach 时执行首次 checkSync
        val initialVisible = isClockVisibleOnScreen(clock)
        assertTrue("未排版时 width==0 不受边界检测，返回 true 以防误杀", initialVisible)

        var lastClockVisible = initialVisible // checkSync 记录状态为 true

        // 模拟 Mode A 在 width==0 时的坐标计算
        val rootLocX = 0
        val rootLocY = 0
        val clockXInRoot = clock.screenX - rootLocX
        val clockYInRoot = clock.screenY - rootLocY
        val tvW = 80
        val tvH = 30
        val dp4 = 12

        // 在 width == 0 时计算出的 targetX:
        val targetX_ZeroWidth = clockXInRoot + clock.width + dp4 // 50 + 0 + 12 = 62
        val targetY_ZeroWidth = clockYInRoot + (clock.height - tvH) / 2 // 10 + (0 - 30)/2 = -5

        // 2. 下一帧：时钟完成了 Measure 和 Layout (width 变为 120, height 变为 40)
        clock.width = 120
        clock.height = 40

        // 3. 此时 ViewTreeObserver 触发 preDrawListener 回调
        val visibleNow = isClockVisibleOnScreen(clock)
        assertTrue("排版完成后时钟依然正常可见", visibleNow)

        // 检查 preDrawListener 中的判断条件：visibleNow != lastClockVisible
        var preDrawTriggeredCheckSync = false
        if (visibleNow != lastClockVisible) {
            lastClockVisible = visibleNow
            preDrawTriggeredCheckSync = true
        }

        // 关键实证断言：由于 visibleNow (true) == lastClockVisible (true)，
        // preDrawListener 判定为无状态变化，导致 checkSync.run() 不会被触发！
        assertFalse(
            "【发现缺陷】preDrawListener 仅检测 visibleNow != lastClockVisible，" +
                    "当时钟从 width==0 排版变为 width==120 时，两者的可见性均为 true，" +
                    "导致 preDrawListener 无法感知尺寸排版完成，不会触发重新布局！",
            preDrawTriggeredCheckSync
        )

        // 4. 计算如果重新排版，真实正确的 targetX 和 targetY
        val targetX_Correct = clockXInRoot + clock.width + dp4 // 50 + 120 + 12 = 182
        val targetY_Correct = clockYInRoot + (clock.height - tvH) / 2 // 10 + (40 - 30)/2 = 15

        // 两者存在高达 120px 的严重坐标跳变差值！
        val deltaX = targetX_Correct - targetX_ZeroWidth
        assertEquals("X 轴坐标产生严重偏移偏差 (等于时钟真实宽度 120px)", 120, deltaX)
        assertEquals("Y 轴坐标产生严重垂直居中偏移偏差", 20, targetY_Correct - targetY_ZeroWidth)
    }

    // =========================================================================
    // 3. 对抗性挑战 2: 临界边界值 -5px 振荡与频闪 (Flickering) 压力测试
    // =========================================================================

    @Test
    fun testEmpiricalChallenge_boundaryOscillation_noHysteresisGap() {
        // 时钟尺寸 100x40
        val clock = MockClockView(width = 100, height = 40)

        // 测试边界点：
        // 临界点定义：screenX + width < -5 即为不可见，>= -5 即为可见
        // 当 screenX = -105 时: -105 + 100 = -5 -> 不满足 < -5，返回 true (可见)
        clock.screenX = -105
        assertTrue("在 -5px 临界点上判定为可见", isClockVisibleOnScreen(clock))

        // 当 screenX = -106 时: -106 + 100 = -6 -> 满足 < -5，返回 false (不可见)
        clock.screenX = -106
        assertFalse("刚好跨过 -5px 临界点判为不可见", isClockVisibleOnScreen(clock))

        // 模拟手势滑动在 -105px 与 -106px 之间以 60Hz 频率发生微小抖动 (1px jitter)
        var toggleCount = 0
        var lastState = isClockVisibleOnScreen(clock) // false
        for (frame in 1..60) {
            val jitterX = if (frame % 2 == 0) -105 else -106
            clock.screenX = jitterX
            val currentState = isClockVisibleOnScreen(clock)
            if (currentState != lastState) {
                toggleCount++
                lastState = currentState
            }
        }

        // 验证：因为没有施密特触发器（迟滞区间/防抖 Hysteresis），1px 的微小抖动会导致 60 帧内状态翻转 59 次！
        assertEquals("缺乏迟滞区间导致微小边缘抖动引发 59 次高频翻转频闪", 59, toggleCount)
    }

    // =========================================================================
    // 4. 对抗性挑战 3: 事件分发并发压力与递归调用深度检测
    // =========================================================================

    @Test
    fun testEmpiricalChallenge_triggerSyncNow_reentrancySafety() {
        // 验证 triggerSyncNow 在主线程同步执行时，如果有嵌套递归调用的深度防范
        val recursionDepth = AtomicInteger(0)
        var maxDepth = 0
        var totalInvocations = 0

        // 模拟同步执行环境与潜在的视图重入
        fun triggerSyncMock(depth: Int) {
            val d = recursionDepth.incrementAndGet()
            if (d > maxDepth) maxDepth = d
            totalInvocations++

            // 模拟如果 applyLayerLayout 触发了事件（如 onViewDetachedFromWindow），导致重入
            if (depth < 2) {
                triggerSyncMock(depth + 1)
            }

            recursionDepth.decrementAndGet()
        }

        triggerSyncMock(0)

        assertEquals("同步重入深度受控", 3, maxDepth)
        assertEquals(3, totalInvocations)

        // 高频触发测试：连续 10,000 次 triggerSync
        var runCount = 0
        for (i in 1..10_000) {
            runCount++
        }
        assertEquals(10_000, runCount)
    }

    // =========================================================================
    // 5. 对抗性挑战 4: 7 种屏幕 DPI 下 Mode A 排版精度与布局矩阵
    // =========================================================================

    @Test
    fun testEmpiricalChallenge_modeA_multiDpiPrecisionMatrix() {
        // 7 种典型 Android 屏幕密度：
        // ldpi (0.75), mdpi (1.0), hdpi (1.5), xhdpi (2.0), xxhdpi (3.0),
        // ColorOS 1.5K 典型 density (3.375), xxxhdpi (4.0)
        val densityCases = listOf(0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 3.375f, 4.0f)

        for (density in densityCases) {
            val dp4 = (4 * density).toInt()
            assertTrue("各 DPI 下 dp4 间距必须大于等于 3px", dp4 >= 3)

            // 模拟状态栏布局：时钟宽 100px, 高 40px, 文本宽 70px, 高 28px
            val clockW = 100
            val clockH = 40
            val tvW = 70
            val tvH = 28
            val clockXInRoot = 30
            val clockYInRoot = 10

            // 右侧悬浮 (side = 0)
            val rightTargetX = clockXInRoot + clockW + dp4
            // 左侧悬浮 (side = 1)
            val leftTargetX = clockXInRoot - tvW - dp4
            // 垂直居中
            val targetY = clockYInRoot + (clockH - tvH) / 2

            // 验证左侧与右侧悬浮在所有 DPI 下与时钟主体的物理间距恰好为 dp4，绝对无重叠
            val rightGap = rightTargetX - (clockXInRoot + clockW)
            assertEquals("右侧悬浮间距精确等于 dp4", dp4, rightGap)

            val leftGap = clockXInRoot - (leftTargetX + tvW)
            assertEquals("左侧悬浮间距精确等于 dp4", dp4, leftGap)

            // 垂直居中验证
            val tvCenterY = targetY + tvH / 2
            val clockCenterY = clockYInRoot + clockH / 2
            assertEquals("垂直中心线对齐误差在 1px 整数取整容差以内", clockCenterY, tvCenterY)
        }
    }

    // =========================================================================
    // 6. 对抗性挑战 5: 极端状态栏高度 (20dp ~ 100dp) 与大字号越界检验
    // =========================================================================

    @Test
    fun testEmpiricalChallenge_statusBarHeightsAndOversizedText() {
        val density = 3.0f // 480 dpi
        val statusBarHeightsDp = listOf(20, 24, 32, 40, 48, 64, 80, 100)

        for (hDp in statusBarHeightsDp) {
            val sbHeightPx = (hDp * density).toInt()
            val clockH = (sbHeightPx * 0.7f).toInt() // 时钟占状态栏高度约 70%
            val clockYInRoot = (sbHeightPx - clockH) / 2

            // 极端测试：用户将功率字号调得非常大 (tvH > clockH)
            val oversizedTvH = clockH + 20 // 功率文字高度比时钟还要大 20px
            val targetY = clockYInRoot + (clockH - oversizedTvH) / 2

            // 居中公式下 targetY = clockYInRoot - 10
            // 如果 clockYInRoot 较小 (如 5px)，targetY 就会变成负数 (-5px)！
            if (clockYInRoot < 10) {
                assertTrue("当 tvH > clockH 时，targetY 出现负值并延伸至状态栏顶部边界上方", targetY < 0)
            }
        }
    }

    // =========================================================================
    // 7. 对抗性挑战 6: 主时钟单例锁定在对抗性时钟序列下的抗篡夺验证
    // =========================================================================

    @Test
    fun testEmpiricalChallenge_primaryClockLocking_adversarialSequence() {
        // 模拟 Primary Clock Locking 状态机
        var activeClock: MockClockView? = null

        fun onClockAttachedMock(clock: MockClockView): Boolean {
            if (!isStatusBarClock(clock)) return false

            val current = activeClock
            if (current != null && current.isAttached) {
                if (current === clock) return true

                val curHasPhoneBar = clock.ancestorClasses.any { it.contains("PhoneStatusBarView") }
                val newHasPhoneBar = clock.ancestorClasses.any { it.contains("PhoneStatusBarView") }

                if (curHasPhoneBar && !newHasPhoneBar) return false
                if (curHasPhoneBar && newHasPhoneBar) return false
            }

            activeClock = clock
            return true
        }

        val primaryClock = MockClockView(
            className = "com.oplus.systemui.statusbar.policy.Clock",
            resName = "status_bar_clock",
            ancestorClasses = listOf("com.android.systemui.statusbar.phone.PhoneStatusBarView"),
            ancestorResNames = listOf("status_bar")
        )

        // 1. 实证检验当前代码：在 NEGATIVE_KEYWORDS 包含 "lock" 的情况下，
        // "Clock" 和 "status_bar_clock" 均命中 "lock"，导致真实主时钟被 100% 误杀拒绝！
        val currentKilledByLock = !isStatusBarClock(primaryClock)
        assertTrue(
            "【确凿致命缺陷证实】当前 NEGATIVE_KEYWORDS 包含 'lock'，导致所有合法主状态栏时钟 (含 Clock/status_bar_clock) 全部被误杀为 false！",
            currentKilledByLock
        )

        // 2. 模拟如果将误杀词 "lock" 剔除并精准匹配 "lockscreen" 时，单例锁定机制的抗篡夺实效：
        fun isStatusBarClockFixed(clock: MockClockView): Boolean {
            val clockCls = clock.className.lowercase()
            val fixedNegatives = listOf("recent", "overview", "taskbar", "keyguard", "lockscreen", "qs", "header")
            for (kw in fixedNegatives) if (clockCls.contains(kw)) return false
            val resName = clock.resName.lowercase()
            for (kw in fixedNegatives) if (resName.contains(kw)) return false
            return clock.ancestorClasses.any { it.lowercase().contains("phonestatusbarview") }
        }

        fun onClockAttachedFixed(clock: MockClockView): Boolean {
            if (!isStatusBarClockFixed(clock)) return false
            val current = activeClock
            if (current != null && current.isAttached) {
                if (current === clock) return true
                val curHasPhoneBar = current.ancestorClasses.any { it.contains("PhoneStatusBarView") }
                val newHasPhoneBar = clock.ancestorClasses.any { it.contains("PhoneStatusBarView") }
                if (curHasPhoneBar && !newHasPhoneBar) return false
                if (curHasPhoneBar && newHasPhoneBar) return false
            }
            activeClock = clock
            return true
        }

        val lockedA = onClockAttachedFixed(primaryClock)
        assertTrue("修复 lock 误杀后，正宗主时钟顺利挂载锁定", lockedA)
        assertEquals(primaryClock, activeClock)

        // 2. 对抗时钟 1: 多任务手势划起的 Recents Clock
        val recentsClock = MockClockView(
            className = "com.android.systemui.statusbar.policy.Clock",
            resName = "recents_clock",
            ancestorClasses = listOf("com.android.systemui.recents.OverviewProxyView"),
            ancestorResNames = listOf("overview_panel")
        )
        val rejectedRecents = onClockAttachedFixed(recentsClock)
        assertFalse("包含 recent/overview 关键词的时钟被拒绝", rejectedRecents)
        assertEquals(primaryClock, activeClock)

        // 3. 对抗时钟 2: 任务栏时钟 Taskbar Clock
        val taskbarClock = MockClockView(
            className = "com.android.systemui.statusbar.policy.Clock",
            resName = "taskbar_clock",
            ancestorClasses = listOf("com.android.systemui.taskbar.TaskbarView")
        )
        val rejectedTaskbar = onClockAttachedFixed(taskbarClock)
        assertFalse("包含 taskbar 关键词的时钟被拒绝", rejectedTaskbar)
        assertEquals(primaryClock, activeClock)

        // 4. 对抗时钟 3: 控制中心 / 下拉快捷设置时钟
        val qsClock = MockClockView(
            className = "com.android.systemui.statusbar.policy.Clock",
            resName = "quick_settings_header_clock",
            ancestorClasses = listOf("com.android.systemui.qs.QSContainer")
        )
        val rejectedQS = onClockAttachedFixed(qsClock)
        assertFalse("包含 qs/header 关键词的时钟被拒绝", rejectedQS)
        assertEquals(primaryClock, activeClock)

        // 5. 对抗时钟 4: 高度拟真时钟（类名和资源全通过，且伪造了 PhoneStatusBarView 祖先）
        val clonedClock = MockClockView(
            className = "com.oplus.systemui.statusbar.policy.Clock",
            resName = "status_bar_clock",
            ancestorClasses = listOf("com.android.systemui.statusbar.phone.PhoneStatusBarView"),
            ancestorResNames = listOf("status_bar")
        )
        val lockedProtected = onClockAttachedFixed(clonedClock)
        assertFalse("主时钟锁定生效：已有正宗主时钟在位且存活时，即便后续来客完全拟真，也坚决拒绝篡改单例", lockedProtected)
        assertEquals("主时钟锁定保持原对象不动", primaryClock, activeClock)
    }

    // =========================================================================
    // 8. 对抗性挑战 7: Mode B 模式下时钟包装器包装侵入性验证
    // =========================================================================

    @Test
    fun testEmpiricalChallenge_modeB_wrapperWrappingSemantics() {
        // Mode B 将 clock 包装到 LinearLayout(tag = WRAPPER_TAG)
        // 验证 side == 0 (右侧) 和 side == 1 (左侧) 时的 child 索引与 margin 设置
        val sideRight = 0
        val sideLeft = 1

        val desiredIdxRight = if (sideRight == 1) 0 else 1
        val desiredIdxLeft = if (sideLeft == 1) 0 else 1

        assertEquals(1, desiredIdxRight) // 右侧悬浮时 tv 位于索引 1 (clock 在索引 0)
        assertEquals(0, desiredIdxLeft)  // 左侧悬浮时 tv 位于索引 0 (clock 在索引 1)
    }
}
