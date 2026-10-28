# Project: BatteryPowerOverlay (一加 Ace 6T / ColorOS 17 电池悬浮窗与状态栏模块全链路修复)

## Architecture
- **Hook 目标进程**：`com.android.systemui`（通过 LSPosed 框架在 `handleLoadPackage` 中注入）
- **数据通道**：
  - ContentProvider (`ConfigProvider`)：主 App 通过 Device Protected Storage 暴露配置，SystemUI 1 秒拉取与 ContentObserver 推送
  - BatteryReader：直读内核节点 `/sys/class/power_supply/battery/power_now`（μW）及 `current_now`（智能判定 mA/μA），KernelSU 下复用常驻 su 会话
- **状态栏展示架构 (Mode A / Mode B)**：
  - Mode A (独立覆盖图层)：挂载于原生状态栏根容器 `PhoneStatusBarView`，时钟位置像素级拟合与颜色字体 1:1 克隆
  - Mode B (并排流式图层)：作为时钟直接子/兄弟节点包装进水平 LinearLayout
- **全屏与游戏沉浸接管引擎 (`ImmersiveOverlayManager`)**：
  - 采用系统独立顶层悬浮窗（`TYPE_APPLICATION_OVERLAY` 2038），注入 `FLAG_NOT_TOUCHABLE`、`FLAG_NOT_FOCUSABLE`、`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，触控完全穿透
  - 沉浸状态机：横屏智能接管、状态栏沉浸隐藏联动、多任务（Recents）手势防抖与锁屏/息屏互斥

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | 实时电池功率悬浮窗 | 在任意界面顶层显示实时功率、电流、电压、温度、电量 | M1 | Survey |
| 2 | 5 种质感外观风格胶囊 | 极简全透、雅致深灰、雾面毛玻璃、简约微光描边、纯净雪白外观 | M1 | Survey |
| 3 | 位置记忆同步落盘 | 拖动悬浮窗松手后即时 commit 写入 SharedPreferences | M1 | Survey |
| 4 | 状态栏独立覆盖图层 (模式 A) | 挂载于状态栏根容器 (PhoneStatusBarView)，微调覆盖通知不挤占物理空间 | M1 | Survey |
| 5 | 状态栏并排流式图层 (模式 B) | 与时钟在同一水平包装器内紧密并排 | M1 | Survey |
| 6 | 状态栏字体 1:1 克隆与拟合 | 提取系统时钟 Typeface (OPlus Sans)、字号、字重、基线，完全融入状态栏 | M1 | Survey |
| 7 | 状态栏颜色与深浅模式自适应 | 实时同步时钟当前文本色与 Alpha，随明暗主题自适应 | M1 | Survey |
| 8 | 状态栏水平/垂直像素微调 | 支持水平 -300px ~ +1300px 跨度，垂直 ±100px 微调 | M1 | Survey |
| 9 | 主状态栏时钟白名单精准识别 | 祖先容器 PhoneStatusBarView 正向特征 + Recents/Taskbar/Keyguard 负向全面排除 | M1 | Survey / R1 |
| 10 | 主状态栏时钟单例锁定机制 | Primary Clock Locking，杜绝后续时钟实例覆盖已挂载主时钟 | M1 | Survey / R1 |
| 11 | 健壮化时钟可见性判定 | 消除未排版尺寸误杀，解除 isShown 祖先链假阴性，保障普通前台稳定常驻 | M1 | Survey / R1 |
| 12 | 全屏沉浸接管窗口类型 2038 | 剔除受 Insets 强行管辖的 2041，首选独立顶层 TYPE_APPLICATION_OVERLAY (2038) | M2 | Survey / R2 |
| 13 | 全屏沉浸触控 100% 穿透 | 注入 FLAG_NOT_TOUCHABLE 与 FLAG_NOT_FOCUSABLE，游戏操作零阻挡 | M2 | Survey / R2 |
| 14 | DisplayCutout 挖孔适配 | 注入 SHORT_EDGES 模式，支持摄像头挖孔区几何对齐 | M2 | Survey / R2 |
| 15 | 沉浸状态机手势防抖与多任务过滤 | 过滤底部上滑 Recents 手势转场，横屏智能接管，消除多任务卡片异常反向冒出 | M2 | Survey / R2 |
| 16 | 锁屏与息屏互斥 | 息屏与锁屏界面自动隐藏浮层与挂起轮询，杜绝幽灵浮层 | M2 | Survey / R2 |
| 17 | ContentProvider 实时 IPC | 主 App 导出 ConfigProvider，SystemUI 1 秒热拉取与 ContentObserver 推送 | M1 | Survey |
| 18 | Device Protected Storage 缓存 | Direct Boot 设备保护存储与 SystemUI 镜像，开机未解锁不丢配置 | M1 | Survey |
| 19 | power_now 节点直读与单位归一化 | 智能判定 mA/μA，支持双电芯倍率与极性翻转 | M3 | Survey |
| 20 | 常驻 su Shell 会话复用 | 兼容 KernelSU，常驻 su 会话复用与心跳保活 | M3 | Survey |
| 21 | 自动化单元测试套件 | 覆盖功率计算、状态机、可见性判定与配置跨进程解析 | M3 | Survey / R3 |
| 22 | Gradle 构建与签名产物 | 自动化编译 Debug 与 Release APK，自动回退有效签名 | M3 | Survey / R3 |
| 23 | Git 提交与 GitHub Release 产物发布 | 代码提交推送远程，通过 gh CLI 更新 Release 产物挂载 | M3 | Survey / R3 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | 状态栏时钟可见性与常驻功率修复 (R1) | 改造 `SystemUIHook.kt`：实现主时钟识别白名单与 Primary Clock Locking；重构 `isClockVisibleOnScreen`；优化根容器挂载点至 `PhoneStatusBarView`；确保普通桌面与常规应用下 100% 稳定常驻显示 | none | DONE |
| M2 | 全屏与游戏沉浸接管及手势防抖修复 (R2) | 改造 `ImmersiveOverlayManager.kt` 与沉浸触发状态机：彻底剔除 2041，首选 2038 (`TYPE_APPLICATION_OVERLAY`)；增加多任务（Recents）手势转场防抖与横屏优先接管；消除滑动卡片反向冒出 | M1 | DONE |
| M3 | 自动化验证、构建与 GitHub Release 交付 (R3) | 补充单元测试；运行 `./gradlew test` 与 Gradle Release 打包；通过 Git 提交推送至 GitHub 并使用 `gh release` 更新发布 Release APK 产物 | M1, M2 | IN_PROGRESS |

## Interface Contracts
### SystemUIHook ↔ ImmersiveOverlayManager
- 签名：`ImmersiveOverlayManager.update(context: Context, powerText: String, cfg: Bundle, anchorClock: View?, shouldShowImmersive: Boolean)`
- 职责隔离：
  - `SystemUIHook` 负责主状态栏时钟的挂载、可见性判定 `isClockVisibleOnScreen`、以及根据横屏/全屏/非锁屏/非多任务手势综合评估 `shouldShowImmersive`。
  - `ImmersiveOverlayManager` 负责 2038 顶层悬浮窗的生命周期、动态 Measure/Layout、触控穿透 Flags 与样式更新。
- 窗口类型约定：首选 `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`（2038），备选 `TYPE_SYSTEM_OVERLAY`（2006），禁止使用 `2041`。

### SystemUIHook ↔ ConfigBridge
- 签名：`ConfigBridge.query(context: Context): Bundle`
- 签名：`ConfigBridge.boolOf(bundle: Bundle, key: String, default: Boolean): Boolean`
- 职责：配置跨进程获取，缓存未变更时避免 GC 抖动。

## Code Layout
- `app/src/main/java/com/jakliuyuy/batterypower/hook/SystemUIHook.kt`：核心 Hook 逻辑、主时钟白名单过滤、可见性判定与状态栏嵌入 (M1, M2 核心修改)
- `app/src/main/java/com/jakliuyuy/batterypower/hook/ConfigBridge.kt`：跨进程配置查询桥梁
- `app/src/main/java/com/jakliuyuy/batterypower/ui/HookSettingsActivity.kt`：状态栏与沉浸配置 UI
- `app/src/main/java/com/jakliuyuy/batterypower/data/SettingsStore.kt`：配置项定义与持久化
- `app/src/test/java/com/jakliuyuy/batterypower/`：自动化测试目录 (M3 扩展测试)
