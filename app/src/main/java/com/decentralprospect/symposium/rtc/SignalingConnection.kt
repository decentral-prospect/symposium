package com.decentralprospect.symposium

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.Locale

internal fun CallRuntime.connect(
    url: String,
    room: String,
    username: String,
    tlsPin: String,
    modKey: String = "",
    e2eeSecret: String
) {
    intentionalDisconnect = false

    val cleanE2eeSecret = try {
        normalizeConferenceE2eeSecret(e2eeSecret)
    } catch (error: Throwable) {
        setStatus("Invalid E2EE key")
        diagnosticError(TAG, "Invalid conference E2EE key: ${error.message}")
        return
    }

    if (webSocket != null || publishPeerConnection != null || subscribePeerConnection != null) {
        diagnosticWarning(TAG, "connect(): stale session detected -> forcing teardown before new connect")
        teardown()
    }

    if (!reconnectMode) {
        resetConferenceTelemetrySession()
    }

    if (!reconnectMode) setStatus("connecting…")
    if (!reconnectMode) {
        startCallService(microphone = false)
    }

    val am = callAudioManager()
    preferredAudioRoute = defaultCallAudioRoute(am)
    setAudioRoute(preferredAudioRoute, "connect-start")

    val hp = parseHostPort(url)
    val host = hp.host
    val port = hp.port

    val pin = try {
        normalizeTlsPin(tlsPin)
    } catch (e: Throwable) {
        setStatus("Invalid TLS pin")
        diagnosticError(TAG, "Invalid TLS pin: ${e.message}")
        return
    }

    val cleanModKey = modKey.trim()
    try {
        configureConferenceE2ee(cleanE2eeSecret)
    } catch (error: Throwable) {
        setStatus("E2EE initialization failed")
        diagnosticError(TAG, "Conference E2EE initialization failed: ${error.message}")
        teardown()
        return
    }
    localRole = if (cleanModKey.isNotBlank()) ROLE_MODERATOR else ROLE_GUEST
    lobbyWaiting = false
    forcedMutedByModerator = false
    selfHandRaised = false
    syncModerationStateToUi()

    val wsUrl = "wss://$host${if (port == 443) "" else ":$port"}/ws"

    lastWsUrl = if (port == 443) host else "$host:$port"
    lastRoom = room
    lastUsername = username
    lastTlsPin = pin
    lastModKey = cleanModKey
    lastE2eeSecret = cleanE2eeSecret
    selfUsername = username.trim()

    resetLocalTrackNamespace()
    rtcController.resetBeforeConnect(selfUsername)
    VideoTracksStore.clear()
    publishSelfTrackToStore()

    val client = pinnedOkHttpClient(host, pin)
    val request = Request.Builder().url(wsUrl).build()

    webSocket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            postUi {
                if (webSocket !== ws) {
                    diagLog("Ignore stale WS open")
                    runCatching { ws.cancel() }
                    return@postUi
                }

                diagLog("WS open")
                trackRtcEvent("ws.open")
                applyPreferredAudioRoute("ws-open")
                createSplitPeerConnections(buildIceServers())
                sendWS(JSONObject().apply {
                    put("type", "join")
                    put("room", room)
                    put("clientId", stableClientId)
                    if (reconnectToken.isNotBlank()) put("reconnectToken", reconnectToken)
                    if (selfUsername.isNotBlank()) put("username", selfUsername)
                    if (cleanModKey.isNotBlank()) put("modKey", cleanModKey)
                })
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            postUi {
                if (webSocket !== ws) {
                    diagLog("Ignore stale WS message")
                    return@postUi
                }

                handleSignalingMessage(text)
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            postUi {
                if (webSocket !== ws) {
                    diagLog("Ignore stale WS failure", t.message)
                    return@postUi
                }

                diagLog("WS failure", t.message)
                wsErrorCountInSession += 1
                trackRtcEvent(
                    name = "ws.failure",
                    attrs = nrAttrs(
                        "message" to t.message,
                        "httpCode" to response?.code
                    )
                )
                diagnosticError(TAG, "WSS failure: ${t.message}")

                webSocket = null
                setConnected(false)
                stopStatsPolling()
                stopPingLoop()

                if (intentionalDisconnect) {
                    teardown()
                    return@postUi
                }

                if (reconnectMode) {
                    teardown()
                } else {
                    startReconnectMode("ws-failure:${t.message ?: "unknown"}")
                }
            }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            debugLog(TAG, "WSS closing: $code $reason")
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            postUi {
                if (webSocket !== ws) {
                    diagLog("Ignore stale WS closed", "code=$code reason=$reason")
                    return@postUi
                }

                diagLog("WS closed", "code=$code reason=$reason")
                if (code != 1000) {
                    wsErrorCountInSession += 1
                }
                trackRtcEvent(
                    name = "ws.closed",
                    attrs = nrAttrs(
                        "code" to code,
                        "reason" to reason,
                        "normalClose" to (code == 1000)
                    )
                )

                webSocket = null
                setConnected(false)
                stopStatsPolling()
                stopPingLoop()

                if (intentionalDisconnect) {
                    teardown()
                    return@postUi
                }

                // A relay restart or a server-side peer cleanup can use a normal
                // WebSocket close code. Only an explicit local/room termination is
                // terminal; every other close must recover.
                if (reconnectMode) {
                    teardown()
                } else {
                    startReconnectMode("ws-closed:$code:$reason")
                }
            }
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {}
    })
}

