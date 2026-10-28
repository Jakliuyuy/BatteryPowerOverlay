package com.jakliuyuy.batterypower.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jakliuyuy.batterypower.MainActivity
import com.jakliuyuy.batterypower.R

/**
 * 悬浮窗前台服务：承载 BatteryOverlayView，保证后台平稳运行不被系统回收。
 */
class OverlayService : Service() {

    private var overlay: BatteryOverlayView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_RELOAD) {
            overlay?.reloadConfig()
        } else {
            if (overlay == null) {
                val v = BatteryOverlayView(this)
                v.attach()
                overlay = v
            } else {
                overlay?.reloadConfig()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlay?.detach()
        overlay = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "电池悬浮窗后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保证电池功率悬浮窗在后台持续运行"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle("电池功率悬浮窗运行中")
            .setContentText("点击打开配置面板")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "battery_overlay_channel"
        const val ACTION_RELOAD = "com.jakliuyuy.batterypower.ACTION_RELOAD_OVERLAY"

        fun start(ctx: Context) {
            val intent = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun reload(ctx: Context) {
            val intent = Intent(ctx, OverlayService::class.java).apply {
                action = ACTION_RELOAD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
