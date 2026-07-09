package com.decentralprospect.symposium

import org.webrtc.CameraVideoCapturer
import org.webrtc.IceCandidate

internal enum class AudioRoute {
    EARPIECE,
    SPEAKER,
    WIRED_HEADSET,
    BLUETOOTH
}

internal data class QueuedIce(
    val generation: Long,
    val candidate: IceCandidate
)

internal data class HostPort(val host: String, val port: Int)

internal data class CameraSelection(
    val backend: String,
    val name: String,
    val facing: String,
    val create: () -> CameraVideoCapturer?
)