internal fun CallRuntime.handleSignalingMessage(text: String) {
    try {
        val msg = JSONObject(text)
        val inType = msg.optString("type", "unknown")
        if (inType != "pong" && inType != "peer-ping") {
            diagLog("WS IN $inType", compactJson(msg))
        }
        when (msg.optString("type")) {
            "join" -> handleJoinAccepted(msg)
            "lobby-wait" -> handleLobbyWait(msg)
            "lobby-state" -> handleLobbyState(msg)
            "lobby-approve" -> handleLobbyApproved(msg)
            "lobby-reject" -> handleLobbyRejected(msg)
            "kick" -> handleKicked(msg)
            "room-closed" -> {
                val reason = msg.optString("reason").ifBlank { "Комната закрыта" }
                Toast.makeText(appContext, tr(reason), Toast.LENGTH_SHORT).show()
                diagLog("Room closed by server", reason)
                intentionalDisconnect = true
                teardown()
            }
            "peer-kicked" -> {
                val targetId = msg.optString("targetPeerId").ifBlank { msg.optString("peerId") }
                if (targetId.isNotBlank()) {
                    rtcController.onPeerLeft(targetId)
                    postUi { uiStateBinder?.setPeerHandRaised(targetId, false) }
                }
            }
            "force-mute" -> applyForcedMuteFromServer(true, msg.optString("type"))
            "force-unmute" -> applyForcedMuteFromServer(false, msg.optString("type"))
            "mute-state" -> handleMuteState(msg)
            "mute-all-state" -> handleMuteAllState(msg)
            "hand-state" -> handleHandState(msg)
            "peer-media-state" -> handlePeerMediaState(msg)
            "media-state" -> handlePeerMediaState(msg)
            "publishAnswer" -> handlePublishAnswer(msg)
            "subscribeOffer" -> handleSubscribeOffer(msg)
            "trickle" -> handleRemoteTrickle(msg)
            "iceComplete" -> {
                val target = msg.optString("target", "")
                debugLog(TAG, "Server ICE completed target=$target")
            }
            "peers" -> {
                val peers = msg.optJSONArray("peers")
                rtcController.onPeersSnapshot(peers)
                publishMuteStatesFromPeers(peers)
                publishHandStatesFromPeers(peers)
                publishMediaStatesFromPeers(peers)
            }
            "peer-joined" -> {
                val id = msg.optString("peerId")
                val name = msg.optString("username")
                val rtt = msg.optLong("rtt", -1L).takeIf { msg.has("rtt") && it >= 0 }
                rtcController.onPeerJoined(id, name, rtt)
                publishMuteStateFromPeerObject(msg)
                publishHandStateFromPeerObject(msg)
                publishMediaStateFromPeerObject(msg)
            }
            "peer-left" -> {
                val id = msg.optString("peerId")
                rtcController.onPeerLeft(id)
                if (id.isNotBlank()) {
                    postUi { uiStateBinder?.setPeerHandRaised(id, false) }
                }
            }
            "peer-ping" -> {
                val id = msg.optString("peerId")
                val rtt = msg.optLong("rtt", -1L).takeIf { msg.has("rtt") && it >= 0 }
                rtcController.onPeerPing(id, rtt)
            }
            "track-published" -> {
                rtcController.onTrackPublished(
                    peerId = msg.optString("peerId"),
                    ownerId = msg.optString("ownerId"),
                    trackKey = msg.optString("trackKey"),
                    trackId = msg.optString("trackId"),
                    kind = msg.optString("kind"),
                    streamId = msg.optString("streamId")
                )
                debugLog(TAG, "Track published owner=${msg.optString("peerId")} track=${msg.optString("trackId")} kind=${msg.optString("kind")}")
            }
            "track-unpublished" -> {
                rtcController.onTrackUnpublished(
                    peerId = msg.optString("peerId"),
                    ownerId = msg.optString("ownerId"),
                    trackKey = msg.optString("trackKey"),
                    trackId = msg.optString("trackId"),
                    kind = msg.optString("kind")
                )
                debugLog(TAG, "Track unpublished owner=${msg.optString("peerId")} track=${msg.optString("trackId")} kind=${msg.optString("kind")}")
            }
            "pong" -> {
                val seq = msg.optLong("seq")
                val sentAt = msg.optLong("sentAt")
                handlePong(seq, sentAt)
            }
            "error" -> {
                val err = msg.optString("error")
                setStatus("server error")
                diagnosticError(TAG, "Server error: $err")
            }
            "answer", "offer", "renegotiate" -> {
                diagnosticWarning(TAG, "Ignoring legacy signaling message: ${msg.optString("type")}")
            }
            else -> debugLog(TAG, "WS unknown: ${compactJson(msg)}")
        }
    } catch (e: Throwable) {
        diagnosticError(TAG, "WS parse error: ${e.message}")
    }
}

