package com.jakliuyuy.batterypower.settings

import android.content.Context

/**
 * 配置持久化：悬浮窗位置、显示项、刷新频率等
 */
object SettingsStore {
    private const val PREF = "battery_power_prefs"

    fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // 模式
    const val MODE_OVERLAY = 0
    const val MODE_HOOK = 1

    var mode: Int
        get() = sp(AppContext.get()).getInt("mode", MODE_OVERLAY)
        set(v) = sp(AppContext.get()).edit().putInt("mode", v).apply()

    // 悬浮窗位置（用 commit 同步落盘：进程被杀瞬间不丢数据）
    var overlayX: Int
        get() = sp(AppContext.get()).getInt("overlay_x", -1)
        set(v) { sp(AppContext.get()).edit().putInt("overlay_x", v).commit() }
    var overlayY: Int
        get() = sp(AppContext.get()).getInt("overlay_y", -1)
        set(v) { sp(AppContext.get()).edit().putInt("overlay_y", v).commit() }

    // 显示项
    var showPower: Boolean
        get() = sp(AppContext.get()).getBoolean("show_power", true)
        set(v) = sp(AppContext.get()).edit().putBoolean("show_power", v).apply()
    var showCurrent: Boolean
        get() = sp(AppContext.get()).getBoolean("show_current", true)
        set(v) = sp(AppContext.get()).edit().putBoolean("show_current", v).apply()
    var showVoltage: Boolean
        get() = sp(AppContext.get()).getBoolean("show_voltage", false)
        set(v) = sp(AppContext.get()).edit().putBoolean("show_voltage", v).apply()
    var showLevel: Boolean
        get() = sp(AppContext.get()).getBoolean("show_level", true)
        set(v) = sp(AppContext.get()).edit().putBoolean("show_level", v).apply()
    var showTemp: Boolean
        get() = sp(AppContext.get()).getBoolean("show_temp", false)
        set(v) = sp(AppContext.get()).edit().putBoolean("show_temp", v).apply()

    // 刷新频率 ms
    var refreshMs: Long
        get() = sp(AppContext.get()).getLong("refresh_ms", 1000L)
        set(v) = sp(AppContext.get()).edit().putLong("refresh_ms", v).apply()

    // 悬浮窗样式
    var textSizeSp: Float
        get() = sp(AppContext.get()).getFloat("text_size", 14f)
        set(v) = sp(AppContext.get()).edit().putFloat("text_size", v).apply()
    var textColor: Int
        get() = sp(AppContext.get()).getInt("text_color", 0xFFFFFFFF.toInt())
        set(v) = sp(AppContext.get()).edit().putInt("text_color", v).apply()

    // Hook 模式配置（SystemUI 进程用 ContentProvider 读取）
    const val SP_NAME = "battery_power_prefs"

    var hookEnabled: Boolean  // 状态栏显示开关
        get() = sp(AppContext.get()).getBoolean("hook_enabled", true)
        set(v) = sp(AppContext.get()).edit().putBoolean("hook_enabled", v).apply()

    // 状态栏刷新频率 ms（500/1000/2000/3000/5000）
    var hookRefreshMs: Long
        get() = sp(AppContext.get()).getLong("hook_refresh_ms", 1000L)
        set(v) = sp(AppContext.get()).edit().putLong("hook_refresh_ms", v).apply()

    // 单位显示开关（false = 只显示数值）
    var showUnit: Boolean
        get() = sp(AppContext.get()).getBoolean("show_unit", true)
        set(v) = sp(AppContext.get()).edit().putBoolean("show_unit", v).apply()

    var hookSide: Int  // 0=时钟右侧 1=时钟左侧
        get() = sp(AppContext.get()).getInt("hook_side", 0)
        set(v) = sp(AppContext.get()).edit().putInt("hook_side", v).apply()
    var hookOffsetX: Int  // 水平偏移 px（-200 ~ 200）
        get() = sp(AppContext.get()).getInt("hook_offset_x", 0)
        set(v) = sp(AppContext.get()).edit().putInt("hook_offset_x", v).apply()
    var hookOffsetY: Int  // 垂直偏移 px（-100 ~ 100）
        get() = sp(AppContext.get()).getInt("hook_offset_y", 0)
        set(v) = sp(AppContext.get()).edit().putInt("hook_offset_y", v).apply()
    var hookAvoid: Boolean  // 智能避让右侧图标
        get() = sp(AppContext.get()).getBoolean("hook_avoid", true)
        set(v) = sp(AppContext.get()).edit().putBoolean("hook_avoid", v).apply()
    var hookShowPower: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_power", true)
        set(v) = sp(AppContext.get()).edit().putBoolean("hook_show_power", v).apply()
    var hookShowCurrent: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_current", false)
        set(v) = sp(AppContext.get()).edit().putBoolean("hook_show_current", v).apply()
    var hookShowVoltage: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_voltage", false)
        set(v) = sp(AppContext.get()).edit().putBoolean("hook_show_voltage", v).apply()
    var hookShowLevel: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_level", true)
        set(v) = sp(AppContext.get()).edit().putBoolean("hook_show_level", v).apply()
    var hookShowTemp: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_temp", false)
        set(v) = sp(AppContext.get()).edit().putBoolean("hook_show_temp", v).apply()
    var hookTextColor: Int
        get() = sp(AppContext.get()).getInt("hook_text_color", 0xFFFFFFFF.toInt())
        set(v) = sp(AppContext.get()).edit().putInt("hook_text_color", v).apply()
    var hookTextSizeSp: Float
        get() = sp(AppContext.get()).getFloat("hook_text_size", 11f)
        set(v) = sp(AppContext.get()).edit().putFloat("hook_text_size", v).apply()
}

/** 简化 Context 获取（悬浮窗服务/Application 场景） */
object AppContext {
    lateinit var ctx: Context
    fun get() = ctx
}
