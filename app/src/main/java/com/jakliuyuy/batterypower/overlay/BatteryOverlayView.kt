package com.jakliuyuy.batterypower.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.jakliuyuy.batterypower.battery.BatteryReader
import com.jakliuyuy.batterypower.settings.SettingsStore
import kotlin.math.roundToInt

/**
 * 悬浮窗视图：显示功率/电流/电压/电量，可拖动，位置记忆。
 */
@SuppressLint("ViewConstructor")
class BatteryOverlayView(context: Context) : TextView(context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastX = 0
    private var lastY = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var attached = false
    private var running = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (SettingsStore.overlayX >= 0 && SettingsStore.overlayY >= 0) {
            x = SettingsStore.overlayX
            y = SettingsStore.overlayY
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!running) return
            update()
            handler.postDelayed(this, SettingsStore.refreshMs)
        }
    }

    init {
        textSize = SettingsStore.textSizeSp
        setTextColor(SettingsStore.textColor)
        // 全透明：无背景、无边框，Scene 迷你监视器风格
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setPadding(dp(4), dp(1), dp(4), dp(1))
        setOnTouchListener(::onTouch)
        update()
    }

    fun attach() {
        if (attached) return
        attached = true
        running = true
        try {
            wm.addView(this, params)
            handler.postDelayed(refresh, SettingsStore.refreshMs)
        } catch (e: Exception) {
            attached = false
        }
    }

    fun detach() {
        running = false
        handler.removeCallbacks(refresh)
        if (attached) {
            try {
                wm.removeView(this)
            } catch (_: Exception) {
            }
            attached = false
        }
    }

    private fun update() {
        val s = BatteryReader.read(context)
        val parts = mutableListOf<String>()
        if (SettingsStore.showPower) parts.add(s.powerText())
        if (SettingsStore.showCurrent) parts.add(formatCurrent(s.currentMa))
        if (SettingsStore.showVoltage) parts.add(String.format("%.2fV", s.voltageMv / 1000.0))
        if (SettingsStore.showTemp && s.tempC > 0) parts.add(String.format("%.1f°C", s.tempC))
        if (SettingsStore.showLevel) parts.add("${s.level}%")
        text = if (parts.isEmpty()) s.powerText() else parts.joinToString(" ")
        setTextColor(SettingsStore.textColor)
    }

    private fun formatCurrent(ma: Int): String {
        val abs = kotlin.math.abs(ma)
        return if (abs >= 1000) String.format("%.2fA", ma / 1000.0) else "${ma}mA"
    }

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                lastX = params.x
                lastY = params.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) dragging = true
                if (dragging) {
                    params.x = lastX + dx.roundToInt()
                    params.y = lastY + dy.roundToInt()
                    try {
                        wm.updateViewLayout(this, params)
                    } catch (_: Exception) {
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    SettingsStore.overlayX = params.x
                    SettingsStore.overlayY = params.y
                }
                return true
            }
        }
        return false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
