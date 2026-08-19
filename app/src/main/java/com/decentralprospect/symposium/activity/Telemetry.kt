package com.decentralprospect.symposium

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.newrelic.agent.android.NewRelic
import android.widget.Toast
import java.util.Locale
import java.util.UUID

internal fun MainActivity.privacyPrefs() = getSharedPreferences(PRIVACY_PREFS_NAME, Context.MODE_PRIVATE)

internal fun MainActivity.loadTelemetryPrivacyPrefs() {
    val prefs = privacyPrefs()
    telemetryEnabledState = prefs.getBoolean(PREF_TELEMETRY_ENABLED, false)
    telemetryPromptShownState = prefs.getBoolean(PREF_TELEMETRY_PROMPT_SHOWN, false)
}

internal fun MainActivity.persistTelemetryPrivacyPrefs() {
    privacyPrefs().edit()
        .putBoolean(PREF_TELEMETRY_ENABLED, telemetryEnabledState)
        .putBoolean(PREF_TELEMETRY_PROMPT_SHOWN, telemetryPromptShownState)
        .apply()
}

internal fun MainActivity.setExternalTelemetryEnabled(enabled: Boolean, showToast: Boolean = false) {
    telemetryPromptShownState = true
    telemetryEnabledState = enabled
    persistTelemetryPrivacyPrefs()

    if (enabled) {
        startNewRelicTelemetry()
        if (showToast) {
            val message = if (newRelicShutdownInThisProcess) {
                "Анонимная диагностика включится после перезапуска приложения"
            } else {
                "Анонимная диагностика включена"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    } else {
        shutdownNewRelicTelemetry()
        if (showToast) {
            Toast.makeText(this, "Анонимная диагностика выключена", Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun MainActivity.externalTelemetryActive(): Boolean {
    return telemetryEnabledState && newRelicStarted && !newRelicShutdownInThisProcess
}

internal fun MainActivity.startNewRelicTelemetry() {
    if (!telemetryEnabledState) return
    if (newRelicStarted) return
    if (newRelicShutdownInThisProcess) {
        Log.w(TAG, "New Relic cannot be restarted in the same app lifecycle after shutdown")
        return
    }

    val appToken = newRelicAppTokenFromBuildConfig()
    if (appToken.isBlank()) {
        Log.w(TAG, "New Relic app token is not configured in BuildConfig")
        return
    }

    runCatching {
        NewRelic.withApplicationToken(appToken)
            .start(this.applicationContext)
        newRelicStarted = true
    }.onFailure { e ->
        Log.w(TAG, "New Relic start failed: ${e.message}")
    }
}

private fun newRelicAppTokenFromBuildConfig(): String {
    return runCatching {
        BuildConfig::class.java
            .getField("NEW_RELIC_APP_TOKEN")
            .get(null)
            ?.toString()
            ?.trim()
            .orEmpty()
    }.getOrDefault("")
}

internal fun MainActivity.shutdownNewRelicTelemetry() {
    if (!newRelicStarted || newRelicShutdownInThisProcess) return

    runCatching {
        NewRelic.shutdown()
        newRelicStarted = false
        newRelicShutdownInThisProcess = true
    }.onFailure { e ->
        Log.w(TAG, "New Relic shutdown failed: ${e.message}")
    }
}

internal fun MainActivity.nrAttrs(vararg pairs: Pair<String, Any?>): Map<String, Any> {
    return buildMap {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Float -> if (value.isFinite()) put(key, value.toDouble())
                is Double -> if (value.isFinite()) put(key, value)
                else -> put(key, value.toString())
            }
        }
    }
}

internal fun MainActivity.trackRtcEvent(
    name: String,
    attrs: Map<String, Any> = emptyMap()
) {
    if (!externalTelemetryActive()) return
    if (!shouldSendImportantTelemetry(name, attrs)) return

    val safeAttrs = sanitizeTelemetryAttrs(attrs)
    val baseAttrs = nrAttrs(
        "eventName" to name,
        "appVersion" to APP_VERSION,
        "sessionId" to telemetrySessionId,
        "role" to localRole,
        "reconnectMode" to reconnectMode,
        "publishPcState" to publishPcState,
        "subscribePcState" to subscribePcState,
        "publishIceState" to publishIceState,
        "subscribeIceState" to subscribeIceState
    )

    runCatching {
        NewRelic.recordCustomEvent("RtcEvent", name, baseAttrs + safeAttrs)
    }.onFailure { e ->
        Log.w(TAG, "New Relic custom event failed: ${e.message}")
    }
}

internal fun MainActivity.shouldSendImportantTelemetry(name: String, attrs: Map<String, Any>): Boolean {
    return when (name) {
        "conference.connected",
        "conference.ended",
        "reconnect.started",
        "reconnect.recovered",
        "rtc.peer_connection.create_failed",
        "rtc.stats.warning",
        "rtc.stats.error",
        "server.install.finished",
        "server.install.failed" -> true

        "ws.failure" -> true
        "ws.closed" -> attrs["normalClose"] == false

        "ping.slow" -> {
            val rtt = (attrs["rttMs"] as? Number)?.toLong() ?: 0L
            val broken = attrs["mediaLooksBroken"] == true
            rtt >= TELEMETRY_CRITICAL_PING_MS || broken
        }

        "ice.bad_state" -> {
            val state = attrs["state"]?.toString().orEmpty()
            state.equals("FAILED", ignoreCase = true) || false
        }

        "pc.bad_state" -> {
            val state = attrs["state"]?.toString().orEmpty()
            state.equals("FAILED", ignoreCase = true) || false
        }

        "audio.record.init_error",
        "audio.record.start_error",
        "audio.record.error",
        "audio.track.init_error",
        "audio.track.start_error",
        "audio.track.error" -> true

        "camera.switch.failed" -> true

        else -> false
    }
}

internal fun MainActivity.sanitizeTelemetryAttrs(attrs: Map<String, Any>): Map<String, Any> {
    val allowedKeys = setOf(
        "attempt",
        "lastPingMs",
        "badPingStreak",
        "rttMs",
        "mediaLooksBroken",
        "code",
        "httpCode",
        "normalClose",
        "target",
        "state",
        "connectDurationMs",
        "conferenceDurationMs",
        "mediaDurationMs",
        "connected",
        "normal",
        "reconnects",
        "maxPingMs",
        "slowPingCount",
        "criticalPingCount",
        "pingReconnectCount",
        "wsErrorCount",
        "iceBadStateCount",
        "pcBadStateCount",
        "audioErrorCount",
        "cameraErrorCount",
        "statsWarningCount",
        "operationDurationMs",
        "installDurationMs",
        "success",
        "exitStatus",
        "lastStage",
        "usedPinnedSshHostKey",
        "observedSshHostKey",
        "relayInfoPresent",
        "tlsPinPresent",
        "adminTokenPresent",
        "httpsPortDefault",
        "inboundAudioPacketsLost",
        "inboundAudioJitterSec",
        "candidatePairRttSec",
        "availableOutgoingBitrateBps"
    )

    return buildMap {
        attrs.forEach { (key, value) ->
            when (key) {
                "reason", "message" -> put("reasonCategory", telemetryReasonCategory(value.toString()))
                "code" -> put("code", value)
                "errorCode" -> put("errorCode", value.toString())
                in allowedKeys -> put(key, value)
                else -> Unit
            }
        }
    }
}

internal fun MainActivity.telemetryReasonCategory(raw: String?): String {
    val s = raw.orEmpty().lowercase(Locale.US)
    return when {
        s.isBlank() -> "unknown"
        "auth" in s || "аутентифика" in s || "парол" in s -> "ssh_auth"
        "host key" in s || "key verification" in s || "ssh-сертифик" in s -> "ssh_host_key"
        "x25519" in s -> "ssh_x25519"
        "apt" in s || "dpkg" in s || "lock" in s -> "apt"
        "nginx" in s -> "nginx"
        "systemctl" in s || "service" in s || "symposium-server" in s -> "service"
        "admin token" in s -> "admin_token"
        "unsupported os" in s -> "unsupported_os"
        "timeout" in s || "timed out" in s -> "timeout"
        "bad-ping" in s || "ping" in s -> "bad_ping"
        "closed" in s -> "closed"
        "failure" in s || "failed" in s -> "failure"
        "ssl" in s || "tls" in s || "certificate" in s || "pin" in s -> "tls"
        "network" in s || "unreachable" in s || "unable to resolve" in s -> "network"
        "ice" in s -> "ice"
        "camera" in s -> "camera"
        "audio" in s || "mic" in s || "microphone" in s -> "audio"
        "reject" in s || "lobby" in s -> "lobby"
        "kick" in s -> "kicked"
        else -> "other"
    }
}

internal fun MainActivity.newTelemetrySessionId(): String {
    return UUID.randomUUID().toString().replace("-", "").take(16)
}

internal fun MainActivity.resetConferenceTelemetrySession() {
    telemetrySessionId = newTelemetrySessionId()
    conferenceConnectStartedAtMs = SystemClock.elapsedRealtime()
    conferenceMediaOnlineAtMs = 0L
    conferenceEndedSent = false
    reconnectStartedAtMs = 0L
    reconnectsInSession = 0
    maxPingMsInSession = 0L
    slowPingCountInSession = 0
    criticalPingCountInSession = 0
    pingReconnectCountInSession = 0
    wsErrorCountInSession = 0
    iceBadStateCountInSession = 0
    pcBadStateCountInSession = 0
    audioErrorCountInSession = 0
    cameraErrorCountInSession = 0
    statsWarningCountInSession = 0
    lastPingTelemetryAtMs = 0L
    lastStatsTelemetryAtMs = 0L
}

internal fun MainActivity.markConferenceConnected(reason: String) {
    val nowMs = SystemClock.elapsedRealtime()

    if (conferenceConnectStartedAtMs == 0L) {
        conferenceConnectStartedAtMs = nowMs
    }

    if (conferenceMediaOnlineAtMs == 0L) {
        conferenceMediaOnlineAtMs = nowMs
        val connectDurationMs = (nowMs - conferenceConnectStartedAtMs).coerceAtLeast(0L)

        trackRtcEvent(
            "conference.connected",
            nrAttrs(
                "connectDurationMs" to connectDurationMs,
                "reason" to reason
            )
        )

        recordRtcMetric("Conference Connect Duration ms", connectDurationMs.toDouble())
    }

    if (reconnectStartedAtMs > 0L) {
        val reconnectDurationMs = (nowMs - reconnectStartedAtMs).coerceAtLeast(0L)
        trackRtcEvent(
            "reconnect.recovered",
            nrAttrs(
                "connectDurationMs" to reconnectDurationMs,
                "attempt" to reconnectAttemptCount
            )
        )
        reconnectStartedAtMs = 0L
    }
}

internal fun MainActivity.finishConferenceTelemetry(reason: String, normal: Boolean) {
    if (conferenceEndedSent || conferenceConnectStartedAtMs == 0L) return

    val nowMs = SystemClock.elapsedRealtime()
    val connected = conferenceMediaOnlineAtMs > 0L
    val connectDurationMs = if (connected) {
        (conferenceMediaOnlineAtMs - conferenceConnectStartedAtMs).coerceAtLeast(0L)
    } else {
        (nowMs - conferenceConnectStartedAtMs).coerceAtLeast(0L)
    }
    val conferenceDurationMs = if (connected) {
        (nowMs - conferenceMediaOnlineAtMs).coerceAtLeast(0L)
    } else {
        0L
    }

    conferenceEndedSent = true

    trackRtcEvent(
        "conference.ended",
        nrAttrs(
            "connected" to connected,
            "normal" to normal,
            "reason" to reason,
            "connectDurationMs" to connectDurationMs,
            "conferenceDurationMs" to conferenceDurationMs,
            "reconnects" to reconnectsInSession,
            "maxPingMs" to maxPingMsInSession,
            "slowPingCount" to slowPingCountInSession,
            "criticalPingCount" to criticalPingCountInSession,
            "pingReconnectCount" to pingReconnectCountInSession,
            "wsErrorCount" to wsErrorCountInSession,
            "iceBadStateCount" to iceBadStateCountInSession,
            "pcBadStateCount" to pcBadStateCountInSession,
            "audioErrorCount" to audioErrorCountInSession,
            "cameraErrorCount" to cameraErrorCountInSession,
            "statsWarningCount" to statsWarningCountInSession
        )
    )

    recordRtcMetric("Conference Duration ms", conferenceDurationMs.toDouble())
    recordRtcMetric("Conference Max Ping ms", maxPingMsInSession.toDouble())
}

internal fun MainActivity.noteAudioTelemetryError() {
    audioErrorCountInSession += 1
}

internal fun MainActivity.noteCameraTelemetryError() {
    cameraErrorCountInSession += 1
}

internal fun MainActivity.noteIceBadStateTelemetry() {
    iceBadStateCountInSession += 1
}

internal fun MainActivity.notePcBadStateTelemetry() {
    pcBadStateCountInSession += 1
}

internal fun MainActivity.recordRtcMetric(name: String, value: Double) {
    if (!externalTelemetryActive()) return
    if (!value.isFinite()) return

    runCatching {
        NewRelic.recordMetric(name, "WebRTC", value)
    }.onFailure { e ->
        Log.w(TAG, "New Relic metric failed: ${e.message}")
    }
}

internal suspend fun MainActivity.performRelayInstallationWithTelemetry(
    serverIp: String,
    login: String,
    password: String,
    expectedSshHostKeyPin: String?,
    existingProfile: DeploymentProfile?,
    logger: suspend (String) -> Unit
): RemoteInstaller.InstallResult {
    relayInstallStartedAtMs = SystemClock.elapsedRealtime()
    relayInstallLastStage = "connect_ssh"

    val usedPinnedSshHostKey = !expectedSshHostKeyPin.isNullOrBlank()

    val telemetryLogger: suspend (String) -> Unit = { line ->
        installerStageFromLog(line)?.let { relayInstallLastStage = it }
        logger(line)
    }

    return try {
        val result = remoteInstaller.performInstallationDetailed(
            serverIp = serverIp,
            login = login,
            password = password,
            expectedSshHostKeyPin = expectedSshHostKeyPin,
            existingProfile = existingProfile,
            logger = telemetryLogger
        )

        val durationMs = relayInstallDurationMs()
        val relayInfo = result.relayInfo

        trackRtcEvent(
            name = "server.install.finished",
            attrs = nrAttrs(
                "success" to result.success,
                "installDurationMs" to durationMs,
                "operationDurationMs" to durationMs,
                "exitStatus" to result.exitStatus,
                "lastStage" to relayInstallLastStage,
                "usedPinnedSshHostKey" to usedPinnedSshHostKey,
                "observedSshHostKey" to !result.observedSshHostKeyPin.isNullOrBlank(),
                "relayInfoPresent" to (relayInfo != null),
                "tlsPinPresent" to !relayInfo?.pin.isNullOrBlank(),
                "adminTokenPresent" to !relayInfo?.adminToken.isNullOrBlank(),
                "httpsPortDefault" to ((relayInfo?.httpsPort ?: 443) == 443)
            )
        )

        recordInstallerMetric("Relay Install Duration ms", durationMs.toDouble())
        recordInstallerMetric("Relay Install Success", if (result.success) 1.0 else 0.0)

        result
    } catch (e: Throwable) {
        val durationMs = relayInstallDurationMs()

        trackRtcEvent(
            name = "server.install.failed",
            attrs = nrAttrs(
                "success" to false,
                "installDurationMs" to durationMs,
                "operationDurationMs" to durationMs,
                "lastStage" to relayInstallLastStage,
                "usedPinnedSshHostKey" to usedPinnedSshHostKey,
                "reason" to (e.message ?: e.javaClass.simpleName)
            )
        )

        recordInstallerMetric("Relay Install Duration ms", durationMs.toDouble())
        recordInstallerMetric("Relay Install Success", 0.0)

        throw e
    }
}

internal fun MainActivity.relayInstallDurationMs(): Long {
    val startedAt = relayInstallStartedAtMs
    if (startedAt <= 0L) return 0L
    return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
}

internal fun MainActivity.installerStageFromLog(line: String): String? {
    val s = line.lowercase(Locale.US)
    return when {
        "подключение к серверу" in s || "ssh готов" in s -> "connect_ssh"
        "очистка" in s -> "prepare_remote_dir"
        "подготовка файлов" in s -> "prepare_files"
        "скрипт" in s -> "upload_script"
        "файл сервера" in s || "файла из приложения" in s -> "upload_binary"
        "запуск" in s -> "run_installer"
        "1/8" in s -> "stage_1_check_server"
        "2/8" in s -> "stage_2_install_deps"
        "3/8" in s -> "stage_3_firewall"
        "4/8" in s -> "stage_4_admin_token"
        "5/8" in s -> "stage_5_install_service"
        "6/8" in s -> "stage_6_start_service"
        "7/8" in s -> "stage_7_https_wss"
        "8/8" in s -> "stage_8_final_checks"
        "получение данных" in s -> "read_relay_info"
        "готово" in s -> "finished"
        "ошибка" in s -> "error"
        else -> null
    }
}

internal fun MainActivity.recordInstallerMetric(name: String, value: Double) {
    if (!externalTelemetryActive()) return
    if (!value.isFinite()) return

    runCatching {
        NewRelic.recordMetric(name, "Installer", value)
    }.onFailure { e ->
        Log.w(TAG, "New Relic installer metric failed: ${e.message}")
    }
}
