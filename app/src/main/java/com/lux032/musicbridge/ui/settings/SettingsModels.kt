package com.lux032.musicbridge.ui.settings

import com.lux032.musicbridge.LocalCacheSize
import com.lux032.musicbridge.LocalOpusBitrate
import com.lux032.musicbridge.LocalPrecacheCount
import com.lux032.musicbridge.LocalStreamingLimit
import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.OpusTranscodeMode
import com.lux032.musicbridge.SonosHiResTranscodeMode
import com.lux032.musicbridge.ai.AiSettings

/**
 * Encapsulates the UI state required by SettingsScreen.
 */
data class SettingsViewState(
    val serverProfiles: List<MusicServerProfile>,
    val activeServerId: String,
    val opusTranscodeMode: OpusTranscodeMode = OpusTranscodeMode.DEFAULT,
    val sonosHiResTranscodeMode: SonosHiResTranscodeMode = SonosHiResTranscodeMode.DEFAULT,
    val wifiQuality: LocalStreamingLimit,
    val cellularQuality: LocalStreamingLimit,
    val localOpusBitrate: LocalOpusBitrate,
    val wifiPrecacheCount: LocalPrecacheCount,
    val cellularPrecacheCount: LocalPrecacheCount,
    val localCacheSize: LocalCacheSize,
    val aiSettings: AiSettings,
)

/**
 * Encapsulates all event callbacks dispatched from the settings views.
 */
data class SettingsActions(
    val onOpusTranscodeModeChange: (OpusTranscodeMode) -> Unit,
    val onSonosHiResTranscodeModeChange: (SonosHiResTranscodeMode) -> Unit,
    val onWifiQualityChange: (LocalStreamingLimit) -> Unit,
    val onCellularQualityChange: (LocalStreamingLimit) -> Unit,
    val onLocalOpusBitrateChange: (LocalOpusBitrate) -> Unit,
    val onWifiPrecacheCountChange: (LocalPrecacheCount) -> Unit,
    val onCellularPrecacheCountChange: (LocalPrecacheCount) -> Unit,
    val onLocalCacheSizeChange: (LocalCacheSize) -> Unit,
    val onClearLocalCache: () -> Unit,
    val onClearAllDownloads: () -> Unit,
    val onSaveServer: (MusicServerProfile, Boolean) -> Unit,
    val onSwitchServer: (String) -> Unit,
    val onDeleteServer: (String) -> Unit,
    val onRefreshHome: () -> Unit,
    val onSaveAiSettings: (AiSettings) -> Unit,
    val loadLocalCacheUsedBytes: suspend () -> Long,
    val loadOfflineDownloadedBytes: suspend () -> Long,
)
