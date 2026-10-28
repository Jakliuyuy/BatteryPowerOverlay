package com.jakliuyuy.batterypower

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakliuyuy.batterypower.databinding.DialogColorPickerBinding

/**
 * 现代化 Material 3 HSV 调色盘：
 * 1. 实时预览条与 HEX 颜色代码展示
 * 2. 经典高亮极客调色板快捷选择
 * 3. 色相 (0~359)、饱和度 (0~100)、亮度 (0~100) 滑块联动
 */
object ColorPickerDialog {

    private val PRESETS = intArrayOf(
        0xFF00E676.toInt(), // 极客荧光绿
        0xFF00B0FF.toInt(), // 霓虹冰川蓝
        0xFFFFD600.toInt(), // 明亮金黄
        0xFFFF3D00.toInt(), // 烈焰橙红
        0xFFE040FB.toInt(), // 赛博朋克紫
        0xFFFFFFFF.toInt(), // 纯白
        0xFF00E5FF.toInt(), // 青空碧蓝
        0xFFFF80AB.toInt()  // 樱花粉
    )

    fun show(context: Context, initialColor: Int, onPick: (Int) -> Unit) {
        val binding = DialogColorPickerBinding.inflate(LayoutInflater.from(context))
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)

        fun updateUI() {
            val color = Color.HSVToColor(hsv)
            binding.cardPreview.setCardBackgroundColor(color)
            val hex = String.format("#%06X", (0xFFFFFF and color))
            binding.tvHexCode.text = hex
            // 自动判断文字反色
            val brightness = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114)
            binding.tvHexCode.setTextColor(if (brightness > 160) Color.BLACK else Color.WHITE)
        }

        binding.sliderHue.value = hsv[0].coerceIn(0f, 359f)
        binding.sliderSat.value = (hsv[1] * 100f).coerceIn(0f, 100f)
        binding.sliderVal.value = (hsv[2] * 100f).coerceIn(0f, 100f)

        binding.sliderHue.addOnChangeListener { _, value, _ ->
            hsv[0] = value
            updateUI()
        }
        binding.sliderSat.addOnChangeListener { _, value, _ ->
            hsv[1] = value / 100f
            updateUI()
        }
        binding.sliderVal.addOnChangeListener { _, value, _ ->
            hsv[2] = value / 100f
            updateUI()
        }

        // 填充预设色块
        val dp = context.resources.displayMetrics.density
        for (c in PRESETS) {
            val swatch = View(context).apply {
                val size = (36 * dp).toInt()
                val lp = LinearLayout.LayoutParams(size, size).apply {
                    setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                }
                layoutParams = lp
                setBackgroundColor(c)
                // 圆角背景
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    setStroke((1.5 * dp).toInt(), 0x44FFFFFF)
                }
                background = shape
                setOnClickListener {
                    Color.colorToHSV(c, hsv)
                    binding.sliderHue.value = hsv[0]
                    binding.sliderSat.value = hsv[1] * 100f
                    binding.sliderVal.value = hsv[2] * 100f
                    updateUI()
                }
            }
            binding.presetPaletteRow.addView(swatch)
        }

        updateUI()

        MaterialAlertDialogBuilder(context)
            .setTitle("自定义文字颜色")
            .setView(binding.root)
            .setPositiveButton("确定") { _, _ ->
                onPick(Color.HSVToColor(hsv))
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
