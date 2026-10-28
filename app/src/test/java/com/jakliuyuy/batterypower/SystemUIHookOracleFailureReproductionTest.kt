package com.jakliuyuy.batterypower

import com.jakliuyuy.batterypower.hook.SystemUIHook
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Method

/**
 * 针对 SystemUIHook 致命缺陷与逻辑漏洞的 Oracle 规范断言复现测试
 * 
 * 任何符合架构契约的实现都应该通过本套件中的断言。
 * 当前由于 Worker 实现中的 P0 级致命缺陷，本测试将明确报红失败（AssertionError），
 * 为 Challenger 的 REJECT 裁决提供直接的、不可辩驳的经验性红灯铁证。
 */
class SystemUIHookOracleFailureReproductionTest {

    private fun getPrivateMethod(name: String, vararg paramTypes: Class<*>): Method {
        val method = SystemUIHook::class.java.getDeclaredMethod(name, *paramTypes)
        method.isAccessible = true
        return method
    }

    /**
     * 【P0 级致命漏洞修复验证】
     * 规范契约：原生状态栏时钟类（如 com.android.systemui.statusbar.policy.Clock）
     * 必须是合法时钟，绝对不能被判定为负向词（negative）！
     */
    @Test
    fun reproduceCriticalBug_clockMustNotBeNegative() {
        val isNegativeNameMethod = getPrivateMethod("isNegativeName", String::class.java)
        val clockClass = "com.android.systemui.statusbar.policy.Clock"
        val isNegative = isNegativeNameMethod.invoke(SystemUIHook, clockClass) as Boolean

        assertFalse(
            "Native Clock class '$clockClass' must NOT be classified as negative!",
            isNegative
        )
    }
}
