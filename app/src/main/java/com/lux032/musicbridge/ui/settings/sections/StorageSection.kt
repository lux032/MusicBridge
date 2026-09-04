package com.lux032.musicbridge.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Storage
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
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.ui.settings.components.CacheQuotaBar
import com.lux032.musicbridge.ui.settings.components.DeviceStorageStatus
import com.lux032.musicbridge.ui.settings.components.SettingsDivider
import com.lux032.musicbridge.ui.settings.components.SettingsGroup
import com.lux032.musicbridge.ui.settings.components.SettingsItem
import com.lux032.musicbridge.ui.settings.components.formatStorageBytes
import com.lux032.musicbridge.ui.settings.dialogs.SettingOptionSheet

@Composable
fun StorageSection(
    localCacheSize: LocalCacheSize,
    localCacheUsedBytes: Long,
    offlineDownloadedBytes: Long,
    deviceUsableBytes: Long,
    deviceTotalBytes: Long,
    onLocalCacheSizeChange: (LocalCacheSize) -> Unit,
    onClearLocalCache: () -> Unit,
    onClearAllDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isCacheSizeSheetVisible by remember { mutableStateOf(false) }
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

    if (isConfirmClearCacheVisible) {
        AlertDialog(
            onDismissRequest = { isConfirmClearCacheVisible = false },
            title = { Text("清空音频缓存？", color = AppColors.TextPrimary) },
            text = {
                Text(
                    "将删除本机已缓存的 ${formatStorageBytes(localCacheUsedBytes)} 临时音频文件。离线下载的原档不会被删除。",
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
                    Text("确定清空", color = AppColors.ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmClearCacheVisible = false }) {
                    Text("取消", color = AppColors.TextSecondary)
                }
            },
        )
    }

    if (isConfirmClearDownloadsVisible) {
        AlertDialog(
            onDismissRequest = { isConfirmClearDownloadsVisible = false },
            title = { Text("清空已下载离线原档？", color = AppColors.TextPrimary) },
            text = {
                Text(
                    "将彻底删除保存在本机的 ${formatStorageBytes(offlineDownloadedBytes)} 离线原档歌曲。删除后需在联网状态重新下载。",
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
                    Text("确定删除", color = AppColors.ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmClearDownloadsVisible = false }) {
                    Text("取消", color = AppColors.TextSecondary)
                }
            },
        )
    }

    SettingsGroup(
        title = "存储与缓存",
        description = "分别管理在线播放临时缓存与离线无损原档",
        modifier = modifier,
    ) {
        // --- 1. 在线播放缓存子面板 ---
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = "在线播放缓存",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Text(
                text = "试听与在线播放时自动生成的临时缓存，受配额上限约束自动循环覆盖",
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
            subtitle = "自动清理超过设定上限的旧缓存",
            value = localCacheSize.megabytesLabel,
            icon = Icons.Filled.Storage,
            onClick = { isCacheSizeSheetVisible = true },
        )

        SettingsDivider()

        SettingsItem(
            title = Strings.clearCache,
            subtitle = if (localCacheUsedBytes > 0L) "已缓存 ${formatStorageBytes(localCacheUsedBytes)}" else "当前无缓存",
            icon = Icons.Filled.CleaningServices,
            enabled = localCacheUsedBytes > 0L,
            onClick = { isConfirmClearCacheVisible = true },
        )

        SettingsDivider()

        // --- 2. 离线无损原档子面板 ---
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = "离线无损原档",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Text(
                text = "手动下载的高规格完整音轨，不受缓存上限影响，支持完全脱网播放",
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

        SettingsItem(
            title = Strings.clearAllDownloads,
            subtitle = if (offlineDownloadedBytes > 0L) "已下载 ${formatStorageBytes(offlineDownloadedBytes)}" else "暂无已下载歌曲",
            icon = Icons.Filled.DeleteSweep,
            enabled = offlineDownloadedBytes > 0L,
            onClick = { isConfirmClearDownloadsVisible = true },
        )
    }
}
