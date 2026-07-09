package com.decentralprospect.symposium

import android.os.SystemClock
import android.util.Log
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import java.util.Locale

internal fun CallRuntime.ensureVideoEnabled() {
    if (videoEnabledState || videoStartInProgress) {
        diagLog("ensureVideoEnabled skipped", "enabled=$videoEnabledState starting=$videoStartInProgress")
        return
    }

    videoStartInProgress = true

    try {
        if (localVideoTrack == null || videoSource == null || surfaceTextureHelper == null) {
            createOrAttachVideoTrackInternal()
        } else if (videoCapturer == null) {
            createOrAttachVideoTrackInternal()
        } else {
            runCatching {
                videoCapturer?.startCapture(
                    if (lastCaptureWidth > 0) lastCaptureWidth else 640,
                    if (lastCaptureHeight > 0) lastCaptureHeight else 480,
                    if (lastCaptureFps > 0) lastCaptureFps else 20
                )
            }
        }

        videoEnabledState = true
        runCatching { localVideoTrack?.setEnabled(true) }

        val senderAttached = ensureLocalVideoSenderInternal()

        VideoTracksStore.setTrack(rtcController.selfPeerId(), localVideoTrack)

        cameraRuntimeState = "capturing"
        setVideoUi(true)
        sendSelfMediaState(videoEnabled = true)
        scheduleCameraWatchdog()
        updateCameraDebug("video-on")

        schedulePublishNegotiation(
            if (senderAttached) "video-enabled" else "video-enabled-sender-missing"
        )
    } catch (t: Throwable) {
        videoEnabledState = false
        setVideoUi(false)
        cameraRuntimeState = "error"
        updateCameraDebug("enable-failed:${t.message}")
        setStatus("Camera init failed")
        Log.e(TAG, "Video enable failed: ${t.message}")
    } finally {
        videoStartInProgress = false
    }
}

internal fun CallRuntime.createOrAttachVideoTrackInternal() {
    if (pcFactory == null || eglBase == null) {
        throw IllegalStateException("Video not initialized")
    }
    cameraRuntimeState = "initializing"
    updateCameraDebug("create-track")

    if (surfaceTextureHelper == null) {
        surfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread",
            eglBase!!.eglBaseContext
        )
    }

    if (videoSource == null) {
        videoSource = pcFactory!!.createVideoSource(false)
    }

    if (localVideoTrack == null) {
        localVideoTrack = pcFactory!!.createVideoTrack(nextLocalVideoTrackId(), videoSource)
    }

    val source = videoSource ?: throw IllegalStateException("videoSource == null")
    val sth = surfaceTextureHelper ?: throw IllegalStateException("surfaceTextureHelper == null")
    if (videoCapturer == null) {
        startNewCapture(source, sth)
    }

    runCatching { localVideoTrack?.setEnabled(true) }
    VideoTracksStore.setTrack(rtcController.selfPeerId(), localVideoTrack)
    cameraRuntimeState = "capturing"
    updateCameraDebug("capture-ready")

    Log.d(TAG, "Local video track ready: ${localVideoTrack?.id()}")
}

internal fun CallRuntime.cameraEventsHandler() = object : CameraVideoCapturer.CameraEventsHandler {
    override fun onCameraError(errorDescription: String?) {
        Log.e(TAG, "Camera error: $errorDescription")
        cameraRuntimeState = "error"
        updateCameraDebug("camera-error:${errorDescription.orEmpty()}")
    }

    override fun onCameraDisconnected() {
        Log.w(TAG, "Camera disconnected")
        cameraRuntimeState = "disconnected"
        updateCameraDebug("camera-disconnected")
    }

    override fun onCameraFreezed(errorDescription: String?) {
        Log.e(TAG, "Camera freezed: $errorDescription")
        cameraRuntimeState = "frozen"
        updateCameraDebug("camera-frozen")
    }

    override fun onCameraOpening(cameraName: String?) {
        Log.d(TAG, "Opening camera: $cameraName")
        activeCapturerName = cameraName
        cameraRuntimeState = "opening"
        updateCameraDebug("opening")
    }

    override fun onFirstFrameAvailable() {
        Log.d(TAG, "First camera frame available")
        firstFrameSeen = true
        lastFirstFrameUptimeMs = SystemClock.uptimeMillis()
        cameraRuntimeState = "streaming"
        updateCameraDebug("first-frame")
    }

    override fun onCameraClosed() {
        Log.d(TAG, "Camera closed")
        cameraRuntimeState = "closed"
        updateCameraDebug("camera-closed")
    }
}

