# Design

## Context

`MusicBridge` 是基于 Kotlin、Jetpack Compose 与 Media3 ExoPlayer 的 Android 032 客户端（`minSdk = 24`，`compileSdk = 36`），本机播放由 `LocalPlaybackEngine` 驱动，Sonos 投放由独立的 `SonosSessionPlayer` / `PlaybackForegroundService` 驱动（音频流不经过手机解码）。

`LocalPlaybackEngine` 已在 `DefaultRenderersFactory.buildAudioSink` 中挂载自定义 `AudioProcessor` 链（当前为 `TeeAudioProcessor(AudioSpectrumTap)`），本地元数据由原生 `SQLiteOpenHelper`（`AlbumDatabaseHelper`，当前 `DATABASE_VERSION = 20`）和 `PlaybackSessionStore`（JSON 文件 + `SharedPreferences`）持久化。需求背景见 `proposal.md`，完整行为契约见 `specs/loudness-normalization/spec.md`。

## Goals / Non-Goals

**Goals:**
- 算法、默认值、档位文案与桌面端 `032player-rs`（`v0.2.34`）完全对齐，让用户在桌面与手机间获得一致听感。
- 在 Media3 PCM 管线内纯 Kotlin 实现增益与 5 ms 立体声联动前瞻 `SoftPeakLimiter`，跨 Android 7-16 与所有本机输出设备（蓝牙、USB-C DAC、扬声器、Android Auto）零兼容差异。
- 支持播放中实时开关与调参无爆音即时生效、离线下载曲目生效、蓝牙/通知栏冷启动续播生效。
- 保持本机音频管线原生位深（16/24/32-bit/Float）与采样率，且不向 Sonos 投放链路泄漏任何本机增益状态。

**Non-Goals:**
- 不在手机端执行离线响度扫描或 FFmpeg 音频分析（所有 LUFS / dBTP 均由 032 服务端计算下发）。
- 不尝试干预 Sonos 音箱内置音量或转码流（避免与 Sonos 音箱端控制打架）。
- 不引入第三方 C++/NDK DSP 库或 Android 系统 `AudioEffect`（`LoudnessEnhancer` / `DynamicsProcessing`）。

## Decisions

### 1. 音频处理方式：Media3 `BaseAudioProcessor` vs Android `AudioEffect`（`DynamicsProcessing` / `LoudnessEnhancer`）
- **决策**：在 `LocalPlaybackEngine` 的 `DefaultAudioSink` 中，将自研纯 Kotlin `LoudnessAudioProcessor`（继承 `BaseAudioProcessor`）插在 `TeeAudioProcessor(AudioSpectrumTap)` 之前。
- **理由**：
  1. `DynamicsProcessing` 仅限 API 28+（本项目 `minSdk = 24`），且各家国产 ROM、蓝牙协议栈及部分 USB DAC 直出模式下 `AudioEffect` 经常失效、出现爆音或被系统音效覆盖。
  2. `LoudnessEnhancer` 无法做负增益（衰减），也无法提供精确的 `-1.0 dBTP` 立体声联动前瞻限幅。
  3. Media3 `BaseAudioProcessor` 已经在 `LocalPlaybackEngine` 中稳定运行（`AudioSpectrumTap`），运行在 ExoPlayer 单一音频渲染线程上，无锁（通过 `@Volatile` 不可变参数快照发布目标计划），支持 `ENCODING_PCM_16BIT` / `ENCODING_PCM_24BIT` / `ENCODING_PCM_32BIT` / `ENCODING_PCM_FLOAT` 原样输入输出，并且挂在 `TeeAudioProcessor` 之前还能让频谱可视化真实反映均衡后的电平。
  4. **关键实现细节**：为了支持「播放中打开开关即时生效（无需重启曲目或重配 AudioSink）」，`LoudnessAudioProcessor.isActive()` 在音频格式为受支持的 PCM 编码时始终返回 `true`（与 `TeeAudioProcessor` 一致）；当响度均衡关闭且当前平滑增益已回到 `1.0` 且限幅器旁路时，`queueInput()` 直接执行零 DSP 的 `ByteBuffer.put()` 位精确直通拷贝，关闭态零算法开销、零相位/延迟变化。

