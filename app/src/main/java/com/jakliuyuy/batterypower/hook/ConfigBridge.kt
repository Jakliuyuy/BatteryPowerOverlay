package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.database.Cursor

/**
 * hook 侧配置桥：SystemUI 进程内通过 ContentProvider 拉取主 app 配置。
 * 查询失败返回空 Map → 调用方使用内置默认值，绝不崩溃。
 */
object ConfigBridge {

    /** 最近一次成功拉取的配置缓存：主 app 进程被杀时兜底，避免回落默认值 */
    @Volatile
    private var cached: Map<String, String>? = null

    /** 下次允许重试 provider 的时间戳（失败节流，避免每秒狂拉起 provider） */
    @Volatile
    private var nextRetryAt = 0L

    fun query(ctx: Context): Map<String, String> {
        val now = System.currentTimeMillis()
        if (now >= nextRetryAt) {
            val fresh = tryQuery(ctx)
            if (fresh.isNotEmpty()) {
                cached = fresh
                nextRetryAt = 0
                return fresh
            }
            // 主 app 被杀或 provider 未就绪：10 秒后再试，期间用缓存
            nextRetryAt = now + 10_000
        }
        return cached ?: emptyMap()
    }

    /** 清除缓存（仅测试用） */
    fun invalidate() {
        cached = null
        nextRetryAt = 0
    }

    private fun tryQuery(ctx: Context): Map<String, String> {
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
