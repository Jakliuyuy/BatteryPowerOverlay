package com.jakliuyuy.batterypower.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed/LSPosed 入口（assets/xposed_init 声明）。
 * 仅作用于 SystemUI。
 */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        SystemUIHook.hook(lpparam)
    }
}
