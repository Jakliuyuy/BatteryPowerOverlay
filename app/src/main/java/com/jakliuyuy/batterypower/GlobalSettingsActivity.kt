package com.jakliuyuy.batterypower

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jakliuyuy.batterypower.databinding.ActivityGlobalSettingsBinding
import com.jakliuyuy.batterypower.overlay.OverlayService
import com.jakliuyuy.batterypower.settings.AppContext
import com.jakliuyuy.batterypower.settings.SettingsStore

/**
 * 全局通用设置与刷新频率管理页
 */
class GlobalSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGlobalSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.init(applicationContext)
        binding = ActivityGlobalSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRefreshInterval()
        setupUnitSwitch()
    }

    private fun setupRefreshInterval() {
        val currentMs = SettingsStore.globalRefreshMs
        when {
            currentMs <= 600L -> binding.rgRefreshInterval.check(R.id.rb_refresh_500)
            currentMs in 601L..1200L -> binding.rgRefreshInterval.check(R.id.rb_refresh_1000)
            currentMs in 1201L..1700L -> binding.rgRefreshInterval.check(R.id.rb_refresh_1500)
            currentMs in 1701L..2500L -> binding.rgRefreshInterval.check(R.id.rb_refresh_2000)
            else -> binding.rgRefreshInterval.check(R.id.rb_refresh_3000)
        }

        binding.rgRefreshInterval.setOnCheckedChangeListener { _, checkedId ->
            val (selectedMs, desc) = when (checkedId) {
                R.id.rb_refresh_500 -> 500L to "500ms (极速灵敏)"
                R.id.rb_refresh_1000 -> 1000L to "1000ms (标准均衡)"
                R.id.rb_refresh_1500 -> 1500L to "1500ms (温和节能)"
                R.id.rb_refresh_2000 -> 2000L to "2000ms (超长续航)"
                R.id.rb_refresh_3000 -> 3000L to "3000ms (极致省电)"
                else -> 1000L to "1000ms"
            }

            SettingsStore.globalRefreshMs = selectedMs
            OverlayService.reload(this)
            Toast.makeText(this, "全局刷新频率已切换为 $desc，悬浮窗与状态栏已同步生效", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupUnitSwitch() {
        binding.swShowUnit.isChecked = SettingsStore.showUnit
        binding.swShowUnit.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.showUnit = isChecked
            SettingsStore.hookShowUnit = isChecked
            OverlayService.reload(this)
            Toast.makeText(
                this,
                if (isChecked) "已全局开启单位显示 (W / mA / V / °C / %)" else "已全局隐藏单位符号",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
