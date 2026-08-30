# BatteryPowerOverlay v2.0 详细开发文档（问题分析与重构方案）

> 版本：v2.0 重构计划 | 状态：待评审，未动代码
> 目标设备：一加 Ace 6T（ColorOS 15 / Android 15，root + LSPosed）

---

## 一、当前问题清单（按严重度）

| # | 现象 | 严重度 | 状态 |
|---|---|---|---|
| P0 | Hook 位置与时钟重合，位置/颜色/温度设置全部不生效 | 致命 | 根因已定位 |
| P0 | 悬浮窗恒显示 0.00W（状态栏读数正常） | 致命 | 根因已定位 |
| P1 | 位置只能"时钟左/右"，无法精确控制 | 中 | 需重构 |
| P2 | 悬浮窗温度项存在但读取依赖 root，同样为 0 | 中 | 随 P0 一起修 |

---

## 二、根因分析（技术定位）

### 2.0 目标机型调研结论（2026-08 实测资料）

| 项目 | 值 | 来源/影响 |
|---|---|---|
| 机型 | 一加 Ace 6T（型号代码 PLR110） | 系统截图 |
| 系统 | **Android 16**，ColorOS 16（版本 PLR110_16.0.9.400 CN01B40P02） | 系统截图 |
| 内核 | **6.12.38**（新内核） | 系统截图 |
| 基带 | Q_V1_P14 | 系统截图 |
| SoC | **第五代骁龙8 = Snapdragon 8 Elite 2（SM8850）** | 官方参数 |
| 电池 | **8300mAh 冰川电池（OPPO 系大电池，大概率双电芯串联）** | 官方参数 → 影响功率换算 |

**关键推论**：
1. **电池节点**：SM8850 高通平台沿用标准 `power_supply` sysfs（内核 6.12 未移除），`/sys/class/power_supply/battery/current_now` 大概率存在；但 OPPO 双电芯机型常见 `battery` + `battery_2`（或 `main-battery`/`dual-battery`）双节点——**若双电芯串联，单节点 current_now 只代表单电芯电流，功率换算需 ×2 或按厂商节点语义调整**。必须实测节点内容确认（诊断面板）。
2. **SystemUI 时钟类名**：ColorOS 16 未见公开变更资料，仍按 `com.oplus.systemui.statusbar.policy.Clock` 优先、AOSP 兜底探测；**同时增加"hook 命中日志"**（hook 成功后写入日志/通知），类名变化可远程定位。
3. **LSPosed 兼容性**：用户实测 hook 已在 SystemUI 生效（状态栏有读数）→ 框架链路 OK，配置失效与 LSPosed 无关，坐实 XSharedPreferences 根因。
4. **温度节点**：内核 6.12 的 battery/temp 仍为标准节点（0.1°C），root 可读；降级路径用 sticky 广播 EXTRA_TEMPERATURE（系统 API 隐藏，广播可用）。

### 2.1 为什么 Hook 的位置/颜色/温度"全都不生效" —— 配置根本没传过去

**现象**：你看到的永远是默认值（白色、11sp、时钟右侧、不显示温度）——因为 SystemUI 进程里读到的一直是默认配置。

**根因**：当前实现用 `XSharedPreferences` 跨进程读主 app 的配置，**这在 Android 11+ / LSPosed 上是不可靠的**：

1. 主 app 的 SharedPreferences 文件（`/data/user/0/com.jakliuyuy.batterypower/shared_prefs/battery_power_prefs.xml`）权限是 `0600`（仅属主可读）
2. SystemUI 进程 UID 是 `system(1000)`，与普通 app（UID 10xxx）**不同 UID**，且被 SELinux 隔离
3. `XSharedPreferences` 的跨进程读取依赖 Xposed 框架注入，**LSPosed 环境下绝大多数情况返回空值**（已知兼容性缺陷，社区普遍报告）
4. 结果：`prefs()` 每次 reload 拿到的全是默认值 → 位置永远插时钟右侧（index+1）、颜色永远白色、温度永远关闭

**你观察到的三个"不生效"完全吻合这一根因**——不是 hook 没跑，是配置通道断了。

### 2.2 为什么悬浮窗 0.00W 而状态栏正常

**状态栏正常**：hook 代码运行在 SystemUI 进程（系统签名），`BatteryManager.getIntProperty(CURRENT_NOW)` 是系统权限，能拿到真实电流。

