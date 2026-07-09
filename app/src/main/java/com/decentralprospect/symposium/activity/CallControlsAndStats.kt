package com.decentralprospect.symposium

import android.util.Log
import android.widget.Toast
import org.webrtc.RTCStatsReport
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver

internal fun CallRuntime.toggleMic() {
    if (forcedMutedByModerator) {
        micEnabledState = false
        runCatching { localAudioTrack?.setEnabled(false) }
        setMicUi()
        sendSelfMediaState(audioEnabled = false)
        Toast.makeText(appContext, "Микрофон выключен модератором", Toast.LENGTH_SHORT).show()
        return
    }

    if (!joinedRoom || lobbyWaiting || webSocket == null) {
        micEnabledState = false
        setMicUi()
        Toast.makeText(appContext, "Сначала подключитесь к комнате", Toast.LENGTH_SHORT).show()
        return
    }


    if (!micEnabledState && localAudioTrack == null) {
        askMic()
        return
    }

    micEnabledState = !micEnabledState
    runCatching { localAudioTrack?.setEnabled(micEnabledState) }
    startCallService(microphone = micEnabledState)
    setMicUi()
    sendSelfMediaState(audioEnabled = micEnabledState)

    Log.d(TAG, "Mic ${if (micEnabledState) "enabled" else "disabled"}")
    diagLog("Mic toggled", "enabled=$micEnabledState")
    trackRtcEvent("mic.toggled", nrAttrs("enabled" to micEnabledState))
}

internal fun CallRuntime.toggleVideo() {
    if (videoStartInProgress || cameraPermissionInFlight) {
        diagLog("Video toggle ignored while starting", "starting=$videoStartInProgress cameraPermission=$cameraPermissionInFlight")
        return
    }

    if (videoEnabledState) {
        videoEnabledState = false
        cameraWatchdogHandler.removeCallbacksAndMessages(null)
        runCatching { localVideoTrack?.setEnabled(false) }
        runCatching { videoCapturer?.stopCapture() }
        runCatching {
            localVideoTransceiver?.direction =
                RtpTransceiver.RtpTransceiverDirection.INACTIVE
        }
        cameraRuntimeState = "stopped"
        firstFrameSeen = false
        lastFirstFrameUptimeMs = null
        setVideoUi(false)
        sendSelfMediaState(videoEnabled = false)
        schedulePublishNegotiation("video-disabled")
        updateCameraDebug("video-off")
        trackRtcEvent("video.toggled", nrAttrs("enabled" to false, "reason" to "user-off"))
        return
    }

    trackRtcEvent("video.enable_requested")
    askCameraForVideoEnable()
}

internal fun CallRuntime.toggleOutput() {
    outputEnabled = !outputEnabled
    setRemoteAudioOutputEnabled(outputEnabled)
    setOutputState(outputEnabled)
    Log.d(TAG, "Output ${if (outputEnabled) "enabled" else "disabled"}")
    trackRtcEvent("output.toggled", nrAttrs("enabled" to outputEnabled))
}

internal fun CallRuntime.switchCamera() {
    if (!videoEnabledState || localVideoTrack == null || videoSource == null || surfaceTextureHelper == null) {
        askCameraForVideoEnable()
        return
    }

    cameraRuntimeState = "switching"
    updateCameraDebug("switch-camera")
    runCatching {
        restartCapture(preferOppositeFacing = true)
        runCatching { localVideoTrack?.setEnabled(true) }
        VideoTracksStore.setTrack(rtcController.selfPeerId(), localVideoTrack)
        cameraRuntimeState = "capturing"
        setVideoUi(true)
        scheduleCameraWatchdog()
        updateCameraDebug("switch-camera-done")
    }.onFailure { e ->
        cameraRuntimeState = "error"
        updateCameraDebug("switch-camera-failed:${e.message}")
        setStatus("Camera switch failed")
        Log.e(TAG, "Camera switch failed: ${e.message}")
        noteCameraTelemetryError()
        trackRtcEvent("camera.switch.failed", nrAttrs("message" to e.message))
    }
}

internal fun CallRuntime.toggleSpeakerphone() {
    val am = callAudioManager()
    preferredAudioRoute = if (speakerphoneOn || currentAudioRoute == AudioRoute.SPEAKER) {
        bestNonSpeakerAudioRoute(am)
    } else {
        AudioRoute.SPEAKER
    }

    if (!isCallAudioActive()) {
        speakerphoneOn = preferredAudioRoute == AudioRoute.SPEAKER
        currentAudioRoute = preferredAudioRoute
        setSpeakerState(speakerphoneOn)
        updateCameraDebug("speaker-toggle-idle")
        return
    }

    setAudioRoute(preferredAudioRoute, "speaker-toggle")
}

