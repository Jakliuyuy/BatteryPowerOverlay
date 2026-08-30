package com.jakliuyuy.batterypower

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
        // ---------- 悬浮窗开关 ----------
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

        // ---------- 刷新频率 ----------
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

        // ---------- 悬浮窗显示项 ----------
        binding.cbPower.isChecked = SettingsStore.showPower
        binding.cbCurrent.isChecked = SettingsStore.showCurrent
        binding.cbVoltage.isChecked = SettingsStore.showVoltage
        binding.cbTemp.isChecked = SettingsStore.showTemp
        binding.cbLevel.isChecked = SettingsStore.showLevel
        binding.cbPower.setOnCheckedChangeListener { _, b -> SettingsStore.showPower = b }
        binding.cbCurrent.setOnCheckedChangeListener { _, b -> SettingsStore.showCurrent = b }
        binding.cbVoltage.setOnCheckedChangeListener { _, b -> SettingsStore.showVoltage = b }
        binding.cbTemp.setOnCheckedChangeListener { _, b -> SettingsStore.showTemp = b }
        binding.cbLevel.setOnCheckedChangeListener { _, b -> SettingsStore.showLevel = b }

        // ---------- 悬浮窗文字颜色 ----------
        setupColorPicker(
            binding.colorRow, SettingsStore.textColor,
            update = { SettingsStore.textColor = it }
        )

        // ---------- 悬浮窗字体大小 ----------
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

        // ---------- Hook 模式：位置 ----------
        binding.radioHookSide.check(
            if (SettingsStore.hookSide == 1) R.id.rb_hook_left else R.id.rb_hook_right
        )
        binding.radioHookSide.setOnCheckedChangeListener { _, id ->
            SettingsStore.hookSide = if (id == R.id.rb_hook_left) 1 else 0
        }

        // ---------- Hook 模式：显示项 ----------
        binding.cbHookPower.isChecked = SettingsStore.hookShowPower
        binding.cbHookCurrent.isChecked = SettingsStore.hookShowCurrent
        binding.cbHookVoltage.isChecked = SettingsStore.hookShowVoltage
        binding.cbHookTemp.isChecked = SettingsStore.hookShowTemp
        binding.cbHookLevel.isChecked = SettingsStore.hookShowLevel
        binding.cbHookPower.setOnCheckedChangeListener { _, b -> SettingsStore.hookShowPower = b }
        binding.cbHookCurrent.setOnCheckedChangeListener { _, b -> SettingsStore.hookShowCurrent = b }
        binding.cbHookVoltage.setOnCheckedChangeListener { _, b -> SettingsStore.hookShowVoltage = b }
        binding.cbHookTemp.setOnCheckedChangeListener { _, b -> SettingsStore.hookShowTemp = b }
        binding.cbHookLevel.setOnCheckedChangeListener { _, b -> SettingsStore.hookShowLevel = b }

        // ---------- Hook 模式：颜色/字号 ----------
        setupColorPicker(
            binding.colorRowHook, SettingsStore.hookTextColor,
            update = { SettingsStore.hookTextColor = it }
        )
        binding.seekHookTextSize.progress = ((SettingsStore.hookTextSizeSp - 8f) / 2f).toInt().coerceIn(0, 10)
        binding.tvHookTextSize.text = "字号 ${SettingsStore.hookTextSizeSp.toInt()}sp"
        binding.seekHookTextSize.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                SettingsStore.hookTextSizeSp = 8f + p * 2f
                binding.tvHookTextSize.text = "字号 ${SettingsStore.hookTextSizeSp.toInt()}sp"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        binding.tvHookHint.text =
            "Hook 模式：LSPosed 启用本模块 → 勾选 SystemUI → 重启 SystemUI 一次\n" +
            "之后在 App 里改配置（位置/颜色/显示项）1 秒内实时生效，无需再重启\n" +
            "当前 root 状态：${if (RootHelper.isAvailable()) "已获取 ✓（内核节点直读）" else "未获取（降级系统接口，电流可能为 0）"}\n\n" +
            "悬浮窗读数为 0？点下方按钮跑数据诊断，把结果截图发我"

        binding.btnDiagnostic.setOnClickListener {
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }
    }

    /** 颜色选择器：色块数组，点击选中 */
    private fun setupColorPicker(row: android.widget.LinearLayout, current: Int, update: (Int) -> Unit) {
        val colors = intArrayOf(
            Color.WHITE, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE,
            Color.YELLOW, Color.CYAN, Color.MAGENTA
        )
        val dp = resources.displayMetrics.density
        for (c in colors) {
            val swatch = View(this)
            val size = (32 * dp).toInt()
            val lp = android.widget.LinearLayout.LayoutParams(size, size)
            lp.setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            swatch.layoutParams = lp
            swatch.setBackgroundColor(c)
            swatch.setPadding((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
            swatch.background = null
            swatch.setBackgroundColor(c)
            if (c == current) {
                swatch.setBackgroundResource(android.R.drawable.btn_star_big_on)
                swatch.setBackgroundTintList(android.content.res.ColorStateList.valueOf(c))
            }
            swatch.setOnClickListener {
                update(c)
                // 刷新选中态
                for (i in 0 until row.childCount) {
                    val v = row.getChildAt(i)
                    val tag = v.tag as? Int ?: continue
                    if (tag == c) {
                        v.setBackgroundResource(android.R.drawable.btn_star_big_on)
                        v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(c))
                    } else {
                        v.setBackgroundColor(tag)
                    }
                }
            }
            swatch.tag = c
            row.addView(swatch)
        }
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
    }

    override fun onResume() {
        super.onResume()
        binding.swOverlay.isChecked = Settings.canDrawOverlays(this)
    }
}
