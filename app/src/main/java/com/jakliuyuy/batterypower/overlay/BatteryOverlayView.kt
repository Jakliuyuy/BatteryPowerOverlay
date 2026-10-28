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
 * 1. 现代化微胶囊设计：全透极简、深灰雅致、毛玻璃、微光描边、纯白清爽 5 种质感外观。
 * 2. 屏幕安全边界约束（Anti-Overscroll Clamping），绝对不会拖出屏幕外失联。
 * 3. 拖动停止即刻 commit() 同步落盘，手机重启后坐标永久精确记忆。
 * 4. 贴边智能吸附，轻击秒回主配置面板。
 */
@SuppressLint("ViewConstructor")
class BatteryOverlayView(context: Context) : TextView(context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastX = 0
    private var lastY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var isDragging = false
    private var isAttached = false
    private var isRunning = false

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
        val savedX = SettingsStore.overlayX
        val savedY = SettingsStore.overlayY
        if (savedX >= 0 && savedY >= 0) {
            x = savedX
            y = savedY
        } else {
            // 默认水平居中偏顶
            x = (screenMetrics.widthPixels / 2) - dp(75)
            y = dp(70)
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            updateContent()
            val ms = SettingsStore.refreshMs.coerceAtLeast(300L)
            handler.postDelayed(this, ms)
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
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            SettingsStore.STYLE_CAPSULE_FROSTED -> {
                background = ContextCompat.getDrawable(context, R.drawable.bg_capsule_frosted)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            SettingsStore.STYLE_CAPSULE_OUTLINE -> {
                background = ContextCompat.getDrawable(context, R.drawable.bg_capsule_outline)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            SettingsStore.STYLE_CAPSULE_LIGHT -> {
                background = ContextCompat.getDrawable(context, R.drawable.bg_capsule_light)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            else -> {
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
        }
    }

    fun attach() {
        if (isAttached) return
        isAttached = true
        isRunning = true
        try {
            wm.addView(this, params)
            handler.post(refreshRunnable)
        } catch (_: Exception) {
            isAttached = false
        }
    }

    fun detach() {
        isRunning = false
        handler.removeCallbacks(refreshRunnable)
        if (isAttached) {
            try {
                wm.removeView(this)
            } catch (_: Exception) {
            }
            isAttached = false
        }
    }

    fun reloadConfig() {
        applyStyle()
        updateContent()

        // 1. 同步最新存储的坐标（如外部点击了复位或修改了位置）
        val savedX = SettingsStore.overlayX
        val savedY = SettingsStore.overlayY
        if (savedX >= 0 && savedY >= 0) {
            params.x = savedX
            params.y = savedY
        }
        if (isAttached) {
            try {
                wm.updateViewLayout(this, params)
            } catch (_: Exception) {
            }
        }

        // 2. 立即重置刷新定时器，使最新的全局刷新频率毫秒级生效
        if (isRunning) {
            handler.removeCallbacks(refreshRunnable)
            handler.post(refreshRunnable)
        }
    }

    private fun updateContent() {
        val sample = BatteryReader.read(context)
        val unit = SettingsStore.showUnit
        val parts = mutableListOf<String>()

        if (SettingsStore.showPower) {
            parts.add(sample.powerText(unit))
        }
        if (SettingsStore.showCurrent) {
            parts.add(sample.currentText(unit))
        }
        if (SettingsStore.showVoltage) {
            parts.add(sample.voltageText(unit))
        }
        if (SettingsStore.showTemp && sample.tempC > 0) {
            parts.add(sample.tempText(unit))
        }
        if (SettingsStore.showLevel) {
            parts.add(sample.levelText(unit))
        }

        text = if (parts.isEmpty()) sample.powerText(unit) else parts.joinToString("  ")
        setTextColor(SettingsStore.textColor)
    }

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        if (SettingsStore.overlayLocked) {
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
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (abs(dx) > 10 || abs(dy) > 10) {
                    isDragging = true
                }
                if (isDragging) {
                    var newX = lastX + dx.roundToInt()
                    var newY = lastY + dy.roundToInt()

                    val maxX = screenMetrics.widthPixels - width
                    val maxY = screenMetrics.heightPixels - height
                    newX = newX.coerceIn(0, maxX.coerceAtLeast(0))
                    newY = newY.coerceIn(0, maxY.coerceAtLeast(0))

                    params.x = newX
                    params.y = newY
                    try {
                        wm.updateViewLayout(this, params)
                    } catch (_: Exception) {}
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    if (SettingsStore.overlaySnapEdge) {
                        val midX = screenMetrics.widthPixels / 2
                        params.x = if (params.x + width / 2 < midX) dp(6) else screenMetrics.widthPixels - width - dp(6)
                        try {
                            wm.updateViewLayout(this, params)
                        } catch (_: Exception) {}
                    }
                    // 坐标立即 commit 同步落盘，永久记忆
                    SettingsStore.overlayX = params.x
                    SettingsStore.overlayY = params.y
                } else {
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
        } catch (_: Exception) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
