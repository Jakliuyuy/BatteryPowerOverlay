package com.jakliuyuy.batterypower

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jakliuyuy.batterypower.battery.RootHelper
import com.jakliuyuy.batterypower.databinding.ActivityMainBinding
import com.jakliuyuy.batterypower.overlay.OverlayService
import com.jakliuyuy.batterypower.settings.AppContext
import com.jakliuyuy.batterypower.settings.SettingsStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshOverlayState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.ctx = applicationContext
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        refreshOverlayState()
    }

    private fun setupUi() {
        // 悬浮窗开关
        binding.swOverlay.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
                    overlayPermLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                    binding.swOverlay.isChecked = false
                    return@setOnCheckedChangeListener
                }
                requestNotifPermission()
                OverlayService.start(this)
                Toast.makeText(this, rootHint(), Toast.LENGTH_LONG).show()
            } else {
                OverlayService.stop(this)
            }
        }

        // 刷新频率
        val freqs = arrayOf("0.5 秒", "1 秒", "2 秒")
        binding.spinnerRefresh.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, freqs)
        binding.spinnerRefresh.setSelection(
            when (SettingsStore.refreshMs) {
                500L -> 0
                2000L -> 2
                else -> 1
            }
        )
        binding.spinnerRefresh.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                SettingsStore.refreshMs = arrayOf(500L, 1000L, 2000L)[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 显示项
        binding.cbPower.isChecked = SettingsStore.showPower
        binding.cbCurrent.isChecked = SettingsStore.showCurrent
        binding.cbVoltage.isChecked = SettingsStore.showVoltage
        binding.cbLevel.isChecked = SettingsStore.showLevel
        binding.cbPower.setOnCheckedChangeListener { _, b -> SettingsStore.showPower = b }
        binding.cbCurrent.setOnCheckedChangeListener { _, b -> SettingsStore.showCurrent = b }
        binding.cbVoltage.setOnCheckedChangeListener { _, b -> SettingsStore.showVoltage = b }
        binding.cbLevel.setOnCheckedChangeListener { _, b -> SettingsStore.showLevel = b }

        // 字体大小滑块
        binding.seekTextSize.progress = ((SettingsStore.textSizeSp - 8f) / 2f).toInt().coerceIn(0, 10)
        binding.tvTextSize.text = "字体 ${SettingsStore.textSizeSp.toInt()}sp"
        binding.seekTextSize.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                SettingsStore.textSizeSp = 8f + p * 2f
                binding.tvTextSize.text = "字体 ${SettingsStore.textSizeSp.toInt()}sp"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        // Hook 模式说明
        binding.tvHookHint.text =
            "Hook 模式：在 LSPosed 中启用本模块 → 勾选 SystemUI → 重启 SystemUI 生效\n" +
            "当前 root 状态：${if (RootHelper.isAvailable()) "已获取 ✓" else "未获取（将降级系统接口）"}"
    }

    private fun rootHint(): String =
        if (RootHelper.isAvailable()) "已用 root 读取内核节点，精度最高"
        else "未检测到 root，已降级系统接口（电流可能为 0）"

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun refreshOverlayState() {
        binding.swOverlay.isChecked =
            Settings.canDrawOverlays(this) &&
            ContextCompat.getSystemService(this, android.app.NotificationManager::class.java) != null
        // 服务是否在跑由 MainActivity onResume 检查
    }

    override fun onResume() {
        super.onResume()
        binding.swOverlay.isChecked = Settings.canDrawOverlays(this)
    }
}
