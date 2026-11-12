package com.lux032.musicbridge.ui.settings.dialogs

import com.lux032.musicbridge.KeyButton
import com.lux032.musicbridge.KeyStyle
import androidx.compose.ui.graphics.Color
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
                color = AppColors.TextPrimary,
            )

            // Display name
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("显示名称") },
                placeholder = {
                    Text("032 Music Server")
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
                    Text("http://192.168.1.10:4533")
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
                label = { Text("API Token") },
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

            // Media Token
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

            Spacer(Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KeyButton(
                    enabled = draft.mediaToken.isNotBlank(),
                    onClick = {
                        onSave(draft, false)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    style = KeyStyle.Hi,
                ) {
                    Text(Strings.save)
                }

                KeyButton(
                    enabled = draft.mediaToken.isNotBlank(),
                    onClick = {
                        onSave(draft, true)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
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
    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
    focusedLabelColor = LocalArtworkPalette.current.accent,
    unfocusedLabelColor = AppColors.TextSecondary,
    cursorColor = LocalArtworkPalette.current.accent,
    focusedTextColor = AppColors.TextPrimary,
    unfocusedTextColor = AppColors.TextPrimary,
)
