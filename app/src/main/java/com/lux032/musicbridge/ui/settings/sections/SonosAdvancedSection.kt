package com.lux032.musicbridge.ui.settings.sections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lux032.musicbridge.OpusTranscodeMode
import com.lux032.musicbridge.SonosHiResTranscodeMode
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.ui.settings.components.SettingsDivider
import com.lux032.musicbridge.ui.settings.components.SettingsGroup
import com.lux032.musicbridge.ui.settings.components.SettingsItem
import com.lux032.musicbridge.ui.settings.dialogs.SettingOptionSheet

@Composable
fun SonosAdvancedSection(
    opusTranscodeMode: OpusTranscodeMode,
    sonosHiResTranscodeMode: SonosHiResTranscodeMode,
    onOpusTranscodeModeChange: (OpusTranscodeMode) -> Unit,
    onSonosHiResTranscodeModeChange: (SonosHiResTranscodeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isOpusSheetVisible by remember { mutableStateOf(false) }
    var isHiResSheetVisible by remember { mutableStateOf(false) }

    if (isOpusSheetVisible) {
        SettingOptionSheet(
            title = Strings.opusTranscodeModeTitle,
            options = OpusTranscodeMode.entries,
            selected = opusTranscodeMode,
            label = { mode ->
                when (mode) {
                    OpusTranscodeMode.DIRECT_MP3 -> Strings.opusModeDirectMp3
                    OpusTranscodeMode.PROXY_FLAC -> Strings.opusModeProxyFlac
                }
            },
            description = { mode ->
                when (mode) {
                    OpusTranscodeMode.DIRECT_MP3 -> Strings.opusModeDirectMp3Desc
                    OpusTranscodeMode.PROXY_FLAC -> Strings.opusModeProxyFlacDesc
                }
            },
            badge = { mode ->
                when (mode) {
                    OpusTranscodeMode.DIRECT_MP3 -> Strings.opusModeDirectMp3Badge
                    OpusTranscodeMode.PROXY_FLAC -> null
                }
            },
            onSelect = onOpusTranscodeModeChange,
            onDismiss = { isOpusSheetVisible = false },
        )
    }

    if (isHiResSheetVisible) {
        SettingOptionSheet(
            title = Strings.sonosHiResTranscodeTitle,
            options = SonosHiResTranscodeMode.entries,
            selected = sonosHiResTranscodeMode,
            label = { mode ->
                when (mode) {
                    SonosHiResTranscodeMode.TRANSCODE_MP3 -> Strings.sonosHiResModeTranscodeMp3
                    SonosHiResTranscodeMode.TRANSCODE_FLAC_48K -> Strings.sonosHiResModeTranscodeFlac48k
                    SonosHiResTranscodeMode.DIRECT_ORIGINAL -> Strings.sonosHiResModeDirectOriginal
                }
            },
            description = { mode ->
                when (mode) {
                    SonosHiResTranscodeMode.TRANSCODE_MP3 -> Strings.sonosHiResModeTranscodeMp3Desc
                    SonosHiResTranscodeMode.TRANSCODE_FLAC_48K -> Strings.sonosHiResModeTranscodeFlac48kDesc
                    SonosHiResTranscodeMode.DIRECT_ORIGINAL -> Strings.sonosHiResModeDirectOriginalDesc
                }
            },
            badge = { mode ->
                when (mode) {
                    SonosHiResTranscodeMode.TRANSCODE_MP3 -> "推荐"
                    SonosHiResTranscodeMode.TRANSCODE_FLAC_48K -> null
                    SonosHiResTranscodeMode.DIRECT_ORIGINAL -> null
                }
            },
            onSelect = onSonosHiResTranscodeModeChange,
            onDismiss = { isHiResSheetVisible = false },
        )
    }

    val currentOpusLabel = when (opusTranscodeMode) {
        OpusTranscodeMode.DIRECT_MP3 -> Strings.opusModeDirectMp3
        OpusTranscodeMode.PROXY_FLAC -> Strings.opusModeProxyFlac
    }

    val currentHiResLabel = when (sonosHiResTranscodeMode) {
        SonosHiResTranscodeMode.TRANSCODE_MP3 -> Strings.sonosHiResModeTranscodeMp3
        SonosHiResTranscodeMode.TRANSCODE_FLAC_48K -> Strings.sonosHiResModeTranscodeFlac48k
        SonosHiResTranscodeMode.DIRECT_ORIGINAL -> Strings.sonosHiResModeDirectOriginal
    }

    SettingsGroup(
        title = "Sonos 与硬件转码",
        description = "配置推送到 Sonos 音箱时的音轨兼容与转码策略",
        modifier = modifier,
    ) {
        SettingsItem(
            title = Strings.sonosHiResTranscodeTitle,
            subtitle = Strings.sonosHiResTranscodeDesc,
            value = currentHiResLabel,
            icon = Icons.Filled.HighQuality,
            onClick = { isHiResSheetVisible = true },
        )

        SettingsDivider()

        SettingsItem(
            title = Strings.opusTranscodeModeTitle,
            subtitle = Strings.opusTranscodeModeDesc,
            value = currentOpusLabel,
            icon = Icons.Filled.Cast,
            onClick = { isOpusSheetVisible = true },
        )
    }
}