**悬浮窗恒 0**：两条路全断了：
1. **root 路径失败**（本该是主数据源）：
   - `ProcessBuilder("su", "-c", ...)` 每次调用都拉起新 su 会话：Magisk 首次弹窗授权时非交互场景会卡住/超时（当前 2 秒超时太短）
   - 一加 ColorOS 电池节点路径可能不是 `battery/current_now`（部分机型是 `current_avg` 或路径不同），当前硬编码单一路径
   - su 执行失败 → 返回 null → 静默降级
2. **降级路径必为 0**：`BatteryManager.CURRENT_NOW` 需要 `BATTERY_STATS` 权限，该权限是 **signature|privileged 级**，普通 app 声明了也拿不到 → `getIntProperty` 返回 `MIN_VALUE` → 电流恒 0 → 功率恒 0.00W

### 2.3 为什么位置会和时钟"重合"

当前实现 `parent.addView(tv, indexOfChild(clock)+1)` 依赖：
- ColorOS 状态栏时钟的父容器是 `LinearLayout` 才有效
- 实际 ColorOS 15 时钟父容器结构复杂（可能是 `FrameLayout` 或带 weight 的布局），index 插入不可控
- 且没有 margin/偏移控制，无法避开旁边已有的图标（Wi-Fi、信号、电量图标）

**结论：需要重做位置控制，提供坐标微调 + 自动避让。**

---

## 三、v2.0 重构架构

### 3.1 配置通道：XSharedPreferences → ContentProvider（核心修复）

**设计**：主 app 提供 `exported` ContentProvider，SystemUI 进程通过标准 `ContentResolver.query` 拉取配置。跨进程标准 IPC，不依赖任何框架特性，100% 可靠。

```
┌───────────── 主 app 进程 (uid 10xxx) ─────────────┐
│  MainActivity 修改设置 → SettingsStore(SP)        │
│        │                                          │
│        ▼                                          │
│  ConfigProvider (exported=true)                   │
│  query() 实时读 SP 返回 JSON/矩阵                  │
└───────────────┬───────────────────────────────────┘
                │ ContentResolver.query（标准 IPC）
┌───────────────▼───────────────────────────────────┐
│  SystemUI 进程 (uid 1000)                         │
│  SystemUIHook → ConfigBridge.query()              │
│  每帧(1s)拉取一次 → 位置/颜色/字号/显示项          │
└───────────────────────────────────────────────────┘
```

- **实时性**：hook 每 1 秒 query 一次，改设置 1 秒内生效
- **安全性**：配置无敏感数据，`exported=true` 无需权限；如需加固可加自定义 readPermission（后续迭代）
- **兼容性**：Android 8+ 全版本可用，与 LSPosed 版本无关

### 3.2 Hook 位置控制：坐标微调 + 自动避让

**方案 A（主）：x/y 偏移微调**（用户明确要求）
- 设置项：`水平偏移 px`（-200 ~ +200）、`垂直偏移 px`（-100 ~ +100），步进 1px
- 注入时：`FrameLayout.LayoutParams` 设 `gravity=END|CENTER_VERTICAL` + `marginEnd=偏移量`，或者直接对 view 设 `translationX/translationY`（不破坏布局流，实时生效）
- 默认值：时钟右侧 +16px 起（错开时钟文字）

**方案 B（辅）：智能避让**
- hook 成功后枚举状态栏根容器所有子 View 的 bounding box（`getLocationOnScreen` + `getWidth/Height`）
- 找到状态栏右侧第一个图标（Wi-Fi/信号/电量）的左边界，注入位置取 `该边界 - 文本宽度 - 8px`
- 每帧跟随布局变化重算（监听 `OnLayoutChangeListener`）

两个方案叠加：默认智能避让，用户可再手动 ±偏移微调。

### 3.3 root 电池读取：多路径 + 诊断 + 交互授权

**多路径候选（按序尝试，取第一个成功的）**：
```
/sys/class/power_supply/battery/current_now      # 标准路径
/sys/class/power_supply/battery/current_avg      # 部分 OPPO/一加机型
/sys/class/power_supply/battery/charge_now       # 少数机型
/sys/class/qcom-battery/current_now              # 高通方案机型
/sys/class/power_supply/main-battery/current_now # 双电池机型
```
同路径族：voltage_now / temp / capacity

