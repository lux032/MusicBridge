package com.lux032.musicbridge.ui.settings.dialogs

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.lux032.musicbridge.AppColors
import com.lux032.musicbridge.KeyButton
import com.lux032.musicbridge.KeyStyle
import com.lux032.musicbridge.LabelKey
import com.lux032.musicbridge.LocalArtworkPalette
import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.music.Music032ConnectionTestResult
import com.lux032.musicbridge.ui.settings.decodeQrCodeFromBitmap
import com.lux032.musicbridge.ui.settings.parseServerConfigImport
import com.lux032.musicbridge.ui.settings.validateServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Material 3 bottom sheet for adding or editing a music server profile.
 * Supports full field validation, inline errors, live connection testing,
 * clipboard/text config import, and QR code scanning (camera or gallery).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditSheet(
    initialProfile: MusicServerProfile,
    onSave: (MusicServerProfile, Boolean) -> String?,
    onTestConnection: suspend (MusicServerProfile) -> Music032ConnectionTestResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalArtworkPalette.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var draft by remember(initialProfile) { mutableStateOf(initialProfile) }
    val isNew = draft.id.isBlank()

    var isTokenVisible by remember { mutableStateOf(false) }
    var isMediaTokenVisible by remember { mutableStateOf(false) }
    var showValidationErrors by remember(initialProfile) { mutableStateOf(false) }
    var statusMessage by remember(initialProfile) { mutableStateOf<String?>(null) }
    var statusIsError by remember(initialProfile) { mutableStateOf(false) }
    var isTestingConnection by remember(initialProfile) { mutableStateOf(false) }

    var showImportDialog by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    var importDialogError by remember { mutableStateOf<String?>(null) }
    var showQrSourceDialog by remember { mutableStateOf(false) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    val validation = validateServerProfile(draft)

    fun applyImportedPayload(rawText: String): Boolean {
        val parsed = parseServerConfigImport(rawText, draft)
        return parsed.fold(
            onSuccess = { imported ->
                draft = imported
                showValidationErrors = false
                statusMessage = Strings.importConfigSuccess
                statusIsError = false
                true
            },
            onFailure = {
                statusMessage = Strings.importConfigInvalid
                statusIsError = true
                false
            },
        )
    }

    fun decodeQrFromFile(file: File) {
        scope.launch {
            val decoded = withContext(Dispatchers.Default) {
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
                    try {
                        decodeQrCodeFromBitmap(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    runCatching { file.delete() }
                }
            }
            if (decoded.isNullOrBlank()) {
                statusMessage = Strings.qrNoCodeFound
                statusIsError = true
            } else {
                applyImportedPayload(decoded)
            }
        }
    }

    fun decodeQrFromUri(uri: Uri) {
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: return@withContext null
                try {
                    withContext(Dispatchers.Default) { decodeQrCodeFromBitmap(bitmap) }
                } finally {
                    bitmap.recycle()
                }
            }
            if (decoded.isNullOrBlank()) {
                statusMessage = Strings.qrNoCodeFound
                statusIsError = true
            } else {
                applyImportedPayload(decoded)
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) {
            decodeQrFromFile(file)
        } else {
            file?.let { runCatching { it.delete() } }
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            decodeQrFromUri(uri)
        }
    }

    fun launchCameraQrCapture() {
        runCatching {
            val captureDir = File(context.cacheDir, "qr_capture").apply { mkdirs() }
            val imageFile = File(captureDir, "qr_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile,
            )
            pendingCameraFile = imageFile
            takePictureLauncher.launch(uri)
        }.onFailure {
            pendingCameraFile?.let { file -> runCatching { file.delete() } }
            pendingCameraFile = null
            statusMessage = Strings.qrCameraUnavailable
            statusIsError = true
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importDialogError = null
            },
            title = { Text(Strings.importConfigDialogTitle, color = AppColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = Strings.importConfigDialogHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = {
                            importInput = it
                            importDialogError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        isError = importDialogError != null,
                        supportingText = importDialogError?.let { msg -> { Text(msg) } },
                        colors = textFieldColors(),
                    )
                    TextButton(
                        onClick = {
                            val pasted = clipboard.getText()?.text.orEmpty()
                            if (pasted.isNotBlank()) {
                                importInput = pasted
                                importDialogError = null
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(Strings.pasteFromClipboard)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (applyImportedPayload(importInput)) {
                            showImportDialog = false
                            importInput = ""
                            importDialogError = null
                        } else {
                            importDialogError = Strings.importConfigInvalid
                        }
                    },
                ) {
                    Text(Strings.applyImport, color = palette.accent)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        importDialogError = null
                    },
                ) {
                    Text(Strings.cancel, color = AppColors.TextSecondary)
                }
            },
        )
    }

    if (showQrSourceDialog) {
        AlertDialog(
            onDismissRequest = { showQrSourceDialog = false },
            title = { Text(Strings.scanQrCode, color = AppColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelKey(
                        text = Strings.scanQrFromCamera,
                        icon = Icons.Filled.PhotoCamera,
                        onClick = {
                            showQrSourceDialog = false
                            launchCameraQrCapture()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LabelKey(
                        text = Strings.scanQrFromGallery,
                        icon = Icons.Filled.PhotoLibrary,
                        onClick = {
                            showQrSourceDialog = false
                            pickImageLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showQrSourceDialog = false }) {
                    Text(Strings.cancel, color = AppColors.TextSecondary)
                }
            },
        )
    }

    fun attemptSave(refreshAfterSave: Boolean) {
        showValidationErrors = true
        if (!validation.isValid) {
            statusMessage = validation.firstError
            statusIsError = true
            return
        }
        val error = onSave(draft, refreshAfterSave)
        if (error != null) {
            statusMessage = error
            statusIsError = true
        } else {
            onDismiss()
        }
    }

    fun runConnectionTest() {
        showValidationErrors = true
        if (!validation.isValid) {
            statusMessage = validation.firstError
            statusIsError = true
            return
        }
        if (isTestingConnection) return
        scope.launch {
            isTestingConnection = true
            statusMessage = null
            statusIsError = false
            val result = runCatching { onTestConnection(draft) }.getOrElse { error ->
                Music032ConnectionTestResult.Failure(error.message ?: Strings.unknownError)
            }
            isTestingConnection = false
            when (result) {
                is Music032ConnectionTestResult.Success -> {
                    statusMessage = if (result.mediaTokenVerified) {
                        Strings.testConnectionSuccess
                    } else {
                        Strings.testConnectionSuccessUnverifiedMedia
                    }
                    statusIsError = false
                }
                Music032ConnectionTestResult.InvalidApiToken -> {
                    statusMessage = Strings.testConnectionInvalidApiToken
                    statusIsError = true
                }
                Music032ConnectionTestResult.InvalidMediaToken -> {
                    statusMessage = Strings.music032InvalidMediaToken
                    statusIsError = true
                }
                is Music032ConnectionTestResult.Failure -> {
                    statusMessage = Strings.testConnectionFailed(result.detail)
                    statusIsError = true
                }
            }
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isNew) Strings.addMusicServerTitle else Strings.editMusicServerTitle,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
            )

            // Quick import / QR scan actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LabelKey(
                    text = Strings.importConfig,
                    icon = Icons.Filled.ContentPaste,
                    onClick = {
                        importInput = ""
                        importDialogError = null
                        showImportDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                )
                LabelKey(
                    text = Strings.scanQrCode,
                    icon = Icons.Filled.QrCodeScanner,
                    onClick = { showQrSourceDialog = true },
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                )
            }

            // Display name
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(Strings.serverDisplayNameLabel) },
                placeholder = { Text("032 Music Server") },
                singleLine = true,
                trailingIcon = {
                    if (draft.name.isNotEmpty()) {
                        IconButton(onClick = { draft = draft.copy(name = "") }) {
                            Icon(Icons.Filled.Clear, contentDescription = Strings.clearInput, tint = AppColors.TextTertiary)
                        }
                    }
                },
                colors = textFieldColors(),
            )

            // Base URL
            val baseUrlError = if (showValidationErrors) validation.baseUrlError else null
            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = {
                    draft = draft.copy(baseUrl = it)
                    statusMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(Strings.serverAddressLabel) },
                placeholder = { Text("http://192.168.1.10:4533") },
                supportingText = { Text(baseUrlError ?: Strings.serverAddressHint) },
                isError = baseUrlError != null,
                singleLine = true,
                trailingIcon = {
                    if (draft.baseUrl.isNotEmpty()) {
                        IconButton(onClick = { draft = draft.copy(baseUrl = "") }) {
                            Icon(Icons.Filled.Clear, contentDescription = Strings.clearInput, tint = AppColors.TextTertiary)
                        }
                    }
                },
                colors = textFieldColors(),
            )

            // API Token
            val tokenError = if (showValidationErrors) validation.tokenError else null
            OutlinedTextField(
                value = draft.token,
                onValueChange = {
                    draft = draft.copy(token = it)
                    statusMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(Strings.serverApiTokenLabel) },
                supportingText = tokenError?.let { msg -> { Text(msg) } },
                isError = tokenError != null,
                singleLine = true,
                visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (draft.token.isNotEmpty()) {
                            IconButton(onClick = { draft = draft.copy(token = "") }) {
                                Icon(Icons.Filled.Clear, contentDescription = Strings.clearInput, tint = AppColors.TextTertiary)
                            }
                        }
                        IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                            Icon(
                                imageVector = if (isTokenVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (isTokenVisible) Strings.hideSecret else Strings.showSecret,
                                tint = AppColors.TextSecondary,
                            )
                        }
                    }
                },
                colors = textFieldColors(),
            )

            // Media Token
            val mediaTokenError = if (showValidationErrors) validation.mediaTokenError else null
            OutlinedTextField(
                value = draft.mediaToken,
                onValueChange = {
                    draft = draft.copy(mediaToken = it)
                    statusMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(Strings.serverMediaTokenLabel) },
                supportingText = { Text(mediaTokenError ?: Strings.serverMediaTokenHint) },
                isError = mediaTokenError != null,
                singleLine = true,
                visualTransformation = if (isMediaTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (draft.mediaToken.isNotEmpty()) {
                            IconButton(onClick = { draft = draft.copy(mediaToken = "") }) {
                                Icon(Icons.Filled.Clear, contentDescription = Strings.clearInput, tint = AppColors.TextTertiary)
                            }
                        }
                        IconButton(onClick = { isMediaTokenVisible = !isMediaTokenVisible }) {
                            Icon(
                                imageVector = if (isMediaTokenVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (isMediaTokenVisible) Strings.hideSecret else Strings.showSecret,
                                tint = AppColors.TextSecondary,
                            )
                        }
                    }
                },
                colors = textFieldColors(),
            )

            // Inline error or connection test feedback
            statusMessage?.let { message ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (statusIsError) {
                        AppColors.ErrorText.copy(alpha = 0.14f)
                    } else {
                        palette.accent.copy(alpha = 0.14f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (statusIsError) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = if (statusIsError) AppColors.ErrorText else palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (statusIsError) AppColors.ErrorText else AppColors.TextPrimary,
                        )
                    }
                }
            }

            // Test connection button
            KeyButton(
                enabled = !isTestingConnection,
                onClick = ::runConnectionTest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isTestingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = palette.accent,
                        )
                        Text(Strings.testingConnection)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.NetworkCheck,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(Strings.testConnection)
                    }
                }
            }

            // Save action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KeyButton(
                    enabled = !isTestingConnection,
                    onClick = { attemptSave(refreshAfterSave = false) },
                    modifier = Modifier.weight(1f),
                    style = KeyStyle.Hi,
                ) {
                    Text(Strings.save)
                }

                KeyButton(
                    enabled = !isTestingConnection,
                    onClick = { attemptSave(refreshAfterSave = true) },
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
