package com.lux032.musicbridge.ui.settings.sections

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.lux032.musicbridge.local.PlaybackDiagnostics
import com.lux032.musicbridge.ui.settings.components.SettingsDivider
import com.lux032.musicbridge.ui.settings.components.SettingsGroup
import com.lux032.musicbridge.ui.settings.components.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AboutAndDiagnosticsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalArtworkPalette.current

    var isDiagnosticsExpanded by remember { mutableStateOf(false) }
    var snapshotStatus by remember { mutableStateOf("正在读取快照…") }

    LaunchedEffect(context, isDiagnosticsExpanded) {
        if (isDiagnosticsExpanded) {
            val count = withContext(Dispatchers.IO) { PlaybackDiagnostics.snapshotCount(context) }
            snapshotStatus = "已保存 $count 份快照"
        }
    }

    SettingsGroup(
        title = "关于与诊断",
        description = "版本信息与开发者排障工具",
        modifier = modifier,
    ) {
        // App Version item
        SettingsItem(
            title = "MusicBridge",
            subtitle = "Plex & 032 Music Server to Sonos Bridge",
            value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            icon = Icons.Filled.Info,
        )

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
                    text = "卡顿诊断与日志快照",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (isDiagnosticsExpanded) "点击收起诊断工具" else "点击展开排障日志与工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }

            Icon(
                imageVector = if (isDiagnosticsExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (isDiagnosticsExpanded) "收起" else "展开",
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
                    text = "听到卡顿后立即点击保存。快照包含最近 3 分钟日志，并高亮标记最后 60 秒。",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )

                Button(
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                PlaybackDiagnostics.saveManualSnapshot(context)
                            }
                            result.onSuccess { file ->
                                val count = withContext(Dispatchers.IO) { PlaybackDiagnostics.snapshotCount(context) }
                                snapshotStatus = "已保存快照：${file.name} (共 $count 份)"
                                Toast.makeText(context, "$snapshotStatus\n稍后可导出排查", Toast.LENGTH_LONG).show()
                            }.onFailure { error ->
                                snapshotStatus = "保存失败：${error.message}"
                                Toast.makeText(context, snapshotStatus, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent,
                    ),
                ) {
                    Text("抓取卡顿诊断快照")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val latest = withContext(Dispatchers.IO) {
                                PlaybackDiagnostics.latestSnapshot(context)
                            }
                            if (latest == null) {
                                Toast.makeText(context, "暂无可分享的诊断快照", Toast.LENGTH_LONG).show()
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
                                            "分享诊断快照",
                                        ),
                                    )
                                }.onFailure {
                                    Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("分享最新快照", color = AppColors.TextPrimary)
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
                                snapshotStatus = if (cleared) "已清空诊断快照" else "清空失败：外部存储不可用"
                                Toast.makeText(context, snapshotStatus, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Text("清空快照", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text(
                    text = "ADB 导出命令：",
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
