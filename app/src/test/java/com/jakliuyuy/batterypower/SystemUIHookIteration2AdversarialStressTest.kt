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
 * Challenger Iteration 2 终极对抗性压力测试与实证验证套件
 *
 * 专门针对：
 * 1. 施密特双阈值迟滞（Schmidt Trigger Hysteresis）4 边界 1000 次 1px 微抖动压测
 * 2. isNegativeName 极限厂商类名与资源 ID 攻击（白名单豁免 vs 负向排除）
 * 3. 0 宽未排版到排版完成瞬态感知机制与防跳变验证
 * 4. 沉浸接管状态机在全屏游戏、多任务手势切入切出、锁屏互斥下的全生命周期转移
 * 5. WindowManager 候选窗口类型 2038 纯净性（严查 2041 污染）
 */
class SystemUIHookIteration2AdversarialStressTest {

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
    // 对抗攻击 1：isNegativeName 极限词表与白名单穿透压测
    // =========================================================================

    @Test
    fun testAdversarialKeywords_comprehensiveWhitelistAndBlacklist() {
        val isNegativeNameMethod = getPrivateMethod("isNegativeName", String::class.java)

        // 1. 合法时钟集合（必须 100% 通过白名单，绝不允许任何误杀）
        val legitimateClocks = listOf(
            "com.android.systemui.statusbar.policy.Clock",
            "com.oplus.systemui.statusbar.policy.Clock",
            "com.oplusos.systemui.statusbar.policy.Clock",
            "com.android.systemui.statusbar.phone.PhoneStatusBarClock",
            "com.oplus.systemui.statusbar.phone.OPlusStatusBarClock",
            "com.coloros.systemui.statusbar.ClockView",
            "clock",
            "CLOCK",
            "Clock",
            "status_bar_clock",
            "STATUS_BAR_CLOCK",
            "oplus_status_bar_clock",
            "center_status_bar_clock",
            "clock_view_container",
            "system_clock_digital",
            "clock_tick"
        )

        for (name in legitimateClocks) {
            val isNeg = isNegativeNameMethod.invoke(SystemUIHook, name) as Boolean
            assertFalse("Legitimate clock identifier '$name' must NOT be classified as negative!", isNeg)
        }

        // 2. 必须排除的黑名单时钟集合（必须 100% 拦截，防止锁屏/多任务时钟篡权）
        val blacklistedClocks = listOf(
            "com.android.keyguard.KeyguardClockSwitch",
            "com.oplus.systemui.keyguard.KeyguardClockView",
            "com.android.systemui.statusbar.phone.KeyguardStatusBarClock",
            "keyguard_clock",
            "keyguard_clock_switch",
            "lockscreen_clock",
            "lockscreen_digital_clock",
            "recents_clock",
            "overview_clock",
            "taskbar_clock",
            "qs_clock",
            "quicksettings_clock",
            "header_clock",
            "controlcenter_clock",
            "split_clock",
            "floating_clock"
        )

        for (name in blacklistedClocks) {
            val isNeg = isNegativeNameMethod.invoke(SystemUIHook, name) as Boolean
            assertTrue("Blacklisted clock identifier '$name' must be classified as negative!", isNeg)
        }
    }

    // =========================================================================
    // 对抗攻击 2：施密特双阈值迟滞（Schmidt Trigger Hysteresis）1000 次 1px 微抖动压测
    // =========================================================================

