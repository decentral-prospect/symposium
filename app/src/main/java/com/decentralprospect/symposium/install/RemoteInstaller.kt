package com.decentralprospect.symposium

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.DefaultSecurityProviderConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.kex.DHG14
import net.schmizz.sshj.transport.kex.DHGexSHA256
import net.schmizz.sshj.transport.kex.ECDHNistP
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class OpenRoomInfo(
    val name: String,
    val moderatorKey: String = ""
)

data class DeploymentProfile(
    val appDir: String,
    val serviceName: String,
    val instanceName: String,
    val serviceUser: String,
    val serviceGroup: String,
    val binaryDirName: String,
    val stateDirName: String,
    val identityDirName: String,
    val binaryName: String,
    val databaseName: String,
    val tokenName: String,
    val certificateName: String,
    val privateKeyName: String,
    val dbTable: String,
    val dbRoomColumn: String,
    val dbKeyColumn: String,
    val publicPort: Int,
    val adminPort: Int,
    val iceUdpPortMin: Int,
    val iceUdpPortMax: Int,
    val firewallTag: String,
    val stagingDir: String,
    val adminToken: String
)

internal data class RelayReleaseMetadata(
    val version: String,
    val downloadUrl: String,
    val sha256: String
)

internal fun configuredRelayRelease(): RelayReleaseMetadata = RelayReleaseMetadata(
    version = BuildConfig.RELAY_RELEASE_VERSION,
    downloadUrl = BuildConfig.RELAY_BINARY_URL,
    sha256 = BuildConfig.RELAY_BINARY_SHA256
)

private fun deploymentProfileToJson(profile: DeploymentProfile): JSONObject = JSONObject()
    .put("appDir", profile.appDir)
    .put("serviceName", profile.serviceName)
    .put("instanceName", profile.instanceName)
    .put("serviceUser", profile.serviceUser)
    .put("serviceGroup", profile.serviceGroup)
    .put("binaryDirName", profile.binaryDirName)
    .put("stateDirName", profile.stateDirName)
    .put("identityDirName", profile.identityDirName)
    .put("binaryName", profile.binaryName)
    .put("databaseName", profile.databaseName)
    .put("tokenName", profile.tokenName)
    .put("certificateName", profile.certificateName)
    .put("privateKeyName", profile.privateKeyName)
    .put("dbTable", profile.dbTable)
    .put("dbRoomColumn", profile.dbRoomColumn)
    .put("dbKeyColumn", profile.dbKeyColumn)
    .put("publicPort", profile.publicPort)
    .put("adminPort", profile.adminPort)
    .put("iceUdpPortMin", profile.iceUdpPortMin)
    .put("iceUdpPortMax", profile.iceUdpPortMax)
    .put("firewallTag", profile.firewallTag)
    .put("stagingDir", profile.stagingDir)
    .put("adminToken", profile.adminToken)

private fun deploymentProfileFromJson(value: JSONObject?): DeploymentProfile? {
    if (value == null) return null
    return runCatching {
        DeploymentProfile(
            appDir = value.getString("appDir"),
            serviceName = value.getString("serviceName"),
            instanceName = value.optString("instanceName").ifBlank { value.getString("serviceName") },
            serviceUser = value.getString("serviceUser"),
            serviceGroup = value.getString("serviceGroup"),
            binaryDirName = value.getString("binaryDirName"),
            stateDirName = value.getString("stateDirName"),
            identityDirName = value.getString("identityDirName"),
            binaryName = value.getString("binaryName"),
            databaseName = value.getString("databaseName"),
            tokenName = value.getString("tokenName"),
            certificateName = value.getString("certificateName"),
            privateKeyName = value.getString("privateKeyName"),
            dbTable = value.getString("dbTable"),
            dbRoomColumn = value.getString("dbRoomColumn"),
            dbKeyColumn = value.getString("dbKeyColumn"),
            publicPort = value.getInt("publicPort"),
            adminPort = value.getInt("adminPort"),
            iceUdpPortMin = value.getInt("iceUdpPortMin"),
            iceUdpPortMax = value.getInt("iceUdpPortMax"),
            firewallTag = value.getString("firewallTag"),
            stagingDir = value.getString("stagingDir"),
            adminToken = value.getString("adminToken")
        )
    }.getOrNull()
}

private fun parseOpenRoomsJson(raw: String): List<OpenRoomInfo> {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return emptyList()

    return runCatching {
        val root = JSONObject(trimmed)
        parseOpenRoomsArray(root.optJSONArray("openRooms") ?: JSONArray())
    }.getOrElse {
        emptyList()
    }
}

private fun parseOpenRoomsArray(arr: JSONArray): List<OpenRoomInfo> {
    val out = ArrayList<OpenRoomInfo>(arr.length())

    for (i in 0 until arr.length()) {
        when (val item = arr.opt(i)) {
            is JSONObject -> {
                val name = item.optString("room")
                    .ifBlank { item.optString("name") }
                    .trim()

                val moderatorKey = item.optString("moderator_key")
                    .ifBlank { item.optString("moderatorKey") }
                    .trim()

                if (name.isNotBlank()) {
                    out += OpenRoomInfo(name = name, moderatorKey = moderatorKey)
                }
            }

            is String -> {
                val name = item.trim()
                if (name.isNotBlank()) {
                    out += OpenRoomInfo(name = name)
                }
            }

            else -> {
                val name = arr.optString(i).trim()
                if (name.isNotBlank() && name != "null") {
                    out += OpenRoomInfo(name = name)
                }
            }
        }
    }

    return out
}

private fun openRoomsToJsonArray(rooms: List<OpenRoomInfo>): JSONArray {
    val arr = JSONArray()
    rooms.forEach { room ->
        arr.put(
            JSONObject()
                .put("room", room.name)
                .put("moderator_key", room.moderatorKey)
        )
    }
    return arr
}

class RemoteInstaller(private val appContext: Context) {
    data class RelayRemovalResult(
        val success: Boolean,
        val observedSshHostKeyPin: String? = null
    )

    data class ProbeResult(
        val installed: Boolean,
        val openRooms: List<OpenRoomInfo>,
        val relayInfo: RelayInfo? = null,
        val deploymentProfile: DeploymentProfile? = null,
        val observedSshHostKeyPin: String? = null,
        val installationInProgress: Boolean = false
    )

    data class RoomAdminResult(
        val openRooms: List<OpenRoomInfo>,
        val relayInfo: RelayInfo? = null,
        val observedSshHostKeyPin: String? = null
    )

    data class RelayVersionResult(
        val name: String,
        val version: String
    )

    data class RelayInfo(
        val host: String,
        val httpsPort: Int,
        val pin: String,
        val adminToken: String = ""
    ) {
        val httpsBaseUrl: String get() = if (httpsPort == 443) "https://$host" else "https://$host:$httpsPort"
        val wssBaseUrl: String get() = if (httpsPort == 443) "wss://$host" else "wss://$host:$httpsPort"
        val wssWsUrl: String get() = "${wssBaseUrl}/ws"
    }

    data class InstallResult(
        val success: Boolean,
        val relayInfo: RelayInfo? = null,
        val deploymentProfile: DeploymentProfile? = null,
        val openRooms: List<OpenRoomInfo> = emptyList(),
        val exitStatus: Int = -1,
        val observedSshHostKeyPin: String? = null
    )

    suspend fun performInstallationDetailed(
        serverIp: String,
        login: String,
        password: String,
        expectedSshHostKeyPin: String?,
        existingProfile: DeploymentProfile? = null,
        logger: suspend (String) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        requireKnownSshHostKeyPin(expectedSshHostKeyPin)

        var observedHostKeyPin: String? = null
        var selectedProfile: DeploymentProfile? = null
        var installMarkerCreated = false

        try {
            logger("Подключение к серверу…")

            buildSshClientNoX25519().use { ssh ->
                val verifier = Sha256HostKeyPinVerifier(expectedSshHostKeyPin) { pin ->
                    observedHostKeyPin = pin
                }
                ssh.addHostKeyVerifier(verifier)
                ssh.connect(serverIp)
                ssh.authPassword(login, password)

                logger("SSH готов")

                val discovered = discoverRandomizedDeployments(
                    ssh = ssh,
                    login = login,
                    password = password,
                    serverIp = serverIp
                )
                val currentDeployment = discovered.firstOrNull {
                    it.installed && it.relayInfo != null
                }
                val staleDeployments = if (currentDeployment == null) {
                    discovered
                } else {
                    discovered.filterNot {
                        it.profile.serviceName == currentDeployment.profile.serviceName
                    }
                }
                val legacyDeploymentPresent = hasLegacyDeployment(
                    ssh = ssh,
                    login = login,
                    password = password
                )

                if (staleDeployments.isNotEmpty() || legacyDeploymentPresent) {
                    logger("Найдены старые или неработающие установки; удаление…")
                    staleDeployments.forEach { deployment ->
                        val removal = runRootOrSudo(
                            ssh = ssh,
                            login = login,
                            password = password,
                            command = buildRemovalScript(deployment.profile)
                        )
                        check(removal.exitStatus == 0) {
                            "Не удалось удалить старый сервис ${deployment.profile.serviceName}"
                        }
                    }
                    if (legacyDeploymentPresent) {
                        val removal = runRootOrSudo(
                            ssh = ssh,
                            login = login,
                            password = password,
                            command = LEGACY_REMOVAL_SCRIPT
                        )
                        check(removal.exitStatus == 0) {
                            "Не удалось удалить старую установку"
                        }
                    }
                    logger("Старые установки удалены")
                }

                if (currentDeployment != null) {
                    logger("Работающая установка найдена; конфигурация синхронизирована")
                    return@withContext InstallResult(
                        success = true,
                        relayInfo = currentDeployment.relayInfo,
                        deploymentProfile = currentDeployment.profile,
                        openRooms = currentDeployment.openRooms,
                        exitStatus = 0,
                        observedSshHostKeyPin = observedHostKeyPin
                    )
                }

                val removedExistingInstallation =
                    staleDeployments.isNotEmpty() || legacyDeploymentPresent
                val deploymentProfile = if (removedExistingInstallation) {
                    generateDeploymentProfile()
                } else {
                    existingProfile ?: generateDeploymentProfile()
                }
                selectedProfile = deploymentProfile
                val markerResult = runRootOrSudo(
                    ssh = ssh,
                    login = login,
                    password = password,
                    command = "printf '%s\n' \"${'$'}(date +%s)\" > ${shQuote(INSTALL_IN_PROGRESS_MARKER_PATH)} && chmod 600 ${shQuote(INSTALL_IN_PROGRESS_MARKER_PATH)}"
                )
                check(markerResult.exitStatus == 0) { "Не удалось установить маркер установки" }
                installMarkerCreated = true

                val installRemoteDir = deploymentProfile.stagingDir
                logger("Очистка…")
                ssh.startSession().use { session ->
                    val cmd = session.exec(
                        "rm -rf ${shQuote(installRemoteDir)} && mkdir -p ${shQuote(installRemoteDir)} && chmod 700 ${shQuote(installRemoteDir)}"
                    )
                    cmd.join()
                    check((cmd.exitStatus ?: 1) == 0) { "Не удалось подготовить каталог установщика" }
                }
            }

            val deploymentProfile = checkNotNull(selectedProfile)
            val installRemoteDir = deploymentProfile.stagingDir
            val remoteScript = "$installRemoteDir/${deploymentProfile.binaryName}.sh"

            logger("Подготовка файлов…")
            val scriptBytes = renderInstallScript(deploymentProfile, serverIp).toByteArray(Charsets.UTF_8)

            uploadResumableFile(
                serverIp = serverIp,
                login = login,
                password = password,
                expectedSshHostKeyPin = expectedSshHostKeyPin,
                remotePath = remoteScript,
                payload = scriptBytes,
                label = "Скрипт",
                logger = logger,
                onObservedPin = { pin -> observedHostKeyPin = pin }
            )

            logger("Скачивание SymposiumRelay из GitHub Release…")
            var installRun = runRemoteInstallScript(
                serverIp = serverIp,
                login = login,
                password = password,
                expectedSshHostKeyPin = expectedSshHostKeyPin,
                remoteScript = remoteScript,
                forceLocalBinary = false,
                logger = logger,
                onObservedPin = { pin -> observedHostKeyPin = pin }
            )

            if (
                installRun.exitStatus != 0 &&
                installRun.combinedOutput.contains(RELAY_DOWNLOAD_FAILED_MARKER)
            ) {
                logger("GitHub Release недоступен; используется резервный файл из приложения…")
                val binBytes = appContext.assets.open(SERVER_BIN_ASSET_PATH).use { it.readBytes() }
                uploadResumableFile(
                    serverIp = serverIp,
                    login = login,
                    password = password,
                    expectedSshHostKeyPin = expectedSshHostKeyPin,
                    remotePath = "$installRemoteDir/${deploymentProfile.binaryName}.src",
                    payload = binBytes,
                    label = "Резервный файл сервера",
                    logger = logger,
                    onObservedPin = { pin -> observedHostKeyPin = pin }
                )

                logger("Повторный запуск с резервным файлом…")
                installRun = runRemoteInstallScript(
                    serverIp = serverIp,
                    login = login,
                    password = password,
                    expectedSshHostKeyPin = expectedSshHostKeyPin,
                    remoteScript = remoteScript,
                    forceLocalBinary = true,
                    logger = logger,
                    onObservedPin = { pin -> observedHostKeyPin = pin }
                )
            }

            if (
                installRun.exitStatus != 0 &&
                installRun.combinedOutput.contains(EXISTING_INSTALL_FOUND_MARKER)
            ) {
                logger("Во время установки найден существующий сервис; проверка…")
                var healthyDeployment: DiscoveredDeployment? = null
                var removedStaleDeployment = false
                buildSshClientNoX25519().use { ssh ->
                    val verifier = Sha256HostKeyPinVerifier(expectedSshHostKeyPin) { pin ->
                        observedHostKeyPin = pin
                    }
                    ssh.addHostKeyVerifier(verifier)
                    ssh.connect(serverIp)
                    ssh.authPassword(login, password)
                    val deployments = discoverRandomizedDeployments(
                        ssh = ssh,
                        login = login,
                        password = password,
                        serverIp = serverIp
                    )
                    healthyDeployment = deployments.firstOrNull {
                        it.installed && it.relayInfo != null
                    }
                    val staleDeployments = if (healthyDeployment == null) {
                        deployments
                    } else {
                        deployments.filterNot {
                            it.profile.serviceName == healthyDeployment?.profile?.serviceName
                        }
                    }
                    val legacyDeploymentPresent = hasLegacyDeployment(ssh, login, password)
                    if (staleDeployments.isNotEmpty() || legacyDeploymentPresent) {
                        logger("Удаление найденных неработающих установок…")
                        staleDeployments.forEach { stale ->
                            val removal = runRootOrSudo(
                                ssh = ssh,
                                login = login,
                                password = password,
                                command = buildRemovalScript(stale.profile)
                            )
                            check(removal.exitStatus == 0) {
                                "Не удалось удалить старый сервис ${stale.profile.serviceName}"
                            }
                        }
                        if (legacyDeploymentPresent) {
                            val removal = runRootOrSudo(
                                ssh = ssh,
                                login = login,
                                password = password,
                                command = LEGACY_REMOVAL_SCRIPT
                            )
                            check(removal.exitStatus == 0) {
                                "Не удалось удалить старую установку"
                            }
                        }
                        removedStaleDeployment = true
                    }
                }

                val synchronizedDeployment = healthyDeployment
                if (synchronizedDeployment?.relayInfo != null) {
                    logger("Конфигурация синхронизирована")
                    return@withContext InstallResult(
                        success = true,
                        relayInfo = synchronizedDeployment.relayInfo,
                        deploymentProfile = synchronizedDeployment.profile,
                        openRooms = synchronizedDeployment.openRooms,
                        exitStatus = 0,
                        observedSshHostKeyPin = observedHostKeyPin
                    )
                }

                if (removedStaleDeployment) {
                    logger("Старые установки удалены; повтор чистой установки…")
                    return@withContext performInstallationDetailed(
                        serverIp = serverIp,
                        login = login,
                        password = password,
                        expectedSshHostKeyPin = expectedSshHostKeyPin,
                        existingProfile = null,
                        logger = logger
                    )
                }

                return@withContext InstallResult(
                    success = false,
                    deploymentProfile = deploymentProfile,
                    exitStatus = EXISTING_DEPLOYMENT_UNHEALTHY_EXIT_CODE,
                    observedSshHostKeyPin = observedHostKeyPin
                )
            }

            val exitStatus = installRun.exitStatus
            if (exitStatus != 0) {
                logger("Ошибка установки: код $exitStatus")
                return@withContext InstallResult(
                    success = false,
                    deploymentProfile = deploymentProfile,
                    exitStatus = exitStatus,
                    observedSshHostKeyPin = observedHostKeyPin
                )
            }

            val tlsPin = Regex("(?m)^RELAY_TLS_PIN=(sha256/[A-Za-z0-9+/=]+)$")
                .find(installRun.combinedOutput)
                ?.groupValues
                ?.getOrNull(1)
                ?: throw IllegalStateException("Установщик не вернул TLS pin")
            logger("Готово")
            InstallResult(
                success = true,
                relayInfo = RelayInfo(
                    host = serverIp,
                    httpsPort = deploymentProfile.publicPort,
                    pin = tlsPin,
                    adminToken = deploymentProfile.adminToken
                ),
                deploymentProfile = deploymentProfile,
                openRooms = emptyList(),
                exitStatus = exitStatus,
                observedSshHostKeyPin = observedHostKeyPin
            )
        } catch (e: Exception) {
            val sshError = explainSshConnectionIssue(e)
            val msg = e.message ?: e.javaClass.simpleName
            if (sshError != null) {
                logger(sshError)
            } else if (msg.contains("X25519", ignoreCase = true)) {
                logger("Ошибка SSH: X25519 не поддерживается")
            } else {
                logger("Ошибка установки: $msg")
            }
            throw IllegalStateException(sshError ?: msg, e)
        } finally {
            if (installMarkerCreated) {
                runCatching {
                    clearInstallInProgressMarker(
                        serverIp = serverIp,
                        login = login,
                        password = password,
                        expectedSshHostKeyPin = expectedSshHostKeyPin
                    )
                }
            }
        }
    }

