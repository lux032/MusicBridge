package com.lux032.musicbridge.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.LocalCacheSize
import com.lux032.musicbridge.LocalOpusBitrate
import com.lux032.musicbridge.OfflineDownloadFormat
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.ui.settings.components.CacheQuotaBar
import com.lux032.musicbridge.ui.settings.components.DeviceStorageStatus
import com.lux032.musicbridge.ui.settings.components.SettingsDivider
import com.lux032.musicbridge.ui.settings.components.SettingsGroup
import com.lux032.musicbridge.ui.settings.components.SettingsItem
import com.lux032.musicbridge.ui.settings.components.SettingsSwitchItem
import com.lux032.musicbridge.ui.settings.components.formatStorageBytes
import com.lux032.musicbridge.ui.settings.dialogs.SettingOptionSheet

@Composable
fun StorageSection(
    localCacheSize: LocalCacheSize,
    localCacheUsedBytes: Long,
    offlineDownloadedBytes: Long,
    deviceUsableBytes: Long,
    deviceTotalBytes: Long,
    downloadWifiOnly: Boolean,
    downloadFormat: OfflineDownloadFormat,
    localOpusBitrate: LocalOpusBitrate,
    onLocalCacheSizeChange: (LocalCacheSize) -> Unit,
    onDownloadWifiOnlyChange: (Boolean) -> Unit,
    onDownloadFormatChange: (OfflineDownloadFormat) -> Unit,
    onClearLocalCache: () -> Unit,
    onClearAllDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isCacheSizeSheetVisible by remember { mutableStateOf(false) }
    var isDownloadFormatSheetVisible by remember { mutableStateOf(false) }
    var isConfirmClearCacheVisible by remember { mutableStateOf(false) }
    var isConfirmClearDownloadsVisible by remember { mutableStateOf(false) }

    if (isCacheSizeSheetVisible) {
        SettingOptionSheet(
            title = Strings.localCacheSizeTitle,
            options = LocalCacheSize.entries,
            selected = localCacheSize,
            label = LocalCacheSize::megabytesLabel,
            onSelect = onLocalCacheSizeChange,
            onDismiss = { isCacheSizeSheetVisible = false },
        )
    }

    if (isDownloadFormatSheetVisible) {
        SettingOptionSheet(
            title = Strings.downloadFormatTitle,
            options = OfflineDownloadFormat.entries,
            selected = downloadFormat,
            label = { option ->
                when (option) {
                    OfflineDownloadFormat.ORIGINAL -> Strings.downloadFormatOriginal
                    OfflineDownloadFormat.OPUS -> "${Strings.downloadFormatOpus} (${localOpusBitrate.label})"
                }
            },
            onSelect = onDownloadFormatChange,
            onDismiss = { isDownloadFormatSheetVisible = false },
        )
    }

    if (isConfirmClearCacheVisible) {
        AlertDialog(
            onDismissRequest = { isConfirmClearCacheVisible = false },
            title = { Text(Strings.confirmClearCacheTitle, color = AppColors.TextPrimary) },
            text = {
                Text(
                    Strings.confirmClearCacheBody(formatStorageBytes(localCacheUsedBytes)),
                    color = AppColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isConfirmClearCacheVisible = false
                        onClearLocalCache()
                    },
                ) {
                    Text(Strings.confirmClearAction, color = AppColors.ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmClearCacheVisible = false }) {
                    Text(Strings.cancel, color = AppColors.TextSecondary)
                }
            },
        )
    }

    if (isConfirmClearDownloadsVisible) {
        AlertDialog(
            onDismissRequest = { isConfirmClearDownloadsVisible = false },
            title = { Text(Strings.confirmClearDownloadsTitle, color = AppColors.TextPrimary) },
            text = {
                Text(
                    Strings.confirmClearDownloadsBody(formatStorageBytes(offlineDownloadedBytes)),
                    color = AppColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isConfirmClearDownloadsVisible = false
                        onClearAllDownloads()
                    },
                ) {
                    Text(Strings.confirmDelete, color = AppColors.ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmClearDownloadsVisible = false }) {
                    Text(Strings.cancel, color = AppColors.TextSecondary)
                }
            },
        )
    }

    SettingsGroup(
        title = Strings.storageAndCacheTitle,
        description = Strings.storageAndCacheDesc,
        modifier = modifier,
    ) {
        // --- 1. Streaming cache ---
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = Strings.onlineCacheHeader,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Text(
                text = Strings.onlineCacheDesc,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
        }

        CacheQuotaBar(
            usedCacheBytes = localCacheUsedBytes,
            totalCacheSizeBytes = localCacheSize.bytes,
        )

        SettingsDivider()

        SettingsItem(
            title = Strings.localCacheSizeTitle,
            subtitle = Strings.localCacheSizeSubtitle,
            value = localCacheSize.megabytesLabel,
            icon = Icons.Filled.Storage,
            onClick = { isCacheSizeSheetVisible = true },
        )

        SettingsDivider()

        SettingsItem(
            title = Strings.clearCache,
            subtitle = if (localCacheUsedBytes > 0L) Strings.cacheUsedSummary(formatStorageBytes(localCacheUsedBytes)) else Strings.noCacheYet,
            icon = Icons.Filled.CleaningServices,
            enabled = localCacheUsedBytes > 0L,
            onClick = { isConfirmClearCacheVisible = true },
        )

        SettingsDivider()

        // --- 2. Offline originals ---
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = Strings.offlineOriginalHeader,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Text(
                text = Strings.offlineOriginalDesc,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
        }

        DeviceStorageStatus(
            offlineDownloadedBytes = offlineDownloadedBytes,
            deviceUsableBytes = deviceUsableBytes,
            deviceTotalBytes = deviceTotalBytes,
        )

        SettingsDivider()

        SettingsSwitchItem(
            title = Strings.downloadWifiOnlyTitle,
            subtitle = Strings.downloadWifiOnlySubtitle,
            icon = Icons.Filled.Wifi,
            checked = downloadWifiOnly,
            onCheckedChange = onDownloadWifiOnlyChange,
        )

        SettingsDivider()

        SettingsItem(
            title = Strings.downloadFormatTitle,
            subtitle = when (downloadFormat) {
                OfflineDownloadFormat.ORIGINAL -> Strings.downloadFormatOriginalSubtitle
                OfflineDownloadFormat.OPUS -> Strings.downloadFormatOpusSubtitle(localOpusBitrate.label)
            },
            value = when (downloadFormat) {
                OfflineDownloadFormat.ORIGINAL -> Strings.downloadFormatOriginal
                OfflineDownloadFormat.OPUS -> Strings.downloadFormatOpusValue(localOpusBitrate.label)
            },
            icon = Icons.Filled.Audiotrack,
            onClick = { isDownloadFormatSheetVisible = true },
        )

        SettingsDivider()

        SettingsItem(
            title = Strings.clearAllDownloads,
            subtitle = if (offlineDownloadedBytes > 0L) Strings.downloadedBytesSummary(formatStorageBytes(offlineDownloadedBytes)) else Strings.noDownloadedTracks,
            icon = Icons.Filled.DeleteSweep,
            enabled = offlineDownloadedBytes > 0L,
            onClick = { isConfirmClearDownloadsVisible = true },
        )
    }
}
