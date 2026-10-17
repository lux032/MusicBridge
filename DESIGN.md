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

## 正在播放标识
实现入口：`ui/components/NowPlaying.kt`，状态由根部 `LocalNowPlaying` 提供。
- 曲号位（专辑、歌单、离线、队列）：曲号换成 accent 色 `PlayingBars`，歌名 accent + SemiBold（`TrackNumberSlot` / `nowPlayingTitleColor`）。
- 小封面（≤ 64dp，歌手曲目、离线专辑行）：`NowPlayingArtworkScrim`，45% 黑遮罩 + 白色柱。
- 大封面（专辑卡片、最近播放）：`NowPlayingCoverBadge`，左下角 28dp 深色圆 + 白色柱。封面上不用 accent，因为 accent 就取自这张封面，会融进去。
- 柱子要如实反映状态：播放时跳动，暂停时落到静止形状，缓冲时轻微起伏。不加整行底色。
- 队列按位置判断当前项（同一首歌可能出现多次）；其他列表按 `sourceId + ratingKey` 判断。

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

## 歌单编辑（032）
实现入口：`ui/sections/PlaylistEditing.kt`，由根部 `LocalPlaylistEditor` 决定是否提供（仅 032 服务器）。
- 歌单页标题行：同步（工具）+「新建歌单」（次键 `LabelKey`，40dp）。空列表时空状态的主键也是「新建歌单」。
- 「添加到歌单」挂在现有的「更多」菜单里（曲目行、专辑卡片长按、专辑页加入队列键、播放页更多），不新增按键。
- 选择器用底部弹层：先写清楚要加什么（封面 + 名称），首行「新建歌单」，其后按最近修改排序的歌单；点选即关闭，结果交给 snackbar。
- 表单保留 outlined 输入框；主键（反白）写明动作「创建歌单 / 创建并加入 / 保存」，失败时表单不关、原因写在表单内。
- 写操作反馈统一走 `PlaylistNoticeHost`：反白（近白）snackbar 贴在底栏/房间栏之上；移除曲目带「撤销」。服务端返回的重复、失效曲目如实写出。
- 删除歌单必须二次确认，确认文案说明歌曲文件不受影响且无法撤销。

## 今日推荐（032）
实现入口：`ui/sections/DailyMixUi.kt`、`state/AppStateDailyMixCoordinator.kt`；只在服务器声明 `features.dailyMixes` 时显示。
- 首页标题栏下、快捷入口上方是横向翻页的紧凑横条（方案 B）：高 148dp，卡片底色取自该歌单第一张封面（`rememberArtworkPalette`），左侧 124dp 的 2×2 封面拼图，右侧依次是衬线标题、情绪 · 风格、一行简介，底部是曲数与分钟数和反白圆形播放键。下一张卡片从右边露出 18dp，底部有分页点。
- 点卡片进入详情，播放键直接播放，长按卡片弹出“播放 / 随机播放”。已踩的歌单仍保留，卡片半透明；正在播放的歌单在拼图上显示 `NowPlayingCoverBadge`。
- 服务器生成中时，在同一位置显示同尺寸的占位卡，每 5 秒刷新一次，生成好后替换为歌单卡片。
- 详情页复用歌单页：封面换成拼图，标题下用 accent 色写主题。播放键下面是反馈行：赞、踩（`ToggleKey`）、换一个（`LabelKey`）、另存为歌单（`IconKey`），说明文字写在这一行下面。踩会打开底部弹层，原因可多选也可不选，再点一次撤销。
- 曲目行：探索曲目标“新发现”（accent 色），“更多”菜单里有“不再推荐 / 恢复推荐”，已屏蔽的歌标题变灰。所有结果通过 `PlaylistNoticeHost` 提示，屏蔽操作带“撤销”。
