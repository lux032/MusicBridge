package com.lux032.musicbridge.sonos

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

data class SonosDevice(
    val zoneName: String,
    val modelName: String?,
    val udn: String?,
    val location: String,
    val baseUrl: String,
)

data class SonosRoom(
    val roomName: String,
    val coordinatorUuid: String,
    val coordinatorBaseUrl: String,
    val memberCount: Int,
    val rawCoordinatorName: String,
    val memberBaseUrls: List<String>,
)

class SonosDiscovery(private val context: Context) {
    suspend fun discoverDevices(timeoutMillis: Int = 3_000): List<SonosDevice> = withContext(Dispatchers.IO) {
        withMulticastLock {
            discoverLocations(timeoutMillis)
                .mapNotNull(::fetchDeviceDescription)
                .distinctBy { it.udn ?: it.location }
                .sortedBy { it.zoneName.lowercase() }
        }
    }

    suspend fun discoverRooms(timeoutMillis: Int = 3_000): List<SonosRoom> = withContext(Dispatchers.IO) {
        withMulticastLock {
            val devices = discoverLocations(timeoutMillis)
                .mapNotNull(::fetchDeviceDescription)
                .distinctBy { it.udn ?: it.location }

            val deviceMap = devices.mapNotNull { device ->
                val uuid = normalizeSonosUuid(device.udn).ifBlank { null }
                uuid?.let { it to device }
            }.toMap()

            val topologyRooms = devices.asSequence()
                .mapNotNull { device -> runCatching { fetchRoomsFromTopology(device.baseUrl, deviceMap) }.getOrNull() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .sortedBy { it.roomName.lowercase() }

            if (topologyRooms.isNotEmpty()) {
                topologyRooms
            } else {
                devices.map { device ->
                    val controllableBaseUrl = resolveControllableBaseUrl(listOf(device.baseUrl))
                    SonosRoom(
                        roomName = device.zoneName,
                        coordinatorUuid = normalizeSonosUuid(device.udn).ifBlank { device.location },
                        coordinatorBaseUrl = controllableBaseUrl ?: device.baseUrl,
                        memberCount = 1,
                        rawCoordinatorName = device.zoneName,
                        memberBaseUrls = listOf(device.baseUrl),
                    )
                }.sortedBy { it.roomName.lowercase() }
            }
        }
    }

    private inline fun <T> withMulticastLock(block: () -> T): T {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("plex-to-sonos-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        return try {
            block()
        } finally {
            multicastLock?.runCatching { release() }
        }
    }

    private fun discoverLocations(timeoutMillis: Int): Set<String> {
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        val socket = DatagramSocket().apply {
            soTimeout = timeoutMillis
            broadcast = true
        }

        return socket.use { datagramSocket ->
            val target = InetAddress.getByName("239.255.255.250")
            repeat(3) {
                datagramSocket.send(DatagramPacket(request, request.size, target, 1900))
            }

            val startedAt = System.currentTimeMillis()
            val locations = linkedSetOf<String>()
            while (System.currentTimeMillis() - startedAt < timeoutMillis) {
                val remaining = (timeoutMillis - (System.currentTimeMillis() - startedAt)).toInt().coerceAtLeast(250)
                datagramSocket.soTimeout = remaining
                val buffer = ByteArray(8 * 1024)
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    datagramSocket.receive(response)
                    val payload = String(response.data, 0, response.length, StandardCharsets.UTF_8)
                    parseHeaderValue(payload, "LOCATION")?.let(locations::add)
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
            locations
        }
    }

    private fun fetchDeviceDescription(location: String): SonosDevice? {
        val response = request(location)
        val root = parseXml(response)
        val device = root.getElementsByTagName("device").item(0) as? Element ?: return null
        val zoneName = device.childText("roomName")
            ?: device.childText("friendlyName")
            ?: return null
        return SonosDevice(
            zoneName = zoneName,
            modelName = device.childText("modelName"),
            udn = device.childText("UDN"),
            location = location,
            baseUrl = originOf(location),
        )
    }

    private fun fetchRoomsFromTopology(baseUrl: String, deviceMap: Map<String, SonosDevice>): List<SonosRoom> {
        val response = soapRequest(
            controlUrl = "$baseUrl/ZoneGroupTopology/Control",
            serviceType = "urn:schemas-upnp-org:service:ZoneGroupTopology:1",
            action = "GetZoneGroupState",
            innerXml = "",
        )
        val root = parseXml(response)
        val stateText = root.textOfFirstTag("ZoneGroupState") ?: return emptyList()
        val stateRoot = parseXml(stateText)
        val zoneGroups = stateRoot.getElementsByTagName("ZoneGroup")

        return buildList {
            for (groupIndex in 0 until zoneGroups.length) {
                val group = zoneGroups.item(groupIndex) as? Element ?: continue
                val coordinatorUuid = normalizeSonosUuid(group.getAttribute("Coordinator"))
                if (coordinatorUuid.isBlank()) {
                    continue
                }

                val members = group.getElementsByTagName("ZoneGroupMember").toElementList()
                val visibleMembers = members.filterNot { it.getAttribute("Invisible") == "1" }
                val coordinatorMember = visibleMembers.firstOrNull {
                    normalizeSonosUuid(it.getAttribute("UUID")) == coordinatorUuid
                } ?: members.firstOrNull {
                    normalizeSonosUuid(it.getAttribute("UUID")) == coordinatorUuid
                } ?: continue

                val coordinatorDevice = deviceMap[coordinatorUuid]
                val roomName = coordinatorMember.getAttribute("ZoneName")
                    .ifBlank { coordinatorDevice?.zoneName.orEmpty() }
                    .trim()
                if (roomName.isBlank()) {
                    continue
                }

                val coordinatorBaseUrl = coordinatorDevice?.baseUrl
                    ?: originOf(coordinatorMember.getAttribute("Location"))
                if (coordinatorBaseUrl.isBlank()) {
                    continue
                }

                val memberBaseUrls = visibleMembers
                    .mapNotNull { member ->
                        val memberUuid = normalizeSonosUuid(member.getAttribute("UUID"))
                        val memberBaseUrl = deviceMap[memberUuid]?.baseUrl
                            ?: originOf(member.getAttribute("Location"))
                        memberBaseUrl.takeIf { it.isNotBlank() }
                    }
                    .ifEmpty { listOf(coordinatorBaseUrl) }
                    .distinct()

                val controllableBaseUrl = resolveControllableBaseUrl(
                    listOf(coordinatorBaseUrl) + memberBaseUrls
                ) ?: coordinatorBaseUrl

                add(
                    SonosRoom(
                        roomName = roomName,
                        coordinatorUuid = coordinatorUuid,
                        coordinatorBaseUrl = controllableBaseUrl,
                        memberCount = members.size,
                        rawCoordinatorName = coordinatorMember.getAttribute("ZoneName").ifBlank { roomName },
                        memberBaseUrls = memberBaseUrls,
                    )
                )
            }
        }.distinctBy { it.coordinatorUuid }
    }

    private fun resolveControllableBaseUrl(candidateBaseUrls: List<String>): String? =
        candidateBaseUrls
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .firstOrNull { baseUrl ->
                runCatching {
                    soapRequest(
                        controlUrl = "$baseUrl/MediaRenderer/GroupRenderingControl/Control",
                        serviceType = "urn:schemas-upnp-org:service:GroupRenderingControl:1",
                        action = "GetGroupVolume",
                        innerXml = """<InstanceID>0</InstanceID>""",
                    )
                }.isSuccess
            }

    private fun request(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            doInput = true
            setRequestProperty("Accept", "application/xml,text/xml")
        }

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.readFully().orEmpty()
            if (status !in 200..299) {
                error("HTTP $status from $url: ${body.take(200)}")
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}

class SonosController {
    suspend fun playTrack(room: SonosRoom, trackUrl: String, title: String, albumTitle: String) = withContext(Dispatchers.IO) {
        val controlUrl = "${room.coordinatorBaseUrl}/MediaRenderer/AVTransport/Control"
        val serviceType = "urn:schemas-upnp-org:service:AVTransport:1"
        val didl = buildTrackMetadata(
            trackUrl = trackUrl,
            title = title,
            albumTitle = albumTitle,
        )

        soapRequest(
            controlUrl = controlUrl,
            serviceType = serviceType,
            action = "SetAVTransportURI",
            innerXml = """<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(trackUrl)}</CurrentURI><CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>""",
        )

        kotlinx.coroutines.delay(500)

        soapRequest(
            controlUrl = controlUrl,
            serviceType = serviceType,
            action = "Play",
            innerXml = """<InstanceID>0</InstanceID><Speed>1</Speed>""",
        )
    }

    suspend fun pause(room: SonosRoom) = withContext(Dispatchers.IO) {
        soapRequest(
            controlUrl = "${room.coordinatorBaseUrl}/MediaRenderer/AVTransport/Control",
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "Pause",
            innerXml = """<InstanceID>0</InstanceID>""",
        )
    }

    suspend fun resume(room: SonosRoom) = withContext(Dispatchers.IO) {
        soapRequest(
            controlUrl = "${room.coordinatorBaseUrl}/MediaRenderer/AVTransport/Control",
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "Play",
            innerXml = """<InstanceID>0</InstanceID><Speed>1</Speed>""",
        )
    }

    suspend fun stop(room: SonosRoom) = withContext(Dispatchers.IO) {
        soapRequest(
            controlUrl = "${room.coordinatorBaseUrl}/MediaRenderer/AVTransport/Control",
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "Stop",
            innerXml = """<InstanceID>0</InstanceID>""",
        )
    }

    suspend fun seek(room: SonosRoom, positionSeconds: Int) = withContext(Dispatchers.IO) {
        soapRequest(
            controlUrl = "${room.coordinatorBaseUrl}/MediaRenderer/AVTransport/Control",
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "Seek",
            innerXml = """<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${positionSeconds.toSonosDuration()}</Target>""",
        )
    }

    suspend fun getPlaybackStatus(room: SonosRoom): SonosPlaybackStatus = withContext(Dispatchers.IO) {
        val transportResponse = soapRequest(
            controlUrl = "${room.coordinatorBaseUrl}/MediaRenderer/AVTransport/Control",
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "GetTransportInfo",
            innerXml = """<InstanceID>0</InstanceID>""",
        )
        val positionResponse = soapRequest(
            controlUrl = "${room.coordinatorBaseUrl}/MediaRenderer/AVTransport/Control",
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "GetPositionInfo",
            innerXml = """<InstanceID>0</InstanceID>""",
        )
        val transportRoot = parseXml(transportResponse)
        val positionRoot = parseXml(positionResponse)
        SonosPlaybackStatus(
            transportState = transportRoot.textOfFirstTag("CurrentTransportState").orEmpty(),
            currentTrackUri = positionRoot.textOfFirstTag("TrackURI"),
            relTimeSeconds = positionRoot.textOfFirstTag("RelTime")?.toDurationSeconds(),
            trackDurationSeconds = positionRoot.textOfFirstTag("TrackDuration")?.toDurationSeconds(),
        )
    }

    suspend fun getVolume(room: SonosRoom): Int = withContext(Dispatchers.IO) {
        val candidateBaseUrls = (listOf(room.coordinatorBaseUrl) + room.memberBaseUrls).distinct().filter { it.isNotBlank() }
        val errors = mutableListOf<String>()

        for (baseUrl in candidateBaseUrls) {
            readGroupVolume(baseUrl)?.toIntOrNull()?.coerceIn(0, 100)?.let { return@withContext it }
                ?: errors.add("GroupVolume failed at $baseUrl")
            readRegularVolume(baseUrl)?.toIntOrNull()?.coerceIn(0, 100)?.let { return@withContext it }
                ?: errors.add("RegularVolume failed at $baseUrl")
        }

        error("无法读取音量。尝试的URL: ${candidateBaseUrls.joinToString()}。错误: ${errors.joinToString("; ")}")
    }

    suspend fun setVolume(room: SonosRoom, volume: Int) = withContext(Dispatchers.IO) {
        val safeVolume = volume.coerceIn(0, 100)

        val groupResult = runCatching {
            soapRequest(
                controlUrl = "${room.coordinatorBaseUrl}/MediaRenderer/GroupRenderingControl/Control",
                serviceType = "urn:schemas-upnp-org:service:GroupRenderingControl:1",
                action = "SetGroupVolume",
                innerXml = """<InstanceID>0</InstanceID><DesiredVolume>$safeVolume</DesiredVolume>""",
            )
        }

        if (groupResult.isFailure) {
            val memberBaseUrls = room.memberBaseUrls.ifEmpty { listOf(room.coordinatorBaseUrl) }
            memberBaseUrls.forEach { baseUrl ->
                runCatching {
                    soapRequest(
                        controlUrl = "$baseUrl/MediaRenderer/RenderingControl/Control",
                        serviceType = "urn:schemas-upnp-org:service:RenderingControl:1",
                        action = "SetVolume",
                        innerXml = """<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$safeVolume</DesiredVolume>""",
                    )
                }
            }
        }
    }

}

data class SonosPlaybackStatus(
    val transportState: String,
    val currentTrackUri: String?,
    val relTimeSeconds: Int?,
    val trackDurationSeconds: Int?,
)

private fun buildTrackMetadata(
    trackUrl: String,
    title: String,
    albumTitle: String,
    creator: String? = null,
): String = buildString {
    val protocolInfo = "http-get:*:${guessMimeType(trackUrl)}:*"
    append("""<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""")
    append("""<item id="0" parentID="0" restricted="1">""")
    append("<dc:title>${xmlEscape(title)}</dc:title>")
    creator?.takeIf { it.isNotBlank() }?.let {
        append("<dc:creator>${xmlEscape(it)}</dc:creator>")
    }
    append("<upnp:album>${xmlEscape(albumTitle)}</upnp:album>")
    append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
    append("""<res protocolInfo="$protocolInfo">${xmlEscape(trackUrl)}</res>""")
    append("</item></DIDL-Lite>")
}

private fun guessMimeType(url: String): String {
    val normalized = url.substringBefore('?').lowercase()
    return when {
        normalized.endsWith(".m4a") || normalized.endsWith(".mp4") -> "audio/mp4"
        normalized.endsWith(".aac") -> "audio/aac"
        normalized.endsWith(".flac") -> "audio/flac"
        normalized.endsWith(".ogg") -> "application/ogg"
        normalized.endsWith(".wav") -> "audio/wav"
        normalized.endsWith(".mp3") -> "audio/mpeg"
        else -> "audio/mpeg"
    }
}

private fun String.toDurationSeconds(): Int? {
    val parts = split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    val seconds = parts[2].toIntOrNull() ?: return null
    return (hours * 3600) + (minutes * 60) + seconds
}

private fun Int.toSonosDuration(): String {
    val safeValue = coerceAtLeast(0)
    val hours = safeValue / 3600
    val minutes = (safeValue % 3600) / 60
    val seconds = safeValue % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun readGroupVolume(baseUrl: String): String? = runCatching {
    val response = soapRequest(
        controlUrl = "$baseUrl/MediaRenderer/GroupRenderingControl/Control",
        serviceType = "urn:schemas-upnp-org:service:GroupRenderingControl:1",
        action = "GetGroupVolume",
        innerXml = """<InstanceID>0</InstanceID>""",
    )
    parseXml(response).textOfFirstTag("CurrentVolume")
}.onFailure { println("readGroupVolume error at $baseUrl: ${it.message}") }.getOrNull()

private fun readRegularVolume(baseUrl: String): String? = runCatching {
    val response = soapRequest(
        controlUrl = "$baseUrl/MediaRenderer/RenderingControl/Control",
        serviceType = "urn:schemas-upnp-org:service:RenderingControl:1",
        action = "GetVolume",
        innerXml = """<InstanceID>0</InstanceID><Channel>Master</Channel>""",
    )
    parseXml(response).textOfFirstTag("CurrentVolume")
}.onFailure { println("readRegularVolume error at $baseUrl: ${it.message}") }.getOrNull()

private fun soapRequest(controlUrl: String, serviceType: String, action: String, innerXml: String): String {
    val body = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
            <s:Body>
                <u:$action xmlns:u="$serviceType">
                    $innerXml
                </u:$action>
            </s:Body>
        </s:Envelope>
    """.trimIndent()
    val requestBytes = body.toByteArray(StandardCharsets.UTF_8)
    val uri = URI(controlUrl)
    val host = uri.host ?: error("Invalid SOAP control URL host: $controlUrl")
    val path = buildString {
        append(uri.rawPath?.ifBlank { "/" } ?: "/")
        uri.rawQuery?.let {
            append('?')
            append(it)
        }
    }
    val port = if (uri.port >= 0) uri.port else 80

    val httpRequest = buildString {
        append("POST $path HTTP/1.1\r\n")
        append("Host: $host:$port\r\n")
        append("Content-Type: text/xml; charset=utf-8\r\n")
        append("SOAPACTION: \"$serviceType#$action\"\r\n")
        append("Content-Length: ${requestBytes.size}\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }.toByteArray(StandardCharsets.UTF_8)

    return Socket(host, port).use { socket ->
        socket.soTimeout = 10_000
        val output = socket.getOutputStream()
        output.write(httpRequest)
        output.write(requestBytes)
        output.flush()

        val rawResponse = socket.getInputStream().readFully()
        val headerEnd = rawResponse.indexOf("\r\n\r\n")
        val headerText = if (headerEnd >= 0) rawResponse.substring(0, headerEnd) else rawResponse
        val responseBody = if (headerEnd >= 0) rawResponse.substring(headerEnd + 4) else ""
        val statusLine = headerText.lineSequence().firstOrNull().orEmpty()
        val statusCode = statusLine
            .split(' ')
            .getOrNull(1)
            ?.toIntOrNull()
            ?: error("Invalid HTTP status from $controlUrl: $statusLine")

        if (statusCode !in 200..299) {
            println("SOAP Error Action=$action URL=$controlUrl Status=$statusCode")
            println("SOAP Request Body:\n$body")
            println("SOAP Response Body:\n$responseBody")
            error("HTTP $statusCode from $controlUrl: ${responseBody.take(300)}")
        }

        responseBody
    }
}

private fun parseHeaderValue(payload: String, headerName: String): String? =
    payload.lineSequence()
        .firstOrNull { it.startsWith("$headerName:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun InputStream.readFully(): String =
    bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

private fun parseXml(body: String): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
    }
    return factory.newDocumentBuilder()
        .parse(ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)))
        .documentElement
}

private fun Element.childText(tagName: String): String? {
    val nodes = getElementsByTagName(tagName)
    if (nodes.length == 0) return null
    return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
}

private fun Element.textOfFirstTag(tagName: String): String? =
    getElementsByTagName(tagName).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }

private fun org.w3c.dom.NodeList.toElementList(): List<Element> =
    buildList {
        for (index in 0 until length) {
            val element = item(index) as? Element ?: continue
            add(element)
        }
    }

private fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { char ->
        append(
            when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> char
            }
        )
    }
}

private fun originOf(url: String): String = runCatching {
    val uri = URI(url)
    val scheme = uri.scheme ?: return@runCatching ""
    val host = uri.host ?: return@runCatching ""
    val port = uri.port
    if (port >= 0) "$scheme://$host:$port" else "$scheme://$host"
}.getOrDefault("")

private fun normalizeSonosUuid(rawValue: String?): String =
    rawValue.orEmpty()
        .substringAfter("uuid:", missingDelimiterValue = rawValue.orEmpty())
        .substringBefore(':')
        .trim()

private fun asRadioStreamUri(url: String): String = when {
    url.startsWith("http://", ignoreCase = true) ->
        "x-rincon-mp3radio://${url.removePrefix("http://")}"
    url.startsWith("https://", ignoreCase = true) ->
        "x-rincon-mp3radio://${url.removePrefix("https://")}"
    else -> url
}
