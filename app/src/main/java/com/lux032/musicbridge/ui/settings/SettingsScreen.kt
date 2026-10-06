package com.lux032.musicbridge.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.LocalAppLayoutInfo
import com.lux032.musicbridge.Strings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.lux032.musicbridge.ui.settings.sections.AboutAndDiagnosticsSection
import com.lux032.musicbridge.ui.settings.sections.AiSettingsSection
import com.lux032.musicbridge.ui.settings.sections.AudioPlaybackSection
import com.lux032.musicbridge.ui.settings.sections.ServerSection
import com.lux032.musicbridge.ui.settings.sections.SonosAdvancedSection
import com.lux032.musicbridge.ui.settings.sections.StorageSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main Settings screen container with unified grouped architecture,
 * asynchronous storage computation, and centered large-screen layout.
 */
@Composable
fun SettingsScreen(
    state: SettingsViewState,
    actions: SettingsActions,
    scrollState: ScrollState,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val context = LocalContext.current

    // Asynchronous cache and download storage metrics (High-2 constraint)
    var localCacheUsedBytes by remember { mutableLongStateOf(0L) }
    var offlineDownloadedBytes by remember { mutableLongStateOf(0L) }
    var deviceUsableBytes by remember { mutableLongStateOf(0L) }
    var deviceTotalBytes by remember { mutableLongStateOf(0L) }
    var refreshStorageTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshStorageTrigger) {
        val cache = withContext(Dispatchers.IO) { actions.loadLocalCacheUsedBytes() }
        val downloads = withContext(Dispatchers.IO) { actions.loadOfflineDownloadedBytes() }
        val (usable, total) = withContext(Dispatchers.IO) {
            val targetDir = context.getExternalFilesDir(null) ?: context.filesDir
            targetDir.usableSpace to targetDir.totalSpace
        }
        localCacheUsedBytes = cache
        offlineDownloadedBytes = downloads
        deviceUsableBytes = usable
        deviceTotalBytes = total
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(
                    start = layoutInfo.horizontalPadding,
                    top = 16.dp,
                    end = layoutInfo.horizontalPadding,
                    bottom = bottomContentPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text(
                text = Strings.settings,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(start = 4.dp),
            )
            // 1. Music Servers Management
            ServerSection(
                serverProfiles = state.serverProfiles,
                activeServerId = state.activeServerId,
                onSwitchServer = actions.onSwitchServer,
                onSaveServer = actions.onSaveServer,
                onDeleteServer = actions.onDeleteServer,
                onRefreshHome = actions.onRefreshHome,
            )

            // 2. Audio Quality & Local Playback
            AudioPlaybackSection(
                wifiQuality = state.wifiQuality,
                cellularQuality = state.cellularQuality,
                localOpusBitrate = state.localOpusBitrate,
                wifiPrecacheCount = state.wifiPrecacheCount,
                cellularPrecacheCount = state.cellularPrecacheCount,
                onWifiQualityChange = actions.onWifiQualityChange,
                onCellularQualityChange = actions.onCellularQualityChange,
                onLocalOpusBitrateChange = actions.onLocalOpusBitrateChange,
                onWifiPrecacheCountChange = actions.onWifiPrecacheCountChange,
                onCellularPrecacheCountChange = actions.onCellularPrecacheCountChange,
            )

            // 3. Storage & Cache
            StorageSection(
                localCacheSize = state.localCacheSize,
                localCacheUsedBytes = localCacheUsedBytes,
                offlineDownloadedBytes = offlineDownloadedBytes,
                deviceUsableBytes = deviceUsableBytes,
                deviceTotalBytes = deviceTotalBytes,
                onLocalCacheSizeChange = actions.onLocalCacheSizeChange,
                onClearLocalCache = {
                    actions.onClearLocalCache()
                    refreshStorageTrigger++
                },
                onClearAllDownloads = {
                    actions.onClearAllDownloads()
                    refreshStorageTrigger++
                },
            )

            // 4. Sonos Transcoding & Hardware Policy
            SonosAdvancedSection(
                opusTranscodeMode = state.opusTranscodeMode,
                sonosHiResTranscodeMode = state.sonosHiResTranscodeMode,
                onOpusTranscodeModeChange = actions.onOpusTranscodeModeChange,
                onSonosHiResTranscodeModeChange = actions.onSonosHiResTranscodeModeChange,
            )

            // 5. AI Assistant
            AiSettingsSection(
                settings = state.aiSettings,
                onSave = actions.onSaveAiSettings,
            )

            // 6. About & Diagnostics
            AboutAndDiagnosticsSection(
                onRunPlexOffsetProbe = actions.runPlexOffsetProbe,
            )
        }
    }
}
