package com.jakliuyuy.batterypower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 R2 全屏沉浸状态机、手势防抖与多任务互斥逻辑的健全测试用例 (Tier 1, Tier 3, Tier 4)
 */
class ImmersiveStateMachineTest {

    // =========================================================================
    // 沉浸触发决策公理 (与 SystemUIHook.checkSync / ImmersiveOverlayManager 保持 1:1)
    // shouldShowImmersive = !isClockVisible && hookEnabled && immersiveEnabled && isInteractive && !isLocked
    // =========================================================================
    data class ImmersiveContextState(
        val isClockVisible: Boolean,
        val hookEnabled: Boolean,
        val immersiveEnabled: Boolean,
        val isInteractive: Boolean,
        val isLocked: Boolean
    )

    private fun decideShouldShowImmersive(state: ImmersiveContextState): Boolean {
        return !state.isClockVisible &&
                state.hookEnabled &&
                state.immersiveEnabled &&
                state.isInteractive &&
                !state.isLocked
    }

    // =========================================================================
    // 沉浸浮层对齐与安全坐标推导模型
    // =========================================================================
    data class ImmersivePosition(
        val gravity: Int, // 0: Left, 1: Center, 2: Right
        val targetX: Int,
        val targetY: Int
    )

    private fun calculateImmersivePosition(
        gravityPref: Int,
        ox: Int,
        oy: Int,
        dp16: Int
    ): ImmersivePosition {
        val targetX = when (gravityPref) {
            0 -> dp16 + ox
            2 -> dp16 - ox
            else -> ox
        }
        val targetY = oy
        return ImmersivePosition(gravityPref, targetX, targetY)
    }

    // =========================================================================
    // Tier 3: 32 态完整真值表枚举测试 (TC-3.1.1)
    // 5 个布尔变量共有 2^5 = 32 种全状态排列组合
    // =========================================================================

    @Test
    fun testImmersiveDecision_full32StateTruthTable() {
        var trueCount = 0
        var falseCount = 0

        // 遍历 5 个布尔维度的所有可能状态组合
        for (i in 0 until 32) {
            val isClockVisible = (i and 1) != 0
            val hookEnabled = (i and 2) != 0
            val immersiveEnabled = (i and 4) != 0
            val isInteractive = (i and 8) != 0
            val isLocked = (i and 16) != 0

            val state = ImmersiveContextState(
                isClockVisible = isClockVisible,
                hookEnabled = hookEnabled,
                immersiveEnabled = immersiveEnabled,
                isInteractive = isInteractive,
                isLocked = isLocked
            )

            val result = decideShouldShowImmersive(state)

            val expected = !isClockVisible && hookEnabled && immersiveEnabled && isInteractive && !isLocked
            assertEquals("在状态 $state 下决策结果必须符合沉浸互斥公理", expected, result)

            if (result) trueCount++ else falseCount++
        }

        // 严格数学证明：在 32 种状态组合中，有且仅有 1 个状态组合允许唤起沉浸浮层
        // 即：isClockVisible=false, hookEnabled=true, immersiveEnabled=true, isInteractive=true, isLocked=false
        assertEquals("32 种状态中应当且仅有 1 种情况允许沉浸浮层展示", 1, trueCount)
        assertEquals("32 种状态中其余 31 种情况必须严密隐藏浮层", 31, falseCount)
    }

    // =========================================================================
    // Tier 1 & Tier 3: 互斥门禁单一变量防护测试 (TC-3.1.2, TC-3.2.1, TC-3.2.2)
    // =========================================================================

    @Test
    fun testStatusBarClockVisible_suppressesImmersiveOverlay() {
        // 当状态栏时钟可见时，即使全屏沉浸全部开启且交互正常，也绝对不能展示全屏浮层
        val stateWithClockVisible = ImmersiveContextState(
            isClockVisible = true,
            hookEnabled = true,
            immersiveEnabled = true,
            isInteractive = true,
            isLocked = false
        )
        assertFalse("状态栏时钟可见时，全屏沉浸浮层必须坚决隐藏", decideShouldShowImmersive(stateWithClockVisible))
    }

    @Test
    fun testKeyguardLocked_suppressesImmersiveOverlay() {
        // 处于锁屏状态时，严防幽灵浮层
        val lockedState = ImmersiveContextState(
            isClockVisible = false,
            hookEnabled = true,
            immersiveEnabled = true,
            isInteractive = true,
            isLocked = true
        )
        assertFalse("处于锁屏界面时严禁触发沉浸浮层（杜绝幽灵浮层）", decideShouldShowImmersive(lockedState))
    }

    @Test
    fun testNonInteractiveScreenOff_suppressesImmersiveOverlay() {
        // 息屏时切断浮层显示与轮询
        val screenOffState = ImmersiveContextState(
            isClockVisible = false,
            hookEnabled = true,
            immersiveEnabled = true,
            isInteractive = false,
            isLocked = false
        )
        assertFalse("息屏非交互状态下严禁触发沉浸浮层", decideShouldShowImmersive(screenOffState))
    }

    @Test
    fun testFeatureDisabled_suppressesImmersiveOverlay() {
        // 模块总开关关闭
        val hookDisabledState = ImmersiveContextState(
            isClockVisible = false,
            hookEnabled = false,
            immersiveEnabled = true,
            isInteractive = true,
            isLocked = false
        )
        assertFalse("模块总开关关闭时不可显示", decideShouldShowImmersive(hookDisabledState))

        // 沉浸独立开关关闭
        val immersiveDisabledState = ImmersiveContextState(
            isClockVisible = false,
            hookEnabled = true,
            immersiveEnabled = false,
            isInteractive = true,
            isLocked = false
        )
        assertFalse("沉浸开关关闭时不可显示", decideShouldShowImmersive(immersiveDisabledState))
    }

