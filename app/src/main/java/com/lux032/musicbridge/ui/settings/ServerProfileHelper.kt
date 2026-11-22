package com.lux032.musicbridge.ui.settings

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.MusicServerType
import com.lux032.musicbridge.Strings
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ServerProfileValidationResult(
    val baseUrlError: String? = null,
    val tokenError: String? = null,
    val mediaTokenError: String? = null,
) {
    val isValid: Boolean
        get() = baseUrlError == null && tokenError == null && mediaTokenError == null

    val firstError: String?
        get() = baseUrlError ?: tokenError ?: mediaTokenError
}

internal fun validateServerProfile(profile: MusicServerProfile): ServerProfileValidationResult {
    val baseUrl = profile.baseUrl.trim()
    val baseUrlError = validateServerBaseUrl(baseUrl)
    val tokenError = if (profile.token.trim().isEmpty()) Strings.serverValidationTokenRequired else null
    val mediaTokenError = if (profile.type == MusicServerType.Music032 && profile.mediaToken.trim().isEmpty()) {
        Strings.serverValidationMediaTokenRequired
    } else {
        null
    }
    return ServerProfileValidationResult(
        baseUrlError = baseUrlError,
        tokenError = tokenError,
        mediaTokenError = mediaTokenError,
    )
}

internal fun validateServerBaseUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return Strings.serverValidationUrlRequired
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
        return Strings.serverValidationUrlScheme
    }
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return Strings.serverValidationUrlInvalid
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return Strings.serverValidationUrlScheme
    val host = uri.host?.trim()
    if (host.isNullOrEmpty()) return Strings.serverValidationUrlInvalid
    if (uri.port < -1 || uri.port > 65535) return Strings.serverValidationUrlInvalid
    return null
}

/**
 * Parses a server configuration snippet from clipboard text or QR code payload.
 *
 * Supported formats:
 * 1. JSON object: `{"name":"...","baseUrl":"https://...","token":"...","mediaToken":"..."}`
 *    (also accepts aliases `url`, `serverUrl`, `server_url`, `apiToken`, `api_token`, `media_token`).
 * 2. URI / deep link: `musicbridge://server?baseUrl=...&token=...&mediaToken=...&name=...`
 *    or `https://host:port?token=...&mediaToken=...`.
 * 3. Key-value lines (e.g. `.env` snippets):
 *    `MUSIC_SERVER_URL=...`, `MUSIC_SERVER_API_TOKEN=...`, `MUSIC_SERVER_MEDIA_TOKEN=...`.
 */
internal fun parseServerConfigImport(
    raw: String,
    existing: MusicServerProfile = MusicServerProfile(type = MusicServerType.Music032),
): Result<MusicServerProfile> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Empty import text"))

    parseJsonImport(trimmed, existing)?.let { return Result.success(it) }
    parseUriImport(trimmed, existing)?.let { return Result.success(it) }
    parseKeyValueImport(trimmed, existing)?.let { return Result.success(it) }
    return Result.failure(IllegalArgumentException("Unsupported config format"))
}

private fun parseJsonImport(raw: String, existing: MusicServerProfile): MusicServerProfile? {
    if (!raw.startsWith("{")) return null
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val name = firstNonBlank(json, "name", "displayName", "display_name", "title")
    val baseUrl = firstNonBlank(json, "baseUrl", "base_url", "url", "serverUrl", "server_url", "MUSIC_SERVER_URL")
    val token = firstNonBlank(json, "token", "apiToken", "api_token", "MUSIC_SERVER_API_TOKEN")
    val mediaToken = firstNonBlank(json, "mediaToken", "media_token", "MUSIC_SERVER_MEDIA_TOKEN")
    if (baseUrl == null && token == null && mediaToken == null) return null
    return existing.copy(
        name = name ?: existing.name,
        baseUrl = baseUrl?.trimEnd('/') ?: existing.baseUrl,
        token = token ?: existing.token,
        mediaToken = mediaToken ?: existing.mediaToken,
        type = MusicServerType.Music032,
    )
}

private fun firstNonBlank(json: JSONObject, vararg keys: String): String? {
    for (key in keys) {
        if (!json.has(key) || json.isNull(key)) continue
        val value = json.optString(key).trim()
        if (value.isNotEmpty()) return value
    }
    return null
}

