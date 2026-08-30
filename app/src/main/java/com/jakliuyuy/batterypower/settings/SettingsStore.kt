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

    // 悬浮窗位置
    var overlayX: Int
        get() = sp(AppContext.get()).getInt("overlay_x", -1)
        set(v) = sp(AppContext.get()).edit().putInt("overlay_x", v).apply()
    var overlayY: Int
        get() = sp(AppContext.get()).getInt("overlay_y", -1)
        set(v) = sp(AppContext.get()).edit().putInt("overlay_y", v).apply()

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

    // 刷新频率 ms
    var refreshMs: Long
        get() = sp(AppContext.get()).getLong("refresh_ms", 1000L)
        set(v) = sp(AppContext.get()).edit().putLong("refresh_ms", v).apply()

    // 悬浮窗样式
    var textSizeSp: Float
        get() = sp(AppContext.get()).getFloat("text_size", 14f)
        set(v) = sp(AppContext.get()).edit().putFloat("text_size", v).apply()
    var bgAlpha: Int
        get() = sp(AppContext.get()).getInt("bg_alpha", 120)
        set(v) = sp(AppContext.get()).edit().putInt("bg_alpha", v).apply()

    // Hook 模式：显示在时钟哪一侧（0=右侧 1=左侧）
    var hookSide: Int
        get() = sp(AppContext.get()).getInt("hook_side", 0)
        set(v) = sp(AppContext.get()).edit().putInt("hook_side", v).apply()
}

/** 简化 Context 获取（悬浮窗服务/Application 场景） */
object AppContext {
    lateinit var ctx: Context
    fun get() = ctx
}
