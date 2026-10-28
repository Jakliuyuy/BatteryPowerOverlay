package com.jakliuyuy.batterypower

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jakliuyuy.batterypower.databinding.ActivityDiagnosticBinding
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 现代化底层诊断页：只读探测系统电池节点、供电状态与 Root 路径。
 */
class DiagnosticActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnRunDiag.setOnClickListener { runDiagnosis() }

        binding.btnCopyDiag.setOnClickListener {
            val text = binding.tvDiagOutput.text.toString()
            if (text.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("BatteryDiagnosis", text))
                Toast.makeText(this, "诊断信息已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runDiagnosis() {
        binding.tvDiagOutput.text = "正在执行底层探测，请在 Root (Magisk/KernelSU) 弹窗中允许...\n"
        thread {
            val sb = StringBuilder()
            sb.appendLine("========== 设备底层信息 ==========")
            sb.appendLine("型号: ${Build.MODEL} (${Build.PRODUCT})")
            sb.appendLine("厂商: ${Build.MANUFACTURER} / 品牌: ${Build.BRAND}")
            sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            sb.appendLine("内核: ${System.getProperty("os.version")}")
            sb.appendLine()

            val script = """
                echo '===== SU 提权状态 ====='
                id
                echo
                echo '===== 供电节点目录列表 ====='
                ls -l /sys/class/power_supply/ 2>&1
                echo
                echo '===== 标准 battery 目录关键节点 ====='
                for f in current_now current_avg voltage_now power_now temp capacity status charge_now technology; do
                  if [ -f /sys/class/power_supply/battery/${'$'}f ]; then
                    echo "battery/${'$'}f = ${'$'}(cat /sys/class/power_supply/battery/${'$'}f 2>&1)"
                  else
                    echo "battery/${'$'}f : 不存在"
                  fi
                done
                echo
                echo '===== 双电芯与备选节点探测 ====='
                for b in qcom-battery main-battery battery_1 battery_2 bms dual-battery; do
                  if [ -d /sys/class/power_supply/${'$'}b ]; then
                    echo ">> 发现节点 [${'$'}b]:"
                    for f in current_now current_avg voltage_now temp capacity; do
                      [ -f /sys/class/power_supply/${'$'}b/${'$'}f ] && echo "   ${'$'}f = ${'$'}(cat /sys/class/power_supply/${'$'}b/${'$'}f 2>&1)"
                    done
                  fi
                done
                echo
                echo '===== dumpsys battery 摘要 ====='
                dumpsys battery 2>&1 | head -25
            """.trimIndent()

            val result = execDirect(script)
            sb.append(result)

            runOnUiThread {
                binding.tvDiagOutput.text = sb.toString()
                Toast.makeText(this, "诊断完成", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun execDirect(script: String): String {
        return try {
            val p = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroy()
                "执行超时（10秒）"
            } else {
                text.trim()
            }
        } catch (e: Exception) {
            "su 执行异常: ${e.message}"
        }
    }
}
