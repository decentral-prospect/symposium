package com.decentralprospect.symposium

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

internal fun CallRuntime.onRecordAudioPermissionResult(granted: Boolean) {
    micPermissionInFlight = false

    if (granted) {
        if (pendingStartCallServiceAfterMicPermission) {
            pendingStartCallServiceAfterMicPermission = false
            startCallService(microphone = true)
        }

        postUi {
            safeCreateOrAttachMicTrack()
        }
    } else {
        pendingStartCallServiceAfterMicPermission = false

        micEnabledState = false
        runCatching { localAudioTrack?.setEnabled(false) }
        setMicUi()
        sendSelfMediaState(audioEnabled = false)

        Toast.makeText(
            appContext,
            tr("Без разрешения микрофон останется выключенным"),
            Toast.LENGTH_SHORT
        ).show()

        diagnosticWarning(TAG, "RECORD_AUDIO permission denied; stay listener-only")
        diagLog("Mic permission denied; stay listener-only")
    }

    if (pendingVideoEnableAfterMicPermission) {
        pendingVideoEnableAfterMicPermission = false

        Handler(Looper.getMainLooper()).postDelayed({
            askCameraForVideoEnable()
        }, 300L)
    }
}

internal fun CallRuntime.onCameraPermissionResult(granted: Boolean) {
    cameraPermissionInFlight = false

    if (granted) {
        cameraRuntimeState = "permission-granted"
        updateCameraDebug("camera-permission-ok")

        Handler(Looper.getMainLooper()).postDelayed({
            ensureVideoEnabled()
        }, 250L)
    } else {
        videoEnabledState = false
        videoStartInProgress = false
        uiStateBinder?.setVideo(false)
        setStatus(tr("Разрешение камеры отклонено"))
        cameraRuntimeState = "permission-denied"
        updateCameraDebug("permission-denied")
        Toast.makeText(
            appContext,
            tr("Без разрешения камера останется выключенной"),
            Toast.LENGTH_SHORT
        ).show()
        diagnosticWarning(TAG, "CAMERA permission denied")
    }
}