    suspend fun removeRelayFromServer(
        serverIp: String,
        login: String,
        password: String,
        expectedSshHostKeyPin: String?,
        deploymentProfile: DeploymentProfile? = null,
        logger: suspend (String) -> Unit
    ): RelayRemovalResult = withContext(Dispatchers.IO) {
        requireKnownSshHostKeyPin(expectedSshHostKeyPin)

        var observedHostKeyPin: String? = null
        try {
            logger("Подключение к серверу…")
            buildSshClientNoX25519().use { ssh ->
                val verifier = Sha256HostKeyPinVerifier(expectedSshHostKeyPin) { pin ->
                    observedHostKeyPin = pin
                }
                ssh.addHostKeyVerifier(verifier)
                ssh.connect(serverIp)
                ssh.authPassword(login, password)

                logger("Удаление Relay…")
                val effectiveProfile = deploymentProfile ?: discoverRandomizedDeployments(
                    ssh = ssh,
                    login = login,
                    password = password,
                    serverIp = serverIp
                ).firstOrNull()?.profile
                val cmdText = effectiveProfile?.let { profile ->
                    buildRemovalScript(profile)
                } ?: LEGACY_REMOVAL_SCRIPT
                val exitStatus = runRootOrSudo(ssh, login, password, cmdText).exitStatus
                check(exitStatus == 0) { "Удаление SymposiumRelay завершилось с ошибкой (код $exitStatus)" }

                ssh.startSession().use { cleanupSession ->
                    val stagingDir = effectiveProfile?.stagingDir ?: LEGACY_INSTALL_REMOTE_DIR
                    val cleanupCmd = cleanupSession.exec("rm -rf ${shQuote(stagingDir)}")
                    cleanupCmd.join()
                }

                logger("Relay удалён")
                RelayRemovalResult(success = true, observedSshHostKeyPin = observedHostKeyPin)
            }
        } catch (e: Exception) {
            val sshError = explainSshConnectionIssue(e)
            if (sshError != null) logger(sshError) else logger("Ошибка удаления: ${e.message ?: e.javaClass.simpleName}")
            throw IllegalStateException(sshError ?: (e.message ?: "Неизвестная ошибка"), e)
        }
    }

    private suspend fun uploadResumableFile(
        serverIp: String,
        login: String,
        password: String,
        expectedSshHostKeyPin: String?,
        remotePath: String,
        payload: ByteArray,
        label: String,
        logger: suspend (String) -> Unit,
        onObservedPin: (String) -> Unit
    ) {
        requireKnownSshHostKeyPin(expectedSshHostKeyPin)

        val tempPath = "$remotePath.part"
        val totalBytes = payload.size.toLong()
        var attempt = 0

        while (true) {
            attempt++

            try {
                buildSshClientNoX25519().use { ssh ->
                    val verifier = Sha256HostKeyPinVerifier(expectedSshHostKeyPin) { pin ->
                        onObservedPin(pin)
                    }
                    ssh.addHostKeyVerifier(verifier)
                    ssh.connect(serverIp)
                    ssh.authPassword(login, password)

                    ssh.newSFTPClient().use { sftp ->
                        var remoteBytes = runCatching { sftp.stat(tempPath).size }.getOrElse { 0L }
                        if (remoteBytes > totalBytes) {
                            runCatching { sftp.rm(tempPath) }
                            remoteBytes = 0L
                        }

                        if (remoteBytes > 0L && remoteBytes < totalBytes) {
                            val percent = ((remoteBytes * 100) / totalBytes).toInt()
                            logger("$label: с $percent%")
                        } else if (remoteBytes == 0L) {
                            logger("$label: 0%")
                        }

                        sftp.open(tempPath, EnumSet.of(OpenMode.CREAT, OpenMode.WRITE)).use { remoteFile ->
                            var offset = remoteBytes
                            var lastReportedBucket =
                                (((remoteBytes * 100) / totalBytes).toInt()) / UPLOAD_PROGRESS_STEP_PERCENT

                            while (offset < totalBytes) {
                                val len = minOf(UPLOAD_CHUNK_SIZE.toLong(), totalBytes - offset).toInt()
                                remoteFile.write(offset, payload, offset.toInt(), len)
                                offset += len

                                val percent = ((offset * 100) / totalBytes).toInt()
                                val bucket = percent / UPLOAD_PROGRESS_STEP_PERCENT
                                if (bucket > lastReportedBucket || offset == totalBytes) {
                                    lastReportedBucket = bucket
                                    logger("$label: ${percent.coerceAtMost(100)}%")
                                }
                            }
                        }

                        runCatching { sftp.rm(remotePath) }
                        sftp.rename(tempPath, remotePath)

                        val uploadedBytes = sftp.stat(remotePath).size
                        check(uploadedBytes == totalBytes) {
                            "$label: uploaded size mismatch ($uploadedBytes/$totalBytes)"
                        }
                    }
                }

                return
            } catch (e: Exception) {
                if (attempt >= MAX_UPLOAD_RETRIES) throw e
                logger("$label: повтор ${attempt + 1}/$MAX_UPLOAD_RETRIES")
                delay(1500L * attempt)
            }
        }
    }

    private data class RemoteInstallRun(
        val exitStatus: Int,
        val combinedOutput: String
    )

    private suspend fun runRemoteInstallScript(
        serverIp: String,
        login: String,
        password: String,
        expectedSshHostKeyPin: String?,
        remoteScript: String,
        forceLocalBinary: Boolean,
        logger: suspend (String) -> Unit,
        onObservedPin: (String) -> Unit
    ): RemoteInstallRun {
        var attempt = 0

        while (true) {
            attempt++
            try {
                return buildSshClientNoX25519().use { ssh ->
                    val verifier = Sha256HostKeyPinVerifier(expectedSshHostKeyPin) { pin ->
                        onObservedPin(pin)
                    }
                    ssh.addHostKeyVerifier(verifier)

                    ssh.connect(serverIp)
                    ssh.authPassword(login, password)

                    ssh.startSession().use { session ->
                        val isRootLogin = login.trim() == "root"
                        val env = buildString {
                            append("env ")
                            if (forceLocalBinary) {
                                append("RELAY_FORCE_LOCAL_BINARY=1 ")
                            }
                        }
                        val runCmd = "${env}bash ${shQuote(remoteScript)} ${shQuote(serverIp)}"
                        val launchCmd = if (isRootLogin) {
                            "chmod +x ${shQuote(remoteScript)} && $runCmd"
                        } else {
                            "chmod +x ${shQuote(remoteScript)} && sudo -S -p '' $runCmd"
                        }

                        val cmd = session.exec(launchCmd)
                        if (!isRootLogin) {
                            cmd.outputStream.bufferedWriter(Charsets.UTF_8).use { stdin ->
                                stdin.write(password)
                                stdin.newLine()
                                stdin.flush()
                            }
                        }

                        val uiLog = InstallUiLogFilter(logger)
                        val combinedOutput = StringBuilder()
                        fun remember(line: String) {
                            synchronized(combinedOutput) {
                                combinedOutput.append(line).append('\n')
                            }
                        }

                        coroutineScope {
                            val outJob = launch {
                                cmd.inputStream.bufferedReader().useLines { lines ->
                                    lines.forEach { line ->
                                        remember(line)
                                        uiLog.onStdoutLine(line)
                                    }
                                }
                            }
                            val errJob = launch {
                                cmd.errorStream.bufferedReader().useLines { lines ->
                                    lines.forEach { line ->
                                        remember(line)
                                        uiLog.onStderrLine(line)
                                    }
                                }
                            }

                            cmd.join()
                            outJob.join()
                            errJob.join()
                        }

                        RemoteInstallRun(
                            exitStatus = cmd.exitStatus ?: -1,
                            combinedOutput = combinedOutput.toString()
                        )
                    }
                }
            } catch (e: Exception) {
                if (attempt >= MAX_SSH_REQUEST_RETRIES || !shouldRetryNetworkRequest(e)) throw e
                logger("SSH: повтор ${attempt + 1}/$MAX_SSH_REQUEST_RETRIES")
                delay(retryDelayMs(attempt))
            }
        }
    }

    private class InstallUiLogFilter(
        private val logger: suspend (String) -> Unit
    ) {
        private val shown = linkedSetOf<String>()
        private val errorShown = AtomicBoolean(false)

        suspend fun onStdoutLine(lineRaw: String) {
            val line = lineRaw.trim()
            if (line.isBlank()) return

            stageMessage(line)?.let {
                emitOnce(it)
                return
            }

            finalMessage(line)?.let {
                emitOnce(it)
                return
            }
        }

        suspend fun onStderrLine(lineRaw: String) {
            val line = lineRaw.trim()
            if (line.isBlank()) return

            stageMessage(line)?.let {
                emitOnce(it)
                return
            }

            logger(errorMessage(line))
        }

        private suspend fun emitOnce(message: String) {
            val shouldEmit = synchronized(shown) { shown.add(message) }
            if (shouldEmit) logger(message)
        }

        private fun stageMessage(line: String): String? {
            return when {
                "[1/8]" in line -> "1/8 Проверка"
                "[2/8]" in line -> "2/8 Зависимости"
                "[3/8]" in line -> "3/8 Файрвол"
                "[4/8]" in line -> "4/8 Токен"
                "[5/8]" in line -> "5/8 Сервис"
                "[6/8]" in line -> "6/8 Запуск"
                "[7/8]" in line -> "7/8 HTTPS/WSS"
                "[8/8]" in line -> "8/8 Проверка"
                else -> null
            }
        }

        private fun finalMessage(line: String): String? {
            return when {
                line.startsWith("Installation finished.", ignoreCase = true) -> "Сервер готов"
                line.startsWith("SYMPOSIUM_PIN=", ignoreCase = true) -> "Pin получен"
                line.startsWith("SYMPOSIUM_HTTPS=", ignoreCase = true) -> "HTTPS готов"
                line.startsWith("SYMPOSIUM_WSS=", ignoreCase = true) -> "WSS готов"
                else -> null
            }
        }

        private fun errorMessage(line: String): String {
            val message = translateServerError(line)
            return if (errorShown.compareAndSet(false, true)) {
                "Инфо: $message"
            } else {
                "Инфо: $message"
            }
        }

        private fun translateServerError(line: String): String {
            val text = line.removePrefix("ERROR:").trim()
            return when {
                text.contains(RELAY_DOWNLOAD_FAILED_MARKER, ignoreCase = true) ->
                    "не удалось скачать файл сервера"
                text.contains("installer must be run as root", ignoreCase = true) ->
                    "нужны права root"
                text.contains("APT is locked", ignoreCase = true) || text.contains("APT still locked", ignoreCase = true) ->
                    "APT занят другим процессом"
                text.contains("Permission denied", ignoreCase = true) ->
                    "нет прав доступа"
                text.contains("No such file or directory", ignoreCase = true) ->
                    "файл не найден"
                text.contains("command not found", ignoreCase = true) ->
                    "команда не найдена"
                text.contains("nginx: [emerg]", ignoreCase = true) ->
                    "ошибка Nginx"
                text.contains("Failed to", ignoreCase = true) ->
                    "операция не выполнена"
                text.isBlank() ->
                    "неизвестная ошибка"
                else ->
                    text.take(160)
            }
        }
    }

