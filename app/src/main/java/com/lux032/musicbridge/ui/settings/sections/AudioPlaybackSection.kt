package com.lux032.musicbridge.ui.settings.sections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lux032.musicbridge.LocalOpusBitrate
import com.lux032.musicbridge.LocalPrecacheCount
import com.lux032.musicbridge.LocalStreamingLimit
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.ui.settings.components.SettingsDivider
import com.lux032.musicbridge.ui.settings.components.SettingsGroup
import com.lux032.musicbridge.ui.settings.components.SettingsItem
import com.lux032.musicbridge.ui.settings.dialogs.SettingOptionSheet

@Composable
fun AudioPlaybackSection(
    wifiQuality: LocalStreamingLimit,
    cellularQuality: LocalStreamingLimit,
    localOpusBitrate: LocalOpusBitrate,
    wifiPrecacheCount: LocalPrecacheCount,
    cellularPrecacheCount: LocalPrecacheCount,
    onWifiQualityChange: (LocalStreamingLimit) -> Unit,
    onCellularQualityChange: (LocalStreamingLimit) -> Unit,
    onLocalOpusBitrateChange: (LocalOpusBitrate) -> Unit,
    onWifiPrecacheCountChange: (LocalPrecacheCount) -> Unit,
    onCellularPrecacheCountChange: (LocalPrecacheCount) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isWifiQualitySheetVisible by remember { mutableStateOf(false) }
    var isCellularQualitySheetVisible by remember { mutableStateOf(false) }
    var isOpusBitrateSheetVisible by remember { mutableStateOf(false) }
    var isWifiPrecacheSheetVisible by remember { mutableStateOf(false) }
    var isCellularPrecacheSheetVisible by remember { mutableStateOf(false) }

    if (isWifiQualitySheetVisible) {
        SettingOptionSheet(
            title = Strings.wifiQualityTitle,
            options = LocalStreamingLimit.entries,
            selected = wifiQuality,
            label = LocalStreamingLimit::label,
            onSelect = onWifiQualityChange,
            onDismiss = { isWifiQualitySheetVisible = false },
        )
    }

    if (isCellularQualitySheetVisible) {
        SettingOptionSheet(
            title = Strings.cellularQualityTitle,
            options = LocalStreamingLimit.entries,
            selected = cellularQuality,
            label = LocalStreamingLimit::label,
            onSelect = onCellularQualityChange,
            onDismiss = { isCellularQualitySheetVisible = false },
        )
    }

    if (isOpusBitrateSheetVisible) {
        SettingOptionSheet(
            title = Strings.localOpusBitrateTitle,
            options = LocalOpusBitrate.entries,
            selected = localOpusBitrate,
            label = LocalOpusBitrate::label,
            onSelect = onLocalOpusBitrateChange,
            onDismiss = { isOpusBitrateSheetVisible = false },
        )
    }

    if (isWifiPrecacheSheetVisible) {
        SettingOptionSheet(
            title = Strings.wifiPrecacheTitle,
            options = LocalPrecacheCount.entries,
            selected = wifiPrecacheCount,
            label = LocalPrecacheCount::label,
            onSelect = onWifiPrecacheCountChange,
            onDismiss = { isWifiPrecacheSheetVisible = false },
        )
    }

    if (isCellularPrecacheSheetVisible) {
        SettingOptionSheet(
            title = Strings.cellularPrecacheTitle,
            options = LocalPrecacheCount.entries,
            selected = cellularPrecacheCount,
            label = LocalPrecacheCount::label,
            onSelect = onCellularPrecacheCountChange,
            onDismiss = { isCellularPrecacheSheetVisible = false },
        )
    }

    SettingsGroup(
        title = "本机播放与音质",
        description = Strings.localPlaybackSectionDesc,
        modifier = modifier,
    ) {
        SettingsItem(
            title = Strings.wifiQualityTitle,
            subtitle = Strings.wifiQualityDesc,
            value = wifiQuality.label,
            icon = Icons.Filled.NetworkWifi,
            onClick = { isWifiQualitySheetVisible = true },
        )
        SettingsDivider()

        SettingsItem(
            title = Strings.cellularQualityTitle,
            subtitle = Strings.cellularQualityDesc,
            value = cellularQuality.label,
            icon = Icons.Filled.CellTower,
            onClick = { isCellularQualitySheetVisible = true },
        )
        SettingsDivider()

        SettingsItem(
            title = Strings.localOpusBitrateTitle,
            subtitle = "非无损及转码时的 Opus 编码质量",
            value = localOpusBitrate.label,
            icon = Icons.Filled.Audiotrack,
            onClick = { isOpusBitrateSheetVisible = true },
        )
        SettingsDivider()

        SettingsItem(
            title = Strings.wifiPrecacheTitle,
            subtitle = "WiFi 下自动预拉取的后续曲目数",
            value = wifiPrecacheCount.label,
            icon = Icons.Filled.CloudDownload,
            onClick = { isWifiPrecacheSheetVisible = true },
        )
        SettingsDivider()

        SettingsItem(
            title = Strings.cellularPrecacheTitle,
            subtitle = "蜂窝移动网络下自动预拉取的曲目数",
            value = cellularPrecacheCount.label,
            icon = Icons.Filled.CloudDownload,
            onClick = { isCellularPrecacheSheetVisible = true },
        )
    }
}
