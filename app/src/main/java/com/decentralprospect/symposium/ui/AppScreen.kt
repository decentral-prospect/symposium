package com.decentralprospect.symposium

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import androidx.compose.foundation.layout.height

@Composable
internal fun AppScreen(
    appVersion: String,
    expectedRelayVersion: String = appVersion,
    reconnectMode: Boolean,
    telemetryEnabled: Boolean = false,
    telemetryPromptShown: Boolean = true,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeModeChange: (AppThemeMode) -> Unit = {},
    onTelemetryConsentResult: (Boolean) -> Unit = {},
    onTelemetryEnabledChange: (Boolean) -> Unit = {},
    onRequestBind: (UiBinder) -> Unit,
    initialConnectLink: String? = null,
    onConnect: (String, String, String, String, String) -> Unit,
    onCancelReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleSpeaker: () -> Boolean,
    onSetAudioRoute: (String) -> Unit = {},
    onToggleMic: () -> Boolean,
    onToggleVideo: () -> Boolean,
    onToggleOutput: () -> Boolean,
    onSwitchCamera: () -> Unit,
    onApproveLobbyPeer: (String) -> Unit = {},
    onRejectLobbyPeer: (String) -> Unit = {},
    onKickPeer: (String) -> Unit = {},
    onMutePeer: (String) -> Unit = {},
    onUnmutePeer: (String) -> Unit = {},
    onSetMuteAll: (Boolean) -> Unit = {},
    onInstall: suspend (ip: String, login: String, password: String, expectedSshHostKeyPin: String?, logger: suspend (String) -> Unit) -> RemoteInstaller.InstallResult,
    onRemoveRelay: suspend (ip: String, login: String, password: String, expectedSshHostKeyPin: String?, logger: suspend (String) -> Unit) -> RemoteInstaller.RelayRemovalResult,
    onObserveSshHostKeyPin: suspend (ip: String) -> String,
    onProbeServer: suspend (ip: String, login: String, password: String, expectedSshHostKeyPin: String?) -> RemoteInstaller.ProbeResult,
    onSetRoomOpenState: suspend (ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?, roomName: String, open: Boolean) -> RemoteInstaller.RoomAdminResult,
    onFetchOpenRooms: suspend (ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?) -> RemoteInstaller.RoomAdminResult,
    onFetchRelayVersion: suspend (ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?) -> RemoteInstaller.RelayVersionResult,
    onSetHandRaised: (Boolean) -> Unit = {},
    onLowerPeerHand: (String) -> Unit = {},
    onSetMicAudioEnabled: (Boolean) -> Unit = {},
    initialAccessibilityFontScale: AccessibilityFontScale = AccessibilityFontScale.SYSTEM,
    onAccessibilityFontScaleChange: (AccessibilityFontScale) -> Unit = {}
) {
    val context = LocalContext.current
    val platformDensity = LocalDensity.current

    var accessibilityFontScale by remember { mutableStateOf(initialAccessibilityFontScale) }

    var inCallUi by remember { mutableStateOf(false) }
    var moderatorPanelOpen by remember { mutableStateOf(false) }
    var dismissedModeratorBubblePeerIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var pipOffsetX by remember { mutableStateOf(0f) }
    var pipOffsetY by remember { mutableStateOf(0f) }
    var pipExpanded by remember { mutableStateOf(false) }
    var pinnedPeerId by remember { mutableStateOf<String?>(null) }

    var connectLink by remember { mutableStateOf("") }
    var usernameInput by remember { mutableStateOf("") }

    val homeScope = rememberCoroutineScope()
    val homeServersStore = remember(context) { InstallServersStore(context) }
    val homeServers = remember { mutableStateListOf<InstallServer>() }
    var homeServersLoaded by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var showCreateMeetingDialog by remember { mutableStateOf(false) }
    var showHomeServerGuideDialog by remember { mutableStateOf(false) }
    var showHomeAddServerDialog by remember { mutableStateOf(false) }
    var pendingHomeSshHostKeyTrust by remember { mutableStateOf<PendingHomeSshHostKeyTrust?>(null) }
    var homeQrDialogLink by remember { mutableStateOf<String?>(null) }
    var homeQrDialogTitle by remember { mutableStateOf("QR-код") }

    var homeAddIp by remember { mutableStateOf("") }
    var homeAddUser by remember { mutableStateOf("") }
    var homeAddPassword by remember { mutableStateOf("") }
    var homeAddError by remember { mutableStateOf<String?>(null) }
    var homeAddLoading by remember { mutableStateOf(false) }

    val homeInstallLogs = remember { mutableStateListOf<String>() }
    var selectedHomeInstallServerId by remember { mutableStateOf<String?>(null) }
    var homeInstallLoading by remember { mutableStateOf(false) }
    var homeInstallError by remember { mutableStateOf<String?>(null) }

    var selectedHomeCreateServerId by remember { mutableStateOf<String?>(null) }
    var homeCreateRoomName by remember { mutableStateOf("") }
    var homeRoomError by remember { mutableStateOf<String?>(null) }
    var homeRoomLoading by remember { mutableStateOf(false) }

    var selectedHomeMeetingServerId by remember { mutableStateOf<String?>(null) }
    var selectedHomeMeetingRoomName by remember { mutableStateOf<String?>(null) }
    var confirmHomeCloseMeeting by remember { mutableStateOf(false) }
    var homeServersRefreshing by remember { mutableStateOf(false) }

    var status by remember { mutableStateOf("offline") }
    var connected by remember { mutableStateOf(false) }
    var micEnabled by remember { mutableStateOf(false) }
    var videoEnabled by remember { mutableStateOf(false) }
    var outputOn by remember { mutableStateOf(true) }
    var speakerOn by remember { mutableStateOf(false) }
    var audioRoute by remember { mutableStateOf(AudioOutputRoute.EARPIECE) }
    var headsetAvailable by remember { mutableStateOf(false) }
    var peerId by remember { mutableStateOf("—") }
    var iceState by remember { mutableStateOf("—") }
    var pcState by remember { mutableStateOf("—") }
    var cameraDebug by remember { mutableStateOf("camera: idle") }

    var localRole by remember { mutableStateOf(ROLE_GUEST) }
    var lobbyWaiting by remember { mutableStateOf(false) }
    val peerMuteStates = remember { mutableStateMapOf<String, Boolean>() }
    val peerHandStates = remember { mutableStateMapOf<String, Boolean>() }
    val peerAudioEnabledStates = remember { mutableStateMapOf<String, Boolean>() }
    val peerVideoEnabledStates = remember { mutableStateMapOf<String, Boolean>() }
    var selfHandRaised by remember { mutableStateOf(false) }
    var muteAllEnabled by remember { mutableStateOf(false) }
    var selfMutedByModerator by remember { mutableStateOf(false) }
    val pendingLobbyState = remember { mutableStateListOf<LobbyPeerStatus>() }

    val pendingLobbyIds = pendingLobbyState.map { it.peerId }.toSet()

    LaunchedEffect(pendingLobbyIds) {
        dismissedModeratorBubblePeerIds =
            if (pendingLobbyIds.isEmpty()) {
                emptySet()
            } else {
                dismissedModeratorBubblePeerIds.intersect(pendingLobbyIds)
            }
    }

    val peersState = remember { mutableStateListOf<PeerStatus>() }
    var currentScreen by remember { mutableStateOf(RootScreen.HOME) }
    var serversRefreshNonce by remember { mutableStateOf(0) }
    var callViewMode by remember { mutableStateOf(CallViewMode.FOCUS) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var lastBackPressAt by remember { mutableStateOf(0L) }

    val parsedConnectLink = remember(connectLink) { parseConnectLink(connectLink) }
    val selectedHomeCreateServer = homeServers.firstOrNull { it.id == selectedHomeCreateServerId }
    val selectedHomeInstallServer = homeServers.firstOrNull { it.id == selectedHomeInstallServerId }
    val selectedHomeMeetingServer = homeServers.firstOrNull { it.id == selectedHomeMeetingServerId }
    val selectedHomeMeetingRoom = selectedHomeMeetingServer?.openRooms?.firstOrNull { it.name == selectedHomeMeetingRoomName }

    fun loadHomeServersFromStore() {
        homeServers.clear()
        homeServers.addAll(homeServersStore.load())
        homeServersLoaded = true
    }

    fun persistHomeServers() {
        homeServersStore.save(homeServers)
    }

    fun updateHomeServer(serverId: String, transform: (InstallServer) -> InstallServer) {
        val index = homeServers.indexOfFirst { it.id == serverId }
        if (index >= 0) {
            homeServers[index] = transform(homeServers[index])
            persistHomeServers()
        }
    }

    fun homeRelayVersionStateFor(actualVersion: String): RelayVersionState {
        return if (actualVersion.trim() == expectedRelayVersion.trim()) {
            RelayVersionState.CURRENT
        } else {
            RelayVersionState.OUTDATED
        }
    }

    fun copyHomeValue(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    fun clearHomeAddServerForm() {
        homeAddIp = ""
        homeAddUser = ""
        homeAddPassword = ""
        homeAddError = null
        homeAddLoading = false
    }

    fun refreshHomeServer(server: InstallServer) {
        if (!server.installed) return
        homeScope.launch {
            val versionResult = runCatching {
                onFetchRelayVersion(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken)
            }

            versionResult.onSuccess { info ->
                updateHomeServer(server.id) { current ->
                    current.copy(
                        installed = true,
                        relayVersion = info.version.trim(),
                        relayVersionState = homeRelayVersionStateFor(info.version)
                    )
                }
            }.onFailure {
                updateHomeServer(server.id) { current ->
                    current.copy(
                        installed = false,
                        openRooms = emptyList(),
                        relayVersion = null,
                        relayVersionState = RelayVersionState.NOT_INSTALLED
                    )
                }
                return@launch
            }

            val roomsResult = runCatching {
                onFetchOpenRooms(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken)
            }

            roomsResult.onSuccess { result ->
                updateHomeServer(server.id) { current ->
                    current.copy(
                        openRooms = result.openRooms,
                        sshHostKeyPin = current.sshHostKeyPin ?: result.observedSshHostKeyPin,
                        relayTlsPin = result.relayInfo?.pin ?: current.relayTlsPin,
                        httpsPort = result.relayInfo?.httpsPort ?: current.httpsPort,
                        adminToken = result.relayInfo?.adminToken ?: current.adminToken
                    )
                }
            }
        }
    }

    fun refreshHomeServers() {
        homeServersRefreshing = true
        loadHomeServersFromStore()
        val snapshot = homeServers.toList()
        if (snapshot.none { it.installed }) {
            homeServersRefreshing = false
            return
        }
        homeScope.launch {
            snapshot.filter { it.installed }.forEach { server ->
                val versionResult = runCatching {
                    onFetchRelayVersion(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken)
                }
                if (versionResult.isFailure) {
                    updateHomeServer(server.id) { current ->
                        current.copy(
                            installed = false,
                            openRooms = emptyList(),
                            relayVersion = null,
                            relayVersionState = RelayVersionState.NOT_INSTALLED
                        )
                    }
                    return@forEach
                }

                val version = versionResult.getOrNull()?.version.orEmpty()
                updateHomeServer(server.id) { current ->
                    current.copy(
                        installed = true,
                        relayVersion = version,
                        relayVersionState = homeRelayVersionStateFor(version)
                    )
                }

                val roomsResult = runCatching {
                    onFetchOpenRooms(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken)
                }
                roomsResult.onSuccess { result ->
                    updateHomeServer(server.id) { current ->
                        current.copy(
                            openRooms = result.openRooms,
                            sshHostKeyPin = current.sshHostKeyPin ?: result.observedSshHostKeyPin,
                            relayTlsPin = result.relayInfo?.pin ?: current.relayTlsPin,
                            httpsPort = result.relayInfo?.httpsPort ?: current.httpsPort,
                            adminToken = result.relayInfo?.adminToken ?: current.adminToken
                        )
                    }
                }
            }
            homeServersRefreshing = false
        }
    }

    fun startHomeAddServer() {
        if (homeAddLoading) return

        val ip = normalizeIpInput(homeAddIp)
        val user = homeAddUser.trim()

        if (ip.isBlank() || user.isBlank() || homeAddPassword.isBlank()) {
            homeAddError = "Укажите IP, логин и пароль"
            return
        }

        if (!isValidIpAddress(ip)) {
            homeAddError = "Укажите корректный IP адрес"
            return
        }

        if (homeServers.any { it.ip == ip }) {
            homeAddError = "Этот сервер уже добавлен"
            return
        }

        homeAddError = null
        homeAddLoading = true
        homeScope.launch {
            val observedPin = runCatching { onObserveSshHostKeyPin(ip) }.getOrElse {
                homeAddError = it.message ?: "Не удалось получить SSH-ключ сервера"
                homeAddLoading = false
                return@launch
            }

            pendingHomeSshHostKeyTrust = PendingHomeSshHostKeyTrust(
                ip = ip,
                username = user,
                password = homeAddPassword,
                observedPin = observedPin
            )
            homeAddLoading = false
        }
    }

    fun finishHomeAddServerAfterTrust(trust: PendingHomeSshHostKeyTrust) {
        if (homeAddLoading) return
        homeAddLoading = true
        homeAddError = null
        homeScope.launch {
            val probe = runCatching {
                onProbeServer(trust.ip, trust.username, trust.password, trust.observedPin)
            }

            val probed = probe.getOrElse {
                homeAddError = it.message ?: "Ошибка SSH-аутентификации: неверный логин или пароль"
                homeAddLoading = false
                pendingHomeSshHostKeyTrust = null
                showHomeAddServerDialog = true
                return@launch
            }

            val newServer = InstallServer(
                id = UUID.randomUUID().toString(),
                name = trust.ip,
                ip = trust.ip,
                username = trust.username,
                password = trust.password,
                installed = probed.installed,
                sshHostKeyPin = trust.observedPin,
                httpsPort = probed.relayInfo?.httpsPort,
                relayTlsPin = probed.relayInfo?.pin,
                adminToken = probed.relayInfo?.adminToken,
                openRooms = probed.openRooms,
                relayVersionState = if (probed.installed) RelayVersionState.UNKNOWN else RelayVersionState.NOT_INSTALLED
            )

            homeServers.add(newServer)
            persistHomeServers()
            pendingHomeSshHostKeyTrust = null
            showHomeAddServerDialog = false
            clearHomeAddServerForm()
            if (newServer.installed) refreshHomeServer(newServer)
        }
    }

    fun installRelayFromHome(server: InstallServer) {
        if (homeInstallLoading) return
        if (server.ip.isBlank() || server.username.isBlank() || server.password.isBlank()) {
            homeInstallError = "Для установки нужны IP, логин и пароль"
            return
        }

        homeInstallError = null
        homeInstallLoading = true
        homeInstallLogs.clear()
        homeScope.launch {
            val result = runCatching {
                onInstall(
                    server.ip.trim(),
                    server.username.trim(),
                    server.password,
                    server.sshHostKeyPin
                ) { line ->
                    withContext(Dispatchers.Main) { homeInstallLogs.add(line) }
                }
            }

            val installed = result.getOrElse {
                homeInstallError = it.message ?: "Не удалось установить SymposiumRelay"
                homeInstallLoading = false
                return@launch
            }

            if (!installed.success) {
                homeInstallError = "Установка завершилась с ошибкой (код ${installed.exitStatus})"
                homeInstallLoading = false
                return@launch
            }

            val finalRelayPin = installed.relayInfo?.pin ?: server.relayTlsPin
            val finalAdminToken = installed.relayInfo?.adminToken ?: server.adminToken

            updateHomeServer(server.id) { current ->
                current.copy(
                    installed = true,
                    sshHostKeyPin = current.sshHostKeyPin ?: installed.observedSshHostKeyPin,
                    relayTlsPin = finalRelayPin ?: current.relayTlsPin,
                    httpsPort = installed.relayInfo?.httpsPort ?: current.httpsPort,
                    adminToken = finalAdminToken ?: current.adminToken,
                    relayVersion = expectedRelayVersion,
                    relayVersionState = RelayVersionState.CURRENT
                )
            }

            if (finalRelayPin.isNullOrBlank() || finalAdminToken.isNullOrBlank()) {
                homeInstallError = "Relay установлен, но приложение не получило TLS pin или adminToken. Переустановите SymposiumRelay."
                homeInstallLoading = false
                return@launch
            }

            homeInstallLoading = false
            homeInstallError = null
            homeInstallLogs.clear()
            selectedHomeInstallServerId = null
            selectedHomeCreateServerId = server.id
            homeCreateRoomName = ""
            homeRoomError = null
            Toast.makeText(context, "SymposiumRelay установлен", Toast.LENGTH_SHORT).show()
        }
    }

    fun createHomeRoom(server: InstallServer) {
        if (homeRoomLoading) return
        val roomName = homeCreateRoomName.trim()
        if (roomName.isBlank()) {
            homeRoomError = "Введите название комнаты"
            return
        }
        if (server.openRooms.any { it.name.equals(roomName, ignoreCase = true) }) {
            homeRoomError = "Комната с таким названием уже открыта"
            return
        }
        if (!server.installed || server.relayTlsPin.isNullOrBlank() || server.adminToken.isNullOrBlank()) {
            homeRoomError = "На этом сервере нельзя создать встречу: SymposiumRelay не установлен"
            return
        }

        homeRoomError = null
        homeRoomLoading = true
        homeScope.launch {
            val result = runCatching {
                onSetRoomOpenState(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken, roomName, true)
            }

            val roomResult = result.getOrElse {
                homeRoomError = it.message ?: "Не удалось создать встречу"
                homeRoomLoading = false
                return@launch
            }

            updateHomeServer(server.id) { current ->
                current.copy(
                    openRooms = roomResult.openRooms,
                    sshHostKeyPin = current.sshHostKeyPin ?: roomResult.observedSshHostKeyPin,
                    relayTlsPin = roomResult.relayInfo?.pin ?: current.relayTlsPin,
                    httpsPort = roomResult.relayInfo?.httpsPort ?: current.httpsPort,
                    adminToken = roomResult.relayInfo?.adminToken ?: current.adminToken
                )
            }

            homeRoomLoading = false
            showCreateMeetingDialog = false
            selectedHomeCreateServerId = null
            homeCreateRoomName = ""
            homeRoomError = null
        }
    }

    fun closeHomeMeeting(server: InstallServer, room: OpenRoomInfo) {
        if (homeRoomLoading) return
        homeRoomLoading = true
        homeScope.launch {
            val result = runCatching {
                onSetRoomOpenState(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken, room.name, false)
            }

            val roomResult = result.getOrElse {
                Toast.makeText(context, it.message ?: "Не удалось закрыть комнату", Toast.LENGTH_SHORT).show()
                homeRoomLoading = false
                return@launch
            }

            updateHomeServer(server.id) { current ->
                current.copy(
                    openRooms = roomResult.openRooms,
                    sshHostKeyPin = current.sshHostKeyPin ?: roomResult.observedSshHostKeyPin,
                    relayTlsPin = roomResult.relayInfo?.pin ?: current.relayTlsPin,
                    httpsPort = roomResult.relayInfo?.httpsPort ?: current.httpsPort,
                    adminToken = roomResult.relayInfo?.adminToken ?: current.adminToken
                )
            }

            homeRoomLoading = false
            selectedHomeMeetingServerId = null
            selectedHomeMeetingRoomName = null
            confirmHomeCloseMeeting = false
        }
    }

    fun homeGuestLink(server: InstallServer, room: OpenRoomInfo): String? {
        val pin = server.relayTlsPin?.trim()
        if (pin.isNullOrBlank()) return null
        return buildConnectHttpRedirectLink(server.ip, server.httpsPort, room.name, pin)
    }

    fun homeGuestQrLink(server: InstallServer, room: OpenRoomInfo): String? {
        val pin = server.relayTlsPin?.trim()
        if (pin.isNullOrBlank()) return null
        return buildConnectDeepLink(server.ip, room.name, pin)
    }

    fun homeModeratorLink(server: InstallServer, room: OpenRoomInfo): String? {
        val pin = server.relayTlsPin?.trim()
        val modKey = room.moderatorKey.trim()
        if (pin.isNullOrBlank() || modKey.isBlank()) return null
        return buildConnectDeepLink(server.ip, room.name, pin, moderatorKey = modKey)
    }

    fun showMissingLinkDataError() {
        Toast.makeText(context, "Нет TLS pin или moderator_key", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(initialAccessibilityFontScale) {
        accessibilityFontScale = initialAccessibilityFontScale
    }


    if (!telemetryPromptShown) {
        TelemetryConsentDialog(
            onEnable = { onTelemetryConsentResult(true) },
            onSkip = { onTelemetryConsentResult(false) }
        )
    }

    BackHandler {
        when {
            moderatorPanelOpen -> moderatorPanelOpen = false
            currentScreen == RootScreen.MENU -> currentScreen = RootScreen.HOME
            currentScreen != RootScreen.HOME -> currentScreen = RootScreen.MENU
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressAt < 1800) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressAt = now
                    Toast.makeText(context, "Нажмите ещё раз, чтобы выйти", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val uiStateBinder = remember {
        object : UiBinder {
            override fun setStatus(s: String) { mainHandler.post { status = s } }
            override fun setConnected(on: Boolean) { mainHandler.post { connected = on } }
            override fun setMic(on: Boolean) { mainHandler.post { micEnabled = on } }
            override fun setVideo(on: Boolean) { mainHandler.post { videoEnabled = on } }
            override fun setOutput(on: Boolean) { mainHandler.post { outputOn = on } }
            override fun setSpeaker(on: Boolean) { mainHandler.post { speakerOn = on } }
            override fun setAudioRoute(route: String, headsetAvailableValue: Boolean) {
                mainHandler.post {
                    val parsedRoute = audioOutputRouteFromRaw(route)
                    audioRoute = parsedRoute
                    headsetAvailable = headsetAvailableValue
                    speakerOn = parsedRoute == AudioOutputRoute.SPEAKER
                }
            }
            override fun setPeerId(id: String) { mainHandler.post { peerId = id } }
            override fun setIceState(s: String) { mainHandler.post { iceState = s } }
            override fun setPcState(s: String) { mainHandler.post { pcState = s } }
            override fun setCameraDebug(s: String) { mainHandler.post { cameraDebug = s } }
            override fun setPeerAudioEnabled(peerId: String, enabled: Boolean) {
                mainHandler.post {
                    val id = peerId.trim()
                    if (id.isNotBlank()) {
                        peerAudioEnabledStates[id] = enabled
                    }
                }
            }
            override fun setPeerVideoEnabled(peerId: String, enabled: Boolean) {
                mainHandler.post {
                    val id = peerId.trim()
                    if (id.isNotBlank()) {
                        peerVideoEnabledStates[id] = enabled
                    }
                }
            }
            override fun updatePeers(peers: List<PeerStatus>) {
                mainHandler.post {
                    peersState.clear()
                    peersState.addAll(peers)

                    val liveIds = peers.map { it.peerId }.toSet()
                    peerHandStates.keys.toList().forEach { id ->
                        if (id !in liveIds && id != peerId) peerHandStates.remove(id)
                    }
                    peerMuteStates.keys.toList().forEach { id ->
                        if (id !in liveIds && id != peerId) peerMuteStates.remove(id)
                    }
                    peerAudioEnabledStates.keys.toList().forEach { id ->
                        if (id !in liveIds && id != peerId) peerAudioEnabledStates.remove(id)
                    }
                    peerVideoEnabledStates.keys.toList().forEach { id ->
                        if (id !in liveIds && id != peerId) peerVideoEnabledStates.remove(id)
                    }
                }
            }
            override fun setRole(role: String) {
                mainHandler.post { localRole = role.ifBlank { ROLE_GUEST } }
            }
            override fun setLobbyWaiting(waiting: Boolean) {
                mainHandler.post {
                    lobbyWaiting = waiting
                    if (waiting) {
                        inCallUi = false
                        connected = false
                    }
                }
            }
            override fun updateLobbyPending(peers: List<LobbyPeerStatus>) {
                mainHandler.post {
                    pendingLobbyState.clear()
                    pendingLobbyState.addAll(peers)
                }
            }
            override fun setMuteAll(enabled: Boolean) {
                mainHandler.post {
                    val wasEnabled = muteAllEnabled
                    muteAllEnabled = enabled

                    if (enabled && !wasEnabled) {
                        peerMuteStates.clear()
                    }
                }
            }
            override fun setForcedMute(on: Boolean) {
                mainHandler.post {
                    selfMutedByModerator = on
                    if (on) micEnabled = false
                    val id = peerId.trim()
                    if (id.isNotBlank() && id != "—") {
                        peerMuteStates[id] = on
                    }
                }
            }
            override fun setPeerMuted(peerId: String, muted: Boolean) {
                mainHandler.post {
                    val id = peerId.trim()
                    if (id.isNotBlank()) {
                        peerMuteStates[id] = muted
                    }
                }
            }
            override fun setSelfHandRaised(on: Boolean) {
                mainHandler.post {
                    selfHandRaised = on
                    val id = peerId.trim()
                    if (id.isNotBlank() && id != "—") {
                        peerHandStates[id] = on
                    }
                }
            }
            override fun setPeerHandRaised(targetPeerId: String, raised: Boolean) {
                mainHandler.post {
                    val id = targetPeerId.trim()
                    if (id.isNotBlank()) {
                        peerHandStates[id] = raised
                        if (id == peerId) selfHandRaised = raised
                    }
                }
            }
        }
    }

    LaunchedEffect(peerId, selfHandRaised) {
        val id = peerId.trim()
        if (id.isNotBlank() && id != "—") {
            peerHandStates[id] = selfHandRaised
        }
    }

    LaunchedEffect(peerId, selfMutedByModerator) {
        val id = peerId.trim()
        if (id.isNotBlank() && id != "—") {
            peerMuteStates[id] = selfMutedByModerator
        }
    }

    LaunchedEffect(peerId, videoEnabled) {
        val id = peerId.trim()
        if (id.isNotBlank() && id != "—") {
            peerVideoEnabledStates[id] = videoEnabled
        }
    }

    LaunchedEffect(connected, reconnectMode) {
        if (connected || reconnectMode) {
            lobbyWaiting = false
            inCallUi = true
        }
        if (!connected && !reconnectMode && !lobbyWaiting) {
            inCallUi = false
            pinnedPeerId = null
            moderatorPanelOpen = false
            callViewMode = CallViewMode.FOCUS
            pipOffsetX = 0f
            pipOffsetY = 0f
            pipExpanded = false
            localRole = ROLE_GUEST
            selfMutedByModerator = false
            selfHandRaised = false
            muteAllEnabled = false
            pendingLobbyState.clear()
            peerMuteStates.clear()
            peerHandStates.clear()
            peerAudioEnabledStates.clear()
            peerVideoEnabledStates.clear()
        }
    }

    LaunchedEffect(videoEnabled) {
        if (!videoEnabled) {
            pipExpanded = false
        }
    }

    LaunchedEffect(uiStateBinder) { onRequestBind(uiStateBinder) }

    LaunchedEffect(Unit) {
        loadHomeServersFromStore()
    }

    LaunchedEffect(currentScreen) {
        if (currentScreen == RootScreen.HOME) {
            loadHomeServersFromStore()
        }
    }

    LaunchedEffect(initialConnectLink) {
        if (!initialConnectLink.isNullOrBlank()) {
            connectLink = initialConnectLink
            usernameInput = parseConnectLink(initialConnectLink)?.username.orEmpty()
            showConnectDialog = true
        }
    }

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = platformDensity.density,
            fontScale = platformDensity.fontScale * accessibilityFontScale.multiplier
        ),
        LocalAccessibilityFontScale provides accessibilityFontScale
    ) {
        if (showConnectDialog) {
            HomeConnectDialog(
                connectLink = connectLink,
                onConnectLinkChange = { connectLink = it },
                username = usernameInput,
                onUsernameChange = { usernameInput = it },
                parsed = parsedConnectLink,
                reconnectMode = reconnectMode,
                onDismiss = { showConnectDialog = false },
                onConnect = {
                    parsedConnectLink?.let { payload ->
                        showConnectDialog = false
                        onConnect(
                            payload.ip,
                            payload.room,
                            usernameInput.trim().ifBlank { payload.username.trim() },
                            payload.tlsPin,
                            payload.moderatorKey
                        )
                    }
                }
            )
        }

        if (showCreateMeetingDialog) {
            HomeCreateMeetingDialog(
                servers = homeServers,
                loading = homeServersRefreshing,
                onDismiss = { showCreateMeetingDialog = false },
                onAddServer = {
                    homeAddError = null
                    showHomeAddServerDialog = true
                },
                onOpenServerGuide = { showHomeServerGuideDialog = true },
                onRefresh = { refreshHomeServers() },
                onServerClick = { server ->
                    homeCreateRoomName = ""
                    homeRoomError = null
                    if (canCreateMeetingOnServer(server)) {
                        selectedHomeCreateServerId = server.id
                    } else {
                        homeInstallError = null
                        homeInstallLogs.clear()
                        selectedHomeInstallServerId = server.id
                    }
                }
            )
        }

        if (showHomeServerGuideDialog) {
            HomeServerGuideDialog(
                onDismiss = { showHomeServerGuideDialog = false }
            )
        }

        if (showHomeAddServerDialog) {
            HomeAddServerDialog(
                ip = homeAddIp,
                username = homeAddUser,
                password = homeAddPassword,
                error = homeAddError,
                loading = homeAddLoading,
                onIpChange = { homeAddIp = it },
                onUsernameChange = { homeAddUser = it },
                onPasswordChange = { homeAddPassword = it },
                onDismiss = {
                    showHomeAddServerDialog = false
                    clearHomeAddServerForm()
                },
                onAdd = { startHomeAddServer() }
            )
        }

        pendingHomeSshHostKeyTrust?.let { trust ->
            HomeSshHostKeyTrustDialog(
                trust = trust,
                loading = homeAddLoading,
                onCopy = { copyHomeValue("ssh_host_key_pin", trust.observedPin) },
                onConfirm = { finishHomeAddServerAfterTrust(trust) },
                onDismiss = {
                    pendingHomeSshHostKeyTrust = null
                    homeAddLoading = false
                }
            )
        }

        selectedHomeInstallServer?.let { server ->
            HomeInstallRelayDialog(
                server = server,
                logs = homeInstallLogs,
                error = homeInstallError,
                loading = homeInstallLoading,
                onDismiss = {
                    if (!homeInstallLoading) {
                        selectedHomeInstallServerId = null
                        homeInstallError = null
                        homeInstallLogs.clear()
                    }
                },
                onInstall = { installRelayFromHome(server) }
            )
        }

        selectedHomeCreateServer?.let { server ->
            HomeCreateRoomDialog(
                server = server,
                roomName = homeCreateRoomName,
                error = homeRoomError,
                loading = homeRoomLoading,
                onRoomNameChange = { homeCreateRoomName = it },
                onDismiss = {
                    if (!homeRoomLoading) {
                        selectedHomeCreateServerId = null
                        homeCreateRoomName = ""
                        homeRoomError = null
                    }
                },
                onCreate = { createHomeRoom(server) }
            )
        }

        if (selectedHomeMeetingServer != null && selectedHomeMeetingRoom != null) {
            val server = selectedHomeMeetingServer
            val room = selectedHomeMeetingRoom
            HomeMeetingDialog(
                server = server,
                room = room,
                loading = homeRoomLoading,
                onCopyGuest = {
                    val link = homeGuestLink(server, room)
                    if (link == null) showMissingLinkDataError() else copyHomeValue("guest_room_link", link)
                },
                onQrGuest = {
                    val link = homeGuestQrLink(server, room)
                    if (link == null) {
                        showMissingLinkDataError()
                    } else {
                        homeQrDialogTitle = "QR гостя"
                        homeQrDialogLink = link
                    }
                },
                onCopyModerator = {
                    val link = homeModeratorLink(server, room)
                    if (link == null) showMissingLinkDataError() else copyHomeValue("moderator_room_link", link)
                },
                onQrModerator = {
                    val link = homeModeratorLink(server, room)
                    if (link == null) {
                        showMissingLinkDataError()
                    } else {
                        homeQrDialogTitle = "QR модератора"
                        homeQrDialogLink = link
                    }
                },
                onCloseMeeting = { confirmHomeCloseMeeting = true },
                onDismiss = {
                    selectedHomeMeetingServerId = null
                    selectedHomeMeetingRoomName = null
                    confirmHomeCloseMeeting = false
                }
            )
        }

        if (confirmHomeCloseMeeting && selectedHomeMeetingServer != null && selectedHomeMeetingRoom != null) {
            val server = selectedHomeMeetingServer
            val room = selectedHomeMeetingRoom
            AppDialog(
                title = "Закрыть комнату?",
                onDismiss = { confirmHomeCloseMeeting = false },
                dismissEnabled = !homeRoomLoading,
                content = {
                    Text(
                        text = "Комната \"${room.name}\" будет закрыта на сервере ${serverDisplayAddress(server)}.",
                        color = appTextPrimaryColor(),
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    )
                },
                actions = {
                    ActionButton(
                        label = "Закрыть комнату",
                        onClick = {
                            confirmHomeCloseMeeting = false
                            closeHomeMeeting(server, room)
                        },
                        kind = ActionButtonKind.DANGER,
                        enabled = !homeRoomLoading,
                        loading = homeRoomLoading,
                        modifier = Modifier.weight(1f)
                    )
                }
            )
        }

        homeQrDialogLink?.let { link ->
            QrCodeDialog(
                link = link,
                title = homeQrDialogTitle,
                onDismiss = { homeQrDialogLink = null },
                onCopy = { copyHomeValue("room_qr_link", link) }
            )
        }

        Scaffold(
            containerColor = appBackgroundColor(),
            bottomBar = {
                if (currentScreen == RootScreen.HOME && connected) {
                    ControlBar(
                        speakerOn = speakerOn,
                        micEnabled = micEnabled,
                        micLocked = selfMutedByModerator,
                        audioRoute = audioRoute,
                        headsetAvailable = headsetAvailable,
                        handRaised = selfHandRaised,
                        outputOn = outputOn,
                        onSpeaker = { speakerOn = onToggleSpeaker() },
                        onAudioRouteSelected = { route ->
                            audioRoute = route
                            speakerOn = route == AudioOutputRoute.SPEAKER
                            onSetAudioRoute(route.toWireValue())
                        },
                        onMic = {
                            if (!selfMutedByModerator) {
                                val next = onToggleMic()
                                micEnabled = next

                                val id = peerId.trim()
                                if (id.isNotBlank() && id != "—") {
                                    peerAudioEnabledStates[id] = next
                                }

                                onSetMicAudioEnabled(next)
                            }
                        },
                        onHand = {
                            val next = !selfHandRaised
                            selfHandRaised = next
                            val id = peerId.trim()
                            if (id.isNotBlank() && id != "—") peerHandStates[id] = next
                            onSetHandRaised(next)
                        },
                        onOutput = { outputOn = onToggleOutput() },
                        onDisconnect = onDisconnect,
                        videoEnabled = videoEnabled,
                        onVideo = {
                            videoEnabled = onToggleVideo()
                            val id = peerId.trim()
                            if (id.isNotBlank() && id != "—") {
                                peerVideoEnabledStates[id] = videoEnabled
                            }
                        },
                        showModeratorButton = localRole == ROLE_MODERATOR,
                        moderatorPendingCount = pendingLobbyState.size,
                        moderatorMuteAllEnabled = muteAllEnabled,
                        showModeratorBubble = localRole == ROLE_MODERATOR &&
                                !moderatorPanelOpen &&
                                pendingLobbyIds.any { it !in dismissedModeratorBubblePeerIds },
                        onModeratorBubbleDismiss = {
                            dismissedModeratorBubblePeerIds = dismissedModeratorBubblePeerIds + pendingLobbyIds
                        },
                        onModerator = {
                            if (moderatorPanelOpen) {
                                moderatorPanelOpen = false
                            } else {
                                dismissedModeratorBubblePeerIds = dismissedModeratorBubblePeerIds + pendingLobbyIds
                                moderatorPanelOpen = true
                            }
                        }
                    )
                } else {
                    MinimalBottomNav(
                        menuActive = currentScreen != RootScreen.HOME,
                        onHome = { currentScreen = RootScreen.HOME },
                        onMenu = { currentScreen = RootScreen.MENU }
                    )
                }
            }
        ) { inner ->

            if (lobbyWaiting && currentScreen == RootScreen.HOME) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .background(DarkBackground)
                ) {
                    LobbyWaitingView(
                        onCancel = {
                            lobbyWaiting = false
                            onDisconnect()
                        }
                    )
                    ReconnectOverlay(
                        visible = reconnectMode,
                        onCancel = onCancelReconnect
                    )
                }
                return@Scaffold
            }

            if (inCallUi && currentScreen == RootScreen.HOME) {
                val effectivePeerMuteStates = peerMuteStates.toMutableMap().apply {
                    val id = peerId.trim()
                    if (id.isNotBlank() && id != "—") {
                        this[id] = selfMutedByModerator || this[id] == true
                    }
                }.toMap()

                val effectivePeerMicOffStates = peersState.associate { peer ->
                    val serverMuted = effectivePeerMuteStates[peer.peerId] == true
                    val userAudioEnabled = peerAudioEnabledStates[peer.peerId]
                    val userMicOff = userAudioEnabled == false
                    val noAudioTrack = !peer.audioAttached

                    peer.peerId to (serverMuted || userMicOff || noAudioTrack)
                }

                val effectivePeerVideoOffStates = peersState.associate { peer ->
                    val userVideoEnabled = peerVideoEnabledStates[peer.peerId]
                    val userVideoOff = userVideoEnabled == false
                    val noVideoTrack = !peer.videoAttached

                    peer.peerId to (userVideoOff || noVideoTrack)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .background(DarkBackground)
                ) {
                    CallInProgressView(
                        peers = peersState,
                        selfPeerId = peerId,
                        mode = callViewMode,
                        pinnedPeerId = pinnedPeerId,
                        peerHandStates = peerHandStates.toMap(),
                        peerMicOffStates = effectivePeerMicOffStates,
                        peerVideoOffStates = effectivePeerVideoOffStates,
                        onPin = { pinnedPeerId = it },
                        onToggleMode = {
                            callViewMode = if (callViewMode == CallViewMode.FOCUS) CallViewMode.GRID else CallViewMode.FOCUS
                        },
                        onSwitchCamera = onSwitchCamera,
                        pipOffsetX = pipOffsetX,
                        pipOffsetY = pipOffsetY,
                        onMovePip = { dx, dy ->
                            pipOffsetX += dx
                            pipOffsetY += dy
                        },
                        pipExpanded = pipExpanded,
                        onTogglePipExpanded = { pipExpanded = !pipExpanded }
                    )

                    ModeratorPanelOverlay(
                        visible = moderatorPanelOpen,
                        pendingPeers = pendingLobbyState,
                        peers = peersState,
                        selfPeerId = peerId,
                        peerMuteStates = effectivePeerMuteStates,
                        peerMicOffStates = effectivePeerMicOffStates,
                        peerHandStates = peerHandStates.toMap(),
                        muteAllEnabled = muteAllEnabled,
                        onApprove = { id ->
                            pendingLobbyState.removeAll { it.peerId == id }
                            onApproveLobbyPeer(id)
                        },
                        onReject = { id ->
                            pendingLobbyState.removeAll { it.peerId == id }
                            onRejectLobbyPeer(id)
                        },
                        onKick = onKickPeer,
                        onMute = { id ->
                            peerMuteStates[id] = true
                            onMutePeer(id)
                        },
                        onUnmute = { id ->
                            peerMuteStates[id] = false
                            onUnmutePeer(id)
                        },
                        onLowerHand = { id ->
                            peerHandStates[id] = false
                            onLowerPeerHand(id)
                        },
                        onSetMuteAll = { enabled ->
                            val wasEnabled = muteAllEnabled
                            muteAllEnabled = enabled

                            if (enabled && !wasEnabled) {
                                peerMuteStates.clear()
                            }

                            onSetMuteAll(enabled)
                        },
                        onClose = { moderatorPanelOpen = false }
                    )

                    ReconnectOverlay(
                        visible = reconnectMode,
                        onCancel = {
                            inCallUi = false
                            pinnedPeerId = null
                            moderatorPanelOpen = false
                            callViewMode = CallViewMode.FOCUS
                            pipOffsetX = 0f
                            pipOffsetY = 0f
                            pipExpanded = false
                            onCancelReconnect()
                        }
                    )
                }
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                when (currentScreen) {
                    RootScreen.HOME -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 28.dp)
                        ) {
                            item {
                                HomeMainScreen(
                                    servers = homeServers,
                                    reconnectMode = reconnectMode,
                                    roomsRefreshing = homeServersRefreshing,
                                    onOpenConnect = { showConnectDialog = true },
                                    onOpenCreateMeeting = {
                                        showCreateMeetingDialog = true
                                        refreshHomeServers()
                                    },
                                    onRefreshRooms = { refreshHomeServers() },
                                    onMeetingClick = { server, room ->
                                        selectedHomeMeetingServerId = server.id
                                        selectedHomeMeetingRoomName = room.name
                                    }
                                )
                            }
                        }
                    }

                    RootScreen.MENU -> {
                        MenuScreen(
                            onServers = {
                                serversRefreshNonce++
                                currentScreen = RootScreen.SERVERS
                            },
                            onSettings = { currentScreen = RootScreen.SETTINGS },
                            onAbout = { currentScreen = RootScreen.ABOUT }
                        )
                    }

                    RootScreen.SERVERS -> {
                        InstallTab(
                            expectedRelayVersion = expectedRelayVersion,
                            refreshNonce = serversRefreshNonce,
                            onInstall = onInstall,
                            onRemoveRelay = onRemoveRelay,
                            onObserveSshHostKeyPin = onObserveSshHostKeyPin,
                            onProbeServer = onProbeServer,
                            onSetRoomOpenState = onSetRoomOpenState,
                            onFetchOpenRooms = onFetchOpenRooms,
                            onFetchRelayVersion = onFetchRelayVersion
                        )
                    }

                    RootScreen.SETTINGS -> {
                        SettingsScreen(
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            telemetryEnabled = telemetryEnabled,
                            onTelemetryEnabledChange = onTelemetryEnabledChange,
                            accessibilityFontScale = accessibilityFontScale,
                            onAccessibilityFontScaleChange = { scale ->
                                accessibilityFontScale = scale
                                onAccessibilityFontScaleChange(scale)
                            }
                        )
                    }
                    RootScreen.ABOUT -> PlaceholderScreen(title = "О приложении", subtitle = "Версия: $appVersion")
                }

                ReconnectOverlay(
                    visible = reconnectMode,
                    onCancel = {
                        inCallUi = false
                        pinnedPeerId = null
                        moderatorPanelOpen = false
                        callViewMode = CallViewMode.FOCUS
                        pipOffsetX = 0f
                        pipOffsetY = 0f
                        pipExpanded = false
                        onCancelReconnect()
                    }
                )
            }
        }
    }
}