internal fun CallRuntime.updateReconnectTokenFromServer(msg: JSONObject) {
    val token = msg.optString("reconnectToken").trim()
    if (token.isNotBlank()) {
        reconnectToken = token
        diagLog("Reconnect token updated")
    }
}

internal fun CallRuntime.handleLobbyWait(msg: JSONObject) {
    updateReconnectTokenFromServer(msg)
    if (reconnectMode) {
        stopReconnectMode()
    }
    joinedRoom = false
    mediaOnline = false
    lobbyWaiting = true
    localRole = ROLE_GUEST
    setConnected(false)
    setStatus("waiting for moderator")
    postUi {
        uiStateBinder?.setRole(ROLE_GUEST)
        uiStateBinder?.setLobbyWaiting(true)
        uiStateBinder?.setMuteAll(msg.optBoolean("muteAll", false))
        val peers = parseLobbyPeers(msg.optJSONArray("pending"))
        pendingLobbyPeersUiState = peers
        uiStateBinder?.updateLobbyPending(peers)
    }
    diagLog("Lobby wait", "room=${msg.optString("room")} peerId=${msg.optString("peerId")}")
}

internal fun CallRuntime.handleLobbyState(msg: JSONObject) {
    postUi {
        uiStateBinder?.setMuteAll(msg.optBoolean("muteAll", false))
        val peers = parseLobbyPeers(msg.optJSONArray("pending"))
        pendingLobbyPeersUiState = peers
        uiStateBinder?.updateLobbyPending(peers)
    }
}

internal fun CallRuntime.handleLobbyApproved(msg: JSONObject) {
    updateReconnectTokenFromServer(msg)
    lobbyWaiting = false
    postUi { uiStateBinder?.setLobbyWaiting(false) }
    setStatus("approved")
    diagLog("Lobby approved", compactJson(msg))
}

internal fun CallRuntime.handleLobbyRejected(msg: JSONObject) {
    val reason = msg.optString("reason").ifBlank { "Отклонено модератором" }
    Toast.makeText(appContext, tr(reason), Toast.LENGTH_SHORT).show()
    diagLog("Lobby rejected", reason)
    disconnect()
}

internal fun CallRuntime.handleKicked(msg: JSONObject) {
    val reason = msg.optString("reason").ifBlank { "Удалено модератором" }
    Toast.makeText(appContext, tr(reason), Toast.LENGTH_SHORT).show()
    diagLog("Kicked", reason)
    disconnect()
}

internal fun CallRuntime.handleMuteState(msg: JSONObject) {
    val targetId = msg.optString("targetPeerId").ifBlank { msg.optString("peerId") }
    val muted = msg.optBoolean("muted", false) || (msg.has("canSpeak") && !msg.optBoolean("canSpeak", true))
    if (targetId.isNotBlank()) {
        postUi { uiStateBinder?.setPeerMuted(targetId, muted) }
    }
    if (targetId.isNotBlank() && targetId == rtcController.selfPeerId()) {
        applyForcedMuteFromServer(muted, "mute-state")
    }
    if (msg.has("muteAll")) {
        postUi { uiStateBinder?.setMuteAll(msg.optBoolean("muteAll", false)) }
    }
}

internal fun CallRuntime.handleMuteAllState(msg: JSONObject) {
    val muteAll = msg.optBoolean("muteAll", false)
    postUi { uiStateBinder?.setMuteAll(muteAll) }
    diagLog("Mute-all state", muteAll)
}

internal fun CallRuntime.handleHandState(msg: JSONObject) {
    val targetId = msg.optString("targetPeerId").ifBlank { msg.optString("peerId") }.trim()
    if (targetId.isBlank()) return

    val raised = msg.optBoolean("handRaised", false)
    val selfId = rtcController.selfPeerId()?.trim().orEmpty()
    if (targetId == selfId) {
        selfHandRaised = raised
    }

    postUi {
        uiStateBinder?.setPeerHandRaised(targetId, raised)
        if (targetId == selfId) {
            uiStateBinder?.setSelfHandRaised(raised)
        }
    }

    diagLog("Hand state", "peer=$targetId raised=$raised")
}

internal fun CallRuntime.handlePeerMediaState(msg: JSONObject) {
    val targetId = msg.optString("targetPeerId")
        .ifBlank { msg.optString("peerId") }
        .trim()

    if (targetId.isBlank()) return

    if (msg.has("audioEnabled")) {
        val audioEnabled = msg.optBoolean("audioEnabled", true)
        postUi {
            uiStateBinder?.setPeerAudioEnabled(targetId, audioEnabled)
        }
        diagLog("Peer audio media state", "peer=$targetId audioEnabled=$audioEnabled")
    }

    if (msg.has("videoEnabled")) {
        val videoEnabled = msg.optBoolean("videoEnabled", false)
        postUi {
            uiStateBinder?.setPeerVideoEnabled(targetId, videoEnabled)
        }
        diagLog("Peer video media state", "peer=$targetId videoEnabled=$videoEnabled")
    }
}

internal fun CallRuntime.parseLobbyPeers(arr: JSONArray?): List<LobbyPeerStatus> {
    if (arr == null) return emptyList()
    val out = ArrayList<LobbyPeerStatus>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optString("peerId").trim()
        if (id.isBlank()) continue
        out += LobbyPeerStatus(
            peerId = id,
            username = o.optString("username"),
            joinedAt = o.optLong("joinedAt", 0L)
        )
    }
    return out
}

