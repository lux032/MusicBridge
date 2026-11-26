# Proposal

## Why

032 服务端（`032music-server`）已经完成全曲库 EBU R128 响度离线分析，并在 `/api/v1/capabilities` 与各曲目接口暴露单曲与专辑的集成响度（LUFS）和真峰值（dBTP）；桌面端（`032player-rs`）也已完成对应实现并经实机验证定型。Android 客户端 `MusicBridge` 目前尚未消费这些响度数据，用户在混播歌单、跨年代专辑或相似歌曲自动续播时仍会遇到切歌忽大忽小的问题，因此需要在 `MusicBridge` 上实现与桌面端体验与算法完全对齐的本机响度均衡能力。

## What Changes

- **服务端能力与响度元数据解析**：
  - 扩展 `Music032Capabilities` / `Music032CapabilitiesStore`，兼容解析 `/api/v1/capabilities` 中的 `features.loudness` 与 `loudness` 统计对象（`available`、`analyzedTracks`、`totalTracks`、`libraryMedianLufs`）。
  - 扩展 `MusicTrack`、`DownloadedAudioTrack` 与 `Music032Json`，向后兼容解析 5 个响度物理量（`loudnessLufs`、`truePeakDb`、`loudnessRange`、`albumLoudnessLufs`、`albumTruePeakDb`），并严格区分显式 `0.0 dBTP` 与缺失值 `null`。
- **本地持久化与离线 / 冷启动支持**：
  - `AlbumDatabaseHelper` 从 `v20` 无损升级至 `v21`，为 `tracks`、`album_tracks`、`playlist_tracks`、`downloaded_tracks` 四张表增量添加 5 个可空 `REAL` 响度列。
  - `PlaybackSessionStore` 持久化曲目响度字段；`AppPreferences` 持久化响度开关、响度目标、对齐方式、峰值保护以及按服务器缓存的 `libraryMedianLufs`，确保离线下载播放与蓝牙/通知栏冷启动续播同样生效。
  - 播放已缓存但尚未含响度字段的旧记录时，在服务端支持响度能力的前提下异步按需拉取单曲详情补齐响度并即时更新当前均衡计划。
- **与桌面端完全一致的响度规划与纯 Kotlin PCM 音频处理链**：
  - 实现与桌面端 `v0.2.34` 一致的响度规划器：默认关闭；响度目标支持「跟随曲库中位数（默认，钳制 `[-16.0, -8.0] LUFS`，缺失回落 `-11.0 LUFS`）/ 饱满 `-11 LUFS` / 标准 `-14 LUFS` / 动态 `-18 LUFS`」；对齐方式支持「智能（默认）/ 按单曲 / 按专辑」；峰值保护支持「柔和限幅 `3.5 dB`（默认）/ 强力限幅 `6.0 dB` / 严格防削波 `0 dB`」；未抬升曲目（应用增益 `<= 0 dB`）自动零限幅直通。
  - 实现纯 Kotlin Media3 `LoudnessAudioProcessor`（平滑线性增益 + 5 ms 立体声联动前瞻 `SoftPeakLimiter`），挂载到 `LocalPlaybackEngine` 的 `DefaultAudioSink` 中、`TeeAudioProcessor(AudioSpectrumTap)` 之前，原生支持 PCM 16 / 24 / 32-bit 与 Float，不依赖 Android OEM 音效 API，且仅作用于本机播放（完全不影响 Sonos 投放）。
- **设置页控制与播放信息展示**：
  - 在设置页「音频与播放」分区（`AudioPlaybackSection`）增加「响度均衡」开关与三项参数选择（复用 `SettingOptionSheet`），并展示服务端已分析曲数与曲库中位数参考，以及仅作用于本机的说明。
  - 在播放页更多面板（`PlaybackMoreSheet`）的「详细信息」区域补充展示曲目响度、真峰值、专辑响度以及当前本机生效的增益补偿与限幅器状态。

## Capabilities

### New Capabilities
- `loudness-normalization`: 基于 032 服务端 EBU R128 响度元数据的 Android 本机响度均衡能力，覆盖元数据解析与离线持久化、高基准与有界软限幅规划、智能单曲/专辑切换、Media3 PCM 实时与无缝处理、冷启动续播一致性、设置持久化以及播放信息诊断展示。

### Modified Capabilities
<!-- 无既有 OpenSpec 规格需要修改 -->

## Impact

- **数据模型与网络层**：`MusicModels.kt`、`Music032CapabilitiesStore.kt`、`Music032Json.kt`、`Music032Client.kt`
- **本地存储与偏好**：`AlbumDatabaseHelper.kt`（v20 -> v21）、`AlbumStoreMappers.kt`、`OfflineDownloadManager.kt`、`PlaybackSessionStore.kt`、`AppPreferences.kt`、`AppModels.kt`
- **本机音频引擎与状态协调**：新增 `local/LoudnessNormalization.kt`、`local/SoftPeakLimiter.kt`、`local/LoudnessAudioProcessor.kt`；更新 `local/LocalPlaybackEngine.kt`、`local/LocalPlaybackColdStart.kt`、`local/LocalPlaybackService.kt`、`state/AppState.kt`、`state/AppStateLocalPlaybackCoordinator.kt`
- **界面与文案**：`ui/settings/SettingsModels.kt`、`ui/settings/SettingsScreen.kt`、`ui/settings/sections/AudioPlaybackSection.kt`、`ui/MainActivity.kt`、`ui/sections/PlaybackSection.kt`、`core/Strings.kt`
- **无第三方新增依赖**，完全基于现有 Media3 `BaseAudioProcessor` 与 Kotlin 标准库实现，不影响 Sonos 播放链路与既有离线缓存兼容性。
