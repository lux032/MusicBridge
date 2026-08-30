package com.lux032.musicbridge

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiPlaylistDialog(
    generating: Boolean,
    onCancel: () -> Unit,
    onGenerate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    val presets = listOf(
        Strings.aiPresetCommute,
        Strings.aiPresetNoon,
        Strings.aiPresetLateNight,
        Strings.aiPresetAcg,
    )
    AlertDialog(
        onDismissRequest = { if (!generating) onDismiss() },
        title = { Text(Strings.aiPlaylist) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { suggestion -> SuggestionChip(onClick = { prompt = suggestion }, label = { Text(suggestion) }) }
                }
                OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text(Strings.aiPromptHint) }, minLines = 3)
                if (generating) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(Strings.aiGenerating) }
            }
        },
        confirmButton = {
            if (generating) TextButton(onClick = onCancel) { Text(Strings.cancel) }
            else Button(onClick = { onGenerate(prompt) }, enabled = prompt.isNotBlank()) { Text(Strings.aiGenerate) }
        },
        dismissButton = { if (!generating) TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}
