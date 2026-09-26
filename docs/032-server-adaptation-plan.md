# MusicBridge 适配 032 Music Server 新能力 — 实施规划

- **日期**：2026-09-26
- **状态**：实施中（阶段 A）
- **服务端基线**：`D:/032music-server` `main` @ `43b2006`（P0–P5 已完成，本地提交未推送）
- **客户端基线**：`D:/MusicBridge` `f350ad3`
- **范围**：仅 Android 客户端；服务端不再改动（除非联调发现服务端缺陷，另行立项）

---

## 1. 背景

032 Music Server 已补齐 App 所需的服务端能力（歌手详情/收藏、音频属性、歌词文本、转码、缩略图、相似曲目/渐变路径、跳过推断）。客户端目前的 `Music032Client` 只使用了早期的基础接口，而播放、歌词、缩略图、声波电台、AI 歌单等功能在代码中通过 `as? PlexClient`、`SonicCapableSource` 或 **Plex URL 特征字符串** 与 Plex 绑定，对 032 源不生效或行为错误。

本规划的目标：**让 032 源在功能上与 Plex 源对齐**，同时不回退 Plex 路径的任何行为。

## 2. 服务端契约速查（客户端需要用到的部分）

| 能力 | 接口 | 鉴权 | 要点 |
|---|---|---|---|
| 能力探测 | `GET /api/v1/capabilities` | Bearer | `apiRevision=2`；`features.*`、`transcode.formats.*.available`、`artwork.sizes`、`playback.skipInference` |
| 歌手详情 | `GET /api/v1/artists/{id}` | Bearer | `{artist, albums, tracks, tracksTotal}`；已合并 id 返回 `artist.mergedFrom` |
| 歌手收藏 | `PUT/DELETE /api/v1/artists/{id}/favorite`；`GET /favorites/artists` | Bearer | 成功 204 |
| 曲目字段 | Track / SyncTrack JSON | — | 新增 `discNumber, trackNumber, codec, sampleRate, bitDepth, bitrateKbps, channels, viewCount?, lastViewedAt?(epoch 秒), skipCount?, lyricsUrl?`；未播放时 `viewCount/lastViewedAt/skipCount` 整体省略 |
| 专辑字段 | SyncAlbum JSON | — | 新增 `trackCount, albumType, compilation, live, formats` |
| 歌词文本 | `GET /api/v1/tracks/{id}/lyrics.lrc` | **mediaToken** 或会话 | `text/plain; charset=utf-8`；404 无歌词；413 过大 |
| 实时转码 | `GET /api/v1/tracks/{id}/transcode.mp3?bitrate=&offsetMs=` / `transcode.ogg?...` | **mediaToken** | chunked，无 Content-Length，`Accept-Ranges: none`；非 0 Range → 416；mp3 码率 {128,192,256,320}，ogg(opus) {64,96,128,160,192,256}；中途失败会中止连接 |
| FLAC 转码 | `GET /api/v1/tracks/{id}/transcode.flac[?maxSampleRate=48000]` | **mediaToken** | 服务端全量转码落盘后以静态文件返回（Content-Length + Range + 强 ETag）；**首请求会有转码延迟**；不接受 `offsetMs`/`bitrate` |
| 缩略图 | `GET /api/v1/artwork/{id}?size=N`、`/artists/{id}/image?size=N` | **mediaToken** | N 向上取档 {256,512,768,1024,1536} |
| 相似曲目 | `GET /api/v1/tracks/{id}/similar?limit=` | **Bearer（不接受 mediaToken）** | `{items:[{track, distance(0~1), score, reasons}]}`；并发满 503 + Retry-After |
| 渐变路径 | `GET /api/v1/tracks/path?from=&to=&limit=` | Bearer | `{items:[Track], complete}`，首 from 末 to |
| 建歌单 | `POST /api/v1/playlists {name, description, trackIds}` | Bearer | 单事务；无效 id → 400 |
| 播放上报 | `POST /api/v1/playback/timeline` | Bearer | 新增可选 `skipped`(bool)、`clientId`(≤128)；**未知字段仍会 400** |

