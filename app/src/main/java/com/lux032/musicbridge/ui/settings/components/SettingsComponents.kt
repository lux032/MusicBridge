package com.lux032.musicbridge.ui.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.LocalArtworkPalette
import java.util.Locale

/**
 * Standard group container for settings sections, with an optional header and a styled card surface.
 */
@Composable
fun SettingsGroup(
    title: String? = null,
    description: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!title.isNullOrBlank() || !description.isNullOrBlank()) {
            Column(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary,
                    )
                }
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = AppColors.CardSurface,
            border = BorderStroke(1.dp, AppColors.Border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}

/**
 * Standard interactive row inside a [SettingsGroup].
 */
@Composable
fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val palette = LocalArtworkPalette.current
    val rowModifier = modifier
        .fillMaxWidth()
        .then(
            if (onClick != null && enabled) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        )
        .padding(horizontal = 16.dp, vertical = 13.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) AppColors.TextSecondary else AppColors.TextTertiary,
                modifier = Modifier.size(22.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) AppColors.TextPrimary else AppColors.TextTertiary,
                fontWeight = FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
        }

        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) palette.accent else AppColors.TextTertiary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Standard switch toggle row inside a [SettingsGroup].
 */
@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val palette = LocalArtworkPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) AppColors.TextSecondary else AppColors.TextTertiary,
                modifier = Modifier.size(22.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) AppColors.TextPrimary else AppColors.TextTertiary,
                fontWeight = FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                uncheckedThumbColor = AppColors.TextSecondary,
                uncheckedTrackColor = AppColors.SurfaceMuted,
            ),
        )
    }
}

/**
 * Cache quota progress bar showing used cache bytes against the configured cache limit.
 */
@Composable
fun CacheQuotaBar(
    usedCacheBytes: Long,
    totalCacheSizeBytes: Long,
    modifier: Modifier = Modifier,
) {
    val palette = LocalArtworkPalette.current
    val totalCapacity = totalCacheSizeBytes.coerceAtLeast(1L).toFloat()
    val cacheFraction = (usedCacheBytes.toFloat() / totalCapacity).coerceIn(0f, 1f)
    val percentage = ((usedCacheBytes.toDouble() / totalCapacity.toDouble()) * 100.0).coerceIn(0.0, 100.0)
    val cacheColor = palette.accent
    val freeColor = AppColors.SurfaceMuted

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(freeColor),
        ) {
            if (cacheFraction > 0f) {
                Box(
                    modifier = Modifier
                        .weight(cacheFraction.coerceAtLeast(0.005f))
                        .height(10.dp)
                        .background(cacheColor)
                )
            }
            if (cacheFraction < 1f) {
                Box(
                    modifier = Modifier
                        .weight((1f - cacheFraction).coerceAtLeast(0.005f))
                        .height(10.dp)
                        .background(freeColor)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(cacheColor)
                )
                Text(
                    text = "已占用 ${formatStorageBytes(usedCacheBytes)} / 上限 ${formatStorageBytes(totalCacheSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                )
            }

            Text(
                text = String.format(Locale.US, "%.1f%%", percentage),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = cacheColor,
            )
        }
    }
}

/**
 * Storage status summary item for offline downloads and physical device storage capacity.
 */
@Composable
fun DeviceStorageStatus(
    offlineDownloadedBytes: Long,
    deviceUsableBytes: Long,
    deviceTotalBytes: Long,
    modifier: Modifier = Modifier,
) {
    val downloadColor = Color(0xFF64B5F6) // Soft light blue for downloads

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(downloadColor)
                )
                Text(
                    text = "离线原档占用",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }

            Text(
                text = formatStorageBytes(offlineDownloadedBytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
        }

        if (deviceTotalBytes > 0L) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "机身剩余可用空间",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
                Text(
                    text = "${formatStorageBytes(deviceUsableBytes)} / 总共 ${formatStorageBytes(deviceTotalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }
        }
    }
}

/**
 * Standard divider between items in a settings card.
 */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        color = AppColors.Border.copy(alpha = 0.5f),
        thickness = 0.5.dp,
    )
}

/**
 * Formats a byte count into a human-readable MB or GB string.
 */
fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024.0) {
        String.format(Locale.US, "%.1f GB", megabytes / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", megabytes)
    }
}
