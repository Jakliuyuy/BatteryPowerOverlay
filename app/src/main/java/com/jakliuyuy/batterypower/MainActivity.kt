package com.jakliuyuy.batterypower

import android.content.Intent
import android.graphics.Color
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

/**
 * 现代化 Material 3 动力中心与主控制台：
 * 1. 实时电池动力看板 + 所见即所得模拟视窗。
 * 2. 结构化主菜单体系：桌面悬浮窗、状态栏 Hook、全局与刷新频率、硬件适配与诊断。
 * 3. 毫秒级全局刷新频率热推联动。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoringActive = false

    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshOverlaySwitchState()
            refreshMenuSummaries()
        }

    private val dashboardRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoringActive) return
            refreshRealtimeData()
            val interval = SettingsStore.globalRefreshMs.coerceIn(500L, 3000L)
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.init(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTopBar()
        setupDashboard()
        setupMenuCards()
    }

    override fun onResume() {
        super.onResume()
        isMonitoringActive = true
        handler.removeCallbacks(dashboardRunnable)
        handler.post(dashboardRunnable)

        refreshOverlaySwitchState()
        refreshHookSwitchState()
        refreshMenuSummaries()
    }

    override fun onPause() {
        isMonitoringActive = false
        handler.removeCallbacks(dashboardRunnable)
        super.onPause()
    }

    private fun setupTopBar() {
        binding.btnOpenDiag.setOnClickListener {
            startActivity(Intent(this, HardwareSettingsActivity::class.java))
        }

        binding.btnQuickRestart.setOnClickListener {
            restartSystemUI()
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
            else -> binding.tvPreviewOverlay.setBackgroundColor(Color.TRANSPARENT)
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

            targetTv.translationX = (SettingsStore.hookOffsetX / 10f).coerceIn(-25f, 120f)
            targetTv.translationY = (SettingsStore.hookOffsetY / 6f).coerceIn(-15f, 15f)
        }
    }

    // -------------------------------------------------------------
    // 2. 主菜单卡片群及次级菜单导航
    // -------------------------------------------------------------
    private fun setupMenuCards() {
        // 卡片 1: 桌面悬浮窗管理
        binding.menuCardOverlay.setOnClickListener {
            startActivity(Intent(this, OverlaySettingsActivity::class.java))
        }

        binding.swMenuOverlay.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请授予悬浮窗显示权限", Toast.LENGTH_SHORT).show()
                    overlayPermLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                    binding.swMenuOverlay.isChecked = false
                    return@setOnCheckedChangeListener
                }
                requestNotifPermission()
                OverlayService.start(this)
            } else {
                OverlayService.stop(this)
            }
            refreshMenuSummaries()
        }

        // 卡片 2: 状态栏 Hook 定制
        binding.menuCardHook.setOnClickListener {
            startActivity(Intent(this, HookSettingsActivity::class.java))
        }

        binding.swMenuHook.setOnCheckedChangeListener { _, checked ->
            SettingsStore.hookEnabled = checked
            updatePreview()
            refreshMenuSummaries()
        }

        // 卡片 3: 全局与刷新频率
        binding.menuCardGlobal.setOnClickListener {
            startActivity(Intent(this, GlobalSettingsActivity::class.java))
        }

        // 卡片 4: 硬件适配与底层诊断
        binding.menuCardHardware.setOnClickListener {
            startActivity(Intent(this, HardwareSettingsActivity::class.java))
        }
    }

    private fun refreshMenuSummaries() {
        // 1. 悬浮窗摘要
        val isOverlayActive = isServiceRunning() && Settings.canDrawOverlays(this)
        val styleDesc = when (SettingsStore.overlayStyle) {
            SettingsStore.STYLE_CAPSULE_FROSTED -> "雾面毛玻璃"
            SettingsStore.STYLE_CAPSULE_DARK -> "深灰雅致"
            SettingsStore.STYLE_CAPSULE_LIGHT -> "纯净雪白"
            SettingsStore.STYLE_CAPSULE_OUTLINE -> "简约描边"
            else -> "极简全透"
        }
        binding.tvMenuOverlaySummary.text = if (isOverlayActive) {
            "已开启 · $styleDesc · ${SettingsStore.textSizeSp.toInt()}sp"
        } else {
            "未开启 · 点击配置外观与位置"
        }

        // 2. Hook 模块摘要
        val isHookActive = SettingsStore.hookEnabled
        val layerDesc = if (SettingsStore.hookLayerMode == SettingsStore.LAYER_INLINE) "并排图层" else "独立覆盖图层"
        val sideDesc = if (SettingsStore.hookSide == 1) "时钟左侧" else "时钟右侧"
        binding.tvMenuHookSummary.text = if (isHookActive) {
            "已启用 · $layerDesc · $sideDesc"
        } else {
            "已关闭 · 点击进入深度定制"
        }

        // 3. 全局刷新频率摘要
        val refreshMs = SettingsStore.globalRefreshMs
        val freqDesc = when (refreshMs) {
            500L -> "500ms (极速)"
            1000L -> "1000ms (标准均衡)"
            1500L -> "1500ms (温和节能)"
            2000L -> "2000ms (长效续航)"
            3000L -> "3000ms (极致省电)"
            else -> "${refreshMs}ms"
        }
        val unitDesc = if (SettingsStore.showUnit) "单位开启" else "单位隐藏"
        binding.tvMenuGlobalSummary.text = "全局刷新: $freqDesc · $unitDesc"

        // 4. 硬件适配摘要
        val polarityDesc = if (SettingsStore.polarityInvert) "极性翻转" else "默认极性"
        val cellDesc = if (SettingsStore.doubleCellFix) "双电芯×2" else "单电芯"
        binding.tvMenuHardwareSummary.text = "$cellDesc · $polarityDesc · 内核节点探测"
    }

    private fun refreshOverlaySwitchState() {
        binding.swMenuOverlay.setOnCheckedChangeListener(null)
        binding.swMenuOverlay.isChecked = Settings.canDrawOverlays(this) && isServiceRunning()
        binding.swMenuOverlay.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请授予悬浮窗显示权限", Toast.LENGTH_SHORT).show()
                    overlayPermLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                    binding.swMenuOverlay.isChecked = false
                    return@setOnCheckedChangeListener
                }
                requestNotifPermission()
                OverlayService.start(this)
            } else {
                OverlayService.stop(this)
            }
            refreshMenuSummaries()
        }
    }

    private fun refreshHookSwitchState() {
        binding.swMenuHook.setOnCheckedChangeListener(null)
        binding.swMenuHook.isChecked = SettingsStore.hookEnabled
        binding.swMenuHook.setOnCheckedChangeListener { _, checked ->
            SettingsStore.hookEnabled = checked
            updatePreview()
            refreshMenuSummaries()
        }
    }

    private fun isServiceRunning(): Boolean {
        return SettingsStore.sp(this).getBoolean("overlay_service_active", false)
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

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
