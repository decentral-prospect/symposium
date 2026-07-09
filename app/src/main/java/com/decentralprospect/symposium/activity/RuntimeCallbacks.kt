package com.decentralprospect.symposium

import android.util.Log
import org.json.JSONObject
import org.webrtc.RTCStatsReport
import org.webrtc.VideoTrack

internal fun CallRuntime.onWakeLockRefreshTick(runnable: Runnable) {
    if (!mediaOnline || webSocket == null) {
        wakeLockRefreshRunning = false
        releasePartialWakeLock()
        releaseProximityLock()
        return
    }

    acquirePartialWakeLock()

    if (currentAudioRoute == AudioRoute.EARPIECE) {
        acquireProximityLock()
    } else {
        releaseProximityLock()
    }

    wakeLockRefreshHandler.postDelayed(runnable, WAKELOCK_REFRESH_MS)
}

internal fun CallRuntime.onReconnectTick(runnable: Runnable) {
    if (!reconnectMode) return

    if (webSocket != null) {
        reconnectHandler.postDelayed(runnable, RECONNECT_DELAY_MS)
        return
    }

    Log.d(TAG, "Auto-reconnect attempt #$reconnectAttemptCount")
    reconnectAttemptCount++
    trackRtcEvent(
        name = "reconnect.attempt",
        attrs = nrAttrs(
            "attempt" to reconnectAttemptCount,
            "lastPingMs" to lastPingMs
        )
    )
    setStatus("reconnecting… ($reconnectAttemptCount)")
    internalConnectWithSavedParams()
    reconnectHandler.postDelayed(runnable, RECONNECT_DELAY_MS)
}

internal fun CallRuntime.createRtcTrackNegotiationController(): RtcTrackNegotiationController {
    return RtcTrackNegotiationController(object : RtcTrackNegotiationController.Callbacks {
        override fun sendWs(obj: JSONObject) {
            sendWS(obj)
        }

        override fun ensureLocalAudioSender(): Boolean {
            return ensureLocalAudioSenderInternal()
        }

        override fun ensureLocalVideoSender(): Boolean {
            return ensureLocalVideoSenderInternal()
        }

        override fun isLocalVideoEnabled(): Boolean {
            return videoEnabledState
        }

        override fun isSelfAudioAttached(): Boolean {
            return this@createRtcTrackNegotiationController.isSelfAudioAttached()
        }

        override fun isSelfVideoAttached(): Boolean {
            return this@createRtcTrackNegotiationController.isSelfVideoAttached()
        }

        override fun currentSelfUsername(): String {
            return selfUsername
        }

        override fun onPeerPresenceChanged(snapshot: List<RtcTrackNegotiationController.RtcPeerStatus>) {
            postUi {
                uiStateBinder?.updatePeers(
                    snapshot.map {
                        PeerStatus(
                            peerId = it.peerId,
                            username = it.username,
                            pingMs = it.pingMs,
                            audioAttached = it.audioAttached,
                            videoAttached = it.videoAttached,
                            audioLevel = it.audioLevel
                        )
                    }
                )
            }
        }

        override fun onRemoteVideoTrack(ownerId: String, track: VideoTrack?) {
            postUi {
                if (track == null) {
                    VideoTracksStore.removePeer(ownerId)
                } else {
                    VideoTracksStore.setTrack(ownerId, track)
                }
            }
        }

        override fun onConnectedReady() {
            markMediaOnline("controller-ready")
        }
    })
}

internal fun CallRuntime.onStatsTick(runnable: Runnable) {
    val pc = subscribePeerConnection ?: publishPeerConnection ?: return
    if (!statsPolling) return

    try {
        pc.getStats { report: RTCStatsReport ->
            postUi {
                rtcController.computeAudioLevels(report)
                rtcController.cleanupRemoteTracks()

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastStatsTelemetryAtMs >= TELEMETRY_STATS_INTERVAL_MS) {
                    lastStatsTelemetryAtMs = nowMs
                    recordRtcStatsTelemetry(report)
                }
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Stats polling error: ${e.message}")
        trackRtcEvent("rtc.stats.error", nrAttrs("message" to e.message))
    }

    statsHandler.postDelayed(runnable, 1000)
}
