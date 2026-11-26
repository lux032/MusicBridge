# Tasks

## 1. 服务端能力位、曲目模型与 JSON 解析

- [x] 1.1 在 `Music032CapabilitiesStore.kt` 中新增 `Music032LoudnessCapabilities` 数据类并扩展 `Music032CapabilitiesStore.parse`，兼容解析 `features.loudness` 与 `loudness`（`available`、`analyzedTracks`、`totalTracks`、`libraryMedianLufs`）；编写单元测试覆盖新版完整字段、显式 `0.0`、中位数缺失及旧版无该字段响应并运行通过
- [x] 1.2 在 `MusicModels.kt`（`MusicTrack`）与 `Music032Json.kt`（`parseTrack`）中新增 5 个可空响度字段（`loudnessLufs`、`truePeakDb`、`loudnessRange`、`albumLoudnessLufs`、`albumTruePeakDb`），严格区分显式 `0.0` 与 `null`；编写单元测试验证正常值、显式 `0.0 dBTP`、非法 NaN/Infinity 与缺省字段解析并通过

## 2. 本地存储、离线下载与偏好持久化

- [x] 2.1 在 `AppModels.kt` 与 `AppPreferences.kt` 中新增 `LoudnessTarget`、`LoudnessMode`、`LoudnessPeakProtection`、`LoudnessNormalizationSettings` 及读写方法（含按 `sourceId` 缓存 `libraryMedianLufs` 与 `clearSourceData` 清理），并编写单元测试验证默认值、往返读写与未知字符串回落
- [x] 2.2 将 `AlbumDatabaseHelper.kt` 从 `DATABASE_VERSION = 20` 升级至 `21`，在 `onCreate` 与 `onUpgrade` 中使用 `addColumnIfMissing` 为 `tracks`、`album_tracks`、`playlist_tracks`、`downloaded_tracks` 四张表新增 5 个可空 `REAL` 响度列；同步更新 `AlbumStoreMappers.kt`、`OfflineDownloadManager.kt` 与 `PlaybackSessionStore.kt`，并在单元测试中验证 `PlaybackSessionStore` 曲目 JSON 响度往返（含 `0.0 dBTP`）与 `DownloadedAudioTrack.toTrackStream()` 字段透传

## 3. 响度规划算法与纯 Kotlin PCM 音频处理链

- [x] 3.1 新增 `local/LoudnessNormalization.kt`，实现与桌面端 `v0.2.34` 完全一致的响度规划器（`LibraryMedian` 钳制 `[-16, -8]` / `-11` / `-14` / `-18`、`Smart` / `Track` / `Album` 上下文切换、`SoftLimiter 3.5 dB` / `StrongLimiter 6.0 dB` / `StrictLinear 0 dB`、未抬升曲目零限幅规则、缺失响度保守回退）；编写全面单元测试覆盖所有档位、边界与 Smart 队列上下文（含随机播放与相似自动续播区）并运行通过
- [x] 3.2 新增 `local/SoftPeakLimiter.kt`，实现 5 ms 立体声/多声道联动前瞻软限幅器（`-1.0 dBTP` 阈值、线性攻击、60 ms 指数释放、零热路径分配与 `reset()` 清空缓冲）；编写单元测试验证稳态不失真、过载脉冲严格压限在阈值附近、立体声通道增益联动与 `reset()` 后无残留并运行通过
- [x] 3.3 新增 `local/LoudnessAudioProcessor.kt`（继承 Media3 `BaseAudioProcessor`），支持 PCM 16 / 24 / 32-bit 与 Float 原样输入输出、64 帧平滑增益斜坡、关闭态位精确直通与 `onFlush()` 重置；编写单元测试覆盖四种 PCM 编码往返、增益施加、限幅防削波、关闭态位精确直通与平滑切换并运行通过

## 4. 引擎接入、状态协调与冷启动一致性

- [x] 4.1 将 `LoudnessAudioProcessor` 挂载至 `LocalPlaybackEngine.kt` 的 `DefaultAudioSink` 处理链（置于 `TeeAudioProcessor(AudioSpectrumTap)` 之前），在 `LocalQueueItem` 中保留响度与 `albumRatingKey`，在引擎内统一维护配置与队列上下文并在起播、切歌、编辑队列、切换模式、实时调参与更新单曲响度时即时重算并刷新处理器（同时暴露当前 `LoudnessGainPlan`）；通过 `:app:compileDebugKotlin` 验证编译通过
- [x] 4.2 更新 `LocalPlaybackColdStart.kt`、`LocalPlaybackService.kt`、`AppState.kt` 与 `AppStateLocalPlaybackCoordinator.kt`：接入偏好初始化、能力位与曲库中位数同步缓存、离线下载响度合并回退、旧缓存曲目在线异步按需补齐（`GET /api/v1/tracks/{id}`）及实时调参转发，确保 Sonos 投放路径不受影响；验证编译与相关状态协调测试通过

## 5. 设置页控制与播放详细信息展示

- [x] 5.1 在 `core/Strings.kt`、`ui/settings/SettingsModels.kt`、`ui/settings/sections/AudioPlaybackSection.kt`、`ui/settings/SettingsScreen.kt` 与 `ui/MainActivity.kt` 中接入「响度均衡」总开关、响度目标 / 对齐方式 / 峰值保护选项弹窗、服务端已分析曲数与曲库中位数提示及旧版服务器提示；验证 `:app:compileDebugKotlin` 通过
- [x] 5.2 在 `ui/sections/PlaybackSection.kt` 的 `trackDetailText` 中补充当前曲目的响度物理量（集成响度、真峰值、专辑响度）与本机生效增益 / 限幅器状态（Sonos 播放时不显示本机补偿），并编写纯函数格式化单元测试验证各状态输出文案正确

## 6. 集成验证与构建检查

- [x] 6.1 运行 Gradle 本地单元测试（纯 JVM 单元测试集，含全部新增响度、限幅、PCM 处理器、JSON 与偏好测试）及 `:app:assembleDebug` 构建，确认全部通过且无新增编译警告（已通过 GitHub Actions `Run #45764264576` 全量 `:app:testDebugUnitTest`、`:app:assembleDebug` 与 `:app:assembleRelease` 验证）

## Workflow follow-up

- 提交代码并推送至 GitHub，等待 GitHub Actions 完成全量 `:app:testDebugUnitTest`（含 Robolectric SQLite 测试）与正式签名 APK 构建。
- 由用户在真实 Android 设备上安装 APK 实机测试（蓝牙 / 外放 / USB DAC、切歌、实时调参、离线播放与 Sonos 切换），确认无误后再同步主规格并归档本 OpenSpec 变更。
