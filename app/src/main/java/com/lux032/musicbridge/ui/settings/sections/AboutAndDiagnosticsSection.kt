package com.lux032.musicbridge.ui.settings.sections

import com.lux032.musicbridge.KeyButton
import com.lux032.musicbridge.KeyStyle
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.BuildConfig
import com.lux032.musicbridge.LocalArtworkPalette
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.local.PlaybackDiagnostics
import com.lux032.musicbridge.ui.settings.components.SettingsDivider
import com.lux032.musicbridge.ui.settings.components.SettingsGroup
import com.lux032.musicbridge.ui.settings.components.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AboutAndDiagnosticsSection(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalArtworkPalette.current

    var isDiagnosticsExpanded by remember { mutableStateOf(false) }
    var snapshotStatus by remember { mutableStateOf(Strings.diagnosticsReadingSnapshots) }

    LaunchedEffect(context, isDiagnosticsExpanded) {
        if (isDiagnosticsExpanded) {
            val count = withContext(Dispatchers.IO) { PlaybackDiagnostics.snapshotCount(context) }
            snapshotStatus = Strings.diagnosticsSavedCount(count)
        }
    }

    SettingsGroup(
        title = Strings.aboutAndDiagnosticsTitle,
        description = Strings.aboutAndDiagnosticsDesc,
        modifier = modifier,
    ) {
        // App Version item
        SettingsItem(
            title = "MusicBridge",
            subtitle = Strings.appSubtitle,
            value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            icon = Icons.Filled.Info,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SettingsDivider()
            SettingsItem(
                title = Strings.appLanguageTitle,
                subtitle = Strings.appLanguageSubtitle,
                value = Strings.appLanguageCurrentValue,
                icon = Icons.Filled.Language,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APP_LOCALE_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                },
            )
        }

        SettingsDivider()

        // Diagnostics collapsible header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isDiagnosticsExpanded = !isDiagnosticsExpanded }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(22.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = Strings.diagnosticsTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (isDiagnosticsExpanded) Strings.diagnosticsCollapseHint else Strings.diagnosticsExpandHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }

            Icon(
                imageVector = if (isDiagnosticsExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (isDiagnosticsExpanded) Strings.collapse else Strings.expand,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(20.dp),
            )
        }

        // Diagnostics collapsible content
        AnimatedVisibility(
            visible = isDiagnosticsExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = Strings.diagnosticsDescription,
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )

                KeyButton(
                    style = KeyStyle.Hi,
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                PlaybackDiagnostics.saveManualSnapshot(context)
                            }
                            result.onSuccess { file ->
                                val count = withContext(Dispatchers.IO) { PlaybackDiagnostics.snapshotCount(context) }
                                snapshotStatus = Strings.diagnosticsSnapshotSaved(file.name, count)
                                Toast.makeText(
                                    context,
                                    "$snapshotStatus\n${Strings.diagnosticsExportLaterHint}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }.onFailure { error ->
                                snapshotStatus = Strings.diagnosticsSaveFailed(error.message)
                                Toast.makeText(context, snapshotStatus, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(Strings.diagnosticsCaptureButton)
                }

                KeyButton(
                    onClick = {
                        scope.launch {
                            val latest = withContext(Dispatchers.IO) {
                                PlaybackDiagnostics.latestSnapshot(context)
                            }
                            if (latest == null) {
                                Toast.makeText(context, Strings.diagnosticsNoSnapshotToShare, Toast.LENGTH_LONG).show()
                            } else {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        latest,
                                    )
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                            Strings.diagnosticsShareChooserTitle,
                                        ),
                                    )
                                }.onFailure {
                                    Toast.makeText(context, Strings.diagnosticsShareFailed(it.message), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(Strings.diagnosticsShareButton, color = AppColors.TextPrimary)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = snapshotStatus,
                        color = AppColors.TextTertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    TextButton(
                        onClick = {
                            scope.launch {
                                val cleared = withContext(Dispatchers.IO) {
                                    PlaybackDiagnostics.clearSnapshots(context)
                                }
                                snapshotStatus = if (cleared) Strings.diagnosticsCleared else Strings.diagnosticsClearFailed
                                Toast.makeText(context, snapshotStatus, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Text(Strings.diagnosticsClearButton, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text(
                    text = Strings.diagnosticsAdbPullLabel,
                    color = AppColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                )

                SelectionContainer {
                    Text(
                        text = PlaybackDiagnostics.ADB_PULL_COMMAND,
                        color = AppColors.TextTertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
