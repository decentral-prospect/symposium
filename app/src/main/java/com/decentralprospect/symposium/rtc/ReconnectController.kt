package com.decentralprospect.symposium

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

internal fun CallRuntime.startPingLoop() {
    if (pingLoopRunning) return
    pingLoopRunning = true
    pingHandler.removeCallbacksAndMessages(null)
    sendPing()
}

internal fun CallRuntime.sendPing() {
    if (!pingLoopRunning || webSocket == null) return
    val seq = ++pingSeq
    val now = System.currentTimeMillis()
    inFlightPings[seq] = now
    sendWS(JSONObject().apply {
        put("type", "ping")
        put("seq", seq)
        put("sentAt", now)
    })
    pingHandler.postDelayed({ sendPing() }, 5000)
}

internal fun CallRuntime.stopPingLoop() {
    pingLoopRunning = false
    pingHandler.removeCallbacksAndMessages(null)
    inFlightPings.clear()
}

internal fun CallRuntime.handlePong(seq: Long, sentAt: Long) {
    if (!pingLoopRunning) return
    val start = inFlightPings.remove(seq) ?: sentAt.takeIf { it > 0 }
    val base = start ?: return
    val rtt = (System.currentTimeMillis() - base).coerceAtLeast(0L)

    rtcController.onPeerPing(rtcController.selfPeerId(), rtt)

    maxPingMsInSession = maxOf(maxPingMsInSession, rtt)

    val nowMs = System.currentTimeMillis()
    val slowPing = rtt >= TELEMETRY_SLOW_PING_MS
    val criticalPing = rtt >= TELEMETRY_CRITICAL_PING_MS
    if (slowPing) slowPingCountInSession += 1
    if (criticalPing) criticalPingCountInSession += 1

    val shouldSendPingProblem =
        slowPing &&
                (criticalPing || mediaLooksBrokenForReconnect()) &&
                nowMs - lastPingTelemetryAtMs >= TELEMETRY_PING_EVENT_INTERVAL_MS

    if (shouldSendPingProblem) {
        lastPingTelemetryAtMs = nowMs
        trackRtcEvent(
            name = "ping.slow",
            attrs = nrAttrs(
                "rttMs" to rtt,
                "badPingStreak" to badPingStreak,
                "mediaLooksBroken" to mediaLooksBrokenForReconnect()
            )
        )
    }

    sendWS(JSONObject().apply {
        put("type", "pingReport")
        put("rtt", rtt)
    })

    if (rtt >= TELEMETRY_SLOW_PING_MS && mediaLooksBrokenForReconnect()) {
        badPingStreak++
    } else {
        badPingStreak = 0
    }

    if (badPingStreak >= 3) {
        badPingStreak = 0
        pingReconnectCountInSession += 1
        if (!reconnectMode) startReconnectMode("bad-ping-streak")
    }

    lastPingMs = rtt
}

internal fun CallRuntime.startReconnectMode(reason: String = "unknown") {
    if (reconnectMode) return
    if (intentionalDisconnect) {
        Log.d(TAG, "Skip reconnect after intentional disconnect: $reason")
        return
    }

    if (lastWsUrl.isBlank() || lastTlsPin.isBlank()) {
        Log.e(TAG, "No saved params for reconnect (host/pin missing)")
        setStatus("offline")
        return
    }

    Log.w(TAG, "=== ENTERING AUTO-RECONNECT MODE: $reason ===")
    diagLog("Enter reconnect mode", reason)
    reconnectsInSession += 1
    reconnectStartedAtMs = SystemClock.elapsedRealtime()
    trackRtcEvent(
        name = "reconnect.started",
        attrs = nrAttrs(
            "reason" to reason,
            "lastPingMs" to lastPingMs,
            "badPingStreak" to badPingStreak
        )
    )

    reconnectMode = true
    reconnectAttemptCount = 0

    setConnected(false)
    setStatus("reconnecting…")

    teardown()

    reconnectHandler.removeCallbacksAndMessages(null)
    reconnectHandler.post(reconnectRunnable)
}

internal fun CallRuntime.stopReconnectMode() {
    if (!reconnectMode) return
    Log.d(TAG, "=== EXIT AUTO-RECONNECT MODE ===")
    trackRtcEvent("reconnect.stopped")
    reconnectHandler.removeCallbacksAndMessages(null)
    reconnectMode = false
    reconnectAttemptCount = 0
    setStatus("offline")
    setConnected(false)
}

internal fun CallRuntime.internalConnectWithSavedParams() {
    if (lastWsUrl.isBlank() || lastTlsPin.isBlank()) {
        Log.e(TAG, "No saved params for reconnect (host/pin missing)")
        stopReconnectMode()
        return
    }

    connect(
        url = lastWsUrl,
        room = lastRoom,
        username = lastUsername,
        tlsPin = lastTlsPin,
        modKey = lastModKey
    )
}
