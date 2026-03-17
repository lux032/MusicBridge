package com.lux032.plextosonosplayer.plex

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class PlexAuthConfig(
    val username: String?,
    val password: String?,
    val preferredServer: String?,
    val baseUrl: String?,
    val token: String?,
    val clientIdentifier: String = UUID.randomUUID().toString(),
)

data class PlexServer(val name: String, val uri: String, val accessToken: String?)

data class PlexSection(val key: String, val title: String, val type: String)

data class PlexAlbumResult(
    val serverName: String,
    val sections: List<PlexSection>,
    val albums: List<String>,
)

class PlexClient(private val config: PlexAuthConfig) {
    private val appName = "PlexToSonosPlayer"
    private val appVersion = "0.1.0"
    private val product = "Android App"
    private val platform = "Android ${Build.VERSION.RELEASE ?: "Unknown"}"

    suspend fun fetchAlbumNames(): PlexAlbumResult = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val sections = listMusicSections(server, token)
        if (sections.isEmpty()) {
            error("服务器 '${server.name}' 上没有找到音乐库。")
        }

        val albums = sections
            .flatMap { section -> listAlbums(server, section, token) }
            .distinct()
            .sorted()

        PlexAlbumResult(
            serverName = server.name,
            sections = sections,
            albums = albums,
        )
    }

    private fun resolveToken(): String {
        config.token?.takeIf { it.isNotBlank() }?.let { return it }
        val username = config.username?.takeIf { it.isNotBlank() }
            ?: error("缺少 Plex 用户名。")
        val password = config.password?.takeIf { it.isNotBlank() }
            ?: error("缺少 Plex 密码。")

        val authValue = Base64.encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        val response = request(
            method = "POST",
            url = "https://plex.tv/users/sign_in.xml",
            headers = mapOf("Authorization" to "Basic $authValue"),
        )
        val root = parseXml(response.body)
        return root.childText("authenticationToken")
            ?: error("Plex 登录成功，但没有返回 token。")
    }

    private fun resolveServer(token: String): PlexServer {
        config.baseUrl?.takeIf { it.isNotBlank() }?.let {
            return PlexServer(
                name = config.preferredServer?.ifBlank { null } ?: "DirectServer",
                uri = it.trimEnd('/'),
                accessToken = token,
            )
        }

        val response = request(
            method = "GET",
            url = "https://plex.tv/api/resources?includeHttps=1",
            headers = mapOf("X-Plex-Token" to token),
        )
        val root = parseXml(response.body)
        val devices = root.getElementsByTagName("Device")
        val candidates = buildList {
            for (index in 0 until devices.length) {
                val device = devices.item(index) as? Element ?: continue
                if (!device.getAttribute("provides").contains("server")) {
                    continue
                }
                val accessToken = device.getAttribute("accessToken").ifBlank { token }
                val connections = device.getElementsByTagName("Connection")
                for (connectionIndex in 0 until connections.length) {
                    val connection = connections.item(connectionIndex) as? Element ?: continue
                    val uri = connection.getAttribute("uri")
                    if (uri.isBlank()) {
                        continue
                    }
                    add(PlexServer(device.getAttribute("name"), uri.trimEnd('/'), accessToken))
                }
            }
        }

        if (candidates.isEmpty()) {
            error("当前账号下没有可用的 Plex 服务器。")
        }

        config.preferredServer?.takeIf { it.isNotBlank() }?.let { preferred ->
            candidates.firstOrNull { it.name.equals(preferred, ignoreCase = true) }?.let { return it }
            error(
                "未找到服务器 '$preferred'。可用服务器: ${
                    candidates.map { it.name }.distinct().sorted().joinToString()
                }"
            )
        }

        return candidates.first()
    }

    private fun listMusicSections(server: PlexServer, token: String): List<PlexSection> {
        val response = request(
            method = "GET",
            url = "${server.uri}/library/sections",
            headers = mapOf("X-Plex-Token" to server.accessToken.orEmpty().ifBlank { token }),
        )
        val root = parseXml(response.body)
        val directories = root.getElementsByTagName("Directory")
        return buildList {
            for (index in 0 until directories.length) {
                val directory = directories.item(index) as? Element ?: continue
                if (directory.getAttribute("type") != "artist") {
                    continue
                }
                add(
                    PlexSection(
                        key = directory.getAttribute("key"),
                        title = directory.getAttribute("title"),
                        type = directory.getAttribute("type"),
                    )
                )
            }
        }
    }

    private fun listAlbums(server: PlexServer, section: PlexSection, token: String): List<String> {
        val response = request(
            method = "GET",
            url = "${server.uri}/library/sections/${section.key}/all?type=9",
            headers = mapOf("X-Plex-Token" to server.accessToken.orEmpty().ifBlank { token }),
        )
        val root = parseXml(response.body)
        val directories = root.getElementsByTagName("Directory")
        return buildList {
            for (index in 0 until directories.length) {
                val directory = directories.item(index) as? Element ?: continue
                directory.getAttribute("title").takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun request(method: String, url: String, headers: Map<String, String>): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
            setRequestProperty("Accept", "application/xml")
            defaultHeaders().plus(headers).forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(
                status = status,
                body = stream?.readFully().orEmpty(),
            ).also {
                if (status !in 200..299) {
                    error("HTTP $status from $url: ${it.body.take(400)}")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun defaultHeaders(): Map<String, String> = mapOf(
        "X-Plex-Client-Identifier" to config.clientIdentifier,
        "X-Plex-Product" to product,
        "X-Plex-Version" to appVersion,
        "X-Plex-Platform" to platform,
        "X-Plex-Device-Name" to appName,
        "X-Plex-Provides" to "controller",
    )
}

private data class HttpResponse(
    val status: Int,
    val body: String,
)

private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

private fun InputStream.readFully(): String =
    bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

private fun parseXml(body: String): Element {
    val normalized = body.trimStart()
    if (!normalized.startsWith("<")) {
        error("响应不是 XML。内容前缀: ${body.take(200)}")
    }

    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false

    return runCatching {
        factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)))
            .documentElement
    }.getOrElse { throwable ->
        error("XML 解析失败: ${throwable.message}. 内容前缀: ${body.take(200)}")
    }
}

private fun DocumentBuilderFactory.setSafeFeature(name: String, value: Boolean) {
    runCatching { setFeature(name, value) }
}

private fun Element.childText(tagName: String): String? {
    val nodes = getElementsByTagName(tagName)
    if (nodes.length == 0) return null
    return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
}