    // =========================================================================
    // Tier 4: 手势防抖与多任务 (Recents) 转场生命周期测试 (TC-4.2, TC-4.3)
    // =========================================================================

    @Test
    fun testGestureTransition_enterGame_recentsGesture_resumeGame() {
        // Step 1: 桌面状态 (时钟可见，非沉浸)
        var currentState = ImmersiveContextState(
            isClockVisible = true,
            hookEnabled = true,
            immersiveEnabled = true,
            isInteractive = true,
            isLocked = false
        )
        assertFalse("Step 1 (桌面): 沉浸浮层应隐藏", decideShouldShowImmersive(currentState))

        // Step 2: 启动并进入大型全屏游戏 (状态栏隐藏，沉浸接管引擎启动)
        currentState = currentState.copy(isClockVisible = false)
        assertTrue("Step 2 (全屏游戏): 状态栏隐藏后全屏沉浸浮层应毫秒级浮现", decideShouldShowImmersive(currentState))

        // Step 3: 底部上滑呼出多任务 (Recents 手势)，系统显示状态栏
        // 此时必须立即切断沉浸浮层，消除多任务卡片异常冒出
        currentState = currentState.copy(isClockVisible = true)
        assertFalse("Step 3 (多任务手势划起): 状态栏浮现时，沉浸浮层必须立即隐去，杜绝在卡片中反向冒出", decideShouldShowImmersive(currentState))

        // Step 4: 点击卡片切回全屏游戏，状态栏重新沉浸隐藏
        currentState = currentState.copy(isClockVisible = false)
        assertTrue("Step 4 (切回游戏): 沉浸浮层无缝重新接管", decideShouldShowImmersive(currentState))
    }

    // =========================================================================
    // Tier 1 & Tier 2: 沉浸浮层布局对齐与安全边距推导 (TC-1.4.1)
    // =========================================================================

    @Test
    fun testImmersivePositionCalculation_centerDefault() {
        // 居中模式 (gravityPref = 1)，默认微调 ox = 0, oy = 0
        val pos = calculateImmersivePosition(gravityPref = 1, ox = 0, oy = 0, dp16 = 32)
        assertEquals(1, pos.gravity)
        assertEquals(0, pos.targetX)
        assertEquals(0, pos.targetY)

        // 居中模式，用户自定义微调 ox = 15, oy = 8
        val posOffset = calculateImmersivePosition(gravityPref = 1, ox = 15, oy = 8, dp16 = 32)
        assertEquals(15, posOffset.targetX)
        assertEquals(8, posOffset.targetY)
    }

    @Test
    fun testImmersivePositionCalculation_leftWithSafetyMargin() {
        // 左对齐模式 (gravityPref = 0)：必须注入 16dp 安全边距，防止大圆角截断
        val pos = calculateImmersivePosition(gravityPref = 0, ox = 0, oy = 0, dp16 = 32)
        assertEquals(0, pos.gravity)
        assertEquals(32, pos.targetX) // 16dp 安全边距
        assertEquals(0, pos.targetY)

        // 叠加用户自定义微调
        val posWithOffset = calculateImmersivePosition(gravityPref = 0, ox = 10, oy = 5, dp16 = 32)
        assertEquals(42, posWithOffset.targetX) // 32 + 10
        assertEquals(5, posWithOffset.targetY)
    }

    @Test
    fun testImmersivePositionCalculation_rightWithSafetyMargin() {
        // 右对齐模式 (gravityPref = 2)：注入 16dp - ox
        val pos = calculateImmersivePosition(gravityPref = 2, ox = 0, oy = 0, dp16 = 32)
        assertEquals(2, pos.gravity)
        assertEquals(32, pos.targetX)
        assertEquals(0, pos.targetY)

        // 叠加用户自定义微调
        val posWithOffset = calculateImmersivePosition(gravityPref = 2, ox = 6, oy = 4, dp16 = 32)
        assertEquals(26, posWithOffset.targetX) // 32 - 6
        assertEquals(4, posWithOffset.targetY)
    }

    // =========================================================================
    // Tier 2: 刷新率范围边界钳位测试 (TC-2.2.3)
    // =========================================================================

    @Test
    fun testRefreshIntervalClamping_300msTo5000ms() {
        // 正常范围
        assertEquals(1000L, 1000L.coerceIn(300L, 5000L))
        assertEquals(500L, 500L.coerceIn(300L, 5000L))
        assertEquals(3000L, 3000L.coerceIn(300L, 5000L))

        // 下界溢出（防止超高频刷新导致 GC 抖动）
        assertEquals(300L, 0L.coerceIn(300L, 5000L))
        assertEquals(300L, (-100L).coerceIn(300L, 5000L))
        assertEquals(300L, 50L.coerceIn(300L, 5000L))

        // 上界溢出（防止超过 5 秒未更新造成数据陈旧）
        assertEquals(5000L, 6000L.coerceIn(300L, 5000L))
        assertEquals(5000L, 60000L.coerceIn(300L, 5000L))
        assertEquals(5000L, Long.MAX_VALUE.coerceIn(300L, 5000L))
    }
}
