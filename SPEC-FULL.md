# 电池功率悬浮窗 App —— 完整需求与开发文档（交付版）

> 用途：本文件自包含，可直接作为任何 AI 助手的开发需求书
> 目标设备：一加 Ace 6T（型号 PLR110，Android 16 / ColorOS 16，KernelSU root，LSPosed 已装）
> 当前状态：v2.3 已实现并实测通过，本文件为完整规格 + 重构要点

---

# 第一部分：需求文档

## 1. 产品概述

一款 Android 工具类 App，实现两个核心能力：
1. **全透明悬浮窗**：在任意 App 之上显示电池实时功率（W）、电流（mA）、电压（V）、温度（°C）、电量（%）
2. **状态栏 Hook**：通过 LSPosed 在系统状态栏内嵌显示上述数据，风格参照 **Scene 迷你监视器**（紧凑、无背景、高度自定义）

**目标用户**：root 用户，关注充电功率/耗电曲线的极客。

## 2. 功能需求

### 2.1 悬浮窗模式
- [ ] 全透明无背景（透明背景、无边框）
- [ ] 显示项可勾选：功率 / 电流 / 电压 / 温度 / 电量（任意组合）
- [ ] 拖动移动位置，位置持久化记忆（**必须同步落盘**，见避坑 7）
- [ ] 文字颜色：预设色 + **HSV 无极调色板**（色相/饱和度/亮度）
- [ ] 字体大小可调（8~28sp）
- [ ] 刷新频率可调（0.5s / 1s / 2s）
- [ ] 单位可显示/隐藏（"3.46W" 或 "3.46"）
- [ ] 前台服务保活（START_STICKY + 常驻通知）

### 2.2 状态栏 Hook 模式（LSPosed）
- [ ] 状态栏内嵌显示，**优先级最高：覆盖所有通知图标**（见避坑 6）
- [ ] 位置：时钟左侧/右侧 + 水平偏移 ±2000px / 垂直偏移 ±1000px（滑块 + 10px 步进按钮）
- [ ] 显示项、颜色（HSV 调色板）、字号、刷新频率（0.5/1/2/3/5s）全部可配置
- [ ] 一键显示/隐藏开关
- [ ] **配置修改 1 秒内实时生效**（无需重启 SystemUI）
- [ ] App 内提供"重启 SystemUI"按钮（root pkill，加载新模块代码）

### 2.3 通用
- [ ] root 自动降级：无 root 时 BatteryManager + 系统广播
- [ ] 数据诊断页：只读探测电池节点路径与 su 状态
- [ ] 充电/放电符号：充电显示正功率，放电显示负功率

## 3. 验收标准

1. 悬浮窗显示真实功率（±0.1W 精度），充电为正、放电为负
2. 状态栏功率不被任何通知覆盖（通知再多也在最上层）
3. 杀后台、重启 SystemUI 后，配置（颜色/位置/显示项）不丢失
4. 修改配置 ≤1 秒生效
5. 状态栏功率不与时钟文字重叠（可偏移微调解决）

---

# 第二部分：开发文档

## 4. 技术栈

- Kotlin + Android Gradle Plugin 8.9.2 + Gradle 8.11.1 + JDK 17
- minSdk 26 / targetSdk 34 / compileSdk 36
- LSPosed（Xposed API 82，来自 `https://api.xposed.info/` 仓库，compileOnly）
- 依赖：androidx.core / appcompat / material（无其他重依赖）

## 5. 架构

```
┌─────────────── 主 app 进程 (uid 10xxx) ───────────────┐
│ MainActivity（设置/开关/调色板/重启SystemUI/诊断入口）      │
│   ├── OverlayService（前台服务）                        │
│   │     └── BatteryOverlayView（TYPE_APPLICATION_OVERLAY）│
│   ├── BatteryReader（root 优先，降级 BatteryManager）   │
│   │     └── RootHelper（常驻 su shell 会话）            │
│   └── ConfigProvider（exported ContentProvider）       │
└──────────────────┬────────────────────────────────────┘
                   │ ContentProvider IPC（1s 轮询）
┌──────────────────▼────────────────────────────────────┐
│ SystemUI 进程 (uid 1000, 系统签名)                     │
│ HookEntry（Xposed 入口）→ SystemUIHook                 │
│   ├── 注入 TextView 到状态栏根 FrameLayout（elevation=100）│
│   ├── ConfigBridge（拉配置 + 磁盘缓存）                 │
│   └── BatteryManager 直读（系统权限）                  │
└───────────────────────────────────────────────────────┘
```

## 6. 关键实现细节

### 6.1 电池数据读取（重中之重，单位易错）

**实测（PLR110 / Android 16 / 内核 6.12）**：
```
/sys/class/power_supply/battery/current_now = 865     ← 单位是 mA ！不是 μA
/sys/class/power_supply/battery/voltage_now = 4005000 ← μV
/sys/class/power_supply/battery/temp        = 386     ← 0.1°C
/sys/class/power_supply/battery/capacity    = 66      ← %
/sys/class/power_supply/battery/status      = Discharging
/sys/class/power_supply/battery/power_now   = 存在    ← μW，直接就是功率
```
- **优先读 `power_now`**（μW ÷ 1e6 = W），规避电流单位歧义
- fallback：`current_now(mA) ÷ 1000 × voltage_now(μV) ÷ 1e6`
- **充电符号**：status 含 "Charg"/"Full" 时功率取绝对值（节点值为负表示电流流入）
- 单电芯（实测无 battery_2），功率无需 ×2

