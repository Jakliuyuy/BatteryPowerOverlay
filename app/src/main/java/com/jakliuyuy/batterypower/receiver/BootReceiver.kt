package com.jakliuyuy.batterypower.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.jakliuyuy.batterypower.overlay.OverlayService
import com.jakliuyuy.batterypower.settings.AppContext
import com.jakliuyuy.batterypower.settings.SettingsStore

/**
 * 监听设备开机广播，当用户开启了“开机自启动悬浮窗”且具备悬浮窗权限时，自动拉起 OverlayService。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            AppContext.init(context.applicationContext ?: context)
            if (SettingsStore.bootStartOverlay && Settings.canDrawOverlays(context)) {
                try {
                    OverlayService.start(context)
                } catch (_: Exception) {
                }
            }
        }
    }
}
