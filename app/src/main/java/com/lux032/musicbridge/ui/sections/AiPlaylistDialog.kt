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
        Strings.aiPresetCommuteLabel to Strings.aiPresetCommutePrompt,
        Strings.aiPresetNoonLabel to Strings.aiPresetNoonPrompt,
        Strings.aiPresetLateNightLabel to Strings.aiPresetLateNightPrompt,
        Strings.aiPresetAcgLabel to Strings.aiPresetAcgPrompt,
    )
    AlertDialog(
        onDismissRequest = { if (!generating) onDismiss() },
        title = { Text(Strings.aiPlaylist) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (label, presetPrompt) ->
                        Key(
                            onClick = { prompt = presetPrompt },
                            style = if (prompt == presetPrompt) KeyStyle.Hi else KeyStyle.Tonal,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            modifier = Modifier.heightIn(min = 36.dp),
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(Strings.aiPromptHint) },
                    minLines = 3,
                    shape = KeyShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LocalArtworkPalette.current.accent,
                        unfocusedBorderColor = KeyColors.Tonal,
                        focusedLabelColor = LocalArtworkPalette.current.accent,
                        cursorColor = LocalArtworkPalette.current.accent,
                    ),
                )
                if (generating) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = LocalArtworkPalette.current.accent); Text(Strings.aiGenerating) }
            }
        },
        confirmButton = {
            if (generating) TextButton(onClick = onCancel) { Text(Strings.cancel) }
            else KeyButton(onClick = { onGenerate(prompt) }, enabled = prompt.isNotBlank(), style = KeyStyle.Hi) { Text(Strings.aiGenerate) }
        },
        dismissButton = { if (!generating) TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}
