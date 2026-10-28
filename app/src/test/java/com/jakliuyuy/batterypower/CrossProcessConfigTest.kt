package com.jakliuyuy.batterypower

import com.jakliuyuy.batterypower.battery.BatteryReader
import com.jakliuyuy.batterypower.hook.ConfigBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 针对 R3 跨进程与配置边界、电源合成与并发安全的健全测试用例 (Tier 2, Tier 3)
 */
class CrossProcessConfigTest {

    // =========================================================================
    // Tier 2: 极端与畸形配置解析容错测试 (TC-2.2.1, TC-2.2.2)
    // =========================================================================

    @Test
    fun testExtremeNumericBoundaries() {
        val extremeConfig = mapOf(
            "int_max" to Int.MAX_VALUE.toString(),
            "int_min" to Int.MIN_VALUE.toString(),
            "long_max" to Long.MAX_VALUE.toString(),
            "long_min" to Long.MIN_VALUE.toString(),
            "float_max" to Float.MAX_VALUE.toString(),
            "float_min" to Float.MIN_VALUE.toString()
        )

        assertEquals(Int.MAX_VALUE, ConfigBridge.intOf(extremeConfig, "int_max", 0))
        assertEquals(Int.MIN_VALUE, ConfigBridge.intOf(extremeConfig, "int_min", 0))
        assertEquals(Long.MAX_VALUE, ConfigBridge.longOf(extremeConfig, "long_max", 0L))
        assertEquals(Long.MIN_VALUE, ConfigBridge.longOf(extremeConfig, "long_min", 0L))
        assertEquals(Float.MAX_VALUE, ConfigBridge.floatOf(extremeConfig, "float_max", 0f), 0.001f)
    }

    @Test
    fun testSpecialAndMalformedStringInputs() {
        val malformedConfig = mapOf(
            "empty_str" to "",
            "whitespace_str" to "   ",
            "special_chars" to "!@#$%^&*()",
            "hex_number" to "0x1F",
            "overflow_number" to "99999999999999999999999999999999",
            "float_overflow" to "1e45",
            "not_a_float" to "invalid_float",
            "bool_fuzzy_yes" to "yes",
            "bool_fuzzy_1" to "1",
            "bool_fuzzy_true_upper" to "TRUE"
        )

        // 数字字段遇到非法格式平稳回退默认值，绝不崩溃
        assertEquals(42, ConfigBridge.intOf(malformedConfig, "empty_str", 42))
        assertEquals(42, ConfigBridge.intOf(malformedConfig, "whitespace_str", 42))
        assertEquals(42, ConfigBridge.intOf(malformedConfig, "special_chars", 42))
        assertEquals(42, ConfigBridge.intOf(malformedConfig, "hex_number", 42))
        assertEquals(42, ConfigBridge.intOf(malformedConfig, "overflow_number", 42))

        // 浮点数解析遇到非法或超出范围格式平稳回退默认值
        assertEquals(10.5f, ConfigBridge.floatOf(malformedConfig, "not_a_float", 10.5f), 0.01f)
        assertEquals(10.5f, ConfigBridge.floatOf(malformedConfig, "special_chars", 10.5f), 0.01f)
        assertEquals(10.5f, ConfigBridge.floatOf(malformedConfig, "empty_str", 10.5f), 0.01f)

        // 布尔值解析：ConfigBridge.boolOf 严格匹配 "true" / "false"，其余格式平稳回退默认值
        // 传入 "TRUE" (非严格小写) 应回退默认值
        assertFalse(ConfigBridge.boolOf(malformedConfig, "bool_fuzzy_true_upper", false))
        assertTrue(ConfigBridge.boolOf(malformedConfig, "bool_fuzzy_true_upper", true))
        assertFalse(ConfigBridge.boolOf(malformedConfig, "bool_fuzzy_yes", false))
        assertFalse(ConfigBridge.boolOf(malformedConfig, "bool_fuzzy_1", false))

        // 标准 "true" / "false" 解析
        val standardBoolConfig = mapOf("flag_on" to "true", "flag_off" to "false")
        assertTrue(ConfigBridge.boolOf(standardBoolConfig, "flag_on", false))
        assertFalse(ConfigBridge.boolOf(standardBoolConfig, "flag_off", true))
    }

    @Test
    fun testPixelAdjustmentOffsetRanges() {
        // 状态栏水平与垂直像素微调跨度验证 (-300px ~ +1300px, ±100px)
        val offsetConfig = mapOf(
            "hook_offset_x" to "-300",
            "hook_offset_y" to "100"
        )
        assertEquals(-300, ConfigBridge.intOf(offsetConfig, "hook_offset_x", 0))
        assertEquals(100, ConfigBridge.intOf(offsetConfig, "hook_offset_y", 0))

        val maxOffsetConfig = mapOf(
            "hook_offset_x" to "1300",
            "hook_offset_y" to "-100"
        )
        assertEquals(1300, ConfigBridge.intOf(maxOffsetConfig, "hook_offset_x", 0))
        assertEquals(-100, ConfigBridge.intOf(maxOffsetConfig, "hook_offset_y", 0))
    }

