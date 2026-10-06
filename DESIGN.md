# MusicBridge 设计规范（方案 D：房间 × 色场）

已确定组合：**衬线标题 / 适中色场 / 贴底房间栏**。实现入口：`ui/components/Keys.kt`。

## 按键层级
| 角色 | 组件 | 外观 | 用途 |
|---|---|---|---|
| 主键 | `SplitPlayKey` / `LabelKey(style = Hi)` / `KeyButton(style = Hi)` | 近白填充 + 色场深色字 | 每屏只有一个：「在 X 播放」▶︱⤮、保存、生成 |
| 次键 | `IconKey` / `LabelKey` / `KeyButton`（Tonal） | 11% 白、无描边 | 收藏、下载、加入队列、刷新 |
| 工具 | `BareIcon` | 仅图标，48dp 触控区 | 返回、更多、排序、删除 |
| 开关 | `ToggleKey` | 关=Tonal/Bare，开=反白 Hi | 随机、循环、收藏、筛选 chip |

- 形状统一用 squircle（48dp 时圆角 16dp，`KeyShape`），**全应用不画描边**。
- 主键标明输出房间（`Strings.playOn`），让人知道声音去哪。

## 列表与容器
- 曲目、歌单、离线专辑这些列表都用 hairline 行（`Hairline`，10% 白），不再每行一张卡片。
- 单选列表、弹窗选项、队列都用 `ChoiceRow`：选中时铺 tonal 底，末尾放 accent ✓。
- 设置分组用 5.5% 白的无边面，组内用 hairline 分隔。
- 输入框：搜索用 `TonalSearchField`；表单保留 outlined，未聚焦时边框弱化到 14% 白。

## 字体
- 页面标题和分组标题（display / headline / titleLarge）用衬线体，Bold。
- 其余（titleMedium 及以下）用无衬线体。

## 颜色
- 封面取色 `LocalArtworkPalette` 保持不变，饱和度按「适中」档收敛（`ArtworkPalette.kt`）。
- 贴底元素（底栏、迷你播放条、音量浮层、字母索引）统一用 `dockColor()`，即色场底色压暗 28%。
- 强调色 `palette.accent` 只用于：进度、当前项、艺人名链接、状态点。

## 播放页
自上而下分组：顶栏（收起 · 房间键 · 更多）→ 封面 → 曲目信息（标题 + 收藏键、艺人 · 专辑、信号链）→ 进度 + 走带（随机 · 上一首 · 主播放键 · 下一首 · 循环）→ 房间音量 → 工具行（队列 / 歌词 / 定时）。
组与组之间用等权弹性 `Spacer(weight=1f)` 分摊剩余高度，不把空白全堆到一个地方。