    suspend fun probeInstallationState(
        serverIp: String,
        login: String,
        password: String,
        expectedSshHostKeyPin: String?
    ): ProbeResult = withContext(Dispatchers.IO) {
        requireKnownSshHostKeyPin(expectedSshHostKeyPin)

        var observedHostKeyPin: String? = null

        try {
            buildSshClientNoX25519().use { ssh ->
                val verifier = Sha256HostKeyPinVerifier(expectedSshHostKeyPin) { pin ->
                    observedHostKeyPin = pin
                }
                ssh.addHostKeyVerifier(verifier)
                ssh.connect(serverIp)
                ssh.authPassword(login, password)

                if (isInstallInProgress(ssh, login, password)) {
                    return@use ProbeResult(
                        installed = false,
                        openRooms = emptyList(),
                        observedSshHostKeyPin = observedHostKeyPin,
                        installationInProgress = true
                    )
                }

                val discovered = discoverRandomizedDeployments(
                    ssh = ssh,
                    login = login,
                    password = password,
                    serverIp = serverIp
                )
                (discovered.firstOrNull { it.installed && it.relayInfo != null }
                    ?: discovered.firstOrNull())?.let { deployment ->
                    return@use ProbeResult(
                        installed = deployment.installed,
                        openRooms = deployment.openRooms,
                        relayInfo = deployment.relayInfo,
                        deploymentProfile = deployment.profile,
                        observedSshHostKeyPin = observedHostKeyPin
                    )
                }

                // Compatibility path for installations created by older application builds.
                val adminToken = runCatching { readAdminToken(ssh, login, password) }.getOrNull()
                val relayInfo = runCatching {
                    readRemoteFilePrivileged(ssh, login, password, RELAY_INFO_PATH)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { raw ->
                            val parsed = parseRelayInfo(raw, fallbackIp = serverIp)
                            if (parsed.adminToken.isBlank() && !adminToken.isNullOrBlank()) {
                                parsed.copy(adminToken = adminToken)
                            } else {
                                parsed
                            }
                        }
                }.getOrNull()

                val httpsPort = relayInfo?.httpsPort ?: 443
                val relayTlsPin = relayInfo?.pin
                val token = relayInfo?.adminToken?.takeIf { it.isNotBlank() } ?: adminToken

                val versionResult = if (!relayTlsPin.isNullOrBlank() && !token.isNullOrBlank()) {
                    runCatching {
                        fetchRelayVersionOverHttps(
                            serverIp = serverIp,
                            httpsPort = httpsPort,
                            relayTlsPin = relayTlsPin,
                            adminToken = token
                        )
                    }
                } else {
                    Result.failure(IllegalStateException("Missing HTTPS admin credentials"))
                }

                val installed = versionResult.isSuccess
                val rooms = if (installed && !relayTlsPin.isNullOrBlank() && !token.isNullOrBlank()) {
                    runCatching {
                        fetchOpenRoomsOverHttps(
                            serverIp = serverIp,
                            httpsPort = httpsPort,
                            relayTlsPin = relayTlsPin,
                            adminToken = token
                        ).openRooms
                    }.getOrElse { emptyList() }
                } else {
                    emptyList()
                }

                ProbeResult(
                    installed = installed,
                    openRooms = rooms,
                    relayInfo = relayInfo?.let { info ->
                        if (!token.isNullOrBlank() && info.adminToken.isBlank()) info.copy(adminToken = token) else info
                    },
                    deploymentProfile = null,
                    observedSshHostKeyPin = observedHostKeyPin
                )
            }
        } catch (e: Exception) {
            throw IllegalStateException(explainSshConnectionIssue(e) ?: (e.message ?: "Не удалось подключиться к серверу"), e)
        }
    }

    suspend fun setRoomOpenState(
        serverIp: String,
        login: String,
        password: String,
        expectedSshHostKeyPin: String?,
        roomName: String,
        open: Boolean
    ): RoomAdminResult = withContext(Dispatchers.IO) {
        requireKnownSshHostKeyPin(expectedSshHostKeyPin)

        val room = roomName.trim()
        require(room.isNotBlank()) { "Room name is empty" }

        var observedHostKeyPin: String? = null
        try {
            buildSshClientNoX25519().use { ssh ->
                val verifier = Sha256HostKeyPinVerifier(expectedSshHostKeyPin) { pin ->
                    observedHostKeyPin = pin
                }
                ssh.addHostKeyVerifier(verifier)
                ssh.connect(serverIp)
                ssh.authPassword(login, password)

                val deployment = discoverRandomizedDeployments(
                    ssh = ssh,
                    login = login,
                    password = password,
                    serverIp = serverIp
                ).firstOrNull()
                if (deployment != null) {
                    val actionPath = if (open) "/admin/open-room" else "/admin/close-room"
                    val encodedRoom = URLEncoder.encode(room, Charsets.UTF_8.name())
                    val tokenPath = "${deployment.profile.appDir}/${deployment.profile.identityDirName}/${deployment.profile.tokenName}"
                    val result = runRootOrSudo(
                        ssh = ssh,
                        login = login,
                        password = password,
                        command = """
                            set -e
                            token="${'$'}(cat ${shQuote(tokenPath)})"
                            curl -fsS -X POST -H "X-Relay-Key: ${'$'}token" ${shQuote("http://127.0.0.1:${deployment.profile.adminPort}${actionPath}?name=$encodedRoom")} >/dev/null
                        """.trimIndent()
                    )
                    check(result.exitStatus == 0) {
                        "Failed to ${if (open) "open" else "close"} room"
                    }
                    val refreshed = discoverRandomizedDeployments(
                        ssh = ssh,
                        login = login,
                        password = password,
                        serverIp = serverIp
                    ).firstOrNull() ?: deployment
                    return@use RoomAdminResult(
                        openRooms = refreshed.openRooms,
                        relayInfo = refreshed.relayInfo,
                        observedSshHostKeyPin = observedHostKeyPin
                    )
                }

                val adminToken = readAdminToken(ssh, login, password)
                require(!adminToken.isNullOrBlank()) { "Admin token is missing on server" }

                val actionPath = if (open) "/admin/open-room" else "/admin/close-room"
                val encodedRoom = URLEncoder.encode(room, Charsets.UTF_8.name())
                ssh.startSession().use { session ->
                    val cmd = session.exec(
                        "curl -fsS -X POST -H ${shQuote("X-Relay-Key: $adminToken")} 'http://127.0.0.1:3002${actionPath}?name=${encodedRoom}' >/dev/null"
                    )
                    cmd.join()
                    check((cmd.exitStatus ?: 1) == 0) { "Failed to ${if (open) "open" else "close"} room" }
                }

                fetchOpenRooms(ssh, login, password, observedHostKeyPin, serverIp)
            }
        } catch (e: Exception) {
            throw IllegalStateException(explainSshConnectionIssue(e) ?: (e.message ?: "Не удалось подключиться к серверу"), e)
        }
    }

    suspend fun fetchOpenRoomsOverSsh(
        serverIp: String,
        login: String,
        password: String,
        expectedSshHostKeyPin: String?
    ): RoomAdminResult = withContext(Dispatchers.IO) {
        requireKnownSshHostKeyPin(expectedSshHostKeyPin)

        var observedHostKeyPin: String? = null
        try {
            buildSshClientNoX25519().use { ssh ->
                val verifier = Sha256HostKeyPinVerifier(expectedSshHostKeyPin) { pin ->
                    observedHostKeyPin = pin
                }
                ssh.addHostKeyVerifier(verifier)
                ssh.connect(serverIp)
                ssh.authPassword(login, password)
                discoverRandomizedDeployments(
                    ssh = ssh,
                    login = login,
                    password = password,
                    serverIp = serverIp
                ).firstOrNull()?.let { deployment ->
                    return@use RoomAdminResult(
                        openRooms = deployment.openRooms,
                        relayInfo = deployment.relayInfo,
                        observedSshHostKeyPin = observedHostKeyPin
                    )
                }
                fetchOpenRooms(ssh, login, password, observedHostKeyPin, serverIp)
            }
        } catch (e: Exception) {
            throw IllegalStateException(explainSshConnectionIssue(e) ?: (e.message ?: "Не удалось подключиться к серверу"), e)
        }
    }

    suspend fun fetchRelayVersionOverHttps(
        serverIp: String,
        httpsPort: Int?,
        relayTlsPin: String?,
        adminToken: String?
    ): RelayVersionResult = withContext(Dispatchers.IO) {
        val token = adminToken?.trim().orEmpty()
        val pin = relayTlsPin?.trim().orEmpty()
        val port = httpsPort ?: 443

        require(token.isNotBlank()) { "Admin token is missing" }
        require(pin.isNotBlank()) { "TLS pin is missing" }

        val base = if (port == 443) "https://$serverIp" else "https://$serverIp:$port"
        val client = pinnedHttpsClient(pin, callTimeoutSeconds = RELAY_VERSION_TIMEOUT_SECONDS)
        val request = Request.Builder()
            .url("$base/admin/version")
            .header("X-Relay-Key", token)
            .get()
            .build()

        try {
            withTimeout(RELAY_VERSION_TIMEOUT_MS) {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    check(response.isSuccessful) { "Version request failed: HTTP ${response.code}" }

                    val root = JSONObject(body)
                    val version = root.optString("version").trim()
                    require(version.isNotBlank()) { "Server version is missing" }

                    RelayVersionResult(
                        name = root.optString("name").ifBlank { "SymposiumRelay" },
                        version = version
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("SymposiumRelay не установлен", e)
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("SymposiumRelay не установлен", e)
        }
    }

    suspend fun fetchOpenRoomsOverHttps(
        serverIp: String,
        httpsPort: Int?,
        relayTlsPin: String?,
        adminToken: String?
    ): RoomAdminResult = withContext(Dispatchers.IO) {
        val token = adminToken?.trim().orEmpty()
        val pin = relayTlsPin?.trim().orEmpty()
        val port = httpsPort ?: 443

        require(token.isNotBlank()) { "Admin token is missing" }
        require(pin.isNotBlank()) { "TLS pin is missing" }

        val base = if (port == 443) "https://$serverIp" else "https://$serverIp:$port"
        val client = pinnedHttpsClient(pin)
        val request = Request.Builder()
            .url("$base/admin/open-rooms")
            .header("X-Relay-Key", token)
            .get()
            .build()

        retryNetworkRequest {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Admin request failed: HTTP ${response.code}" }

                RoomAdminResult(
                    openRooms = parseOpenRoomsJson(body),
                    relayInfo = RelayInfo(
                        host = serverIp,
                        httpsPort = port,
                        pin = pin,
                        adminToken = token
                    )
                )
            }
        }
    }

    suspend fun setRoomOpenStateOverHttps(
        serverIp: String,
        httpsPort: Int?,
        relayTlsPin: String?,
        adminToken: String?,
        roomName: String,
        open: Boolean
    ): RoomAdminResult = withContext(Dispatchers.IO) {
        val token = adminToken?.trim().orEmpty()
        val pin = relayTlsPin?.trim().orEmpty()
        val room = roomName.trim()
        val port = httpsPort ?: 443

        require(token.isNotBlank()) { "Admin token is missing" }
        require(pin.isNotBlank()) { "TLS pin is missing" }
        require(room.isNotBlank()) { "Room name is empty" }

        val base = if (port == 443) "https://$serverIp" else "https://$serverIp:$port"
        val actionPath = if (open) "/admin/open-room" else "/admin/close-room"
        val encodedRoom = URLEncoder.encode(room, Charsets.UTF_8.name())

        val client = pinnedHttpsClient(pin)
        val request = Request.Builder()
            .url("$base$actionPath?name=$encodedRoom")
            .header("X-Relay-Key", token)
            .post(ByteArray(0).toRequestBody(null))
            .build()

        retryNetworkRequest {
            client.newCall(request).execute().use { response ->
                response.body?.string()
                check(response.isSuccessful) { "Admin request failed: HTTP ${response.code}" }
            }
        }

        fetchOpenRoomsOverHttps(serverIp, port, pin, token)
    }

    suspend fun rotateModeratorKeyOverHttps(
        serverIp: String,
        httpsPort: Int?,
        relayTlsPin: String?,
        adminToken: String?,
        roomName: String
    ): RoomAdminResult = withContext(Dispatchers.IO) {
        val token = adminToken?.trim().orEmpty()
        val pin = relayTlsPin?.trim().orEmpty()
        val room = roomName.trim()
        val port = httpsPort ?: 443

        require(token.isNotBlank()) { "Admin token is missing" }
        require(pin.isNotBlank()) { "TLS pin is missing" }
        require(room.isNotBlank()) { "Room name is empty" }

        val base = if (port == 443) "https://$serverIp" else "https://$serverIp:$port"
        val encodedRoom = URLEncoder.encode(room, Charsets.UTF_8.name())
        val client = pinnedHttpsClient(pin)
        val request = Request.Builder()
            .url("$base/admin/rotate-moderator-key?name=$encodedRoom")
            .header("X-Relay-Key", token)
            .post(ByteArray(0).toRequestBody(null))
            .build()

        retryNetworkRequest {
            client.newCall(request).execute().use { response ->
                response.body?.string()
                check(response.isSuccessful) { "Admin request failed: HTTP ${response.code}" }
            }
        }

        fetchOpenRoomsOverHttps(serverIp, port, pin, token)
    }

    private suspend fun <T> retryNetworkRequest(block: () -> T): T {
        var attempt = 0

        while (true) {
            attempt++
            try {
                return block()
            } catch (e: Exception) {
                if (attempt >= MAX_HTTP_REQUEST_RETRIES || !shouldRetryNetworkRequest(e)) throw e
                delay(retryDelayMs(attempt))
            }
        }
    }

    private fun shouldRetryNetworkRequest(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.toList()
        val root = chain.lastOrNull() ?: error
        val message = chain.joinToString("\n") { it.message.orEmpty() }

        if (root is UserAuthException) return false
        if (root is CertificateException) return false
        if (message.contains("pin mismatch", ignoreCase = true)) return false
        if (message.contains("TLS pin", ignoreCase = true)) return false
        if (message.contains("SSH-ключ", ignoreCase = true)) return false
        if (message.contains("Admin token is missing", ignoreCase = true)) return false
        if (message.contains("Room name is empty", ignoreCase = true)) return false
        if (message.contains("Server version is missing", ignoreCase = true)) return false

        val httpCode = Regex("HTTP\\s+(\\d{3})").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (httpCode != null) {
            return httpCode == 408 || httpCode == 425 || httpCode == 429 || httpCode in 500..599
        }

        if (root is SocketTimeoutException) return true
        if (root is SocketException) return true
        if (root is TransportException) return true
        if (root is IOException) return true

        return message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ||
            message.contains("connection reset", ignoreCase = true) ||
            message.contains("broken pipe", ignoreCase = true) ||
            message.contains("disconnect", ignoreCase = true) ||
            message.contains("unexpected eof", ignoreCase = true)
    }

    private fun retryDelayMs(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceAtMost(4)
        return minOf(10_000L, NETWORK_RETRY_BASE_DELAY_MS * multiplier)
    }

    private fun explainSshConnectionIssue(error: Throwable): String? {
        val root = generateSequence(error) { it.cause }.last()
        val message = (root.message ?: error.message ?: "").trim()
        if (root is UserAuthException || message.contains("auth fail", ignoreCase = true)) {
            return "Ошибка SSH-аутентификации: неверный логин или пароль."
        }
        if (message.contains("host key", ignoreCase = true) ||
            message.contains("key verification", ignoreCase = true) ||
            message.contains("certificate", ignoreCase = true)
        ) {
            return "Ошибка SSH-сертификата сервера: ключ сервера не прошёл проверку."
        }
        if (root is TransportException && message.contains("disconnect", ignoreCase = true)) {
            return "SSH-соединение разорвано сервером во время аутентификации."
        }
        return null
    }

    private fun fetchOpenRooms(
        ssh: SSHClient,
        login: String,
        password: String,
        observedSshHostKeyPin: String?,
        fallbackIp: String
    ): RoomAdminResult {
        val adminToken = readAdminToken(ssh, login, password)
        require(!adminToken.isNullOrBlank()) { "Admin token is missing on server" }

        val out = ssh.startSession().use { session ->
            val cmd = session.exec(
                "curl -fsS -H ${shQuote("X-Relay-Key: $adminToken")} http://127.0.0.1:3002/admin/open-rooms"
            )
            cmd.join()
            val txt = cmd.inputStream.bufferedReader().readText()
            check((cmd.exitStatus ?: 1) == 0) { "Failed to get open rooms" }
            txt
        }

        val rooms = parseOpenRoomsJson(out)

        val relayInfo = readRemoteFilePrivileged(ssh, login, password, RELAY_INFO_PATH)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                runCatching {
                    val parsed = parseRelayInfo(it, fallbackIp = fallbackIp)
                    if (parsed.adminToken.isBlank()) parsed.copy(adminToken = adminToken) else parsed
                }.getOrNull()
            }

        return RoomAdminResult(
            openRooms = rooms,
            relayInfo = relayInfo,
            observedSshHostKeyPin = observedSshHostKeyPin
        )
    }