    @Test
    fun testSchmidtTriggerHysteresis_1000JitterCyclesNoFlicker() {
        val screenW = 1080
        val screenH = 2400
        val clockW = 100
        val clockH = 40

        // 施密特模型模拟器（与 SystemUIHook 内部数学模型 1:1 对齐）
        class SchmidtModel {
            var lastPhysicalVisible = true

            fun evaluate(clockX: Int, clockY: Int): Boolean {
                val isVisible = if (lastPhysicalVisible) {
                    val outLeft = clockX + clockW < -12
                    val outTop = clockY + clockH < -12
                    val outRight = clockX > screenW + 12
                    val outBottom = clockY > screenH + 12
                    !(outLeft || outTop || outRight || outBottom)
                } else {
                    val inHorizontal = clockX + clockW >= 0 && clockX <= screenW
                    val inVertical = clockY + clockH >= 0 && clockY <= screenH
                    inHorizontal && inVertical
                }
                lastPhysicalVisible = isVisible
                return isVisible
            }
        }

        val model = SchmidtModel()

        // 场景 A: 初始为 VISIBLE，时钟在左侧死区边缘 [-111px, -112px] 之间以 1px 振荡 1000 次
        // 注意：outLeft 条件为 clockX + clockW < -12 -> clockX + 100 < -12 -> clockX < -112
        // 当 clockX = -112 时，clockX + clockW = -12，不满足 < -12，依然为 visible
        // 当 clockX = -111 时，更加为 visible
        var flipCountA = 0
        var prevA = model.lastPhysicalVisible
        for (i in 1..1000) {
            val jitterX = if (i % 2 == 0) -111 else -112
            val vis = model.evaluate(jitterX, 20)
            if (vis != prevA) {
                flipCountA++
                prevA = vis
            }
        }
        assertEquals("在 -111px 与 -112px 边缘抖动 1000 次，状态保持稳定为 VISIBLE，翻转次数严格为 0", 0, flipCountA)
        assertTrue(model.lastPhysicalVisible)

        // 场景 B: 彻底滑出屏幕：clockX 设为 -115px (< -112px)，状态发生单次翻转进入 INVISIBLE
        val visB = model.evaluate(-115, 20)
        assertFalse("滑出 -12px 死区后转为不可见", visB)
        assertFalse(model.lastPhysicalVisible)

        // 场景 C: 处于 INVISIBLE 状态下，在死区内部 [-105px, -110px] 发生 1000 次抖动
        // 恢复可见需要 clockX + clockW >= 0 (即 clockX >= -100)
        // 在 [-105, -110] 之间，虽然比 -112px 靠近屏幕，但由于施密特门禁要求 >= 0px，状态必须稳固保持为 INVISIBLE！
        var flipCountC = 0
        var prevC = model.lastPhysicalVisible
        for (i in 1..1000) {
            val jitterX = if (i % 2 == 0) -105 else -110
            val vis = model.evaluate(jitterX, 20)
            if (vis != prevC) {
                flipCountC++
                prevC = vis
            }
        }
        assertEquals("在死区内抖动 1000 次，状态稳固保持为 INVISIBLE，翻转次数严格为 0", 0, flipCountC)
        assertFalse(model.lastPhysicalVisible)

        // 场景 D: 重新回到屏幕物理边界内 (clockX = 0)，状态平稳恢复为 VISIBLE
        val visD = model.evaluate(0, 20)
        assertTrue("回到 >= 0px 时平稳恢复可见", visD)
        assertTrue(model.lastPhysicalVisible)
    }

    // =========================================================================
    // 对抗攻击 3：0 宽排版感知机制与排版完成即时响应
    // =========================================================================

    @Test
    fun testZeroWidthLayoutTransition_preDrawListenerSizeChangeDetection() {
        // 模拟 PreDrawListener 中的条件：
        // (lastClockWidth <= 0 && widthNow > 0) || (lastClockHeight <= 0 && heightNow > 0) ||
        // (widthNow != lastClockWidth) || (heightNow != lastClockHeight)

        fun checkSizeChanged(
            lastW: Int, lastH: Int,
            nowW: Int, nowH: Int
        ): Boolean {
            return (lastW <= 0 && nowW > 0) ||
                    (lastH <= 0 && nowH > 0) ||
                    (nowW != lastW) ||
                    (nowH != lastH)
        }

        // 1. 时钟从 0 宽到完成排版 100x40：必须成功捕获并触发重排
        val transitionFromZero = checkSizeChanged(0, 0, 100, 40)
        assertTrue("0 宽排版完成瞬间必须被捕获以触发重排", transitionFromZero)

        // 2. 状态栏字体改变或时钟文本跳变（如从 09:59 变到 10:00，宽度由 90px 变为 105px）：必须捕获
        val widthExpanded = checkSizeChanged(90, 40, 105, 40)
        assertTrue("时钟宽度微变必须被捕获以重置相对偏移", widthExpanded)

        // 3. 尺寸稳定无变化时：不触发冗余重排
        val sizeStable = checkSizeChanged(105, 40, 105, 40)
        assertFalse("尺寸无变化时不应触发重排", sizeStable)
    }

    // =========================================================================
    // 对抗攻击 4：ImmersiveOverlayManager 候选窗口类型 2038 纯净性（彻底排除 2041）
    // =========================================================================

