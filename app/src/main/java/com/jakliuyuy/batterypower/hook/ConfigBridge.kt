package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jakliuyuy.batterypower.config.ConfigProvider

/**
 * SystemUI 进程配置桥接器：
 * 1. 采用 SystemUI 自身的 Device Protected Storage (无锁设备保护存储) 作为本地镜像缓存，
 *    确保手机重启开机（Direct Boot / 用户未输入锁屏密码前）100% 能够读取到上次的记忆配置。
 * 2. 写入配置时强制 commit() 同步落盘，永久记忆不丢失。
 * 3. 注册 ContentObserver 实时监听，主应用调节参数毫秒级即时生效。
 */
object ConfigBridge {

    private const val CACHE_SP = "battery_power_systemui_cache"

    @Volatile
    private var memoryCache: Map<String, String>? = null

    @Volatile
    private var nextRetryAt = 0L

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Map<String, String>) -> Unit>()
    private var isObserverRegistered = false

    private fun getDeviceProtectedContext(ctx: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ctx.createDeviceProtectedStorageContext()
        } else {
            ctx
        }
    }

    /** 注册 ContentObserver 监听配置变动，支持生命周期重绑与多组件监听 */
    fun addObserver(ctx: Context, onConfigUpdate: (Map<String, String>) -> Unit) {
        if (!listeners.contains(onConfigUpdate)) {
            listeners.add(onConfigUpdate)
        }
        ensureContentObserver(ctx)
    }

    fun removeObserver(onConfigUpdate: (Map<String, String>) -> Unit) {
        listeners.remove(onConfigUpdate)
    }

    /** 兼容接口：确保 ContentObserver 已挂载并注册监听 */
    fun ensureObserver(ctx: Context, onConfigUpdate: (Map<String, String>) -> Unit) {
        addObserver(ctx, onConfigUpdate)
    }

    private fun ensureContentObserver(ctx: Context) {
        if (isObserverRegistered) return
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    val fresh = tryQuery(ctx)
                    if (fresh.isNotEmpty()) {
                        memoryCache = fresh
                        saveToDisk(ctx, fresh)
                        for (listener in listeners) {
                            runCatching { listener(fresh) }
                        }
                    }
                }
            }
            ctx.contentResolver.registerContentObserver(ConfigProvider.CONTENT_URI, true, observer)
            isObserverRegistered = true
        } catch (_: Exception) {
        }
    }

    fun query(ctx: Context): Map<String, String> {
        // 1. 若内存缓存已就绪，直接返回，杜绝高频轮询引发的跨进程 IPC 与磁盘 commit
        memoryCache?.let { return it }

        // 2. 首次启动或缓存尚未就绪，尝试从主应用 ContentProvider 读取
        val now = System.currentTimeMillis()
        if (now >= nextRetryAt) {
            val fresh = tryQuery(ctx)
            if (fresh.isNotEmpty()) {
                memoryCache = fresh
                saveToDisk(ctx, fresh)
                nextRetryAt = 0
                return fresh
            }
            // 节流，避免在开机未准备好时持续疯狂查询
            nextRetryAt = now + 4000
        }

        // 3. 降级读取 SystemUI 本地 Direct Boot 磁盘镜像缓存
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
                if (!c.moveToFirst()) return emptyMap()
                val kIdx = c.getColumnIndexOrThrow("key")
                val vIdx = c.getColumnIndexOrThrow("value")
                val map = HashMap<String, String>()
                do {
                    map[c.getString(kIdx)] = c.getString(vIdx)
                } while (c.moveToNext())
                map
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

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
