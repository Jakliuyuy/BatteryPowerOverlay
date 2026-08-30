package com.jakliuyuy.batterypower.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 跨进程配置通道：SystemUI 进程（hook）通过 ContentResolver 读取主 app 设置。
 *
 * 替代不可靠的 XSharedPreferences（Android 11+ / LSPosed 下读不到 0600 权限的
 * SharedPreferences 文件）。标准 ContentProvider IPC，任何环境 100% 可用。
 *
 * authority: com.jakliuyuy.batterypower.config
 * query() 返回 [key, value] 双列游标，key 为配置键（同 SettingsStore 键名）。
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
        val sp = context?.getSharedPreferences("battery_power_prefs", 0) ?: return MatrixCursor(
            arrayOf("key", "value")
        )
        val cursor = MatrixCursor(arrayOf("key", "value"))
        for (key in ALL_KEYS) {
            val v = sp.all[key] ?: continue
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

        /** hook 需要读取的全部配置键 */
        val ALL_KEYS = arrayOf(
            "hook_side",
            "hook_offset_x",
            "hook_offset_y",
            "hook_avoid",
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
