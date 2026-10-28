# Dead Ends (已验证失败的方案与教训)

| Iteration | Approach Tried | Why It Failed | Files Touched |
|-----------|---------------|---------------|---------------|
| 1 | 在 `NEGATIVE_KEYWORDS` 中使用单纯的子串 `"lock"` | 单词 `"clock"` 天然包含 `"lock"` 子串，导致所有的系统时钟（`Clock` 类与资源 ID）被自杀式全部误杀拦截，Hook 完全不生效 | `SystemUIHook.kt` |
| 1 | 仅判定 `screenX + w < -5` 和 `screenY + h < -5` 物理移出 | 横屏游戏或分屏下，系统将状态栏向右（`x > screenWidth`）或向下移出屏幕时漏检，导致状态栏隐藏误判为可见，进而阻塞全屏沉浸接管 | `SystemUIHook.kt` |
| 1 | 祖先链仅过滤 `p.visibility == View.GONE` | 遗漏了 `View.INVISIBLE`（值为 4），导致父容器处于 INVISIBLE 状态时子 View 被误判为屏幕可见 | `SystemUIHook.kt` |
| 1 | 未对 -5px 越界判定设置施密特迟滞区间 | 在边缘 1px 抖动时产生高频显隐频闪 | `SystemUIHook.kt` |
