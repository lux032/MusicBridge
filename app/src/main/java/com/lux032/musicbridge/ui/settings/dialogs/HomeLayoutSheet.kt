package com.lux032.musicbridge.ui.settings.dialogs

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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.HomeModuleConfig
import com.lux032.musicbridge.KeyColors
import com.lux032.musicbridge.LocalArtworkPalette
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.defaultHomeLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLayoutSheet(
    layout: List<HomeModuleConfig>,
    onLayoutChange: (List<HomeModuleConfig>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalArtworkPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDefault = layout == defaultHomeLayout()

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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = Strings.homeLayoutSheetTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(start = 4.dp),
                )
                TextButton(
                    onClick = onReset,
                    enabled = !isDefault,
                ) {
                    Text(
                        text = Strings.homeLayoutReset,
                        color = if (!isDefault) palette.accent else AppColors.TextTertiary,
                    )
                }
            }

            Text(
                text = Strings.homeLayoutSheetHint,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(2.dp))

            layout.forEachIndexed { index, item ->
                val canMoveUp = index > 0
                val canMoveDown = index < layout.lastIndex

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = if (item.visible) 0.065f else 0.03f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val updated = layout.toMutableList()
                                updated[index] = item.copy(visible = !item.visible)
                                onLayoutChange(updated)
                            }
                            .padding(start = 6.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (canMoveUp) {
                                        val updated = layout.toMutableList()
                                        updated[index] = updated[index - 1]
                                        updated[index - 1] = item
                                        onLayoutChange(updated)
                                    }
                                },
                                enabled = canMoveUp,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowUp,
                                    contentDescription = Strings.homeModuleMoveUp,
                                    tint = if (canMoveUp) AppColors.TextSecondary else AppColors.TextTertiary.copy(alpha = 0.4f),
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (canMoveDown) {
                                        val updated = layout.toMutableList()
                                        updated[index] = updated[index + 1]
                                        updated[index + 1] = item
                                        onLayoutChange(updated)
                                    }
                                },
                                enabled = canMoveDown,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = Strings.homeModuleMoveDown,
                                    tint = if (canMoveDown) AppColors.TextSecondary else AppColors.TextTertiary.copy(alpha = 0.4f),
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = item.module.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (item.visible) AppColors.TextPrimary else AppColors.TextTertiary,
                            )
                            if (!item.visible) {
                                Text(
                                    text = Strings.homeModuleHidden,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextTertiary,
                                )
                            }
                        }

                        Switch(
                            checked = item.visible,
                            onCheckedChange = { checked ->
                                val updated = layout.toMutableList()
                                updated[index] = item.copy(visible = checked)
                                onLayoutChange(updated)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = palette.onAccent,
                                checkedTrackColor = palette.accent,
                                checkedBorderColor = Color.Transparent,
                                uncheckedThumbColor = AppColors.TextSecondary,
                                uncheckedTrackColor = KeyColors.Tonal,
                                uncheckedBorderColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
