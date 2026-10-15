package com.lux032.musicbridge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexTrackStream
import kotlinx.coroutines.launch

// ── Entry points shared by every track / album menu ─────────────────────────────────────────

/**
 * Root-provided bridge to playlist editing. Null (the default) means the active server cannot
 * edit playlists, so menus simply do not offer "添加到歌单".
 */
@Stable
internal class PlaylistEditor(
    val canAdd: (sourceId: String) -> Boolean,
    val requestAdd: (PlaylistAddition) -> Unit,
)

internal val LocalPlaylistEditor = staticCompositionLocalOf<PlaylistEditor?> { null }

/** "添加到歌单" action for one track, or null when it cannot go into a playlist here. */
@Composable
internal fun addTrackToPlaylistAction(track: PlexTrackStream): (() -> Unit)? {
    val editor = LocalPlaylistEditor.current ?: return null
    if (track.ratingKey.isBlank() || !editor.canAdd(track.sourceId)) return null
    return { editor.requestAdd(PlaylistAddition.Tracks(listOf(track))) }
}

/** "添加到歌单" action for a whole album, or null when unsupported. */
@Composable
internal fun addAlbumToPlaylistAction(album: PlexAlbum, trackCount: Int? = album.leafCount): (() -> Unit)? {
    val editor = LocalPlaylistEditor.current ?: return null
    if (album.ratingKey.toLongOrNull() == null || !editor.canAdd(album.sourceId)) return null
    return { editor.requestAdd(PlaylistAddition.Album(album, trackCount)) }
}

// ── Add-to-playlist picker ──────────────────────────────────────────────────────────────────

/**
 * Bottom sheet: what is being added, a "新建歌单" row, then the server's playlists (most
 * recently changed first). Picking a playlist closes the sheet at once; the snackbar reports
 * what the server actually did (added / already there / vanished).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    addition: PlaylistAddition,
    playlists: List<PlexPlaylist>,
    isMutating: Boolean,
    onPick: (PlexPlaylist) -> Unit,
    onCreate: suspend (name: String, description: String) -> Result<*>,
    onDismiss: () -> Unit,
) {
    val palette = LocalArtworkPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // An empty library goes straight to the form: a list with only "新建" is a dead step.
    var isCreating by rememberSaveable { mutableStateOf(playlists.isEmpty()) }
    fun close(after: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            after()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.ime },
        containerColor = palette.backgroundBottom,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text(
                text = if (isCreating) Strings.newPlaylist else Strings.addToPlaylist,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp),
            )
            AdditionSummary(addition, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            Hairline(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            AnimatedContent(
                targetState = isCreating,
                transitionSpec = { fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(90)) },
                label = "addToPlaylistMode",
            ) { creating ->
                if (creating) {
                    PlaylistForm(
                        initialName = "",
                        initialDescription = "",
                        confirmLabel = Strings.createAndAdd,
                        note = Strings.noPlaylistsInPicker.takeIf { playlists.isEmpty() },
                        onSubmit = onCreate,
                        onDone = { close() },
                        onCancel = { if (playlists.isEmpty()) close() else isCreating = false },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp),
                    ) {
                        item(key = "new", contentType = "new") {
                            PickerRow(
                                title = Strings.newPlaylist,
                                subtitle = null,
                                enabled = !isMutating,
                                onClick = { isCreating = true },
                                leading = {
                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(KeyColors.Tonal),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(26.dp))
                                    }
                                },
                            )
                        }
                        items(playlists, key = { it.ratingKey }, contentType = { "playlist" }) { playlist ->
                            PickerRow(
                                title = playlist.title,
                                subtitle = Strings.tracks(playlist.leafCount),
                                enabled = !isMutating,
                                onClick = { close { onPick(playlist) } },
                                leading = {
                                    AsyncAlbumArtwork(
                                        imageUrl = playlist.thumbUrl,
                                        title = playlist.title,
                                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                                        thumbnailSize = 52.dp,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Artwork + name of what is about to be added, so the sheet never acts on a guess. */
