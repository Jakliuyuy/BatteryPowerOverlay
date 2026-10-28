package com.jakliuyuy.batterypower.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build

/**
 * 跨进程配置通道：SystemUI 进程（hook）通过 ContentResolver 读取主 app 设置。
 *
 * 核心升级：
 * 1. 采用 Device Protected Storage，确保手机开机阶段（Direct Boot / 未解锁）也能正常读取。
 * 2. 导出 notifyChanged 方法，主 app 配置更改时即时触发 ContentResolver 通知，无需 SystemUI 苦等 1s 轮询。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf("key", "value"))
        val dpContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ctx.createDeviceProtectedStorageContext()
        } else {
            ctx
        }
        val sp = dpContext.getSharedPreferences("battery_power_prefs", Context.MODE_PRIVATE)
        val cursor = MatrixCursor(arrayOf("key", "value"))
        val allPrefs = sp.all
        for (key in ALL_KEYS) {
            val v = allPrefs[key] ?: continue
            cursor.addRow(arrayOf(key, v.toString()))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.batterypower.config"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.jakliuyuy.batterypower.config"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/config")

        fun notifyChanged(context: Context) {
            try {
                context.contentResolver.notifyChange(CONTENT_URI, null)
            } catch (_: Exception) {
            }
        }

        /** hook 需要读取的全部配置键 */
        val ALL_KEYS = arrayOf(
            "hook_enabled",
            "hook_layer_mode",
            "hook_refresh_ms",
            "show_unit",
            "hook_show_unit",
            "hook_side",
            "hook_offset_x",
            "hook_offset_y",
            "hook_avoid",
            "hook_adapt_color",
            "hook_follow_clock_size",
            "hook_text_color",
            "hook_text_size",
            "hook_show_power",
            "hook_show_current",
            "hook_show_voltage",
            "hook_show_temp",
            "hook_show_level"
        )
    }
}
