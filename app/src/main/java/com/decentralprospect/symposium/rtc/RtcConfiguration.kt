package com.decentralprospect.symposium

import android.util.Log
import org.json.JSONObject
import org.webrtc.PeerConnection

internal fun CallRuntime.createRtcConfiguration(
    iceServers: List<PeerConnection.IceServer>
): PeerConnection.RTCConfiguration {
    return PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        iceTransportsType = PeerConnection.IceTransportsType.ALL
        tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        iceCandidatePoolSize = 0
    }
}

internal fun CallRuntime.restartSubscribePeerConnection(reason: String) {
    val factory = pcFactory ?: run {
        diagLog("Cannot restart subscribe PC; factory is null", reason)
        return
    }

    if (webSocket == null || !joinedRoom) {
        diagLog("Cannot restart subscribe PC; not joined", reason)
        return
    }

    cancelSubscribeRecovery()
    clearRemoteAudioTracks()

    handlingSubscribeOffer = false
    pendingSubscribeOffer = null
    retireSubscribeProtocolGeneration("restart-subscribe:$reason")
    queuedRemoteIceSubscribe.clear()

    runCatching {
        subscribePeerConnection?.close()
    }.onFailure {
        Log.w(TAG, "close old subscribe PC failed: ${it.message}")
    }

    subscribePeerConnection = null
    subscribePcState = "new"
    subscribeIceState = "new"
    refreshCombinedStates()

    val iceServers = buildIceServers()

    val config = createRtcConfiguration(iceServers)
    val subscribeGeneration = nextSubscribeGeneration()

    val newPc = runCatching {
        factory.createPeerConnection(config, makeSubscribeObserver(subscribeGeneration))
    }.getOrElse {
        Log.e(TAG, "Failed to recreate subscribe PC: ${it.message}")
        diagLog("Subscribe PC recreate failed", it.message)
        return
    }

    subscribePeerConnection = newPc
    rtcController.setPeerConnection(newPc)

    diagLog("Subscribe PC recreated locally", reason)

    sendWS(JSONObject().apply {
        put("type", "restartSubscribe")
        put("target", TARGET_SUBSCRIBE)
        put("reason", reason)
    })
}
