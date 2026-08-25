package com.decentralprospect.symposium

import android.util.Log
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal fun CallRuntime.setStatus(s: String) = postUi {
    val finalStatus = if (reconnectMode) {
        if (reconnectAttemptCount > 0) "reconnecting… ($reconnectAttemptCount)" else "Bad connection. Reconnection…"
    } else {
        s
    }
    statusUiState = finalStatus
    uiStateBinder?.setStatus(finalStatus)
    debugLog(TAG, "Status: $finalStatus")
}

internal fun CallRuntime.setConnected(on: Boolean) = postUi {
    connectedUiState = on
    uiStateBinder?.setConnected(on)
    debugLog(TAG, "Connected: $on")
}

internal fun CallRuntime.setPeerId(id: String) = postUi {
    peerIdUiState = id
    uiStateBinder?.setPeerId(id)
    debugLog(TAG, "PeerID: $id")
}

internal fun CallRuntime.setIceState(s: String) = postUi {
    iceUiState = s
    uiStateBinder?.setIceState(s)
    debugLog(TAG, "ICE: $s")
}

internal fun CallRuntime.setPcState(s: String) = postUi {
    pcUiState = s
    uiStateBinder?.setPcState(s)
    debugLog(TAG, "PC: $s")
}

internal fun CallRuntime.refreshCombinedStates() {
    setIceState("pub=$publishIceState sub=$subscribeIceState")
    setPcState("pub=$publishPcState sub=$subscribePcState")
}

internal fun CallRuntime.nextPublishGeneration(): Long {
    publishPcGeneration += 1
    return publishPcGeneration
}

internal fun CallRuntime.nextSubscribeGeneration(): Long {
    subscribePcGeneration += 1
    return subscribePcGeneration
}

internal fun CallRuntime.isCurrentPublishGeneration(generation: Long): Boolean {
    return generation == publishPcGeneration
}

internal fun CallRuntime.isCurrentSubscribeGeneration(generation: Long): Boolean {
    return generation == subscribePcGeneration
}

internal fun CallRuntime.resetSubscribeProtocolGeneration(reason: String) {
    subscribeProtocolGeneration = 0L
    retiredSubscribeProtocolGeneration = 0L
    diagLog("Reset subscribe protocol generation", reason)
}

internal fun CallRuntime.retireSubscribeProtocolGeneration(reason: String) {
    if (subscribeProtocolGeneration > retiredSubscribeProtocolGeneration) {
        retiredSubscribeProtocolGeneration = subscribeProtocolGeneration
    }
    subscribeProtocolGeneration = 0L
    diagLog(
        "Retire subscribe protocol generation",
        "retired=$retiredSubscribeProtocolGeneration reason=$reason"
    )
}

internal fun CallRuntime.shouldDropSubscribeGeneration(generation: Long, context: String): Boolean {
    if (generation <= 0L) {
        diagLog("Drop subscribe signaling without generation", context)
        return true
    }

    if (generation <= retiredSubscribeProtocolGeneration) {
        diagLog(
            "Drop retired subscribe signaling",
            "$context msgGen=$generation retired=$retiredSubscribeProtocolGeneration current=$subscribeProtocolGeneration"
        )
        return true
    }

    if (subscribeProtocolGeneration > 0L && generation != subscribeProtocolGeneration) {
        diagLog(
            "Drop stale subscribe signaling",
            "$context msgGen=$generation current=$subscribeProtocolGeneration retired=$retiredSubscribeProtocolGeneration"
        )
        return true
    }

    return false
}

internal fun CallRuntime.setCameraDebugUi(s: String) = postUi {
    cameraDebugUiState = s
    uiStateBinder?.setCameraDebug(s)
}

internal fun CallRuntime.setOutputState(on: Boolean) = postUi {
    outputEnabled = on
    uiStateBinder?.setOutput(on)
    debugLog(TAG, "Output: $on")
}

internal fun CallRuntime.setSpeakerState(on: Boolean) = postUi {
    speakerphoneOn = on
    uiStateBinder?.setSpeaker(on)
    debugLog(TAG, "Speaker: $on route=$currentAudioRoute")
}

internal fun CallRuntime.setVideoUi(on: Boolean) = postUi {
    videoEnabledState = on
    uiStateBinder?.setVideo(on)
    debugLog(TAG, "Video: $on")
}

internal fun CallRuntime.syncModerationStateToUi() = postUi {
    uiStateBinder?.setRole(localRole)
    uiStateBinder?.setLobbyWaiting(lobbyWaiting)
    uiStateBinder?.setForcedMute(forcedMutedByModerator)
    uiStateBinder?.setSelfHandRaised(selfHandRaised)
    val id = rtcController.selfPeerId()?.trim().orEmpty()
    if (id.isNotBlank() && id != "—") {
        uiStateBinder?.setPeerHandRaised(id, selfHandRaised)
    }
}

internal fun CallRuntime.resetModerationState() {
    localRole = ROLE_GUEST
    lobbyWaiting = false
    forcedMutedByModerator = false
    selfHandRaised = false
    postUi {
        uiStateBinder?.setRole(ROLE_GUEST)
        uiStateBinder?.setLobbyWaiting(false)
        uiStateBinder?.setForcedMute(false)
        uiStateBinder?.setMuteAll(false)
        uiStateBinder?.setSelfHandRaised(false)
        pendingLobbyPeersUiState = emptyList()
        uiStateBinder?.updateLobbyPending(emptyList())
    }
}

