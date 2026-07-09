package com.decentralprospect.symposium

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.webrtc.MediaConstraints
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver

internal fun CallRuntime.askPostNotifications() {
    if (Build.VERSION.SDK_INT >= 33) {
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) permissionRequester?.requestPostNotifications()
    }
}

internal fun CallRuntime.askMic(startServiceAfterGrant: Boolean = false) {
    if (micPermissionInFlight) {
        diagLog("Mic permission request ignored; already in flight")
        return
    }

    if (!joinedRoom || lobbyWaiting || webSocket == null) {
        micEnabledState = false
        setMicUi()
        Toast.makeText(appContext, "Сначала подключитесь к комнате", Toast.LENGTH_SHORT).show()
        return
    }

    if (forcedMutedByModerator) {
        micEnabledState = false
        runCatching { localAudioTrack?.setEnabled(false) }
        setMicUi()
        startCallService(microphone = false)
        sendSelfMediaState(audioEnabled = false)
        Toast.makeText(appContext, "Микрофон выключен модератором", Toast.LENGTH_SHORT).show()
        return
    }

    configureCallAudioMode()
    applyPreferredAudioRoute("ask-mic")

    val granted = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    if (granted) {
        if (startServiceAfterGrant) {
            startCallService(microphone = true)
        }

        postUi {
            safeCreateOrAttachMicTrack()
        }
    } else {
        val requester = permissionRequester
        if (requester == null) {
            micPermissionInFlight = false
            pendingStartCallServiceAfterMicPermission = false
            Toast.makeText(appContext, "Откройте приложение, чтобы разрешить микрофон", Toast.LENGTH_SHORT).show()
        } else {
            micPermissionInFlight = true
            pendingStartCallServiceAfterMicPermission = startServiceAfterGrant
            requester.requestRecordAudio(startServiceAfterGrant)
        }
    }
}

internal fun CallRuntime.askCameraForVideoEnable() {
    if (videoEnabledState || videoStartInProgress || cameraPermissionInFlight) {
        diagLog("Camera enable ignored", "enabled=$videoEnabledState starting=$videoStartInProgress permission=$cameraPermissionInFlight")
        return
    }


    val granted = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    if (granted) {
        cameraRuntimeState = "permission-granted"
        updateCameraDebug("camera-permission-ok")
        ensureVideoEnabled()
    } else {
        val requester = permissionRequester
        if (requester == null) {
            cameraPermissionInFlight = false
            cameraRuntimeState = "permission-request-needs-activity"
            updateCameraDebug("request-camera-permission-no-activity")
            Toast.makeText(appContext, "Откройте приложение, чтобы разрешить камеру", Toast.LENGTH_SHORT).show()
        } else {
            cameraPermissionInFlight = true
            cameraRuntimeState = "awaiting-permission"
            updateCameraDebug("request-camera-permission")
            requester.requestCamera()
        }
    }
}

internal fun CallRuntime.safeCreateOrAttachMicTrack() {
    try {
        configureCallAudioMode()
        applyPreferredAudioRoute("mic-before-create")
        createOrAttachMicTrackInternal()
        micEnabledState = !forcedMutedByModerator
        runCatching { localAudioTrack?.setEnabled(micEnabledState) }
        runCatching {
            localAudioTransceiver?.direction =
                if (micEnabledState) {
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                } else {
                    RtpTransceiver.RtpTransceiverDirection.INACTIVE
                }
        }
        setMicUi()
        startCallService(microphone = micEnabledState && !forcedMutedByModerator)
        sendSelfMediaState(audioEnabled = micEnabledState)
        schedulePublishNegotiation("local-audio-ready")
        Log.d(TAG, "Mic initialized enabled=$micEnabledState forcedMute=$forcedMutedByModerator")
    } catch (e: Throwable) {
        Log.w(TAG, "Mic init failed: ${e.message} -> retry with soft AEC/NS")
        try {
            recreateAdm(useHwAec = false, useHwNs = false)
            runCatching { localAudioTrack?.dispose() }
            runCatching { audioSource?.dispose() }
            localAudioTrack = null
            audioSource = null
            createOrAttachMicTrackInternal()
            micEnabledState = !forcedMutedByModerator
            runCatching { localAudioTrack?.setEnabled(micEnabledState) }
            runCatching {
                localAudioTransceiver?.direction =
                    if (micEnabledState) {
                        RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                    } else {
                        RtpTransceiver.RtpTransceiverDirection.INACTIVE
                    }
            }
            setMicUi()
            startCallService(microphone = micEnabledState && !forcedMutedByModerator)
            sendSelfMediaState(audioEnabled = micEnabledState)
            schedulePublishNegotiation("local-audio-ready-retry")
            Log.d(TAG, "Mic initialized (software AEC/NS) enabled=$micEnabledState forcedMute=$forcedMutedByModerator")
        } catch (e2: Throwable) {
            micEnabledState = false
            setMicUi()
            sendSelfMediaState(audioEnabled = false)
            Log.e(TAG, "Mic init fatal: ${e2.message}")
            schedulePublishNegotiation("local-audio-failed")
        }
    }
}

internal fun CallRuntime.setMicUi() {
    postUi { uiStateBinder?.setMic(micEnabledState) }
}

internal fun CallRuntime.createOrAttachMicTrackInternal() {
    if (pcFactory == null) throw IllegalStateException("PC factory not initialized")

    if (audioSource == null) {
        audioSource = pcFactory!!.createAudioSource(createVoiceAudioConstraints())
        Log.d(TAG, "Audio source created")
    }

    if (localAudioTrack == null) {
        localAudioTrack = pcFactory!!.createAudioTrack(nextLocalAudioTrackId(), audioSource)
        Log.d(TAG, "Local audio track created: ${localAudioTrack?.id()}")
    }

    runCatching { localAudioTrack?.setEnabled(!forcedMutedByModerator) }
    ensureLocalAudioSenderInternal()
}

internal fun CallRuntime.configureAudioSenderForVoice(sender: RtpSender) {
    runCatching {
        val params = sender.parameters
        if (params.encodings.isEmpty()) return

        params.encodings.forEach { encoding ->
            encoding.maxBitrateBps = 24000
        }

        val applied = sender.setParameters(params)
        Log.d(TAG, "Audio sender params applied=$applied")
    }.onFailure {
        Log.w(TAG, "Audio sender tuning failed: ${it.message}")
    }
}

internal fun CallRuntime.createVoiceAudioConstraints(): MediaConstraints {
    return MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        optional.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
        optional.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
        optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
        optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
        optional.add(MediaConstraints.KeyValuePair("audio_jitter_buffer_min_delay_ms", "40"))
        optional.add(MediaConstraints.KeyValuePair("audio_jitter_buffer_max_packets", "120"))
    }
}
