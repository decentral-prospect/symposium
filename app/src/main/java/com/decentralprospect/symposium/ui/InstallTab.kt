package com.decentralprospect.symposium

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal enum class InstallView { GRID, ADD, DETAIL }

private data class PendingSshHostKeyTrust(
    val ip: String,
    val username: String,
    val password: String,
    val observedPin: String
)

@Composable
fun InstallTab(
    expectedRelayVersion: String,
    refreshNonce: Int = 0,
    onInstall: suspend (ip: String, login: String, password: String, expectedSshHostKeyPin: String?, deploymentProfile: DeploymentProfile?, logger: suspend (String) -> Unit) -> RemoteInstaller.InstallResult,
    onRemoveRelay: suspend (ip: String, login: String, password: String, expectedSshHostKeyPin: String?, deploymentProfile: DeploymentProfile?, logger: suspend (String) -> Unit) -> RemoteInstaller.RelayRemovalResult,
    onObserveSshHostKeyPin: suspend (ip: String) -> String,
    onProbeServer: suspend (ip: String, login: String, password: String, expectedSshHostKeyPin: String?) -> RemoteInstaller.ProbeResult,
    onSetRoomOpenState: suspend (ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?, roomName: String, open: Boolean) -> RemoteInstaller.RoomAdminResult,
    onRotateModeratorKey: suspend (ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?, roomName: String) -> RemoteInstaller.RoomAdminResult,
    onFetchOpenRooms: suspend (ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?) -> RemoteInstaller.RoomAdminResult,
    onFetchRelayVersion: suspend (ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?) -> RemoteInstaller.RelayVersionResult
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val store = remember(context) { InstallServersStore(context) }
    val conferenceE2eeSecrets = remember(context) { ConferenceE2eeSecretStore(context) }
    val servers = remember { mutableStateListOf<InstallServer>() }
    val installScope = rememberCoroutineScope()
    val installLogListState = rememberLazyListState()

    var qrDialogLink by remember { mutableStateOf<String?>(null) }
    var qrDialogTitle by remember { mutableStateOf("QR-код") }

    var view by remember { mutableStateOf(InstallView.GRID) }
    var selectedServerId by remember { mutableStateOf<String?>(null) }

    var addIp by remember { mutableStateOf("") }
    var addUser by remember { mutableStateOf("") }
    var addPassword by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var addLoading by remember { mutableStateOf(false) }
    var addSuccessMessage by remember { mutableStateOf<String?>(null) }
    var pendingSshHostKeyTrust by remember { mutableStateOf<PendingSshHostKeyTrust?>(null) }

    var fieldEditorTitle by remember { mutableStateOf<String?>(null) }
    var fieldEditorValue by remember { mutableStateOf("") }
    var fieldEditorSaver by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val installLogs = remember { mutableStateListOf<String>() }
    var installing by remember { mutableStateOf(false) }
    var installSuccess by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var roomActionName by remember { mutableStateOf("") }
    var showAddRoomDialog by remember { mutableStateOf(false) }
    var roomDialogName by remember { mutableStateOf("") }
    var roomActionLoading by remember { mutableStateOf(false) }
    var pendingModeratorLinkRotationRoom by remember { mutableStateOf<String?>(null) }
    var pendingDeleteServerId by remember { mutableStateOf<String?>(null) }
    var removingRelay by remember { mutableStateOf(false) }
    var updatingRelay by remember { mutableStateOf(false) }
    var serversLoaded by remember { mutableStateOf(false) }
    var installLogsExpanded by remember(selectedServerId) { mutableStateOf(false) }

    var serverParamsExpanded by remember(selectedServerId) { mutableStateOf(false) }
    var roomsExpanded by remember(selectedServerId) { mutableStateOf(false) }
    var expandedRoomName by remember(selectedServerId) { mutableStateOf<String?>(null) }

    val selectedServer = servers.firstOrNull { it.id == selectedServerId }
    val pendingDeleteServer = servers.firstOrNull { it.id == pendingDeleteServerId }

    BackHandler(enabled = fieldEditorTitle != null || showAddRoomDialog || pendingModeratorLinkRotationRoom != null || pendingDeleteServer != null || pendingSshHostKeyTrust != null || qrDialogLink != null || view != InstallView.GRID) {
        when {
            qrDialogLink != null -> qrDialogLink = null
            pendingModeratorLinkRotationRoom != null -> {
                if (!roomActionLoading) pendingModeratorLinkRotationRoom = null
            }
            pendingSshHostKeyTrust != null -> {
                pendingSshHostKeyTrust = null
                addLoading = false
            }
            fieldEditorTitle != null -> {
                fieldEditorTitle = null
                fieldEditorSaver = null
                fieldEditorValue = ""
            }
            showAddRoomDialog -> {
                if (!roomActionLoading) {
                    showAddRoomDialog = false
                    roomDialogName = ""
                }
            }
            pendingDeleteServer != null -> pendingDeleteServerId = null
            view != InstallView.GRID -> {
                view = InstallView.GRID
                selectedServerId = null
                addIp = ""
                addUser = ""
                addPassword = ""
                addError = null
            }
        }
    }

    LaunchedEffect(Unit) {
        servers.clear()
        servers.addAll(store.load())
        serversLoaded = true
    }

    LaunchedEffect(installLogs.size) {
        val lastLogIndex = installLogs.lastIndex
        if (lastLogIndex >= 0) {
            installLogListState.animateScrollToItem(lastLogIndex)
        }
    }

    LaunchedEffect(addSuccessMessage) {
        if (addSuccessMessage != null) {
            delay(2200)
            addSuccessMessage = null
        }
    }

    fun persistServers() {
        store.save(servers)
    }

    fun copyToClipboard(label: String, value: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, tr("Скопировано"), Toast.LENGTH_SHORT).show()
    }

    fun updateServer(updated: InstallServer) {
        val index = servers.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            servers[index] = updated
            persistServers()
        }
    }

    fun updateServer(serverId: String, transform: (InstallServer) -> InstallServer) {
        val index = servers.indexOfFirst { it.id == serverId }
        if (index >= 0) {
            servers[index] = transform(servers[index])
            persistServers()
        }
    }

    fun relayVersionStateFor(actualVersion: String): RelayVersionState {
        return if (actualVersion.trim() == expectedRelayVersion.trim()) {
            RelayVersionState.CURRENT
        } else {
            RelayVersionState.OUTDATED
        }
    }

    fun markRelayVersion(serverId: String, result: Result<RemoteInstaller.RelayVersionResult>) {
        updateServer(serverId) { current ->
            result.fold(
                onSuccess = { info ->
                    val actualVersion = info.version.trim()
                    current.copy(
                        installed = true,
                        relayVersion = actualVersion,
                        relayVersionState = relayVersionStateFor(actualVersion)
                    )
                },
                onFailure = {
                    current.copy(
                        installed = false,
                        openRooms = emptyList(),
                        relayVersion = null,
                        relayVersionState = RelayVersionState.NOT_INSTALLED
                    )
                }
            )
        }
    }

    suspend fun syncServerState(server: InstallServer, reportErrors: Boolean = false) {
        updateServer(server.id) { current ->
            current.copy(relayVersionState = RelayVersionState.UNKNOWN)
        }

        val hasHttpsCredentials =
            !server.relayTlsPin.isNullOrBlank() && !server.adminToken.isNullOrBlank()
        val directVersionResult = if (hasHttpsCredentials) {
            runCatching {
                onFetchRelayVersion(
                    server.ip,
                    server.httpsPort,
                    server.relayTlsPin,
                    server.adminToken
                )
            }
        } else {
            Result.failure(IllegalStateException("Relay credentials are not synchronized yet"))
        }

        if (directVersionResult.isSuccess) {
            markRelayVersion(server.id, directVersionResult)
            runCatching {
                onFetchOpenRooms(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken)
            }.onSuccess { result ->
                updateServer(server.id) { current ->
                    current.copy(
                        openRooms = result.openRooms,
                        sshHostKeyPin = current.sshHostKeyPin ?: result.observedSshHostKeyPin,
                        relayTlsPin = result.relayInfo?.pin ?: current.relayTlsPin,
                        httpsPort = result.relayInfo?.httpsPort ?: current.httpsPort,
                        adminToken = result.relayInfo?.adminToken ?: current.adminToken
                    )
                }
                if (reportErrors) installError = null
            }.onFailure {
                if (reportErrors) installError = it.message ?: "Не удалось обновить открытые комнаты"
            }
            return
        }

        val probeResult = runCatching {
            onProbeServer(server.ip, server.username, server.password, server.sshHostKeyPin)
        }
        val probed = probeResult.getOrElse {
            updateServer(server.id) { current ->
                current.copy(
                    relayVersionState = if (current.installed) {
                        RelayVersionState.UNKNOWN
                    } else {
                        RelayVersionState.NOT_INSTALLED
                    }
                )
            }
            if (reportErrors) {
                installError = it.message ?: "Не удалось проверить сервер по SSH"
            }
            return
        }

        if (probed.installationInProgress) {
            updateServer(server.id) { current ->
                current.copy(
                    relayVersionState = RelayVersionState.UNKNOWN,
                    sshHostKeyPin = current.sshHostKeyPin ?: probed.observedSshHostKeyPin
                )
            }
            return
        }

        if (!probed.installed) {
            updateServer(server.id) { current ->
                if (current.installed) {
                    current.copy(
                        installed = false,
                        relayVersionState = RelayVersionState.UNKNOWN,
                        sshHostKeyPin = current.sshHostKeyPin ?: probed.observedSshHostKeyPin
                    )
                } else {
                    current.copy(
                        installed = false,
                        openRooms = emptyList(),
                        relayTlsPin = null,
                        httpsPort = null,
                        adminToken = null,
                        deploymentProfile = null,
                        relayVersion = null,
                        relayVersionState = RelayVersionState.NOT_INSTALLED,
                        sshHostKeyPin = current.sshHostKeyPin ?: probed.observedSshHostKeyPin
                    )
                }
            }
            if (reportErrors) installError = null
            return
        }

        val relayInfo = probed.relayInfo
        updateServer(server.id) { current ->
            current.copy(
                installed = true,
                openRooms = probed.openRooms,
                sshHostKeyPin = current.sshHostKeyPin ?: probed.observedSshHostKeyPin,
                relayTlsPin = relayInfo?.pin ?: current.relayTlsPin,
                httpsPort = relayInfo?.httpsPort ?: current.httpsPort,
                adminToken = relayInfo?.adminToken ?: current.adminToken,
                deploymentProfile = probed.deploymentProfile ?: current.deploymentProfile,
                relayVersionState = RelayVersionState.UNKNOWN
            )
        }

        val discoveredPin = relayInfo?.pin ?: server.relayTlsPin
        val discoveredPort = relayInfo?.httpsPort ?: server.httpsPort
        val discoveredToken = relayInfo?.adminToken ?: server.adminToken
        if (discoveredPin.isNullOrBlank() || discoveredToken.isNullOrBlank()) {
            if (reportErrors) installError = "SymposiumRelay найден, но его параметры не удалось синхронизировать"
            return
        }

        val discoveredVersionResult = runCatching {
            onFetchRelayVersion(server.ip, discoveredPort, discoveredPin, discoveredToken)
        }
        if (discoveredVersionResult.isSuccess) {
            markRelayVersion(server.id, discoveredVersionResult)
        }

        runCatching {
            onFetchOpenRooms(server.ip, discoveredPort, discoveredPin, discoveredToken)
        }.onSuccess { result ->
            updateServer(server.id) { current ->
                current.copy(
                    openRooms = result.openRooms,
                    relayTlsPin = result.relayInfo?.pin ?: current.relayTlsPin,
                    httpsPort = result.relayInfo?.httpsPort ?: current.httpsPort,
                    adminToken = result.relayInfo?.adminToken ?: current.adminToken
                )
            }
        }
        if (reportErrors) installError = null
    }

    fun refreshRelayVersion(server: InstallServer) {
        installScope.launch { syncServerState(server) }
    }

    LaunchedEffect(serversLoaded, refreshNonce) {
        if (!serversLoaded) return@LaunchedEffect

        servers.toList().forEach { server ->
            refreshRelayVersion(server)
        }
    }

    LaunchedEffect(serversLoaded) {
        if (!serversLoaded) return@LaunchedEffect
        while (true) {
            delay(20_000)
            val unsynchronized = servers.toList().filter {
                !it.installed ||
                    it.relayTlsPin.isNullOrBlank() ||
                    it.adminToken.isNullOrBlank() ||
                    it.relayVersionState == RelayVersionState.UNKNOWN
            }
            unsynchronized.forEach { server ->
                syncServerState(server)
            }
        }
    }

    fun deleteServer(server: InstallServer) {
        servers.removeAll { it.id == server.id }
        persistServers()
        selectedServerId = null
        installError = null
        installSuccess = false
        installLogs.clear()
        addSuccessMessage = null
        pendingDeleteServerId = null
        view = InstallView.GRID
    }

    LaunchedEffect(selectedServerId) {
        installLogs.clear()
        installLogsExpanded = false
        installError = null
        installSuccess = false
        installing = false
        updatingRelay = false
        roomActionLoading = false

        val server = selectedServer ?: return@LaunchedEffect
        syncServerState(server, reportErrors = true)
    }

    fun finishAddServerAfterHostKeyTrust(trust: PendingSshHostKeyTrust) {
        addLoading = true
        addError = null

        installScope.launch {
            val probe = runCatching {
                onProbeServer(
                    trust.ip,
                    trust.username,
                    trust.password,
                    trust.observedPin
                )
            }

            val probed = probe.getOrElse {
                addError = it.message ?: "Ошибка SSH-аутентификации: неверный логин или пароль."
                addLoading = false
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
                deploymentProfile = probed.deploymentProfile,
                openRooms = probed.openRooms,
                relayVersionState = if (probed.installed) RelayVersionState.UNKNOWN else RelayVersionState.NOT_INSTALLED
            )

            servers.add(newServer)
            persistServers()
            refreshRelayVersion(newServer)

            selectedServerId = null
            view = InstallView.GRID
            addSuccessMessage = "Сервер успешно добавлен"

            addIp = ""
            addUser = ""
            addPassword = ""
            addLoading = false
        }
    }

    fun addServer() {
        if (addLoading) return

        val ip = normalizeIpInput(addIp)
        val user = addUser.trim()

        if (ip.isBlank() || user.isBlank() || addPassword.isBlank()) {
            addError = "Заполните IP, пользователя и пароль"
            return
        }

        if (!isValidIpAddress(ip)) {
            addError = "Укажите корректный IP адрес"
            return
        }

        addLoading = true
        addError = null

        val password = addPassword
        installScope.launch {
            val observedPin = runCatching {
                onObserveSshHostKeyPin(ip)
            }.getOrElse {
                addError = it.message ?: "Не удалось получить SSH-ключ сервера"
                addLoading = false
                return@launch
            }

            pendingSshHostKeyTrust = PendingSshHostKeyTrust(
                ip = ip,
                username = user,
                password = password,
                observedPin = observedPin
            )
            addLoading = false
        }
    }

    fun startInstallation(server: InstallServer) {
        if (installing) return
        if (server.ip.isBlank() || server.username.isBlank() || server.password.isBlank()) {
            installError = "Введите IP, логин и пароль"
            installSuccess = false
            return
        }

        installScope.launch {
            installError = null
            installSuccess = false
            installing = true
            installLogs.clear()
            installLogsExpanded = true

            val result = runCatching {
                onInstall(
                    server.ip.trim(),
                    server.username.trim(),
                    server.password,
                    server.sshHostKeyPin,
                    server.deploymentProfile
                ) { line ->
                    withContext(Dispatchers.Main) { installLogs.add(line) }
                }
            }

            val r = result.getOrElse {
                installError = it.message ?: "Не удалось выполнить установку"
                installing = false
                installSuccess = false
                installLogsExpanded = installLogs.isNotEmpty()
                return@launch
            }

            installing = false
            installLogsExpanded = false
            installSuccess = r.success

            if (!r.success) {
                installError = installError ?: "Установка завершилась с ошибкой (код ${r.exitStatus})"
                return@launch
            }

            val sshPin = server.sshHostKeyPin ?: r.observedSshHostKeyPin
            val relayPin = r.relayInfo?.pin
            val httpsPort = r.relayInfo?.httpsPort
            val adminToken = r.relayInfo?.adminToken

            updateServer(server.id) { current ->
                current.copy(
                    installed = true,
                    sshHostKeyPin = sshPin,
                    relayTlsPin = relayPin ?: current.relayTlsPin,
                    httpsPort = httpsPort ?: current.httpsPort,
                    adminToken = adminToken ?: current.adminToken,
                    deploymentProfile = r.deploymentProfile ?: current.deploymentProfile,
                    openRooms = r.openRooms,
                    relayVersion = expectedRelayVersion,
                    relayVersionState = RelayVersionState.CURRENT
                )
            }
        }
    }

    fun removeRelay(server: InstallServer) {
        if (removingRelay) return
        installScope.launch {
            installError = null
            installSuccess = false
            removingRelay = true
            installLogs.clear()
            installLogsExpanded = true

            val result = runCatching {
                onRemoveRelay(
                    server.ip.trim(),
                    server.username.trim(),
                    server.password,
                    server.sshHostKeyPin,
                    server.deploymentProfile
                ) { line ->
                    withContext(Dispatchers.Main) { installLogs.add(line) }
                }
            }

            val r = result.getOrElse {
                installError = it.message ?: "Не удалось удалить SymposiumRelay"
                removingRelay = false
                installLogsExpanded = installLogs.isNotEmpty()
                return@launch
            }

            updateServer(server.id) { current ->
                current.copy(
                    installed = false,
                    openRooms = emptyList(),
                    relayTlsPin = null,
                    httpsPort = null,
                    adminToken = null,
                    deploymentProfile = null,
                    relayVersion = null,
                    relayVersionState = RelayVersionState.NOT_INSTALLED,
                    sshHostKeyPin = current.sshHostKeyPin ?: r.observedSshHostKeyPin
                )
            }

            removingRelay = false
            installLogsExpanded = false
        }
    }

    fun updateRelay(server: InstallServer) {
        if (installing || removingRelay || updatingRelay) return
        if (server.ip.isBlank() || server.username.isBlank() || server.password.isBlank()) {
            installError = "Введите IP, логин и пароль"
            installSuccess = false
            return
        }

        installScope.launch {
            installError = null
            installSuccess = false
            updatingRelay = true
            installLogs.clear()
            installLogsExpanded = true

            val removalResult = runCatching {
                onRemoveRelay(
                    server.ip.trim(),
                    server.username.trim(),
                    server.password,
                    server.sshHostKeyPin,
                    server.deploymentProfile
                ) { line ->
                    withContext(Dispatchers.Main) { installLogs.add(line) }
                }
            }

            val removal = removalResult.getOrElse {
                installError = it.message ?: "Не удалось удалить старую версию SymposiumRelay"
                updatingRelay = false
                installLogsExpanded = installLogs.isNotEmpty()
                return@launch
            }

            val sshPin = server.sshHostKeyPin ?: removal.observedSshHostKeyPin

            val installResult = runCatching {
                onInstall(
                    server.ip.trim(),
                    server.username.trim(),
                    server.password,
                    sshPin,
                    server.deploymentProfile
                ) { line ->
                    withContext(Dispatchers.Main) { installLogs.add(line) }
                }
            }

            val installed = installResult.getOrElse {
                installError = it.message ?: "Не удалось установить новую версию SymposiumRelay"
                updatingRelay = false
                installLogsExpanded = installLogs.isNotEmpty()
                return@launch
            }

            updatingRelay = false
            installLogsExpanded = false
            installSuccess = installed.success

            if (!installed.success) {
                installError = "Обновление завершилось с ошибкой (код ${installed.exitStatus})"
                return@launch
            }

            updateServer(server.id) { current ->
                current.copy(
                    installed = true,
                    sshHostKeyPin = sshPin ?: installed.observedSshHostKeyPin,
                    relayTlsPin = installed.relayInfo?.pin ?: current.relayTlsPin,
                    httpsPort = installed.relayInfo?.httpsPort ?: current.httpsPort,
                    adminToken = installed.relayInfo?.adminToken ?: current.adminToken,
                    deploymentProfile = installed.deploymentProfile ?: current.deploymentProfile,
                    openRooms = installed.openRooms,
                    relayVersion = expectedRelayVersion,
                    relayVersionState = RelayVersionState.CURRENT
                )
            }
        }
    }

    fun applyRoomAction(server: InstallServer, open: Boolean, onSuccess: (() -> Unit)? = null) {
        if (roomActionLoading) return
        if (!server.installed) return
        val name = roomActionName.trim()
        if (name.isBlank()) {
            installError = "Введите имя комнаты"
            return
        }
        roomActionLoading = true
        installScope.launch {
            val result = runCatching {
                onSetRoomOpenState(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken, name, open)
            }
            val r = result.getOrElse {
                installError = it.message ?: "Ошибка управления комнатой"
                roomActionLoading = false
                return@launch
            }
            updateServer(server.id) { current ->
                current.copy(
                    openRooms = r.openRooms,
                    sshHostKeyPin = current.sshHostKeyPin ?: r.observedSshHostKeyPin,
                    relayTlsPin = r.relayInfo?.pin ?: current.relayTlsPin,
                    httpsPort = r.relayInfo?.httpsPort ?: current.httpsPort,
                    adminToken = r.relayInfo?.adminToken ?: current.adminToken
                )
            }
            if (open) {
                conferenceE2eeSecrets.rotate(server.ip, server.httpsPort, name)
            } else {
                conferenceE2eeSecrets.remove(server.ip, server.httpsPort, name)
            }
            installError = null
            roomActionName = ""
            roomActionLoading = false
            onSuccess?.invoke()
        }
    }

    fun rotateModeratorLink(server: InstallServer, roomName: String) {
        if (roomActionLoading || !server.installed) return
        roomActionLoading = true
        roomActionName = roomName
        installScope.launch {
            val result = runCatching {
                onRotateModeratorKey(
                    server.ip,
                    server.httpsPort,
                    server.relayTlsPin,
                    server.adminToken,
                    roomName
                )
            }
            val rotated = result.getOrElse {
                installError = it.message ?: "Не удалось обновить ссылку модератора"
                roomActionLoading = false
                pendingModeratorLinkRotationRoom = null
                return@launch
            }
            updateServer(server.id) { current ->
                current.copy(
                    openRooms = rotated.openRooms,
                    relayTlsPin = rotated.relayInfo?.pin ?: current.relayTlsPin,
                    httpsPort = rotated.relayInfo?.httpsPort ?: current.httpsPort,
                    adminToken = rotated.relayInfo?.adminToken ?: current.adminToken
                )
            }
            installError = null
            roomActionName = ""
            roomActionLoading = false
            pendingModeratorLinkRotationRoom = null
            Toast.makeText(context, tr("Ссылка модератора обновлена"), Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshOpenRooms(server: InstallServer) {
        if (!server.installed) return
        installScope.launch {
            val result = runCatching {
                onFetchOpenRooms(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken)
            }
            val r = result.getOrElse {
                installError = it.message ?: "Не удалось обновить открытые комнаты"
                return@launch
            }
            updateServer(server.id) { current ->
                current.copy(
                    openRooms = r.openRooms,
                    sshHostKeyPin = current.sshHostKeyPin ?: r.observedSshHostKeyPin,
                    relayTlsPin = r.relayInfo?.pin ?: current.relayTlsPin,
                    httpsPort = r.relayInfo?.httpsPort ?: current.httpsPort,
                    adminToken = r.relayInfo?.adminToken ?: current.adminToken
                )
            }
            installError = null
        }
    }

    fun guestLink(server: InstallServer, room: OpenRoomInfo): String? {
        val pin = server.relayTlsPin?.trim()
        if (pin.isNullOrBlank()) return null
        val e2eeSecret = conferenceE2eeSecrets.getOrCreate(server.ip, server.httpsPort, room.name)
        return buildConnectHttpRedirectLink(server.ip, server.httpsPort, room.name, pin, e2eeSecret)
    }

    fun guestQrLink(server: InstallServer, room: OpenRoomInfo): String? {
        val pin = server.relayTlsPin?.trim()
        if (pin.isNullOrBlank()) return null
        val e2eeSecret = conferenceE2eeSecrets.getOrCreate(server.ip, server.httpsPort, room.name)
        return buildConnectDeepLink(server.ip, server.httpsPort, room.name, pin, e2eeSecret)
    }

    fun moderatorLink(server: InstallServer, room: OpenRoomInfo): String? {
        val pin = server.relayTlsPin?.trim()
        val modKey = room.moderatorKey.trim()
        if (pin.isNullOrBlank() || modKey.isBlank()) return null
        val e2eeSecret = conferenceE2eeSecrets.getOrCreate(server.ip, server.httpsPort, room.name)
        return buildConnectDeepLink(
            server.ip,
            server.httpsPort,
            room.name,
            pin,
            e2eeSecret,
            moderatorKey = modKey
        )
    }

    fun moderatorQrLink(server: InstallServer, room: OpenRoomInfo): String? {
        val pin = server.relayTlsPin?.trim()
        val modKey = room.moderatorKey.trim()
        if (pin.isNullOrBlank() || modKey.isBlank()) return null
        val e2eeSecret = conferenceE2eeSecrets.getOrCreate(server.ip, server.httpsPort, room.name)
        return buildConnectDeepLink(
            server.ip,
            server.httpsPort,
            room.name,
            pin,
            e2eeSecret,
            moderatorKey = modKey
        )
    }

    fun missingPinError() {
        installError = "Переустановите SymposiumRelay."
        Toast.makeText(context, tr("TLS pin отсутствует"), Toast.LENGTH_SHORT).show()
    }

    fun missingModKeyError() {
        installError = "Пересоздайте комнату"
        Toast.makeText(context, tr("Нет moderator_key"), Toast.LENGTH_SHORT).show()
    }

    pendingSshHostKeyTrust?.let { trust ->
        AppDialog(
            title = "Запомнить SSH-ключ",
            onDismiss = {
                pendingSshHostKeyTrust = null
                addLoading = false
            },
            content = {
                Text(
                    text = "Первое подключение к серверу ${trust.ip}. Пароль ещё не отправлялся. Если IP указан верно, приложение запомнит fingerprint и предупредит при его изменении.",
                    color = appTextPrimaryColor(),
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InnerCardShape)
                        .background(appSurfaceElevatedColor())
                        .border(1.dp, Color.Transparent, InnerCardShape)
                        .padding(12.dp)
                ) {
                    Text(
                        text = trust.observedPin,
                        color = appTextPrimaryColor(),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
                Text(
                    text = "Если сервер был переустановлен или вы не уверены в сети, проверьте fingerprint через панель VPS/консоль сервера перед продолжением.",
                    color = appTextSecondaryColor(),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            actions = {
                ActionButton(
                    label = "Копировать",
                    onClick = { copyToClipboard("ssh_host_key_pin", trust.observedPin) },
                    kind = ActionButtonKind.SECONDARY,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    label = "Продолжить",
                    onClick = {
                        pendingSshHostKeyTrust?.let { confirmed ->
                            pendingSshHostKeyTrust = null
                            finishAddServerAfterHostKeyTrust(confirmed)
                        }
                    },
                    kind = ActionButtonKind.PRIMARY,
                    modifier = Modifier.weight(1f)
                )
            }
        )
    }

    if (fieldEditorTitle != null) {
        FieldEditDialog(
            title = fieldEditorTitle ?: "",
            value = fieldEditorValue,
            onValueChange = { fieldEditorValue = it },
            onDismiss = {
                fieldEditorTitle = null
                fieldEditorSaver = null
                fieldEditorValue = ""
            },
            onSave = {
                fieldEditorSaver?.invoke(fieldEditorValue)
                fieldEditorTitle = null
                fieldEditorSaver = null
                fieldEditorValue = ""
            }
        )
    }

    if (showAddRoomDialog) {
        FieldEditDialog(
            title = "Добавить комнату",
            value = roomDialogName,
            placeholder = "Введите название",
            loading = roomActionLoading,
            saveLabel = "Добавить",
            onValueChange = { roomDialogName = it },
            onDismiss = {
                if (!roomActionLoading) {
                    showAddRoomDialog = false
                    roomDialogName = ""
                }
            },
            onSave = FieldEditDialogOnSave@{
                val server = selectedServer ?: return@FieldEditDialogOnSave
                roomActionName = roomDialogName
                applyRoomAction(server, open = true) {
                    showAddRoomDialog = false
                    roomDialogName = ""
                }
            }
        )
    }

    if (pendingModeratorLinkRotationRoom != null && selectedServer != null) {
        val roomName = pendingModeratorLinkRotationRoom.orEmpty()
        AppDialog(
            title = "Обновить ссылку модератора?",
            onDismiss = {
                if (!roomActionLoading) pendingModeratorLinkRotationRoom = null
            },
            dismissEnabled = !roomActionLoading,
            content = {
                Text(
                    text = "Старая ссылка комнаты \"$roomName\" перестанет работать. Активные модераторы будут отключены; гости останутся в комнате.",
                    color = appTextPrimaryColor(),
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            },
            actions = {
                ActionButton(
                    label = "Обновить ссылку",
                    onClick = { rotateModeratorLink(selectedServer, roomName) },
                    kind = ActionButtonKind.DANGER,
                    enabled = !roomActionLoading,
                    loading = roomActionLoading,
                    modifier = Modifier.weight(1f)
                )
            }
        )
    }

    pendingDeleteServer?.let { serverToDelete ->
        AppDialog(
            title = "Удалить сервер",
            onDismiss = { pendingDeleteServerId = null },
            content = {
                Text(
                    text = "Удалить сервер ${serverDisplayAddress(serverToDelete)} из приложения? SymposiumRelay на сервере не удаляется.",
                    color = appTextPrimaryColor(),
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            },
            actions = {
                ActionButton(
                    label = "Удалить",
                    onClick = { deleteServer(serverToDelete) },
                    kind = ActionButtonKind.DANGER,
                    modifier = Modifier.weight(1f)
                )
            }
        )
    }

    if (qrDialogLink != null) {
        val link = qrDialogLink ?: ""

        QrCodeDialog(
            link = link,
            title = qrDialogTitle,
            onDismiss = { qrDialogLink = null },
            onCopy = { copyToClipboard("room_deep_link", link) }
        )
    }

    when (view) {
        InstallView.GRID -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Spacer(Modifier.height(6.dp))

                        if (addSuccessMessage != null) {
                            SuccessMessage(addSuccessMessage ?: "")
                        }
                            ActionButton(
                                label = "Добавить сервер",
                                onClick = { view = InstallView.ADD },
                                modifier = Modifier.fillMaxWidth(),
                                kind = ActionButtonKind.PRIMARY
                            )
                    }
                }

                if (servers.isEmpty()) {
                    item {
                        SectionCard(
                            title = "Пока пусто",
                            subtitle = "Добавьте сервер, чтобы начать установку"
                        ) {}
                    }
                } else {
                    items(servers, key = { it.id }) { server ->
                        ServerListCard(
                            server = server,
                            onClick = {
                                selectedServerId = server.id
                                view = InstallView.DETAIL
                            },
                            onDelete = { pendingDeleteServerId = server.id }
                        )
                    }
                }
            }
        }

        InstallView.ADD -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    SectionCard(
                        title = "Новый сервер"
                    ) {
                        OutlinedTextField(
                            value = addIp,
                            onValueChange = { addIp = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("IP адрес") },
                            colors = textFieldColors()
                        )

                        OutlinedTextField(
                            value = addUser,
                            onValueChange = { addUser = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Пользователь") },
                            colors = textFieldColors()
                        )

                        OutlinedTextField(
                            value = addPassword,
                            onValueChange = { addPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("Пароль") },
                            colors = textFieldColors()
                        )

                        if (addError != null) {
                            ErrorMessage(addError ?: "")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ActionButton(
                                label = "Назад",
                                onClick = {
                                    view = InstallView.GRID
                                    addIp = ""
                                    addUser = ""
                                    addPassword = ""
                                    addError = null
                                },
                                modifier = Modifier.weight(1f),
                                kind = ActionButtonKind.SECONDARY
                            )
                            ActionButton(
                                label = "Добавить",
                                onClick = { addServer() },
                                modifier = Modifier.weight(1f),
                                enabled = !addLoading,
                                loading = addLoading
                            )
                        }
                    }
                }
            }
        }

        InstallView.DETAIL -> {
            val server = selectedServer
            if (server == null) {
                view = InstallView.GRID
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        ActionButton(
                            label = "Назад к серверам",
                            onClick = {
                                view = InstallView.GRID
                                selectedServerId = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            kind = ActionButtonKind.SECONDARY
                        )
                    }

                    item {
                        SectionCard(
                            title = serverDisplayAddress(server),
                            trailing = {
                                InfoBadge(
                                    label = serverStateLabel(server),
                                    color = serverStateColor(server)
                                )
                            },
                            backgroundColor = appSurfaceElevatedColor()
                        ) {
                            Text(
                                text = "Открытых комнат: ${openRoomsLabel(server.openRooms.size)}",
                                color = appTextSecondaryColor(),
                                fontSize = 14.sp
                            )

                            if (!server.installed) {
                                ActionButton(
                                    label = "Установить SymposiumRelay",
                                    onClick = { startInstallation(server) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !installing && !removingRelay && !updatingRelay,
                                    loading = installing
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ActionButton(
                                        label = "Удалить SymposiumRelay",
                                        onClick = { removeRelay(server) },
                                        modifier = Modifier.weight(1f),
                                        kind = ActionButtonKind.DANGER,
                                        enabled = !removingRelay && !installing && !updatingRelay,
                                        loading = removingRelay
                                    )

                                    if (server.relayVersionState == RelayVersionState.OUTDATED) {
                                        ActionButton(
                                            label = "Обновить",
                                            onClick = { updateRelay(server) },
                                            modifier = Modifier.weight(1f),
                                            enabled = !removingRelay && !installing && !updatingRelay,
                                            loading = updatingRelay,
                                            icon = Icons.Filled.Refresh
                                        )
                                    }
                                }
                            }

                            if (installError != null) {
                                ErrorMessage(installError ?: "")
                            }

                            if (installSuccess) {
                                SuccessMessage("Операция завершена успешно")
                            }
                        }
                    }

                    item {
                        CollapsibleSectionCard(
                            title = "Параметры сервера",
                            expanded = serverParamsExpanded,
                            onToggle = { serverParamsExpanded = !serverParamsExpanded }
                        ) {
                            EditableFieldRow(
                                label = "IP адрес",
                                value = server.ip,
                                onEdit = {
                                    fieldEditorTitle = "IP адрес"
                                    fieldEditorValue = server.ip
                                    fieldEditorSaver = { newValue ->
                                        val value = normalizeIpInput(newValue)
                                        if (isValidIpAddress(value)) {
                                            updateServer(server.copy(ip = value))
                                            installError = null
                                        } else {
                                            installError = "Укажите корректный IP адрес без протокола"
                                        }
                                    }
                                }
                            )

                            EditableFieldRow(
                                label = "Логин",
                                value = server.username,
                                onEdit = {
                                    fieldEditorTitle = "Логин"
                                    fieldEditorValue = server.username
                                    fieldEditorSaver = { newValue ->
                                        val value = newValue.trim()
                                        if (value.isNotEmpty()) {
                                            updateServer(server.copy(username = value))
                                        }
                                    }
                                }
                            )

                            EditableFieldRow(
                                label = "Пароль",
                                value = "•".repeat(server.password.length.coerceAtLeast(1)),
                                onEdit = {
                                    fieldEditorTitle = "Пароль"
                                    fieldEditorValue = server.password
                                    fieldEditorSaver = { newValue ->
                                        if (newValue.isNotEmpty()) {
                                            updateServer(server.copy(password = newValue))
                                        }
                                    }
                                }
                            )

                            if (server.adminToken.isNullOrBlank() && server.installed) {
                                ErrorMessage("adminToken отсутствует. Добавьте сервер заново или переустановите SymposiumRelay.")
                            }
                        }
                    }

                    if (server.installed) {
                        item {
                            CollapsibleSectionCard(
                                title = "Комнаты",
                                subtitle = "Открытых комнат: ${openRoomsLabel(server.openRooms.size)}",
                                expanded = roomsExpanded,
                                onToggle = { roomsExpanded = !roomsExpanded }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Открытые комнаты",
                                        color = appTextPrimaryColor(),
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TopIconAction(
                                            icon = Icons.Filled.Refresh,
                                            onClick = { refreshOpenRooms(server) },
                                            enabled = !roomActionLoading,
                                            contentDescription = "Обновить список комнат"
                                        )
                                        TopIconAction(
                                            icon = Icons.Filled.Add,
                                            onClick = {
                                                roomDialogName = ""
                                                showAddRoomDialog = true
                                            },
                                            enabled = !roomActionLoading,
                                            contentDescription = "Добавить комнату"
                                        )
                                    }
                                }

                                if (server.openRooms.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(InnerCardShape)
                                            .background(appSurfaceColor().copy(alpha = 0.72f))
                                            .border(1.dp, appGrayControlBorderColor(), InnerCardShape)
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = "Открытых комнат нет.",
                                            color = appTextSecondaryColor(),
                                            fontSize = 13.sp
                                        )
                                    }
                                } else {
                                    server.openRooms.forEach { openRoom ->
                                        RoomItemCard(
                                            room = openRoom,
                                            expanded = expandedRoomName == openRoom.name,
                                            loading = roomActionLoading && roomActionName == openRoom.name,
                                            onToggle = {
                                                expandedRoomName =
                                                    if (expandedRoomName == openRoom.name) null else openRoom.name
                                            },
                                            onCopyGuest = {
                                                val link = guestLink(server, openRoom)
                                                if (link == null) missingPinError() else copyToClipboard("guest_https_room_link", link)
                                            },
                                            onQrGuest = {
                                                val link = guestQrLink(server, openRoom)
                                                if (link == null) {
                                                    missingPinError()
                                                } else {
                                                    qrDialogTitle = "QR гостя"
                                                    qrDialogLink = link
                                                }
                                            },
                                            onCopyModerator = {
                                                val link = moderatorLink(server, openRoom)
                                                when {
                                                    server.relayTlsPin.isNullOrBlank() -> missingPinError()
                                                    openRoom.moderatorKey.isBlank() -> missingModKeyError()
                                                    else -> copyToClipboard("moderator_https_room_link", link ?: "")
                                                }
                                            },
                                            onQrModerator = {
                                                val link = moderatorQrLink(server, openRoom)
                                                when {
                                                    server.relayTlsPin.isNullOrBlank() -> missingPinError()
                                                    openRoom.moderatorKey.isBlank() -> missingModKeyError()
                                                    else -> {
                                                        qrDialogTitle = "QR модератора"
                                                        qrDialogLink = link
                                                    }
                                                }
                                            },
                                            onRotateModerator = {
                                                pendingModeratorLinkRotationRoom = openRoom.name
                                            },
                                            onClose = {
                                                roomActionName = openRoom.name
                                                applyRoomAction(server, open = false)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (installLogs.isNotEmpty()) {
                        item {
                            CollapsibleSectionCard(
                                title = when {
                                    installing -> "Установка SymposiumRelay"
                                    removingRelay -> "Удаление SymposiumRelay"
                                    updatingRelay -> "Обновление SymposiumRelay"
                                    else -> "Логи последней операции"
                                },
                                expanded = installLogsExpanded,
                                onToggle = { installLogsExpanded = !installLogsExpanded }
                            ) {
                                LazyColumn(
                                    state = installLogListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 220.dp, max = 320.dp)
                                        .clip(InnerCardShape)
                                        .background(appSurfaceElevatedColor())
                                        .border(1.dp, Color.Transparent, InnerCardShape)
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(installLogs) { line ->
                                        Text(line, color = appTextPrimaryColor(), fontSize = 13.sp, lineHeight = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ErrorMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(AppError.copy(alpha = 0.12f))
            .border(1.dp, AppError.copy(alpha = 0.55f), InnerCardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text, color = appTextPrimaryColor(), fontSize = 13.sp)
    }
}

@Composable
internal fun SuccessMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(AppSuccess.copy(alpha = 0.14f))
            .border(1.dp, AppSuccess, InnerCardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text, color = AppSuccess, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

@Composable
internal fun ServerListCard(
    server: InstallServer,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = appSurfaceElevatedColor(),
        shape = CardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .border(1.dp, appGrayControlBorderColor(), CardShape)
                .clickable(onClick = onClick)
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = serverDisplayAddress(server),
                            color = appTextPrimaryColor(),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Открытых комнат: ${openRoomsLabel(server.openRooms.size)}",
                            color = appTextSecondaryColor(),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(10.dp))

                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = tr("Удалить сервер"),
                            tint = AppError
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))

                    InfoBadge(
                        label = serverStateLabel(server),
                        color = serverStateColor(server),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }
        }
    }
}

@Composable
internal fun RoomsSectionCard(
    roomNames: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    actionsEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        color = appRoomSurfaceColor(),
        shape = CardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, Color.Transparent, CardShape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InnerCardShape)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(AppButtonShape)
                        .background(AppAccent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.GridView,
                        contentDescription = null,
                        tint = AppAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Комнаты",
                        color = appTextPrimaryColor(),
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Открытых комнат: ${openRoomsLabel(roomNames.size)}",
                        color = appTextSecondaryColor(),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = tr(if (expanded) "Свернуть комнаты" else "Развернуть комнаты"),
                    tint = appTextSecondaryColor(),
                    modifier = Modifier.size(24.dp)
                )
            }

            AnimatedVisibility(visible = !expanded) {
                Text(
                    text = when {
                        roomNames.isEmpty() -> "Открытых комнат нет."
                        roomNames.size <= 2 -> roomNames.joinToString(" · ")
                        else -> roomNames.take(2).joinToString(" · ") + " · +${roomNames.size - 2}"
                    },
                    color = appTextSecondaryColor(),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ActionButton(
                            label = "Обновить",
                            onClick = onRefresh,
                            modifier = Modifier.weight(1f),
                            kind = ActionButtonKind.SECONDARY,
                            enabled = actionsEnabled,
                            icon = Icons.Filled.Refresh
                        )
                        ActionButton(
                            label = "Добавить комнату",
                            onClick = onAdd,
                            modifier = Modifier.weight(1f),
                            kind = ActionButtonKind.SECONDARY,
                            enabled = actionsEnabled,
                            icon = Icons.Filled.Add
                        )
                    }

                    content()
                }
            }
        }
    }
}

@Composable
internal fun RoomItemCard(
    room: OpenRoomInfo,
    expanded: Boolean,
    loading: Boolean,
    onToggle: () -> Unit,
    onCopyGuest: () -> Unit,
    onQrGuest: () -> Unit,
    onCopyModerator: () -> Unit,
    onQrModerator: () -> Unit,
    onRotateModerator: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = appRoomSurfaceColor(),
        shape = InnerCardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, appGrayControlBorderColor(), InnerCardShape)
                .animateContentSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InnerCardShape)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(AppButtonShape)
                        .background(AppAccent.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MeetingRoom,
                        contentDescription = null,
                        tint = AppAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = room.name,
                        color = appTextPrimaryColor(),
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (room.moderatorKey.isBlank()) "Ссылка модератора недоступна" else "Ссылки готовы",
                        color = if (room.moderatorKey.isBlank()) AppError else appTextSecondaryColor(),
                        fontSize = 12.sp
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = tr(if (expanded) "Свернуть комнату" else "Развернуть комнату"),
                    tint = appTextSecondaryColor(),
                    modifier = Modifier.size(22.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoomLinkActionsRow(
                        title = "Гость",
                        subtitle = "Ссылка для подключения без прав модератора",
                        badgeColor = appTextSecondaryColor(),
                        onCopy = onCopyGuest,
                        onQr = onQrGuest,
                        enabled = !loading
                    )

                    RoomLinkActionsRow(
                        title = "Модератор",
                        subtitle = "Ссылка с правами управления комнатой",
                        badgeColor = AppAccent,
                        onCopy = onCopyModerator,
                        onQr = onQrModerator,
                        enabled = !loading
                    )

                    ActionButton(
                        label = "Обновить ссылку модератора",
                        onClick = onRotateModerator,
                        modifier = Modifier.fillMaxWidth(),
                        kind = ActionButtonKind.SECONDARY,
                        enabled = !loading
                    )

                    ActionButton(
                        label = "Закрыть комнату",
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                        kind = ActionButtonKind.SECONDARY,
                        textColorOverride = AppError,
                        enabled = !loading,
                        loading = loading
                    )
                }
            }
        }
    }
}