internal fun CallRuntime.publishMuteStatesFromPeers(arr: JSONArray?) {
    if (arr == null) return
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        publishMuteStateFromPeerObject(o)
    }
}

internal fun CallRuntime.publishMuteStateFromPeerObject(o: JSONObject) {
    val id = o.optString("peerId").trim()
    if (id.isBlank()) return
    val muted = o.optBoolean("muted", false) || (o.has("canSpeak") && !o.optBoolean("canSpeak", true))
    postUi { uiStateBinder?.setPeerMuted(id, muted) }
    if (id == rtcController.selfPeerId()) {
        applyForcedMuteFromServer(muted, "peer-snapshot")
    }
}

internal fun CallRuntime.publishHandStatesFromPeers(arr: JSONArray?) {
    if (arr == null) return
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        publishHandStateFromPeerObject(o)
    }
}

internal fun CallRuntime.publishMediaStatesFromPeers(arr: JSONArray?) {
    if (arr == null) return
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        publishMediaStateFromPeerObject(o)
    }
}

internal fun CallRuntime.publishMediaStateFromPeerObject(o: JSONObject) {
    val id = o.optString("peerId").trim()
    if (id.isBlank()) return

    if (o.has("audioEnabled")) {
        val audioEnabled = o.optBoolean("audioEnabled", true)
        postUi {
            uiStateBinder?.setPeerAudioEnabled(id, audioEnabled)
        }
    }

    if (o.has("videoEnabled")) {
        val videoEnabled = o.optBoolean("videoEnabled", false)
        postUi {
            uiStateBinder?.setPeerVideoEnabled(id, videoEnabled)
        }
    }
}

internal fun CallRuntime.publishHandStateFromPeerObject(o: JSONObject) {
    if (!o.has("handRaised")) return
    val id = o.optString("peerId").trim()
    if (id.isBlank()) return

    val raised = o.optBoolean("handRaised", false)
    val selfId = rtcController.selfPeerId()?.trim().orEmpty()
    if (id == selfId) {
        selfHandRaised = raised
    }

    postUi {
        uiStateBinder?.setPeerHandRaised(id, raised)
        if (id == selfId) {
            uiStateBinder?.setSelfHandRaised(raised)
        }
    }
}

internal fun CallRuntime.applyForcedMuteFromServer(enabled: Boolean, reason: String) {
    val changed = forcedMutedByModerator != enabled
    forcedMutedByModerator = enabled

    postUi {
        uiStateBinder?.setForcedMute(enabled)

        val selfId = rtcController.selfPeerId()?.trim().orEmpty()
        if (selfId.isNotBlank() && selfId != "—") {
            uiStateBinder?.setPeerMuted(selfId, enabled)
        }
    }

    if (enabled) {
        micEnabledState = false
        runCatching { localAudioTrack?.setEnabled(false) }
        setMicUi()
        sendSelfMediaState(audioEnabled = false)
        Toast.makeText(appContext, tr("Микрофон выключен модератором"), Toast.LENGTH_SHORT).show()
    } else {
        postUi { uiStateBinder?.setForcedMute(false) }
        runCatching { localAudioTrack?.setEnabled(micEnabledState) }

        if (changed) {
            Toast.makeText(appContext, tr("Модератор разрешил включить микрофон"), Toast.LENGTH_SHORT).show()
        }
    }

    diagLog("Forced mute", "enabled=$enabled reason=$reason")
}

internal fun CallRuntime.handleJoinAccepted(msg: JSONObject) {
    updateReconnectTokenFromServer(msg)
    val id = msg.optString("peerId", "—")
    val assignedName = msg.optString("username").takeIf { it.isNotBlank() }
    if (assignedName != null) selfUsername = assignedName

    localRole = msg.optString("role").ifBlank { localRole.ifBlank { ROLE_GUEST } }
    lobbyWaiting = false
    joinedRoom = true
    setPeerId(id)

    selfHandRaised = msg.optBoolean("handRaised", false)

    postUi {
        uiStateBinder?.setRole(localRole)
        uiStateBinder?.setLobbyWaiting(false)
        uiStateBinder?.setMuteAll(msg.optBoolean("muteAll", false))
        uiStateBinder?.setSelfHandRaised(selfHandRaised)
        uiStateBinder?.setPeerHandRaised(id, selfHandRaised)
        val peers = parseLobbyPeers(msg.optJSONArray("pending"))
        pendingLobbyPeersUiState = peers
        uiStateBinder?.updateLobbyPending(peers)
    }

    val mutedByJoin = msg.optBoolean("muted", false) || (msg.has("canSpeak") && !msg.optBoolean("canSpeak", true))
    applyForcedMuteFromServer(mutedByJoin, "join")

    val peers = msg.optJSONArray("peers")
    rtcController.onJoinAccepted(id, assignedName, peers)
    publishMuteStatesFromPeers(peers)
    publishHandStatesFromPeers(peers)
    publishMediaStatesFromPeers(peers)
    rtcController.onTracksSnapshot(msg.optJSONArray("tracks"))

    VideoTracksStore.moveSelfTrack(rtcController.selfPeerId())
    publishSelfTrackToStore()
    if (videoEnabledState && localVideoTrack != null) {
        ensureLocalVideoSenderInternal()
        schedulePublishNegotiation("video-enabled-after-join")
    }
    postUi {
        uiStateBinder?.setPeerAudioEnabled(id, micEnabledState)
        uiStateBinder?.setPeerVideoEnabled(id, videoEnabledState)
    }
    if (reconnectMode) {
        stopReconnectMode()
    }

    diagLog("Joined room", "room=${msg.optString("room")} peerId=$id username=$selfUsername role=$localRole muted=$mutedByJoin hand=$selfHandRaised")
    applyPreferredAudioRoute("join-accepted")


    micEnabledState = false
    runCatching { localAudioTrack?.setEnabled(false) }
    setMicUi()
    sendSelfMediaState(audioEnabled = false)


    startCallService(microphone = false)


    markMediaOnline("join-accepted-listener")

    startPingLoop()
    diagLog("Joined listener-only; mic permission will be requested only on mic toggle")
}

