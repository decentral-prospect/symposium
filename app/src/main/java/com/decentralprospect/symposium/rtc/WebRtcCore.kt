package com.decentralprospect.symposium

import android.media.MediaRecorder
import android.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.Logging
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.audio.JavaAudioDeviceModule

internal fun CallRuntime.ensurePublishBootstrapTransceivers() {
    val pc = publishPeerConnection ?: return
    if (publishBootstrapDone) return

    if (localAudioTransceiver == null) {
        localAudioTransceiver = runCatching {
            pc.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
            )
        }.getOrNull()
        localAudioSender = localAudioTransceiver?.sender
        diagLog("Bootstrap inactive publish audio transceiver", "mid=${localAudioTransceiver?.mid}")
    }

    if (localVideoTransceiver == null) {
        localVideoTransceiver = runCatching {
            pc.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
            )
        }.getOrNull()
        localVideoSender = localVideoTransceiver?.sender
        diagLog("Bootstrap inactive publish video transceiver", "mid=${localVideoTransceiver?.mid}")
    }

    publishBootstrapDone = true
}

internal fun CallRuntime.ensureLocalAudioSenderInternal(): Boolean {
    val pc = publishPeerConnection ?: return false
    val track = localAudioTrack ?: return false
    val streamId = rtcController.selfPeerId()?.takeIf { it.isNotBlank() && it != "—" } ?: return false

    ensurePublishBootstrapTransceivers()

    val transceiver = localAudioTransceiver?.takeIf { it.sender != null } ?: runCatching {
        pc.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        )
    }.getOrNull()?.also {
        localAudioTransceiver = it
        localAudioSender = it.sender
        Log.d(TAG, "Created publish audio transceiver mid=${it.mid}")
    }

    val sender = localAudioSender
        ?: transceiver?.sender
        ?: pc.senders.firstOrNull { it.track()?.kind() == "audio" }

    if (sender == null) {
        Log.e(TAG, "Audio sender is unavailable")
        return false
    }

    localAudioSender = sender
    runCatching {
        localAudioTransceiver?.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
    }

    if (sender.track() == track) {
        runCatching { sender.setStreams(listOf(streamId)) }
        configureAudioSenderForVoice(sender)
        return true
    }

    return runCatching {
        val ok = sender.setTrack(track, false)
        if (ok) {
            localAudioTransceiver?.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            sender.setStreams(listOf(streamId))
            configureAudioSenderForVoice(sender)
        }
        ok
    }.getOrElse {
        Log.e(TAG, "Failed to attach local audio track: ${it.message}")
        false
    }
}

internal fun CallRuntime.ensureLocalVideoSenderInternal(): Boolean {
    val pc = publishPeerConnection ?: return false
    val track = localVideoTrack ?: return false
    val streamId = rtcController.selfPeerId()?.takeIf { it.isNotBlank() && it != "—" } ?: return false

    ensurePublishBootstrapTransceivers()

    val transceiver = localVideoTransceiver?.takeIf { it.sender != null } ?: runCatching {
        pc.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        )
    }.getOrNull()?.also {
        localVideoTransceiver = it
        localVideoSender = it.sender
        Log.d(TAG, "Created publish video transceiver mid=${it.mid}")
    }

    val sender = localVideoSender
        ?: transceiver?.sender
        ?: pc.senders.firstOrNull { it.track()?.kind() == "video" }

    if (sender == null) {
        Log.e(TAG, "Video sender is unavailable")
        return false
    }

    localVideoSender = sender
    runCatching { localVideoTransceiver?.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY }

    if (sender.track() == track) {
        runCatching { sender.setStreams(listOf(streamId)) }
        configureVideoSenderForMobile(sender)
        return true
    }

    return runCatching {
        val ok = sender.setTrack(track, false)
        if (ok) {
            localVideoTransceiver?.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            sender.setStreams(listOf(streamId))
            configureVideoSenderForMobile(sender)
        }
        ok
    }.getOrElse {
        Log.e(TAG, "Failed to attach local video track: ${it.message}")
        false
    }
}

internal fun CallRuntime.isSelfAudioAttached(): Boolean {
    val track = localAudioTrack ?: return false
    return track.safeState() == org.webrtc.MediaStreamTrack.State.LIVE && micEnabledState && !forcedMutedByModerator
}

internal fun CallRuntime.isSelfVideoAttached(): Boolean {
    val track = localVideoTrack ?: return false
    return track.safeState() == org.webrtc.MediaStreamTrack.State.LIVE && videoEnabledState
}