    private data class ParsedDeploymentUnit(
        val profile: DeploymentProfile,
        val tokenPath: String,
        val certificatePath: String
    )

    private data class DiscoveredDeployment(
        val profile: DeploymentProfile,
        val relayInfo: RelayInfo?,
        val openRooms: List<OpenRoomInfo>,
        val installed: Boolean
    )

    private fun isInstallInProgress(
        ssh: SSHClient,
        login: String,
        password: String
    ): Boolean {
        val result = runRootOrSudo(
            ssh = ssh,
            login = login,
            password = password,
            command = """
                marker=${shQuote(INSTALL_IN_PROGRESS_MARKER_PATH)}
                [ -f "${'$'}marker" ] || exit 1
                timestamp="${'$'}(tr -dc '0-9' < "${'$'}marker")"
                [ -n "${'$'}timestamp" ] || { rm -f "${'$'}marker"; exit 1; }
                now="${'$'}(date +%s)"
                age="${'$'}((now - timestamp))"
                if [ "${'$'}age" -ge 0 ] && [ "${'$'}age" -le ${INSTALL_MARKER_MAX_AGE_SECONDS} ]; then
                  exit 0
                fi
                rm -f "${'$'}marker"
                exit 1
            """.trimIndent()
        )
        return result.exitStatus == 0
    }

    private fun clearInstallInProgressMarker(
        serverIp: String,
        login: String,
        password: String,
        expectedSshHostKeyPin: String?
    ) {
        buildSshClientNoX25519().use { ssh ->
            ssh.addHostKeyVerifier(Sha256HostKeyPinVerifier(expectedSshHostKeyPin) { })
            ssh.connect(serverIp)
            ssh.authPassword(login, password)
            runRootOrSudo(
                ssh = ssh,
                login = login,
                password = password,
                command = "rm -f ${shQuote(INSTALL_IN_PROGRESS_MARKER_PATH)}"
            )
        }
    }

    private fun discoverRandomizedDeployments(
        ssh: SSHClient,
        login: String,
        password: String,
        serverIp: String
    ): List<DiscoveredDeployment> {
        val listing = runRootOrSudo(
            ssh = ssh,
            login = login,
            password = password,
            command = RANDOMIZED_UNIT_DISCOVERY_SCRIPT
        )
        if (listing.exitStatus != 0) return emptyList()

        val unitPaths = listing.stdout
            .lineSequence()
            .map(String::trim)
            .filter { DISCOVERABLE_UNIT_PATH.matches(it) }
            .distinct()
            .sorted()
            .toList()

        val discovered = unitPaths.mapNotNull { unitPath ->
            val unitText = readRemoteFilePrivileged(ssh, login, password, unitPath)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val parsed = parseRandomizedDeploymentUnit(unitPath, unitText)
                ?: return@mapNotNull null

            val adminToken = readRemoteFilePrivileged(ssh, login, password, parsed.tokenPath)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                .orEmpty()
            val tlsPin = readCertificatePin(
                ssh = ssh,
                login = login,
                password = password,
                certificatePath = parsed.certificatePath
            )

            val profile = parsed.profile.copy(adminToken = adminToken)
            val relayInfo = tlsPin?.takeIf { adminToken.isNotBlank() }?.let {
                RelayInfo(
                    host = serverIp,
                    httpsPort = profile.publicPort,
                    pin = it,
                    adminToken = adminToken
                )
            }
            val rooms = if (adminToken.isNotBlank()) {
                readDeploymentRoomsOverLoopback(
                    ssh = ssh,
                    login = login,
                    password = password,
                    profile = profile,
                    tokenPath = parsed.tokenPath
                )
            } else {
                null
            }

            DiscoveredDeployment(
                profile = profile,
                relayInfo = relayInfo,
                openRooms = rooms ?: emptyList(),
                installed = relayInfo != null && rooms != null
            )
        }

        return discovered.sortedWith(
            compareByDescending<DiscoveredDeployment> { it.installed }
                .thenBy { it.profile.serviceName }
        )
    }

    private fun parseRandomizedDeploymentUnit(
        unitPath: String,
        unitText: String
    ): ParsedDeploymentUnit? = runCatching {
        fun property(name: String): String {
            return unitText.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: error("Missing $name")
        }

        val serviceName = unitPath
            .substringAfterLast('/')
            .removeSuffix(".service")
            .also { require(SAFE_SYSTEMD_NAME.matches(it)) }
        val appDir = property("WorkingDirectory").trimEnd('/')
            .also(::requireSafeAbsolutePath)
        val serviceUser = property("User").also { require(SAFE_ACCOUNT_NAME.matches(it)) }
        val serviceGroup = property("Group").also { require(SAFE_ACCOUNT_NAME.matches(it)) }
        val execStart = property("ExecStart")
        val tokens = execStart.split(Regex("\\s+")).filter(String::isNotBlank)
        require(tokens.size >= 2)

        fun optionalFlagValue(name: String): String? {
            tokens.firstOrNull { it.startsWith("$name=") }?.let {
                return it.substringAfter('=').takeIf(String::isNotBlank)
            }
            val index = tokens.indexOf(name)
            return if (index >= 0 && index + 1 < tokens.size) tokens[index + 1] else null
        }

        fun flagValue(name: String): String {
            return optionalFlagValue(name) ?: error("Missing or empty $name")
        }

        require(flagValue("--admin-on-public").equals("true", ignoreCase = true))
        val executablePath = tokens.first().also(::requireSafeAbsolutePath)
        val databasePath = flagValue("--rooms-db").also(::requireSafeAbsolutePath)
        val tokenPath = flagValue("--admin-token-file").also(::requireSafeAbsolutePath)
        val certificatePath = flagValue("--tls-cert").also(::requireSafeAbsolutePath)
        val privateKeyPath = flagValue("--tls-key").also(::requireSafeAbsolutePath)

        val (binaryDirName, binaryName) = splitManagedFilePath(appDir, executablePath)
        val (stateDirName, databaseName) = splitManagedFilePath(appDir, databasePath)
        val (identityDirName, tokenName) = splitManagedFilePath(appDir, tokenPath)
        val (certificateDirName, certificateName) = splitManagedFilePath(appDir, certificatePath)
        val (privateKeyDirName, privateKeyName) = splitManagedFilePath(appDir, privateKeyPath)
        require(identityDirName == certificateDirName && identityDirName == privateKeyDirName)

        val publicPort = parseListenPort(flagValue("--addr"))
        val adminAddress = flagValue("--admin-addr")
        require(
            adminAddress.startsWith("127.0.0.1:") ||
                adminAddress.startsWith("localhost:")
        )
        val adminPort = parseListenPort(adminAddress)
        val iceMin = flagValue("--ice-udp-port-min").toInt()
        val iceMax = flagValue("--ice-udp-port-max").toInt()
        require(publicPort != adminPort)
        require(iceMin in 1..65535 && iceMax in iceMin..65535)

        val dbTable = flagValue("--db-table").also { require(SAFE_DB_IDENTIFIER.matches(it)) }
        val dbRoomColumn = flagValue("--db-room-column").also { require(SAFE_DB_IDENTIFIER.matches(it)) }
        val dbKeyColumn = flagValue("--db-key-column").also { require(SAFE_DB_IDENTIFIER.matches(it)) }
        require(dbRoomColumn != dbKeyColumn)

        ParsedDeploymentUnit(
            profile = DeploymentProfile(
                appDir = appDir,
                serviceName = serviceName,
                instanceName = optionalFlagValue("--instance-name")
                    ?.also { require(SAFE_SYSTEMD_NAME.matches(it)) }
                    ?: serviceName,
                serviceUser = serviceUser,
                serviceGroup = serviceGroup,
                binaryDirName = binaryDirName,
                stateDirName = stateDirName,
                identityDirName = identityDirName,
                binaryName = binaryName,
                databaseName = databaseName,
                tokenName = tokenName,
                certificateName = certificateName,
                privateKeyName = privateKeyName,
                dbTable = dbTable,
                dbRoomColumn = dbRoomColumn,
                dbKeyColumn = dbKeyColumn,
                publicPort = publicPort,
                adminPort = adminPort,
                iceUdpPortMin = iceMin,
                iceUdpPortMax = iceMax,
                firewallTag = serviceName,
                stagingDir = ".cache/.${randomHex(8)}",
                adminToken = ""
            ),
            tokenPath = tokenPath,
            certificatePath = certificatePath
        )
    }.getOrNull()

    private fun requireSafeAbsolutePath(path: String) {
        require(SAFE_ABSOLUTE_PATH.matches(path))
        require(path != "/")
        require(path.split('/').none { it == "." || it == ".." })
    }

    private fun splitManagedFilePath(appDir: String, path: String): Pair<String, String> {
        val prefix = "$appDir/"
        require(path.startsWith(prefix))
        val parts = path.removePrefix(prefix).split('/')
        require(parts.size == 2)
        parts.forEach { require(SAFE_PATH_COMPONENT.matches(it)) }
        return parts[0] to parts[1]
    }

    private fun parseListenPort(address: String): Int {
        val port = address.substringAfterLast(':').toInt()
        require(port in 1..65535)
        return port
    }

    private fun readCertificatePin(
        ssh: SSHClient,
        login: String,
        password: String,
        certificatePath: String
    ): String? {
        val result = runRootOrSudo(
            ssh = ssh,
            login = login,
            password = password,
            command = """
                set -e
                openssl x509 -in ${shQuote(certificatePath)} -pubkey -noout |
                  openssl pkey -pubin -outform DER |
                  openssl dgst -sha256 -binary |
                  openssl base64 -A
            """.trimIndent()
        )
        if (result.exitStatus != 0) return null
        val value = result.stdout.trim()
        if (!BASE64_VALUE.matches(value)) return null
        return "sha256/$value"
    }

    private fun readDeploymentRoomsOverLoopback(
        ssh: SSHClient,
        login: String,
        password: String,
        profile: DeploymentProfile,
        tokenPath: String
    ): List<OpenRoomInfo>? {
        val result = runRootOrSudo(
            ssh = ssh,
            login = login,
            password = password,
            command = """
                set -e
                token="${'$'}(cat ${shQuote(tokenPath)})"
                version="${'$'}(curl -fsS --connect-timeout 5 --max-time 10 -H "X-Relay-Key: ${'$'}token" http://127.0.0.1:${profile.adminPort}/admin/version)"
                rooms="${'$'}(curl -fsS --connect-timeout 5 --max-time 10 -H "X-Relay-Key: ${'$'}token" http://127.0.0.1:${profile.adminPort}/admin/open-rooms)"
                printf 'VERSION_B64=%s\n' "${'$'}(printf '%s' "${'$'}version" | base64 | tr -d '\n')"
                printf 'ROOMS_B64=%s\n' "${'$'}(printf '%s' "${'$'}rooms" | base64 | tr -d '\n')"
            """.trimIndent()
        )
        if (result.exitStatus != 0) return null

        val values = result.stdout.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        val versionJson = decodeBase64Text(values["VERSION_B64"]) ?: return null
        val roomsJson = decodeBase64Text(values["ROOMS_B64"]) ?: return null
        val version = runCatching { JSONObject(versionJson) }.getOrNull() ?: return null
        if (version.optString("version").isBlank()) return null
        if (version.optString("name").isBlank()) return null
        return parseOpenRoomsJson(roomsJson)
    }

