package com.jakliuyuy.batterypower

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jakliuyuy.batterypower.battery.BatteryReader
import com.jakliuyuy.batterypower.databinding.ActivityOverlaySettingsBinding
import com.jakliuyuy.batterypower.overlay.OverlayService
import com.jakliuyuy.batterypower.settings.AppContext
import com.jakliuyuy.batterypower.settings.SettingsStore

/**
 * 桌面悬浮窗专属精细配置页
 */
class OverlaySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOverlaySettingsBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false

    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshOverlaySwitchState()
        }

    private val previewRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            updatePreview()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.init(applicationContext)
        binding = ActivityOverlaySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupOverlaySwitch()
        setupButtons()
        setupStyleToggle()
        setupChips()
        setupSizeAndColor()
        updatePreview()
    }

    override fun onResume() {
        super.onResume()
        isMonitoring = true
        handler.post(previewRunnable)
        refreshOverlaySwitchState()
        updateLockBtnText()
    }

    override fun onPause() {
        isMonitoring = false
        handler.removeCallbacks(previewRunnable)
        super.onPause()
    }

    private fun setupOverlaySwitch() {
        refreshOverlaySwitchState()

        binding.swOverlaySnap.isChecked = SettingsStore.overlaySnapEdge
        binding.swOverlaySnap.setOnCheckedChangeListener { _, checked ->
            SettingsStore.overlaySnapEdge = checked
            OverlayService.reload(this)
        }

        binding.swOverlayBoot.isChecked = SettingsStore.bootStartOverlay
        binding.swOverlayBoot.setOnCheckedChangeListener { _, checked ->
            SettingsStore.bootStartOverlay = checked
        }
    }

    private fun setupButtons() {
        binding.btnOverlayLock.setOnClickListener {
            SettingsStore.overlayLocked = !SettingsStore.overlayLocked
            updateLockBtnText()
            Toast.makeText(
                this,
                if (SettingsStore.overlayLocked) "悬浮窗位置已锁定（防误触拖动）" else "悬浮窗位置已解锁，可自由拖拽",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnOverlayReset.setOnClickListener {
            val dm = resources.displayMetrics
            SettingsStore.overlayX = (dm.widthPixels / 2) - dp(75)
            SettingsStore.overlayY = dp(70)
            OverlayService.reload(this)
            Toast.makeText(this, "悬浮窗已复位至顶部居中", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupStyleToggle() {
        when (SettingsStore.overlayStyle) {
            SettingsStore.STYLE_CAPSULE_FROSTED -> binding.toggleStyle.check(R.id.btn_style_frosted)
            SettingsStore.STYLE_CAPSULE_DARK -> binding.toggleStyle.check(R.id.btn_style_dark)
            SettingsStore.STYLE_CAPSULE_LIGHT -> binding.toggleStyle.check(R.id.btn_style_light)
            SettingsStore.STYLE_CAPSULE_OUTLINE -> binding.toggleStyle.check(R.id.btn_style_outline)
            else -> binding.toggleStyle.check(R.id.btn_style_transparent)
        }

        binding.toggleStyle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_style_frosted -> SettingsStore.overlayStyle = SettingsStore.STYLE_CAPSULE_FROSTED
                R.id.btn_style_dark -> SettingsStore.overlayStyle = SettingsStore.STYLE_CAPSULE_DARK
                R.id.btn_style_light -> SettingsStore.overlayStyle = SettingsStore.STYLE_CAPSULE_LIGHT
                R.id.btn_style_outline -> SettingsStore.overlayStyle = SettingsStore.STYLE_CAPSULE_OUTLINE
                R.id.btn_style_transparent -> SettingsStore.overlayStyle = SettingsStore.STYLE_TRANSPARENT
            }
            OverlayService.reload(this)
            updatePreview()
        }
    }

    private fun setupChips() {
        binding.chipPower.isChecked = SettingsStore.showPower
        binding.chipCurrent.isChecked = SettingsStore.showCurrent
        binding.chipVoltage.isChecked = SettingsStore.showVoltage
        binding.chipLevel.isChecked = SettingsStore.showLevel
        binding.chipTemp.isChecked = SettingsStore.showTemp
        binding.chipUnit.isChecked = SettingsStore.showUnit

        binding.chipPower.setOnCheckedChangeListener { _, c -> SettingsStore.showPower = c; notifyOverlay() }
        binding.chipCurrent.setOnCheckedChangeListener { _, c -> SettingsStore.showCurrent = c; notifyOverlay() }
        binding.chipVoltage.setOnCheckedChangeListener { _, c -> SettingsStore.showVoltage = c; notifyOverlay() }
        binding.chipLevel.setOnCheckedChangeListener { _, c -> SettingsStore.showLevel = c; notifyOverlay() }
        binding.chipTemp.setOnCheckedChangeListener { _, c -> SettingsStore.showTemp = c; notifyOverlay() }
        binding.chipUnit.setOnCheckedChangeListener { _, c -> SettingsStore.showUnit = c; notifyOverlay() }
    }

    private fun setupSizeAndColor() {
        binding.sliderTextSize.value = SettingsStore.textSizeSp.coerceIn(8f, 28f)
        binding.tvTextSizeVal.text = "${SettingsStore.textSizeSp.toInt()}sp"
        binding.sliderTextSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                SettingsStore.textSizeSp = value
                binding.tvTextSizeVal.text = "${value.toInt()}sp"
                OverlayService.reload(this)
                updatePreview()
            }
        }

        updateOverlayColorPreview()
        binding.btnOverlayColor.setOnClickListener {
            runCatching {
                ColorPickerDialog.show(this, SettingsStore.textColor) { pickedColor ->
                    SettingsStore.textColor = pickedColor
                    updateOverlayColorPreview()
                    OverlayService.reload(this)
                    updatePreview()
                }
            }
        }
    }

    private fun updatePreview() {
        val sample = BatteryReader.read(this)
        val unit = SettingsStore.showUnit

        val overlayParts = mutableListOf<String>()
        if (SettingsStore.showPower) overlayParts.add(sample.powerText(unit))
        if (SettingsStore.showCurrent) overlayParts.add(sample.currentText(unit))
        if (SettingsStore.showVoltage) overlayParts.add(sample.voltageText(unit))
        if (SettingsStore.showTemp && sample.tempC > 0) overlayParts.add(sample.tempText(unit))
        if (SettingsStore.showLevel) overlayParts.add(sample.levelText(unit))

        binding.tvPreviewOverlay.text = if (overlayParts.isEmpty()) sample.powerText(unit) else overlayParts.joinToString("  ")
        binding.tvPreviewOverlay.textSize = (SettingsStore.textSizeSp * 0.9f).coerceIn(10f, 20f)
        binding.tvPreviewOverlay.setTextColor(SettingsStore.textColor)

        when (SettingsStore.overlayStyle) {
            SettingsStore.STYLE_CAPSULE_DARK -> binding.tvPreviewOverlay.background = ContextCompat.getDrawable(this, R.drawable.bg_capsule_dark)
            SettingsStore.STYLE_CAPSULE_FROSTED -> binding.tvPreviewOverlay.background = ContextCompat.getDrawable(this, R.drawable.bg_capsule_frosted)
            SettingsStore.STYLE_CAPSULE_OUTLINE -> binding.tvPreviewOverlay.background = ContextCompat.getDrawable(this, R.drawable.bg_capsule_outline)
            SettingsStore.STYLE_CAPSULE_LIGHT -> binding.tvPreviewOverlay.background = ContextCompat.getDrawable(this, R.drawable.bg_capsule_light)
            else -> binding.tvPreviewOverlay.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun notifyOverlay() {
        OverlayService.reload(this)
        updatePreview()
    }

    private fun updateLockBtnText() {
        binding.btnOverlayLock.text = if (SettingsStore.overlayLocked) "已锁定 (点击解锁)" else "锁定位置"
    }

    private fun updateOverlayColorPreview() {
        val color = SettingsStore.textColor
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), 0x33000000)
        }
        binding.viewOverlayColorPreview.background = shape
    }

    private fun refreshOverlaySwitchState() {
        binding.swOverlay.setOnCheckedChangeListener(null)
        binding.swOverlay.isChecked = Settings.canDrawOverlays(this) && isServiceRunning()
        binding.swOverlay.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请授予悬浮窗显示权限", Toast.LENGTH_SHORT).show()
                    overlayPermLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                    binding.swOverlay.isChecked = false
                    return@setOnCheckedChangeListener
                }
                requestNotifPermission()
                OverlayService.start(this)
            } else {
                OverlayService.stop(this)
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        return SettingsStore.sp(this).getBoolean("overlay_service_active", false)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
