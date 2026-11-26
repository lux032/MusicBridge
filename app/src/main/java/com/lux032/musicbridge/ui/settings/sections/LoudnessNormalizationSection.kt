package com.lux032.musicbridge.ui.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.LocalArtworkPalette
import com.lux032.musicbridge.LoudnessMode
import com.lux032.musicbridge.LoudnessNormalizationSettings
import com.lux032.musicbridge.LoudnessPeakProtection
import com.lux032.musicbridge.LoudnessTarget
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.music.Music032LoudnessCapabilities
import com.lux032.musicbridge.ui.settings.components.SettingsDivider
import com.lux032.musicbridge.ui.settings.components.SettingsGroup
import com.lux032.musicbridge.ui.settings.components.SettingsItem
import com.lux032.musicbridge.ui.settings.components.SettingsSwitchItem
import com.lux032.musicbridge.ui.settings.dialogs.SettingOptionSheet

@Composable
fun LoudnessNormalizationSection(
    settings: LoudnessNormalizationSettings,
    capabilities: Music032LoudnessCapabilities?,
    cachedLibraryMedianLufs: Double?,
    isServerSupported: Boolean,
    activeSonosRoomName: String?,
    onEnabledChange: (Boolean) -> Unit,
    onTargetChange: (LoudnessTarget) -> Unit,
    onModeChange: (LoudnessMode) -> Unit,
    onPeakProtectionChange: (LoudnessPeakProtection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isTargetSheetVisible by remember { mutableStateOf(false) }
    var isModeSheetVisible by remember { mutableStateOf(false) }
    var isPeakSheetVisible by remember { mutableStateOf(false) }

    val effectiveMedian = capabilities?.libraryMedianLufs ?: cachedLibraryMedianLufs
    val groupDescription = when {
        !isServerSupported -> Strings.loudnessSectionDescUnsupported
        capabilities != null -> Strings.loudnessSectionDescServer(
            analyzed = capabilities.analyzedTracks,
            total = capabilities.totalTracks,
            medianLufs = effectiveMedian,
        )
        else -> Strings.loudnessSectionDescOffline(cachedLibraryMedianLufs)
    }

    if (isTargetSheetVisible) {
        SettingOptionSheet(
            title = Strings.loudnessTargetTitle,
            options = LoudnessTarget.entries,
            selected = settings.target,
            label = { Strings.loudnessTargetOptionLabel(it, effectiveMedian) },
            description = { Strings.loudnessTargetOptionDesc(it, effectiveMedian) },
            badge = { if (it == LoudnessTarget.LIBRARY_MEDIAN) Strings.loudnessRecommendedBadge else null },
            onSelect = onTargetChange,
            onDismiss = { isTargetSheetVisible = false },
        )
    }

    if (isModeSheetVisible) {
        SettingOptionSheet(
            title = Strings.loudnessModeTitle,
            options = LoudnessMode.entries,
            selected = settings.mode,
            label = Strings::loudnessModeLabel,
            description = Strings::loudnessModeDesc,
            badge = { if (it == LoudnessMode.SMART) Strings.loudnessRecommendedBadge else null },
            onSelect = onModeChange,
            onDismiss = { isModeSheetVisible = false },
        )
    }

    if (isPeakSheetVisible) {
        SettingOptionSheet(
            title = Strings.loudnessPeakTitle,
            options = LoudnessPeakProtection.entries,
            selected = settings.peakProtection,
            label = Strings::loudnessPeakLabel,
            description = Strings::loudnessPeakDesc,
            badge = { if (it == LoudnessPeakProtection.SOFT_LIMITER) Strings.loudnessRecommendedBadge else null },
            onSelect = onPeakProtectionChange,
            onDismiss = { isPeakSheetVisible = false },
        )
    }

    SettingsGroup(
        title = Strings.loudnessSectionTitle,
        description = groupDescription,
        modifier = modifier,
    ) {
        SettingsSwitchItem(
            title = Strings.loudnessSwitchTitle,
            subtitle = Strings.loudnessSwitchSubtitle,
            checked = settings.enabled && isServerSupported,
            onCheckedChange = onEnabledChange,
            icon = Icons.Filled.Equalizer,
            enabled = isServerSupported,
        )

        AnimatedVisibility(
            visible = settings.enabled && isServerSupported,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsDivider()
                SettingsItem(
                    title = Strings.loudnessTargetTitle,
                    subtitle = Strings.loudnessTargetSubtitle,
                    value = Strings.loudnessTargetShortValue(settings.target, effectiveMedian),
                    icon = Icons.Filled.Tune,
                    onClick = { isTargetSheetVisible = true },
                )
                SettingsDivider()
                SettingsItem(
                    title = Strings.loudnessModeTitle,
                    subtitle = Strings.loudnessModeSubtitle,
                    value = Strings.loudnessModeLabel(settings.mode),
                    icon = Icons.Filled.Album,
                    onClick = { isModeSheetVisible = true },
                )
                SettingsDivider()
                SettingsItem(
                    title = Strings.loudnessPeakTitle,
                    subtitle = Strings.loudnessPeakSubtitle,
                    value = Strings.loudnessPeakShortValue(settings.peakProtection),
                    icon = Icons.Filled.Shield,
                    onClick = { isPeakSheetVisible = true },
                )
            }
        }

        if (!isServerSupported) {
            SettingsDivider()
            LoudnessStatusNoticeRow(
                icon = Icons.Filled.Info,
                text = Strings.loudnessLegacyNotice,
            )
        } else if (settings.enabled && !activeSonosRoomName.isNullOrBlank()) {
            SettingsDivider()
            LoudnessStatusNoticeRow(
                icon = Icons.Filled.Speaker,
                text = Strings.loudnessSonosActiveNotice(activeSonosRoomName),
            )
        }
    }
}

@Composable
private fun LoudnessStatusNoticeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    val palette = LocalArtworkPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
    }
}