internal fun CallRuntime.updateCameraDebug(reason: String? = null) {
    val firstFramePart = if (firstFrameSeen) {
        val at = lastFirstFrameUptimeMs?.let { "${it}ms" } ?: "yes"
        "firstFrame=$at"
    } else {
        "firstFrame=no"
    }
    val line = buildString {
        append("camera=").append(cameraRuntimeState)
        append(" | facing=").append(activeCameraFacing)
        append(" | backend=").append(activeCapturerBackend)
        append(" | size=").append(lastCaptureWidth).append("x").append(lastCaptureHeight)
        append("@").append(lastCaptureFps)
        append(" | ").append(firstFramePart)
        append(" | enabled=").append(videoEnabledState)
        append("\n")
        append("audioRoute=").append(currentAudioRoute)
        append(" | preferred=").append(preferredAudioRoute)
        append(" | speaker=").append(speakerphoneOn)
        append(" | btSco=").append(bluetoothScoStarted)
        if (!reason.isNullOrBlank()) append("\n").append("reason=").append(reason)
        activeCapturerName?.let { append("\n").append("device=").append(it) }
    }
    lastCameraDebugLine = line
    publishDiagnosticPanel()
}

internal fun CallRuntime.diagLog(message: String, details: Any? = null) {
    val ts = runCatching {
        java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date())
    }.getOrDefault(System.currentTimeMillis().toString())

    val safeDetails = if (BuildConfig.DEBUG) {
        details?.let { sanitizeLogText(it.toString()) }
    } else {
        null
    }
    val line = if (safeDetails == null) {
        "[$ts] $message"
    } else {
        "[$ts] $message $safeDetails"
    }

    debugLog(TAG, line)
    synchronized(diagLogLines) {
        diagLogLines.addLast(line)
        while (diagLogLines.size > diagLogMaxLines) {
            diagLogLines.removeFirst()
        }
    }
    publishDiagnosticPanel()
}

internal fun CallRuntime.diagState(message: String) {
    diagLog(
        message,
        "ws=${if (webSocket == null) "closed" else "active"} pubPc=$publishPcState subPc=$subscribePcState pubIce=$publishIceState subIce=$subscribeIceState joined=$joinedRoom online=$mediaOnline role=$localRole lobby=$lobbyWaiting forcedMute=$forcedMutedByModerator hand=$selfHandRaised audioRoute=$currentAudioRoute preferredAudio=$preferredAudioRoute"
    )
}

internal fun CallRuntime.compactJson(obj: JSONObject?, maxLen: Int = 700): String {
    if (obj == null) return "null"
    val raw = runCatching { sanitizeJsonObjectForLog(obj).toString() }
        .getOrElse { "{\"type\":\"${obj.optString("type", "unknown")}\",\"logSanitizationError\":true}" }
    return if (raw.length <= maxLen) raw else raw.take(maxLen) + "…"
}

private fun sanitizeJsonObjectForLog(obj: JSONObject): JSONObject {
    val out = JSONObject()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.opt(key)
        out.put(key, sanitizeJsonValueForLog(key, value))
    }
    return out
}

private fun sanitizeJsonArrayForLog(arr: JSONArray): JSONArray {
    val out = JSONArray()
    for (i in 0 until arr.length()) {
        out.put(sanitizeJsonValueForLog(null, arr.opt(i)))
    }
    return out
}

private fun sanitizeJsonValueForLog(key: String?, value: Any?): Any? {
    if (value == null || value == JSONObject.NULL) return JSONObject.NULL

    val normalizedKey = key?.lowercase(Locale.US).orEmpty()

    return when {
        normalizedKey in REDACTED_LOG_KEYS -> "<redacted>"
        normalizedKey == "sdp" && value is JSONObject -> sanitizeJsonObjectForLog(value)
        normalizedKey == "candidate" && value is JSONObject -> sanitizeJsonObjectForLog(value)
        normalizedKey == "sdp" -> "<redacted-sdp>"
        normalizedKey == "candidate" -> "<redacted-ice-candidate>"
        value is JSONObject -> sanitizeJsonObjectForLog(value)
        value is JSONArray -> sanitizeJsonArrayForLog(value)
        value is String -> sanitizeLogText(value)
        else -> value
    }
}

private val REDACTED_LOG_KEYS = setOf(
    "modkey",
    "moderator_key",
    "moderator-key",
    "moderatorkey",
    "admintoken",
    "admin_token",
    "admin-token",
    "password",
    "pass",
    "token",
    "reconnecttoken",
    "reconnect_token",
    "reconnect-token",
    "authorization"
)

private fun sanitizeLogText(raw: String): String {
    return raw
        .replace(Regex("candidate:[^\r\n]*"), "candidate:<redacted-ice-candidate>")
        .replace(Regex("""(?i)\bmodKey=[^\s,}]+"""), "modKey=<redacted>")
        .replace(Regex("""(?i)\breconnectToken=[^\s,}]+"""), "reconnectToken=<redacted>")
        .replace(Regex("""(?i)\badminToken=[^\s,}]+"""), "adminToken=<redacted>")
        .replace(Regex("""(?i)\bpassword=[^\s,}]+"""), "password=<redacted>")
}

internal fun CallRuntime.publishDiagnosticPanel() {
    val logTail = synchronized(diagLogLines) {
        diagLogLines.joinToString("\n")
    }
    val text = buildString {
        if (lastCameraDebugLine.isNotBlank()) {
            append(lastCameraDebugLine)
        } else {
            append("camera=").append(cameraRuntimeState)
        }
        append("\n\n")
        append("RTC diagnostic log:\n")
        if (logTail.isBlank()) append("—") else append(logTail)
    }
    setCameraDebugUi(text)
}

internal fun CallRuntime.postUi(block: () -> Unit) {
    val main = Looper.getMainLooper()
    if (Thread.currentThread() === main.thread) {
        block()
    } else {
        Handler(main).post(block)
    }
}
