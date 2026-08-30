package com.lux032.musicbridge

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.ai.AiSettings

@Composable
internal fun AiSettingsCard(settings: AiSettings, onSave: (AiSettings) -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = AppColors.CardSurface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Strings.aiSettings, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Strings.aiEnabled, color = AppColors.TextPrimary)
                Switch(draft.enabled, { draft = draft.copy(enabled = it) })
            }
            OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it) }, Modifier.fillMaxWidth(), label = { Text(Strings.aiBaseUrl) }, singleLine = true)
            OutlinedTextField(draft.apiKey, { draft = draft.copy(apiKey = it) }, Modifier.fillMaxWidth(), label = { Text(Strings.aiApiKey) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(draft.model, { draft = draft.copy(model = it) }, Modifier.fillMaxWidth(), label = { Text(Strings.aiModel) }, singleLine = true)
            Button(onClick = { onSave(draft) }, Modifier.fillMaxWidth()) { Text(Strings.saveAiSettings) }
        }
    }
}
