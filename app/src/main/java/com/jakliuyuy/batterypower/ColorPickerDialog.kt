package com.jakliuyuy.batterypower

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakliuyuy.batterypower.databinding.DialogColorPickerBinding
import kotlin.math.roundToInt

/**
 * 现代化防闪退 HSV 调色盘：
 * 1. 采用原生最稳健的 SeekBar，彻底规避 Material Slider 的 stepSize / valueFitsStepSize 崩溃缺陷。
 * 2. 实时预览条与 HEX 颜色代码展示。
 * 3. 经典高亮极客调色板快捷选择。
 */
object ColorPickerDialog {

    private val PRESETS = intArrayOf(
        0xFF000000.toInt(), // 纯黑（适合浅色背景时钟）
        0xFFFFFFFF.toInt(), // 纯白（适合暗色背景时钟）
        0xFF00E676.toInt(), // 极客荧光绿
        0xFF00B0FF.toInt(), // 霓虹冰川蓝
        0xFFFFD600.toInt(), // 明亮金黄
        0xFFFF3D00.toInt(), // 烈焰橙红
        0xFFE040FB.toInt(), // 赛博朋克紫
        0xFF78909C.toInt()  // 雅致高级灰
    )

    fun show(context: Context, initialColor: Int, onPick: (Int) -> Unit) {
        try {
            val binding = DialogColorPickerBinding.inflate(LayoutInflater.from(context))
            val hsv = FloatArray(3)
            Color.colorToHSV(initialColor, hsv)

            fun updateUI() {
                try {
                    val color = Color.HSVToColor(hsv)
                    binding.cardPreview.setCardBackgroundColor(color)
                    val hex = String.format("#%06X", (0xFFFFFF and color))
                    binding.tvHexCode.text = hex
                    // 自动计算文字明暗反色
                    val brightness = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114)
                    binding.tvHexCode.setTextColor(if (brightness > 160) Color.BLACK else Color.WHITE)
                } catch (_: Exception) {
                }
            }

            val hueInt = hsv[0].roundToInt().coerceIn(0, 360)
            val satInt = (hsv[1] * 100f).roundToInt().coerceIn(0, 100)
            val valInt = (hsv[2] * 100f).roundToInt().coerceIn(0, 100)

            binding.sbHue.progress = hueInt
            binding.sbSat.progress = satInt
            binding.sbVal.progress = valInt

            val listener = object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    when (seekBar?.id) {
                        R.id.sb_hue -> hsv[0] = progress.toFloat()
                        R.id.sb_sat -> hsv[1] = progress / 100f
                        R.id.sb_val -> hsv[2] = progress / 100f
                    }
                    updateUI()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }

            binding.sbHue.setOnSeekBarChangeListener(listener)
            binding.sbSat.setOnSeekBarChangeListener(listener)
            binding.sbVal.setOnSeekBarChangeListener(listener)

            // 填充预设色块
            val dp = context.resources.displayMetrics.density
            for (c in PRESETS) {
                val swatch = View(context).apply {
                    val size = (34 * dp).toInt()
                    val lp = LinearLayout.LayoutParams(size, size).apply {
                        setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                    }
                    layoutParams = lp
                    val shape = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        setStroke((1.5 * dp).toInt(), 0x33000000)
                    }
                    background = shape
                    setOnClickListener {
                        Color.colorToHSV(c, hsv)
                        binding.sbHue.progress = hsv[0].roundToInt().coerceIn(0, 360)
                        binding.sbSat.progress = (hsv[1] * 100f).roundToInt().coerceIn(0, 100)
                        binding.sbVal.progress = (hsv[2] * 100f).roundToInt().coerceIn(0, 100)
                        updateUI()
                    }
                }
                binding.presetPaletteRow.addView(swatch)
            }

            updateUI()

            MaterialAlertDialogBuilder(context)
                .setTitle("选择文字颜色")
                .setView(binding.root)
                .setPositiveButton("确定") { _, _ ->
                    onPick(Color.HSVToColor(hsv))
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (_: Exception) {
        }
    }
}
