# BatteryPowerOverlay — 电池功率悬浮窗

一加 Ace 6T (ColorOS, root) 实时电池功率显示，双模式：

## 模式一：悬浮窗
- `TYPE_APPLICATION_OVERLAY` 悬浮窗，显示功率 W / 电流 mA / 电压 V / 电量 %
- 可拖动、位置记忆、字体/透明度/刷新频率可调
- root 读 `/sys/class/power_supply/battery/current_now`，免 root 自动降级 BatteryManager

## 模式二：状态栏 Hook（LSPosed）
- hook SystemUI Clock 注入功率显示（兼容 ColorOS `com.oplus...` 与 AOSP 双类名）
- 代码运行在 SystemUI 进程，系统权限直读 BatteryManager

## 构建
```bash
./gradlew assembleRelease   # 本地构建
```
GitHub Actions 自动打包（push main / workflow_dispatch），产物在 artifact。

## 使用
1. 安装 APK，授予悬浮窗权限
2. 打开悬浮窗开关（root 弹窗授权）
3. Hook 模式：LSPosed 启用模块 → 勾选 SystemUI → 重启 SystemUI