@Composable
internal fun RoomLinkActionsRow(
    title: String,
    subtitle: String,
    badgeColor: Color,
    onCopy: () -> Unit,
    onQr: () -> Unit,
    enabled: Boolean,
    comfortable: Boolean = false
) {
    if (comfortable) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(appRoomSurfaceColor())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(badgeColor)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        color = appTextPrimaryColor(),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            color = appTextSecondaryColor(),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActionButton(
                    label = "Копировать",
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    kind = ActionButtonKind.SECONDARY,
                    icon = Icons.Filled.ContentCopy,
                    enabled = enabled
                )
                ActionButton(
                    label = "QR-код",
                    onClick = onQr,
                    modifier = Modifier.weight(1f),
                    kind = ActionButtonKind.SECONDARY,
                    icon = Icons.Filled.QrCode2,
                    enabled = enabled
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(appSurfaceElevatedColor().copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(badgeColor)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = title,
                color = appTextPrimaryColor(),
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = appTextSecondaryColor(),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        RoomLinkIconButton(
            icon = Icons.Filled.ContentCopy,
            contentDescription = "Копировать",
            onClick = onCopy,
            enabled = enabled
        )
        RoomLinkIconButton(
            icon = Icons.Filled.QrCode2,
            contentDescription = "QR-код",
            onClick = onQr,
            enabled = enabled
        )
    }
}

@Composable
private fun RoomLinkIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(38.dp)
            .clip(AppButtonShape)
            .background(appSurfaceColor())
            .border(1.dp, appGrayControlBorderColor(), AppButtonShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tr(contentDescription),
            tint = if (enabled) appTextPrimaryColor() else appTextMutedColor(),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun EditableFieldRow(
    label: String,
    value: String,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(appSurfaceElevatedColor())
            .border(1.dp, Color.Transparent, InnerCardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = appTextSecondaryColor(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                text = value.ifBlank { "—" },
                color = appTextPrimaryColor(),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEdit) {
            Icon(imageVector = Icons.Filled.Edit, contentDescription = tr("Редактировать"), tint = AppAccent)
        }
    }
}

@Composable
internal fun FieldEditDialog(
    title: String,
    value: String,
    placeholder: String = "",
    loading: Boolean = false,
    saveLabel: String = "Сохранить",
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AppDialog(
        title = title,
        onDismiss = onDismiss,
        dismissEnabled = !loading,
        content = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
                colors = textFieldColors()
            )
        },
        actions = {
            ActionButton(
                label = saveLabel,
                onClick = onSave,
                enabled = !loading,
                loading = loading,
                modifier = Modifier.weight(1f)
            )
        }
    )
}
