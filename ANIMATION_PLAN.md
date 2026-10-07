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
| 返回收起改为整页下滑（单一运动） | ✅ | Pop 离开播放页时只有播放页这一张不透明“纸”在动：`slideOutVertically` 380ms，曲线 `(0.2,0.3,0.1,1)`（带初速度但限峰值；M3 emphasized-decelerate 初速 ~14× 平均速度，整屏页面每帧跳 1/5 屏，造成频闪残影，已弃用）。下层页面无动画、不透明；封面/背景共享元素仅展开时生效，收起时随页面一起下滑；底部导航/迷你条收起时直接出现、静止，不做淡入或上滑（与下落页面反向交错会产生重影）。下滑手势：拖动偏移改为同步写入的 State（原每帧 launch snapTo 会滞后一帧抖动），只做位移+顶部圆角，不缩放不透明度；过 22% 或快速轻扫即交接给退出动画 |
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
| 专辑详情转场统一 | ✅ | 移除“全部专辑”入口的封面共享元素试点；所有入口统一使用与艺人详情页一致的 Push 页面转场 |

设计约束：统一 `SizeTransform(clip = false)`；时长 300–360ms `FastOutSlowInEasing`（以现有 600ms 全局背景色渐变为基准，交互反馈更快）。

**涉及文件**
- `ui/transition/AppTransitions.kt`（`sectionTransitionSpec` 定向分派、`PageSlideMillis`/`TabFadeInMillis`）
- `ui/components/CommonComponents.kt`（`AlbumCoverCard` 保持普通静态封面）
- `ui/sections/HomeSection.kt`（全部专辑网格）、`ui/sections/PlaybackSection.kt`（`AlbumDetailSection` 封面 + 网格缩略图 placeholder）

---

## P2 — 微交互与细节 🔶 部分完成

| 项目 | 状态 | 说明 |
|---|---|---|
| 列表项插入/移除动画 | ✅ | 全部 Lazy 列表的 `items`/`itemsIndexed` 内容根节点加 `Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)`（仅位移动画，不叠加淡入淡出，避免与页面转场打架）。覆盖：歌单列表、歌单详情曲目、艺人网格/列表、艺人详情专辑行+热门曲目、首页最近播放横滑+预览网格行、合集网格、全部专辑网格、播放队列底部弹层、专辑详情曲目（宽屏+窄屏） |
| 全局音量浮层显隐动画 | ✅ | `MainActivity` 中 `if` 改为 `AnimatedVisibility`：入场半高上滑+淡入 280ms，出场下滑 220ms+淡出 180ms；`overlayRoom` 在外层 `?.let` 解包，保证退出动画期间浮层保持组合 |
| 底部 Tab 指示器胶囊 | ✅ | `BottomNavigationBar` 重排为 4 个等宽 `weight(1f)` 单元格；选中胶囊跟随移动，前后两缘各跟一条不同 stiffness 的弹簧（前缘 500/阻尼 0.85，后缘 StiffnessLow/0.92），移动中拉伸形变、到位后收敛；accent 16% 填充、18dp 圆角，尺寸由 Row `onSizeChanged` 实测 |
| 预测式返回（可选） | ⬜ 暂缓 | 自定义 `AnimatedContent` + `SharedTransitionLayout` 架构下接 `SeekableTransitionState` 手势进度会与共享元素 morph 冲突；按"交互可靠性优先"原则暂缓，待导航架构迁移（如 Navigation 3）后再评估 |

**涉及文件**
- `ui/sections/PlaylistSection.kt`、`ui/sections/ArtistSection.kt`、`ui/sections/HomeSection.kt`、`ui/sections/PlaybackSection.kt`（`animateItem`）
- `ui/MainActivity.kt`（音量浮层 `AnimatedVisibility`）
- `ui/components/CommonComponents.kt`（`BottomNavigationBar` 胶囊指示器）

---

## 技术要点备忘（复用这些结论，勿重复踩坑）

- **`BoundsTransform` 是顶层接口**（`androidx.compose.animation.BoundsTransform`），不是嵌套类。
- 本版本 Compose **没有** `sharedBoundsWithCallerManagedVisibility`；`miniSharedBounds`/`playerSharedBounds` 均用 `sharedElement` 变体实现。
- `ContentTransform` 用构造函数（`targetContentEnter, initialContentExit, sizeTransform`）；`using` 中缀在此版本不可解析。
- Row 内调用 `AnimatedVisibility` 需全限定（`RowScope.AnimatedVisibility` 会遮蔽顶层函数）。
- **单端停用共享元素时，另一端也要停用**：播放页端改为仅展开时挂载后，迷你条端 `sharedElementWithCallerManagedVisibility` 仍记得播放页全屏 bounds，收起时会从全屏 morph 回迷你条，且在 overlay 最上层绘制（录屏可见一整块深色 dock 幕布下落 = 用户感知的“残影”）。`miniSharedElement` 现在在 visible 由 false→true 的收起窗口内同步摘除共享元素，窗口结束后再挂回。
- **交互按钮不做共享元素**：overlay 渲染期间命中判定与视觉位置分离，按钮会"点了没反应"。
- 高频传输控件**不要用网络 loading 状态门控**；loading 只用于展示，不用于禁用。
- 大改布局嵌套后用括号配平校验（awk 统计深度归零），错位一个 `}` 会让兄弟节点落进错误的 lambda。
- foundation 1.9 的 `animateItem` 是 `LazyItemScope`/`LazyGridItemScope` 的**成员函数**（非扩展、已去实验注解）——无需 import、无需 `@OptIn`，list/grid 同名也不冲突。
- 浮层包 `AnimatedVisibility` 时，可空数据源要在**外层**解包（`room?.let { ... }`）；把 null 判断并进 `visible` 会导致数据变 null 时整个分支瞬间消失，退出动画播不出来。
- 移动指示器的"拉伸形变"：指示器左右两缘各跟一个不同 stiffness 的 `animateFloatAsState`，left=min(两缘)、right=max(两缘)+1，弹簧速度差自然产生拉伸。
- Box 里想让某层匹配兄弟内容的高度，**不能**用 `fillMaxHeight`（会吃到父级传入的 maxHeight 把布局撑爆）；用 `onSizeChanged` 实测兄弟尺寸再换算。