    private fun decodeBase64Text(value: String?): String? {
        val encoded = value?.trim()?.takeIf { BASE64_VALUE.matches(it) } ?: return null
        return runCatching {
            Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private data class RemoteCommandResult(
        val exitStatus: Int,
        val stdout: String,
        val stderr: String
    )

    private fun runRootOrSudo(
        ssh: SSHClient,
        login: String,
        password: String,
        command: String
    ): RemoteCommandResult {
        val isRootLogin = login.trim() == "root"
        val shellCommand = if (isRootLogin) {
            "bash -lc ${shQuote(command)}"
        } else {
            "sudo -S -p '' bash -lc ${shQuote(command)}"
        }

        return ssh.startSession().use { session ->
            val cmd = session.exec(shellCommand)
            if (!isRootLogin) {
                cmd.outputStream.bufferedWriter(Charsets.UTF_8).use { stdin ->
                    stdin.write(password)
                    stdin.newLine()
                    stdin.flush()
                }
            }
            cmd.join()
            RemoteCommandResult(
                exitStatus = cmd.exitStatus ?: -1,
                stdout = cmd.inputStream.bufferedReader().readText(),
                stderr = cmd.errorStream.bufferedReader().readText()
            )
        }
    }

    private fun hasLegacyDeployment(
        ssh: SSHClient,
        login: String,
        password: String
    ): Boolean {
        val result = runRootOrSudo(
            ssh = ssh,
            login = login,
            password = password,
            command = """
                test -f /etc/systemd/system/symposium-server.service ||
                test -d /opt/symposium-server
            """.trimIndent()
        )
        return result.exitStatus == 0
    }

    private fun readRemoteFile(ssh: SSHClient, path: String): String? {
        return ssh.startSession().use { session ->
            val cmd = session.exec("cat ${shQuote(path)}")
            cmd.join()
            val out = cmd.inputStream.bufferedReader().readText()
            if ((cmd.exitStatus ?: 1) == 0) out else null
        }
    }

    private fun readRemoteFilePrivileged(
        ssh: SSHClient,
        login: String,
        password: String,
        path: String
    ): String? {
        val direct = readRemoteFile(ssh, path)
        if (direct != null) return direct

        val result = runRootOrSudo(
            ssh = ssh,
            login = login,
            password = password,
            command = "cat ${shQuote(path)}"
        )
        return if (result.exitStatus == 0) result.stdout else null
    }

    private fun readAdminToken(ssh: SSHClient, login: String, password: String): String? {
        return readRemoteFilePrivileged(ssh, login, password, ADMIN_TOKEN_PATH)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseRelayInfo(json: String, fallbackIp: String): RelayInfo {
        val o = JSONObject(json)
        val host = o.optString("host").takeIf { it.isNotBlank() } ?: fallbackIp
        val httpsPort = o.optInt("httpsPort", 443)
        val pin = o.getString("pin")
        val adminToken = o.optString("adminToken").trim()
        return RelayInfo(host = host, httpsPort = httpsPort, pin = pin, adminToken = adminToken)
    }

    suspend fun observeSshHostKeyPin(serverIp: String): String = withContext(Dispatchers.IO) {
        var observedHostKeyPin: String? = null

        try {
            buildSshClientNoX25519().use { ssh ->
                val verifier = Sha256HostKeyPinVerifier(expectedPin = null) { pin ->
                    observedHostKeyPin = pin
                }
                ssh.addHostKeyVerifier(verifier)
                ssh.connect(serverIp)
            }
        } catch (_: Exception) {
        }

        observedHostKeyPin?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Не удалось получить SSH-ключ сервера")
    }

    private fun requireKnownSshHostKeyPin(expectedPin: String?) {
        require(!expectedPin.isNullOrBlank()) {
            "SSH-ключ сервера ещё не принят. Сначала добавьте сервер и подтвердите его ключ."
        }
    }

    private fun buildSshClientNoX25519(): SSHClient {
        val config = DefaultSecurityProviderConfig().apply {
            setKeyExchangeFactories(
                listOf(
                    ECDHNistP.Factory256(),
                    ECDHNistP.Factory384(),
                    ECDHNistP.Factory521(),
                    DHGexSHA256.Factory(),
                    DHG14.Factory()
                )
            )
        }
        return SSHClient(config).apply {
            setConnectTimeout(20_000)
            setTimeout(30_000)
        }
    }

    private class Sha256HostKeyPinVerifier(
        private val expectedPin: String?,
        private val onObserved: (String) -> Unit
    ) : HostKeyVerifier {

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val pin = sha256PinOf(key.encoded)
            onObserved(pin)

            val expected = expectedPin?.trim()?.takeIf { it.isNotBlank() }

            if (expected == null) return false

            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                pin.toByteArray(Charsets.UTF_8)
            )
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> {
            return mutableListOf()
        }

        private fun sha256PinOf(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val b64 = Base64.encodeToString(digest, Base64.NO_WRAP)
            return "sha256/$b64"
        }
    }

    private class SpkiPinTrustManager(expectedPinRaw: String) : X509TrustManager {
        private val expectedPin = normalizePin(expectedPinRaw)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) {
                throw CertificateException("Empty server certificate chain")
            }

            val peerPins = mutableListOf<String>()
            for (cert in chain) {
                val pin = spkiPin(cert)
                peerPins += pin
                if (secureEquals(pin, expectedPin)) return
            }

            throw CertificateException(
                buildString {
                    append("SPKI pin mismatch.\n")
                    append("Expected: ").append(expectedPin).append('\n')
                    append("Peer pins:\n")
                    peerPins.forEach { append("  ").append(it).append('\n') }
                }
            )
        }

        private fun spkiPin(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            val b64 = Base64.encodeToString(digest, Base64.NO_WRAP)
            return "sha256/$b64"
        }

        private fun secureEquals(a: String, b: String): Boolean {
            return MessageDigest.isEqual(
                a.toByteArray(Charsets.UTF_8),
                b.toByteArray(Charsets.UTF_8)
            )
        }

        companion object {
            private fun normalizePin(pin: String): String {
                val p = pin.trim()
                require(p.startsWith("sha256/")) { "TLS pin must start with sha256/" }
                require(p.length > "sha256/".length) { "TLS pin is empty" }
                return p
            }
        }
    }

    private fun pinnedHttpsClient(
        tlsPin: String,
        callTimeoutSeconds: Long? = null
    ): OkHttpClient {
        val trustManager = SpkiPinTrustManager(tlsPin)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        val builder = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (callTimeoutSeconds != null) {
            builder.callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        }

        return builder.build()
    }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    private fun randomHex(bytes: Int): String {
        val value = ByteArray(bytes)
        SecureRandom().nextBytes(value)
        return value.joinToString("") { "%02x".format(it) }
    }

