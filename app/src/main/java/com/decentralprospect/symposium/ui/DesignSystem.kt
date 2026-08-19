package com.decentralprospect.symposium

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Patterns
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.hsl
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import android.graphics.Color as AndroidColor

enum class AccessibilityFontScale(
    val label: String,
    val talkBackLabel: String,
    val multiplier: Float
) {
    SYSTEM("Системный", "Системный размер шрифта Android", 1f),
    LARGE("Крупный", "Крупный шрифт", 1.18f),
    EXTRA_LARGE("Очень крупный", "Очень крупный шрифт", 1.34f)
}

enum class AppThemeMode(
    val label: String,
    val talkBackLabel: String
) {
    SYSTEM("Системная", "Использовать тему устройства"),
    DARK("Тёмная", "Всегда использовать тёмную тему"),
    LIGHT("Светлая", "Всегда использовать светлую тему")
}


internal val LocalAccessibilityFontScale = staticCompositionLocalOf { AccessibilityFontScale.SYSTEM }

@Composable
internal fun accessibleAccentColor(): Color = AppAccent

@Composable
internal fun accessiblePositiveColor(): Color = AppSuccess

@Composable
internal fun accessibleDangerColor(): Color = AppError

internal val CardShape = RoundedCornerShape(24.dp)
internal val InnerCardShape = RoundedCornerShape(14.dp)
internal val AppButtonShape = RoundedCornerShape(10.dp)
internal const val DEFAULT_RELAY_HTTP_PORT = 443

internal enum class RootScreen { HOME, MENU, SERVERS, SETTINGS, ABOUT }
internal enum class ConnectLinkFormat { SYMPOSIUM_DEEP_LINK, HTTP_REDIRECT }

internal fun connectLinkFormatLabel(format: ConnectLinkFormat): String {
    return when (format) {
        ConnectLinkFormat.SYMPOSIUM_DEEP_LINK -> "symposium://"
        ConnectLinkFormat.HTTP_REDIRECT -> "HTTPS redirect"
    }
}

enum class MeetLayout(val label: String) {
    AUTO("AUTO"),
    GRID_2("2×2"),
    GRID_3("3×3"),
    GRID_4("4×4"),
    SPOTLIGHT("SPOT")
}

internal fun columnsFor(layout: MeetLayout, count: Int): Int {
    return when (layout) {
        MeetLayout.GRID_2 -> 2
        MeetLayout.GRID_3 -> 3
        MeetLayout.GRID_4 -> 4
        MeetLayout.SPOTLIGHT -> 2
        MeetLayout.AUTO -> when {
            count <= 1 -> 1
            count <= 4 -> 2
            count <= 9 -> 3
            else -> 4
        }
    }
}

enum class CallViewMode { FOCUS, GRID }

enum class AudioOutputRoute {
    EARPIECE,
    SPEAKER,
    HEADSET
}

internal fun audioOutputRouteFromRaw(raw: String): AudioOutputRoute {
    return when (raw.trim().lowercase()) {
        "speaker" -> AudioOutputRoute.SPEAKER
        "bluetooth", "wired_headset", "headset" -> AudioOutputRoute.HEADSET
        "earpiece" -> AudioOutputRoute.EARPIECE
        else -> AudioOutputRoute.EARPIECE
    }
}

internal fun AudioOutputRoute.toWireValue(): String {
    return when (this) {
        AudioOutputRoute.EARPIECE -> "earpiece"
        AudioOutputRoute.SPEAKER -> "speaker"
        AudioOutputRoute.HEADSET -> "headset"
    }
}

internal data class ConnectLinkPayload(
    val ip: String,
    val room: String,
    val tlsPin: String,
    val username: String = "",
    val moderatorKey: String = "",
    val format: ConnectLinkFormat = ConnectLinkFormat.SYMPOSIUM_DEEP_LINK
)

data class LobbyPeerStatus(
    val peerId: String,
    val username: String,
    val joinedAt: Long = 0L
)

