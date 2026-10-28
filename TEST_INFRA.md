# 电池悬浮窗 (BatteryPowerOverlay) 自动化测试基础设施与测试矩阵 (TEST_INFRA)

## 1. 架构目标与 4-Tier 测试方法论

本项目作为基于 LSPosed 框架注入 Android 核心系统进程（`com.android.systemui`）的高可靠系统级插件，运行环境处于厂商定制 OS（如 ColorOS 17 / 一加 Ace 6T）与 Android 14+ 严格沙箱之中。为彻底杜绝“状态栏功率不显示”、“游戏全屏不显示”、“多任务卡片异常反向冒出”以及“锁屏幽灵浮层”等致命缺陷，本项目建立了基于 **4-Tier 金字塔模型** 的高健壮性自动化测试体系。

```
              ┌──────────────────────────────────────────────┐
              │  Tier 4: Real-World Workload Scenarios       │
              │  (手势转场、全屏游戏进入退出、锁屏息屏全周期) │
              ├──────────────────────────────────────────────┤
              │  Tier 3: Cross-Feature & Contract Integrity  │
              │  (状态栏与沉浸互斥公理、跨进程 IPC、观察者机制) │
              ├──────────────────────────────────────────────┤
              │  Tier 2: Boundary & Corner Conditions        │
              │  (屏幕边缘截断、刷新率钳位、畸形配置输入容错)   │
              ├──────────────────────────────────────────────┤
              │  Tier 1: Feature Coverage (Core Logic)       │
              │  (功率计算、单位格式化、时钟过滤白名单、配置解析)│
              └──────────────────────────────────────────────┘
```

---

## 2. 4-Tier 详细用例体系

### Tier 1: Feature Coverage (核心功能与业务规则单元)
测试系统各独立逻辑模块的确定性行为与基本契约（Happy Path），确保核心业务函数的输入输出完全符合设计规格。

- **1.1 电池与功率数据建模 (`BatteryPowerCalculationTest`)**
  - **TC-1.1.1** 高功率快充格式化：功率 $\ge 10\text{W}$ 格式化保留 1 位小数（如 `+45.4W`）。
  - **TC-1.1.2** 低功率充电格式化：功率 $< 10\text{W}$ 格式化保留 2 位小数（如 `+4.26W`）。
  - **TC-1.1.3** 耗电放电格式化：功率为负值时正确携带负号并保留 2 位小数（如 `-3.85W`）。
  - **TC-1.1.4** 极性翻转逻辑：支持用户配置 `polarity_invert` 反转充放电显示符号。
  - **TC-1.1.5** 单位开关逻辑：验证 `showUnit = true/false` 时功率、电流、电压、温度、电量文本的后缀拼接与脱落。

- **1.2 跨进程配置解析 (`ConfigParsingTest`)**
  - **TC-1.2.1** 缺省回退测试：空字典或未定义键平稳回退至规范定义的初始默认值。
  - **TC-1.2.2** 基础类型反序列化：正确解析 `intOf`, `longOf`, `floatOf`, `boolOf`。
  - **TC-1.2.3** 键名完整性测试：验证 `ConfigProvider.ALL_KEYS` 包含所有受支持的配置项。

- **1.3 状态栏时钟白名单与排版规则 (`StatusBarVisibilityRuleTest`)**
  - **TC-1.3.1** 主状态栏时钟正向识别：常规命名（如 `clock`, `status_bar_clock`）被正确识别。
  - **TC-1.3.2** 锁屏与快捷开关黑名单排除：过滤 `keyguard_clock`, `qs_clock`, `header_clock` 等非主状态栏时钟。
  - **TC-1.3.3** 模式 A 坐标排版推导：时钟右侧模式计算 `clockX + clockWidth + 4dp + ox`，时钟左侧计算 `clockX - tvWidth - 4dp + ox`，垂直居中 `clockY + (clockHeight - tvHeight) / 2 + oy`。

- **1.4 全屏沉浸基础判定 (`ImmersiveStateMachineTest`)**
  - **TC-1.4.1** 沉浸浮层重力与位置推导：居中（`Gravity.TOP or Gravity.CENTER_HORIZONTAL`）、左对齐（带 16dp 安全边距）、右对齐（带 16dp 安全边距）。
  - **TC-1.4.2** 沉浸胶囊外观样式匹配：极简纯文本（`style=0`）与雅致微胶囊（`style=1`）。

---

### Tier 2: Boundary & Corner Conditions (边界条件与极端异常输入)
验证系统在面对异常、畸形、越界以及极端边界条件时的防守表现与平稳自愈能力。

- **2.1 屏幕与视口边界判定 (`StatusBarVisibilityRuleTest`)**
  - **TC-2.1.1** 物理视口移出判定：当 `loc[1] + height <= 0`（向上移出视口）或 `loc[0] + width <= 0`（向左移出视口）时，判定为不可见。
  - **TC-2.1.2** 零尺寸保护：当 View 宽度或高度尚未排版（0 尺寸）时的预排版保护与边界防爆。
  - **TC-2.1.3** 祖先不可见渗透：当任意祖先或自身 `visibility != VISIBLE` 或 `windowVisibility != VISIBLE` 时判定为不可见。

