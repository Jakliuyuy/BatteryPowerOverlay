package com.jakliuyuy.batterypower.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.jakliuyuy.batterypower.config.ConfigProvider

/**
 * 配置持久化管理（悬浮窗位置、显示项、刷新频率、Hook 状态栏参数等）。
 *
 * 核心设计：
 * 1. 使用 Device Protected Storage（设备保护存储）：Android 7.0+ 开机 Direct Boot 阶段即可访问，
 *    即便用户尚未解锁手机屏幕，配置也能被 SystemUI 和开机广播正常读取。
 * 2. 关键写操作强制使用 commit() 同步落盘，彻底消除进程突遭终止或手机重启时内存未落盘导致的配置丢失。
 * 3. 任何与 Hook 相关的配置修改均自动触发 ConfigProvider.notifyChanged()，通知 SystemUI 毫秒级即时刷新。
 */
object SettingsStore {
    private const val PREF_NAME = "battery_power_prefs"

    fun sp(ctx: Context): SharedPreferences {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ctx.createDeviceProtectedStorageContext()
        } else {
            ctx
        }
        return storageContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getEditor(): SharedPreferences.Editor = sp(AppContext.get()).edit()

    private fun notifyHookUpdate() {
        ConfigProvider.notifyChanged(AppContext.get())
    }

    // 运行模式
    const val MODE_OVERLAY = 0
    const val MODE_HOOK = 1

    var mode: Int
        get() = sp(AppContext.get()).getInt("mode", MODE_OVERLAY)
        set(v) { getEditor().putInt("mode", v).commit() }

    // 开机自启动悬浮窗
    var bootStartOverlay: Boolean
        get() = sp(AppContext.get()).getBoolean("boot_start_overlay", true)
        set(v) { getEditor().putBoolean("boot_start_overlay", v).commit() }

    // 悬浮窗位置（commit 同步落盘：开机或杀后台绝不丢位置）
    var overlayX: Int
        get() = sp(AppContext.get()).getInt("overlay_x", -1)
        set(v) { getEditor().putInt("overlay_x", v).commit() }

    var overlayY: Int
        get() = sp(AppContext.get()).getInt("overlay_y", -1)
        set(v) { getEditor().putInt("overlay_y", v).commit() }

    // 悬浮窗位置锁定（防误触拖动）
    var overlayLocked: Boolean
        get() = sp(AppContext.get()).getBoolean("overlay_locked", false)
        set(v) { getEditor().putBoolean("overlay_locked", v).commit() }

    // 悬浮窗背景样式：0=极简全透明, 1=磨砂黑胶囊, 2=毛玻璃半透, 3=精致细描边
    const val STYLE_TRANSPARENT = 0
    const val STYLE_CAPSULE_DARK = 1
    const val STYLE_CAPSULE_FROSTED = 2
    const val STYLE_CAPSULE_OUTLINE = 3

    var overlayStyle: Int
        get() = sp(AppContext.get()).getInt("overlay_style", STYLE_CAPSULE_DARK)
        set(v) { getEditor().putInt("overlay_style", v).commit() }

    // 悬浮窗贴边吸附
    var overlaySnapEdge: Boolean
        get() = sp(AppContext.get()).getBoolean("overlay_snap_edge", false)
        set(v) { getEditor().putBoolean("overlay_snap_edge", v).commit() }

    // 悬浮窗显示项
    var showPower: Boolean
        get() = sp(AppContext.get()).getBoolean("show_power", true)
        set(v) { getEditor().putBoolean("show_power", v).commit() }

    var showCurrent: Boolean
        get() = sp(AppContext.get()).getBoolean("show_current", true)
        set(v) { getEditor().putBoolean("show_current", v).commit() }

    var showVoltage: Boolean
        get() = sp(AppContext.get()).getBoolean("show_voltage", false)
        set(v) { getEditor().putBoolean("show_voltage", v).commit() }

    var showLevel: Boolean
        get() = sp(AppContext.get()).getBoolean("show_level", true)
        set(v) { getEditor().putBoolean("show_level", v).commit() }

    var showTemp: Boolean
        get() = sp(AppContext.get()).getBoolean("show_temp", false)
        set(v) { getEditor().putBoolean("show_temp", v).commit() }

    // 刷新频率 ms
    var refreshMs: Long
        get() = sp(AppContext.get()).getLong("refresh_ms", 1000L)
        set(v) { getEditor().putLong("refresh_ms", v).commit() }

    // 悬浮窗样式
    var textSizeSp: Float
        get() = sp(AppContext.get()).getFloat("text_size", 13f)
        set(v) { getEditor().putFloat("text_size", v).commit() }

    var textColor: Int
        get() = sp(AppContext.get()).getInt("text_color", 0xFF00E676.toInt()) // 默认现代极客荧光绿
        set(v) { getEditor().putInt("text_color", v).commit() }