    private fun randomSecret(bytes: Int = 32): String {
        val value = ByteArray(bytes)
        SecureRandom().nextBytes(value)
        return Base64.encodeToString(value, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private fun generateDeploymentProfile(): DeploymentProfile {
        val root = randomHex(8)
        val userSuffix = randomHex(5)
        val publicPort = 20_000 + SecureRandom().nextInt(35_000)
        var adminPort = 20_000 + SecureRandom().nextInt(35_000)
        while (adminPort == publicPort) adminPort = 20_000 + SecureRandom().nextInt(35_000)
        val iceMin = 30_000 + SecureRandom().nextInt(12_000)
        val dbRoomColumn = "c_${randomHex(7)}"
        var dbKeyColumn = "c_${randomHex(7)}"
        while (dbKeyColumn == dbRoomColumn) dbKeyColumn = "c_${randomHex(7)}"
        return DeploymentProfile(
            appDir = "/opt/.$root",
            serviceName = "w${randomHex(7)}",
            instanceName = "n${randomHex(9)}",
            serviceUser = "u$userSuffix",
            serviceGroup = "g$userSuffix",
            binaryDirName = ".${randomHex(6)}",
            stateDirName = ".${randomHex(6)}",
            identityDirName = ".${randomHex(6)}",
            binaryName = "x${randomHex(8)}",
            databaseName = ".${randomHex(7)}.dat",
            tokenName = ".${randomHex(7)}.key",
            certificateName = ".${randomHex(7)}.crt",
            privateKeyName = ".${randomHex(7)}.pem",
            dbTable = "t_${randomHex(7)}",
            dbRoomColumn = dbRoomColumn,
            dbKeyColumn = dbKeyColumn,
            publicPort = publicPort,
            adminPort = adminPort,
            iceUdpPortMin = iceMin,
            iceUdpPortMax = iceMin + 199,
            firewallTag = "r${randomHex(7)}",
            stagingDir = ".cache/.${randomHex(8)}",
            adminToken = randomSecret()
        )
    }

    private fun renderInstallScript(profile: DeploymentProfile, serverIp: String): String {
        val relayRelease = configuredRelayRelease()
        val replacements = mapOf(
            "@@SERVER_IP@@" to shQuote(serverIp),
            "@@APP_DIR@@" to shQuote(profile.appDir),
            "@@SERVICE_NAME@@" to shQuote(profile.serviceName),
            "@@INSTANCE_NAME@@" to shQuote(profile.instanceName),
            "@@SERVICE_USER@@" to shQuote(profile.serviceUser),
            "@@SERVICE_GROUP@@" to shQuote(profile.serviceGroup),
            "@@BINARY_DIR_NAME@@" to shQuote(profile.binaryDirName),
            "@@STATE_DIR_NAME@@" to shQuote(profile.stateDirName),
            "@@IDENTITY_DIR_NAME@@" to shQuote(profile.identityDirName),
            "@@BINARY_NAME@@" to shQuote(profile.binaryName),
            "@@DATABASE_NAME@@" to shQuote(profile.databaseName),
            "@@TOKEN_NAME@@" to shQuote(profile.tokenName),
            "@@CERTIFICATE_NAME@@" to shQuote(profile.certificateName),
            "@@PRIVATE_KEY_NAME@@" to shQuote(profile.privateKeyName),
            "@@DB_TABLE@@" to shQuote(profile.dbTable),
            "@@DB_ROOM_COLUMN@@" to shQuote(profile.dbRoomColumn),
            "@@DB_KEY_COLUMN@@" to shQuote(profile.dbKeyColumn),
            "@@PUBLIC_PORT@@" to profile.publicPort.toString(),
            "@@ADMIN_PORT@@" to profile.adminPort.toString(),
            "@@ICE_MIN@@" to profile.iceUdpPortMin.toString(),
            "@@ICE_MAX@@" to profile.iceUdpPortMax.toString(),
            "@@FIREWALL_TAG@@" to shQuote(profile.firewallTag),
            "@@ADMIN_TOKEN@@" to shQuote(profile.adminToken),
            "@@RELAY_RELEASE_VERSION@@" to relayRelease.version,
            "@@RELAY_DOWNLOAD_URL@@" to relayRelease.downloadUrl,
            "@@RELAY_SHA256@@" to relayRelease.sha256
        )
        return replacements.entries.fold(ISOLATED_INSTALL_SCRIPT) { script, entry ->
            script.replace(entry.key, entry.value)
        }
    }

    private fun buildRemovalScript(profile: DeploymentProfile): String = """
        set -Eeuo pipefail
        SERVICE=${shQuote(profile.serviceName)}
        APP_DIR=${shQuote(profile.appDir)}
        APP_USER=${shQuote(profile.serviceUser)}
        APP_GROUP=${shQuote(profile.serviceGroup)}
        PUBLIC_PORT=${profile.publicPort}
        ICE_MIN=${profile.iceUdpPortMin}
        ICE_MAX=${profile.iceUdpPortMax}
        FIREWALL_TAG=${shQuote(profile.firewallTag)}
        systemctl stop "${'$'}{SERVICE}.service" 2>/dev/null || true
        systemctl disable "${'$'}{SERVICE}.service" 2>/dev/null || true
        rm -f "/etc/systemd/system/${'$'}{SERVICE}.service"
        systemctl daemon-reload
        rm -rf "${'$'}APP_DIR"
        userdel "${'$'}APP_USER" 2>/dev/null || true
        groupdel "${'$'}APP_GROUP" 2>/dev/null || true
        if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
          ufw --force delete allow "${'$'}PUBLIC_PORT/tcp" >/dev/null 2>&1 || true
          ufw --force delete allow "${'$'}ICE_MIN:${'$'}ICE_MAX/udp" >/dev/null 2>&1 || true
        fi
        if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
          firewall-cmd --permanent --remove-port="${'$'}PUBLIC_PORT/tcp" >/dev/null 2>&1 || true
          firewall-cmd --permanent --remove-port="${'$'}ICE_MIN-${'$'}ICE_MAX/udp" >/dev/null 2>&1 || true
          firewall-cmd --reload >/dev/null 2>&1 || true
        fi
    """.trimIndent()

    companion object {
        private const val LEGACY_INSTALL_REMOTE_DIR = ".symposium-installer"
        private const val RELAY_INFO_PATH = "/opt/symposium-server/relay-info.json"
        private const val ADMIN_TOKEN_PATH = "/opt/symposium-server/admin-token"
        private const val OPEN_ROOMS_DB_PATH = "/opt/symposium-server/open_rooms.db"
        private const val SERVER_BIN_ASSET_PATH = "symposium/symposium-server-linux-amd64"
        private const val RELAY_DOWNLOAD_FAILED_MARKER = "RELAY_DOWNLOAD_FAILED"
        private const val EXISTING_INSTALL_FOUND_MARKER = "EXISTING_INSTALL_FOUND"
        private const val EXISTING_DEPLOYMENT_UNHEALTHY_EXIT_CODE = 42
        private const val INSTALL_IN_PROGRESS_MARKER_PATH = "/run/.network-worker-bootstrap.pending"
        private const val INSTALL_MARKER_MAX_AGE_SECONDS = 30 * 60
        private const val UPLOAD_CHUNK_SIZE = 16 * 1024
        private const val MAX_UPLOAD_RETRIES = 10
        private const val MAX_SSH_REQUEST_RETRIES = 4
        private const val MAX_HTTP_REQUEST_RETRIES = 4
        private const val NETWORK_RETRY_BASE_DELAY_MS = 750L
        private const val RELAY_VERSION_TIMEOUT_SECONDS = 8L
        private const val RELAY_VERSION_TIMEOUT_MS = RELAY_VERSION_TIMEOUT_SECONDS * 1000L
        private const val UPLOAD_PROGRESS_STEP_PERCENT = 5
        private val DISCOVERABLE_UNIT_PATH =
            Regex("^/etc/systemd/system/[A-Za-z0-9_.@-]+\\.service$")
        private val SAFE_SYSTEMD_NAME = Regex("^[A-Za-z0-9_.@-]{1,128}$")
        private val SAFE_ACCOUNT_NAME = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,30}$")
        private val SAFE_DB_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")
        private val SAFE_ABSOLUTE_PATH = Regex("^/[A-Za-z0-9._/-]+$")
        private val SAFE_PATH_COMPONENT = Regex("^[A-Za-z0-9._-]+$")
        private val BASE64_VALUE = Regex("^[A-Za-z0-9+/]+={0,2}$")

        private val RANDOMIZED_UNIT_DISCOVERY_SCRIPT = """
            set -e
            for unit in /etc/systemd/system/*.service; do
              [ -f "${'$'}unit" ] || continue
              grep -Fq -- '--admin-on-public=true' "${'$'}unit" || continue
              grep -Fq -- '--rooms-db' "${'$'}unit" || continue
              grep -Fq -- '--db-table' "${'$'}unit" || continue
              grep -Fq -- '--db-room-column' "${'$'}unit" || continue
              grep -Fq -- '--db-key-column' "${'$'}unit" || continue
              grep -Fq -- '--admin-token-file' "${'$'}unit" || continue
              grep -Fq -- '--tls-cert' "${'$'}unit" || continue
              grep -Fq -- '--tls-key' "${'$'}unit" || continue
              grep -Fq -- '--ice-udp-port-min' "${'$'}unit" || continue
              grep -Fq -- '--ice-udp-port-max' "${'$'}unit" || continue
              printf '%s\n' "${'$'}unit"
            done
        """.trimIndent()

        private val LEGACY_REMOVAL_SCRIPT = """
            set -e
            systemctl stop symposium-server.service || true
            systemctl disable symposium-server.service || true
            rm -f /etc/systemd/system/symposium-server.service
            systemctl daemon-reload
            rm -rf /opt/symposium-server
            userdel symposium 2>/dev/null || true
            groupdel symposium 2>/dev/null || true
            rm -f /etc/nginx/sites-enabled/symposium-relay.conf
            rm -f /etc/nginx/sites-available/symposium-relay.conf
            rm -f /etc/nginx/sites-enabled/symposium.conf
            rm -f /etc/nginx/sites-available/symposium.conf
            rm -f /etc/nginx/conf.d/symposium_ws_map.conf
            nginx -t >/dev/null 2>&1 && systemctl reload nginx || true
        """.trimIndent()

        private val ISOLATED_INSTALL_SCRIPT = """
            #!/usr/bin/env bash
            set -Eeuo pipefail
            log(){ echo "[${'$'}(date +'%F %T')] ${'$'}*"; }
            die(){ echo "ERROR: ${'$'}*" >&2; exit 1; }
            need_cmd(){ command -v "${'$'}1" >/dev/null 2>&1; }
            [ "${'$'}{EUID:-1}" -eq 0 ] || die "installer must be run as root"

            SERVER_IP=@@SERVER_IP@@
            APP_DIR=@@APP_DIR@@
            SERVICE_NAME=@@SERVICE_NAME@@
            INSTANCE_NAME=@@INSTANCE_NAME@@
            APP_USER=@@SERVICE_USER@@
            APP_GROUP=@@SERVICE_GROUP@@
            BINARY_DIR_NAME=@@BINARY_DIR_NAME@@
            STATE_DIR_NAME=@@STATE_DIR_NAME@@
            IDENTITY_DIR_NAME=@@IDENTITY_DIR_NAME@@
            BINARY_NAME=@@BINARY_NAME@@
            DATABASE_NAME=@@DATABASE_NAME@@
            TOKEN_NAME=@@TOKEN_NAME@@
            CERTIFICATE_NAME=@@CERTIFICATE_NAME@@
            PRIVATE_KEY_NAME=@@PRIVATE_KEY_NAME@@
            DB_TABLE=@@DB_TABLE@@
            DB_ROOM_COLUMN=@@DB_ROOM_COLUMN@@
            DB_KEY_COLUMN=@@DB_KEY_COLUMN@@
            PUBLIC_PORT=@@PUBLIC_PORT@@
            ADMIN_PORT=@@ADMIN_PORT@@
            ICE_MIN=@@ICE_MIN@@
            ICE_MAX=@@ICE_MAX@@
            FIREWALL_TAG=@@FIREWALL_TAG@@
            ADMIN_TOKEN=@@ADMIN_TOKEN@@
            SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
            RELAY_RELEASE_VERSION="@@RELAY_RELEASE_VERSION@@"
            RELAY_DOWNLOAD_URL="${'$'}{SYMPOSIUM_RELAY_DOWNLOAD_URL:-@@RELAY_DOWNLOAD_URL@@}"
            RELAY_EXPECTED_SHA256="@@RELAY_SHA256@@"
            LOCAL_BINARY="${'$'}SCRIPT_DIR/${'$'}BINARY_NAME.src"
            DOWNLOADED_BINARY="${'$'}SCRIPT_DIR/${'$'}BINARY_NAME.download"
            SERVER_BINARY_SOURCE=""
            KEEP_STAGING=0
            BINARY_DIR="${'$'}APP_DIR/${'$'}BINARY_DIR_NAME"
            STATE_DIR="${'$'}APP_DIR/${'$'}STATE_DIR_NAME"
            IDENTITY_DIR="${'$'}APP_DIR/${'$'}IDENTITY_DIR_NAME"
            DATABASE_PATH="${'$'}STATE_DIR/${'$'}DATABASE_NAME"
            TOKEN_PATH="${'$'}IDENTITY_DIR/${'$'}TOKEN_NAME"
            CERTIFICATE_PATH="${'$'}IDENTITY_DIR/${'$'}CERTIFICATE_NAME"
            PRIVATE_KEY_PATH="${'$'}IDENTITY_DIR/${'$'}PRIVATE_KEY_NAME"

            cleanup(){
              if [ "${'$'}KEEP_STAGING" = "1" ]; then
                return 0
              fi
              rm -rf "${'$'}SCRIPT_DIR" 2>/dev/null || true
            }
            trap cleanup EXIT

            need_cmd flock || die "required lock utility is unavailable"
            need_cmd sha256sum || die "required checksum utility is unavailable"
            exec 9>/run/lock/.network-worker-bootstrap.lock
            flock -w 300 9 || die "another installer is still running"

            EXISTING_UNIT=""
            for unit in /etc/systemd/system/*.service; do
              [ -f "${'$'}unit" ] || continue
              grep -Fq -- '--admin-on-public=true' "${'$'}unit" || continue
              grep -Fq -- '--rooms-db' "${'$'}unit" || continue
              grep -Fq -- '--db-table' "${'$'}unit" || continue
              grep -Fq -- '--db-room-column' "${'$'}unit" || continue
              grep -Fq -- '--db-key-column' "${'$'}unit" || continue
              grep -Fq -- '--admin-token-file' "${'$'}unit" || continue
              grep -Fq -- '--tls-cert' "${'$'}unit" || continue
              grep -Fq -- '--tls-key' "${'$'}unit" || continue
              EXISTING_UNIT="${'$'}unit"
              break
            done
            if [ -n "${'$'}EXISTING_UNIT" ]; then
              echo "EXISTING_INSTALL_FOUND" >&2
              exit 42
            fi

            verify_server_binary() {
              local path="${'$'}1"
              local actual
              [ -s "${'$'}path" ] || return 1
              actual="${'$'}(sha256sum "${'$'}path")"
              actual="${'$'}{actual%% *}"
              [ "${'$'}actual" = "${'$'}RELAY_EXPECTED_SHA256" ]
            }

            download_release_binary() {
              local tmp="${'$'}{DOWNLOADED_BINARY}.part"
              rm -f "${'$'}DOWNLOADED_BINARY" "${'$'}tmp"
              log "Downloading SymposiumRelay ${'$'}RELAY_RELEASE_VERSION from GitHub Release"

              set +e
              curl -fL --show-error --connect-timeout 15 --max-time 900 \
                -o "${'$'}tmp" "${'$'}RELAY_DOWNLOAD_URL"
              local rc=${'$'}?
              set -e

              if [ "${'$'}rc" -eq 0 ] && verify_server_binary "${'$'}tmp"; then
                chmod 0750 "${'$'}tmp"
                mv -f "${'$'}tmp" "${'$'}DOWNLOADED_BINARY"
                SERVER_BINARY_SOURCE="${'$'}DOWNLOADED_BINARY"
                log "GitHub Release download complete"
                return 0
              fi

              rm -f "${'$'}tmp" "${'$'}DOWNLOADED_BINARY"
              log "GitHub Release download or integrity verification failed"
              return 1
            }

            select_server_binary() {
              if [ "${'$'}{RELAY_FORCE_LOCAL_BINARY:-0}" = "1" ]; then
                [ -s "${'$'}LOCAL_BINARY" ] || die "RELAY_DOWNLOAD_FAILED: app fallback binary is not available"
                verify_server_binary "${'$'}LOCAL_BINARY" || die "RELAY_DOWNLOAD_FAILED: app fallback binary failed integrity verification"
                SERVER_BINARY_SOURCE="${'$'}LOCAL_BINARY"
                log "Using SymposiumRelay binary uploaded by the app"
                return 0
              fi

              if download_release_binary; then
                return 0
              fi

              KEEP_STAGING=1
              die "RELAY_DOWNLOAD_FAILED: GitHub Release download failed"
            }

            log "[1/8] Detect OS"
            [ -r /etc/os-release ] || die "unsupported operating system"
            . /etc/os-release
            case "${'$'}{ID:-}" in debian|ubuntu) ;; *) die "unsupported operating system" ;; esac

            log "[2/8] Install runtime dependencies"
            export DEBIAN_FRONTEND=noninteractive
            apt-get -o Dpkg::Use-Pty=0 -o Acquire::Retries=3 -o DPkg::Lock::Timeout=300 update
            apt-get -o Dpkg::Use-Pty=0 -o Acquire::Retries=3 -o DPkg::Lock::Timeout=300 install -y ca-certificates curl openssl

            select_server_binary

            systemctl stop "${'$'}SERVICE_NAME.service" 2>/dev/null || true
            port_busy(){ ss -ltnH 2>/dev/null | awk '{print ${'$'}4}' | grep -Eq "(^|:)${'$'}1${'$'}"; }
            if port_busy "${'$'}PUBLIC_PORT"; then die "selected public TCP port is already in use"; fi
            if port_busy "${'$'}ADMIN_PORT"; then die "selected admin TCP port is already in use"; fi

            log "[3/8] Configure isolated firewall ports"
            if need_cmd ufw && ufw status 2>/dev/null | grep -q '^Status: active'; then
              ufw allow "${'$'}PUBLIC_PORT/tcp" comment "${'$'}FIREWALL_TAG" >/dev/null
              ufw allow "${'$'}ICE_MIN:${'$'}ICE_MAX/udp" comment "${'$'}FIREWALL_TAG" >/dev/null
            elif need_cmd firewall-cmd && firewall-cmd --state >/dev/null 2>&1; then
              firewall-cmd --permanent --add-port="${'$'}PUBLIC_PORT/tcp" >/dev/null
              firewall-cmd --permanent --add-port="${'$'}ICE_MIN-${'$'}ICE_MAX/udp" >/dev/null
              firewall-cmd --reload >/dev/null
            else
              log "No managed firewall is active; no global firewall configuration changed"
            fi

            log "[4/8] Create isolated identity"
            getent group "${'$'}APP_GROUP" >/dev/null 2>&1 || groupadd --system "${'$'}APP_GROUP"
            id -u "${'$'}APP_USER" >/dev/null 2>&1 || useradd --system --gid "${'$'}APP_GROUP" --home-dir "${'$'}APP_DIR" --no-create-home --shell /usr/sbin/nologin "${'$'}APP_USER"
            install -d -o "${'$'}APP_USER" -g "${'$'}APP_GROUP" -m 0750 "${'$'}APP_DIR"
            install -d -o "${'$'}APP_USER" -g "${'$'}APP_GROUP" -m 0750 "${'$'}BINARY_DIR" "${'$'}STATE_DIR"
            install -d -o "${'$'}APP_USER" -g "${'$'}APP_GROUP" -m 0700 "${'$'}IDENTITY_DIR"
            umask 077
            printf '%s\n' "${'$'}ADMIN_TOKEN" > "${'$'}TOKEN_PATH"

            log "[5/8] Generate pinned TLS identity"
            openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 825 \
              -subj "/CN=${'$'}SERVER_IP" \
              -addext "subjectAltName=IP:${'$'}SERVER_IP" \
              -keyout "${'$'}PRIVATE_KEY_PATH" -out "${'$'}CERTIFICATE_PATH" >/dev/null 2>&1
            PIN="${'$'}(openssl x509 -in "${'$'}CERTIFICATE_PATH" -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl base64 -A)"
            PIN="sha256/${'$'}PIN"

            log "[6/8] Install isolated worker"
            [ -s "${'$'}SERVER_BINARY_SOURCE" ] || die "selected server binary is missing"
            install -o "${'$'}APP_USER" -g "${'$'}APP_GROUP" -m 0750 "${'$'}SERVER_BINARY_SOURCE" "${'$'}BINARY_DIR/${'$'}BINARY_NAME"
            install -o "${'$'}APP_USER" -g "${'$'}APP_GROUP" -m 0600 /dev/null "${'$'}DATABASE_PATH"
            chown "${'$'}APP_USER:${'$'}APP_GROUP" "${'$'}TOKEN_PATH" "${'$'}CERTIFICATE_PATH" "${'$'}PRIVATE_KEY_PATH"
            chmod 600 "${'$'}TOKEN_PATH" "${'$'}PRIVATE_KEY_PATH"
            chmod 644 "${'$'}CERTIFICATE_PATH"

            cat > "/etc/systemd/system/${'$'}SERVICE_NAME.service" <<UNIT
            [Unit]
            Description=Managed network worker
            After=network-online.target
            Wants=network-online.target

            [Service]
            Type=simple
            WorkingDirectory=${'$'}APP_DIR
            ExecStart=${'$'}BINARY_DIR/${'$'}BINARY_NAME --instance-name ${'$'}INSTANCE_NAME --addr :${'$'}PUBLIC_PORT --admin-addr 127.0.0.1:${'$'}ADMIN_PORT --admin-on-public=true --public-base-url https://${'$'}SERVER_IP:${'$'}PUBLIC_PORT --public-ip ${'$'}SERVER_IP --nat1to1 ${'$'}SERVER_IP --rooms-db ${'$'}DATABASE_PATH --db-table ${'$'}DB_TABLE --db-room-column ${'$'}DB_ROOM_COLUMN --db-key-column ${'$'}DB_KEY_COLUMN --admin-token-file ${'$'}TOKEN_PATH --tls-cert ${'$'}CERTIFICATE_PATH --tls-key ${'$'}PRIVATE_KEY_PATH --ice-udp-port-min ${'$'}ICE_MIN --ice-udp-port-max ${'$'}ICE_MAX
            Restart=always
            RestartSec=2
            User=${'$'}APP_USER
            Group=${'$'}APP_GROUP
            NoNewPrivileges=true
            PrivateTmp=true
            ProtectSystem=strict
            ProtectHome=true
            PrivateDevices=true
            ReadWritePaths=${'$'}APP_DIR
            CapabilityBoundingSet=
            AmbientCapabilities=
            LockPersonality=true
            MemoryDenyWriteExecute=true
            SystemCallArchitectures=native

            [Install]
            WantedBy=multi-user.target
            UNIT

            log "[7/8] Start isolated service"
            systemctl daemon-reload
            systemctl enable "${'$'}SERVICE_NAME.service" >/dev/null
            systemctl restart "${'$'}SERVICE_NAME.service"
            sleep 2
            systemctl is-active --quiet "${'$'}SERVICE_NAME.service" || die "service failed to start"

            log "[8/8] Verify pinned HTTPS endpoint"
            curl -kfsS --connect-timeout 10 -H "X-Relay-Key: ${'$'}ADMIN_TOKEN" "https://127.0.0.1:${'$'}PUBLIC_PORT/admin/version" >/dev/null
            echo "RELAY_TLS_PIN=${'$'}PIN"
            echo "Installation finished."
        """.trimIndent()

        private val INSTALL_SCRIPT = """
            #!/usr/bin/env bash
            set -Eeuo pipefail
            trap 'rc=${'$'}?; echo "ERROR: line ${'$'}LINENO: ${'$'}BASH_COMMAND" >&2; exit ${'$'}rc' ERR

            log(){ echo "[${'$'}(date +'%F %T')] ${'$'}*"; }
            die(){ echo "ERROR: ${'$'}*" >&2; exit 1; }
            need_cmd(){ command -v "${'$'}1" >/dev/null 2>&1; }

            if [ "${'$'}{EUID:-0}" -ne 0 ]; then
               echo "ERROR: installer must be run as root" >&2
               exit 1
            fi

            SERVER_IP="${'$'}{1:-}"
            if [ -z "${'$'}SERVER_IP" ]; then
              echo "Usage: ${'$'}0 SERVER_IP"
              exit 1
            fi

            APP_DIR="/opt/symposium-server"
            APP_NAME="symposium-server"
            APP_USER="symposium"
            APP_GROUP="symposium"
            ROOMS_DB="${'$'}{APP_DIR}/open_rooms.db"
            ADMIN_TOKEN_FILE="${'$'}{APP_DIR}/admin-token"
            SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
            RELAY_DOWNLOAD_URL="${'$'}{SYMPOSIUM_RELAY_DOWNLOAD_URL:-${BuildConfig.RELAY_BINARY_URL}}"
            LOCAL_BINARY="${'$'}{SCRIPT_DIR}/symposium-server"
            DOWNLOADED_BINARY="${'$'}{SCRIPT_DIR}/symposium-server.download"
            SERVER_BINARY_SOURCE=""
            ICE_UDP_PORT_MIN="${'$'}{SYMPOSIUM_ICE_UDP_PORT_MIN:-32768}"
            ICE_UDP_PORT_MAX="${'$'}{SYMPOSIUM_ICE_UDP_PORT_MAX:-60999}"
            ICE_UDP_PORT_RANGE="${'$'}{ICE_UDP_PORT_MIN}:${'$'}{ICE_UDP_PORT_MAX}"
            FIREWALLD_ICE_UDP_PORT_RANGE="${'$'}{ICE_UDP_PORT_MIN}-${'$'}{ICE_UDP_PORT_MAX}"

            TLS_DIR="/etc/symposium/tls"
            TLS_KEY="${'$'}{TLS_DIR}/relay.key"
            TLS_CRT="${'$'}{TLS_DIR}/relay.crt"

            export DEBIAN_FRONTEND=noninteractive

            APT_OPTS=(
              -o Dpkg::Use-Pty=0
              -o Acquire::Retries=3
              -o DPkg::Lock::Timeout=300
            )

            APT_LOCKS=(
              /var/lib/dpkg/lock-frontend
              /var/lib/dpkg/lock
              /var/lib/apt/lists/lock
              /var/lib/apt/archives/lock
            )

            apt_quiet_services() {
              if need_cmd systemctl; then
                systemctl stop apt-daily.service apt-daily-upgrade.service unattended-upgrades 2>/dev/null || true
                systemctl stop apt-daily.timer apt-daily-upgrade.timer 2>/dev/null || true
                systemctl mask apt-daily.service apt-daily-upgrade.service unattended-upgrades 2>/dev/null || true
              fi
            }

            wait_apt_locks() {
              local timeout="${'$'}{1:-300}"
              local start_ts now
              start_ts="${'$'}(date +%s)"

              while true; do
                local locked=0
                local f=""
                for f in "${'$'}{APT_LOCKS[@]}"; do
                  if fuser "${'$'}f" >/dev/null 2>&1; then
                    locked=1
                    break
                  fi
                done

                if [ "${'$'}locked" -eq 0 ]; then
                  if dpkg --audit 2>/dev/null | grep -q .; then
                    log "dpkg interrupted earlier; running dpkg --configure -a"
                    dpkg --configure -a || true
                  fi
                  return 0
                fi

                now="${'$'}(date +%s)"
                if [ "${'$'}((now - start_ts))" -ge "${'$'}timeout" ]; then
                  log "APT lock still held after ${'$'}{timeout}s (example lock: ${'$'}f)"
                  ps aux | egrep 'apt|dpkg|unattended|apt.systemd.daily' | grep -v egrep || true
                  return 1
                fi

                log "APT locked by another process; waiting..."
                sleep 3
              done
            }

            apt_run() {
              local tries=30
              local i=1
              local out rc

              while true; do
                apt_quiet_services
                wait_apt_locks 300 || die "APT is locked too long"

                set +e
                out="${'$'}(apt-get "${'$'}{APT_OPTS[@]}" "${'$'}@" 2>&1)"
                rc=${'$'}?
                set -e

                if [ "${'$'}rc" -eq 0 ]; then
                  [ -n "${'$'}out" ] && echo "${'$'}out"
                  return 0
                fi

                if echo "${'$'}out" | grep -qiE 'Could not get lock|Unable to acquire|Unable to lock|Waiting for cache lock|is another process using it|Could not open lock file'; then
                  log "APT lock contention (retry ${'$'}i/${'$'}tries)"
                  sleep 3
                  i="${'$'}((i+1))"
                  if [ "${'$'}i" -gt "${'$'}tries" ]; then
                    echo "${'$'}out"
                    die "APT still locked after ${'$'}tries retries"
                  fi
                  continue
                fi

                echo "${'$'}out"
                return "${'$'}rc"
              done
            }

            download_with_retries() {
              local url="${'$'}1"
              local dest="${'$'}2"
              local tmp="${'$'}{dest}.part"
              local tries=1
              local i=1
              local rc=1

              rm -f "${'$'}dest" "${'$'}tmp"

              while [ "${'$'}i" -le "${'$'}tries" ]; do
                log "Downloading SymposiumRelay from link (attempt ${'$'}i/${'$'}tries)"

                set +e
                curl \
                  -fL \
                  --show-error \
                  --connect-timeout 15 \
                  --max-time 900 \
                  -o "${'$'}tmp" \
                  "${'$'}url"
                rc=${'$'}?
                set -e

                if [ "${'$'}rc" -eq 0 ] && [ -s "${'$'}tmp" ]; then
                  chmod 0750 "${'$'}tmp"
                  mv -f "${'$'}tmp" "${'$'}dest"
                  log "Link download complete"
                  return 0
                fi

                if [ "${'$'}rc" -eq 0 ]; then
                  log "Link download failed: downloaded file is empty"
                else
                  log "Link download failed with curl exit ${'$'}rc"
                fi

                i="${'$'}((i+1))"
              done

              rm -f "${'$'}tmp"
              return 1
            }

            select_server_binary() {
              if [ "${'$'}{SYMPOSIUM_FORCE_LOCAL_BINARY:-0}" != "1" ]; then
                rm -f "${'$'}LOCAL_BINARY"

                if download_with_retries "${'$'}RELAY_DOWNLOAD_URL" "${'$'}DOWNLOADED_BINARY"; then
                  SERVER_BINARY_SOURCE="${'$'}DOWNLOADED_BINARY"
                  return 0
                fi

                log "Link download failed; requesting app fallback binary"
                die "RELAY_DOWNLOAD_FAILED: link download failed"
              fi

              if [ -s "${'$'}LOCAL_BINARY" ]; then
                SERVER_BINARY_SOURCE="${'$'}LOCAL_BINARY"
                log "Using SymposiumRelay binary uploaded by the app"
                return 0
              fi

              die "RELAY_DOWNLOAD_FAILED: app fallback binary is not available"
            }

            gen_tls_for_ip() {
              mkdir -p "${'$'}{TLS_DIR}"
              chmod 700 "${'$'}{TLS_DIR}"

              if [ -f "${'$'}{TLS_KEY}" ] && [ -f "${'$'}{TLS_CRT}" ]; then
                log "TLS already exists: ${'$'}{TLS_CRT}"
              else
                log "Generating self-signed TLS cert for IP ${'$'}{SERVER_IP}"

                openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${'$'}{TLS_KEY}"
                chmod 600 "${'$'}{TLS_KEY}"

                cat > "${'$'}{TLS_DIR}/openssl.cnf" <<EOF
            [req]
            distinguished_name = dn
            x509_extensions = v3
            prompt = no

            [dn]
            CN = ${'$'}{SERVER_IP}

            [v3]
            subjectAltName = @alt
            keyUsage = digitalSignature, keyEncipherment
            extendedKeyUsage = serverAuth

            [alt]
            IP.1 = ${'$'}{SERVER_IP}
            EOF

                openssl req -x509 -new -key "${'$'}{TLS_KEY}" -sha256 -days 825 -out "${'$'}{TLS_CRT}" -config "${'$'}{TLS_DIR}/openssl.cnf"
                chmod 644 "${'$'}{TLS_CRT}"
              fi
            }

            calc_spki_pin() {
              local b64
              b64="${'$'}(
                openssl x509 -in "${'$'}{TLS_CRT}" -pubkey -noout \
                  | openssl pkey -pubin -outform DER \
                  | openssl dgst -sha256 -binary \
                  | openssl enc -base64 -A
              )"
              echo "sha256/${'$'}{b64}"
            }

            is_uint() {
              case "${'$'}1" in
                ''|*[!0-9]*) return 1 ;;
                *) return 0 ;;
              esac
            }

            validate_udp_range() {
              if ! is_uint "${'$'}ICE_UDP_PORT_MIN" || ! is_uint "${'$'}ICE_UDP_PORT_MAX"; then
                die "Invalid ICE UDP port range: ${'$'}{ICE_UDP_PORT_MIN}-${'$'}{ICE_UDP_PORT_MAX}"
              fi

              if [ "${'$'}ICE_UDP_PORT_MIN" -lt 1 ] || [ "${'$'}ICE_UDP_PORT_MAX" -gt 65535 ] || [ "${'$'}ICE_UDP_PORT_MAX" -lt "${'$'}ICE_UDP_PORT_MIN" ]; then
                die "Invalid ICE UDP port range: ${'$'}{ICE_UDP_PORT_MIN}-${'$'}{ICE_UDP_PORT_MAX}"
              fi

              ICE_UDP_PORT_RANGE="${'$'}{ICE_UDP_PORT_MIN}:${'$'}{ICE_UDP_PORT_MAX}"
              FIREWALLD_ICE_UDP_PORT_RANGE="${'$'}{ICE_UDP_PORT_MIN}-${'$'}{ICE_UDP_PORT_MAX}"
            }

            firewall_warn() {
              log "Firewall warning: ${'$'}*"
            }

            ensure_ufw_ports() {
              if ! need_cmd ufw; then
                return 1
              fi

              if ! ufw status 2>/dev/null | grep -qi 'Status: active'; then
                return 1
              fi

              log "Detected active UFW; allowing SymposiumRelay ports"
              ufw allow 443/tcp >/dev/null || firewall_warn "failed to allow 443/tcp via UFW"
              ufw allow "${'$'}{ICE_UDP_PORT_RANGE}/udp" >/dev/null || firewall_warn "failed to allow ${'$'}{ICE_UDP_PORT_RANGE}/udp via UFW"
              return 0
            }

            ensure_firewalld_ports() {
              if ! need_cmd firewall-cmd; then
                return 1
              fi

              if ! firewall-cmd --state >/dev/null 2>&1; then
                return 1
              fi

              log "Detected active firewalld; allowing SymposiumRelay ports"
              firewall-cmd --permanent --add-port=443/tcp >/dev/null || firewall_warn "failed to allow 443/tcp via firewalld"
              firewall-cmd --permanent --add-port="${'$'}{FIREWALLD_ICE_UDP_PORT_RANGE}/udp" >/dev/null || firewall_warn "failed to allow ${'$'}{FIREWALLD_ICE_UDP_PORT_RANGE}/udp via firewalld"
              firewall-cmd --reload >/dev/null || firewall_warn "failed to reload firewalld"
              return 0
            }

            nft_input_chains() {
              nft -a list ruleset 2>/dev/null | awk '
                /^table / { family=${'$'}2; table=${'$'}3 }
                /^[[:space:]]*chain / { chain=${'$'}2 }
                /hook input/ { print family, table, chain }
              '
            }

            ensure_nft_ports() {
              if ! need_cmd nft; then
                return 1
              fi

              local chains
              chains="${'$'}(nft_input_chains || true)"
              if [ -z "${'$'}chains" ]; then
                return 1
              fi

              log "Detected nftables input chains; allowing SymposiumRelay ports"

              local family table chain
              while read -r family table chain; do
                [ -n "${'$'}family" ] || continue
                [ -n "${'$'}table" ] || continue
                [ -n "${'$'}chain" ] || continue

                if ! nft list chain "${'$'}family" "${'$'}table" "${'$'}chain" 2>/dev/null | grep -Fq 'symposium-relay-https'; then
                  nft insert rule "${'$'}family" "${'$'}table" "${'$'}chain" tcp dport 443 accept comment "symposium-relay-https" 2>/dev/null                     || firewall_warn "failed to add nftables rule for 443/tcp in ${'$'}family ${'$'}table ${'$'}chain"
                fi

                if ! nft list chain "${'$'}family" "${'$'}table" "${'$'}chain" 2>/dev/null | grep -Fq 'symposium-relay-ice-udp-range'; then
                  nft insert rule "${'$'}family" "${'$'}table" "${'$'}chain" udp dport "${'$'}{ICE_UDP_PORT_MIN}-${'$'}{ICE_UDP_PORT_MAX}" accept comment "symposium-relay-ice-udp-range" 2>/dev/null                     || firewall_warn "failed to add nftables rule for ${'$'}{ICE_UDP_PORT_MIN}-${'$'}{ICE_UDP_PORT_MAX}/udp in ${'$'}family ${'$'}table ${'$'}chain"
                fi
              done <<< "${'$'}chains"

              return 0
            }

            iptables_input_active() {
              if ! need_cmd iptables; then
                return 1
              fi

              local rules
              rules="${'$'}(iptables -S INPUT 2>/dev/null || true)"
              [ -n "${'$'}rules" ] || return 1

              echo "${'$'}rules" | grep -qE '^-P INPUT (DROP|REJECT)|^-A INPUT '
            }

            ensure_one_iptables_rule() {
              local bin="${'$'}1"
              local proto="${'$'}2"
              local port_expr="${'$'}3"
              local match_arg="--dport"

              if ! need_cmd "${'$'}bin"; then
                return 0
              fi

              if echo "${'$'}port_expr" | grep -q ':'; then
                match_arg="--dports"
                "${'$'}bin" -C INPUT -p "${'$'}proto" -m multiport "${'$'}match_arg" "${'$'}port_expr" -j ACCEPT >/dev/null 2>&1                   || "${'$'}bin" -I INPUT -p "${'$'}proto" -m multiport "${'$'}match_arg" "${'$'}port_expr" -j ACCEPT >/dev/null 2>&1                   || firewall_warn "failed to add ${'$'}bin rule for ${'$'}port_expr/${'$'}proto"
                return 0
              fi

              "${'$'}bin" -C INPUT -p "${'$'}proto" "${'$'}match_arg" "${'$'}port_expr" -j ACCEPT >/dev/null 2>&1                 || "${'$'}bin" -I INPUT -p "${'$'}proto" "${'$'}match_arg" "${'$'}port_expr" -j ACCEPT >/dev/null 2>&1                 || firewall_warn "failed to add ${'$'}bin rule for ${'$'}port_expr/${'$'}proto"
            }

            persist_iptables_if_possible() {
              if need_cmd netfilter-persistent; then
                netfilter-persistent save >/dev/null 2>&1 || firewall_warn "failed to persist iptables rules via netfilter-persistent"
                return 0
              fi

              if [ -d /etc/iptables ] && need_cmd iptables-save; then
                iptables-save > /etc/iptables/rules.v4 2>/dev/null || firewall_warn "failed to persist IPv4 iptables rules"
                if need_cmd ip6tables-save; then
                  ip6tables-save > /etc/iptables/rules.v6 2>/dev/null || firewall_warn "failed to persist IPv6 iptables rules"
                fi
              fi
            }

            ensure_iptables_ports() {
              if ! iptables_input_active; then
                return 1
              fi

              log "Detected iptables input rules; allowing SymposiumRelay ports"
              ensure_one_iptables_rule iptables tcp 443
              ensure_one_iptables_rule iptables udp "${'$'}ICE_UDP_PORT_RANGE"
              ensure_one_iptables_rule ip6tables tcp 443
              ensure_one_iptables_rule ip6tables udp "${'$'}ICE_UDP_PORT_RANGE"
              persist_iptables_if_possible
              return 0
            }

            ensure_firewall_ports() {
              log "Checking host firewall rules"

              local detected=0

              if ensure_ufw_ports; then
                detected=1
              fi

              if ensure_firewalld_ports; then
                detected=1
              fi

              if [ "${'$'}detected" -eq 0 ]; then
                if ensure_nft_ports; then
                  detected=1
                fi
              fi

              if [ "${'$'}detected" -eq 0 ]; then
                if ensure_iptables_ports; then
                  detected=1
                fi
              fi

              if [ "${'$'}detected" -eq 0 ]; then
                log "No active host firewall manager detected"
              else
                log "Firewall ports ensured: 443/tcp and ${'$'}{ICE_UDP_PORT_RANGE}/udp"
              fi
            }

            ensure_symposium_user() {
              if ! getent group "${'$'}{APP_GROUP}" >/dev/null 2>&1; then
                groupadd --system "${'$'}{APP_GROUP}"
              fi

              if ! id -u "${'$'}{APP_USER}" >/dev/null 2>&1; then
                useradd \
                  --system \
                  --gid "${'$'}{APP_GROUP}" \
                  --home-dir "${'$'}{APP_DIR}" \
                  --no-create-home \
                  --shell /usr/sbin/nologin \
                  "${'$'}{APP_USER}"
              fi
            }

            prepare_app_dir() {
              install -d -o "${'$'}{APP_USER}" -g "${'$'}{APP_GROUP}" -m 0750 "${'$'}{APP_DIR}"
            }

            ensure_admin_token() {
              prepare_app_dir

              if [ -s "${'$'}{ADMIN_TOKEN_FILE}" ]; then
                ADMIN_TOKEN="${'$'}(tr -d '\r\n' < "${'$'}{ADMIN_TOKEN_FILE}")"
              else
                ADMIN_TOKEN="${'$'}(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
                umask 077
                printf '%s\n' "${'$'}{ADMIN_TOKEN}" > "${'$'}{ADMIN_TOKEN_FILE}"
              fi

              chown "${'$'}{APP_USER}:${'$'}{APP_GROUP}" "${'$'}{ADMIN_TOKEN_FILE}"
              chmod 600 "${'$'}{ADMIN_TOKEN_FILE}"
            }

            log "[1/8] Detect OS"
            ID="unknown"
            VERSION_CODENAME=""
            if [ -f /etc/os-release ]; then
              . /etc/os-release
              ID="${'$'}{ID:-unknown}"
              VERSION_CODENAME="${'$'}{VERSION_CODENAME:-}"
            fi
            log "Detected OS: ${'$'}{ID} ${'$'}{VERSION_CODENAME}"

            if [ "${'$'}ID" != "debian" ] && [ "${'$'}ID" != "ubuntu" ]; then
              die "Unsupported OS: ${'$'}ID"
            fi

            apt_quiet_services

            log "[2/8] Install runtime deps"
            apt_run update
            apt_run install -y ca-certificates curl openssl

            validate_udp_range
            log "ICE UDP port range: ${'$'}{ICE_UDP_PORT_MIN}-${'$'}{ICE_UDP_PORT_MAX}/udp"

            log "[3/8] Configure firewall"
            ensure_firewall_ports

            log "[4/8] Prepare service user and admin token"
            ensure_symposium_user
            prepare_app_dir
            ensure_admin_token

            log "[5/8] Install symposium-server binary and service"
            mkdir -p "${'$'}{APP_DIR}"
            cd "${'$'}{APP_DIR}"

            select_server_binary

            if need_cmd systemctl; then
              systemctl stop "${'$'}{APP_NAME}.service" 2>/dev/null || true
            fi

            rm -f "${'$'}{APP_DIR}/${'$'}{APP_NAME}"
            rm -f "${'$'}{ROOMS_DB}" "${'$'}{ROOMS_DB}-shm" "${'$'}{ROOMS_DB}-wal" "${'$'}{ROOMS_DB}-journal"
            rm -f "${'$'}{APP_DIR}/relay-info.json"

            install -o "${'$'}{APP_USER}" -g "${'$'}{APP_GROUP}" -m 0750 "${'$'}{SERVER_BINARY_SOURCE}" "${'$'}{APP_DIR}/${'$'}{APP_NAME}"

            install -o "${'$'}{APP_USER}" -g "${'$'}{APP_GROUP}" -m 0600 /dev/null "${'$'}{ROOMS_DB}"

            cat > /etc/systemd/system/${'$'}{APP_NAME}.service <<SERVICE
            [Unit]
            Description=Symposium SFU Server
            After=network-online.target
            Wants=network-online.target

            [Service]
            Type=simple
            WorkingDirectory=${'$'}{APP_DIR}
            ExecStart=${'$'}{APP_DIR}/${'$'}{APP_NAME} --addr 127.0.0.1:3001 --admin-addr 127.0.0.1:3002 --public-ip ${'$'}{SERVER_IP} --nat1to1 ${'$'}{SERVER_IP} --rooms-db ${'$'}{ROOMS_DB} --admin-token-file ${'$'}{ADMIN_TOKEN_FILE}
            Restart=always
            RestartSec=2
            User=${'$'}{APP_USER}
            Group=${'$'}{APP_GROUP}
            NoNewPrivileges=true
            PrivateTmp=true
            ProtectSystem=strict
            ProtectHome=true
            PrivateDevices=true
            ReadWritePaths=${'$'}{APP_DIR}
            CapabilityBoundingSet=
            AmbientCapabilities=
            LockPersonality=true
            MemoryDenyWriteExecute=true
            SystemCallArchitectures=native
            Environment=SYMPOSIUM_PUBLIC_IP=${'$'}{SERVER_IP}
            Environment=SYMPOSIUM_OPEN_ROOMS_DB=${'$'}{ROOMS_DB}
            Environment=SYMPOSIUM_ADMIN_TOKEN_FILE=${'$'}{ADMIN_TOKEN_FILE}
            Environment=SYMPOSIUM_ICE_UDP_PORT_MIN=${'$'}{ICE_UDP_PORT_MIN}
            Environment=SYMPOSIUM_ICE_UDP_PORT_MAX=${'$'}{ICE_UDP_PORT_MAX}

            [Install]
            WantedBy=multi-user.target
            SERVICE

            log "[6/8] Enable and start symposium-server service"
            if need_cmd systemctl; then
              systemctl daemon-reload
              systemctl enable "${'$'}{APP_NAME}.service"

              if ! systemctl restart "${'$'}{APP_NAME}.service"; then
                journalctl -u "${'$'}{APP_NAME}.service" -n 80 --no-pager || true
                die "symposium-server failed to start"
              fi

              sleep 1
              if ! systemctl is-active --quiet "${'$'}{APP_NAME}.service"; then
                journalctl -u "${'$'}{APP_NAME}.service" -n 80 --no-pager || true
                die "symposium-server is not active after start"
              fi
            else
              die "systemctl is required"
            fi

            log "[7/8] Setup HTTPS/WSS via nginx"
            apt_run update
            apt_run install -y nginx

            if need_cmd systemctl; then
              systemctl stop apache2 2>/dev/null || true
              systemctl disable apache2 2>/dev/null || true
            fi

            gen_tls_for_ip
            PIN="${'$'}(calc_spki_pin)"
            log "TLS pin: ${'$'}{PIN}"

            cat > /etc/nginx/conf.d/symposium_ws_map.conf <<'MAP'
            map ${'$'}http_upgrade ${'$'}connection_upgrade {
              default upgrade;
              '' close;
            }
            MAP

            cat > /etc/nginx/sites-available/symposium.conf <<CONF
            server {
              listen 443 ssl http2;
              server_name _;

              ssl_certificate     ${'$'}{TLS_CRT};
              ssl_certificate_key ${'$'}{TLS_KEY};

              location /admin/ {
                proxy_pass http://127.0.0.1:3002;
                proxy_http_version 1.1;

                proxy_set_header Host              \${'$'}host;
                proxy_set_header X-Real-IP         \${'$'}remote_addr;
                proxy_set_header X-Forwarded-For   \${'$'}proxy_add_x_forwarded_for;
                proxy_set_header X-Forwarded-Proto https;

                proxy_read_timeout 60;
                proxy_send_timeout 60;
              }

              location /ws {
                proxy_pass http://127.0.0.1:3001;
                proxy_http_version 1.1;

                proxy_set_header Host              \${'$'}host;
                proxy_set_header X-Real-IP         \${'$'}remote_addr;
                proxy_set_header X-Forwarded-For   \${'$'}proxy_add_x_forwarded_for;
                proxy_set_header X-Forwarded-Proto https;

                proxy_read_timeout 3600;
                proxy_send_timeout 3600;

                proxy_set_header Upgrade           \${'$'}http_upgrade;
                proxy_set_header Connection        \${'$'}connection_upgrade;
              }

              location / {
                proxy_pass http://127.0.0.1:3001;
                proxy_http_version 1.1;

                proxy_set_header Host              \${'$'}host;
                proxy_set_header X-Real-IP         \${'$'}remote_addr;
                proxy_set_header X-Forwarded-For   \${'$'}proxy_add_x_forwarded_for;
                proxy_set_header X-Forwarded-Proto https;

                proxy_read_timeout 3600;
                proxy_send_timeout 3600;

                proxy_set_header Upgrade           \${'$'}http_upgrade;
                proxy_set_header Connection        \${'$'}connection_upgrade;
              }
            }
            CONF

            rm -f /etc/nginx/sites-enabled/default || true
            rm -f /etc/nginx/sites-enabled/symposium-relay.conf || true
            rm -f /etc/nginx/sites-available/symposium-relay.conf || true
            ln -sf /etc/nginx/sites-available/symposium.conf /etc/nginx/sites-enabled/symposium.conf

            nginx -t
            if need_cmd systemctl; then
              systemctl enable --now nginx || true
              systemctl reload nginx || systemctl restart nginx || true
            else
              nginx -s reload || true
            fi

            log "[8/8] Write relay-info.json + checks"
            cat > "${'$'}{APP_DIR}/relay-info.json" <<JSON
            {"host":"${'$'}{SERVER_IP}","httpsPort":443,"pin":"${'$'}{PIN}","adminToken":"${'$'}{ADMIN_TOKEN}","iceUdpPortMin":${'$'}{ICE_UDP_PORT_MIN},"iceUdpPortMax":${'$'}{ICE_UDP_PORT_MAX}}
            JSON
            chown "${'$'}{APP_USER}:${'$'}{APP_GROUP}" "${'$'}{APP_DIR}/relay-info.json"
            chmod 600 "${'$'}{APP_DIR}/relay-info.json"

            set +e
            curl -fsS -H "X-Symposium-Admin-Token: ${'$'}{ADMIN_TOKEN}" "http://127.0.0.1:3002/admin/version" >/dev/null
            ADMIN_RC=${'$'}?
            curl -fsS "http://127.0.0.1:3001" >/dev/null
            APP_RC=${'$'}?
            set -e
            if [ ${'$'}APP_RC -eq 0 ]; then
              log "App responds on http://127.0.0.1:3001"
            else
              log "App not responding yet"
            fi
            if [ ${'$'}ADMIN_RC -eq 0 ]; then
              log "Admin API token check OK"
            else
              log "Admin API token check failed"
            fi

            curl -kfsS "https://127.0.0.1/" >/dev/null && log "Nginx HTTPS OK" || log "Nginx HTTPS check failed"

            echo "SYMPOSIUM_PIN=${'$'}{PIN}"
            echo "SYMPOSIUM_HTTPS=https://${'$'}{SERVER_IP}"
            echo "SYMPOSIUM_WSS=wss://${'$'}{SERVER_IP}/ws"

            echo "Installation finished."
            echo "journalctl -u ${'$'}{APP_NAME}.service -f"
        """.trimIndent()
    }
}

class InstallServersStore(context: Context) {
    private val appContext = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val securePrefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun migrateLegacyIfNeeded() {
        val already = securePrefs.getString(SECURE_KEY, null)
        if (!already.isNullOrBlank()) return

        val legacy = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = legacy.getString(LEGACY_KEY, null) ?: return

        securePrefs.edit().putString(SECURE_KEY, raw).apply()
        legacy.edit().remove(LEGACY_KEY).apply()
    }

