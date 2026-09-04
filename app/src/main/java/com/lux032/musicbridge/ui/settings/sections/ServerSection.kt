package com.lux032.musicbridge.ui.settings.sections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.LocalArtworkPalette
import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.MusicServerType
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.ui.settings.dialogs.ServerEditSheet

@Composable
fun ServerSection(
    serverProfiles: List<MusicServerProfile>,
    activeServerId: String,
    onSwitchServer: (String) -> Unit,
    onSaveServer: (MusicServerProfile, Boolean) -> Unit,
    onDeleteServer: (String) -> Unit,
    onRefreshHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalArtworkPalette.current
    var editingProfile by remember { mutableStateOf<MusicServerProfile?>(null) }
    var pendingDeleteProfile by remember { mutableStateOf<MusicServerProfile?>(null) }

    // Delete confirmation dialog
    pendingDeleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProfile = null },
            title = { Text("移除服务器？", color = AppColors.TextPrimary) },
            text = {
                Text(
                    "将从本机删除“${profile.displayName}”的连接配置，服务器上的音乐和数据不会被删除。",
                    color = AppColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteServer(profile.id)
                        pendingDeleteProfile = null
                    },
                ) {
                    Text("移除", color = AppColors.ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProfile = null }) {
                    Text("取消", color = AppColors.TextSecondary)
                }
            },
        )
    }

    // Server edit / add sheet
    editingProfile?.let { profile ->
        ServerEditSheet(
            initialProfile = profile,
            onSave = onSaveServer,
            onDismiss = { editingProfile = null },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Section Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "音乐服务器",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = "支持管理多个 Plex 或 032 Music Server 并随时切换",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }

            FilledTonalButton(
                onClick = { editingProfile = MusicServerProfile(type = MusicServerType.Music032) },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = palette.accent.copy(alpha = 0.15f),
                    contentColor = palette.accent,
                ),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加")
            }
        }

        // Server Cards
        if (serverProfiles.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = AppColors.CardSurface,
                border = BorderStroke(1.dp, AppColors.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "暂未配置音乐服务器",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextSecondary,
                    )
                    OutlinedButton(
                        onClick = { editingProfile = MusicServerProfile(type = MusicServerType.Music032) },
                        border = BorderStroke(1.dp, palette.accent),
                    ) {
                        Text("立即添加服务器", color = palette.accent)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                serverProfiles.forEach { profile ->
                    val isActive = profile.id == activeServerId

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onSwitchServer(profile.id) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isActive) palette.accent.copy(alpha = 0.12f) else AppColors.CardSurface,
                        border = BorderStroke(
                            1.dp,
                            if (isActive) palette.accent.copy(alpha = 0.6f) else AppColors.Border,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = if (profile.type == MusicServerType.Plex) Icons.Filled.Cloud else Icons.Filled.Storage,
                                contentDescription = null,
                                tint = if (isActive) palette.accent else AppColors.TextSecondary,
                                modifier = Modifier.size(24.dp),
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = profile.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = AppColors.TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (isActive) {
                                        AssistChip(
                                            onClick = {},
                                            label = { Text("当前", style = MaterialTheme.typography.labelSmall) },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = palette.accent.copy(alpha = 0.2f),
                                                labelColor = palette.accent,
                                            ),
                                            border = null,
                                            modifier = Modifier.height(24.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = "${profile.type.label} · ${profile.baseUrl.ifBlank { "自动发现" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            IconButton(onClick = { editingProfile = profile }) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "编辑服务器",
                                    tint = AppColors.TextSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            IconButton(onClick = { pendingDeleteProfile = profile }) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = "移除服务器",
                                    tint = AppColors.TextSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Refresh home button
        OutlinedButton(
            onClick = onRefreshHome,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, AppColors.BorderStrong),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.TextPrimary),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(Strings.refreshHome)
        }
    }
}