> ⚠️ **媒体鉴权**：服务端的媒体类接口（stream / artwork / artist image / lyrics.lrc / transcode）**只接受 mediaToken 或管理员会话，不接受 API Token**。

## 3. 差异清单（现状 → 目标）

| # | 领域 | 现状（文件） | 问题 | 目标 |
|---|---|---|---|---|
| G1 | 媒体 Token | `music/Music032Client.kt:30` `mediaToken = profile.mediaToken.ifBlank { apiToken }`；`ui/settings/dialogs/ServerEditSheet.kt:207-220` 提示可留空 | 服务端单独配置媒体 Token 时，留空会导致所有音频/封面 401 | 设置页校验与提示；保存时要求媒体 Token，连接后探测 401 提示补填；存量回退 |
| G2 | 字段解析 | `Music032Client.parseTrack/parseAlbum` | 未解析 codec/采样率/位深/码率/碟号/曲号/lyricsUrl/skipCount，`viewCount` 等依赖旧字段名 | 完整解析；本地库 `AlbumLocalStore` 已有对应列（v16），无需迁移 |
| G3 | 歌手曲目与收藏 | `Music032Client.fetchArtistTracks` 吞掉异常；`fetchArtistAlbums` 未实现 | 服务端已提供，可直接对接 | 读 `root.tracks`/`root.albums`；歌手 `isFavorite` |
| G4 | 单曲补全 | `state/AppStatePlaybackCoordinator.kt:1291`、`state/AppStateLibraryCoordinator.kt:453` 仅 `as? PlexClient` 调 `fetchTrackStream` | 032 源无法补采样率，也无法为离线组重建推送流 | 抽象 `fetchTrackStream` 到 `MusicSourceClient`（默认 null），032 实现为 `GET /tracks/{id}` |
| G5 | Sonos 转码（Opus / Hi-Res） | `AppStatePlaybackCoordinator.prepareSonosTrack` → `withOpusTranscodeMode` / `withSonosHiResTranscodeMode`（`plex/PlexClient.kt:1391,1531`）只识别 `/library/parts` 与 `X-Plex-Token` | 032 Opus 投 Sonos 无声；Hi-Res 不转码 | 为 032 生成 `transcode.mp3` / `transcode.flac?maxSampleRate=48000` URL |
| G6 | Sonos 起播偏移与 seek | `AppStatePlaybackCoordinator.kt:144,282,556,842` 用 `"/:/transcode/universal/start.mp3"` 判断实时流，`withPlexTranscodeOffsetSeconds` 改 `offset` 参数 | 032 实时 mp3 无法 seek/续播 | 引入源无关的"实时转码 URL"判定与"设置偏移"函数，032 用 `offsetMs` |
| G7 | FLAC 中继 | `PlexTranscodeProxy.preparePlaybackUrl` 对 opus+FLAC 走手机代理 | 032 的 `transcode.flac` 已是带长度/Range 的静态文件，**不需要手机代理** | 032 源直接把 `transcode.flac` URL 交给 Sonos；首请求延迟需 UI 提示与超时策略 |
| G8 | 本机蜂窝限速转码 | `plex/PlexClient.kt:1631 willTranscodeLocally`、`:1648 withLocalPlaybackPolicy` 只处理 Plex；`AppStateLocalPlaybackCoordinator.kt:306-330` 用 URL 特征判定 | 032 源在限速档位下仍拉原文件 | 032 用 `transcode.ogg?bitrate=` 生成 Opus 流；缓存键区分码率 |
| G9 | 本机 seek（实时流重开） | `local/LocalTranscodeSeek.kt:31,68`（`withPlexTranscodeOffsetSeconds`、`isPlexLiveTranscodeUrl`、`fastSeek`） | 032 实时 ogg 无法 seek | 抽象为按源的 URL 适配器；032 用 `offsetMs`（毫秒精度，可去掉整秒取整误差） |
| G10 | 转码会话清理 | `local/LocalPlaybackEngine.kt:312,454,468,561,571-590,647,777` `stopPlexTranscodeSession` / `withPlexTranscodeSession` / `parsePlexTranscodeEndpoint` | 对 032 应为无操作（服务端随连接断开回收 ffmpeg） | `parsePlexTranscodeEndpoint` 对 032 URL 返回 null 即可，确认无副作用 |
| G11 | 缩略图 | `util/AppHelpers.kt:104 plexThumbnailUrl`，调用处 `ui/components/CommonComponents.kt:786,796`、`util/ArtworkPalette.kt:120`、`service/PlaybackForegroundService.kt:133` | 032 封面每次加载原图，列表卡顿 | 泛化为 `thumbnailUrl(url, px)`：Plex 走 `/photo/:/transcode`，032 追加 `size=` 桶 |
| G12 | 歌词 | `ui/sections/PlaybackSection.kt:165` `resolvePlexLyricUrl`；离线下载 `storage/OfflineDownloadManager.kt:320` 依赖 `lyricUrl` | 032 曲目无 `lyricUrl`，歌词与离线歌词都缺失 | 032 解析 `lyricsUrl` 为带 mediaToken 的 `lyrics.lrc` 绝对 URL，复用 `fetchPlexLyrics` 解析 LRC |
| G13 | 声波电台 / AI 保存歌单 | `plex/SonicCapableSource.kt`；`state/AppState.kt:689,704` | 032 不支持声波电台与保存 AI 歌单 | `Music032Client` 实现 `SonicCapableSource`：`similar`→`fetchNearestTracks`，`path`→`computeSonicPath`，`POST playlists{trackIds}`→`createPlaylist` |
| G14 | AI 歌单门禁 | `state/AppState.kt:620-623` `aiPlexOnly`；`:639` 最近播放仅 Plex | 032 用户无法使用 AI 歌单 | 取消门禁；032 最近播放用 `/playback/history`；文案去 Plex 化（`core/Strings.kt:182,186,187`） |
| G15 | 跳过上报（可选） | `AppStateLocalPlaybackCoordinator.kt:480`、`AppStatePlaybackCoordinator.kt` 切歌时 `stopped+continuing` | 服务端已按推断工作，无需改动；显式上报可提高准确度 | 可选：用户主动"下一首"时对 032 发送 `skipped=true`，自然结束发送 `skipped=false` |
| G16 | 能力探测 | 无 | 服务端旧版本或无 ffmpeg 时，新 URL 会 404/503 | 连接/切换服务器时拉取 capabilities 并缓存到进程级 store，按能力降级 |