**su 调用改造**：
- 超时从 2s → 5s；`su -c` 加 `-s sh` 指定 shell 减少兼容问题
- 首次检测：启动时探测一次 `su -c id` 确认 root 可用（Magisk 弹窗在此次授权，用户点"始终允许"后不再弹）
- 失败自动切下一候选路径；全部失败才降级
- **诊断模式**：设置页加"数据诊断"按钮，显示每个候选路径的读取结果（有/无/值），一加机型路径差异一目了然

**兜底**：root 全失败时，悬浮窗显示明确提示"root 读取失败，请查看诊断"，不再静默显示 0.00W 误导

### 3.4 悬浮窗与 Hook 数据源统一

`BatteryReader` 重构为分层：
```
BatteryReader.read(ctx)
 ├─ 1. RootChannel（su 多路径）→ 成功返回 Sample（含温度）
 ├─ 2. SystemChannel（BatteryManager 电流 + sticky 广播电压/温度/电量）
 └─ 3. 失败标记（source="failed"），UI 显示 "--W" + 提示
```
悬浮窗与 Hook 共用同一套 `Sample` 数据模型，行为一致。

---

## 四、模块设计（v2.0）

### 4.1 新增：ConfigProvider（主 app）
```
class ConfigProvider : ContentProvider {
    // authority: com.jakliuyuy.batterypower.config
    query() → MatrixCursor: 
        [key, value] 行集：hook_side/hook_offset_x/hook_offset_y/
        hook_text_color/hook_text_size/hook_show_*/overlay_*
}
```

### 4.2 新增：ConfigBridge（hook 侧，SystemUI 进程）
```
object ConfigBridge {
    fun query(ctx): Map<String, String>   // ContentResolver.query，失败返回空 Map → 用内置默认
}
```

### 4.3 重构：SystemUIHook
- 注入点不变（ColorOS/Oplus 双类名探测）
- 注入视图：TextView + `LayoutParams(gravity=END|CENTER_VERTICAL)`
- 每帧流程：`query 配置 → 应用颜色/字号/显示项 → 计算避让位置 → translationX/Y 偏移`
- 文本组装：功率/电流/电压/温度/电量（可勾选）

### 4.4 重构：RootHelper
- 路径候选数组 + 逐路径探测
- `su` 会话复用（保持一个常驻 su shell 进程，避免每帧拉起）
- 5s 超时 + 可诊断

### 4.5 重构：BatteryReader
- 统一 Sample 模型 + source 标记
- root → system → failed 三级

### 4.6 设置界面新增
| 设置项 | 控件 | 说明 |
|---|---|---|
| Hook 水平偏移 | SeekBar -200~+200px | 实时生效 |
| Hook 垂直偏移 | SeekBar -100~+100px | 实时生效 |
| Hook 智能避让 | Switch | 默认开 |
| 数据诊断 | 按钮 + 结果面板 | 逐路径显示读取结果 |
| root 状态 | 文本 | 已获取/未获取 + su 探测结果 |

---

## 五、实现计划

| 阶段 | 内容 | 验证方式 |
|---|---|---|
| 1 | ConfigProvider + ConfigBridge + hook 改造（颜色/位置/温度生效） | 用户实机：改颜色/位置 1s 内生效 |
| 2 | 位置：智能避让 + x/y 偏移 | 实机截图对比不与图标重合 |
| 3 | RootHelper 多路径 + su 会话复用 + 诊断面板 | 实机：悬浮窗出现真实功率（非 0） |
| 4 | BatteryReader 统一 + 失败提示 | 悬浮窗/Hook 读数一致 |
| 5 | 打包发布 v2.0 | Actions artifact |

## 六、验收标准

1. ✅ Hook 颜色、字号、显示项修改 ≤1s 生效（不用重启 SystemUI）
2. ✅ Hook 位置可 x/y 微调，默认不与状态栏图标/时钟重合
3. ✅ 悬浮窗显示真实功率（root 路径），与状态栏一致
4. ✅ 温度显示正常（root 或系统路径）
5. ✅ 无 root 时悬浮窗明确提示，不静默显示 0.00W

---

## 七、待用户确认

1. **配置通道**采用 ContentProvider 方案（推荐，最可靠）还是再试 LSPosed 增强版 XSharedPreferences？
2. **root 诊断**：是否允许我下一步给你一个"诊断版"临时 APK（只读路径探测，输出节点内容），以便确认一加机型的真实电池节点路径？——这是悬浮窗 0.00W 能否根治的关键数据
3. 位置微调范围 ±200px 是否够用？