    fun load(): List<InstallServer> {
        migrateLegacyIfNeeded()

        val raw = securePrefs.getString(SECURE_KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()

        val out = ArrayList<InstallServer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue

            val id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
            val name = o.optString("name")
            val ip = o.optString("ip")
            val username = o.optString("username")
            val password = o.optString("password")
            val installed = o.optBoolean("installed", false)
            val sshHostKeyPin = o.optString("sshHostKeyPin").takeIf { it.isNotBlank() }
            val httpsPort = o.optInt("httpsPort", -1).takeIf { it > 0 }
            val relayTlsPin = o.optString("relayTlsPin").takeIf { it.isNotBlank() }
            val adminToken = o.optString("adminToken").takeIf { it.isNotBlank() }
            val deploymentProfile = deploymentProfileFromJson(o.optJSONObject("deploymentProfile"))
            val openRooms = parseOpenRoomsArray(o.optJSONArray("openRooms") ?: JSONArray())
            val relayVersion = o.optString("relayVersion").takeIf { it.isNotBlank() }
            val relayVersionState = runCatching {
                RelayVersionState.valueOf(o.optString("relayVersionState"))
            }.getOrElse {
                if (installed) RelayVersionState.UNKNOWN else RelayVersionState.NOT_INSTALLED
            }

            out += InstallServer(
                id = id,
                name = name,
                ip = ip,
                username = username,
                password = password,
                installed = installed,
                sshHostKeyPin = sshHostKeyPin,
                httpsPort = httpsPort,
                relayTlsPin = relayTlsPin,
                adminToken = adminToken,
                deploymentProfile = deploymentProfile,
                openRooms = openRooms,
                relayVersion = relayVersion,
                relayVersionState = relayVersionState
            )
        }
        return out
    }