internal fun CallRuntime.initWebRtc() {
    val initOptions = PeerConnectionFactory.InitializationOptions
        .builder(appContext)
        .setFieldTrials("DisableIPv6OnWifi/Enabled/ WebRTC-Bwe-MaxProbes/Disabled/ WebRTC-Bwe-ProbeTimeoutMs/10000/")
        .setEnableInternalTracer(false)
        .createInitializationOptions()

    PeerConnectionFactory.initialize(initOptions)
    Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)

    eglBase = VideoRenderContext.eglBase
    recreateAdm(useHwAec = true, useHwNs = true)

    val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
    val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

    pcFactory = PeerConnectionFactory.builder()
        .setAudioDeviceModule(audioDeviceModule)
        .setVideoEncoderFactory(encoderFactory)
        .setVideoDecoderFactory(decoderFactory)
        .createPeerConnectionFactory()

    val am = callAudioManager()
    configureCallAudioMode(am)
    runCatching { am.isSpeakerphoneOn = false }
    speakerphoneOn = false
    preferredAudioRoute = bestNonSpeakerAudioRoute(am)
    currentAudioRoute = preferredAudioRoute
    setSpeakerState(false)
    publishAudioRouteToUi()
    Log.d(TAG, "WebRTC initialized")
}

internal fun CallRuntime.recreateAdm(useHwAec: Boolean, useHwNs: Boolean) {
    runCatching { audioDeviceModule?.release() }

    audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
        .setUseHardwareAcousticEchoCanceler(useHwAec)
        .setUseHardwareNoiseSuppressor(useHwNs)
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                Log.e(TAG, "ADM Record init: $errorMessage")
                noteAudioTelemetryError()
                trackRtcEvent("audio.record.init_error", nrAttrs("message" to errorMessage))
            }

            override fun onWebRtcAudioRecordStartError(
                errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                errorMessage: String?
            ) {
                Log.e(TAG, "ADM Record start: $errorCode $errorMessage")
                noteAudioTelemetryError()
                trackRtcEvent(
                    "audio.record.start_error",
                    nrAttrs("code" to errorCode, "message" to errorMessage)
                )
            }

            override fun onWebRtcAudioRecordError(errorMessage: String?) {
                Log.e(TAG, "ADM Record: $errorMessage")
                noteAudioTelemetryError()
                trackRtcEvent("audio.record.error", nrAttrs("message" to errorMessage))
            }
        })
        .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                Log.e(TAG, "ADM Track init: $errorMessage")
                noteAudioTelemetryError()
                trackRtcEvent("audio.track.init_error", nrAttrs("message" to errorMessage))
            }

            override fun onWebRtcAudioTrackStartError(
                errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                errorMessage: String?
            ) {
                Log.e(TAG, "ADM Track start: $errorCode $errorMessage")
                noteAudioTelemetryError()
                trackRtcEvent(
                    "audio.track.start_error",
                    nrAttrs("code" to errorCode, "message" to errorMessage)
                )
            }

            override fun onWebRtcAudioTrackError(errorMessage: String?) {
                Log.e(TAG, "ADM Track: $errorMessage")
                noteAudioTelemetryError()
                trackRtcEvent("audio.track.error", nrAttrs("message" to errorMessage))
            }
        })
        .createAudioDeviceModule()
}

internal fun CallRuntime.releaseWebRtc() {
    nextPublishGeneration()
    nextSubscribeGeneration()

    stopStatsPolling()
    cameraWatchdogHandler.removeCallbacksAndMessages(null)
    publishNegotiationHandler.removeCallbacksAndMessages(null)
    cancelPublishIceRestart()
    publishIceRestartHandler.removeCallbacksAndMessages(null)
    stopAudioRoutingMonitor()
    cancelSubscribeRecovery()
    subscribeRecoveryHandler.removeCallbacksAndMessages(null)

    runCatching { localAudioTrack?.dispose() }
    runCatching { audioSource?.dispose() }
    runCatching { localVideoTrack?.dispose() }
    runCatching { videoSource?.dispose() }
    runCatching { videoCapturer?.stopCapture() }
    runCatching { videoCapturer?.dispose() }
    runCatching { surfaceTextureHelper?.dispose() }
    runCatching { publishPeerConnection?.dispose() }
    runCatching { subscribePeerConnection?.dispose() }
    runCatching { pcFactory?.dispose() }
    runCatching { audioDeviceModule?.release() }

    localAudioTrack = null
    audioSource = null
    localVideoTrack = null
    videoSource = null
    videoCapturer = null
    surfaceTextureHelper = null
    publishPeerConnection = null
    subscribePeerConnection = null
    pcFactory = null
    audioDeviceModule = null
    eglBase = null
    localAudioSender = null
    localVideoSender = null
    localAudioTransceiver = null
    localVideoTransceiver = null
    publishBootstrapDone = false
    activeCapturerName = null
    activeCameraFacing = "unknown"
    activeCapturerBackend = "none"
    lastCaptureWidth = 0
    lastCaptureHeight = 0
    lastCaptureFps = 0
    firstFrameSeen = false
    lastFirstFrameUptimeMs = null
    cameraRuntimeState = "released"

    micEnabledState = false
    videoEnabledState = false
    outputEnabled = true
    speakerphoneOn = false
    resetModerationState()

    setMicUi()
    setVideoUi(false)
    setOutputState(true)
    setSpeakerState(false)

    clearRemoteAudioTracks()
    VideoTracksStore.clear()
    updateCameraDebug("release-webrtc")
}

internal fun CallRuntime.buildIceServers(): List<PeerConnection.IceServer> {
    Log.d(TAG, "ICE servers: none")
    return emptyList()
}