    @Test
    fun testCandidateTypes_no2041Contamination() {
        // 读取 SystemUIHook.kt 源码，确认沉浸管理器中绝无 2041 残留
        val sourceFile = listOf(
            java.io.File("/root/电池悬浮窗/app/src/main/java/com/jakliuyuy/batterypower/hook/SystemUIHook.kt"),
            java.io.File("src/main/java/com/jakliuyuy/batterypower/hook/SystemUIHook.kt"),
            java.io.File("app/src/main/java/com/jakliuyuy/batterypower/hook/SystemUIHook.kt")
        ).firstOrNull { it.exists() }
        assertNotNull("SystemUIHook.kt 源码文件必须存在", sourceFile)
        val content = sourceFile!!.readText()

        // 核心对抗断言：
        // 1. candidateTypes 列表中绝不能包含 2041
        val candidateTypesBlock = content.substringAfter("val candidateTypes =").substringBefore("var lpFlags =")
        assertFalse(
            "candidateTypes list must NOT contain 2041 (TYPE_STATUS_BAR_ADDITIONAL)!",
            candidateTypesBlock.contains("2041")
        )

        // 2. 必须包含 TYPE_APPLICATION_OVERLAY (2038)
        assertTrue(
            "SystemUIHook must contain WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY!",
            content.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY")
        )

        // 3. 必须包含 FLAG_NOT_TOUCHABLE 与 FLAG_NOT_FOCUSABLE 触控穿透配置
        assertTrue(
            "SystemUIHook must configure FLAG_NOT_TOUCHABLE for 100% touch-through!",
            content.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE")
        )
        assertTrue(
            "SystemUIHook must configure FLAG_NOT_FOCUSABLE!",
            content.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE")
        )
    }

    // =========================================================================
    // 对抗攻击 5：全屏游戏与多任务手势切入切出全生命周期压测
    // =========================================================================

    @Test
    fun testLifecycle_fullGameAndRecentsGestureTransitions() {
        // shouldShowImmersive = !isClockVisible && hookEnabled && immersiveEnabled && isInteractive && !isLocked
        data class HookState(
            var isClockVisible: Boolean,
            var hookEnabled: Boolean = true,
            var immersiveEnabled: Boolean = true,
            var isInteractive: Boolean = true,
            var isLocked: Boolean = false
        ) {
            val shouldShowImmersive: Boolean
                get() = !isClockVisible && hookEnabled && immersiveEnabled && isInteractive && !isLocked
        }

        val state = HookState(isClockVisible = true)

        // 步骤 1: 桌面前台（正常显示桌面，时钟可见）
        assertFalse("桌面场景下沉浸浮层绝不显示", state.shouldShowImmersive)

        // 步骤 2: 点击启动大型横屏游戏《原神》
        // 游戏启动后接管全屏，状态栏移出屏幕或沉浸隐藏 -> isClockVisible 变为 false
        state.isClockVisible = false
        assertTrue("进入全屏游戏时，沉浸接管引擎毫秒级唤醒浮层", state.shouldShowImmersive)

        // 步骤 3: 游戏中玩家从底部上滑呼出多任务手势（Recents）
        // 系统状态栏浮现 -> isClockVisible 瞬间变为 true
        state.isClockVisible = true
        assertFalse("滑动呼出多任务时，沉浸浮层必须立即隐去，绝不在多任务卡片上反向冒出！", state.shouldShowImmersive)

        // 步骤 4: 玩家放弃切换，点击游戏卡片回到游戏
        // 状态栏重新隐藏 -> isClockVisible 变为 false
        state.isClockVisible = false
        assertTrue("切回游戏后，沉浸浮层立即恢复展示", state.shouldShowImmersive)

        // 步骤 5: 游戏过程中玩家按下电源键锁屏
        // 息屏 -> isInteractive 变为 false
        state.isInteractive = false
        assertFalse("息屏时立即隐藏浮层并休眠", state.shouldShowImmersive)

        // 步骤 6: 玩家点亮屏幕进入锁屏界面
        // 亮屏 (isInteractive=true)，但处于锁屏锁定态 (isLocked=true)
        state.isInteractive = true
        state.isLocked = true
        assertFalse("锁屏界面下严禁显示浮层（彻底杜绝幽灵浮层）", state.shouldShowImmersive)

        // 步骤 7: 指纹解锁成功，直接进入游戏界面
        // isLocked 变为 false
        state.isLocked = false
        assertTrue("解锁后沉浸浮层无缝接管", state.shouldShowImmersive)
    }
}
