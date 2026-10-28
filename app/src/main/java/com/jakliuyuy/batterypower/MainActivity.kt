package com.jakliuyuy.batterypower

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import com.jakliuyuy.batterypower.battery.RootHelper
import com.jakliuyuy.batterypower.databinding.ActivityMainBinding
import com.jakliuyuy.batterypower.overlay.OverlayService
import com.jakliuyuy.batterypower.settings.AppContext
import com.jakliuyuy.batterypower.settings.SettingsStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoringActive = false

    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshOverlaySwitchState()
        }

    private val dashboardRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoringActive) return
            updateDashboard()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.init(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDashboard()
        setupOverlayCard()
        setupHookCard()
        setupAdvancedCard()
    }

    override fun onResume() {
        super.onResume()
        isMonitoringActive = true
        handler.post(dashboardRunnable)
        refreshOverlaySwitchState()
    }

    override fun onPause() {
        isMonitoringActive = false
        handler.removeCallbacks(dashboardRunnable)
        super.onPause()
    }

    // -------------------------------------------------------------
    // 1. 顶部实时电池看板
    // -------------------------------------------------------------
    private fun setupDashboard() {
        updateDashboard()
    }

    private fun updateDashboard() {
        val sample = BatteryReader.read(this)
        val isRoot = sample.source == "root"

        binding.tvDriverSource.text = if (isRoot) "内核直读 (Root)" else "系统接口 (API)"
        binding.tvDriverSource.setTextColor(if (isRoot) 0xFF00E676.toInt() else 0xFFFF9100.toInt())

        binding.tvLivePower.text = sample.powerText()
        binding.tvLivePower.setTextColor(if (sample.isCharging) 0xFF00E676.toInt() else 0xFFFF9100.toInt())

        binding.tvBatteryStatusDesc.text = if (sample.isCharging) "正在充电（充入电量）" else "正在放电（动力消耗）"

        binding.tvLiveCurrent.text = "${sample.currentMa} mA"
        binding.tvLiveVoltage.text = String.format("%.2f V", sample.voltageMv / 1000.0)
        binding.tvLiveLevel.text = "${sample.level} %"
        binding.tvLiveTemp.text = String.format("%.1f °C", sample.tempC)
    }

    // -------------------------------------------------------------
    // 2. 桌面悬浮窗控制
    // -------------------------------------------------------------
    private fun setupOverlayCard() {
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

        // 位置锁定
        updateLockBtnText()
        binding.btnOverlayLock.setOnClickListener {
            SettingsStore.overlayLocked = !SettingsStore.overlayLocked
            updateLockBtnText()
            Toast.makeText(
                this,
                if (SettingsStore.overlayLocked) "悬浮窗位置已锁定" else "悬浮窗位置已解锁，可自由拖动",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 居中重置
        binding.btnOverlayReset.setOnClickListener {
            val dm = resources.displayMetrics
            SettingsStore.overlayX = (dm.widthPixels / 2) - dp(80)
            SettingsStore.overlayY = dp(80)
            OverlayService.reload(this)
            Toast.makeText(this, "悬浮窗已复位至顶部居中", Toast.LENGTH_SHORT).show()
        }

        // 背景风格
        when (SettingsStore.overlayStyle) {
            SettingsStore.STYLE_CAPSULE_FROSTED -> binding.toggleStyle.check(R.id.btn_style_frosted)
            SettingsStore.STYLE_CAPSULE_OUTLINE -> binding.toggleStyle.check(R.id.btn_style_outline)
            SettingsStore.STYLE_TRANSPARENT -> binding.toggleStyle.check(R.id.btn_style_transparent)
            else -> binding.toggleStyle.check(R.id.btn_style_dark)
        }
        binding.toggleStyle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_style_dark -> SettingsStore.overlayStyle = SettingsStore.STYLE_CAPSULE_DARK
                R.id.btn_style_frosted -> SettingsStore.overlayStyle = SettingsStore.STYLE_CAPSULE_FROSTED
                R.id.btn_style_outline -> SettingsStore.overlayStyle = SettingsStore.STYLE_CAPSULE_OUTLINE
                R.id.btn_style_transparent -> SettingsStore.overlayStyle = SettingsStore.STYLE_TRANSPARENT
            }
            OverlayService.reload(this)
        }

        // 显示数据项 Chips
        binding.chipPower.isChecked = SettingsStore.showPower
        binding.chipCurrent.isChecked = SettingsStore.showCurrent
        binding.chipVoltage.isChecked = SettingsStore.showVoltage
        binding.chipLevel.isChecked = SettingsStore.showLevel
        binding.chipTemp.isChecked = SettingsStore.showTemp
        binding.chipUnit.isChecked = SettingsStore.showUnit

        binding.chipPower.setOnCheckedChangeListener { _, b -> SettingsStore.showPower = b; OverlayService.reload(this) }
        binding.chipCurrent.setOnCheckedChangeListener { _, b -> SettingsStore.showCurrent = b; OverlayService.reload(this) }
        binding.chipVoltage.setOnCheckedChangeListener { _, b -> SettingsStore.showVoltage = b; OverlayService.reload(this) }
        binding.chipLevel.setOnCheckedChangeListener { _, b -> SettingsStore.showLevel = b; OverlayService.reload(this) }
        binding.chipTemp.setOnCheckedChangeListener { _, b -> SettingsStore.showTemp = b; OverlayService.reload(this) }
        binding.chipUnit.setOnCheckedChangeListener { _, b -> SettingsStore.showUnit = b; OverlayService.reload(this) }

        // 字号 Slider
        binding.sliderTextSize.value = SettingsStore.textSizeSp.coerceIn(8f, 24f)
        binding.tvTextSizeLabel.text = "字号 ${SettingsStore.textSizeSp.toInt()}sp"
        binding.sliderTextSize.addOnChangeListener { _, value, _ ->
            SettingsStore.textSizeSp = value
            binding.tvTextSizeLabel.text = "字号 ${value.toInt()}sp"
            OverlayService.reload(this)
        }

        // 颜色选择器
        updateOverlayColorPreview()
        binding.btnColorPicker.setOnClickListener {
            ColorPickerDialog.show(this, SettingsStore.textColor) { picked ->
                SettingsStore.textColor = picked
                updateOverlayColorPreview()
                OverlayService.reload(this)
            }
        }

        // 刷新频率 Toggle
        when (SettingsStore.refreshMs) {
            500L -> binding.toggleFreq.check(R.id.btn_freq_500)
            2000L -> binding.toggleFreq.check(R.id.btn_freq_2000)
            else -> binding.toggleFreq.check(R.id.btn_freq_1000)
        }
        binding.toggleFreq.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_freq_500 -> SettingsStore.refreshMs = 500L
                R.id.btn_freq_2000 -> SettingsStore.refreshMs = 2000L
                else -> SettingsStore.refreshMs = 1000L
            }
            OverlayService.reload(this)
        }
    }

    private fun updateLockBtnText() {
        binding.btnOverlayLock.text = if (SettingsStore.overlayLocked) "已锁定 (不可拖动)" else "已解锁 (可自由拖动)"
    }

    private fun updateOverlayColorPreview() {
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SettingsStore.textColor)
            setStroke(dp(1), 0x55FFFFFF)
        }
        binding.viewColorPreview.background = d
    }

    // -------------------------------------------------------------
    // 3. 状态栏 Hook 控制
    // -------------------------------------------------------------
    private fun setupHookCard() {
        binding.swHookEnabled.isChecked = SettingsStore.hookEnabled
        binding.swHookEnabled.setOnCheckedChangeListener { _, b ->
            SettingsStore.hookEnabled = b
        }

        // 锚点选择
        when (SettingsStore.hookSide) {
            SettingsStore.ANCHOR_CLOCK_LEFT -> binding.toggleHookAnchor.check(R.id.btn_anchor_clock_left)
            SettingsStore.ANCHOR_STATUSBAR_RIGHT -> binding.toggleHookAnchor.check(R.id.btn_anchor_sb_right)
            SettingsStore.ANCHOR_STATUSBAR_LEFT -> binding.toggleHookAnchor.check(R.id.btn_anchor_sb_left)
            else -> binding.toggleHookAnchor.check(R.id.btn_anchor_clock_right)
        }
        binding.toggleHookAnchor.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_anchor_clock_left -> SettingsStore.hookSide = SettingsStore.ANCHOR_CLOCK_LEFT
                R.id.btn_anchor_sb_right -> SettingsStore.hookSide = SettingsStore.ANCHOR_STATUSBAR_RIGHT
                R.id.btn_anchor_sb_left -> SettingsStore.hookSide = SettingsStore.ANCHOR_STATUSBAR_LEFT
                else -> SettingsStore.hookSide = SettingsStore.ANCHOR_CLOCK_RIGHT
            }
        }

        // 水平微调 Slider 与步进加减
        syncHookOx(SettingsStore.hookOffsetX)
        binding.sliderHookOx.addOnChangeListener { _, value, _ ->
            SettingsStore.hookOffsetX = value.toInt()
            binding.tvHookOxLabel.text = "水平 ${value.toInt()}px"
        }
        binding.btnHookOxMinus.setOnClickListener {
            val next = (SettingsStore.hookOffsetX - 10).coerceIn(-500, 500)
            syncHookOx(next)
        }
        binding.btnHookOxPlus.setOnClickListener {
            val next = (SettingsStore.hookOffsetX + 10).coerceIn(-500, 500)
            syncHookOx(next)
        }

        // 垂直微调 Slider 与步进加减
        syncHookOy(SettingsStore.hookOffsetY)
        binding.sliderHookOy.addOnChangeListener { _, value, _ ->
            SettingsStore.hookOffsetY = value.toInt()
            binding.tvHookOyLabel.text = "垂直 ${value.toInt()}px"
        }
        binding.btnHookOyMinus.setOnClickListener {
            val next = (SettingsStore.hookOffsetY - 5).coerceIn(-200, 200)
            syncHookOy(next)
        }
        binding.btnHookOyPlus.setOnClickListener {
            val next = (SettingsStore.hookOffsetY + 5).coerceIn(-200, 200)
            syncHookOy(next)
        }

        // 一键复位偏移
        binding.btnHookResetOffset.setOnClickListener {
            syncHookOx(0)
            syncHookOy(0)
            Toast.makeText(this, "状态栏偏移已归零 (0, 0)", Toast.LENGTH_SHORT).show()
        }

        // Hook 显示项 Chips
        binding.chipHookPower.isChecked = SettingsStore.hookShowPower
        binding.chipHookCurrent.isChecked = SettingsStore.hookShowCurrent
        binding.chipHookVoltage.isChecked = SettingsStore.hookShowVoltage
        binding.chipHookLevel.isChecked = SettingsStore.hookShowLevel
        binding.chipHookTemp.isChecked = SettingsStore.hookShowTemp

        binding.chipHookPower.setOnCheckedChangeListener { _, b -> SettingsStore.hookShowPower = b }
        binding.chipHookCurrent.setOnCheckedChangeListener { _, b -> SettingsStore.hookShowCurrent = b }
        binding.chipHookVoltage.setOnCheckedChangeListener { _, b -> SettingsStore.hookShowVoltage = b }
        binding.chipHookLevel.setOnCheckedChangeListener { _, b -> SettingsStore.hookShowLevel = b }
        binding.chipHookTemp.setOnCheckedChangeListener { _, b -> SettingsStore.hookShowTemp = b }

        // 字号 Slider
        binding.sliderHookSize.value = SettingsStore.hookTextSizeSp.coerceIn(8f, 20f)
        binding.tvHookSizeLabel.text = "字号 ${SettingsStore.hookTextSizeSp.toInt()}sp"
        binding.sliderHookSize.addOnChangeListener { _, value, _ ->
            SettingsStore.hookTextSizeSp = value
            binding.tvHookSizeLabel.text = "字号 ${value.toInt()}sp"
        }

        // 自适应颜色 CheckBox 与调色按钮
        binding.cbHookAdaptColor.isChecked = SettingsStore.hookAdaptColor
        binding.cbHookAdaptColor.setOnCheckedChangeListener { _, b ->
            SettingsStore.hookAdaptColor = b
        }
        binding.btnHookColorPicker.setOnClickListener {
            ColorPickerDialog.show(this, SettingsStore.hookTextColor) { picked ->
                SettingsStore.hookTextColor = picked
                SettingsStore.hookAdaptColor = false
                binding.cbHookAdaptColor.isChecked = false
            }
        }

        // 重启 SystemUI
        binding.btnRestartSui.setOnClickListener {
            Toast.makeText(this, "正在请求 Root 重启 SystemUI...", Toast.LENGTH_SHORT).show()
            kotlin.concurrent.thread {
                val ok = RootHelper.exec("pkill -f com.android.systemui") != null
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) "SystemUI 已重启，Hook 模块已重新加载" else "重启失败，请检查是否授予 Root 权限",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun syncHookOx(value: Int) {
        val clamped = value.coerceIn(-500, 500)
        SettingsStore.hookOffsetX = clamped
        binding.sliderHookOx.value = clamped.toFloat()
        binding.tvHookOxLabel.text = "水平 ${clamped}px"
    }

    private fun syncHookOy(value: Int) {
        val clamped = value.coerceIn(-200, 200)
        SettingsStore.hookOffsetY = clamped
        binding.sliderHookOy.value = clamped.toFloat()
        binding.tvHookOyLabel.text = "垂直 ${clamped}px"
    }

    // -------------------------------------------------------------
    // 4. 高级与系统设置
    // -------------------------------------------------------------
    private fun setupAdvancedCard() {
        binding.swBootStart.isChecked = SettingsStore.bootStartOverlay
        binding.swBootStart.setOnCheckedChangeListener { _, b ->
            SettingsStore.bootStartOverlay = b
        }

        binding.swDoubleCell.isChecked = SettingsStore.doubleCellFix
        binding.swDoubleCell.setOnCheckedChangeListener { _, b ->
            SettingsStore.doubleCellFix = b
            updateDashboard()
            OverlayService.reload(this)
        }

        binding.swPolarityInvert.isChecked = SettingsStore.polarityInvert
        binding.swPolarityInvert.setOnCheckedChangeListener { _, b ->
            SettingsStore.polarityInvert = b
            updateDashboard()
            OverlayService.reload(this)
        }

        binding.btnDiagnostic.setOnClickListener {
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }
    }

    private fun refreshOverlaySwitchState() {
        binding.swOverlay.isChecked =
            Settings.canDrawOverlays(this) &&
                    ContextCompat.getSystemService(this, android.app.NotificationManager::class.java) != null
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
