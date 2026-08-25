package com.decentralprospect.symposium

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AndroidMediaE2ETest {

    @get:Rule
    val mediaPermissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    )

    @Test
    fun publishesAndReceivesAudioAndVideoThroughRelay() {
        val arguments = InstrumentationRegistry.getArguments()
        val relayAddress = arguments.getString(ARG_RELAY_ADDRESS).orEmpty().trim()
        val room = arguments.getString(ARG_ROOM).orEmpty().trim()
        val moderatorKey = arguments.getString(ARG_MODERATOR_KEY).orEmpty().trim()
        val tlsPin = arguments.getString(ARG_TLS_PIN).orEmpty().trim()
        val e2eeSecret = arguments.getString(ARG_E2EE_SECRET).orEmpty().trim()
        val requireOutboundAudio = arguments.getString(ARG_REQUIRE_OUTBOUND_AUDIO)
            ?.toBooleanStrictOrNull()
            ?: true

        assumeTrue(
            "Run through scripts/android-media-e2e.sh to provide the relay configuration",
            relayAddress.isNotBlank() && room.isNotBlank() &&
                moderatorKey.isNotBlank() && tlsPin.isNotBlank() && e2eeSecret.isNotBlank()
        )

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var runtime: CallRuntime? = null

        try {
            awaitCondition("CallService runtime binding") {
                scenario.onActivity { activity -> runtime = activity.callRuntimeState }
                runtime != null
            }
            val callRuntime = requireNotNull(runtime)

            scenario.onActivity { activity ->
                activity.connectViaCallService(
                    url = relayAddress,
                    room = room,
                    username = "android-e2e",
                    tlsPin = tlsPin,
                    modKey = moderatorKey,
                    e2eeSecret = e2eeSecret
                )
            }

            awaitMainState(callRuntime, "moderator join") {
                it.joinedRoom && it.localRole == ROLE_MODERATOR && it.webSocket != null
            }

            onMain {
                callRuntime.askMic(startServiceAfterGrant = true)
                callRuntime.askCameraForVideoEnable()
            }

            awaitMainState(callRuntime, "microphone and camera publication") {
                it.micEnabledState && it.videoEnabledState && it.firstFrameSeen &&
                    it.localAudioTrack != null && it.localVideoTrack != null
            }

            var lastTraffic = MediaTraffic()
            awaitCondition("required encrypted audio/video RTP", MEDIA_TIMEOUT_MS) {
                lastTraffic = collectMediaTraffic(callRuntime)
                lastTraffic.requiredDirectionsActive(requireOutboundAudio)
            }

            awaitMainState(callRuntime, "authenticated frame encryption", MEDIA_TIMEOUT_MS) {
                val ready = it.conferenceE2eeEnabled && it.conferenceE2eeLastError == null &&
                    (
                        !requireOutboundAudio ||
                            it.conferenceE2eeStates.hasOkState("sender", "audio")
                    ) &&
                    it.conferenceE2eeStates.hasOkState("sender", "video") &&
                    it.conferenceE2eeStates.hasOkState("receiver", "audio") &&
                    it.conferenceE2eeStates.hasOkState("receiver", "video") &&
                    it.conferenceE2eeSenderCryptors.size >= 2 &&
                    it.conferenceE2eeReceiverCryptors.size >= 2
                check(ready) {
                    "states=${it.conferenceE2eeStates.toSortedMap()} " +
                        "senders=${it.conferenceE2eeSenderCryptors.size} " +
                        "receivers=${it.conferenceE2eeReceiverCryptors.size} " +
                        "error=${it.conferenceE2eeLastError}"
                }
                true
            }

            val selfPeerId = readOnMain { callRuntime.rtcController.selfPeerId().orEmpty() }
            val remoteVideoOwners = readOnMain {
                VideoTracksStore.snapshot().keys.filter { it != selfPeerId }
            }
            val remoteAudioTrackCount = readOnMain { callRuntime.remoteAudioTracks.size }

            assertTrue(
                "remote audio track was not registered; traffic=$lastTraffic",
                remoteAudioTrackCount > 0
            )
            assertTrue(
                "remote video track was not registered; owners=$remoteVideoOwners traffic=$lastTraffic",
                remoteVideoOwners.isNotEmpty()
            )

            // The host peer closes only after it has authenticated the required
            // Android tracks. Waiting for its remote video to be removed is the
            // end-to-end acknowledgement and prevents teardown from racing the
            // relay's second subscribe negotiation.
            awaitCondition("host peer frame authentication", HOST_ACK_TIMEOUT_MS) {
                readOnMain {
                    VideoTracksStore.snapshot().keys.none { it != selfPeerId }
                }
            }
        } finally {
            runtime?.let { callRuntime ->
                onMain { callRuntime.disconnect() }
            }
            scenario.close()
        }
    }

    private fun collectMediaTraffic(runtime: CallRuntime): MediaTraffic {
        val publishPc = readOnMain { runtime.publishPeerConnection }
        val subscribePc = readOnMain { runtime.subscribePeerConnection }
        val outbound = collectStats(publishPc)
        val inbound = collectStats(subscribePc)

        return MediaTraffic(
            inboundAudioBytes = inbound.bytes("inbound-rtp", "audio", "bytesReceived"),
            inboundVideoBytes = inbound.bytes("inbound-rtp", "video", "bytesReceived"),
            outboundAudioBytes = outbound.bytes("outbound-rtp", "audio", "bytesSent"),
            outboundVideoBytes = outbound.bytes("outbound-rtp", "video", "bytesSent")
        )
    }

    private fun collectStats(peerConnection: PeerConnection?): RTCStatsReport? {
        if (peerConnection == null) return null

        val report = AtomicReference<RTCStatsReport?>()
        val completed = CountDownLatch(1)
        onMain {
            peerConnection.getStats {
                report.set(it)
                completed.countDown()
            }
        }
        check(completed.await(STATS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "timed out collecting WebRTC stats"
        }
        return report.get()
    }

    private fun RTCStatsReport?.bytes(type: String, kind: String, member: String): Long {
        if (this == null) return 0L
        return statsMap.values
            .asSequence()
            .filter { it.type == type }
            .filter {
                val mediaKind = it.members["kind"]?.toString()
                    ?: it.members["mediaType"]?.toString()
                mediaKind == kind
            }
            .mapNotNull { it.members[member].asLongOrNull() }
            .sum()
    }

    private fun Any?.asLongOrNull(): Long? = when (this) {
        is Number -> toLong()
        is String -> toDoubleOrNull()?.toLong()
        else -> null
    }

    private fun awaitMainState(
        runtime: CallRuntime,
        description: String,
        timeoutMs: Long = STATE_TIMEOUT_MS,
        predicate: (CallRuntime) -> Boolean
    ) {
        awaitCondition(description, timeoutMs) {
            readOnMain { predicate(runtime) }
        }
    }

    private fun awaitCondition(
        description: String,
        timeoutMs: Long = STATE_TIMEOUT_MS,
        predicate: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if (predicate()) return
            } catch (error: Throwable) {
                lastError = error
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }

        val suffix = lastError?.let { ": ${it.message}" }.orEmpty()
        throw AssertionError("timed out waiting for $description$suffix", lastError)
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }

    private fun <T> readOnMain(block: () -> T): T {
        val result = AtomicReference<T>()
        onMain { result.set(block()) }
        return result.get()
    }

    private data class MediaTraffic(
        val inboundAudioBytes: Long = 0L,
        val inboundVideoBytes: Long = 0L,
        val outboundAudioBytes: Long = 0L,
        val outboundVideoBytes: Long = 0L
    ) {
        fun requiredDirectionsActive(requireOutboundAudio: Boolean): Boolean =
            inboundAudioBytes > 0L && inboundVideoBytes > 0L &&
                (!requireOutboundAudio || outboundAudioBytes > 0L) &&
                outboundVideoBytes > 0L
    }

    private companion object {
        const val ARG_RELAY_ADDRESS = "e2eRelayAddress"
        const val ARG_ROOM = "e2eRoom"
        const val ARG_MODERATOR_KEY = "e2eModeratorKey"
        const val ARG_TLS_PIN = "e2eTlsPin"
        const val ARG_E2EE_SECRET = "e2eSecret"
        const val ARG_REQUIRE_OUTBOUND_AUDIO = "e2eRequireOutboundAudio"

        const val STATE_TIMEOUT_MS = 30_000L
        const val MEDIA_TIMEOUT_MS = 60_000L
        const val HOST_ACK_TIMEOUT_MS = 30_000L
        const val STATS_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