- **2.2 配置解析边界与畸形输入容错 (`CrossProcessConfigTest`)**
  - **TC-2.2.1** 非法数值字符串输入：传入非数字文本（如 `"abc"`, `"true"`, `"--"`）时平稳回退默认值，不抛出 `NumberFormatException`。
  - **TC-2.2.2** 极大数值与负数极值：解析极端大数、负偏移（`-300px ~ +1300px`）和极端垂直偏移（`±100px`）的准确性。
  - **TC-2.2.3** 刷新率安全范围钳位：测试 `refreshMs.coerceIn(300, 5000)`，低于 300ms 强制上提至 300ms（防 GC 爆破），高于 5000ms 截断为 5000ms。
  - **TC-2.2.4** 电量百分比钳位：测试电量数据超出 0~100 范围时的 `coerceIn(0, 100)` 保护。

- **2.3 硬件电流节点单位自适应 (`BatteryPowerCalculationTest`)**
  - **TC-2.3.1** μA 与 mA 自动归一化边界：绝对值 $> 50,000$ 判定为 $\mu\text{A}$ 除以 1000，$\le 50,000$ 保持 $\text{mA}$。
  - **TC-2.3.2** 双电芯倍率翻倍：开启 `double_cell_fix` 时电流与计算功率准确翻倍。

---

### Tier 3: Cross-Feature & Contract Integrity (跨特性协同与契约互斥)
针对 SystemUIHook 与 ImmersiveOverlayManager 之间的复杂多变量状态机与跨进程管道进行严密的形式化测试。

- **3.1 状态栏与全屏沉浸显示互斥公理 (`ImmersiveStateMachineTest`)**
  - **公理核心公式**：
    $$\text{shouldShowImmersive} = (\neg \text{isClockVisible}) \land \text{hookEnabled} \land \text{immersiveEnabled} \land \text{isInteractive} \land (\neg \text{isLocked})$$
  - **TC-3.1.1** 完整真值表枚举覆盖：通过 5 个布尔变量（共 $2^5 = 32$ 种全状态排列组合），验证只有在状态栏隐藏、模块使能、沉浸使能、屏幕交互点亮、未锁屏这 5 个条件同时满足时，沉浸浮层才被准许唤起（唯一真值分支）。
  - **TC-3.1.2** 状态栏与沉浸互斥：当状态栏可见时，无论全屏配置如何，全屏沉浸浮层绝对不可显示。

- **3.2 锁屏与息屏门禁互斥**
  - **TC-3.2.1** 锁屏防护：处于锁屏状态（`isLocked = true`）时，严禁弹出全屏沉浸浮层。
  - **TC-3.2.2** 息屏防护：处于息屏非交互状态（`isInteractive = false`）时，立即切断沉浸浮层并抑制刷新。

- **3.3 跨进程观察者广播与解耦 (`ConfigParsingTest`)**
  - **TC-3.3.1** 多观察者无干扰广播：验证多个独立业务组件注册时，一次配置推送全部触达。
  - **TC-3.3.2** 观察者注销隔离：验证组件销毁后注销观察者，不再接收后续配置变更回调。

---

### Tier 4: Real-World Workload Scenarios (真实场景与复杂手势仿真)
模拟现实用户在 Android 设备上的完整操作路径与复杂手势生命周期。

- **4.1 桌面与普通应用前台运行 (R1 场景)**
  - 状态：`isClockVisible=true, isLocked=false, isInteractive=true`
  - 预期行为：状态栏时钟旁常驻显示功率；全屏沉浸浮层完全隐形；文字颜色与字体随浅色/深色主题动态自适应。

- **4.2 启动并进入大型全屏游戏 (R2 场景)**
  - 状态转场：`isClockVisible` 从 `true` 转变为 `false`
  - 预期行为：状态栏文字隐去，顶层 2038 沉浸胶囊毫秒级浮现在用户配置的对齐位置（顶居中/左/右）；触控操作具备 `FLAG_NOT_TOUCHABLE` 100% 穿透。

- **4.3 底部上滑呼出多任务卡片 (Recents 手势防抖场景)**
  - 状态转场：全屏游戏状态下，用户从屏幕底部向上划动呼出多任务视图。
  - 预期行为：系统原生状态栏短暂显示或时钟重新可见，沉浸浮层立即隐藏，绝对杜绝在多任务缩略卡片滑动时浮层异常反向冒出。

- **4.4 息屏与锁屏全生命周期**
  - 状态转场：电源键锁屏或超时息屏，随后双击亮屏显示锁屏时钟。
  - 预期行为：锁屏阶段时钟被白名单排除或被锁屏标志阻断，不产生幽灵浮层；解锁进入桌面后秒级复原常驻状态栏功率。

---

## 3. 测试套件清单与执行规范

| 测试类名 | 对应需求 | 核心测试范畴 | Tier 等级 |
|---|---|---|---|
| `BatteryPowerCalculationTest` | R3 | 功率计算、单位切换、双电芯倍率、极性翻转、μA/mA 归一化 | Tier 1, Tier 2 |
| `ConfigParsingTest` | R3 | 跨进程配置解析、缺省回退、观察者注册注销、键完整性 | Tier 1, Tier 3 |
| `StatusBarVisibilityRuleTest` | R1 | 时钟白名单/黑名单过滤、物理视口判定、Mode A/B 坐标推导 | Tier 1, Tier 2, Tier 4 |
| `ImmersiveStateMachineTest` | R2 | 沉浸接管 32 态真值表、手势防抖互斥、挖孔与安全边距计算 | Tier 1, Tier 3, Tier 4 |
| `CrossProcessConfigTest` | R3 | 边界容错、多参数数据合成、刷新率钳位、并发线程安全 | Tier 2, Tier 3 |

### 本地测试执行指令
```bash
./gradlew test --no-daemon
```
构建与执行要求：所有测试用例 100% 通过（0 失败，0 错误），用例执行耗时保持在亚秒级。
