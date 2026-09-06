# 电池功率悬浮窗 App —— 开发需求文档

> 版本：v2.3（当前） | 更新：2026-09-01
> 项目仓库：github.com/Jakliuyuy/BatteryPowerOverlay（私有）
> 目标设备：一加 Ace 6T（PLR110，Android 16 / ColorOS 16，KernelSU root）

---

## 1. 项目概述

开发一款 Android 应用，在**任意界面悬浮显示电池实时功率**（含电流、电压、温度、电量），并支持通过 LSPosed Hook 在**系统状态栏**内嵌显示。参照 Scene 迷你监视器的风格：全透明、紧凑、可高度自定义。

**核心价值**：实时掌握充电/放电功率，直观感知手机耗电与快充状态。

---

## 2. 功能需求

### 2.1 模式一：全透明悬浮窗（默认）

| 功能 | 说明 | 状态 |
|---|---|---|
| 实时功率显示 | 功率 W（= 电流×电压，root 直读内核节点） | ✅ |
| 显示项自定义 | 功率 / 电流 / 电压 / 温度 / 电量 任意勾选 | ✅ |
| 全透明样式 | 无背景、无边框，Scene 风格 | ✅ |
| 拖动定位 | 长按拖动，位置自动记忆（同步落盘不丢失） | ✅ |
| 文字颜色 | 8 预设色 + **HSV 无极调色板** | ✅ |
| 字体大小 | 8~28sp 滑块 | ✅ |
| 刷新频率 | 0.5s / 1s / 2s | ✅ |
| 单位开关 | 显示 W/mA/V/°C/% 或仅数字 | ✅ |
| 前台服务保活 | START_STICKY，通知常驻 | ✅ |

### 2.2 模式二：状态栏 Hook（LSPosed）

| 功能 | 说明 | 状态 |
|---|---|---|
| 状态栏内嵌显示 | hook SystemUI 时钟容器，注入功率文本 | ✅ |
| **覆盖优先级** | 注入状态栏根容器 + elevation=100，**覆盖所有通知图标** | ✅ |
| 显示位置 | 时钟左侧/右侧 + **水平 ±2000px / 垂直 ±1000px 偏移**（滑块 + 10px 步进按钮） | ✅ |
| 文字颜色 | 8 预设色 + HSV 无极调色板 | ✅ |
| 字号 | 8~28sp 滑块 | ✅ |
| 刷新频率 | 0.5s / 1s / 2s / 3s / 5s | ✅ |
| 显示项 | 功率 / 电流 / 电压 / 温度 / 电量 任意勾选 | ✅ |
| 单位开关 | 与悬浮窗共用 | ✅ |
| 显示开关 | 一键显示/隐藏状态栏功率 | ✅ |
| 配置热更新 | 改配置 **1 秒内实时生效**（无需重启 SystemUI） | ✅ |

### 2.3 通用

| 功能 | 说明 | 状态 |
|---|---|---|
| App 内重启 SystemUI | root pkill，一键重载 Hook（升级 APK 后使用） | ✅ |
| 数据诊断页 | 只读探测电池节点/su 状态，定位机型差异 | ✅ |
| 配置持久化 | ContentProvider 实时通道 + SystemUI 磁盘缓存（杀后台/重启不丢配置） | ✅ |
| root 自动降级 | 无 root 时用 BatteryManager + 系统广播 | ✅ |

---

## 3. 技术方案

### 3.1 电池数据源（功率 = 电流 × 电压）

| 优先级 | 数据源 | 说明 |
|---|---|---|
| 1 | `power_now` 节点直读 | μW 直接是功率，无单位歧义（实测存在） |
| 2 | `current_now`(mA) × `voltage_now`(μV) | 实测 PLR110 单位是 **mA**（非 μA），已按此换算 |
| 3 | BatteryManager + sticky 广播 | 无 root 降级，普通 app 电流可能为 0 |

- **充电符号**：读 `status` 节点判断充/放电，充电显示正功率、放电显示负
- **su 通道**：常驻 su shell 会话复用（KernelSU 兼容），避免每秒拉起进程被限流

### 3.2 Hook 实现

- **注入点**：hook `Clock.onAttachedToWindow`，时钟类双兼容（`com.oplus.systemui.statusbar.policy.Clock` / AOSP）
- **层级**：TextView 挂到状态栏根 FrameLayout，`elevation=100` → 画在所有通知图标之上
- **位置锚定**：屏幕坐标计算时钟边界，marginEnd 控制左右，偏移滑块/按钮微调
- **数据**：hook 代码运行在 SystemUI 进程（系统签名），BatteryManager 可取真实电流

### 3.3 配置通道（跨进程）

```
主 app 进程 (uid 10xxx)               SystemUI 进程 (uid 1000)
SettingsStore(SP) ──► ConfigProvider ──► ConfigBridge(query 1s)
                          │                    │
                          └── ContentProvider IPC（实时）
                          磁盘缓存（SystemUI 自己的 SP，重启不丢）
```

- 替代不可靠的 XSharedPreferences（Android 11+/LSPosed 下读不到 0600 权限的 SP）
- 主 app 被杀 → 磁盘缓存兜底；SystemUI 重启 → 磁盘缓存恢复

---

## 4. 机型适配记录（一加 Ace 6T 实测）

| 项目 | 实测值 | 影响 |
|---|---|---|
| root 框架 | **KernelSU**（非 Magisk） | su 会话复用兼容 |
| 电池节点 | 仅 `battery`，单电芯 | 功率无需 ×2 |
| current_now | **mA** 单位 | 与常见 μA 文档不同，已适配 |
| power_now | 存在（μW） | 最优功率路径 |
| 时钟类名 | ColorOS `com.oplus...` | 双类名探测 |

---

## 5. 版本历史

| 版本 | 内容 |
|---|---|
| v1.0.0 | 基础版：悬浮窗 + hook（XSharedPreferences 配置） |
| v1.1.0 | 全透明悬浮窗、Hook 显示项/颜色/位置 |
| v1.1.1 | 数据诊断页 |
| v2.0 | **重构**：ContentProvider 配置通道 + power_now 直读 + 常驻 su + x/y 偏移 |
| v2.0.3 | 配置持久化到 SystemUI 磁盘缓存 |
| v2.1 | App 内重启 SystemUI + 状态栏开关 + 位置 commit + 单位开关 |
| v2.1.1 | 充电功率符号修正 |
| v2.2 | 功率覆盖通知图标（根容器 + elevation） |
| v2.3 | 状态栏刷新频率 + 偏移 +/- 按钮 + HSV 调色板 |

---

## 6. 使用说明

1. 安装 APK（https://dl.74520.xyz/apk/ 下载，正式签名）
2. 授予悬浮窗权限 → 打开"悬浮窗显示"开关（KernelSU 授权）
3. 悬浮窗拖动定位，App 内调样式
4. Hook 模式：LSPosed 启用模块 → 勾选 SystemUI → App 内点"重启 SystemUI"
5. 之后所有配置修改实时生效

## 7. 构建方式

- 本地：`./gradlew assembleRelease`（JDK 17 + Android SDK 36）
- 自动：GitHub Actions（push main 自动构建，Release 正式签名）
- 产物：Actions Artifact + 服务器下载站 dl.74520.xyz/apk/
