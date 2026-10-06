package com.lux032.musicbridge.ui.settings.dialogs

import androidx.compose.foundation.background
import com.lux032.musicbridge.ChoiceRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.LocalArtworkPalette

/**
 * Reusable Material 3 bottom sheet for single-choice option selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingOptionSheet(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: ((T) -> String?)? = null,
    badge: ((T) -> String?)? = null,
) {
    val palette = LocalArtworkPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.backgroundBottom,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 6.dp),
                color = AppColors.TextPrimary,
            )

            Spacer(Modifier.height(4.dp))

            options.forEach { option ->
                val isSelected = option == selected
                val itemBadge = badge?.invoke(option)
                val itemDesc = description?.invoke(option)

                ChoiceRow(
                    selected = isSelected,
                    onClick = {
                        onSelect(option)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label(option),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = AppColors.TextPrimary,
                            )
                            if (!itemBadge.isNullOrBlank()) {
                                Text(
                                    text = itemBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.accent.copy(alpha = 0.16f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (!itemDesc.isNullOrBlank()) {
                            Text(
                                text = itemDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
