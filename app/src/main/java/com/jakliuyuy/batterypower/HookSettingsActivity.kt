package com.jakliuyuy.batterypower

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jakliuyuy.batterypower.battery.BatteryReader
import com.jakliuyuy.batterypower.battery.RootHelper
import com.jakliuyuy.batterypower.databinding.ActivityHookSettingsBinding
import com.jakliuyuy.batterypower.settings.AppContext
import com.jakliuyuy.batterypower.settings.SettingsStore

/**
 * 状态栏 Hook (LSPosed 模块) 专属深度定制页
 */
class HookSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHookSettingsBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false

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
        binding = ActivityHookSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupHookSwitch()
        setupLayerAndSide()
        setupOffsets()
        setupColorAndSize()
        setupChips()
        setupRestartSystemUI()
        updatePreview()
    }

    override fun onResume() {
        super.onResume()
        isMonitoring = true
        handler.post(previewRunnable)
    }

    override fun onPause() {
        isMonitoring = false
        handler.removeCallbacks(previewRunnable)
        super.onPause()
    }

    private fun setupHookSwitch() {
        binding.swHook.isChecked = SettingsStore.hookEnabled
        binding.swHook.setOnCheckedChangeListener { _, checked ->
            SettingsStore.hookEnabled = checked
            updatePreview()
        }
    }

    private fun setupLayerAndSide() {
        // 图层模式选择
        if (SettingsStore.hookLayerMode == SettingsStore.LAYER_INLINE) {
            binding.toggleHookLayer.check(R.id.btn_hook_layer_inline)
            binding.tvHookLayerDesc.text = "并排流式图层：与时钟流式并列，会轻微占用通知图标空间"
        } else {
            binding.toggleHookLayer.check(R.id.btn_hook_layer_overlay)
            binding.tvHookLayerDesc.text = "独立覆盖图层：悬浮在通知上方，不挤占任何通知图标空间（推荐）"
        }
        binding.toggleHookLayer.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.btn_hook_layer_inline) {
                SettingsStore.hookLayerMode = SettingsStore.LAYER_INLINE
                binding.tvHookLayerDesc.text = "并排流式图层：与时钟流式并列，会轻微占用通知图标空间"
            } else {
                SettingsStore.hookLayerMode = SettingsStore.LAYER_OVERLAY
                binding.tvHookLayerDesc.text = "独立覆盖图层：悬浮在通知上方，不挤占任何通知图标空间（推荐）"
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
    }

    private fun setupOffsets() {
        // 水平微调 (-300px ~ +1300px)
        binding.sliderHookOffsetX.value = SettingsStore.hookOffsetX.toFloat().coerceIn(-300f, 1300f)
        binding.tvOffsetXVal.text = "${SettingsStore.hookOffsetX} px"
        binding.sliderHookOffsetX.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                SettingsStore.hookOffsetX = value.toInt()
                binding.tvOffsetXVal.text = "${value.toInt()} px"
                updatePreview()
            }
        }
        binding.btnOffsetXMinus.setOnClickListener {
            val v = (SettingsStore.hookOffsetX - 10).coerceIn(-300, 1300)
            SettingsStore.hookOffsetX = v
            binding.sliderHookOffsetX.value = v.toFloat()
            binding.tvOffsetXVal.text = "$v px"
            updatePreview()
        }
        binding.btnOffsetXPlus.setOnClickListener {
            val v = (SettingsStore.hookOffsetX + 10).coerceIn(-300, 1300)
            SettingsStore.hookOffsetX = v
            binding.sliderHookOffsetX.value = v.toFloat()
            binding.tvOffsetXVal.text = "$v px"
            updatePreview()
        }
        binding.btnOffsetXZero.setOnClickListener {
            SettingsStore.hookOffsetX = 0
            binding.sliderHookOffsetX.value = 0f
            binding.tvOffsetXVal.text = "0 px"
            updatePreview()
        }

        // 水平预设快捷跳转
        binding.btnPresetClock.setOnClickListener {
            SettingsStore.hookOffsetX = 0
            binding.sliderHookOffsetX.value = 0f
            binding.tvOffsetXVal.text = "0 px"
            updatePreview()
        }
        binding.btnPresetCenter.setOnClickListener {
            val v = 500
            SettingsStore.hookOffsetX = v
            binding.sliderHookOffsetX.value = v.toFloat()
            binding.tvOffsetXVal.text = "$v px"
            updatePreview()
        }
        binding.btnPresetRight.setOnClickListener {
            val v = 1000
            SettingsStore.hookOffsetX = v
            binding.sliderHookOffsetX.value = v.toFloat()
            binding.tvOffsetXVal.text = "$v px"
            updatePreview()
        }

        // 垂直微调 (-120px ~ +120px)
        binding.sliderHookOffsetY.value = SettingsStore.hookOffsetY.toFloat().coerceIn(-120f, 120f)
        binding.tvOffsetYVal.text = "${SettingsStore.hookOffsetY} px"
        binding.sliderHookOffsetY.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                SettingsStore.hookOffsetY = value.toInt()
                binding.tvOffsetYVal.text = "${value.toInt()} px"
                updatePreview()
            }
        }
        binding.btnOffsetYMinus.setOnClickListener {
            val v = (SettingsStore.hookOffsetY - 5).coerceIn(-120, 120)
            SettingsStore.hookOffsetY = v
            binding.sliderHookOffsetY.value = v.toFloat()
            binding.tvOffsetYVal.text = "$v px"
            updatePreview()
        }
        binding.btnOffsetYPlus.setOnClickListener {
            val v = (SettingsStore.hookOffsetY + 5).coerceIn(-120, 120)
            SettingsStore.hookOffsetY = v
            binding.sliderHookOffsetY.value = v.toFloat()
            binding.tvOffsetYVal.text = "$v px"
            updatePreview()
        }
        binding.btnOffsetYZero.setOnClickListener {
            SettingsStore.hookOffsetY = 0
            binding.sliderHookOffsetY.value = 0f
            binding.tvOffsetYVal.text = "0 px"
            updatePreview()
        }
    }

    private fun setupColorAndSize() {
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

        // 状态栏字号自适应系统时钟
        binding.swHookFollowClockSize.isChecked = SettingsStore.hookFollowClockSize
        binding.layoutHookCustomSize.visibility = if (SettingsStore.hookFollowClockSize) View.GONE else View.VISIBLE
        binding.swHookFollowClockSize.setOnCheckedChangeListener { _, checked ->
            SettingsStore.hookFollowClockSize = checked
            binding.layoutHookCustomSize.visibility = if (checked) View.GONE else View.VISIBLE
            updatePreview()
        }

        // 自定义状态栏字号滑块
        binding.sliderHookSize.value = SettingsStore.hookTextSizeSp.coerceIn(8f, 24f)
        binding.tvHookSizeVal.text = "${SettingsStore.hookTextSizeSp.toInt()}sp"
        binding.sliderHookSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                SettingsStore.hookTextSizeSp = value
                binding.tvHookSizeVal.text = "${value.toInt()}sp"
                updatePreview()
            }
        }
    }

    private fun setupChips() {
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
    }

    private fun setupRestartSystemUI() {
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

    private fun updatePreview() {
        val sample = BatteryReader.read(this)
        if (!SettingsStore.hookEnabled) {
            binding.tvPreviewClockLeft.visibility = View.GONE
            binding.tvPreviewClockRight.visibility = View.GONE
            return
        }

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
