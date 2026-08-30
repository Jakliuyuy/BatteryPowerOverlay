package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.database.Cursor

/**
 * hook 侧配置桥：SystemUI 进程内通过 ContentProvider 拉取主 app 配置。
 * 查询失败返回空 Map → 调用方使用内置默认值，绝不崩溃。
 */
object ConfigBridge {

    fun query(ctx: Context): Map<String, String> {
        return try {
            val cursor: Cursor? = ctx.contentResolver.query(
                com.jakliuyuy.batterypower.config.ConfigProvider.CONTENT_URI,
                null, null, null, null
            )
            if (cursor == null) return emptyMap()
            cursor.use { c ->
                if (!c.moveToFirst()) return emptyMap()
                val kIdx = c.getColumnIndexOrThrow("key")
                val vIdx = c.getColumnIndexOrThrow("value")
                val map = HashMap<String, String>()
                do {
                    map[c.getString(kIdx)] = c.getString(vIdx)
                } while (c.moveToNext())
                map
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ---- 便捷读取（带默认值）----

    fun intOf(cfg: Map<String, String>, key: String, def: Int): Int =
        cfg[key]?.toIntOrNull() ?: def

    fun boolOf(cfg: Map<String, String>, key: String, def: Boolean): Boolean =
        when (cfg[key]) {
            "true" -> true
            "false" -> false
            else -> def
        }

    fun floatOf(cfg: Map<String, String>, key: String, def: Float): Float =
        cfg[key]?.toFloatOrNull() ?: def
}