internal fun CallRuntime.sendModeratorTargetCommand(type: String, peerId: String) {
    val id = peerId.trim()
    if (id.isBlank()) return
    sendWS(JSONObject().apply {
        put("type", type)
        put("targetPeerId", id)
        if (lastModKey.isNotBlank()) put("modKey", lastModKey)
    })
}

internal fun CallRuntime.sendSetMuteAll(enabled: Boolean) {
    sendWS(JSONObject().apply {
        put("type", "set-mute-all")
        put("muteAll", enabled)
        if (lastModKey.isNotBlank()) put("modKey", lastModKey)
    })
}

internal fun CallRuntime.sendSetHandRaised(enabled: Boolean) {
    selfHandRaised = enabled
    val selfId = rtcController.selfPeerId()?.trim().orEmpty()
    postUi {
        uiStateBinder?.setSelfHandRaised(enabled)
        if (selfId.isNotBlank() && selfId != "—") {
            uiStateBinder?.setPeerHandRaised(selfId, enabled)
        }
    }
    sendWS(JSONObject().apply {
        put("type", "set-hand-raised")
        put("handRaised", enabled)
    })
}

internal fun CallRuntime.sendSelfMediaState(
    audioEnabled: Boolean? = null,
    videoEnabled: Boolean? = null
) {
    if (!joinedRoom || lobbyWaiting || webSocket == null) return

    val selfId = rtcController.selfPeerId()?.trim().orEmpty()

    postUi {
        if (selfId.isNotBlank() && selfId != "—") {
            audioEnabled?.let {
                uiStateBinder?.setPeerAudioEnabled(selfId, it)
            }
            videoEnabled?.let {
                uiStateBinder?.setPeerVideoEnabled(selfId, it)
            }
        }
    }

    sendWS(JSONObject().apply {
        put("type", "media-state")
        audioEnabled?.let { put("audioEnabled", it) }
        videoEnabled?.let { put("videoEnabled", it) }
    })

    diagLog(
        "Self media state",
        "audioEnabled=$audioEnabled videoEnabled=$videoEnabled"
    )
}

internal fun CallRuntime.sendLowerPeerHand(peerId: String) {
    val id = peerId.trim()
    if (id.isBlank()) return
    postUi { uiStateBinder?.setPeerHandRaised(id, false) }
    sendWS(JSONObject().apply {
        put("type", "lower-peer-hand")
        put("targetPeerId", id)
        if (lastModKey.isNotBlank()) put("modKey", lastModKey)
    })
}

internal fun CallRuntime.sendWS(obj: JSONObject) {
    val type = obj.optString("type", "unknown")
    if (type != "ping" && type != "pingReport") {
        diagLog("WS OUT $type", compactJson(obj))
    }
    val sent = webSocket?.send(obj.toString())
    if (sent != true) {
        diagLog("WS send failed", "webSocket is null or send=false type=$type")
        diagnosticWarning(TAG, "Cannot send WS message: webSocket is null or send=false")
    }
}

internal abstract class SimpleSdpObserver(private val label: String) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        diagnosticError(TAG, "$label create failure: $error")
    }
    override fun onSetFailure(error: String?) {
        diagnosticError(TAG, "$label set failure: $error")
    }
}

internal fun CallRuntime.sdpToJson(desc: SessionDescription): JSONObject {
    return JSONObject().apply {
        put("type", desc.type.canonicalForm())
        put("sdp", desc.description)
    }
}

internal fun CallRuntime.sdpFromJson(obj: JSONObject, fallbackType: SessionDescription.Type): SessionDescription {
    val typeString = obj.optString("type", fallbackType.canonicalForm())
    val type = when (typeString.lowercase(Locale.US)) {
        "offer" -> SessionDescription.Type.OFFER
        "answer" -> SessionDescription.Type.ANSWER
        "pranswer" -> SessionDescription.Type.PRANSWER
        "rollback" -> SessionDescription.Type.ROLLBACK
        else -> fallbackType
    }
    return SessionDescription(type, obj.optString("sdp"))
}

