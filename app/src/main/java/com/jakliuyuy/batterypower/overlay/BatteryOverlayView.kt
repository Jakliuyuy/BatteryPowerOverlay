package com.jakliuyuy.batterypower.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jakliuyuy.batterypower.MainActivity
import com.jakliuyuy.batterypower.R
import com.jakliuyuy.batterypower.battery.BatteryReader
import com.jakliuyuy.batterypower.settings.SettingsStore
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 桌面悬浮窗核心视图：
 * 1. 样式丰富：极简透明、磨砂黑胶囊、半透毛玻璃、细线描边。
 * 2. 屏幕边界安全保护（Clamping），防止拖出屏幕外失踪。
 * 3. 拖动手势结束时 commit() 同步存储 X/Y 坐标，永久记忆不丢失。
 * 4. 点击打开 App，支持长按或锁定位置。
 */
@SuppressLint("ViewConstructor")
class BatteryOverlayView(context: Context) : TextView(context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastX = 0
    private var lastY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    private var attached = false
    private var running = false

    private val screenMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(it)
    }

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
        } else {
            // 默认居中靠顶部
            x = (screenMetrics.widthPixels / 2) - dp(80)
            y = dp(60)
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            updateContent()
            handler.postDelayed(this, SettingsStore.refreshMs.coerceAtLeast(300L))
        }
    }

    init {
        includeFontPadding = false
        applyStyle()
        setOnTouchListener(::onTouch)
        updateContent()
    }

    fun applyStyle() {
        textSize = SettingsStore.textSizeSp
        setTextColor(SettingsStore.textColor)

        when (SettingsStore.overlayStyle) {
            SettingsStore.STYLE_CAPSULE_DARK -> {
                background = ContextCompat.getDrawable(context, R.drawable.bg_capsule_dark)
            }
            SettingsStore.STYLE_CAPSULE_FROSTED -> {
                background = ContextCompat.getDrawable(context, R.drawable.bg_capsule_frosted)
            }
            SettingsStore.STYLE_CAPSULE_OUTLINE -> {
                background = ContextCompat.getDrawable(context, R.drawable.bg_capsule_outline)
            }
            else -> {
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(4), dp(1), dp(4), dp(1))
            }
        }
    }

    fun attach() {
        if (attached) return
        attached = true
        running = true
        try {
            wm.addView(this, params)
            handler.post(refreshRunnable)
        } catch (_: Exception) {
            attached = false
        }
    }

    fun detach() {
        running = false
        handler.removeCallbacks(refreshRunnable)
        if (attached) {
            try {
                wm.removeView(this)
            } catch (_: Exception) {
            }
            attached = false
        }
    }

    fun reloadConfig() {
        applyStyle()
        updateContent()
    }

    private fun updateContent() {
        val s = BatteryReader.read(context)
        val unit = SettingsStore.showUnit
        val parts = mutableListOf<String>()

        if (SettingsStore.showPower) {
            parts.add(if (unit) s.powerText() else s.powerText().removeSuffix("W"))
        }
        if (SettingsStore.showCurrent) {
            val t = formatCurrent(s.currentMa)
            parts.add(if (unit) t else t.filter { it.isDigit() || it == '-' || it == '.' })
        }
        if (SettingsStore.showVoltage) {
            val t = String.format("%.2fV", s.voltageMv / 1000.0)
            parts.add(if (unit) t else String.format("%.2f", s.voltageMv / 1000.0))
        }
        if (SettingsStore.showTemp && s.tempC > 0) {
            val t = String.format("%.1f°C", s.tempC)
            parts.add(if (unit) t else String.format("%.1f", s.tempC))
        }
        if (SettingsStore.showLevel) {
            parts.add(if (unit) "${s.level}%" else "${s.level}")
        }

        text = if (parts.isEmpty()) s.powerText() else parts.joinToString("  ")
        setTextColor(SettingsStore.textColor)
    }

    private fun formatCurrent(ma: Int): String {
        val absVal = abs(ma)
        return if (absVal >= 1000) String.format("%.2fA", ma / 1000.0) else "${ma}mA"
    }

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        if (SettingsStore.overlayLocked) {
            // 锁定状态下，点击依然可以打开主界面
            if (e.action == MotionEvent.ACTION_UP) {
                openMainActivity()
            }
            return true
        }

        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                lastX = params.x
                lastY = params.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (abs(dx) > 10 || abs(dy) > 10) {
                    dragging = true
                }
                if (dragging) {
                    var newX = lastX + dx.roundToInt()
                    var newY = lastY + dy.roundToInt()

                    // 屏幕越界安全保护
                    val maxX = screenMetrics.widthPixels - width
                    val maxY = screenMetrics.heightPixels - height
                    newX = newX.coerceIn(0, maxX.coerceAtLeast(0))
                    newY = newY.coerceIn(0, maxY.coerceAtLeast(0))

                    params.x = newX
                    params.y = newY
                    try {
                        wm.updateViewLayout(this, params)
                    } catch (_: Exception) {
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    // 贴边吸附处理
                    if (SettingsStore.overlaySnapEdge) {
                        val midX = screenMetrics.widthPixels / 2
                        params.x = if (params.x + width / 2 < midX) dp(4) else screenMetrics.widthPixels - width - dp(4)
                        try {
                            wm.updateViewLayout(this, params)
                        } catch (_: Exception) {}
                    }
                    // 同步刷盘，重启永久记忆
                    SettingsStore.overlayX = params.x
                    SettingsStore.overlayY = params.y
                } else {
                    // 轻触触发点击打开主界面
                    val clickDist = abs(e.rawX - downRawX) + abs(e.rawY - downRawY)
                    if (clickDist < 15) {
                        openMainActivity()
                    }
                }
                return true
            }
        }
        return false
    }

    private fun openMainActivity() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
