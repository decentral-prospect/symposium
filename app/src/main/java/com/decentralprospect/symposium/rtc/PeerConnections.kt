package com.decentralprospect.symposium

import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import java.util.Locale

internal fun CallRuntime.createSplitPeerConnections(iceServers: List<PeerConnection.IceServer>) {
    if (pcFactory == null) {
        diagnosticError(TAG, "Cannot create peer connections: pcFactory is null")
        return
    }

    check(conferenceE2eeEnabled && conferenceE2eeKeyProvider != null) {
        "conference E2EE must be configured before creating peer connections"
    }
    disposeE2eePeerCryptors()
    val config = createRtcConfiguration(iceServers)
    val publishGeneration = nextPublishGeneration()
    val subscribeGeneration = nextSubscribeGeneration()

    localAudioSender = null
    localVideoSender = null
    localAudioTransceiver = null
    localVideoTransceiver = null
    publishBootstrapDone = false
    publishPcState = "new"
    subscribePcState = "new"
    publishIceState = "new"
    subscribeIceState = "new"
    resetSubscribeProtocolGeneration("split-pcs-created")
    refreshCombinedStates()

    runCatching {
        publishPeerConnection = pcFactory!!.createPeerConnection(config, makePublishObserver(publishGeneration))
        subscribePeerConnection = pcFactory!!.createPeerConnection(config, makeSubscribeObserver(subscribeGeneration))

        applyPreferredAudioRoute("pcs-created")
        ensurePublishBootstrapTransceivers()
        rtcController.setPeerConnection(subscribePeerConnection)
        diagState("Split PeerConnections created")
    }.onFailure {
        diagnosticError(TAG, "Failed to create split peer connections: ${it.message}")
        trackRtcEvent("rtc.peer_connection.create_failed", nrAttrs("message" to it.message))
    }
}