internal fun SessionDescription.Type.canonicalForm(): String {
    return when (this) {
        SessionDescription.Type.OFFER -> "offer"
        SessionDescription.Type.ANSWER -> "answer"
        SessionDescription.Type.PRANSWER -> "pranswer"
        SessionDescription.Type.ROLLBACK -> "rollback"
    }
}

internal fun CallRuntime.schedulePublishNegotiation(reason: String) {
    if (!joinedRoom) {
        debugLog(TAG, "Skip publish negotiation before join: $reason")
        return
    }
    if (publishPeerConnection == null) {
        diagnosticWarning(TAG, "Skip publish negotiation, publish PC is null: $reason")
        return
    }

    diagLog("Schedule publish negotiation", reason)
    publishPendingOffer = true
    if (publishNegotiationScheduled) return

    publishNegotiationScheduled = true
    publishNegotiationHandler.postDelayed({
        publishNegotiationScheduled = false
        flushPublishNegotiation("timer")
    }, 120L)
}

internal fun CallRuntime.flushPublishNegotiation(reason: String) {
    val pc = publishPeerConnection ?: return
    if (!joinedRoom) return

    if (publishMakingOffer) {
        publishPendingOffer = true
        debugLog(TAG, "Publish offer already in progress; mark pending: $reason")
        return
    }

    if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
        publishPendingOffer = true
        debugLog(TAG, "Publish PC not stable (${pc.signalingState()}); delay offer")
        publishNegotiationHandler.postDelayed({ flushPublishNegotiation("wait-stable") }, 150L)
        return
    }

    publishPendingOffer = false
    publishMakingOffer = true

    ensurePublishBootstrapTransceivers()

    if (localAudioTrack != null) {
        ensureLocalAudioSenderInternal()
    }

    if (videoEnabledState && localVideoTrack != null) {
        ensureLocalVideoSenderInternal()
    }

    pc.createOffer(object : SimpleSdpObserver("publish createOffer") {
        override fun onCreateSuccess(desc: SessionDescription?) {
            val offer = desc ?: run {
                publishMakingOffer = false
                return
            }

            pc.setLocalDescription(object : SimpleSdpObserver("publish setLocalDescription") {
                override fun onSetSuccess() {
                    val local = pc.localDescription ?: offer
                    sendWS(JSONObject().apply {
                        put("type", "publishOffer")
                        put("target", TARGET_PUBLISH)
                        put("sdp", sdpToJson(local))
                    })
                    diagLog("Sent publishOffer", "reason=$reason signaling=${pc.signalingState()}")
                }

                override fun onSetFailure(error: String?) {
                    super.onSetFailure(error)
                    publishMakingOffer = false
                }
            }, offer)
        }

        override fun onCreateFailure(error: String?) {
            super.onCreateFailure(error)
            publishMakingOffer = false
        }
    }, MediaConstraints())
}

internal fun CallRuntime.handlePublishAnswer(msg: JSONObject) {
    val pc = publishPeerConnection ?: return
    val sdpObj = msg.optJSONObject("sdp") ?: run {
        diagnosticError(TAG, "publishAnswer missing sdp")
        publishMakingOffer = false
        return
    }
    val answer = sdpFromJson(sdpObj, SessionDescription.Type.ANSWER)
    pc.setRemoteDescription(object : SimpleSdpObserver("publish setRemoteAnswer") {
        override fun onSetSuccess() {
            publishMakingOffer = false
            drainQueuedRemoteIce(TARGET_PUBLISH)
            markMediaOnline("publish-answer")
            if (publishPendingOffer) {
                schedulePublishNegotiation("publish-pending-after-answer")
            }
        }

        override fun onSetFailure(error: String?) {
            super.onSetFailure(error)
            publishMakingOffer = false
        }
    }, answer)
}

