package com.lux032.musicbridge.ui.settings.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.LocalArtworkPalette
import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.MusicServerType
import com.lux032.musicbridge.Strings

/**
 * Material 3 bottom sheet for adding or editing a music server profile.
 * Designed with IME insets and scroll safety to avoid keyboard clipping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditSheet(
    initialProfile: MusicServerProfile,
    onSave: (MusicServerProfile, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalArtworkPalette.current
    var draft by remember(initialProfile) { mutableStateOf(initialProfile) }
    val isNew = draft.id.isBlank()

    var isTokenVisible by remember { mutableStateOf(false) }
    var isMediaTokenVisible by remember { mutableStateOf(false) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.ime },
        containerColor = palette.backgroundBottom,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (isNew) "添加音乐服务器" else "编辑音乐服务器",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary,
            )

            // Server type chips
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicServerType.entries.forEach { type ->
                    val isSelected = draft.type == type
                    FilterChip(
                        selected = isSelected,
                        enabled = isNew,
                        onClick = {
                            if (draft.type != type) draft = draft.freshDraftForType(type)
                        },
                        label = { Text(type.label) },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.accent.copy(alpha = 0.2f),
                            selectedLabelColor = palette.accent,
                            selectedLeadingIconColor = palette.accent,
                        ),
                    )
                }
            }

            if (!isNew) {
                Text(
                    text = "服务器类型在保存后不可更改。如需使用另一种服务，请在主页点击“添加”创建独立配置。",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Display name
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("显示名称") },
                placeholder = {
                    Text(if (draft.type == MusicServerType.Plex) "家里的 Plex" else "032 Music Server")
                },
                singleLine = true,
                trailingIcon = {
                    if (draft.name.isNotEmpty()) {
                        IconButton(onClick = { draft = draft.copy(name = "") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "清空", tint = AppColors.TextTertiary)
                        }
                    }
                },
                colors = textFieldColors(),
            )

            // Base URL
            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { draft = draft.copy(baseUrl = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务器地址") },
                placeholder = {
                    Text(
                        if (draft.type == MusicServerType.Plex) "http://192.168.1.10:32400"
                        else "http://192.168.1.10:4533"
                    )
                },
                singleLine = true,
                trailingIcon = {
                    if (draft.baseUrl.isNotEmpty()) {
                        IconButton(onClick = { draft = draft.copy(baseUrl = "") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "清空", tint = AppColors.TextTertiary)
                        }
                    }
                },
                colors = textFieldColors(),
            )

            // Token
            OutlinedTextField(
                value = draft.token,
                onValueChange = { draft = draft.copy(token = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (draft.type == MusicServerType.Plex) "Plex Token" else "API Token") },
                singleLine = true,
                visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (draft.token.isNotEmpty()) {
                            IconButton(onClick = { draft = draft.copy(token = "") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "清空", tint = AppColors.TextTertiary)
                            }
                        }
                        IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                            Icon(
                                imageVector = if (isTokenVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (isTokenVisible) "隐藏内容" else "显示明文",
                                tint = AppColors.TextSecondary,
                            )
                        }
                    }
                },
                colors = textFieldColors(),
            )

            if (draft.type == MusicServerType.Music032) {
                // Media Token for 032
                OutlinedTextField(
                    value = draft.mediaToken,
                    onValueChange = { draft = draft.copy(mediaToken = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("媒体 Token") },
                    supportingText = { Text("必填：服务端 MUSIC_SERVER_MEDIA_TOKEN 的值。") },
                    isError = draft.mediaToken.isBlank(),
                    singleLine = true,
                    visualTransformation = if (isMediaTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (draft.mediaToken.isNotEmpty()) {
                                IconButton(onClick = { draft = draft.copy(mediaToken = "") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "清空", tint = AppColors.TextTertiary)
                                }
                            }
                            IconButton(onClick = { isMediaTokenVisible = !isMediaTokenVisible }) {
                                Icon(
                                    imageVector = if (isMediaTokenVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = if (isMediaTokenVisible) "隐藏内容" else "显示明文",
                                    tint = AppColors.TextSecondary,
                                )
                            }
                        }
                    },
                    colors = textFieldColors(),
                )
            } else {
                // Plex server name (optional)
                OutlinedTextField(
                    value = draft.server,
                    onValueChange = { draft = draft.copy(server = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Plex 服务器名称（可选）") },
                    singleLine = true,
                    trailingIcon = {
                        if (draft.server.isNotEmpty()) {
                            IconButton(onClick = { draft = draft.copy(server = "") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "清空", tint = AppColors.TextTertiary)
                            }
                        }
                    },
                    colors = textFieldColors(),
                )

                // Plex username
                OutlinedTextField(
                    value = draft.username,
                    onValueChange = { draft = draft.copy(username = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Plex 用户名（无 Token 时使用）") },
                    singleLine = true,
                    trailingIcon = {
                        if (draft.username.isNotEmpty()) {
                            IconButton(onClick = { draft = draft.copy(username = "") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "清空", tint = AppColors.TextTertiary)
                            }
                        }
                    },
                    colors = textFieldColors(),
                )

                // Plex password
                OutlinedTextField(
                    value = draft.password,
                    onValueChange = { draft = draft.copy(password = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Plex 密码") },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (draft.password.isNotEmpty()) {
                                IconButton(onClick = { draft = draft.copy(password = "") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "清空", tint = AppColors.TextTertiary)
                                }
                            }
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = if (isPasswordVisible) "隐藏内容" else "显示明文",
                                    tint = AppColors.TextSecondary,
                                )
                            }
                        }
                    },
                    colors = textFieldColors(),
                )
            }

            Spacer(Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    enabled = draft.type != MusicServerType.Music032 || draft.mediaToken.isNotBlank(),
                    onClick = {
                        onSave(draft, false)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent,
                    ),
                ) {
                    Text(Strings.save)
                }

                OutlinedButton(
                    enabled = draft.type != MusicServerType.Music032 || draft.mediaToken.isNotBlank(),
                    onClick = {
                        onSave(draft, true)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, AppColors.BorderStrong),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.TextPrimary),
                ) {
                    Text(Strings.saveAndRefresh)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalArtworkPalette.current.accent,
    unfocusedBorderColor = AppColors.BorderStrong,
    focusedLabelColor = LocalArtworkPalette.current.accent,
    unfocusedLabelColor = AppColors.TextSecondary,
    cursorColor = LocalArtworkPalette.current.accent,
    focusedTextColor = AppColors.TextPrimary,
    unfocusedTextColor = AppColors.TextPrimary,
)
