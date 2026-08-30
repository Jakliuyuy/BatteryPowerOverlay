package com.jakliuyuy.batterypower

import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * 数据诊断页：只读探测电池节点与 root 状态，用于定位一加机型真实路径。
 * 所有命令均为只读（cat / ls / dumpsys / id），不写任何文件。
 */
class DiagnosticActivity : AppCompatActivity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "数据诊断"

        output = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
        }
        val sv = ScrollView(this).apply { addView(output) }

        val btn = Button(this).apply {
            text = "开始诊断"
            setOnClickListener { runDiagnosis() }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(btn)
                addView(sv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f
                ))
            }
        )
    }

    private fun runDiagnosis() {
        output.text = "诊断中...（Magisk 弹窗请允许 root）\n"
        Toast.makeText(this, "诊断开始，Magisk 弹窗请允许", Toast.LENGTH_LONG).show()
        thread {
            val sb = StringBuilder()
            sb.appendLine("===== 设备信息 =====")
            sb.appendLine("型号: ${android.os.Build.MODEL}")
            sb.appendLine("产品: ${android.os.Build.PRODUCT}")
            sb.appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            sb.appendLine("内核: ${System.getProperty("os.version")}")
            sb.appendLine()

            sb.append(exec("""
                echo '===== SU 状态 ====='
                id
                echo
                echo '===== 供电节点总览 ====='
                ls /sys/class/power_supply/
                echo
                echo '===== battery 目录内容 ====='
                ls -l /sys/class/power_supply/battery/ 2>&1
                echo
                echo '===== battery 标准节点读取 ====='
                for f in current_now current_avg charge_now voltage_now temp capacity status technology; do
                  echo -n "battery/${'$'}f = "
                  cat /sys/class/power_supply/battery/${'$'}f 2>&1
                done
                echo
                echo '===== 备用电池节点探测 ====='
                for b in qcom-battery main-battery battery_2 dual-battery battery_1; do
                  if [ -d /sys/class/power_supply/${'$'}b ]; then
                    echo "-- ${'$'}b --"
                    for f in current_now current_avg voltage_now temp capacity; do
                      echo -n "  ${'$'}f = "
                      cat /sys/class/power_supply/${'$'}b/${'$'}f 2>&1
                    done
                  else
                    echo "-- ${'$'}b : 不存在"
                  fi
                done
                echo
                echo '===== dumpsys battery ====='
                dumpsys battery 2>&1 | head -25
            """.trimIndent()))

            runOnUiThread {
                output.text = sb.toString()
                Toast.makeText(this, "诊断完成，截图发我", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 单次 su 会话执行整段脚本（只读命令），超时 10s */
    private fun exec(script: String): String {
        return try {
            val p = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy()
                "执行超时"
            } else out
        } catch (e: Exception) {
            "su 执行失败: ${e.message}"
        }
    }
}