internal fun CallRuntime.handleSubscribeOffer(msg: JSONObject) {
    if (handlingSubscribeOffer) {
        pendingSubscribeOffer = JSONObject(msg.toString())
        diagLog("Subscribe offer queued while handling previous offer")
        return
    }

    val generation = msg.optLong("generation", -1L)
    if (generation <= 0L) {
        diagLog("Drop subscribeOffer without generation", compactJson(msg))
        return
    }

    if (generation <= retiredSubscribeProtocolGeneration) {
        diagLog(
            "Drop retired subscribeOffer",
            "msgGen=$generation retired=$retiredSubscribeProtocolGeneration current=$subscribeProtocolGeneration"
        )
        return
    }

    if (subscribeProtocolGeneration > 0L && generation < subscribeProtocolGeneration) {
        diagLog(
            "Drop stale subscribeOffer",
            "msgGen=$generation current=$subscribeProtocolGeneration retired=$retiredSubscribeProtocolGeneration"
        )
        return
    }

    if (subscribeProtocolGeneration > 0L && generation > subscribeProtocolGeneration) {

        queuedRemoteIceSubscribe.clear()
        diagLog(
            "Advance subscribe protocol generation",
            "old=$subscribeProtocolGeneration new=$generation"
        )
    }

    subscribeProtocolGeneration = generation

    val pc = subscribePeerConnection ?: run {
        diagnosticError(TAG, "subscribeOffer received but subscribe PC is null")
        return
    }
    val sdpObj = msg.optJSONObject("sdp") ?: run {
        diagnosticError(TAG, "subscribeOffer missing sdp")
        return
    }
    val revision = msg.optLong("revision", -1L)
    val offer = sdpFromJson(sdpObj, SessionDescription.Type.OFFER)

    handlingSubscribeOffer = true
    diagLog(
        "Apply subscribeOffer",
        "generation=$generation revision=$revision signaling=${pc.signalingState()}"
    )

    pc.setRemoteDescription(object : SimpleSdpObserver("subscribe setRemoteOffer") {
        override fun onSetSuccess() {
            if (generation != subscribeProtocolGeneration) {
                diagLog(
                    "Drop subscribeOffer after setRemote; generation changed",
                    "msgGen=$generation current=$subscribeProtocolGeneration"
                )
                finishSubscribeOfferHandling()
                return
            }

            drainQueuedRemoteIce(TARGET_SUBSCRIBE)
            pc.createAnswer(object : SimpleSdpObserver("subscribe createAnswer") {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    val answer = desc ?: run {
                        finishSubscribeOfferHandling()
                        return
                    }
                    pc.setLocalDescription(object : SimpleSdpObserver("subscribe setLocalAnswer") {
                        override fun onSetSuccess() {
                            if (generation != subscribeProtocolGeneration) {
                                diagLog(
                                    "Skip stale subscribeAnswer; generation changed",
                                    "msgGen=$generation current=$subscribeProtocolGeneration"
                                )
                                finishSubscribeOfferHandling()
                                return
                            }

                            val local = pc.localDescription ?: answer
                            sendWS(JSONObject().apply {
                                put("type", "subscribeAnswer")
                                put("target", TARGET_SUBSCRIBE)
                                put("generation", generation)
                                if (revision >= 0) put("revision", revision)
                                put("sdp", sdpToJson(local))
                            })
                            diagLog(
                                "Sent subscribeAnswer",
                                "generation=$generation revision=$revision signaling=${pc.signalingState()}"
                            )
                            markMediaOnline("subscribe-answer")
                            finishSubscribeOfferHandling()
                        }

                        override fun onSetFailure(error: String?) {
                            super.onSetFailure(error)
                            finishSubscribeOfferHandling()
                        }
                    }, answer)
                }

                override fun onCreateFailure(error: String?) {
                    super.onCreateFailure(error)
                    finishSubscribeOfferHandling()
                }
            }, MediaConstraints())
        }

        override fun onSetFailure(error: String?) {
            super.onSetFailure(error)
            finishSubscribeOfferHandling()
        }
    }, offer)
}

internal fun CallRuntime.finishSubscribeOfferHandling() {
    handlingSubscribeOffer = false
    val pending = pendingSubscribeOffer
    pendingSubscribeOffer = null
    if (pending != null) {
        Handler(Looper.getMainLooper()).post { handleSubscribeOffer(pending) }
    }
}

internal fun CallRuntime.handleRemoteTrickle(msg: JSONObject) {
    val target = msg.optString("target", "")
    val generation = msg.optLong("generation", 0L)
    val candObj = msg.optJSONObject("candidate")
    val cand = if (candObj != null) {
        IceCandidate(
            candObj.optString("sdpMid"),
            candObj.optInt("sdpMLineIndex"),
            candObj.optString("candidate")
        )
    } else {
        val s = msg.optString("candidate", "")
        if (s.isBlank()) null else IceCandidate(
            msg.optString("sdpMid", "0"),
            msg.optInt("sdpMLineIndex", 0),
            s
        )
    }

    if (cand == null) return

    when (target) {
        TARGET_PUBLISH -> addOrQueueRemoteIce(TARGET_PUBLISH, cand, generation)
        TARGET_SUBSCRIBE -> {
            if (shouldDropSubscribeGeneration(generation, "remote-ice")) return
            addOrQueueRemoteIce(TARGET_SUBSCRIBE, cand, generation)
        }
        else -> {
            diagnosticWarning(TAG, "Remote ICE without target; applying fallback to subscribe")
            if (shouldDropSubscribeGeneration(generation, "remote-ice-fallback")) return
            addOrQueueRemoteIce(TARGET_SUBSCRIBE, cand, generation)
        }
    }
}

internal fun CallRuntime.addOrQueueRemoteIce(target: String, cand: IceCandidate, generation: Long) {
    if (target == TARGET_SUBSCRIBE && shouldDropSubscribeGeneration(generation, "remote-ice-add")) {
        return
    }

    val pc = when (target) {
        TARGET_PUBLISH -> publishPeerConnection
        TARGET_SUBSCRIBE -> subscribePeerConnection
        else -> null
    }
    if (pc == null) {
        queueRemoteIce(target, cand, generation)
        return
    }

    if (pc.remoteDescription == null) {
        queueRemoteIce(target, cand, generation)
        diagLog(
            "Queued remote ICE before remoteDescription",
            "target=$target generation=$generation mid=${cand.sdpMid} mline=${cand.sdpMLineIndex}"
        )
        return
    }


    if (target == TARGET_SUBSCRIBE && subscribeProtocolGeneration == 0L) {
        queueRemoteIce(target, cand, generation)
        diagLog(
            "Queued subscribe ICE until generation is known",
            "generation=$generation mid=${cand.sdpMid} mline=${cand.sdpMLineIndex}"
        )
        return
    }

    runCatching {
        pc.addIceCandidate(cand)
    }.onFailure {
        diagnosticWarning(TAG, "addIceCandidate failed target=$target generation=$generation: ${it.message}")
    }
}

