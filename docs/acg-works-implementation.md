# 作品关联：Android 消费侧

视觉和交互依据：`.prototypes/acg-works.html`，沿用 `DESIGN.md`，不修改原型、不增加底栏页签、不提供关联编辑或审核。

## 实现约定

- 播放页：歌手 / 专辑下方显示用途和作品名；无关联或服务器不支持 `trackWorks` 时不占位置。单部作品直接进入详情，多部显示 `+N` 并打开 Material 底部弹层。关联按用途优先级排列。`tracks/{id}/works` 不提供海报和年份，因此弹层仅对可见行额外请求作品详情补齐，失败仍可跳转。
- 作品页：108×152dp 海报和衬线标题并排；色场取自海报，无海报时回退到关联专辑封面。反白播放 / 随机主键，加入队列为次键。曲目是 hairline 行，曲号位显示用途 / 当前播放柱；下方为专辑和同系列海报横排。
- **当前默认**：主键、随机和加入队列涵盖整个作品（包括原声带）；页面有明确说明。筛选只影响可见列表，点击歌曲从筛选后的队列开始播放。首页作品横排放在最近播放下面。这是原型未定项的实现选择，可后续调整。
- 歌曲列表仅显示非整专辑派生的 `op / ed / theme / insert / character / image_song`；原声带通过专辑卡片进入。播放整部作品和系列时按 sourceId + trackId 去重。
- 每条作品导航记录保留 sourceId 和 workId，同系列逐层进入后返回不会覆盖上一部作品；列表位置和筛选沿用 Compose 页面状态保留机制。
- 首页首次使用 `/api/v1/home` 的 `works`；换批使用 `/api/v1/home/works?type=all&batch=…&generation=…`，以服务端返回页号为准，支持轮换过期提示。只有多批时显示换批按钮；网络失败可重试，保留已有卡片。
- 使用能力位 `home / trackWorks / series / seriesTracks` 隐藏或拒绝不支持的操作，Plex 不请求 032 接口。
- 全部新增文案同时提供中文 / 英文；按钮触控高度至少 48dp，列表和弹层可滚动；不使用演示数据或打包新图像。

## 代码入口

- API / wire 模型：`music/Music032Client.kt`、`music/Music032Works.kt`
- 状态与播放协调：`state/AppStateWorksCoordinator.kt`
- UI：`ui/sections/WorksSection.kt`，由 `MainActivity.kt`、`HomeSection.kt` 和 `PlaybackSection.kt` 挂接
- 测试：`Music032WorksTest.kt`、`Music032WorksApiTest.kt`

## 验证记录

- Debug Kotlin 编译和 APK 构建通过。此 ARM 开发机使用现有 ARM aapt2，通过命令行 `-Pandroid.aapt2FromMavenOverride=…` 临时指定，没有改变项目构建配置。
- 032 相关 JVM 测试通过，包括 JSON 协议、真实本地 HTTP 请求路径、Bearer / mediaToken 分离、换批 generation 编码、404、用途筛选、队列去重和导航身份。
- 全量单测曾执行 434 项：391 通过、42 失败、1 跳过；42 项均因开发机缺少 Robolectric 的 `conscrypt_openjdk_jni-linux-aarch_64`，不是断言失败。
- **未完成原生截图验收**：SDK 默认 adb 是 x86，本机备用 ARM adb 也无法启动（fdsan 错误），没有可用设备 / 模拟器。手机、平板和大字体布局仍需在设备上确认；没有将 HTML 渲染冒充原生截图。