@Composable
internal fun MinimalBottomNav(
    menuActive: Boolean,
    onHome: () -> Unit,
    onMenu: () -> Unit
) {
    val navShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

    Surface(
        color = appSurfaceColor(),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(navShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, appBorderColor(0.95f), navShape)
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavIconButton(active = !menuActive, icon = Icons.Filled.Home, label = "Главная", onClick = onHome, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .size(width = 1.dp, height = 64.dp)
                    .background(appBorderColor(0.55f))
            )
            NavIconButton(active = menuActive, icon = Icons.Filled.Menu, label = "Меню", onClick = onMenu, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun NavIconButton(active: Boolean, icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = 78.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .semantics {
                role = Role.Button
                contentDescription = label
                stateDescription = if (active) "Выбрано" else "Не выбрано"
            }
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (active) AppAccent else Color.Transparent)
        )
        Spacer(Modifier.size(7.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) AppAccent else appTextMutedColor(),
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            color = if (active) AppAccent else appTextMutedColor(),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun TelemetryConsentDialog(
    onEnable: () -> Unit,
    onSkip: () -> Unit
) {
    AppDialog(
        title = "Помочь улучшить приложение?",
        onDismiss = onSkip,
        content = {
            Text(
                text = "Дайте Symposium возможность отправлять анонимную диагностику: длительность подключения, ошибки конференции, высокий ping и итог установки сервера.",
                color = appTextSecondaryColor(),
                fontSize = 14.sp,
                lineHeight = 19.sp
            )
            Text(
                text = "Персональные данные отправляться не будут",
                color = appTextSecondaryColor(),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        },
        actions = {
            ActionButton(
                label = "Включить",
                onClick = onEnable,
                kind = ActionButtonKind.PRIMARY,
                modifier = Modifier.weight(1f)
            )
        }
    )
}

@Composable
internal fun SettingsScreen(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    telemetryEnabled: Boolean,
    onTelemetryEnabledChange: (Boolean) -> Unit,
    accessibilityFontScale: AccessibilityFontScale,
    onAccessibilityFontScaleChange: (AccessibilityFontScale) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            SectionCard(
                title = "Тема",
                subtitle = "По умолчанию используется тема системы"
            ) {
                AppThemeMode.values().forEach { mode ->
                    SettingsOptionRow(
                        title = mode.label,
                        subtitle = mode.talkBackLabel,
                        selected = themeMode == mode,
                        role = Role.RadioButton,
                        onClick = { onThemeModeChange(mode) }
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "Размер шрифта",
                subtitle = ""
            ) {

                AccessibilityFontScale.values().forEach { scale ->
                    SettingsOptionRow(
                        title = scale.label,
                        subtitle = scale.talkBackLabel,
                        selected = accessibilityFontScale == scale,
                        role = Role.RadioButton,
                        onClick = { onAccessibilityFontScaleChange(scale) }
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "Приватность",
                subtitle = "Управление внешней диагностикой"
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InnerCardShape)
                        .background(appSurfaceElevatedColor())
                        .border(1.dp, appBorderColor(), InnerCardShape)
                        .semantics(mergeDescendants = true) {
                            role = Role.Switch
                            stateDescription = if (telemetryEnabled) "Включено" else "Выключено"
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "Анонимная диагностика",
                            color = appTextPrimaryColor(),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Отправляет только важные технические события: длительность подключения, высокий ping, ошибки WebRTC и итог установки сервера.",
                            color = appTextSecondaryColor(),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        Text(
                            text = "Персональные данные не отправляются",
                            color = appTextSecondaryColor(),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    androidx.compose.material3.Switch(
                        checked = telemetryEnabled,
                        onCheckedChange = onTelemetryEnabledChange,
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppAccent,
                            checkedBorderColor = AppAccent,
                            uncheckedThumbColor = appTextSecondaryColor(),
                            uncheckedTrackColor = appSurfaceElevatedColor(),
                            uncheckedBorderColor = appBorderColor()
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    role: Role,
    onClick: () -> Unit
) {
    val accent = accessibleAccentColor()
    val borderColor = if (selected) accent else appBorderColor()
    val backgroundColor = if (selected) accent.copy(alpha = 0.14f) else appSurfaceElevatedColor()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(backgroundColor)
            .border(1.dp, borderColor, InnerCardShape)
            .semantics(mergeDescendants = true) {
                this.role = role
                stateDescription = if (selected) "Выбрано" else "Не выбрано"
            }
            .clickable(role = role, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = appTextPrimaryColor(),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Text(
                text = subtitle,
                color = appTextSecondaryColor(),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        Spacer(Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) accent else appSurfaceElevatedColor())
                .border(1.dp, if (selected) accent else appBorderColor(), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) "✓" else "",
                color = AppOnAccent,
                fontSize = 20.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun MenuScreen(
    onServers: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        MenuNavButton(label = "Управление серверами", icon = Icons.Filled.GridView, onClick = onServers)
        MenuNavButton(label = "Настройки", icon = Icons.Filled.Settings, onClick = onSettings)
        MenuNavButton(label = "О приложении", icon = Icons.Filled.Info, onClick = onAbout)
    }
}

@Composable
internal fun MenuNavButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(role = Role.Button, onClick = onClick),
        color = appSurfaceColor(),
        shape = InnerCardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 72.dp)
                .border(1.dp, appBorderColor(0.95f), InnerCardShape)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppAccent.copy(alpha = 0.12f))
                    .border(1.dp, AppAccent.copy(alpha = 0.32f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = AppAccent, modifier = Modifier.size(21.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = label,
                    color = appTextPrimaryColor(),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun PlaceholderScreen(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = appTextPrimaryColor(),
            fontSize = 22.sp,
        )
        Text(subtitle, color = appTextSecondaryColor(), fontSize = 14.sp)
    }
}
