# MusicBridge 动画优化计划书

> 目标：让 App 的过渡与交互动画达到商业流媒体软件（Spotify / Apple Music / Plexamp）水准。
> 方向（已确认）：**现代流媒体风**——共享元素过渡 + 物理弹簧感；实施顺序：**播放页优先**。
> 创建日期：2026-08-21　最后更新：2026-08-22

图例：✅ 已完成　🔶 部分完成　⬜ 未开始

---

## P0 — 播放页过渡（核心体验）✅ 已完成

| 项目 | 状态 | 说明 |
|---|---|---|
| 迷你播放器 ↔ 全屏播放页共享元素过渡 | ✅ | `SharedTransitionLayout` + `sharedElement`；封面与背景容器双元素 morph，弹簧 `spring(0.85, 500)` |
| 迷你条侧 caller-managed 可见性 | ✅ | 播放器打开时迷你条元素隐藏并交给过渡 overlay，避免双份渲染 |
| 封面防灰闪 | ✅ | Coil `placeholderMemoryCacheKey`：播放页大图请求以迷你条 52dp 缓存图作 placeholder，morph 首帧即真实封面 |
| 下滑手势关闭播放页 | ✅ | 双通道：NestedScroll（可滚动区域到顶后拦截）+ drag detector（非滚动区域）+ Final-pass 手势结束监听；22% 阈值，过阈值 160ms 补程后 `onBack()`，未过弹簧回弹；拖动中 translationY/scale(0.93)/alpha(0.55) 联动 |
| 入场编排 | ✅ | 各内容块**同时**淡入+轻微上移（240ms，无 delay 无 stagger——stagger 会造成"封面先出、其余后出"的不同步感，已按反馈移除） |
| 播放键共享元素 morph | ❌ 已移除 | 共享元素 overlay 渲染期间触摸判定留在布局原位，飞行中的按钮点不中——交互可靠性优先，播放键改为随控制区淡入 |
| **修复：播放页播放键间歇/完全失灵** | ✅ | 根因是传输控件被 `isPlaybackCommandLoading` 门控（网络命令飞行期间按钮禁用、点了没反应，而迷你条不看此标志所以正常）。修复：播放/暂停、上一首/下一首、进度条不再被门控；`togglePause` 协程内重读最新暂停状态；SOAP socket 补 5s 连接超时（原仅读超时，IP 失效可卡死数分钟） |
| **修复：转场后导航栏错位/页面变暗** | ✅ | AnimatedContent 闭合位置错误导致底部导航/迷你条/音量浮层被包进页面内容 lambda |
| 导航方向标记 | ✅ | `AppState.lastNavAction: NavAction { Push, Pop, TabSwitch }`，供 P1 定向转场使用 |

**涉及文件**
- `ui/transition/AppTransitions.kt`（新增：`LocalSharedTransitionScope` / `LocalAnimatedVisibilityScope` / `PlayerSharedKeys` / `sectionTransitionSpec` / 时长常量 / `staggeredPlayerEnter` / 共享元素 Modifier 助手）
- `ui/MainActivity.kt`（根部 `SharedTransitionLayout`；`key(activeSection)` → `AnimatedContent`；迷你条 `AnimatedVisibility` 显隐）
- `ui/sections/PlaybackSection.kt`（共享元素、下滑手势、入场动画、传输控件门控移除）
- `ui/components/CommonComponents.kt`（`AsyncAlbumArtwork` 增加 `placeholderThumbnailSize`；`BottomMiniPlayerHost` 透传 `isPlayerOpen`）
- `state/AppState.kt`（`lastNavAction`）、`state/AppStatePlaybackCoordinator.kt`（`togglePause` 修复）、`sonos/SonosDiscovery.kt`（连接超时）

---

## P1 — 全局页面转场 ✅ 已完成

| 项目 | 状态 | 说明 |
|---|---|---|
| Push 转场（列表 → 详情） | ✅ | 新页 `slideInHorizontally { it } + fadeIn`；旧页视差 `slideOutHorizontally { -it/4 } + fadeOut + scaleOut(0.95f)`，320ms `FastOutSlowInEasing` |
| Pop 转场（返回） | ✅ | 与 Push 完全镜像（旧页滑出右侧，新页从 -it/4 + scaleIn(0.95f) 回归） |
| Tab 切换转场 | ✅ | `fadeIn(280) + slideInVertically { it/28 }` 淡入轻上移，无横向滑动 |
| 定向转场接线 | ✅ | `sectionTransitionSpec(action)` 按 `NavAction` 分派；**播放页参与时仍只交叉淡入**（共享元素主导，页面级滑动会与 morph 打架） |
| 专辑封面 morph（P1.5 试点） | ✅ | 全部专辑页网格封面 → 专辑详情页封面共享元素；`AlbumSharedKeys.artwork(album)` + `sectionSharedElement`；详情页大图以网格缩略图缓存作 placeholder 防灰闪；从其他入口（首页/艺人页）打开时无匹配端，自动降级为普通淡入 |

设计约束：统一 `SizeTransform(clip = false)`；时长 300–360ms `FastOutSlowInEasing`（以现有 600ms 全局背景色渐变为基准，交互反馈更快）。

**涉及文件**
- `ui/transition/AppTransitions.kt`（`sectionTransitionSpec` 定向分派、`AlbumSharedKeys`、`sectionSharedElement`、`PageSlideMillis`/`TabFadeInMillis`）
- `ui/components/CommonComponents.kt`（`AlbumCoverCard` 增加 `sharedArtworkKey` 可选参数）
- `ui/sections/HomeSection.kt`（全部专辑网格传入共享 key）、`ui/sections/PlaybackSection.kt`（`AlbumDetailSection` 封面共享元素 + 网格缩略图 placeholder）

---

## P2 — 微交互与细节 ⬜ 未开始

| 项目 | 状态 | 说明 |
|---|---|---|
| 列表项插入/移除动画 | ⬜ | Lazy 列表 `Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)` |
| 全局音量浮层显隐动画 | ⬜ | `GlobalVolumeOverlay` 包 `AnimatedVisibility` |
| 底部 Tab 指示器胶囊 | ⬜ | 选中指示器位移+形变动画 |
| 预测式返回（可选） | ⬜ | Predictive Back + `SeekableTransitionState` 手势驱动转场 |

---

## 技术要点备忘（复用这些结论，勿重复踩坑）

- **`BoundsTransform` 是顶层接口**（`androidx.compose.animation.BoundsTransform`），不是嵌套类。
- 本版本 Compose **没有** `sharedBoundsWithCallerManagedVisibility`；`miniSharedBounds`/`playerSharedBounds` 均用 `sharedElement` 变体实现。
- `ContentTransform` 用构造函数（`targetContentEnter, initialContentExit, sizeTransform`）；`using` 中缀在此版本不可解析。
- Row 内调用 `AnimatedVisibility` 需全限定（`RowScope.AnimatedVisibility` 会遮蔽顶层函数）。
- **交互按钮不做共享元素**：overlay 渲染期间命中判定与视觉位置分离，按钮会"点了没反应"。
- 高频传输控件**不要用网络 loading 状态门控**；loading 只用于展示，不用于禁用。
- 大改布局嵌套后用括号配平校验（awk 统计深度归零），错位一个 `}` 会让兄弟节点落进错误的 lambda。