## 4. 设计要点

### 4.1 能力模型（G16，所有后续阶段的前置）
- 新增 `data class Music032Capabilities(apiRevision, features: Set<String>, transcodeFormats: Map<String, FormatCaps>, artworkSizes: List<Int>)`。
- 进程级 `Music032CapabilitiesStore` 按规范化地址和 API Token 缓存（成功 10 分钟、失败 30 秒；保存时失效）；获取失败视为 `apiRevision=1`（全部新能力关闭），**保持今天的行为**。
- 所有 032 新能力调用前检查对应 feature；转码按 `transcode.formats.<fmt>.available` 判断。

### 4.2 源无关的播放 URL 适配层（G5–G10 的核心）
当前多处用 URL 子串判断 Plex 实时转码，建议抽出一个小接口，Plex 与 032 各一个实现，调用处只依赖接口：

```kotlin
internal interface PlaybackUrlAdapter {
    fun isLiveTranscode(url: String): Boolean          // Plex: "/:/transcode/universal/start"；032: "/transcode.mp3" 或 "/transcode.ogg"
    fun withOffset(url: String, offsetMillis: Long): String // Plex: offset=秒；032: offsetMs=毫秒
    fun sonosUrl(track: PlexTrackStream, opus: OpusTranscodeMode, hiRes: SonosHiResTranscodeMode): PlexTrackStream
    fun localUrl(track: PlexTrackStream, policy: LocalPlaybackPolicy): PlexTrackStream
    fun willTranscodeLocally(track: PlexTrackStream, policy: LocalPlaybackPolicy): Boolean
}
```
- 以 `track.sourceId` → 服务器类型选择实现；**Plex 实现内部直接调用现有函数，行为逐字不变**。
- 032 的 `streamUrl` 仍为原文件直链；转码 URL 在播放时按策略生成，**不写入本地库**，避免策略切换后残留。