internal fun CallRuntime.buildCameraSelections(): List<CameraSelection> {
    val handler = cameraEventsHandler()
    val selections = mutableListOf<CameraSelection>()

    if (Camera2Enumerator.isSupported(appContext)) {
        val e = Camera2Enumerator(appContext)
        val names = e.deviceNames.toList()
        val ordered = names.sortedBy {
            when {
                e.isFrontFacing(it) -> 0
                e.isBackFacing(it) -> 1
                else -> 2
            }
        }
        ordered.forEach { name ->
            val facing = when {
                e.isFrontFacing(name) -> "front"
                e.isBackFacing(name) -> "back"
                else -> "unknown"
            }
            selections += CameraSelection("camera2", name, facing) {
                e.createCapturer(name, handler)
            }
        }
    }

    val e1 = Camera1Enumerator(true)
    val names1 = e1.deviceNames.toList()
    val ordered1 = names1.sortedBy {
        when {
            e1.isFrontFacing(it) -> 0
            e1.isBackFacing(it) -> 1
            else -> 2
        }
    }
    ordered1.forEach { name ->
        val facing = when {
            e1.isFrontFacing(name) -> "front"
            e1.isBackFacing(name) -> "back"
            else -> "unknown"
        }
        selections += CameraSelection("camera1", name, facing) {
            e1.createCapturer(name, handler)
        }
    }

    return selections.distinctBy { "${it.backend}:${it.name}" }
}

internal fun CallRuntime.startNewCapture(source: VideoSource, sth: SurfaceTextureHelper, forceFacing: String? = null) {
    val selections = buildCameraSelections()
    if (selections.isEmpty()) throw IllegalStateException("No camera capturer")

    val ordered = if (forceFacing.isNullOrBlank()) {
        selections
    } else {
        selections.sortedBy { if (it.facing == forceFacing) 0 else 1 }
    }

    val resolutions = listOf(
        Triple(640, 480, 20),
        Triple(960, 540, 20),
        Triple(640, 480, 15),
        Triple(320, 240, 15)
    )

    var lastError: Throwable? = null
    ordered.forEach { selection ->
        val capturer = selection.create() ?: return@forEach
        try {
            capturer.initialize(sth, appContext, source.capturerObserver)
            var started = false
            for ((w, h, fps) in resolutions) {
                try {
                    capturer.startCapture(w, h, fps)
                    started = true
                    lastCaptureWidth = w
                    lastCaptureHeight = h
                    lastCaptureFps = fps
                    break
                } catch (e: Throwable) {
                    lastError = e
                    Log.w(TAG, "startCapture failed ${selection.name} ${w}x${h}@$fps: ${e.message}")
                }
            }
            if (!started) {
                runCatching { capturer.dispose() }
                return@forEach
            }
            videoCapturer = capturer
            activeCapturerName = selection.name
            activeCameraFacing = selection.facing
            activeCapturerBackend = selection.backend
            firstFrameSeen = false
            lastFirstFrameUptimeMs = null
            cameraRuntimeState = "capturing"
            updateCameraDebug("capture-start")
            return
        } catch (e: Throwable) {
            lastError = e
            runCatching { capturer.dispose() }
        }
    }
    throw IllegalStateException("Failed to start camera capture: ${lastError?.message ?: "unknown"}")
}

internal fun CallRuntime.scheduleCameraWatchdog() {
    val token = SystemClock.uptimeMillis()
    cameraWatchdogToken = token
    cameraWatchdogHandler.removeCallbacksAndMessages(null)
    cameraWatchdogHandler.postDelayed({
        if (cameraWatchdogToken != token) return@postDelayed
        if (!videoEnabledState) return@postDelayed
        if (firstFrameSeen) {
            updateCameraDebug("watchdog-ok")
            return@postDelayed
        }
        Log.w(TAG, "No first frame detected, restarting capture with alternate camera")
        updateCameraDebug("watchdog-restart")
        runCatching {
            restartCapture(preferOppositeFacing = true)
            updateCameraDebug("watchdog-recovered")
        }.onFailure {
            cameraRuntimeState = "error"
            updateCameraDebug("watchdog-failed:${it.message}")
        }
    }, 4000L)
}

internal fun CallRuntime.restartCapture(preferOppositeFacing: Boolean) {
    val source = videoSource ?: throw IllegalStateException("videoSource == null")
    val sth = surfaceTextureHelper ?: throw IllegalStateException("surfaceTextureHelper == null")
    runCatching { videoCapturer?.stopCapture() }
    runCatching { videoCapturer?.dispose() }
    videoCapturer = null
    val forcedFacing = if (preferOppositeFacing) {
        when (activeCameraFacing.lowercase(Locale.US)) {
            "front" -> "back"
            "back" -> "front"
            else -> null
        }
    } else {
        activeCameraFacing
    }
    startNewCapture(source, sth, forcedFacing)
}
