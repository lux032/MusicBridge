package com.lux032.musicbridge.ui.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.LocalArtworkPalette
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.ai.AiSettings
import com.lux032.musicbridge.ui.settings.components.SettingsDivider
import com.lux032.musicbridge.ui.settings.components.SettingsGroup
import com.lux032.musicbridge.ui.settings.components.SettingsSwitchItem

@Composable
fun AiSettingsSection(
    settings: AiSettings,
    onSave: (AiSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalArtworkPalette.current
    var draft by remember(settings) { mutableStateOf(settings) }
    var isApiKeyVisible by remember { mutableStateOf(false) }

    SettingsGroup(
        title = "AI 灵感助手",
        description = "使用大语言模型和声波分析探索音乐库并智能创建歌单",
        modifier = modifier,
    ) {
        SettingsSwitchItem(
            title = Strings.aiEnabled,
            subtitle = if (draft.enabled) "已启用 AI 智能生成功能" else "未启用",
            icon = Icons.Filled.AutoAwesome,
            checked = draft.enabled,
            onCheckedChange = { isChecked ->
                draft = draft.copy(enabled = isChecked)
                onSave(draft)
            },
        )

        AnimatedVisibility(
            visible = draft.enabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsDivider(Modifier.padding(horizontal = 0.dp))

                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { draft = draft.copy(baseUrl = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.aiBaseUrl) },
                    placeholder = { Text("https://api.deepseek.com/v1") },
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

                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = { draft = draft.copy(apiKey = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.aiApiKey) },
                    singleLine = true,
                    visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (draft.apiKey.isNotEmpty()) {
                                IconButton(onClick = { draft = draft.copy(apiKey = "") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "清空", tint = AppColors.TextTertiary)
                                }
                            }
                            IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                Icon(
                                    imageVector = if (isApiKeyVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = if (isApiKeyVisible) "隐藏内容" else "显示明文",
                                    tint = AppColors.TextSecondary,
                                )
                            }
                        }
                    },
                    colors = textFieldColors(),
                )

                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft = draft.copy(model = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.aiModel) },
                    placeholder = { Text("deepseek-chat") },
                    singleLine = true,
                    trailingIcon = {
                        if (draft.model.isNotEmpty()) {
                            IconButton(onClick = { draft = draft.copy(model = "") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "清空", tint = AppColors.TextTertiary)
                            }
                        }
                    },
                    colors = textFieldColors(),
                )

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = { onSave(draft) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent,
                    ),
                ) {
                    Text(Strings.saveAiSettings)
                }
            }
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
