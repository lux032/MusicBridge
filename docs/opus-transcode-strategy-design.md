# Sonos Opus 音频转码双模式投播方案设计文档

- **日期**：2026-08-20
- **文档状态**：待实现（RFC / 架构设计）
- **目标功能**：在 MusicBridge 设置中增加「Opus 转码策略」切换开关，支持“直连秒播 (MP3 320k)”与“无损中继 (FLAC 本地缓存)”双模式。

---

## 1. 背景与技术成因

### 1.1 问题现状
1. **Sonos 硬件限制**：Sonos 全系列音箱（包括 Sonos Five）原生不支持 Opus 编码格式。
2. **Plex 实时转码特性**：
   - 当请求 Plex 实时将 Opus 转码为 FLAC 时，Plex 采用 `Transfer-Encoding: chunked` 发送无定长（No `Content-Length`）裸流。
   - Sonos 播放 FLAC 时将其视为离线文件容器，强制要求 `Content-Length` 和 Range 支持。直连时 Sonos 会在播放约 6 秒（缓冲区播完）后静默卡死。
3. **现有方案的优缺点**：
   - MusicBridge 目前通过 `PlexTranscodeProxy` 在手机本地建立 HTTP 代理服务器，将 Plex 的 Chunked FLAC 下载为临时文件并补齐 `Content-Length` 与 Range 支持。
   - **优点**：声学理论上 100% 保留了 Opus 解码后的全部 PCM 精度（无二次有损压缩）。
   - **缺点**：起播需缓冲等待 4~6 秒；消耗双倍家庭 Wi-Fi 带宽；手机需常驻 CPU/闪存写入；手机锁屏省电杀后台或断网会导致音箱停播。

### 1.2 引入直连秒播（MP3 320k）的声学与体验依据
1. **声学盲听事实**：源音频本身已是网络有损格式（Opus），在最高规格的 320kbps MP3 重新编码下，人耳在 Sonos Five 等音箱环境下 100% 无法辨别其与 FLAC 的区别（二次损失低于人耳掩蔽阈值）。
2. **Sonos 对流式 MP3 的原生兼容**：Sonos 原生支持 MP3 Chunked 广播流。直接由 Plex 生成 MP3 转码 URL 投送给 Sonos，可实现 **毫秒级秒播、零手机资源消耗、手机关机音箱继续播放**（即 Symfonium 的经典默认策略）。
3. **原盘无损不受影响**：音乐库中原本就是 FLAC / ALAC / WAV 的真·无损曲目，无论何种模式均保持 **100% Direct Play（原盘直连透传）**。

---

## 2. 产品与交互设计

在「设置」页面增加配置卡片：

### 2.1 UI 原型示意

```text
┌─────────────────────────────────────────────────────────────┐
│ 🎵 Opus 投播策略                                            │
│ 选择针对 Sonos 无法直放的 Opus 音轨的处理方式                │
│                                                             │
│  ◉ 直连秒播 (MP3 320k) [推荐]                                │
│    Plex 实时转为 320kbps MP3 直投音箱。秒播起播，零手机耗电，   │
│    手机锁屏/离线不影响播放。                                   │
│                                                             │
│  ○ 无损中继 (FLAC 本地缓存)                                  │
│    Plex 转为 FLAC 并由手机代理转发。理论无损，但起播需缓冲      │
│    数秒，且播放期间手机需保持在网。                             │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 模式枚举定义

```kotlin
enum class OpusTranscodeMode(val id: String) {
    /** 直连秒播：Plex 实时转码 MP3 320k 直投 Sonos，绕过手机代理 */
    DIRECT_MP3("direct_mp3"),

    /** 无损中继：Plex 实时转码 FLAC，通过手机本地 PlexTranscodeProxy 代理投送 */
    PROXY_FLAC("proxy_flac");

    companion object {
        val DEFAULT = DIRECT_MP3
        fun fromId(id: String?): OpusTranscodeMode =
            values().firstOrNull { it.id == id } ?: DEFAULT
    }
}
```

---

## 3. 技术架构与数据流

```
                    ┌─────────────────────────────────────────────────────────────────────────┐
                    │                           播放请求发起 (Play Track)                      │
                    └─────────────────────────────────────────────────────────────────────────┘
                                                         │
                                    [检查 sourceAudioCodec == "opus"]
                                      /                            \
                                [是] /                              \ [否: FLAC/MP3/AAC 原盘]
                                    v                                v
                     [检查 OpusTranscodeMode]                  [Direct Play 直链]
                      /                    \                         │
       [DIRECT_MP3]  /                      \ [PROXY_FLAC]           │
                    v                        v                       │
    ┌───────────────────────┐   ┌────────────────────────┐           │
    │  Plex MP3 320k 转码    │   │   Plex FLAC 转码 URL   │           │
    │  (/start.mp3)         │   │   (/start.flac)        │           │
    └───────────────────────┘   └────────────────────────┘           │
                │                                    │               │
                │                                    v               │
                │                       ┌────────────────────────┐   │
                │                       │  PlexTranscodeProxy    │   │
                │                       │  (手机本地缓存 + 代理)  │   │
                │                       └────────────────────────┘   │
                │                                    │               │
                │ [直投 Plex MP3 URL]                 │ [投送本地 URL]  │ [投送 Plex 原盘 URL]
                │                                    │               │
                v                                    v               v
    ┌─────────────────────────────────────────────────────────────────────────┐
    │                       Sonos AVTransport 播放器                          │
    └─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. 核心代码改造点清单

