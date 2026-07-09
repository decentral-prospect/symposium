package com.decentralprospect.symposium

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
    onInstall: suspend (ip: String, login: String, password: String, expectedSshHostKeyPin: String?, logger: suspend (String) -> Unit) -> RemoteInstaller.InstallResult,
    onRemoveRelay: suspend (ip: String, login: String, password: String, expectedSshHostKeyPin: String?, logger: suspend (String) -> Unit) -> RemoteInstaller.RelayRemovalResult,
    onObserveSshHostKeyPin: suspend (ip: String) -> String,
    onProbeServer: suspend (ip: String, login: String, password: String, expectedSshHostKeyPin: String?) -> RemoteInstaller.ProbeResult,
    onSetRoomOpenState: suspend (ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?, roomName: String, open: Boolean) -> RemoteInstaller.RoomAdminResult,
    onFetchOpenRooms: suspend (ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?) -> RemoteInstaller.RoomAdminResult,
    onFetchRelayVersion: suspend (ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?) -> RemoteInstaller.RelayVersionResult
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val store = remember(context) { InstallServersStore(context) }
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
    var pendingDeleteServerId by remember { mutableStateOf<String?>(null) }
    var removingRelay by remember { mutableStateOf(false) }
    var updatingRelay by remember { mutableStateOf(false) }
    var serversLoaded by remember { mutableStateOf(false) }
    var installLogsExpanded by remember(selectedServerId) { mutableStateOf(false) }

    var serverParamsExpanded by remember(selectedServerId) { mutableStateOf(false) }
    var roomsExpanded by remember(selectedServerId) { mutableStateOf(false) }

    val selectedServer = servers.firstOrNull { it.id == selectedServerId }
    val pendingDeleteServer = servers.firstOrNull { it.id == pendingDeleteServerId }

    BackHandler(enabled = fieldEditorTitle != null || showAddRoomDialog || pendingDeleteServer != null || pendingSshHostKeyTrust != null || qrDialogLink != null || view != InstallView.GRID) {
        when {
            qrDialogLink != null -> qrDialogLink = null
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
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
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

    fun refreshRelayVersion(server: InstallServer) {
        updateServer(server.id) { current ->
            current.copy(relayVersionState = RelayVersionState.UNKNOWN)
        }

        installScope.launch {
            markRelayVersion(
                server.id,
                runCatching {
                    onFetchRelayVersion(
                        server.ip,
                        server.httpsPort,
                        server.relayTlsPin,
                        server.adminToken
                    )
                }
            )
        }
    }

    LaunchedEffect(serversLoaded, refreshNonce) {
        if (!serversLoaded) return@LaunchedEffect

        servers.toList().forEach { server ->
            refreshRelayVersion(server)
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

        val versionResult = runCatching {
            onFetchRelayVersion(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken)
        }
        markRelayVersion(server.id, versionResult)
        if (versionResult.isFailure) return@LaunchedEffect

        val result = runCatching {
            onFetchOpenRooms(server.ip, server.httpsPort, server.relayTlsPin, server.adminToken)
        }
        result.onSuccess { r ->
            updateServer(server.id) { current ->
                current.copy(
                    openRooms = r.openRooms,
                    sshHostKeyPin = current.sshHostKeyPin ?: r.observedSshHostKeyPin,
                    relayTlsPin = r.relayInfo?.pin ?: current.relayTlsPin,
                    httpsPort = r.relayInfo?.httpsPort ?: current.httpsPort,
                    adminToken = r.relayInfo?.adminToken ?: current.adminToken
                )
            }
        }.onFailure {
            installError = it.message ?: "Не удалось обновить открытые комнаты"
        }
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
                    server.sshHostKeyPin
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
                    openRooms = current.openRooms,
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
                    server.sshHostKeyPin
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
                    server.sshHostKeyPin
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
                    sshPin
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
                    openRooms = current.openRooms,
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
            installError = null
            roomActionName = ""
            roomActionLoading = false
            onSuccess?.invoke()
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
        return buildConnectHttpRedirectLink(server.ip, server.httpsPort, room.name, pin)
    }

    fun guestQrLink(server: InstallServer, room: OpenRoomInfo): String? {
        val pin = server.relayTlsPin?.trim()
        if (pin.isNullOrBlank()) return null
        return buildConnectDeepLink(server.ip, room.name, pin)
    }

    fun moderatorLink(server: InstallServer, room: OpenRoomInfo): String? {
        val pin = server.relayTlsPin?.trim()
        val modKey = room.moderatorKey.trim()
        if (pin.isNullOrBlank() || modKey.isBlank()) return null
        return buildConnectDeepLink(server.ip, room.name, pin, moderatorKey = modKey)
    }

    fun moderatorQrLink(server: InstallServer, room: OpenRoomInfo): String? {
        val pin = server.relayTlsPin?.trim()
        val modKey = room.moderatorKey.trim()
        if (pin.isNullOrBlank() || modKey.isBlank()) return null
        return buildConnectDeepLink(server.ip, room.name, pin, moderatorKey = modKey)
    }

    fun missingPinError() {
        installError = "Переустановите SymposiumRelay."
        Toast.makeText(context, "TLS pin отсутствует", Toast.LENGTH_SHORT).show()
    }

    fun missingModKeyError() {
        installError = "Пересоздайте комнату"
        Toast.makeText(context, "Нет moderator_key", Toast.LENGTH_SHORT).show()
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
                        .border(1.dp, appBorderColor(), InnerCardShape)
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
                            }
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
                                expanded = roomsExpanded,
                                onToggle = { roomsExpanded = !roomsExpanded }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = buildAnnotatedString {
                                            append("Открытых комнат: ")
                                            withStyle(
                                                SpanStyle(
                                                    color = AppAccent,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            ) {
                                                append(openRoomsLabel(server.openRooms.size))
                                            }
                                        },
                                        color = appTextSecondaryColor(),
                                        fontSize = 14.sp
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TopIconAction(
                                            icon = Icons.Filled.Refresh,
                                            onClick = { refreshOpenRooms(server) },
                                            enabled = !roomActionLoading,
                                            accent = AppAccent,
                                            contentDescription = "Обновить список комнат"
                                        )
                                        TopIconAction(
                                            icon = Icons.Filled.Add,
                                            onClick = {
                                                roomDialogName = ""
                                                showAddRoomDialog = true
                                            },
                                            enabled = !roomActionLoading,
                                            accent = AppAccent,
                                            contentDescription = "Добавить комнату"
                                        )
                                    }
                                }

                                if (server.openRooms.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(InnerCardShape)
                                            .background(appSurfaceElevatedColor())
                                            .border(1.dp, appBorderColor(), InnerCardShape)
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
                                            loading = roomActionLoading && roomActionName == openRoom.name,
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
                                        .border(1.dp, appBorderColor(), InnerCardShape)
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
        color = appSurfaceColor(),
        shape = CardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .border(1.dp, appBorderColor(), CardShape)
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
                            contentDescription = "Удалить сервер",
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
internal fun RoomItemCard(
    room: OpenRoomInfo,
    loading: Boolean,
    onCopyGuest: () -> Unit,
    onQrGuest: () -> Unit,
    onCopyModerator: () -> Unit,
    onQrModerator: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = appSurfaceElevatedColor(),
        shape = InnerCardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, appBorderColor(), InnerCardShape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = room.name,
                        color = appTextPrimaryColor(),
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (room.moderatorKey.isBlank()) "moderator_key не получен" else "",
                        color = if (room.moderatorKey.isBlank()) AppError else appTextSecondaryColor(),
                        fontSize = 12.sp
                    )
                }

                ActionButton(
                    label = "Закрыть",
                    onClick = onClose,
                    modifier = Modifier.width(106.dp),
                    kind = ActionButtonKind.GHOST,
                    enabled = !loading,
                    loading = loading
                )
            }

            RoomLinkActionsRow(
                title = "Гость",
                subtitle = "",
                badgeColor = appTextSecondaryColor(),
                onCopy = onCopyGuest,
                onQr = onQrGuest,
                enabled = !loading
            )

            RoomLinkActionsRow(
                title = "Модератор",
                subtitle = "",
                badgeColor = AppAccent,
                onCopy = onCopyModerator,
                onQr = onQrModerator,
                enabled = !loading
            )
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
    enabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(appSurfaceColor().copy(alpha = 0.72f))
            .border(1.dp, appBorderColor(0.75f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoBadge(label = title, color = badgeColor)
            Text(subtitle, color = appTextSecondaryColor(), fontSize = 12.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionButton(
                label = "Ссылка",
                onClick = onCopy,
                modifier = Modifier.weight(1f),
                kind = ActionButtonKind.SECONDARY,
                icon = Icons.Filled.ContentCopy,
                enabled = enabled
            )

            ActionButton(
                label = "QR",
                onClick = onQr,
                modifier = Modifier.weight(0.8f),
                kind = ActionButtonKind.SECONDARY,
                icon = Icons.Filled.QrCode2,
                enabled = enabled
            )
        }
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
            .border(1.dp, appBorderColor(), InnerCardShape)
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
            Icon(imageVector = Icons.Filled.Edit, contentDescription = "Редактировать", tint = AppAccent)
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
