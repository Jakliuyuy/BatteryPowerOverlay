package com.jakliuyuy.batterypower

import com.jakliuyuy.batterypower.hook.ConfigBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParsingTest {

    @Test
    fun testEmptyMapFallbacks() {
        val emptyConfig = emptyMap<String, String>()

        assertEquals(0, ConfigBridge.intOf(emptyConfig, "hook_side", 0))
        assertEquals(1000L, ConfigBridge.longOf(emptyConfig, "hook_refresh_ms", 1000L))
        assertEquals(11f, ConfigBridge.floatOf(emptyConfig, "hook_text_size", 11f), 0.01f)
        assertTrue(ConfigBridge.boolOf(emptyConfig, "hook_enabled", true))
        assertFalse(ConfigBridge.boolOf(emptyConfig, "hook_show_current", false))
    }

    @Test
    fun testValidParsedValues() {
        val config = mapOf(
            "hook_side" to "1",
            "hook_refresh_ms" to "500",
            "hook_text_size" to "14.5",
            "hook_enabled" to "false",
            "hook_adapt_color" to "true",
            "hook_offset_x" to "-25",
            "hook_offset_y" to "12"
        )

        assertEquals(1, ConfigBridge.intOf(config, "hook_side", 0))
        assertEquals(500L, ConfigBridge.longOf(config, "hook_refresh_ms", 1000L))
        assertEquals(14.5f, ConfigBridge.floatOf(config, "hook_text_size", 11f), 0.01f)
        assertFalse(ConfigBridge.boolOf(config, "hook_enabled", true))
        assertTrue(ConfigBridge.boolOf(config, "hook_adapt_color", false))
        assertEquals(-25, ConfigBridge.intOf(config, "hook_offset_x", 0))
        assertEquals(12, ConfigBridge.intOf(config, "hook_offset_y", 0))
    }

    @Test
    fun testInvalidOrMalformedValues() {
        val badConfig = mapOf(
            "hook_side" to "invalid_int",
            "hook_refresh_ms" to "abc",
            "hook_text_size" to "xyz",
            "hook_enabled" to "not_a_bool"
        )

        // 应平稳回退至传入的默认值，不崩溃
        assertEquals(2, ConfigBridge.intOf(badConfig, "hook_side", 2))
        assertEquals(2000L, ConfigBridge.longOf(badConfig, "hook_refresh_ms", 2000L))
        assertEquals(12f, ConfigBridge.floatOf(badConfig, "hook_text_size", 12f), 0.01f)
        assertTrue(ConfigBridge.boolOf(badConfig, "hook_enabled", true))
    }

    @Test
    fun testGlobalRefreshIntervals() {
        // 测试全局刷新率支持的 5 档预设：500, 1000, 1500, 2000, 3000
        val intervals = listOf(500L, 1000L, 1500L, 2000L, 3000L)
        for (ms in intervals) {
            val config = mapOf("refresh_ms" to ms.toString(), "hook_refresh_ms" to ms.toString())
            val parsedHook = ConfigBridge.longOf(config, "hook_refresh_ms", 1000L)
            val parsedOverlay = ConfigBridge.longOf(config, "refresh_ms", 1000L)
            assertEquals(ms, parsedHook)
            assertEquals(ms, parsedOverlay)
        }

        // 测试 refresh_ms 回退链
        val hookOnly = mapOf("hook_refresh_ms" to "1500")
        val refreshMsFromHook = ConfigBridge.longOf(hookOnly, "hook_refresh_ms", ConfigBridge.longOf(hookOnly, "refresh_ms", 1000L))
        assertEquals(1500L, refreshMsFromHook)

        val overlayOnly = mapOf("refresh_ms" to "2000")
        val refreshMsFromOverlay = ConfigBridge.longOf(overlayOnly, "hook_refresh_ms", ConfigBridge.longOf(overlayOnly, "refresh_ms", 1000L))
        assertEquals(2000L, refreshMsFromOverlay)
    }

    @Test
    fun testHardwareSettingsParsing() {
        // 测试双电芯修正与极性翻转的跨进程解析
        val enabledConfig = mapOf(
            "polarity_invert" to "true",
            "double_cell_fix" to "true"
        )
        assertTrue(ConfigBridge.boolOf(enabledConfig, "polarity_invert", false))
        assertTrue(ConfigBridge.boolOf(enabledConfig, "double_cell_fix", false))

        val disabledConfig = mapOf(
            "polarity_invert" to "false",
            "double_cell_fix" to "false"
        )
        assertFalse(ConfigBridge.boolOf(disabledConfig, "polarity_invert", true))
        assertFalse(ConfigBridge.boolOf(disabledConfig, "double_cell_fix", true))

        // 缺省回退
        val emptyConfig = emptyMap<String, String>()
        assertFalse(ConfigBridge.boolOf(emptyConfig, "polarity_invert", false))
        assertFalse(ConfigBridge.boolOf(emptyConfig, "double_cell_fix", false))
    }

    @Test
    fun testUnitDisplayParsing() {
        // 状态栏专属单位覆盖全局单位
        val hookUnitOnly = mapOf("hook_show_unit" to "false", "show_unit" to "true")
        val unitResult1 = ConfigBridge.boolOf(hookUnitOnly, "hook_show_unit", ConfigBridge.boolOf(hookUnitOnly, "show_unit", true))
        assertFalse(unitResult1)

        // 专属单位未设时，回退至全局单位
        val globalUnitOnly = mapOf("show_unit" to "false")
        val unitResult2 = ConfigBridge.boolOf(globalUnitOnly, "hook_show_unit", ConfigBridge.boolOf(globalUnitOnly, "show_unit", true))
        assertFalse(unitResult2)

        val globalUnitOn = mapOf("show_unit" to "true")
        val unitResult3 = ConfigBridge.boolOf(globalUnitOn, "hook_show_unit", ConfigBridge.boolOf(globalUnitOn, "show_unit", false))
        assertTrue(unitResult3)
    }

    @Test
    fun testAllKeysCompleteness() {
        val requiredKeys = setOf(
            "hook_enabled",
            "hook_layer_mode",
            "hook_refresh_ms",
            "refresh_ms",
            "global_refresh_ms",
            "show_unit",
            "hook_show_unit",
            "hook_side",
            "hook_offset_x",
            "hook_offset_y",
            "hook_adapt_color",
            "hook_follow_clock_size",
            "hook_text_color",
            "hook_text_size",
            "hook_show_power",
            "hook_show_current",
            "hook_show_voltage",
            "hook_show_temp",
            "hook_show_level",
            "polarity_invert",
            "double_cell_fix"
        )
        val providerKeys = com.jakliuyuy.batterypower.config.ConfigProvider.ALL_KEYS.toSet()
        for (key in requiredKeys) {
            assertTrue("ConfigProvider.ALL_KEYS 必须包含关键跨进程键: $key", providerKeys.contains(key))
        }
    }
}
