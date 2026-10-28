package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jakliuyuy.batterypower.config.ConfigProvider

/**
 * Hook 侧配置桥：SystemUI 进程内拉取配置与持久化镜像管理。
 *
 * 核心升级：
 * 1. 采用 SystemUI 的 Device Protected Storage 作为本地无锁缓存（CACHE_SP），
 *    保证手机刚开机（Direct Boot 阶段 / 用户未解锁）即可 100% 恢复上次记忆位置与样式。
 * 2. 写入缓存时强制 commit() 立即刷盘。
 * 3. 注册 ContentObserver 监听主应用配置变更，毫秒级即时生效，无需单纯依赖 1s 轮询。
 */
object ConfigBridge {

    private const val CACHE_SP = "battery_power_hook_cache"

    @Volatile
    private var memoryCache: Map<String, String>? = null

    @Volatile
    private var nextRetryAt = 0L

    private var isObserverRegistered = false

    private fun getDeviceProtectedContext(ctx: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ctx.createDeviceProtectedStorageContext()
        } else {
            ctx
        }
    }

    /** 注册 ContentObserver 监听主 App 配置变动，实现零延迟热重载 */
    fun ensureObserver(ctx: Context, onConfigUpdate: (Map<String, String>) -> Unit) {
        if (isObserverRegistered) return
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    val fresh = tryQuery(ctx)
                    if (fresh.isNotEmpty()) {
                        memoryCache = fresh
                        saveToDisk(ctx, fresh)
                        onConfigUpdate(fresh)
                    }
                }
            }
            ctx.contentResolver.registerContentObserver(ConfigProvider.CONTENT_URI, true, observer)
            isObserverRegistered = true
        } catch (_: Exception) {
        }
    }

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
            // provider 暂时不可用（主 app 未启动或手机刚开机）：5 秒节流重试
            nextRetryAt = now + 5_000
        }
        // 兜底顺序：内存缓存 -> 本地磁盘缓存（开机重启即刻可读） -> 默认空表
        memoryCache?.let { return it }
        val disk = loadFromDisk(ctx)
        if (disk != null) {
            memoryCache = disk
            return disk
        }
        return emptyMap()
    }

    private fun tryQuery(ctx: Context): Map<String, String> {
        return try {
            val cursor = ctx.contentResolver.query(
                ConfigProvider.CONTENT_URI,
                null, null, null, null
            ) ?: return emptyMap()
            cursor.use { c ->
                val nonNullCursor = c ?: return emptyMap()
                if (!nonNullCursor.moveToFirst()) return emptyMap()
                val kIdx = nonNullCursor.getColumnIndexOrThrow("key")
                val vIdx = nonNullCursor.getColumnIndexOrThrow("value")
                val map = HashMap<String, String>()
                do {
                    map[nonNullCursor.getString(kIdx)] = nonNullCursor.getString(vIdx)
                } while (nonNullCursor.moveToNext())
                map
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 写入 SystemUI 进程自己的 SharedPreferences（使用 commit 同步落盘，跨重启永久记忆） */
    private fun saveToDisk(ctx: Context, cfg: Map<String, String>) {
        try {
            val dpCtx = getDeviceProtectedContext(ctx)
            val sp = dpCtx.getSharedPreferences(CACHE_SP, Context.MODE_PRIVATE)
            val editor = sp.edit()
            for ((k, v) in cfg) {
                editor.putString(k, v)
            }
            editor.commit()
        } catch (_: Exception) {
        }
    }

    private fun loadFromDisk(ctx: Context): Map<String, String>? {
        return try {
            val dpCtx = getDeviceProtectedContext(ctx)
            val sp = dpCtx.getSharedPreferences(CACHE_SP, Context.MODE_PRIVATE)
            val map = HashMap<String, String>()
            for (k in ConfigProvider.ALL_KEYS) {
                val v = sp.getString(k, null)
                if (v != null) {
                    map[k] = v
                }
            }
            if (map.isEmpty()) null else map
        } catch (_: Exception) {
            null
        }
    }

    // ---- 便捷读取工具 ----

    fun intOf(cfg: Map<String, String>, key: String, def: Int): Int =
        cfg[key]?.toIntOrNull() ?: def

    fun longOf(cfg: Map<String, String>, key: String, def: Long): Long =
        cfg[key]?.toLongOrNull() ?: def

    fun floatOf(cfg: Map<String, String>, key: String, def: Float): Float =
        cfg[key]?.toFloatOrNull() ?: def

    fun boolOf(cfg: Map<String, String>, key: String, def: Boolean): Boolean =
        when (cfg[key]) {
            "true" -> true
            "false" -> false
            else -> def
        }
}