### 4.3 032 Sonos 策略映射
| 源 | 设置 | 032 URL | MIME |
|---|---|---|---|
| Opus | 直连秒播 (MP3 320k) | `transcode.mp3?bitrate=320[&offsetMs=]` | `audio/mpeg` |
| Opus | 无损中继 (FLAC) | `transcode.flac`（**不走手机代理**） | `audio/flac` |
| 无损 >48kHz | 转 MP3 | `transcode.mp3?bitrate=320` | `audio/mpeg` |
| 无损 >48kHz | （新增选项）转 FLAC ≤48k | `transcode.flac?maxSampleRate=48000` | `audio/flac` |
| 其他 | — | 原 `stream` 直链 | 按原文件 |

- "无损中继"对 032 实际变成服务端缓存：首次请求需等待服务端转完（NAS 上 5 分钟曲目约 3–10 秒）。建议对 032 源在推送前先以 `GET Range: bytes=0-0` 预热并显示"服务器正在准备 FLAC"提示；30 秒预算内对 503 按 Retry-After 重试，502/超时回退 MP3；服务端断开后任务仍继续，建议预热下一首。
- 是否新增"Hi-Res 转 24/48 FLAC"选项需决策（见 §7 D3）。

### 4.4 本机限速转码（G8/G9）
- `willTranscodeLocally`：032 源在 `sourceAudioBitrateKbps > policy.limit` 时为 true（码率来自服务端 `bitrateKbps`；未知时与 Plex 现状一致：opus 不转，其他 codec 转）。
- URL：`transcode.ogg?bitrate=<policy.opusBitrate.kbps>`；码率需映射到服务端白名单 {64,96,128,160,192,256}，不在白名单则取不超过的最大值。
- seek：`offsetMs` 毫秒精度，`LocalSeekPlan.logicalBaseMillis` 可直接用目标毫秒，不再整秒截断；无需 `fastSeek`。
- 预缓存与缓存键：沿用 `transcodedCacheKey(sourceId, ratingKey, partKey, kbps)`；`OggStreamValidator` 应对 032 输出同样有效（需测试确认）。
- 会话清理：`parsePlexTranscodeEndpoint` 对 032 URL 返回 null → 不调用 stop；服务端在连接断开 300ms 内回收 ffmpeg。

### 4.5 缩略图（G11）
- `plexThumbnailUrl` 重命名/包装为 `thumbnailUrl(original, px)`：识别 032 URL（`/api/v1/artwork/` 或 `/api/v1/artists/{id}/image`）时追加 `size=<桶>`，保留 `mediaToken`；其余逻辑不变。
- 服务端缩略图 ETag/immutable 与 Coil 磁盘缓存兼容；歌手图缩略图服务端只缓存 1 天，App 无需特殊处理。

### 4.6 歌词（G12）
- `parseTrack` 将 `lyricsUrl` 转为 `mediaUrl(...)`（带 mediaToken），写入 `PlexTrackStream.lyricUrl`。
- `resolvePlexLyricUrl` 在 `lyricUrl` 已存在时直接返回（现状已如此），032 无需额外解析。
- 离线下载沿用 `downloadCompanionAsset(lyricUrl)`，需处理 404（无歌词）与 413。

### 4.7 声波电台与 AI（G13/G14）
- `fetchNearestTracks`：`similar?limit=` → `SonicTrackMatch(ratingKey, distance, track)`；032 客户端将 distance 按 `max(0.01, d) × 7.4` 映射；不修改 `HybridRecommender`。
- `computeSonicPath(sectionKey, start, end)`：032 忽略 sectionKey，调用 `path?from=&to=&limit=`；`complete=false` 时仍返回结果并提示"未完全连通"。
- `createPlaylist`：`POST /playlists {name, trackIds}`，返回 `PlexPlaylist`。
- 503（相似度并发满）：按 `Retry-After` 重试一次，再失败提示用户。

