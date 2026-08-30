# BatteryPowerOverlay — 电池功率悬浮窗

一加 Ace 6T (ColorOS, root) 实时电池功率显示，双模式，Scene 迷你监视器风格。

## 模式一：全透明悬浮窗
- 全透明无背景，`TYPE_APPLICATION_OVERLAY` 悬浮窗
- 显示项可勾选：功率 W / 电流 mA / 电压 V / 温度 °C / 电量 %
- 可拖动、位置记忆、文字颜色 8 色可选、字体大小可调、刷新频率可调
- root 读 `/sys/class/power_supply/battery/*`（含温度），免 root 自动降级 BatteryManager

## 模式二：状态栏 Hook（LSPosed）
- hook SystemUI Clock 注入显示（兼容 ColorOS `com.oplus...` 与 AOSP 双类名）
- 显示位置：时钟左侧 / 右侧
- 显示项、文字颜色、字号全部可自定义
- 配置修改 1 秒内实时生效（XSharedPreferences 跨进程热读，无需重启 SystemUI）
- 代码运行在 SystemUI 进程，系统权限直读 BatteryManager

## 构建
```bash
./gradlew assembleRelease   # 本地构建（fallback debug 签名）
```
GitHub Actions 自动打包（push main / workflow_dispatch），Release 正式签名，产物在 artifact。

## 使用
1. 安装 APK，授予悬浮窗权限
2. 打开悬浮窗开关（root 弹窗授权）
3. Hook 模式：LSPosed 启用模块 → 勾选 SystemUI → 重启 SystemUI 一次，之后配置实时生效
