# 电池功率悬浮窗（BatteryPowerOverlay）开发文档

> 目标设备：一加 Ace 6T（ColorOS，Android 15+，已 root）
> 项目类型：Android 应用（Kotlin），双模式：悬浮窗 + LSPosed 状态栏 Hook

---

## 1. 功能需求

### 模式一：悬浮窗（默认，免 Hook）
- 在任意界面显示**实时电池功率**悬浮窗
- 显示内容可自定义：功率（W）、电流（mA）、电压（mV）、电池百分比
- 悬浮窗可**自由拖动**、位置记忆、贴边
- 样式可调：字体大小、透明度、背景、刷新频率（0.5s / 1s / 2s）
- 点击悬浮窗展开详情或快速设置

### 模式二：状态栏 Hook（LSPosed 模块）
- 作为 Xposed 模块，hook SystemUI，在**状态栏时钟旁注入功率显示**
- 不走悬浮窗，与系统 UI 融为一体（类似流量监控软件的网速显示）
- 可配置显示在时钟左侧/右侧、显示项、字体颜色大小
- 需要 LSPosed 中启用模块并重启 SystemUI

### 通用
- root 模式下读取系统电池节点，**精度最高、刷新最快**
- 无 root 时降级用 BatteryManager（部分机型拿不到瞬时电流则为 0）
- 前台服务保活，低耗电

---

## 2. 电池功率读取原理

**功率 = 电流 × 电压**

| 数据源 | 方式 | 精度 | 要求 |
|---|---|---|---|
| `/sys/class/power_supply/battery/current_now`（μA）+ `voltage_now`（μV） | root（su）读取 | **最高**，1s 级刷新 | root |
| `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` | Java API | 中，受系统限制 | `READ_BATTERY_STATS`（普通 app 常为 0） |
| Hook 模式下 SystemUI 进程内 BatteryManager | Java API（跑在系统进程） | 高（系统签名权限） | LSPosed 模块 |

- **root 模式**：`su -c cat /sys/class/power_supply/battery/current_now`，输出单位 μA，除以 1,000,000 得 A；`voltage_now` 单位 μV，除以 1,000,000 得 V。功率 W = A × V。
- 一加/OPPO 设备电池节点路径稳定为 `battery/current_now`、`battery/voltage_now`，兼容 OPPO 部分机型路径 `battery/current_avg`。

---

## 3. 技术方案

### 3.1 架构
```
┌─────────────────────────── BatteryPowerOverlay ───────────────────────────┐
│                                                                           │
│  MainActivity ──► 设置/开关/模式选择                                        │
│      │                                                                     │
│      ├──► [模式一] OverlayService（前台服务）                                │
│      │         ├── BatteryReader（root su 读取 / 降级 BatteryManager）      │
│      │         └── BatteryOverlayView（TYPE_APPLICATION_OVERLAY 悬浮窗）    │
│      │                                                                     │
│      └──► [模式二] Xposed 模块（作用域 SystemUI）                            │
│                ├── HookEntry（LSPosed 入口）                                │
│                └── SystemUIHook（hook 状态栏 Clock，注入功率 TextView）      │
│                      └── 进程内 BatteryManager 读取（系统权限）              │
└───────────────────────────────────────────────────────────────────────────┘
```

### 3.2 悬浮窗实现（模式一）
- `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW` 权限
- 前台服务（`FOREGROUND_SERVICE_SPECIAL_USE`）承载，`onMove` 拖动，`SharedPreferences` 记忆位置
- Handler + `postDelayed` 定时刷新（避开重复起线程）

### 3.3 Hook 状态栏实现（模式二）
- LSPosed 模块（`de.robv.android.xposed:api:82`），`assets/xposed_init` 声明入口
- Hook 点：`com.android.systemui.statusbar.policy.Clock` 的 `onAttachedToWindow`
  - ColorOS 兼容：`com.oplus.systemui.statusbar.policy.Clock`（一加 ColorOS 实际类名，运行时装在则取之）
- 在 Clock 的父容器 `onLayout` 后插入自定义 `TextView`（时钟右侧），注册 `BatteryManager` 广播刷新
- 数据源：模块代码运行在 SystemUI 进程（系统签名），`BatteryManager.getIntProperty(CURRENT_NOW)` 可取真实电流
- 模块与主 app 同 APK：LSPosed 只加载 `xposed_init` 声明的类，主 app 其余逻辑不受影响

### 3.4 root 通道
- `libsu`（topjohnwu）库封装 `su -c`，避免裸 ProcessBuilder 的坑
- 首次使用引导授权（Magisk 弹窗），失败自动降级 BatteryManager
- 所有读操作带超时，异常静默降级

---

## 4. 权限清单

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 前台服务保活 |
| `POST_NOTIFICATIONS` | 前台服务通知（Android 13+） |
| `READ_BATTERY_STATS` | BatteryManager 降级方案 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启（可选开关） |

Hook 模式不需要额外权限（模块代码跑在 SystemUI 进程）。

---

## 5. 项目结构

```
BatteryPowerOverlay/
├── app/
│   ├── src/main/
│   │   ├── java/com/jakliuyuy/batterypower/
│   │   │   ├── MainActivity.kt            # 主界面：模式选择、开关、设置
│   │   │   ├── overlay/OverlayService.kt  # 悬浮窗前台服务
│   │   │   ├── overlay/BatteryOverlayView.kt
│   │   │   ├── battery/BatteryReader.kt   # 统一数据源（root/BatteryManager 自动降级）
│   │   │   ├── battery/RootHelper.kt      # libsu 封装
│   │   │   ├── hook/HookEntry.kt          # Xposed 入口（xposed_init）
│   │   │   ├── hook/SystemUIHook.kt       # 状态栏注入
│   │   │   └── settings/SettingsStore.kt  # 配置持久化
│   │   ├── res/…                          # 布局/图标/字符串
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/…                               # wrapper
├── build.gradle.kts / settings.gradle.kts
└── .github/workflows/build-apk.yml        # GitHub Actions 自动打包
```

## 6. 构建打包（GitHub Actions）

- 触发：push 到 main / 手动 workflow_dispatch / 打 tag
- 环境：ubuntu-latest + JDK 17 + Android SDK（`android-34`）
- 产物：**Debug APK（免签名直接装）+ Release APK（自带签名）**
- 签名：构建时生成 keystore 并写入仓库 secrets（`KEYSTORE_BASE64` / `KEYSTORE_PASS` / `KEY_ALIAS`），APK 可直接安装
- Artifact 上传，下载即装

## 7. 安装使用

1. 安装 APK → 授予悬浮窗权限（设置-应用-显示在其他应用上层）
2. 开启"悬浮窗模式" → 拖动到目标位置
3. （可选）Hook 模式：LSPosed 中启用模块 → 勾选 SystemUI → 重启 SystemUI
4. 确认 root 授权（Magisk 弹窗），不授权自动降级

## 8. 兼容性与风险

- ColorOS 每个大版本 SystemUI 类名可能有变化，Hook 点做了双类名 fallback；若失效需按机型微调（提供 hook 点可配置）
- 悬浮窗模式为通用方案，**任何 Android 8+ 设备可用**（免 root 也能跑，精度降低）
- 读电池节点是只读操作，无刷写风险；root 权限仅用于读取
- 前台服务常驻耗电约 1-3%/天（1s 刷新）

## 9. 交付物

- 源码仓库（GitHub：Jakliuyuy/BatteryPowerOverlay）
- GitHub Actions 自动构建 APK
- 本机调试产物 + Actions artifact 下载