    fun save(servers: List<InstallServer>) {
        val arr = JSONArray()
        servers.forEach { s ->
            val o = JSONObject()
                .put("id", s.id)
                .put("name", s.name)
                .put("ip", s.ip)
                .put("username", s.username)
                .put("password", s.password)
                .put("installed", s.installed)

            s.sshHostKeyPin?.let { o.put("sshHostKeyPin", it) }
            s.httpsPort?.let { o.put("httpsPort", it) }
            s.relayTlsPin?.let { o.put("relayTlsPin", it) }
            s.adminToken?.let { o.put("adminToken", it) }
            s.deploymentProfile?.let { o.put("deploymentProfile", deploymentProfileToJson(it)) }
            s.relayVersion?.let { o.put("relayVersion", it) }
            o.put("relayVersionState", s.relayVersionState.name)
            o.put("openRooms", openRoomsToJsonArray(s.openRooms))

            arr.put(o)
        }

        securePrefs.edit().putString(SECURE_KEY, arr.toString()).apply()
    }

    fun upsert(server: InstallServer): List<InstallServer> {
        val list = load().toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        save(list)
        return list
    }

    fun delete(id: String): List<InstallServer> {
        val list = load().filterNot { it.id == id }
        save(list)
        return list
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "install_servers"
        private const val LEGACY_KEY = "servers"
        private const val SECURE_PREFS_NAME = "install_servers_secure"
        private const val SECURE_KEY = "servers_json"
    }
}