internal fun CallRuntime.makePublishObserver(generation: Long): PeerConnection.Observer {
    return object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) {
            if (!isCurrentPublishGeneration(generation)) {
                diagLog("Ignore stale publish signaling", newState)
                return
            }
            diagLog("Publish signaling", newState)
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            postUi {
                if (!isCurrentPublishGeneration(generation)) {
                    diagLog("Ignore stale publish ICE", newState)
                    return@postUi
                }

                publishIceState = newState.name.lowercase(Locale.US)
                refreshCombinedStates()
                diagState("Publish ICE: $newState")

                if (newState == PeerConnection.IceConnectionState.CONNECTED ||
                    newState == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    cancelPublishIceRestart()
                    markMediaOnline("publish-ice-${newState.name.lowercase(Locale.US)}")
                }

                if (newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    diagLog("Publish ICE disconnected; debounce before restart")
                    noteIceBadStateTelemetry()
                    trackRtcEvent(
                        "ice.bad_state",
                        nrAttrs("target" to TARGET_PUBLISH, "state" to newState.name)
                    )
                    schedulePublishIceRestart("publish-ice-disconnected")
                }

                if (newState == PeerConnection.IceConnectionState.FAILED) {
                    diagLog("Publish ICE failed; debounce before restart")
                    noteIceBadStateTelemetry()
                    trackRtcEvent(
                        "ice.bad_state",
                        nrAttrs("target" to TARGET_PUBLISH, "state" to newState.name)
                    )
                    schedulePublishIceRestart("publish-ice-failed")
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            if (!isCurrentPublishGeneration(generation)) return
            debugLog(TAG, "Publish ICE receiving: $receiving")
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            postUi {
                if (!isCurrentPublishGeneration(generation)) {
                    diagLog("Ignore stale publish PC", newState)
                    return@postUi
                }

                publishPcState = newState.name.lowercase(Locale.US)
                refreshCombinedStates()
                diagState("Publish PC: $newState")

                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    cancelPublishIceRestart()
                    markMediaOnline("publish-pc-connected")
                }

                if (newState == PeerConnection.PeerConnectionState.DISCONNECTED) {
                    diagLog("Publish PC disconnected; debounce before ICE restart")
                    notePcBadStateTelemetry()
                    trackRtcEvent(
                        "pc.bad_state",
                        nrAttrs("target" to TARGET_PUBLISH, "state" to newState.name)
                    )
                    schedulePublishIceRestart("publish-pc-disconnected")
                }

                if (newState == PeerConnection.PeerConnectionState.FAILED) {
                    diagLog("Publish PC failed; debounce before ICE restart")
                    notePcBadStateTelemetry()
                    trackRtcEvent(
                        "pc.bad_state",
                        nrAttrs("target" to TARGET_PUBLISH, "state" to newState.name)
                    )
                    schedulePublishIceRestart("publish-pc-failed")
                }
            }
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (!isCurrentPublishGeneration(generation)) {
                diagLog("Ignore stale publish ICE gathering", newState)
                return
            }
            diagLog("Publish ICE gathering", newState)
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                sendIceComplete(TARGET_PUBLISH)
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            if (!isCurrentPublishGeneration(generation)) {
                diagLog("Ignore stale publish local ICE")
                return
            }
            sendLocalIce(TARGET_PUBLISH, candidate)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        override fun onDataChannel(dc: org.webrtc.DataChannel) {}
        override fun onAddStream(stream: org.webrtc.MediaStream) {}

        override fun onRenegotiationNeeded() {
            postUi {
                if (!isCurrentPublishGeneration(generation)) {
                    diagLog("Ignore stale publish renegotiation")
                    return@postUi
                }
                diagLog("Publish onRenegotiationNeeded")
                schedulePublishNegotiation("publish-onRenegotiationNeeded")
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
            if (!isCurrentPublishGeneration(generation)) return
            diagnosticWarning(TAG, "Unexpected remote track on publish PC")
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            if (!isCurrentPublishGeneration(generation)) return
            diagnosticWarning(TAG, "Unexpected remote transceiver on publish PC")
        }
    }
}

internal fun CallRuntime.rememberRemoteAudioTrack(track: org.webrtc.MediaStreamTrack?) {
    if (track !is AudioTrack) return

    remoteAudioTracks[track.id()] = track
    runCatching { track.setEnabled(outputEnabled) }
    diagLog("Remote audio track registered", "id=${track.id()} output=$outputEnabled")
    applyPreferredAudioRoute("remote-audio-track")
    schedulePreferredAudioRouteReapply("remote-audio-track")
}

internal fun CallRuntime.setRemoteAudioOutputEnabled(enabled: Boolean) {
    remoteAudioTracks.values.forEach { track ->
        runCatching { track.setEnabled(enabled) }
    }
    diagLog("Remote audio output changed", "enabled=$enabled tracks=${remoteAudioTracks.size}")
}

internal fun CallRuntime.clearRemoteAudioTracks() {
    remoteAudioTracks.clear()
}

internal fun CallRuntime.makeSubscribeObserver(generation: Long): PeerConnection.Observer {
    return object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) {
            if (!isCurrentSubscribeGeneration(generation)) {
                diagLog("Ignore stale subscribe signaling", newState)
                return
            }
            diagLog("Subscribe signaling", newState)
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            postUi {
                if (!isCurrentSubscribeGeneration(generation)) {
                    diagLog("Ignore stale subscribe ICE", newState)
                    return@postUi
                }

                subscribeIceState = newState.name.lowercase(Locale.US)
                refreshCombinedStates()
                diagState("Subscribe ICE: $newState")

                if (newState == PeerConnection.IceConnectionState.CONNECTED ||
                    newState == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    cancelSubscribeRecovery()
                    markMediaOnline("subscribe-ice-${newState.name.lowercase(Locale.US)}")
                }

                if (newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    diagLog("Subscribe ICE disconnected; debounce before recovery")
                    noteIceBadStateTelemetry()
                    trackRtcEvent(
                        "ice.bad_state",
                        nrAttrs("target" to TARGET_SUBSCRIBE, "state" to newState.name)
                    )
                    scheduleSubscribeRecovery("subscribe-ice-disconnected")
                }

                if (newState == PeerConnection.IceConnectionState.FAILED) {
                    diagLog("Subscribe ICE failed; debounce before recovery")
                    noteIceBadStateTelemetry()
                    trackRtcEvent(
                        "ice.bad_state",
                        nrAttrs("target" to TARGET_SUBSCRIBE, "state" to newState.name)
                    )
                    scheduleSubscribeRecovery("subscribe-ice-failed")
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            if (!isCurrentSubscribeGeneration(generation)) return
            debugLog(TAG, "Subscribe ICE receiving: $receiving")
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            postUi {
                if (!isCurrentSubscribeGeneration(generation)) {
                    diagLog("Ignore stale subscribe PC", newState)
                    return@postUi
                }

                subscribePcState = newState.name.lowercase(Locale.US)
                refreshCombinedStates()
                diagState("Subscribe PC: $newState")

                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    cancelSubscribeRecovery()
                    markMediaOnline("subscribe-pc-connected")
                }

                if (newState == PeerConnection.PeerConnectionState.DISCONNECTED) {
                    diagLog("Subscribe PC disconnected; debounce before recovery")
                    notePcBadStateTelemetry()
                    trackRtcEvent(
                        "pc.bad_state",
                        nrAttrs("target" to TARGET_SUBSCRIBE, "state" to newState.name)
                    )
                    scheduleSubscribeRecovery("subscribe-pc-disconnected")
                }

                if (newState == PeerConnection.PeerConnectionState.FAILED) {
                    diagLog("Subscribe PC failed; debounce before recovery")
                    notePcBadStateTelemetry()
                    trackRtcEvent(
                        "pc.bad_state",
                        nrAttrs("target" to TARGET_SUBSCRIBE, "state" to newState.name)
                    )
                    scheduleSubscribeRecovery("subscribe-pc-failed")
                }
            }
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (!isCurrentSubscribeGeneration(generation)) {
                diagLog("Ignore stale subscribe ICE gathering", newState)
                return
            }
            diagLog("Subscribe ICE gathering", newState)
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                sendIceComplete(TARGET_SUBSCRIBE)
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            if (!isCurrentSubscribeGeneration(generation)) {
                diagLog("Ignore stale subscribe local ICE")
                return
            }
            sendLocalIce(TARGET_SUBSCRIBE, candidate, subscribeProtocolGeneration)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        override fun onDataChannel(dc: org.webrtc.DataChannel) {}
        override fun onAddStream(stream: org.webrtc.MediaStream) {}

        override fun onRenegotiationNeeded() {
            if (!isCurrentSubscribeGeneration(generation)) {
                diagLog("Ignore stale subscribe renegotiation")
                return
            }
            diagLog("Subscribe onRenegotiationNeeded ignored; waiting for server offer")
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
            if (!isCurrentSubscribeGeneration(generation)) {
                diagLog("Ignore stale subscribe onAddTrack")
                return
            }
            if (!attachE2eeReceiverImmediately(receiver)) return
            postUi {
                if (!isCurrentSubscribeGeneration(generation)) {
                    diagLog("Ignore stale subscribe onAddTrack")
                    return@postUi
                }
                rememberRemoteAudioTrack(receiver.track())
                rtcController.handleRemoteTrack(receiver, streams)
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            if (!isCurrentSubscribeGeneration(generation)) {
                diagLog("Ignore stale subscribe onTrack")
                return
            }
            if (!attachE2eeReceiverImmediately(transceiver.receiver)) return
            postUi {
                if (!isCurrentSubscribeGeneration(generation)) {
                    diagLog("Ignore stale subscribe onTrack")
                    return@postUi
                }
                rememberRemoteAudioTrack(transceiver.receiver.track())
                rtcController.handleRemoteTrack(transceiver)
            }
        }
    }
}

private fun CallRuntime.attachE2eeReceiverImmediately(receiver: RtpReceiver): Boolean {
    return runCatching {
        attachE2eeReceiver(receiver)
        true
    }.onFailure { error ->
        // Without an encoded-frame transformer WebRTC would accept plaintext.
        // Disable the track synchronously, then terminate the compromised
        // receive pipeline on the UI thread.
        runCatching { receiver.track()?.setEnabled(false) }
        conferenceE2eeLastError = "receiver setup: ${error.message}"
        diagnosticError(TAG, "Failed to attach E2EE receiver: ${error.message}")
        postUi {
            setStatus("E2EE receiver failed")
            intentionalDisconnect = true
            teardown()
        }
    }.getOrDefault(false)
}

internal fun CallRuntime.markMediaOnline(reason: String) {
    if (mediaOnline) {
        postUi {
            applyPreferredAudioRoute("media-ready:$reason")
            schedulePreferredAudioRouteReapply("media-ready:$reason")
        }
        return
    }
    mediaOnline = true
    markConferenceConnected(reason)
    postUi {
        setConnected(true)
        setStatus("online")
        acquirePartialWakeLock()
        applyPreferredAudioRoute("media-online:$reason")
        schedulePreferredAudioRouteReapply("media-online:$reason")
        startWakeLockRefresh()
        startStatsPolling()
        diagState("Media online: $reason")
    }
}