internal fun CallRuntime.queueRemoteIce(target: String, cand: IceCandidate, generation: Long) {
    val item = QueuedIce(generation, cand)
    when (target) {
        TARGET_PUBLISH -> queuedRemoteIcePublish += item
        TARGET_SUBSCRIBE -> queuedRemoteIceSubscribe += item
    }
}

internal fun CallRuntime.drainQueuedRemoteIce(target: String) {
    val pc = when (target) {
        TARGET_PUBLISH -> publishPeerConnection
        TARGET_SUBSCRIBE -> subscribePeerConnection
        else -> null
    } ?: return

    val list = when (target) {
        TARGET_PUBLISH -> queuedRemoteIcePublish
        TARGET_SUBSCRIBE -> queuedRemoteIceSubscribe
        else -> return
    }

    if (pc.remoteDescription == null || list.isEmpty()) return

    val copy = list.toList()
    list.clear()
    copy.forEach { item ->
        if (target == TARGET_SUBSCRIBE && shouldDropSubscribeGeneration(item.generation, "queued-remote-ice")) {
            return@forEach
        }

        runCatching { pc.addIceCandidate(item.candidate) }
            .onFailure {
                diagnosticWarning(
                    TAG,
                    "drain addIceCandidate failed target=$target generation=${item.generation}: ${it.message}"
                )
            }
    }
    diagLog("Drained remote ICE candidates", "target=$target count=${copy.size}")
}

internal fun CallRuntime.sendLocalIce(target: String, candidate: IceCandidate, generation: Long = 0L) {
    if (candidate.sdp.isBlank()) {
        debugLog(TAG, "Local ICE complete target=$target")
        return
    }

    if (target == TARGET_SUBSCRIBE && generation <= 0L) {
        diagLog("Drop local subscribe ICE without protocol generation")
        return
    }

    sendWS(JSONObject().apply {
        put("type", "trickle")
        put("target", target)
        if (generation > 0L) put("generation", generation)
        put("candidate", JSONObject().apply {
            put("candidate", candidate.sdp)
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
        })
    })
    diagLog(
        "Local ICE",
        "target=$target generation=$generation mid=${candidate.sdpMid} mline=${candidate.sdpMLineIndex} candidate=<redacted-ice-candidate>"
    )
}

internal fun CallRuntime.sendIceComplete(target: String) {
    diagLog("Local ICE complete", "target=$target")
}

internal fun CallRuntime.cancelPublishIceRestart() {
    publishIceRestartRunnable?.let { publishIceRestartHandler.removeCallbacks(it) }
    publishIceRestartRunnable = null
}

internal fun CallRuntime.schedulePublishIceRestart(reason: String) {
    val pcAtSchedule = publishPeerConnection ?: return
    if (!joinedRoom) return

    cancelPublishIceRestart()

    val runnable = Runnable {
        val pc = publishPeerConnection ?: return@Runnable

        if (pc !== pcAtSchedule) {
            diagLog("Skip publish ICE restart; PC changed", reason)
            return@Runnable
        }

        val stillBad =
            publishIceState == "failed" ||
                    publishIceState == "disconnected" ||
                    publishPcState == "failed" ||
                    publishPcState == "disconnected"

        if (!joinedRoom || webSocket == null || !stillBad) {
            diagLog(
                "Skip publish ICE restart; recovered",
                "reason=$reason pubIce=$publishIceState pubPc=$publishPcState"
            )
            return@Runnable
        }

        if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            diagLog("Delay publish ICE restart; not stable", pc.signalingState())
            schedulePublishIceRestart("wait-stable:$reason")
            return@Runnable
        }

        diagLog(
            "Restart publish ICE after debounce",
            "reason=$reason pubIce=$publishIceState pubPc=$publishPcState"
        )

        runCatching {
            pc.restartIce()
        }.onFailure {
            diagnosticWarning(TAG, "publish restartIce failed: ${it.message}")
        }

        schedulePublishNegotiation("ice-restart:$reason")
    }

    publishIceRestartRunnable = runnable
    publishIceRestartHandler.postDelayed(runnable, 2500L)
}

internal fun CallRuntime.mediaLooksBrokenForReconnect(): Boolean {
    val pubHardFailed = publishPcState == "failed"
    val subHardFailed = subscribePcState == "failed"

    val pubUnavailable =
        publishIceState == "failed" ||
                publishIceState == "disconnected" ||
                publishPcState == "failed" ||
                publishPcState == "disconnected"

    val subUnavailable =
        subscribeIceState == "failed" ||
                subscribeIceState == "disconnected" ||
                subscribePcState == "failed" ||
                subscribePcState == "disconnected"

    return pubHardFailed || subHardFailed || (pubUnavailable && subUnavailable)
}
