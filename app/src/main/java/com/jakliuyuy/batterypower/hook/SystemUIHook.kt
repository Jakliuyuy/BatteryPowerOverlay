package com.jakliuyuy.batterypower.hook

import android.content.Context
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook SystemUI 状态栏，注入电池功率 TextView（Scene 迷你监视器风格）。
 *
 * - 时钟类兼容：ColorOS(oplus) / AOSP 双类名探测
 * - 配置：ConfigBridge（ContentProvider IPC）实时拉取，1s 内生效
 * - 位置：默认插时钟右侧 + 智能避让右侧图标；支持 x/y 偏移微调
 */
object SystemUIHook {

    private val CANDIDATES = listOf(
        "com.oplus.systemui.statusbar.policy.Clock",
        "com.android.systemui.statusbar.policy.Clock",
        "com.oplusos.systemui.statusbar.policy.Clock"
    )

    private const val POWER_TAG = "battery_power_overlay_tag"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clockClass = CANDIDATES.firstNotNullOfOrNull {
            runCatching { XposedHelpers.findClass(it, lpparam.classLoader) }.getOrNull()
        } ?: return  // 三个候选都没找到，ColorOS 类名变更，需要远程日志定位

        XposedHelpers.findAndHookMethod(
            clockClass,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    inject(param.thisObject as View)
                }
            }
        )
    }

    private fun inject(clock: View) {
        val parent = clock.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(POWER_TAG) != null) return

        val ctx = clock.context
        val tv = TextView(ctx).apply {
            tag = POWER_TAG
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        // 先挂到父容器末尾（位置由每帧 applyConfig 计算）
        parent.addView(tv)

        // 1s 刷新循环
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!tv.isAttachedToWindow) return
                val cfg = ConfigBridge.query(ctx)
                applyConfig(tv, parent, clock, cfg)
                tv.text = readPower(ctx, cfg)
                handler.postDelayed(this, 1000L)
            }
        }
        tv.post(runnable)
    }

    /** 位置（智能避让 + x/y 偏移）/ 颜色 / 字号 实时应用 */
    private fun applyConfig(
        tv: TextView,
        parent: ViewGroup,
        clock: View,
        cfg: Map<String, String>
    ) {
        val ctx = tv.context

        // 颜色/字号
        tv.textSize = ConfigBridge.floatOf(cfg, "hook_text_size", 11f)
        tv.setTextColor(ConfigBridge.intOf(cfg, "hook_text_color", Color.WHITE))

        // 基准位置：时钟右侧（side=0）或左侧（side=1）
        val side = ConfigBridge.intOf(cfg, "hook_side", 0)
        val clockIdx = parent.indexOfChild(clock)
        val targetIdx = if (clockIdx >= 0) clockIdx + if (side == 1) 0 else 1 else -1
        val currentIdx = parent.indexOfChild(tv)
        if (targetIdx >= 0 && currentIdx != targetIdx) {
            runCatching { parent.removeView(tv) }
            runCatching { parent.addView(tv, targetIdx.coerceAtMost(parent.childCount)) }
        }

        // x/y 偏移（px，translation 不破坏布局流）
        val ox = ConfigBridge.intOf(cfg, "hook_offset_x", 0).toFloat()
        val oy = ConfigBridge.intOf(cfg, "hook_offset_y", 0).toFloat()
        tv.translationX = ox
        tv.translationY = oy

        // 智能避让：自动往左让出右侧系统图标空间（当偏移为 0 时生效）
        val avoid = ConfigBridge.boolOf(cfg, "hook_avoid", true)
        if (avoid && ox == 0f) {
            val statusRoot = findStatusBarRoot(parent) ?: parent
            val rightEdge = statusRoot.right - statusRoot.paddingRight
            val iconsLeft = findRightmostIconLeft(statusRoot, clock, tv)
            val target = iconsLeft - dp(ctx, 4)
            if (tv.width > 0 && target < tv.left) {
                tv.translationX = (target - tv.left).toFloat()
            }
        }
    }

    /** 状态栏根容器（向上找到 DecorView 或最大容器） */
    private fun findStatusBarRoot(v: ViewGroup): ViewGroup? {
        var cur: ViewGroup? = v
        var root: ViewGroup? = v
        var guard = 0
        while (cur != null && guard++ < 10) {
            root = cur
            cur = cur.parent as? ViewGroup
        }
        return root
    }

    /** 找出当前注入点右侧的第一个图标左边界（避让用） */
    private fun findRightmostIconLeft(root: ViewGroup, clock: View, tv: TextView): Int {
        val tvRight = tv.right + tv.translationX.toInt()
        var minLeft = root.right - root.paddingRight
        var found = false
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child === tv || child === clock) continue
            if (child.visibility != View.VISIBLE) continue
            if (child.right <= tvRight) continue
            if (child.left < minLeft) {
                minLeft = child.left
                found = true
            }
        }
        return if (found) minLeft else root.right - dp(root.context, 24)
    }

    /** SystemUI 进程内用系统权限读 BatteryManager，无需 root */
    private fun readPower(ctx: Context, cfg: Map<String, String>): String {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val bintent = ctx.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val mv = bintent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val cap = bintent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val temp = bintent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val ma = if (cur == Int.MIN_VALUE) 0 else cur
            val w = ma / 1000.0 * (mv / 1000.0)

            val parts = mutableListOf<String>()
            if (ConfigBridge.boolOf(cfg, "hook_show_power", true)) {
                parts.add(if (w >= 10) String.format("%.1fW", w) else String.format("%.2fW", w))
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_current", false)) {
                parts.add(if (kotlin.math.abs(ma) >= 1000) String.format("%.2fA", ma / 1000.0) else "${ma}mA")
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_voltage", false)) {
                parts.add(String.format("%.2fV", mv / 1000.0))
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_temp", false) && temp > 0) {
                parts.add(String.format("%.1f°C", temp / 10.0))
            }
            if (ConfigBridge.boolOf(cfg, "hook_show_level", true)) {
                parts.add("$cap%")
            }
            if (parts.isEmpty()) "--" else parts.joinToString(" ")
        } catch (e: Exception) {
            "--W"
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