internal fun CallRuntime.acquireProximityLock() {
    val wl = proximityWakeLock ?: return
    runCatching {
        wl.acquire(WAKELOCK_TIMEOUT_MS)
    }.onFailure {
        Log.w(TAG, "Proximity acquire failed: ${it.message}")
    }
}

internal fun CallRuntime.releaseProximityLock() {
    val wl = proximityWakeLock ?: return
    if (wl.isHeld) {
        runCatching { wl.release() }
            .onFailure { Log.w(TAG, "Proximity release failed: ${it.message}") }
    }
}

internal fun CallRuntime.acquirePartialWakeLock() {
    val wl = partialWakeLock ?: return
    runCatching {
        wl.acquire(WAKELOCK_TIMEOUT_MS)
    }.onFailure {
        Log.w(TAG, "Partial wake acquire failed: ${it.message}")
    }
}

internal fun CallRuntime.releasePartialWakeLock() {
    val wl = partialWakeLock ?: return
    if (wl.isHeld) {
        runCatching { wl.release() }
            .onFailure { Log.w(TAG, "Partial wake release failed: ${it.message}") }
    }
}

internal fun CallRuntime.startWakeLockRefresh() {
    wakeLockRefreshHandler.removeCallbacks(wakeLockRefreshRunnable)
    wakeLockRefreshRunning = true
    wakeLockRefreshHandler.post(wakeLockRefreshRunnable)
}

internal fun CallRuntime.stopWakeLockRefresh() {
    wakeLockRefreshRunning = false
    wakeLockRefreshHandler.removeCallbacks(wakeLockRefreshRunnable)
    releasePartialWakeLock()
    releaseProximityLock()
}

internal fun CallRuntime.disconnect() {
    intentionalDisconnect = true
    runCatching { webSocket?.close(1000, "bye") }
    teardown()
}

internal fun CallRuntime.teardown() {
    nextPublishGeneration()
    nextSubscribeGeneration()

    if (!reconnectMode || intentionalDisconnect) {
        finishConferenceTelemetry(
            reason = if (intentionalDisconnect) "user-disconnect" else "teardown",
            normal = intentionalDisconnect
        )
    }

    cameraWatchdogHandler.removeCallbacksAndMessages(null)
    publishNegotiationHandler.removeCallbacksAndMessages(null)
    cancelPublishIceRestart()
    publishIceRestartHandler.removeCallbacksAndMessages(null)
    cancelSubscribeRecovery()
    subscribeRecoveryHandler.removeCallbacksAndMessages(null)

    runCatching { webSocket?.cancel() }
    runCatching { webSocket?.close(1000, "teardown") }
    webSocket = null

    setConnected(false)
    setStatus("offline")
    setPeerId("—")
    setIceState("—")
    setPcState("—")

    stopStatsPolling()
    stopPingLoop()
    stopWakeLockRefresh()
    resetAudioRoutingForIdle()

    runCatching { publishPeerConnection?.close() }
    runCatching { subscribePeerConnection?.close() }
    publishPeerConnection = null
    subscribePeerConnection = null
    rtcController.setPeerConnection(null)

    localAudioSender = null
    localVideoSender = null
    localAudioTransceiver = null
    localVideoTransceiver = null
    publishBootstrapDone = false

    runCatching { localAudioTrack?.dispose() }
    runCatching { audioSource?.dispose() }
    runCatching { localVideoTrack?.dispose() }
    runCatching { videoSource?.dispose() }
    runCatching { videoCapturer?.stopCapture() }
    runCatching { videoCapturer?.dispose() }
    runCatching { surfaceTextureHelper?.dispose() }

    localAudioTrack = null
    audioSource = null
    localVideoTrack = null
    videoSource = null
    videoCapturer = null
    surfaceTextureHelper = null
    activeCapturerName = null
    activeCameraFacing = "unknown"
    activeCapturerBackend = "none"
    lastCaptureWidth = 0
    lastCaptureHeight = 0
    lastCaptureFps = 0
    firstFrameSeen = false
    lastFirstFrameUptimeMs = null
    cameraRuntimeState = "idle"

    clearRemoteAudioTracks()
    VideoTracksStore.clear()
    rtcController.clearAll()

    micEnabledState = false
    videoEnabledState = false
    outputEnabled = true
    speakerphoneOn = false
    badPingStreak = 0
    lastPingMs = null
    joinedRoom = false
    mediaOnline = false
    publishMakingOffer = false
    publishPendingOffer = false
    publishNegotiationScheduled = false
    handlingSubscribeOffer = false
    pendingSubscribeOffer = null
    queuedRemoteIcePublish.clear()
    queuedRemoteIceSubscribe.clear()
    resetSubscribeProtocolGeneration("teardown")
    publishPcState = "new"
    subscribePcState = "new"
    publishIceState = "new"
    subscribeIceState = "new"
    resetModerationState()

    setMicUi()
    setVideoUi(false)
    setOutputState(true)
    setSpeakerState(false)

    resetLocalTrackNamespace()
    stopCallService()
    updateCameraDebug("teardown")
    diagLog("Teardown complete")
}