## 5. 分阶段实施计划

| 阶段 | 内容 | 覆盖 | 风险 | 依赖 |
|---|---|---|---|---|
| **A 基础** | 能力探测与缓存；媒体 Token 校验与设置页提示；`parseTrack/parseAlbum` 新字段；歌手详情/歌手收藏；`fetchTrackStream` 抽象与 032 实现 | G1 G2 G3 G4 G16 | 低 | — |
| **B 轻量体验** | 缩略图泛化；歌词与离线歌词 | G11 G12 | 低 | A |
| **C Sonos 转码** | URL 适配层 + Plex 实现（零行为变化）；032 Sonos 策略、起播偏移、seek；FLAC 走服务端缓存与预热/回退 | G5 G6 G7 | **高**（改动播放主链路） | A |
| **D 本机转码** | 032 限速 Opus 转码、seek、缓存键、会话清理无操作 | G8 G9 G10 | **高** | C 的适配层 |
| **E 声波与 AI** | `SonicCapableSource` for 032；去除 AI 门禁；最近播放；文案 | G13 G14 | 中 | A |
| **F 可选** | 显式 `skipped` 上报 | G15 | 低 | A |

建议执行顺序：A → B → C → D → E →（F）。C 与 D 各自独立审查并真机验收后再进入下一阶段。

## 6. 验证计划

### 6.1 单元测试（JVM，`app/src/test`）
- `Music032Client` 解析：旧服务端（无新字段）与新服务端 JSON 夹具；未播放省略字段；`lastViewedAt` epoch 秒；`lyricsUrl` 转绝对 URL 并带 mediaToken。
- 能力解析：缺字段/旧版本/部分格式不可用。
- URL 适配层：
  - **Plex 回归**：现有 Plex URL 经适配层输出与改造前逐字一致（用改造前函数输出作为基准快照）。
  - 032：`isLiveTranscode`、`withOffset`（替换/新增 `offsetMs`，保留 `mediaToken`）、Sonos 映射表逐项、本机码率白名单映射。
- 缩略图：Plex 与 032 URL 各档位；非 032/Plex URL 原样返回。
- 现有测试（`LocalCacheRoutingTest`、`PlaybackDiagnosticsTest`、`CompletionPrecacheTest` 等）全部保持通过。

### 6.2 真机联调（需用户协助）
| 场景 | 预期 |
|---|---|
| 032 FLAC/MP3 直接投 Sonos | 与现状一致 |
| 032 Opus → Sonos 直连 MP3，起播于中间位置，拖动 3 次 | 秒播、位置正确 |
| 032 Opus → Sonos 无损中继 | 首次等待有提示；二次秒播；Sonos 可拖动 |
| 032 96k/24 FLAC → Sonos（MP3 / FLAC≤48k 两种） | 正常播放 |
| 手机锁屏 + 断开 App 后 Sonos 继续播放 | 032 FLAC 不再依赖手机代理 |
| 本机蜂窝限速 128k，播放、拖动、切歌、预缓存 | Opus 转码生效，拖动精确 |
| 缩略图列表快速滚动 | 无卡顿，流量明显下降 |
| 歌词显示与离线下载歌词 | 正常 |
| 声波电台、渐变路径、AI 歌单并保存 | 可用 |
| Plex 源完整回归（上述同类场景） | 行为不变 |
| 连接旧版 032 服务端 | 新功能自动隐藏，基础功能正常 |

安装与构建沿用 `musicbridge-release-install` 流程。

## 7. 待决策项

