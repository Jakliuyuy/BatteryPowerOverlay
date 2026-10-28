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
import com.jakliuyuy.batterypower.battery.RootHelper
import com.jakliuyuy.batterypower.databinding.ActivityMainBinding
import com.jakliuyuy.batterypower.overlay.OverlayService
import com.jakliuyuy.batterypower.settings.AppContext
import com.jakliuyuy.batterypower.settings.SettingsStore
import kotlin.math.abs

/**
 * 现代化 Material 3 主控制台：
 * 1. 实时电池动力看板 + 所见即所得模拟视窗。
 * 2. 桌面悬浮窗控制（5 款质感外观、屏幕防溢出约束、贴边吸附与位置锁定）。
 * 3. 状态栏 Hook 控制（原生兄弟内嵌防漂移、硬件位移微调步进器、状态栏颜色自适应、SystemUI 快速重启）。
 * 4. 内核与底层硬件适配（极性翻转、双电芯修正、底层节点诊断）。
 */
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
            refreshRealtimeData()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.init(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTopBar()
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
        updateLockBtnText()
    }

    override fun onPause() {
        isMonitoringActive = false
        handler.removeCallbacks(dashboardRunnable)
        super.onPause()
    }

    private fun setupTopBar() {
        binding.btnOpenDiag.setOnClickListener {
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }
    }

    // -------------------------------------------------------------
    // 1. 顶部电池看板与实时视窗
    // -------------------------------------------------------------
    private fun setupDashboard() {
        val sample = BatteryReader.read(this)
        updateDashboard(sample)
        updatePreview(sample)
    }

    private fun refreshRealtimeData() {
        val sample = BatteryReader.read(this)
        updateDashboard(sample)
        updatePreview(sample)
    }

    private fun updateDashboard(sample: BatteryReader.Sample) {
        val isRoot = sample.source == "root"

        binding.tvDriverSource.text = if (isRoot) "内核直读 (Root)" else "系统接口 (API)"
        val brandColor = ContextCompat.getColor(this, R.color.primary)
        val orangeColor = ContextCompat.getColor(this, R.color.discharging_orange)
        val greenColor = ContextCompat.getColor(this, R.color.charging_green)

        binding.tvDriverSource.setTextColor(if (isRoot) brandColor else orangeColor)

        binding.tvLivePower.text = sample.powerText(true)
        binding.tvLivePower.setTextColor(if (sample.isCharging) greenColor else orangeColor)

        binding.tvBatteryStatusDesc.text = if (sample.isCharging) "⚡ 正在充电（电量充入中）" else "🔋 正在放电（动力消耗中）"

        binding.tvLiveCurrent.text = sample.currentText(true)
        binding.tvLiveVoltage.text = sample.voltageText(true)
        binding.tvLiveLevel.text = sample.levelText(true)
        binding.tvLiveTemp.text = sample.tempText(true)
    }

    private fun updatePreview(sample: BatteryReader.Sample = BatteryReader.read(this)) {
        val unit = SettingsStore.showUnit

        // 1. 悬浮窗微缩预览
        val overlayParts = mutableListOf<String>()
        if (SettingsStore.showPower) overlayParts.add(sample.powerText(unit))
        if (SettingsStore.showCurrent) overlayParts.add(sample.currentText(unit))
        if (SettingsStore.showVoltage) overlayParts.add(sample.voltageText(unit))
        if (SettingsStore.showTemp && sample.tempC > 0) overlayParts.add(sample.tempText(unit))
        if (SettingsStore.showLevel) overlayParts.add(sample.levelText(unit))

        binding.tvPreviewOverlay.text = if (overlayParts.isEmpty()) sample.powerText(unit) else overlayParts.joinToString("  ")
        binding.tvPreviewOverlay.textSize = (SettingsStore.textSizeSp * 0.9f).coerceIn(10f, 18f)
        binding.tvPreviewOverlay.setTextColor(SettingsStore.textColor)

        when (SettingsStore.overlayStyle) {
            SettingsStore.STYLE_CAPSULE_DARK -> binding.tvPreviewOverlay.background = ContextCompat.getDrawable(this, R.drawable.bg_capsule_dark)
            SettingsStore.STYLE_CAPSULE_FROSTED -> binding.tvPreviewOverlay.background = ContextCompat.getDrawable(this, R.drawable.bg_capsule_frosted)
            SettingsStore.STYLE_CAPSULE_OUTLINE -> binding.tvPreviewOverlay.background = ContextCompat.getDrawable(this, R.drawable.bg_capsule_outline)
            SettingsStore.STYLE_CAPSULE_LIGHT -> binding.tvPreviewOverlay.background = ContextCompat.getDrawable(this, R.drawable.bg_capsule_light)
            else -> {
                binding.tvPreviewOverlay.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        // 2. 状态栏时钟与内嵌文本预览
        if (!SettingsStore.hookEnabled) {
            binding.tvPreviewClockLeft.visibility = View.GONE
            binding.tvPreviewClockRight.visibility = View.GONE
        } else {
            val hookUnit = SettingsStore.hookShowUnit
            val hookParts = mutableListOf<String>()
            if (SettingsStore.hookShowPower) hookParts.add(sample.powerText(hookUnit))
            if (SettingsStore.hookShowCurrent) hookParts.add(sample.currentText(hookUnit))
            if (SettingsStore.hookShowVoltage) hookParts.add(sample.voltageText(hookUnit))
            if (SettingsStore.hookShowTemp && sample.tempC > 0) hookParts.add(sample.tempText(hookUnit))
            if (SettingsStore.hookShowLevel) hookParts.add(sample.levelText(hookUnit))

            val hookText = if (hookParts.isEmpty()) sample.powerText(hookUnit) else hookParts.joinToString(" ")
            val isLeft = SettingsStore.hookSide == 1

            val targetTv = if (isLeft) binding.tvPreviewClockLeft else binding.tvPreviewClockRight
            val hiddenTv = if (isLeft) binding.tvPreviewClockRight else binding.tvPreviewClockLeft

            hiddenTv.visibility = View.GONE
            targetTv.visibility = View.VISIBLE
            targetTv.text = hookText
            targetTv.textSize = SettingsStore.hookTextSizeSp.coerceIn(9f, 16f)

            if (SettingsStore.hookAdaptColor) {
                targetTv.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            } else {
                targetTv.setTextColor(SettingsStore.hookTextColor)
            }

            targetTv.translationX = (SettingsStore.hookOffsetX / 4f).coerceIn(-20f, 20f)
            targetTv.translationY = (SettingsStore.hookOffsetY / 4f).coerceIn(-10f, 10f)
        }
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

        binding.swOverlaySnap.isChecked = SettingsStore.overlaySnapEdge
        binding.swOverlaySnap.setOnCheckedChangeListener { _, checked ->
            SettingsStore.overlaySnapEdge = checked
            OverlayService.reload(this)
        }

        binding.swOverlayBoot.isChecked = SettingsStore.bootStartOverlay
        binding.swOverlayBoot.setOnCheckedChangeListener { _, checked ->
            SettingsStore.bootStartOverlay = checked
        }

        // 外观胶囊风格单选
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

        // 显示项 Chips
        setupOverlayChips()

        // 字号滑块
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

        // 文字颜色预览与调色盘
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

        // 刷新率
        when (SettingsStore.refreshMs) {
            500L -> binding.toggleRefreshMs.check(R.id.btn_refresh_500)
            2000L -> binding.toggleRefreshMs.check(R.id.btn_refresh_2000)
            else -> binding.toggleRefreshMs.check(R.id.btn_refresh_1000)
        }
        binding.toggleRefreshMs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_refresh_500 -> SettingsStore.refreshMs = 500L
                R.id.btn_refresh_1000 -> SettingsStore.refreshMs = 1000L
                R.id.btn_refresh_2000 -> SettingsStore.refreshMs = 2000L
            }
            OverlayService.reload(this)
        }
    }

    private fun setupOverlayChips() {
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

    // -------------------------------------------------------------
    // 3. 状态栏 Hook 控制 (LSPosed 模块)
    // -------------------------------------------------------------
    private fun setupHookCard() {
        binding.swHook.isChecked = SettingsStore.hookEnabled
        binding.swHook.setOnCheckedChangeListener { _, checked ->
            SettingsStore.hookEnabled = checked
            updatePreview()
        }

        // 图层模式选择（二选一：覆盖图层 vs 并排图层）
        if (SettingsStore.hookLayerMode == SettingsStore.LAYER_INLINE) {
            binding.toggleHookLayer.check(R.id.btn_hook_layer_inline)
            binding.tvHookLayerDesc.text = "并排图层：与时钟流式并列，会轻微占用通知图标空间"
        } else {
            binding.toggleHookLayer.check(R.id.btn_hook_layer_overlay)
            binding.tvHookLayerDesc.text = "覆盖图层：悬浮在通知上方，不挤占任何通知图标空间（推荐）"
        }
        binding.toggleHookLayer.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.btn_hook_layer_inline) {
                SettingsStore.hookLayerMode = SettingsStore.LAYER_INLINE
                binding.tvHookLayerDesc.text = "并排图层：与时钟流式并列，会轻微占用通知图标空间"
            } else {
                SettingsStore.hookLayerMode = SettingsStore.LAYER_OVERLAY
                binding.tvHookLayerDesc.text = "覆盖图层：悬浮在通知上方，不挤占任何通知图标空间（推荐）"
            }
            updatePreview()
        }

        // 锚点选择
        if (SettingsStore.hookSide == 1) {
            binding.toggleHookSide.check(R.id.btn_hook_side_left)
        } else {
            binding.toggleHookSide.check(R.id.btn_hook_side_right)
        }
        binding.toggleHookSide.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            SettingsStore.hookSide = if (checkedId == R.id.btn_hook_side_left) 1 else 0
            updatePreview()
        }

        // 水平微调
        binding.sliderHookOffsetX.value = SettingsStore.hookOffsetX.toFloat().coerceIn(-100f, 100f)
        binding.tvOffsetXVal.text = "${SettingsStore.hookOffsetX} px"
        binding.sliderHookOffsetX.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                SettingsStore.hookOffsetX = value.toInt()
                binding.tvOffsetXVal.text = "${value.toInt()} px"
                updatePreview()
            }
        }
        binding.btnOffsetXMinus.setOnClickListener {
            val v = (SettingsStore.hookOffsetX - 5).coerceIn(-100, 100)
            SettingsStore.hookOffsetX = v
            binding.sliderHookOffsetX.value = v.toFloat()
            binding.tvOffsetXVal.text = "$v px"
            updatePreview()
        }
        binding.btnOffsetXPlus.setOnClickListener {
            val v = (SettingsStore.hookOffsetX + 5).coerceIn(-100, 100)
            SettingsStore.hookOffsetX = v
            binding.sliderHookOffsetX.value = v.toFloat()
            binding.tvOffsetXVal.text = "$v px"
            updatePreview()
        }

        // 垂直微调
        binding.sliderHookOffsetY.value = SettingsStore.hookOffsetY.toFloat().coerceIn(-60f, 60f)
        binding.tvOffsetYVal.text = "${SettingsStore.hookOffsetY} px"
        binding.sliderHookOffsetY.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                SettingsStore.hookOffsetY = value.toInt()
                binding.tvOffsetYVal.text = "${value.toInt()} px"
                updatePreview()
            }
        }
        binding.btnOffsetYMinus.setOnClickListener {
            val v = (SettingsStore.hookOffsetY - 2).coerceIn(-60, 60)
            SettingsStore.hookOffsetY = v
            binding.sliderHookOffsetY.value = v.toFloat()
            binding.tvOffsetYVal.text = "$v px"
            updatePreview()
        }
        binding.btnOffsetYPlus.setOnClickListener {
            val v = (SettingsStore.hookOffsetY + 2).coerceIn(-60, 60)
            SettingsStore.hookOffsetY = v
            binding.sliderHookOffsetY.value = v.toFloat()
            binding.tvOffsetYVal.text = "$v px"
            updatePreview()
        }

        // 状态栏颜色自适应
        binding.swHookAdaptColor.isChecked = SettingsStore.hookAdaptColor
        binding.layoutHookCustomColor.visibility = if (SettingsStore.hookAdaptColor) View.GONE else View.VISIBLE
        binding.swHookAdaptColor.setOnCheckedChangeListener { _, checked ->
            SettingsStore.hookAdaptColor = checked
            binding.layoutHookCustomColor.visibility = if (checked) View.GONE else View.VISIBLE
            updatePreview()
        }

        updateHookColorPreview()
        binding.btnHookColor.setOnClickListener {
            runCatching {
                ColorPickerDialog.show(this, SettingsStore.hookTextColor) { picked ->
                    SettingsStore.hookTextColor = picked
                    SettingsStore.hookAdaptColor = false
                    binding.swHookAdaptColor.isChecked = false
                    binding.layoutHookCustomColor.visibility = View.VISIBLE
                    updateHookColorPreview()
                    updatePreview()
                }
            }
        }

        // 状态栏字号
        binding.sliderHookSize.value = SettingsStore.hookTextSizeSp.coerceIn(8f, 20f)
        binding.tvHookSizeVal.text = "${SettingsStore.hookTextSizeSp.toInt()}sp"
        binding.sliderHookSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                SettingsStore.hookTextSizeSp = value
                binding.tvHookSizeVal.text = "${value.toInt()}sp"
                updatePreview()
            }
        }

        // 状态栏显示项
        binding.chipHookPower.isChecked = SettingsStore.hookShowPower
        binding.chipHookCurrent.isChecked = SettingsStore.hookShowCurrent
        binding.chipHookVoltage.isChecked = SettingsStore.hookShowVoltage
        binding.chipHookLevel.isChecked = SettingsStore.hookShowLevel
        binding.chipHookTemp.isChecked = SettingsStore.hookShowTemp
        binding.chipHookUnit.isChecked = SettingsStore.hookShowUnit

        binding.chipHookPower.setOnCheckedChangeListener { _, c -> SettingsStore.hookShowPower = c; updatePreview() }
        binding.chipHookCurrent.setOnCheckedChangeListener { _, c -> SettingsStore.hookShowCurrent = c; updatePreview() }
        binding.chipHookVoltage.setOnCheckedChangeListener { _, c -> SettingsStore.hookShowVoltage = c; updatePreview() }
        binding.chipHookLevel.setOnCheckedChangeListener { _, c -> SettingsStore.hookShowLevel = c; updatePreview() }
        binding.chipHookTemp.setOnCheckedChangeListener { _, c -> SettingsStore.hookShowTemp = c; updatePreview() }
        binding.chipHookUnit.setOnCheckedChangeListener { _, c -> SettingsStore.hookShowUnit = c; updatePreview() }

        // 重启 SystemUI
        binding.btnRestartSystemui.setOnClickListener {
            restartSystemUI()
        }
    }

    private fun updateHookColorPreview() {
        val color = SettingsStore.hookTextColor
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), 0x33000000)
        }
        binding.viewHookColorPreview.background = shape
    }

    private fun restartSystemUI() {
        Toast.makeText(this, "正在请求 Root 重启 SystemUI...", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = RootHelper.exec("pkill -f com.android.systemui") != null
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "SystemUI 重启指令已发送", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "重启失败，请确认是否已授予 Root 权限", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // -------------------------------------------------------------
    // 4. 高级硬件适配卡片
    // -------------------------------------------------------------
    private fun setupAdvancedCard() {
        binding.swPolarityInvert.isChecked = SettingsStore.polarityInvert
        binding.swPolarityInvert.setOnCheckedChangeListener { _, checked ->
            SettingsStore.polarityInvert = checked
            refreshRealtimeData()
            OverlayService.reload(this)
        }

        binding.swDoubleCell.isChecked = SettingsStore.doubleCellFix
        binding.swDoubleCell.setOnCheckedChangeListener { _, checked ->
            SettingsStore.doubleCellFix = checked
            refreshRealtimeData()
            OverlayService.reload(this)
        }
    }

    private fun refreshOverlaySwitchState() {
        binding.swOverlay.isChecked = Settings.canDrawOverlays(this) && isServiceRunning()
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