internal fun parseConnectLink(raw: String): ConnectLinkPayload? {
    val input = raw.trim()
    if (input.isBlank()) return null

    val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return null
    val scheme = uri.scheme?.trim()?.lowercase().orEmpty()
    val isDeepLink = scheme == "symposium"
    val isHttpRedirect = scheme == "http" || scheme == "https"
    if (!isDeepLink && !isHttpRedirect) return null

    val room = uri.getQueryParameter("room")?.trim().orEmpty()
    val tlsPin = uri.getQueryParameter("tlsPin")?.trim().orEmpty()
    val username = uri.getQueryParameter("username")?.trim().orEmpty()
    val moderatorKey = if (isDeepLink) uri.getQueryParameter("modKey")?.trim().orEmpty() else ""

    val explicitIp = uri.getQueryParameter("ip")?.trim().orEmpty()
    val httpHost = if (isHttpRedirect) uri.host?.trim().orEmpty() else ""
    val httpPort = if (isHttpRedirect) uri.port else -1
    val httpAuthority = when {
        httpHost.isBlank() -> ""
        httpPort > 0 && httpPort != DEFAULT_RELAY_HTTP_PORT -> "$httpHost:$httpPort"
        else -> httpHost
    }
    val ip = when {
        explicitIp.isBlank() -> httpAuthority
        isHttpRedirect && httpPort > 0 && httpPort != DEFAULT_RELAY_HTTP_PORT &&
            explicitIp.substringAfterLast(":", "").toIntOrNull() == null -> "$explicitIp:$httpPort"
        else -> explicitIp
    }

    if (ip.isBlank() || room.isBlank() || tlsPin.isBlank()) return null

    return ConnectLinkPayload(
        ip = ip,
        room = room,
        tlsPin = tlsPin,
        username = username,
        moderatorKey = moderatorKey,
        format = if (isHttpRedirect) ConnectLinkFormat.HTTP_REDIRECT else ConnectLinkFormat.SYMPOSIUM_DEEP_LINK
    )
}

internal fun buildConnectDeepLink(
    ip: String,
    httpsPort: Int?,
    room: String,
    relayTlsPin: String?,
    moderatorKey: String? = null
): String {
    val pin = relayTlsPin?.trim().orEmpty()
    val port = httpsPort ?: DEFAULT_RELAY_HTTP_PORT
    val endpoint = if (port == DEFAULT_RELAY_HTTP_PORT) ip.trim() else "${ip.trim()}:$port"
    val builder = Uri.Builder()
        .scheme("symposium")
        .authority("connect")
        .appendQueryParameter("ip", endpoint)
        .appendQueryParameter("room", room.trim())
        .appendQueryParameter("tlsPin", pin)

    val modKey = moderatorKey?.trim().orEmpty()
    if (modKey.isNotBlank()) {
        builder.appendQueryParameter("modKey", modKey)
    }

    return builder.build().toString()
}

internal fun buildConnectHttpRedirectLink(
    ip: String,
    httpsPort: Int?,
    room: String,
    relayTlsPin: String?,
    moderatorKey: String? = null
): String {
    val pin = relayTlsPin?.trim().orEmpty()
    val port = httpsPort ?: DEFAULT_RELAY_HTTP_PORT
    val authority = if (port == 443) ip.trim() else "${ip.trim()}:$port"

    val builder = Uri.Builder()
        .scheme("https")
        .encodedAuthority(authority)
        .path("connect")
        .appendQueryParameter("ip", authority)
        .appendQueryParameter("room", room.trim())
        .appendQueryParameter("tlsPin", pin)


    return builder.build().toString()
}

internal fun generateQrBitmap(content: String, sizePx: Int = 900): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
    )

    val matrix = MultiFormatWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        hints
    )

    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)

    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(
                x,
                y,
                if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
            )
        }
    }

    return bitmap
}

internal fun saveQrBitmapToPictures(
    context: Context,
    bitmap: Bitmap,
    fileName: String = "symposium_qr_${System.currentTimeMillis()}.png"
): Uri? {
    return runCatching {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Symposium")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null

            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return@runCatching null

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            uri
        } else {
            val dir = File(
                context.getExternalFilesDir(null),
                "Symposium"
            ).apply { mkdirs() }

            val file = File(dir, fileName)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            Uri.fromFile(file)
        }
    }.getOrNull()
}

internal fun normalizeIpInput(raw: String): String {
    val input = raw.trim()
    if (input.isBlank()) return ""

    if ("://" in input) {
        val uri = runCatching { Uri.parse(input) }.getOrNull()
        val host = uri?.host?.trim().orEmpty()
        if (host.isNotBlank()) return host
    }

    return input
        .substringBefore("/")
        .substringBefore(":")
        .trim()
}