### 2. 响度规划数学模型与 `SoftPeakLimiter` 实现（与桌面端 100% 统一）
- **决策**：新增纯 Kotlin 无 Android 依赖的 `local/LoudnessNormalization.kt` 与 `local/SoftPeakLimiter.kt`，完整复刻 `032player-rs`（`crates/core/src/loudness.rs` 与 `crates/player/src/limiter.rs`）的公式与参数：
  - 目标枚举 `LoudnessTarget`：
    - `LibraryMedian`（默认）：`libraryMedianLufs?.coerceIn(-16.0, -8.0) ?: -11.0`
    - `Loud`：`-11.0`
    - `Standard`：`-14.0`
    - `Dynamic`：`-18.0`
  - 对齐方式 `LoudnessMode`：`Smart`（默认）、`Track`、`Album`。
    - `Smart` 规则：非 `PlaybackMode.Shuffle`、当前索引 `< manualQueueCount`（不在相似自动续播区）、手动队列前曲或后曲具有相同非空 `albumRatingKey`、且当前曲目专辑响度与真峰值齐全时选专辑响度；其余选单曲响度。
  - 峰值保护 `LoudnessPeakProtection`：
    - `SoftLimiter`（默认，最大压限 `3.5 dB`）
    - `StrongLimiter`（最大压限 `6.0 dB`）
    - `StrictLinear`（最大压限 `0.0 dB`，严格线性零限幅）
  - 增益计算（Android 侧设备音量由系统硬件调节，ExoPlayer 内部音量固定 `1.0`，对应 `userVolDb = 0.0`）：
    - `rawGain = targetLufs - measuredLufs`
    - `linearHeadroom = -1.0 - truePeakDb`
    - `appliedGain = min(rawGain, linearHeadroom + maxGr).coerceIn(-24.0, 18.0)`
    - `overshoot = max(0.0, truePeakDb + appliedGain - (-1.0))`
    - `useLimiter = peakProtection != StrictLinear && appliedGain > 0.05 && overshoot > 0.05`（未抬升曲目零限幅纯线性/直通）
    - 缺失响度回退：`appliedGain = libraryMedianLufs?.let { (targetLufs - it).coerceIn(-6.0, 0.0) } ?: 0.0`，`useLimiter = false`
  - `SoftPeakLimiter`：5 ms 环形前瞻缓冲（零 GC 预分配 `FloatArray`）、立体声/多声道全通道联动峰值检测、`-1.0 dBTP` 阈值（`10^(-1/20) ≈ 0.89125`）、线性平滑攻击（5 ms 前瞻窗口内到达目标增益）与 60 ms 指数释放，所有热路径零对象分配。
  - `LoudnessAudioProcessor` 增益平滑：每次计划变更采用 64 帧（44.1/48 kHz 下约 1.3-1.5 ms）线性斜坡平滑过渡，彻底消除实时切开关/切档位咔嗒声；在 `onFlush()` 时重置限幅器状态并立即对齐最新目标增益，防止 seek/手动切歌残留上一位置的 5 ms 音频尾巴。

### 3. 队列元数据流转与冷启动一致性
- **决策**：将响度配置与队列每项的响度元数据直接挂到 `LocalPlaybackEngine`（通过 `LocalQueueItem` 新增轻量可空响度字段及 `albumRatingKey`），并在 `LocalPlaybackEngine` 内统一计算与刷新当前 `LoudnessGainPlan`：
  - `LocalPlaybackEngine` 持有当前 `LoudnessNormalizationSettings`、当前服务器缓存的 `libraryMedianLufs`、当前 `PlaybackMode` 与 `manualQueueCount`。
  - 在 `setQueue`、`prepareColdStartQueue`、`onMediaItemTransition`、`playAt`、`insertQueueItems`、`moveQueueItem`、`removeQueueItem`、`replaceUpcomingAfter`、`setPlaybackMode`、`setLoudnessConfig`、`updateTrackLoudness` 等入口统一调用 `recomputeAndApplyLoudness()`，更新 `loudnessAudioProcessor` 并通过回调/只读属性暴露 `currentLoudnessPlan`。
  - `LocalPlaybackService` 在冷启动创建 `LocalPlaybackEngine` 时直接从 `AppPreferences` 读取响度设置与缓存的 `libraryMedianLufs` 注入引擎；同时 `PlaybackSessionStore` 的 `queue.json` 序列化/反序列化这 5 个可空响度字段，确保无 UI 冷启动与离线恢复完全一致。
  - **为什么放在 `LocalPlaybackEngine` 而非只在 `AppStateLocalPlaybackCoordinator`**：如果只在 `AppState` 里算增益，应用进程被系统回收后通过蓝牙耳机或通知栏冷启动 `LocalPlaybackService` 时，`AppState` 尚未创建，响度均衡会失效直到用户打开 App。放在引擎内后，前后台与冷启动路径完全统一。

### 4. SQLite v20 -> v21 增量迁移与旧缓存补齐
- **决策**：
  - `AlbumDatabaseHelper.DATABASE_VERSION` 从 `20` 升至 `21`，新增 5 个列常量：`loudness_lufs`、`true_peak_db`、`loudness_range`、`album_loudness_lufs`、`album_true_peak_db`（均为可空 `REAL`）。
  - `onCreate` 与 `onUpgrade`（`oldVersion < 21`）使用既有的幂等 `addColumnIfMissing` 辅助方法为 `tracks`、`album_tracks`、`playlist_tracks`、`downloaded_tracks` 四张表安全加列，零数据丢失。
  - 离线播放 `resolveTrackWithLocalDownload` 合并策略：若旧版下载的 `downloaded_tracks` 行缺少响度列，回退复用传入 `track`（来自最新曲库/接口）的响度字段，避免用户重下音频文件。
  - 在线旧缓存按需补齐：当本机播放起播或切歌时，若响度均衡已开启、当前曲目是 032 曲目、服务器支持 `features.loudness` 且当前曲目 `loudnessLufs == null`，由 `AppStateLocalPlaybackCoordinator` 发起非阻塞 `GET /api/v1/tracks/{id}` 拉取最新响度，成功则更新 `miniPlayerState.tracks` 与 `localPlaybackEngine` 的该项响度并即时重算增益（每队列同曲目仅尝试一次，避免无响度曲目反复请求）。

