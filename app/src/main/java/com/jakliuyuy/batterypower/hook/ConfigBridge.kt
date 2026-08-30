package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.database.Cursor

/**
 * hook 侧配置桥：SystemUI 进程内通过 ContentProvider 拉取主 app 配置。
 *
 * 可靠性设计（主 app 进程被杀 / SystemUI 重启都不丢配置）：
 * 1. 实时通道：ContentProvider query（app 存活时每 1s 拉取）
 * 2. 持久化缓存：写入 SystemUI 自己的 SharedPreferences（文件在 /data/user_de/0/com.android.systemui/ 下，
 *    SystemUI 重启后依然可读），query 失败时兜底
 * 3. 内存缓存：本次进程内免读盘
 */
object ConfigBridge {

    private const val CACHE_SP = "battery_power_hook_cache"

    @Volatile
    private var memoryCache: Map<String, String>? = null

    @Volatile
    private var nextRetryAt = 0L

    fun query(ctx: Context): Map<String, String> {
        val now = System.currentTimeMillis()
        if (now >= nextRetryAt) {
            val fresh = tryQuery(ctx)
            if (fresh.isNotEmpty()) {
                memoryCache = fresh
                saveToDisk(ctx, fresh)
                nextRetryAt = 0
                return fresh
            }
            // provider 不可用（主 app 被杀）：10 秒节流重试
            nextRetryAt = now + 10_000
        }
        // 兜底顺序：内存缓存 → 磁盘缓存（SystemUI 重启后仍有）→ 默认值
        memoryCache?.let { return it }
        return loadFromDisk(ctx) ?: emptyMap()
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

    /** 写入 SystemUI 进程自己的 SharedPreferences（跨重启持久化） */
    private fun saveToDisk(ctx: Context, cfg: Map<String, String>) {
        try {
            val sp = ctx.getSharedPreferences(CACHE_SP, Context.MODE_PRIVATE)
            sp.edit().apply {
                for ((k, v) in cfg) putString(k, v)
                apply()
            }
        } catch (_: Exception) {
        }
    }

    private fun loadFromDisk(ctx: Context): Map<String, String>? {
        return try {
            val sp = ctx.getSharedPreferences(CACHE_SP, Context.MODE_PRIVATE)
            val map = HashMap<String, String>()
            for (k in com.jakliuyuy.batterypower.config.ConfigProvider.ALL_KEYS) {
                sp.getString(k, null)?.let { map[k] = it }
            }
            if (map.isEmpty()) null else map
        } catch (e: Exception) {
            null
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
