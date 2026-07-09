package com.decentralprospect.symposium

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.newrelic.agent.android.NewRelic
import android.widget.Toast
import java.util.Locale
import java.util.UUID

internal fun CallRuntime.privacyPrefs() = appContext.getSharedPreferences(PRIVACY_PREFS_NAME, Context.MODE_PRIVATE)

internal fun CallRuntime.loadTelemetryPrivacyPrefs() {
    val prefs = privacyPrefs()
    telemetryEnabledState = prefs.getBoolean(PREF_TELEMETRY_ENABLED, false)
    telemetryPromptShownState = prefs.getBoolean(PREF_TELEMETRY_PROMPT_SHOWN, false)
}

internal fun CallRuntime.persistTelemetryPrivacyPrefs() {
    privacyPrefs().edit()
        .putBoolean(PREF_TELEMETRY_ENABLED, telemetryEnabledState)
        .putBoolean(PREF_TELEMETRY_PROMPT_SHOWN, telemetryPromptShownState)
        .apply()
}

internal fun CallRuntime.setExternalTelemetryEnabled(enabled: Boolean, showToast: Boolean = false) {
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
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    } else {
        shutdownNewRelicTelemetry()
        if (showToast) {
            Toast.makeText(appContext, "Анонимная диагностика выключена", Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun CallRuntime.externalTelemetryActive(): Boolean {
    return telemetryEnabledState && newRelicStarted && !newRelicShutdownInThisProcess
}

internal fun CallRuntime.startNewRelicTelemetry() {
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
            .start(appContext)
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

internal fun CallRuntime.shutdownNewRelicTelemetry() {
    if (!newRelicStarted || newRelicShutdownInThisProcess) return

    runCatching {
        NewRelic.shutdown()
        newRelicStarted = false
        newRelicShutdownInThisProcess = true
    }.onFailure { e ->
        Log.w(TAG, "New Relic shutdown failed: ${e.message}")
    }
}

internal fun CallRuntime.nrAttrs(vararg pairs: Pair<String, Any?>): Map<String, Any> {
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

internal fun CallRuntime.trackRtcEvent(
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

internal fun CallRuntime.shouldSendImportantTelemetry(name: String, attrs: Map<String, Any>): Boolean {
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
            state.equals("FAILED", ignoreCase = true) || mediaLooksBrokenForReconnect()
        }

        "pc.bad_state" -> {
            val state = attrs["state"]?.toString().orEmpty()
            state.equals("FAILED", ignoreCase = true) || mediaLooksBrokenForReconnect()
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

internal fun CallRuntime.sanitizeTelemetryAttrs(attrs: Map<String, Any>): Map<String, Any> {
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

internal fun CallRuntime.telemetryReasonCategory(raw: String?): String {
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

internal fun CallRuntime.newTelemetrySessionId(): String {
    return UUID.randomUUID().toString().replace("-", "").take(16)
}

internal fun CallRuntime.resetConferenceTelemetrySession() {
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

internal fun CallRuntime.markConferenceConnected(reason: String) {
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

internal fun CallRuntime.finishConferenceTelemetry(reason: String, normal: Boolean) {
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

internal fun CallRuntime.noteAudioTelemetryError() {
    audioErrorCountInSession += 1
}

internal fun CallRuntime.noteCameraTelemetryError() {
    cameraErrorCountInSession += 1
}

internal fun CallRuntime.noteIceBadStateTelemetry() {
    iceBadStateCountInSession += 1
}

internal fun CallRuntime.notePcBadStateTelemetry() {
    pcBadStateCountInSession += 1
}

internal fun CallRuntime.recordRtcMetric(name: String, value: Double) {
    if (!externalTelemetryActive()) return
    if (!value.isFinite()) return

    runCatching {
        NewRelic.recordMetric(name, "WebRTC", value)
    }.onFailure { e ->
        Log.w(TAG, "New Relic metric failed: ${e.message}")
    }
}
