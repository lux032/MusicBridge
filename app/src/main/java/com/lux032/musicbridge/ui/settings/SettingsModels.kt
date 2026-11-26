package com.lux032.musicbridge.ui.settings

import com.lux032.musicbridge.HomeModuleConfig
import com.lux032.musicbridge.LocalCacheSize
import com.lux032.musicbridge.LocalOpusBitrate
import com.lux032.musicbridge.LocalPrecacheCount
import com.lux032.musicbridge.LocalStreamingLimit
import com.lux032.musicbridge.LoudnessMode
import com.lux032.musicbridge.LoudnessNormalizationSettings
import com.lux032.musicbridge.LoudnessPeakProtection
import com.lux032.musicbridge.LoudnessTarget
import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.OfflineDownloadFormat
import com.lux032.musicbridge.OpusTranscodeMode
import com.lux032.musicbridge.SonosHiResTranscodeMode
import com.lux032.musicbridge.defaultHomeLayout
import com.lux032.musicbridge.music.Music032ConnectionTestResult
import com.lux032.musicbridge.music.Music032LoudnessCapabilities

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
    val downloadWifiOnly: Boolean = true,
    val downloadFormat: OfflineDownloadFormat = OfflineDownloadFormat.DEFAULT,
    val autoPlaySimilarEnabled: Boolean = true,
    val homeLayout: List<HomeModuleConfig> = defaultHomeLayout(),
    val loudnessSettings: LoudnessNormalizationSettings = LoudnessNormalizationSettings(),
    val loudnessCapabilities: Music032LoudnessCapabilities? = null,
    val cachedLoudnessLibraryMedianLufs: Double? = null,
    val isLoudnessServerSupported: Boolean = true,
    val activeSonosRoomName: String? = null,
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
    val onDownloadWifiOnlyChange: (Boolean) -> Unit = {},
    val onDownloadFormatChange: (OfflineDownloadFormat) -> Unit = {},
    val onAutoPlaySimilarChange: (Boolean) -> Unit = {},
    val onLoudnessEnabledChange: (Boolean) -> Unit = {},
    val onLoudnessTargetChange: (LoudnessTarget) -> Unit = {},
    val onLoudnessModeChange: (LoudnessMode) -> Unit = {},
    val onLoudnessPeakProtectionChange: (LoudnessPeakProtection) -> Unit = {},
    val onRefreshLoudnessCapabilities: () -> Unit = {},
    val onClearLocalCache: () -> Unit,
    val onClearAllDownloads: () -> Unit,
    val onSaveServer: (MusicServerProfile, Boolean) -> String?,
    val onTestServerConnection: suspend (MusicServerProfile) -> Music032ConnectionTestResult,
    val onSwitchServer: (String) -> Unit,
    val onDeleteServer: (String) -> Unit,
    val onRefreshHome: () -> Unit,
    val loadLocalCacheUsedBytes: suspend () -> Long,
    val loadOfflineDownloadedBytes: suspend () -> Long,
    val onHomeLayoutChange: (List<HomeModuleConfig>) -> Unit = {},
    val onResetHomeLayout: () -> Unit = {},
)
