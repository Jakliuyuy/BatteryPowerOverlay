package com.jakliuyuy.batterypower

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 无极 HSV 调色板：色相/饱和度/亮度三个滑条 + 实时预览。
 * 返回选中的 ARGB 颜色。
 */
object ColorPickerDialog {

    fun show(context: Context, initialColor: Int, onPick: (Int) -> Unit) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        // 预览
        val preview = TextView(context).apply {
            text = "颜色预览"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val previewLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 96
        )
        root.addView(preview, previewLp)

        // HSV 当前值
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)

        fun makeBar(label: String, max: Int, value: Int, onChange: (Int) -> Unit): SeekBar {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tv = TextView(context).apply {
                text = label
                width = 96
            }
            val sb = SeekBar(context).apply {
                this.max = max
                progress = value
            }
            val sbLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(tv)
            row.addView(sb, sbLp)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                    onChange(p)
                    preview.setBackgroundColor(Color.HSVToColor(hsv))
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
            root.addView(row)
            return sb
        }

        makeBar("色相", 359, hsv[0].toInt()) { hsv[0] = it.toFloat() }
        makeBar("饱和度", 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f }
        makeBar("亮度", 100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f }

        preview.setBackgroundColor(Color.HSVToColor(hsv))

        AlertDialog.Builder(context)
            .setTitle("自定义颜色")
            .setView(root)
            .setPositiveButton("确定") { _, _ -> onPick(Color.HSVToColor(hsv)) }
            .setNegativeButton("取消", null)
            .show()
    }
}