    // 通用：显示单位（W/mA/V/°C/%）
    var showUnit: Boolean
        get() = sp(AppContext.get()).getBoolean("show_unit", true)
        set(v) {
            getEditor().putBoolean("show_unit", v).commit()
            notifyHookUpdate()
        }

    // 电池与内核读取高级配置
    var polarityInvert: Boolean // 充放电符号极性翻转
        get() = sp(AppContext.get()).getBoolean("polarity_invert", false)
        set(v) { getEditor().putBoolean("polarity_invert", v).commit() }

    var doubleCellFix: Boolean // 双电芯功率自适应
        get() = sp(AppContext.get()).getBoolean("double_cell_fix", false)
        set(v) { getEditor().putBoolean("double_cell_fix", v).commit() }

    // -------------------------------------------------------------
    // Hook 模式配置（SystemUI 进程使用 ContentProvider 与磁盘缓存读取）
    // -------------------------------------------------------------

    var hookEnabled: Boolean  // 状态栏显示总开关
        get() = sp(AppContext.get()).getBoolean("hook_enabled", true)
        set(v) {
            getEditor().putBoolean("hook_enabled", v).commit()
            notifyHookUpdate()
        }

    // 状态栏刷新频率 ms（500/1000/2000/3000/5000）
    var hookRefreshMs: Long
        get() = sp(AppContext.get()).getLong("hook_refresh_ms", 1000L)
        set(v) {
            getEditor().putLong("hook_refresh_ms", v).commit()
            notifyHookUpdate()
        }

    // 锚点定义：0=时钟右侧, 1=时钟左侧, 2=状态栏最右侧, 3=状态栏最左侧
    const val ANCHOR_CLOCK_RIGHT = 0
    const val ANCHOR_CLOCK_LEFT = 1
    const val ANCHOR_STATUSBAR_RIGHT = 2
    const val ANCHOR_STATUSBAR_LEFT = 3

    var hookSide: Int
        get() = sp(AppContext.get()).getInt("hook_side", ANCHOR_CLOCK_RIGHT)
        set(v) {
            getEditor().putInt("hook_side", v).commit()
            notifyHookUpdate()
        }

    // 水平偏移 px
    var hookOffsetX: Int
        get() = sp(AppContext.get()).getInt("hook_offset_x", 0)
        set(v) {
            getEditor().putInt("hook_offset_x", v).commit()
            notifyHookUpdate()
        }

    // 垂直偏移 px
    var hookOffsetY: Int
        get() = sp(AppContext.get()).getInt("hook_offset_y", 0)
        set(v) {
            getEditor().putInt("hook_offset_y", v).commit()
            notifyHookUpdate()
        }

    // 智能避让右侧图标
    var hookAvoid: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_avoid", true)
        set(v) {
            getEditor().putBoolean("hook_avoid", v).commit()
            notifyHookUpdate()
        }

    // 状态栏文字跟随系统暗色模式或自适应
    var hookAdaptColor: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_adapt_color", false)
        set(v) {
            getEditor().putBoolean("hook_adapt_color", v).commit()
            notifyHookUpdate()
        }

    var hookShowPower: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_power", true)
        set(v) {
            getEditor().putBoolean("hook_show_power", v).commit()
            notifyHookUpdate()
        }

    var hookShowCurrent: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_current", false)
        set(v) {
            getEditor().putBoolean("hook_show_current", v).commit()
            notifyHookUpdate()
        }

    var hookShowVoltage: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_voltage", false)
        set(v) {
            getEditor().putBoolean("hook_show_voltage", v).commit()
            notifyHookUpdate()
        }

    var hookShowLevel: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_level", true)
        set(v) {
            getEditor().putBoolean("hook_show_level", v).commit()
            notifyHookUpdate()
        }

    var hookShowTemp: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_temp", false)
        set(v) {
            getEditor().putBoolean("hook_show_temp", v).commit()
            notifyHookUpdate()
        }

    var hookTextColor: Int
        get() = sp(AppContext.get()).getInt("hook_text_color", 0xFFFFFFFF.toInt())
        set(v) {
            getEditor().putInt("hook_text_color", v).commit()
            notifyHookUpdate()
        }

    var hookTextSizeSp: Float
        get() = sp(AppContext.get()).getFloat("hook_text_size", 11f)
        set(v) {
            getEditor().putFloat("hook_text_size", v).commit()
            notifyHookUpdate()
        }
}

/** 简化全局 Context 安全获取 */
object AppContext {
    private var instance: Context? = null

    fun init(ctx: Context) {
        if (instance == null) {
            instance = ctx.applicationContext ?: ctx
        }
    }

    var ctx: Context
        get() = instance ?: error("AppContext has not been initialized. Ensure BatteryPowerApp is declared in AndroidManifest.")
        set(value) { instance = value.applicationContext ?: value }

    fun get(): Context = instance ?: error("AppContext has not been initialized.")
}