### 6.2 root 通道（KernelSU 兼容）
- **必须使用常驻 su shell 会话**：`ProcessBuilder("su")` 启动一次，通过 stdin 写命令、stdout 按 marker 分隔读结果
- 每 1 秒拉起新 su 进程会被 KernelSU 限流/超时（实测 2s 超时不够，至少 5s）
- 命令格式：`cmd; echo KOMARI_DONE_${nanoTime}`，读到 marker 即完成

### 6.3 Hook 注入（避免与通知重叠）
- 时钟类名双候选：`com.oplus.systemui.statusbar.policy.Clock`（ColorOS）→ `com.android.systemui.statusbar.policy.Clock`（AOSP），hook `onAttachedToWindow`
- **挂载点**：不要插在时钟父容器（会被通知图标挤掉），要 `findStatusBarRoot` 找到最外层 **FrameLayout**，addView + `elevation=100f` + `translationZ=100f`
- **位置控制**：FrameLayout.LayoutParams + `gravity=END|CENTER_VERTICAL` + marginEnd（用 **getLocationOnScreen 屏幕坐标**计算时钟边界，因为 clock 与 root 坐标系不同）+ 用户偏移
- 兜底：根容器不是 FrameLayout 时降级插时钟旁边

### 6.4 跨进程配置通道（避免配置不生效）
- **禁用 XSharedPreferences**：Android 11+ / LSPosed 下读不到主 app 的 SP（0600 权限），症状是"配置改了没反应，全是默认值"
- **用 ContentProvider**：主 app 注册 exported provider（authority `com.jakliuyuy.batterypower.config`），query 返回 [key,value] MatrixCursor
- **双缓存兜底**：SystemUI 进程内 ① 内存缓存 ② **SystemUI 自己的 SharedPreferences**（`/data/user_de/0/com.android.systemui/` 下，重启不丢）——主 app 被杀、SystemUI 重启都不丢配置
- 失败节流：query 失败 10 秒后再重试，避免每秒狂拉起 provider

### 6.5 LSPosed 模块要点
- `assets/xposed_init` 声明入口类
- **APK 升级后必须重启 SystemUI 才加载新模块代码**（App 内提供 pkill 按钮：`pkill -f com.android.systemui`，system_server 会自动拉起）
- 模块代码运行在 SystemUI 进程，BatteryManager 是系统权限，可直接读真实电流

### 6.6 悬浮窗
- `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW`
- 前台服务 `FOREGROUND_SERVICE_SPECIAL_USE` 承载
- 拖动：onTouch 记录 down/rawX，move 时 updateViewLayout，up 时**同步 commit() 保存位置**（见避坑 7）

## 7. 避坑清单（实测踩过）

| # | 坑 | 症状 | 解法 |
|---|---|---|---|
| 1 | XSharedPreferences 跨进程读取 | 配置改了不生效，全默认值 | ContentProvider IPC |
| 2 | current_now 按 μA 处理 | 功率显示 0.00W（差 1000 倍） | 实测单位是 mA，或直接用 power_now |
| 3 | 每 1s 拉起 su 进程 | root 读取失败 → 悬浮窗 0.00W | 常驻 su 会话复用 |
| 4 | 模块 APK 升级后不重启 | 新功能"不生效" | 重启 SystemUI（App 内按钮） |
| 5 | 插时钟父容器 | 位置不可控、与时钟重叠 | 挂根 FrameLayout + margin 锚定 |
| 6 | 不设 elevation | 通知图标覆盖功率 | elevation=100 + translationZ |
| 7 | SharedPreferences apply() 存位置 | 杀后台位置丢失 | 位置用 commit() 同步落盘 |
| 8 | 无 root 降级 BatteryManager | 电流恒 0 | 提示用户而非静默显示 0.00W |
| 9 | 电压常量 `BATTERY_PROPERTY_VOLTAGE_NOW` | 编译不过 | 新 SDK 已隐藏，改用 sticky 广播 `EXTRA_VOLTAGE` |
| 10 | Kotlin 三引号里写 shell `$f` | 编译报 Unresolved reference | 写成 `${'$'}f` |

## 8. 构建与发布

- 签名：GitHub Actions secrets（KEYSTORE_BASE64 / KEYSTORE_PASS / KEY_ALIAS），无 env 时 fallback debug 签名
- workflow：push main 触发，JDK 17 + android-actions/setup-android，产物 upload-artifact
- 发布：Actions Artifact → 下载后挂到静态服务器（本机 nginx /apk/ 目录）
- 版本管理：v2.3 当前，语义化版本

## 9. 建议的下一步迭代（未实现）

- [ ] 状态栏电池图标级联（替换系统电池图标为功率数字）
- [ ] 悬浮窗历史曲线小窗（迷你折线图）
- [ ] 充电完成/低电量阈值通知
- [ ] 多设备/多电池节点配置模板
- [ ] 深浅色主题跟随