internal fun CallRuntime.publishSelfTrackToStore() {
    if (localVideoTrack == null) {
        VideoTracksStore.setTrack(rtcController.selfPeerId(), null)
    } else {
        VideoTracksStore.setTrack(rtcController.selfPeerId(), localVideoTrack)
    }
}

internal fun CallRuntime.startStatsPolling() {
    if (statsPolling) return
    statsPolling = true
    statsHandler.post(statsTick)
}

internal fun CallRuntime.stopStatsPolling() {
    statsPolling = false
    statsHandler.removeCallbacksAndMessages(null)
}

internal fun CallRuntime.recordRtcStatsTelemetry(report: RTCStatsReport) {
    var inboundAudioPacketsLost: Long? = null
    var inboundAudioJitter: Double? = null
    var outboundAudioBytesSent: Long? = null
    var outboundVideoBytesSent: Long? = null
    var candidatePairRttSec: Double? = null
    var availableOutgoingBitrate: Double? = null

    report.statsMap.values.forEach { stat ->
        val members = stat.members
        val mediaKind = members["kind"]?.toString() ?: members["mediaType"]?.toString()

        when (stat.type) {
            "inbound-rtp" -> {
                if (mediaKind == "audio") {
                    inboundAudioPacketsLost = members["packetsLost"].asTelemetryLongOrNull()
                    inboundAudioJitter = members["jitter"].asTelemetryDoubleOrNull()
                }
            }

            "outbound-rtp" -> {
                if (mediaKind == "audio") {
                    outboundAudioBytesSent = members["bytesSent"].asTelemetryLongOrNull()
                }
                if (mediaKind == "video") {
                    outboundVideoBytesSent = members["bytesSent"].asTelemetryLongOrNull()
                }
            }

            "candidate-pair" -> {
                val nominated = members["nominated"].asTelemetryBooleanOrNull() == true
                val selected = members["selected"].asTelemetryBooleanOrNull() == true
                val state = members["state"]?.toString()
                if ((nominated || selected) && state == "succeeded") {
                    candidatePairRttSec = members["currentRoundTripTime"].asTelemetryDoubleOrNull()
                    availableOutgoingBitrate = members["availableOutgoingBitrate"].asTelemetryDoubleOrNull()
                }
            }
        }
    }

    val suspiciousStats =
        (inboundAudioPacketsLost ?: 0L) > 0L ||
                (inboundAudioJitter ?: 0.0) > TELEMETRY_BAD_JITTER_SEC ||
                (candidatePairRttSec ?: 0.0) > TELEMETRY_BAD_CANDIDATE_RTT_SEC

    if (suspiciousStats) {
        statsWarningCountInSession += 1
        trackRtcEvent(
            name = "rtc.stats.warning",
            attrs = nrAttrs(
                "inboundAudioPacketsLost" to inboundAudioPacketsLost,
                "inboundAudioJitterSec" to inboundAudioJitter,
                "candidatePairRttSec" to candidatePairRttSec,
                "availableOutgoingBitrateBps" to availableOutgoingBitrate
            )
        )
    }
}

internal fun Any?.asTelemetryLongOrNull(): Long? {
    return when (this) {
        is Number -> this.toLong()
        is String -> this.toLongOrNull()
        else -> null
    }
}

internal fun Any?.asTelemetryDoubleOrNull(): Double? {
    return when (this) {
        is Number -> this.toDouble()
        is String -> this.toDoubleOrNull()
        else -> null
    }
}

internal fun Any?.asTelemetryBooleanOrNull(): Boolean? {
    return when (this) {
        is Boolean -> this
        is String -> this.toBooleanStrictOrNull()
        else -> null
    }
}

internal fun CallRuntime.configureVideoSenderForMobile(sender: RtpSender) {
    runCatching {
        val params = sender.parameters
        if (params.encodings.isEmpty()) return

        params.encodings.forEach { encoding ->
            encoding.maxBitrateBps = 450_000
            encoding.maxFramerate = 20
        }

        val ok = sender.setParameters(params)
        Log.d(TAG, "Video sender params applied=$ok")
    }.onFailure {
        Log.w(TAG, "Video sender tuning failed: ${it.message}")
    }
}

internal fun org.webrtc.MediaStreamTrack.safeState(): org.webrtc.MediaStreamTrack.State? {
    return try {
        state()
    } catch (_: Throwable) {
        null
    }
}