private fun parseUriImport(raw: String, existing: MusicServerProfile): MusicServerProfile? {
    if (raw.contains('\n')) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val params = parseQueryParams(uri.rawQuery)

    if (scheme == "musicbridge" || scheme == "music032") {
        val baseUrl = params.firstNonBlank("baseUrl", "base_url", "url", "serverUrl", "server")
        val token = params.firstNonBlank("token", "apiToken", "api_token")
        val mediaToken = params.firstNonBlank("mediaToken", "media_token")
        val name = params.firstNonBlank("name", "displayName", "title")
        if (baseUrl == null && token == null && mediaToken == null) return null
        return existing.copy(
            name = name ?: existing.name,
            baseUrl = baseUrl?.trimEnd('/') ?: existing.baseUrl,
            token = token ?: existing.token,
            mediaToken = mediaToken ?: existing.mediaToken,
            type = MusicServerType.Music032,
        )
    }

    if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
        val explicitBase = params.firstNonBlank("baseUrl", "base_url", "url", "serverUrl")
        val derivedBase = buildString {
            append(scheme)
            append("://")
            append(uri.host)
            if (uri.port > 0) {
                append(':')
                append(uri.port)
            }
            val cleanPath = uri.rawPath.orEmpty().trimEnd('/')
            if (cleanPath.isNotEmpty()) append(cleanPath)
        }
        val token = params.firstNonBlank("token", "apiToken", "api_token")
        val mediaToken = params.firstNonBlank("mediaToken", "media_token")
        val name = params.firstNonBlank("name", "displayName", "title")
        return existing.copy(
            name = name ?: existing.name,
            baseUrl = (explicitBase ?: derivedBase).trimEnd('/'),
            token = token ?: existing.token,
            mediaToken = mediaToken ?: existing.mediaToken,
            type = MusicServerType.Music032,
        )
    }
    return null
}

private fun parseKeyValueImport(raw: String, existing: MusicServerProfile): MusicServerProfile? {
    var name: String? = null
    var baseUrl: String? = null
    var token: String? = null
    var mediaToken: String? = null
    var matched = 0

    raw.lineSequence().forEach { line ->
        val cleaned = line.trim().removePrefix("export ").trim()
        if (cleaned.isEmpty() || cleaned.startsWith("#")) return@forEach
        val sepIndex = cleaned.indexOfFirst { it == '=' || it == ':' }
        if (sepIndex <= 0) return@forEach
        val key = cleaned.substring(0, sepIndex).trim().lowercase()
        val value = cleaned.substring(sepIndex + 1).trim().trim('"', '\'')
        if (value.isEmpty()) return@forEach
        when (key) {
            "name", "displayname", "display_name", "server_name" -> {
                name = value; matched++
            }
            "baseurl", "base_url", "url", "serverurl", "server_url", "music_server_url" -> {
                baseUrl = value; matched++
            }
            "token", "apitoken", "api_token", "music_server_api_token", "music_server_token" -> {
                token = value; matched++
            }
            "mediatoken", "media_token", "music_server_media_token" -> {
                mediaToken = value; matched++
            }
        }
    }

    if (matched == 0 || (baseUrl == null && token == null && mediaToken == null)) return null
    return existing.copy(
        name = name ?: existing.name,
        baseUrl = baseUrl?.trimEnd('/') ?: existing.baseUrl,
        token = token ?: existing.token,
        mediaToken = mediaToken ?: existing.mediaToken,
        type = MusicServerType.Music032,
    )
}

private fun parseQueryParams(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return buildMap {
        rawQuery.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val key = decodeQueryComponent(pair.substring(0, idx))
            val value = decodeQueryComponent(pair.substring(idx + 1))
            if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
        }
    }
}

private fun decodeQueryComponent(value: String): String =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value).trim()

private fun Map<String, String>.firstNonBlank(vararg keys: String): String? {
    for (key in keys) {
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    return null
}

/**
 * Decodes a QR code from a captured camera photo or gallery image using ZXing.
 * Downscales oversized bitmaps first so full-res camera photos do not cause OOM.
 */
internal fun decodeQrCodeFromBitmap(bitmap: Bitmap): String? {
    val maxDim = maxOf(bitmap.width, bitmap.height)
    val scaled = if (maxDim > MAX_QR_DECODE_DIMENSION) {
        val ratio = MAX_QR_DECODE_DIMENSION.toFloat() / maxDim.toFloat()
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        bitmap
    }
    return try {
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        val reader = MultiFormatReader().apply { setHints(hints) }
        runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
            ?: runCatching {
                reader.reset()
                reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
            }.getOrNull()
    } finally {
        if (scaled !== bitmap) scaled.recycle()
    }
}

private const val MAX_QR_DECODE_DIMENSION = 1600
