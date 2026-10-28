package com.jakliuyuy.batterypower.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.jakliuyuy.batterypower.config.ConfigProvider

/**
 * 集中统一配置持久化管理：
 * 1. 采用 Device Protected Storage（设备保护存储），确保 Android 7.0+ Direct Boot 阶段（开机未解锁）完全可用。
 * 2. 关键写操作一律采用 commit() 强制同步落盘，彻底根绝掉电或重启导致的配置丢失。
 * 3. 任何与 Hook 相关的配置变更自动调用 ConfigProvider.notifyChanged()，毫秒级跨进程热推。
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

    // 悬浮窗开机自启
    var bootStartOverlay: Boolean
        get() = sp(AppContext.get()).getBoolean("boot_start_overlay", false)
        set(v) { getEditor().putBoolean("boot_start_overlay", v).commit() }

    // 悬浮窗坐标（强制 commit 同步落盘，永久记忆坐标）
    var overlayX: Int
        get() = sp(AppContext.get()).getInt("overlay_x", -1)
        set(v) { getEditor().putInt("overlay_x", v).commit() }

    var overlayY: Int
        get() = sp(AppContext.get()).getInt("overlay_y", -1)
        set(v) { getEditor().putInt("overlay_y", v).commit() }

    // 悬浮窗锁定
    var overlayLocked: Boolean
        get() = sp(AppContext.get()).getBoolean("overlay_locked", false)
        set(v) { getEditor().putBoolean("overlay_locked", v).commit() }

    // 悬浮窗贴边吸附
    var overlaySnapEdge: Boolean
        get() = sp(AppContext.get()).getBoolean("overlay_snap_edge", false)
        set(v) { getEditor().putBoolean("overlay_snap_edge", v).commit() }

    // 悬浮窗外观风格
    const val STYLE_TRANSPARENT = 0     // 极简全透明
    const val STYLE_CAPSULE_DARK = 1    // 雅致深灰胶囊
    const val STYLE_CAPSULE_FROSTED = 2 // 雾面毛玻璃胶囊
    const val STYLE_CAPSULE_OUTLINE = 3 // 简约微光描边
    const val STYLE_CAPSULE_LIGHT = 4   // 纯净雪白胶囊

    var overlayStyle: Int
        get() = sp(AppContext.get()).getInt("overlay_style", STYLE_CAPSULE_FROSTED)
        set(v) { getEditor().putInt("overlay_style", v).commit() }

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

    // 悬浮窗刷新率
    var refreshMs: Long
        get() = sp(AppContext.get()).getLong("refresh_ms", 1000L)
        set(v) { getEditor().putLong("refresh_ms", v).commit() }

    // 悬浮窗字号与文字颜色
    var textSizeSp: Float
        get() = sp(AppContext.get()).getFloat("text_size", 12f)
        set(v) { getEditor().putFloat("text_size", v).commit() }

    var textColor: Int
        get() = sp(AppContext.get()).getInt("text_color", 0xFF00E676.toInt())
        set(v) { getEditor().putInt("text_color", v).commit() }

    // 显示单位
    var showUnit: Boolean
        get() = sp(AppContext.get()).getBoolean("show_unit", true)
        set(v) {
            getEditor().putBoolean("show_unit", v).commit()
            notifyHookUpdate()
        }

    // 极性与双电芯
    var polarityInvert: Boolean
        get() = sp(AppContext.get()).getBoolean("polarity_invert", false)
        set(v) { getEditor().putBoolean("polarity_invert", v).commit() }

    var doubleCellFix: Boolean
        get() = sp(AppContext.get()).getBoolean("double_cell_fix", false)
        set(v) { getEditor().putBoolean("double_cell_fix", v).commit() }

    // -------------------------------------------------------------
    // Hook 模式参数（SystemUI 进程使用）
    // -------------------------------------------------------------

    var hookEnabled: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_enabled", true)
        set(v) {
            getEditor().putBoolean("hook_enabled", v).commit()
            notifyHookUpdate()
        }

    var hookRefreshMs: Long
        get() = sp(AppContext.get()).getLong("hook_refresh_ms", 1000L)
        set(v) {
            getEditor().putLong("hook_refresh_ms", v).commit()
            notifyHookUpdate()
        }

    // 图层模式定义：0=独立覆盖图层（悬浮在通知上方，不挤压通知图标），1=并排图层（与时钟流式并列，会占用通知位置）
    const val LAYER_OVERLAY = 0
    const val LAYER_INLINE = 1

    var hookLayerMode: Int
        get() = sp(AppContext.get()).getInt("hook_layer_mode", LAYER_OVERLAY)
        set(v) {
            getEditor().putInt("hook_layer_mode", v).commit()
            notifyHookUpdate()
        }

    // 锚点定义
    const val ANCHOR_CLOCK_RIGHT = 0     // 时钟右侧（原生同级内嵌，绝对防漂移）
    const val ANCHOR_CLOCK_LEFT = 1      // 时钟左侧（原生同级内嵌，绝对防漂移）
    const val ANCHOR_STATUSBAR_RIGHT = 2 // 状态栏最右侧
    const val ANCHOR_STATUSBAR_LEFT = 3  // 状态栏最左侧

    var hookSide: Int
        get() = sp(AppContext.get()).getInt("hook_side", ANCHOR_CLOCK_RIGHT)
        set(v) {
            getEditor().putInt("hook_side", v).commit()
            notifyHookUpdate()
        }

    var hookOffsetX: Int
        get() = sp(AppContext.get()).getInt("hook_offset_x", 0)
        set(v) {
            getEditor().putInt("hook_offset_x", v).commit()
            notifyHookUpdate()
        }

    var hookOffsetY: Int
        get() = sp(AppContext.get()).getInt("hook_offset_y", 0)
        set(v) {
            getEditor().putInt("hook_offset_y", v).commit()
            notifyHookUpdate()
        }

    var hookAdaptColor: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_adapt_color", true)
        set(v) {
            getEditor().putBoolean("hook_adapt_color", v).commit()
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

    var hookShowPower: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_power", true)
        set(v) {
            getEditor().putBoolean("hook_show_power", v).commit()
            notifyHookUpdate()
        }

    // 状态栏专属单位显示开关（关闭后隐藏 W / mA / V 等字母）
    var hookShowUnit: Boolean
        get() = sp(AppContext.get()).getBoolean("hook_show_unit", true)
        set(v) {
            getEditor().putBoolean("hook_show_unit", v).commit()
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
}

/** 全局 Context 安全安全托管 */
object AppContext {
    private var instance: Context? = null

    fun init(ctx: Context) {
        if (instance == null) {
            instance = ctx.applicationContext ?: ctx
        }
    }

    var ctx: Context
        get() = instance ?: error("AppContext is not initialized")
        set(value) { instance = value.applicationContext ?: value }

    fun get(): Context = instance ?: error("AppContext is not initialized")
}