internal fun isValidIpAddress(value: String): Boolean {
    return value.isNotBlank() && Patterns.IP_ADDRESS.matcher(value).matches()
}

internal fun openRoomsLabel(count: Int): String {
    return count.toString()
}

enum class RelayVersionState {
    UNKNOWN,
    NOT_INSTALLED,
    CURRENT,
    OUTDATED
}

data class InstallServer(
    val id: String,
    val name: String,
    val ip: String,
    val username: String,
    val password: String,
    val installed: Boolean,
    val sshHostKeyPin: String? = null,
    val httpsPort: Int? = null,
    val relayTlsPin: String? = null,
    val adminToken: String? = null,
    val deploymentProfile: DeploymentProfile? = null,
    val openRooms: List<OpenRoomInfo> = emptyList(),
    val relayVersion: String? = null,
    val relayVersionState: RelayVersionState = RelayVersionState.UNKNOWN
)

internal fun serverDisplayAddress(server: InstallServer): String {
    return server.ip.ifBlank { server.name }
}

internal fun displayName(peer: PeerStatus): String {
    val n = peer.username.trim()
    return if (n.isNotBlank()) n else peer.peerId
}

internal fun displayName(peer: LobbyPeerStatus): String {
    val n = peer.username.trim()
    return if (n.isNotBlank()) n else peer.peerId
}

internal fun tileColorFromName(name: String): Color {
    val ch = name.trim().firstOrNull()?.uppercaseChar() ?: '#'
    val hue = ((ch.code * 29) % 360).toFloat()
    return hsl(hue, 0.45f, 0.19f)
}

internal fun serverStateLabel(server: InstallServer): String {
    if (server.relayVersionState == RelayVersionState.UNKNOWN) {
        return "Проверка версии…"
    }
    if (!server.installed || server.relayVersionState == RelayVersionState.NOT_INSTALLED) {
        return "SymposiumRelay не установлен"
    }

    return when (server.relayVersionState) {
        RelayVersionState.CURRENT -> "Актуальная · ${server.relayVersion.orEmpty()}"
        RelayVersionState.OUTDATED -> "Не актуальна · ${server.relayVersion ?: "—"}"
        RelayVersionState.UNKNOWN -> "Проверка версии…"
        RelayVersionState.NOT_INSTALLED -> "SymposiumRelay не установлен"
    }
}

@Composable
internal fun serverStateColor(server: InstallServer): Color {
    if (server.relayVersionState == RelayVersionState.UNKNOWN) {
        return AppIdle
    }
    if (!server.installed || server.relayVersionState == RelayVersionState.NOT_INSTALLED) {
        return accessibleDangerColor()
    }

    return when (server.relayVersionState) {
        RelayVersionState.CURRENT -> accessiblePositiveColor()
        RelayVersionState.OUTDATED -> AppWarning
        RelayVersionState.UNKNOWN -> AppIdle
        RelayVersionState.NOT_INSTALLED -> accessibleDangerColor()
    }
}

internal interface UiBinder {
    fun setStatus(s: String)
    fun setConnected(on: Boolean)
    fun setMic(on: Boolean)
    fun setVideo(on: Boolean)
    fun setOutput(on: Boolean)
    fun setSpeaker(on: Boolean)
    fun setPeerId(id: String)
    fun setIceState(s: String)
    fun setPcState(s: String)
    fun setCameraDebug(s: String)
    fun updatePeers(peers: List<PeerStatus>)
    fun setAudioRoute(route: String, headsetAvailable: Boolean)
    fun setRole(role: String)
    fun setLobbyWaiting(waiting: Boolean)
    fun updateLobbyPending(peers: List<LobbyPeerStatus>)
    fun setMuteAll(on: Boolean)
    fun setForcedMute(on: Boolean)
    fun setPeerMuted(peerId: String, muted: Boolean)
    fun setSelfHandRaised(on: Boolean)
    fun setPeerHandRaised(peerId: String, raised: Boolean)
    fun setPeerAudioEnabled(peerId: String, enabled: Boolean)
    fun setPeerVideoEnabled(peerId: String, enabled: Boolean)
}

internal enum class ActionButtonKind { PRIMARY, SECONDARY, DANGER, GHOST }

@Composable
internal fun appTextButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = Color.Transparent,
    contentColor = AppAccent,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = DarkTextSecondary.copy(alpha = 0.55f)
)