### 4.1 持久化层：`AppPreferences.kt`
- 增加 Key：`KEY_OPUS_TRANSCODE_MODE`。
- 提供 `getOpusTranscodeMode(): OpusTranscodeMode` 与 `setOpusTranscodeMode(mode: OpusTranscodeMode)`。

### 4.2 全局状态层：`AppState.kt` & `AppStatePlaybackCoordinator.kt`
- 在 `AppState` 中暴露响应式状态：
  ```kotlin
  var opusTranscodeMode by mutableStateOf(AppPreferences.getOpusTranscodeMode(context))
  ```
- 提供更新方法 `updateOpusTranscodeMode(mode: OpusTranscodeMode)`。

### 4.3 URL 构建层：`PlexClient.kt`
修改 `buildPlaybackUrl` 与转码 Profile 逻辑：

```kotlin
private const val PLEX_MP3_320K_TRANSCODE_PROFILE =
    "add-transcode-target(type=musicProfile&context=streaming&protocol=http&container=mp3&audioCodec=mp3&maxAudioBitrate=320&replace=true)"

private const val PLEX_FLAC_TRANSCODE_PROFILE =
    "add-transcode-target(type=musicProfile&context=streaming&protocol=http&container=flac&audioCodec=flac&replace=true)"

fun buildPlaybackUrl(
    serverUri: String,
    ratingKey: String,
    partKey: String,
    sourceAudioCodec: String?,
    token: String,
    clientIdentifier: String,
    opusMode: OpusTranscodeMode = OpusTranscodeMode.DEFAULT
): String {
    if (!sourceAudioCodec.equals("opus", ignoreCase = true)) {
        return buildStreamUrl(serverUri, partKey, token)
    }

    return when (opusMode) {
        OpusTranscodeMode.DIRECT_MP3 -> buildPlexUrl(
            serverUri = serverUri,
            path = "/music/:/transcode/universal/start.mp3",
            queryParameters = listOf(
                "path" to "/library/metadata/$ratingKey",
                "mediaIndex" to "0",
                "partIndex" to "0",
                "protocol" to "http",
                "offset" to "0",
                "directPlay" to "0",
                "directStream" to "0",
                "directStreamAudio" to "0",
                "audioCodec" to "mp3",
                "maxAudioBitrate" to "320",
                "audioChannelCount" to "2",
                "location" to "lan",
                "X-Plex-Client-Identifier" to clientIdentifier,
                "X-Plex-Client-Profile-Name" to "generic",
                "X-Plex-Client-Profile-Extra" to PLEX_MP3_320K_TRANSCODE_PROFILE,
                "X-Plex-Product" to "MusicBridge",
                "X-Plex-Version" to "0.1.0",
                "X-Plex-Platform" to "Android",
                "X-Plex-Device-Name" to "MusicBridge",
                "X-Plex-Token" to token,
            )
        )
        OpusTranscodeMode.PROXY_FLAC -> buildPlexUrl(
            serverUri = serverUri,
            path = "/music/:/transcode/universal/start.flac",
            // 维持现有已验证的 FLAC 参数
            ...
        )
    }
}
```

- 对应调整 `playbackMimeType`：
  - `DIRECT_MP3` $\rightarrow$ `"audio/mpeg"`
  - `PROXY_FLAC` $\rightarrow$ `"audio/flac"`

### 4.4 代理旁路控制：`AppStatePlaybackCoordinator.kt` / `PlaybackSection.kt`
在投送播放时，当 `opusTranscodeMode == DIRECT_MP3`：
- **完全跳过** `PlexTranscodeProxy.preparePlaybackUrl()`。
- 直接将生成的 `start.mp3` URL 连同 MIME `audio/mpeg` 交给 `SonosController.playTrack()`。

### 4.5 UI 设置卡片：`SettingsSection.kt` / `HomeSection.kt`
- 增加单选 RadioButton 切换卡片，选中后即时更新并持久化。

---

## 5. 真机验证与测试用例

实施该功能后，需按以下测试用例进行验证：

| 编号 | 测试场景 | 预期行为 | 验证指标 |
| :--- | :--- | :--- | :--- |
| **TC-01** | `DIRECT_MP3` 模式播放 Opus 曲目 | 秒播起播，Sonos 持续播放 | 1. 起播延迟 < 1 秒；<br>2. 连续播放超过 30 秒不卡死；<br>3. Plex Dashboard 显示 `Transcode (Opus → MP3 320kbps)`。 |
| **TC-02** | `DIRECT_MP3` 模式下手机锁屏 / 断开 Wi-Fi | Sonos 正常播完整首歌 | 手机断网后音箱不中断，证明完全脱离手机依赖。 |
| **TC-03** | `PROXY_FLAC` 模式播放 Opus 曲目 | 维持现有稳定逻辑 | 1. 手机完成 512KB 缓存后起播；<br>2. Plex Dashboard 显示 `Transcode (Opus → FLAC)`；<br>3. 播放完整无卡顿。 |
| **TC-04** | 任意模式播放原始 FLAC / MP3 曲目 | 维持 Direct Play 原盘直连 | Plex Dashboard 显示 `Direct Play`，完全不受设置开关影响。 |

---
*文档已归档于 `docs/opus-transcode-strategy-design.md`，可作为后续功能开发的直接依据。*