@Composable
private fun AdditionSummary(addition: PlaylistAddition, modifier: Modifier = Modifier) {
    val (thumb, title, subtitle) = when (addition) {
        is PlaylistAddition.Tracks -> {
            val first = addition.tracks.first()
            if (addition.tracks.size == 1) {
                Triple(first.thumbUrl, first.title, listOfNotNull(first.artistName, first.albumTitle).joinToString(" · "))
            } else {
                Triple(first.thumbUrl, Strings.selectedTracks(addition.tracks.size), first.albumTitle.orEmpty())
            }
        }
        is PlaylistAddition.Album -> Triple(
            addition.album.thumbUrl,
            addition.album.title,
            listOfNotNull(
                Strings.wholeAlbum,
                addition.trackCount?.takeIf { it > 0 }?.let { Strings.tracks(it) },
            ).joinToString(" · "),
        )
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncAlbumArtwork(
            imageUrl = thumb,
            title = title,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            thumbnailSize = 44.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = AppColors.TextPrimary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = AppColors.TextTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = AppColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (subtitle == null) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, color = AppColors.TextTertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ── Create / edit form ──────────────────────────────────────────────────────────────────────

/** Standalone sheet around [PlaylistForm]: create from the playlists page, or edit details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistEditSheet(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialDescription: String,
    onSubmit: suspend (name: String, description: String) -> Result<*>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.ime },
        containerColor = LocalArtworkPalette.current.backgroundBottom,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            PlaylistForm(
                initialName = initialName,
                initialDescription = initialDescription,
                confirmLabel = confirmLabel,
                onSubmit = onSubmit,
                onDone = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                onCancel = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

/**
 * Name + optional description. The name is focused on open; submitting keeps the form (and
 * the typed text) on failure and shows the server's reason inline.
 */
@Composable
private fun PlaylistForm(
    initialName: String,
    initialDescription: String,
    confirmLabel: String,
    onSubmit: suspend (name: String, description: String) -> Result<*>,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val palette = LocalArtworkPalette.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val nameFocus = remember { FocusRequester() }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canSubmit = name.isNotBlank() && !isSaving

    fun submit() {
        if (!canSubmit) return
        isSaving = true
        error = null
        scope.launch {
            val result = onSubmit(name.trim(), description.trim())
            isSaving = false
            result.onSuccess { onDone() }.onFailure { error = it.message ?: Strings.playlistActionFailed(null) }
        }
    }

    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = palette.accent,
        unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
        focusedLabelColor = palette.accent,
        unfocusedLabelColor = AppColors.TextSecondary,
        cursorColor = palette.accent,
        focusedTextColor = AppColors.TextPrimary,
        unfocusedTextColor = AppColors.TextPrimary,
        focusedPlaceholderColor = AppColors.TextTertiary,
        unfocusedPlaceholderColor = AppColors.TextTertiary,
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (note != null) {
            Text(note, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(MAX_PLAYLIST_NAME_LENGTH)
                error = null
            },
            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
            label = { Text(Strings.playlistName) },
            placeholder = { Text(Strings.playlistNamePlaceholder) },
            singleLine = true,
            enabled = !isSaving,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors,
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(MAX_PLAYLIST_DESCRIPTION_LENGTH) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(Strings.playlistDescriptionLabel) },
            minLines = 2,
            maxLines = 4,
            enabled = !isSaving,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors,
        )
        error?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AppColors.ErrorText, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                Text(it, color = AppColors.ErrorText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KeyButton(onClick = onCancel, enabled = !isSaving) { Text(Strings.cancel) }
            KeyButton(
                onClick = ::submit,
                enabled = canSubmit,
                style = KeyStyle.Hi,
                modifier = Modifier.weight(1f),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                }
                Text(confirmLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val MAX_PLAYLIST_NAME_LENGTH = 100
private const val MAX_PLAYLIST_DESCRIPTION_LENGTH = 500

// ── Delete confirmation ─────────────────────────────────────────────────────────────────────

@Composable
internal fun DeletePlaylistDialog(
    playlist: PlexPlaylist,
    onConfirm: suspend () -> Result<*>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isDeleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = AppColors.TextSecondary) },
        title = { Text(Strings.deletePlaylist) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(Strings.deletePlaylistConfirm(playlist.title))
                error?.let { Text(it, color = AppColors.ErrorText) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isDeleting,
                onClick = {
                    isDeleting = true
                    error = null
                    scope.launch {
                        val result = onConfirm()
                        isDeleting = false
                        result.onSuccess { onDismiss() }.onFailure { error = it.message }
                    }
                },
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.ErrorText)
                    Spacer(Modifier.size(8.dp))
                }
                Text(Strings.confirmDelete, color = AppColors.ErrorText, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text(Strings.cancel, color = AppColors.TextSecondary)
            }
        },
        containerColor = AppColors.SurfaceAlt,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
    )
}

// ── Feedback ────────────────────────────────────────────────────────────────────────────────

private data class PlaylistSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    val isError: Boolean,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration =
        if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
}

/**
 * Global snackbar for playlist writes. Light container on the dark field (inverse surface),
 * so it reads above any artwork tint; undo lives on the snackbar itself.
 */
@Composable
internal fun PlaylistNoticeHost(
    notice: PlaylistNotice?,
    onConsumed: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        val result = hostState.showSnackbar(PlaylistSnackbarVisuals(current.message, current.actionLabel, current.isError))
        if (result == SnackbarResult.ActionPerformed) current.onAction?.invoke()
        onConsumed(current.id)
    }
    SnackbarHost(hostState, modifier = modifier) { data ->
        val visuals = data.visuals as? PlaylistSnackbarVisuals
        val ink = keyHiInk()
        Snackbar(
            containerColor = KeyColors.Hi,
            contentColor = ink,
            shape = KeyShape,
            action = data.visuals.actionLabel?.let { label ->
                {
                    TextButton(onClick = { data.performAction() }) {
                        Text(label, color = ink, fontWeight = FontWeight.Bold)
                    }
                }
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (visuals?.isError == true) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = ErrorOnLight, modifier = Modifier.size(20.dp))
                }
                Text(data.visuals.message, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** M3 light-scheme error; keeps ≥4.5:1 on the near-white snackbar. */
private val ErrorOnLight = Color(0xFFB3261E)