### 5. `Music032Capabilities` 与 `AppPreferences` 持久化
- **决策**：
  - `Music032Capabilities` 新增 `loudness: Music032LoudnessCapabilities?`（含 `available`、`analyzedTracks`、`totalTracks`、`libraryMedianLufs`），并扩展 `Music032CapabilitiesStore.parse` 兼容新旧服务端响应。
  - `AppPreferences` 新增全局键（`loudness_normalization_enabled_v1`、`loudness_target_v1`、`loudness_mode_v1`、`loudness_peak_protection_v1`）以及按 `sourceId` 分键缓存的 `loudness_library_median_lufs_v1_<sourceId>`（在 `clearSourceData` 中同步清理）。每次成功获取服务端能力且返回有限 `libraryMedianLufs` 时自动刷新本地缓存。

### 6. 设置页与播放详细信息 UI
- **决策**：
  - 在 `AudioPlaybackSection.kt` 中（位于「自动续播相似歌曲」下方或本机播放控制区），增加「响度均衡」开关行（复用 `SettingsSwitchRow`）及说明文案；当总开关开启时，展开展示「响度目标」「对齐方式」「峰值保护」三项（复用 `SettingsRow` + `SettingOptionSheet`），并展示当前服务器状态（已分析曲数与曲库中位数参考，或在旧版服务器下提示当前服务器不支持响度均衡）。
  - 在 `PlaybackSection.kt` 的 `trackDetailText`（注入 `PlaybackMoreSheet` 的「详细信息」折叠区）中，保留既有音质标签的同时，换行追加响度摘要（曲目集成响度 LUFS / 真峰值 dBTP / 专辑响度；开启响度均衡且当前输出为本机时追加实际应用增益、目标基准及限幅器状态）。

## Risks / Trade-offs

- **[无缝切歌边界存在 AudioTrack 缓冲提前量]** -> Media3 `DefaultAudioSink` 在同格式无缝切歌时可能已向 `AudioTrack` 预写入约 200-500 ms 的下一曲开头 PCM，而 `onMediaItemTransition(REASON_AUTO)` 在播放游标跨入下一曲时回调。直接清空或重建 AudioSink 会破坏无缝播放并引发爆音，因此采用「在 `onMediaItemTransition` 即时更新目标增益 + 64 帧平滑斜坡」的方案：保留 100% 真无缝播放、零爆音，且几乎所有曲目前 0.3-0.5 秒均为前奏/渐入，实听完全平滑；手动切歌与 seek 走 `onFlush` 则精准从第 0 帧起生效。
- **[24-bit 打包 PCM 字节序与有符号扩展]** -> Media3 `ENCODING_PCM_24BIT` 为 3 字节小端有符号整数。解码时须对最高字节做有符号扩展（`(b2.toInt() shl 24) shr 8` 再与低字节按位或），写回时须严格钳位到 `[-8388608, 8388607]`，并通过专门的单元测试覆盖 PCM 16 / 24 / 32-bit 与 Float 的往返精度及边界钳位（含 `-1.0` / `+1.0` / 过载样本）。
- **[ARM64 Linux 开发机 Robolectric SQLite 兼容性]** -> 当前本地构建容器为 ARM64 Linux，Robolectric 依赖的原生 SQLite 库可能仅在 x86_64 CI Runner 上完整运行。规避方式：将响度规划器、`SoftPeakLimiter`、`LoudnessAudioProcessor` 与 JSON 解析写为纯 JVM 零 Android 依赖逻辑，确保本地即可用 Gradle 跑通全部核心单元测试，并将完整含 Robolectric 的 `:app:testDebugUnitTest` + Release APK 构建交给 GitHub Actions 验证。
- **[服务端尚未完成全部扫描或切换回旧版服务器]** -> 曲库中位数按 `sourceId` 分键缓存，切换服务器时仅使用当前活动服务器的中位数与能力状态，避免多服务器间误串中位数。

## Migration Plan

1. SQLite `DATABASE_VERSION` 20 -> 21 采用无损 `ALTER TABLE ... ADD COLUMN`（带 `addColumnIfMissing` 幂等保护），覆盖安装无需清数据，既有曲库与离线下载全部保留。
2. `AppPreferences` 与 `PlaybackSessionStore` 所有新字段均向后兼容缺失值，升级后默认响度均衡关闭，不影响用户原有听感；随时关闭开关即可恢复位精确直通。
3. 回滚策略：若发现任何异常，仅需关闭设置中的「响度均衡」总开关即可立即回到零 DSP 直通路径，无需卸载或清除数据。
