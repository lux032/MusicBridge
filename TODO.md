# MusicBridge 改进与功能规划 TODO List

> 基于代码审查（v1.1.12）整理的问题修复清单与功能路线图。

---

## 一、高优先级缺陷（可靠性 / 崩溃风险 / 性能）

- [x] **离线下载跨服务器 Key 冲突修复**：移除非空 `sourceId` 的裸 `ratingKey` 写入与兜底匹配，统一走 `downloadedTrackKey(sourceId, ratingKey)` / `containsDownloadedTrack()`，避免多台 032 服务器间数字 ID 碰撞。
- [x] **离线下载完整性校验与连接释放**：校验 HTTP 2xx 响应码与 `Content-Length` 实际读取字节数（`isDownloadComplete`），防止断流残缺文件落盘为已完成；音频与附属资源（封面/歌词）下载均在 `finally` 中 `disconnect()`。
- [x] **本机播放服务后台启动防崩溃**：`LocalPlaybackService.start()` 捕获 Android 8+ 后台 `startService` 抛出的 `IllegalStateException`，自动降级为 `MediaController` 绑定拉起；无 `mediaSession` 时 `onStartCommand` 返回 `START_NOT_STICKY`。
- [x] **Sonos 前台服务防「幽灵通知」与耗电优化**：`PlaybackForegroundService` 改为 `START_NOT_STICKY` 并在 `intent == null` 时立即 `stopSelf()`；暂停时自动释放 `WakeLock` / `WifiLock`，播放时再获取；API 29+ 改用 `WIFI_MODE_FULL_LOW_LATENCY`；后台启动捕获 `IllegalStateException`。
- [x] **`Music032Client` 线程池与连接泄漏修复**：将 `playbackEventExecutor` 提升为 `companion object` 进程共享守护线程池并拆分独立 `requestExecutor`，重建客户端不再泄漏线程；`request()` 与 `postPlaybackEvent()` 均在 `finally` 中 `disconnect()`；`fetchWork()` 并发拉取曲目与专辑。
- [x] **消除主线程同步 SQLite / 磁盘 I/O 与回调泄漏**：
  - `OfflineDownloadManager` 维护内存快照 `downloadedByKey`，`AppStateLocalPlaybackCoordinator` 启动/插队时不再在主线程逐首查库与 `File.exists()`。
  - `AppState.selectedArtist` 改为通过 `snapshotFlow` + `Dispatchers.IO` 异步加载 `localSelectedArtist`，不再在 Compose `derivedStateOf` 里同步查库。
  - `NetworkQualityMonitor` 新增 `unregister()` 并在 `AppState.onCleared()` 中注销 `NetworkCallback`。
- [ ] **离线下载后台保活与任务控制**：接入 `WorkManager` / 下载前台进度通知，支持单曲/队列取消、暂停、失败重试与断点续传。
- [ ] **本机播放服务冷启动恢复**：支持蓝牙/系统媒体按钮在进程被杀后冷启动 `LocalPlaybackService` 并从 `PlaybackSessionStore` 恢复上次会话。

---

## 二、中优先级问题（安全 / 体验边界 / 国际化 / 维护性）

- [x] **敏感凭据加密存储**：将 `AppPreferences` 中的 `token` / `mediaToken` 迁移至 Android Keystore 加密存储。
- [x] **清理 Sonos 日志敏感信息泄漏与 XML 解析加固**：移除 `SonosDiscovery.kt` 中的 `println`（避免打印带 `mediaToken` 的 SOAP 请求体），复用并加固 `DocumentBuilderFactory`（禁用外部实体 / DTD）。
- [x] **服务器配置表单校验完善与连接测试**：`ServerEditSheet` 补齐 `baseUrl`（含协议头校验）与 `token` 校验，保存失败不关闭弹窗、页内显示错误，并增加「测试连接」按钮与配置导入/扫码。
- [x] **双语国际化补齐与 Locale 修复**：修复 `Strings.kt` 中 `isZhCN` 写死 `country == "CN"` 导致繁中回退英文的问题；清理设置页、弹窗与提示中的硬编码中文；考虑迁回 Android 资源系统以支持 Android 13+ 单应用语言。
- [x] **离线封面按专辑去重**：`OfflineDownloadManager` 按 `sourceId + albumRatingKey` 共享本地封面，避免整张专辑下载时每首歌重复存储相同图片。
- [x] **代码结构瘦身与历史债务清理**：
  - 拆分超大文件：`PlaybackSection.kt`、`AppStatePlaybackCoordinator.kt`、`LocalPlaybackEngine.kt`、`AlbumLocalStore.kt`。
  - 统一网络层到已引入的 OkHttp，减少裸 `HttpURLConnection` / 手写 `Socket` HTTP 解析。
  - 清理 Plex 移除后的残留死代码（如 `reportTimelineSafelyLocal` / `scrobbleIfNeededLocal`）并评估合并 `TABLE_TRACKS` 与 `TABLE_ALBUM_TRACKS`。

---

## 三、待补齐的功能（产品体验路线图）

### P1 · 高频核心体验
- [ ] **播放队列编辑**：支持队列内移除单曲、拖拽调整顺序（同步 ExoPlayer 与 Sonos 队列）、清空队列、将当前队列另存为歌单。
- [ ] **本机通知栏/锁屏「收藏」按钮**：为 `LocalPlaybackService` 的 `MediaSession` 补齐收藏自定义操作，与 Sonos 通知栏对齐。
- [ ] **歌单体验补齐**：支持一键下载整个歌单、歌单内拖拽重排曲目顺序、歌单内筛选/搜索。
- [ ] **下载策略设置**：新增「仅 Wi-Fi 下载」开关、支持选择转码为 Opus 离线下载以节省手机存储空间。
- [ ] **离线本地搜索兜底**：断网或仅听离线库时，`SearchSection` 自动降级检索本地 SQLite（专辑/艺人/歌曲/已下载），并支持「仅看已下载」筛选。
- [ ] **睡眠定时器增强**：新增「播完当前曲目后停止」选项。

### P2 · 进阶差异化能力（Plexamp / Roon + Sonos 一等公民）
- [ ] **Sonos 多房间进阶控制**：支持多房间编组/解组（Group / Ungroup）、组内各音箱独立音量微调与静音、以及手动输入 Sonos IP 兜底发现。
- [ ] **032 相似曲目电台与自动续播**：重新接入 032 服务端已有的 `GET /api/v1/tracks/{id}/similar` 与 `/api/v1/tracks/path`，支持「由当前曲目开启电台」及队列播完自动续播。
- [ ] **响度均衡（ReplayGain）**：解决跨专辑/每日推荐/AI 歌单混播时音量忽大忽小的问题。
- [ ] **均衡器（EQ）/ 播放倍速 / 淡入淡出（Crossfade）**：基于本机自定义 `AudioSink` 链路扩展音频效果。
- [ ] **歌词体验增强**：支持手动调整歌词时间轴偏移（如 `±0.5s`）与歌词分享卡片。
- [ ] **Android 生态集成**：桌面播放小组件（AppWidget）与 `MediaLibraryService`（Android Auto / 车机与蓝牙媒体库浏览）。