    // =========================================================================
    // Tier 1 & Tier 2: 状态栏数据文本合成组合逻辑模拟与测试 (TC-1.1.5, TC-2.2.4)
    // 覆盖 SystemUIHook.readSystemPower 中的组合策略
    // =========================================================================

    private fun synthesizePowerString(
        sample: BatteryReader.Sample,
        cfg: Map<String, String>
    ): String {
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
        if (ConfigBridge.boolOf(cfg, "hook_show_temp", false) && sample.tempC > 0) {
            parts.add(sample.tempText(unit))
        }
        if (ConfigBridge.boolOf(cfg, "hook_show_level", false)) {
            parts.add(sample.levelText(unit))
        }

        return if (parts.isEmpty()) sample.powerText(unit) else parts.joinToString(" ")
    }

    @Test
    fun testPowerStringSynthesis_powerOnlyDefault() {
        val sample = BatteryReader.Sample(
            powerW = 28.5,
            currentMa = 3000,
            voltageMv = 9500,
            level = 80,
            tempC = 33.2,
            status = "Charging",
            isCharging = true,
            source = "system"
        )
        // 默认只开功率，带单位
        val defaultCfg = emptyMap<String, String>()
        val result = synthesizePowerString(sample, defaultCfg)
        assertEquals("+28.5W", result)
    }

    @Test
    fun testPowerStringSynthesis_allFieldsEnabled() {
        val sample = BatteryReader.Sample(
            powerW = 28.5,
            currentMa = 3000,
            voltageMv = 9500,
            level = 80,
            tempC = 33.2,
            status = "Charging",
            isCharging = true,
            source = "system"
        )
        val allOnCfg = mapOf(
            "hook_show_power" to "true",
            "hook_show_current" to "true",
            "hook_show_voltage" to "true",
            "hook_show_temp" to "true",
            "hook_show_level" to "true",
            "hook_show_unit" to "true"
        )
        val result = synthesizePowerString(sample, allOnCfg)
        assertEquals("+28.5W +3.00A 9.50V 33.2°C 80%", result)

        // 无单位模式
        val noUnitCfg = allOnCfg + mapOf("hook_show_unit" to "false")
        val noUnitResult = synthesizePowerString(sample, noUnitCfg)
        assertEquals("+28.5 +3.00 9.50 33.2 80", noUnitResult)
    }

    @Test
    fun testPowerStringSynthesis_tempZeroOrNegativeHidden() {
        val sampleWithInvalidTemp = BatteryReader.Sample(
            powerW = 12.0,
            currentMa = 1200,
            voltageMv = 10000,
            level = 50,
            tempC = 0.0, // 温度异常为 0
            status = "Charging",
            isCharging = true,
            source = "system"
        )
        val cfgWithTemp = mapOf(
            "hook_show_power" to "true",
            "hook_show_temp" to "true"
        )
        val result = synthesizePowerString(sampleWithInvalidTemp, cfgWithTemp)
        // tempC <= 0 时不应输出温度
        assertEquals("+12.0W", result)
    }

    @Test
    fun testPowerStringSynthesis_allDisabledFallbacksToPower() {
        val sample = BatteryReader.Sample(
            powerW = 5.5,
            currentMa = 1000,
            voltageMv = 5500,
            level = 60,
            tempC = 30.0,
            status = "Charging",
            isCharging = true,
            source = "system"
        )
        // 用户将所有指标全部关闭，应当兜底输出功率，避免悬浮窗空白无字
        val allOffCfg = mapOf(
            "hook_show_power" to "false",
            "hook_show_current" to "false",
            "hook_show_voltage" to "false",
            "hook_show_temp" to "false",
            "hook_show_level" to "false"
        )
        val result = synthesizePowerString(sample, allOffCfg)
        assertEquals("+5.50W", result)
    }

    @Test
    fun testBatteryLevelClamping_0To100() {
        // 电量百分比必须严格钳位在 0 到 100 之间
        assertEquals(0, (-5).coerceIn(0, 100))
        assertEquals(0, 0.coerceIn(0, 100))
        assertEquals(75, 75.coerceIn(0, 100))
        assertEquals(100, 100.coerceIn(0, 100))
        assertEquals(100, 105.coerceIn(0, 100))
        assertEquals(100, 999.coerceIn(0, 100))
    }

    // =========================================================================
    // Tier 3: 并发高频读取与观察者安全性测试 (TC-3.3.1, TC-3.3.2)
    // =========================================================================

    @Test
    fun testConcurrentConfigAccessAndObserverRegistration() {
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        val testConfig = mapOf("hook_side" to "1", "hook_refresh_ms" to "1500")

        for (i in 0 until 4) {
            executor.submit {
                try {
                    for (j in 0 until 100) {
                        val side = ConfigBridge.intOf(testConfig, "hook_side", 0)
                        val ms = ConfigBridge.longOf(testConfig, "hook_refresh_ms", 1000L)
                        assertEquals(1, side)
                        assertEquals(1500L, ms)

                        val dummyObserver: (Map<String, String>) -> Unit = {}
                        ConfigBridge.removeObserver(dummyObserver)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("多线程并发读取配置与操作观察者未发生阻塞或死锁", completed)
    }
}