| # | 问题 | 选项 | 建议 |
|---|---|---|---|
| D1 | 媒体 Token 留空时的行为 | A：保持回退 API Token，连接时探测一次媒体接口，401 则在设置页提示必须填写；B：直接要求必填 | **保存必填；存量回退并探测**（非 devMode 未配置时随机生成媒体 Token） |
| D2 | 032 的"无损中继"是否仍经过手机代理 | A：直连服务端 FLAC 缓存；B：沿用手机代理 | **A**（服务端已提供 Content-Length/Range，省电且锁屏不断播） |
| D3 | 是否新增"Hi-Res 转 24/48 FLAC"Sonos 选项 | A：新增（Plex 源下该选项回退为 MP3）；B：不新增 | **A**（Plex 下回退 MP3） |
| D4 | 032 相似度与 Plex 声波距离尺度不同 | A：在 `HybridRecommender` 按来源归一化；B：直接使用 | **A**（032 客户端映射，不修改 HybridRecommender） |
| D5 | 是否实现显式 skip 上报（F 阶段） | A：做；B：暂缓 | B（服务端推断已可用，先观察准确度） |
| D6 | 流程 | 是否沿用硬化交付流程（oracle 审计 → 分阶段 worker → reviewer → 真机验收） | 建议沿用，C/D 阶段必须真机验收 |

## 8. 风险与注意事项
- **播放主链路改动（C/D）风险最高**：采用"适配层 + Plex 实现逐字保持"的方式，并以快照测试锁定 Plex URL 输出，防止回归。
- **Sonos 首字节超时**：服务端 FLAC 缓存首次生成需要数秒，Sonos 的等待上限未知，需真机确认；预热与回退 MP3 是兜底。
- **实时流 Range**：032 实时流对非 0 Range 返回 416，所有 seek 必须走 `offsetMs` 重开，不能依赖 Sonos/ExoPlayer 的 Range seek。
- **本地库 URL 持久化**：`stream_url`/`thumb_url` 含 mediaToken，地址或媒体 Token 变化时保存即刷新曲库，重写持久化 URL。
- **旧服务端兼容**：所有新能力以 capabilities 为准，避免对旧版本发出不存在的请求。
- **中途失败语义**：032 实时流中途失败会中止连接（非正常 EOF），客户端应按错误处理（重试/回退），不要当作曲目播完。

## 9. Oracle 审计修正与决策记录（2026-09-26）
- D1：媒体 Token 保存时必填；存量 profile 保留 `mediaToken.ifBlank { apiToken }` 回退且连接时探测，401 提示补填，不更改 `hasCredentials`。非 devMode 未配置 `MUSIC_SERVER_MEDIA_TOKEN` 时服务端每次启动随机生成；服务端 README:136 的回退说明已过期，作为服务端文档问题另行处理。
- D2 A：032 FLAC 缓存直连，无手机代理。D3 A：新增 `TRANSCODE_FLAC_48K`；Plex `forPlex()` 回退 MP3，设置、Sonos 计划生成、本机播放三处边界保持源隔离。
- D4 A：在 `Music032Client` 内按 `max(0.01, d) × 7.4` 映射距离，不改 `HybridRecommender`。D5 B：暂缓显式 skip。D6：沿用 oracle 审计 → 分阶段 worker → reviewer → 真机验收硬化流程。
- 撤回 B-1：`parseTrack` 不写 `playbackMimeType`（该字段是 Sonos 转码覆盖）；原始 MIME 由独立纯函数计算。撤回 H-2：容器 `formats` 不写发行类型 `PlexAlbum.format`；使用 albumType / live / compilation 映射；albumType=album 不写 format（服务端未标记时默认推断为 album，否则会绕过标题/曲数启发式）。
- Sonos FLAC 预热仅用 GET Range 0-0，HEAD 不触发编码；`offsetMs` 必须 clamp 至 duration-1000（大于 duration 返回 400，等于 duration 返回 502）。
- 后续阶段需覆盖：PlaybackSection.kt:~2580 `positionIsEncodedInUrl` 与 LocalPlaybackEngine RoutingDataSource:~713 使用通用实时转码谓词（B-3）；Sonos 实时流按实际生成计划判定而不是设置（H-3）；032 曲目不经过 Plex `with*TranscodeMode` 或 `PlexTranscodeProxy`（B-2）。歌词 remember key 加 sourceId（L-4）；渐变路径目前无 UI 调用方；歌手详情曲目上限 5000；能力缓存必须进程级（M-1）。
