package com.decentralprospect.symposium

import android.util.Log
import org.json.JSONArray
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap


class RtcTrackNegotiationController(
    private val callbacks: Callbacks
) {

    companion object {
        private const val TAG = "RTC_TRACK_NEG"
    }

    interface Callbacks {
        fun sendWs(obj: org.json.JSONObject)
        fun ensureLocalAudioSender(): Boolean
        fun ensureLocalVideoSender(): Boolean
        fun isLocalVideoEnabled(): Boolean
        fun isSelfAudioAttached(): Boolean
        fun isSelfVideoAttached(): Boolean
        fun currentSelfUsername(): String
        fun onPeerPresenceChanged(snapshot: List<RtcPeerStatus>)
        fun onRemoteVideoTrack(ownerId: String, track: VideoTrack?)
        fun onConnectedReady()
    }

    data class RtcPeerStatus(
        val peerId: String,
        val username: String,
        val pingMs: Long?,
        val audioAttached: Boolean,
        val videoAttached: Boolean,
        val audioLevel: Float
    )

    private data class PeerPresence(
        var username: String,
        var pingMs: Long?,
        var audioAttached: Boolean,
        var videoAttached: Boolean,
        var audioLevel: Float
    )

    private data class RemoteTrackInfo(
        val receiverId: String,
        val trackId: String,
        val track: MediaStreamTrack,
        val ownerId: String,
        val streamId: String?
    )

    private data class RemoteVideoTrackInfo(
        val receiverId: String,
        val trackId: String,
        val track: VideoTrack,
        val ownerId: String,
        val streamId: String?
    )

    private val ownerThread = Thread.currentThread()

    private var subscribePeerConnection: PeerConnection? = null
    private var selfPeerId: String? = null
    private var hasJoinedRoom = false

    private val remoteAudioTracks: MutableMap<String, RemoteTrackInfo> = ConcurrentHashMap()
    private val remoteVideoTracks: MutableMap<String, RemoteVideoTrackInfo> = ConcurrentHashMap()


    private val remoteTrackOwners: MutableMap<String, String> = ConcurrentHashMap()


    private val announcedTrackOwners: MutableMap<String, String> = ConcurrentHashMap()
    private val announcedTrackKinds: MutableMap<String, String> = ConcurrentHashMap()
    private val announcedStreamOwners: MutableMap<String, String> = ConcurrentHashMap()

    private val peerPresences: MutableMap<String, PeerPresence> = ConcurrentHashMap()
    private val knownPeerIds = linkedSetOf<String>()

    private var prevOutboundEnergy: Double? = null
    private var prevOutboundDur: Double? = null
    private val prevInboundStats = mutableMapOf<String, Pair<Double, Double>>()

    fun setPeerConnection(pc: PeerConnection?) {
        checkThread()
        subscribePeerConnection = pc
    }

    fun selfPeerId(): String? = selfPeerId

    fun hasJoinedRoom(): Boolean = hasJoinedRoom

    fun resetBeforeConnect(username: String) {
        checkThread()
        selfPeerId = null
        hasJoinedRoom = false
        subscribePeerConnection = null
        remoteAudioTracks.clear()
        remoteVideoTracks.clear()
        remoteTrackOwners.clear()
        announcedTrackOwners.clear()
        announcedTrackKinds.clear()
        peerPresences.clear()
        knownPeerIds.clear()
        prevInboundStats.clear()
        announcedStreamOwners.clear()
        prevOutboundEnergy = null
        prevOutboundDur = null

        if (username.isNotBlank()) {
            debugLog(TAG, "Prepare connect as username=$username")
        }
        publishPeerPresence()
    }

    fun clearAll() {
        checkThread()
        selfPeerId = null
        hasJoinedRoom = false
        subscribePeerConnection = null
        remoteAudioTracks.clear()
        remoteVideoTracks.clear()
        remoteTrackOwners.clear()
        announcedTrackOwners.clear()
        announcedTrackKinds.clear()
        peerPresences.clear()
        knownPeerIds.clear()
        prevInboundStats.clear()
        announcedStreamOwners.clear()
        prevOutboundEnergy = null
        prevOutboundDur = null
        publishPeerPresence()
    }

    fun onJoinAccepted(peerId: String?, assignedUsername: String?, peers: JSONArray?) {
        checkThread()
        val id = peerId?.takeIf { it.isNotBlank() && it != "—" }
        selfPeerId = id
        hasJoinedRoom = id != null

        remoteAudioTracks.clear()
        remoteVideoTracks.clear()
        remoteTrackOwners.clear()
        announcedTrackOwners.clear()
        announcedTrackKinds.clear()
        peerPresences.clear()
        knownPeerIds.clear()
        prevInboundStats.clear()

        replacePeerList(peers)

        selfPeerId?.let { self ->
            knownPeerIds += self
            applyPeerPresence(self, assignedUsername ?: callbacks.currentSelfUsername(), null)
            setPeerAudioAttachment(self, callbacks.isSelfAudioAttached())
            setPeerVideoAttachment(self, callbacks.isSelfVideoAttached())
        }

        callbacks.ensureLocalAudioSender()
        if (callbacks.isLocalVideoEnabled()) {
            callbacks.ensureLocalVideoSender()
        }

        publishPeerPresence()
    }

    fun onPeersSnapshot(peers: JSONArray?) {
        checkThread()
        replacePeerList(peers)
    }

    fun onPeerJoined(peerId: String?, username: String?, pingMs: Long?) {
        checkThread()
        val id = peerId?.takeIf { it.isNotBlank() } ?: return
        knownPeerIds += id
        applyPeerPresence(id, username, pingMs)
        recomputePeerAudioAttachment(id)
        recomputePeerVideoAttachment(id)
    }

    fun onPeerLeft(peerId: String?) {
        checkThread()
        val id = peerId?.takeIf { it.isNotBlank() } ?: return
        removeRemoteTracksForPeer(id)
        removePeerPresence(id)
        if (id != selfPeerId) knownPeerIds.remove(id)

        val staleTracks = remoteTrackOwners.filterValues { it == id }.keys.toList()
        staleTracks.forEach { remoteTrackOwners.remove(it) }
        val staleAnnounced = announcedTrackOwners.filterValues { it == id }.keys.toList()
        staleAnnounced.forEach {
            announcedTrackOwners.remove(it)
            announcedTrackKinds.remove(it)
        }

        publishPeerPresence()
    }

    fun onPeerPing(peerId: String?, pingMs: Long?) {
        checkThread()
        applyPeerPresence(peerId, null, pingMs)
    }


    fun onTrackPublished(
        peerId: String?,
        ownerId: String? = null,
        trackKey: String? = null,
        trackId: String?,
        kind: String?,
        streamId: String? = null
    ) {
        checkThread()

        val owner = ownerId
            ?.takeIf { it.isNotBlank() }
            ?: peerId?.takeIf { it.isNotBlank() }
            ?: return

        knownPeerIds += owner

        val ids = listOf(trackKey, trackId)
            .mapNotNull { it?.takeIf { v -> v.isNotBlank() } }
            .distinct()

        ids.forEach { id ->
            announcedTrackOwners[id] = owner
            remoteTrackOwners[id] = owner
            if (!kind.isNullOrBlank()) announcedTrackKinds[id] = kind
        }

        streamId?.takeIf { it.isNotBlank() }?.let {
            announcedStreamOwners[it] = owner
        }

        applyPeerPresence(owner, null, null)

        debugLog(
            TAG,
            "Announced remote track owner=$owner trackKey=${trackKey.orEmpty()} trackId=${trackId.orEmpty()} kind=${kind.orEmpty()} stream=${streamId.orEmpty()}"
        )
    }

    fun onTrackUnpublished(
        peerId: String?,
        ownerId: String? = null,
        trackKey: String? = null,
        trackId: String?,
        kind: String?
    ) {
        checkThread()

        val owner = ownerId
            ?.takeIf { it.isNotBlank() }
            ?: peerId?.takeIf { it.isNotBlank() }

        val ids = listOf(trackKey, trackId)
            .mapNotNull { it?.takeIf { v -> v.isNotBlank() } }
            .distinct()

        if (ids.isNotEmpty()) {

            val audioReceiverIds = remoteAudioTracks
                .filterValues { it.trackId in ids }
                .keys
                .toList()

            audioReceiverIds.forEach { removeRemoteReceiverAudio(it) }

            val videoReceiverIds = remoteVideoTracks
                .filterValues { it.trackId in ids }
                .keys
                .toList()

            videoReceiverIds.forEach { removeRemoteReceiverVideo(it) }

            ids.forEach { id ->
                remoteTrackOwners.remove(id)
                announcedTrackOwners.remove(id)
                announcedTrackKinds.remove(id)
            }
            if (kind == "video" && owner != null) {
                callbacks.onRemoteVideoTrack(owner, null)
                setPeerVideoAttachment(owner, false)
                publishPeerPresence()
            }
        } else if (owner != null) {

            when (kind) {
                "audio" -> remoteAudioTracks
                    .filterValues { it.ownerId == owner }
                    .keys
                    .toList()
                    .forEach { removeRemoteReceiverAudio(it) }

                "video" -> remoteVideoTracks
                    .filterValues { it.ownerId == owner }
                    .keys
                    .toList()
                    .forEach { removeRemoteReceiverVideo(it) }

                else -> removeRemoteTracksForPeer(owner)
            }
        }

        owner?.let {
            recomputePeerAudioAttachment(it)
            recomputePeerVideoAttachment(it)
        }

        publishPeerPresence()
    }

    fun handleRemoteTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
        checkThread()
        onRemoteReceiverTrack(
            receiverId = receiver.safeId() ?: receiver.hashCode().toString(),
            track = receiver.track(),
            streamOwner = streams.firstOrNull()?.id
        )
    }

    fun handleRemoteTrack(transceiver: RtpTransceiver) {
        checkThread()
        val receiver = transceiver.receiver ?: return
        onRemoteReceiverTrack(
            receiverId = receiver.safeId() ?: receiver.hashCode().toString(),
            track = receiver.track(),
            streamOwner = null
        )
    }

    fun cleanupRemoteTracks() {
        checkThread()
        val deadAudioReceiverIds = remoteAudioTracks
            .filterValues { it.track.safeState() != MediaStreamTrack.State.LIVE }
            .keys
            .toList()
        deadAudioReceiverIds.forEach { removeRemoteReceiverAudio(it) }

        val deadVideoReceiverIds = remoteVideoTracks
            .filterValues { it.track.safeState() != MediaStreamTrack.State.LIVE }
            .keys
            .toList()
        deadVideoReceiverIds.forEach { removeRemoteReceiverVideo(it) }
    }

    fun computeAudioLevels(report: RTCStatsReport) {
        checkThread()
        val remoteLevels = mutableMapOf<String, Double>()
        var localLevel: Double? = null
        var outEnergy: Double? = null
        var outDur: Double? = null

        for ((_, stat) in report.statsMap) {
            when (stat.type) {
                "outbound-rtp" -> {
                    val mediaType = stat.members["mediaType"] ?: stat.members["kind"]
                    if (mediaType?.toString() == "audio") {
                        localLevel = (stat.members["audioLevel"] as? Number)?.toDouble() ?: localLevel
                        outEnergy = (stat.members["totalAudioEnergy"] as? Number)?.toDouble() ?: outEnergy
                        outDur = (stat.members["totalSamplesDuration"] as? Number)?.toDouble() ?: outDur
                    }
                }

                "inbound-rtp" -> {
                    val mediaType = stat.members["mediaType"] ?: stat.members["kind"]
                    if (mediaType?.toString() == "audio") {
                        val statId = stat.id
                        val trackId = stat.members["trackIdentifier"]?.toString()
                        val ownerId = trackId
                            ?.let { remoteTrackOwners[it] ?: announcedTrackOwners[it] }
                            ?.takeIf { knownPeerIds.contains(it) }

                        val audioLevel = (stat.members["audioLevel"] as? Number)?.toDouble()
                        val level = if (audioLevel != null) {
                            audioLevel.coerceIn(0.0, 1.0)
                        } else {
                            val energy = (stat.members["totalAudioEnergy"] as? Number)?.toDouble()
                            val dur = (stat.members["totalSamplesDuration"] as? Number)?.toDouble()
                            if (energy == null || dur == null) {
                                prevInboundStats[statId] = prevInboundStats[statId] ?: (0.0 to 0.0)
                                null
                            } else {
                                val prev = prevInboundStats[statId]
                                val dE = if (prev != null) (energy - prev.first).coerceAtLeast(0.0) else 0.0
                                val dD = if (prev != null) (dur - prev.second).coerceAtLeast(1e-6) else 1e-6
                                prevInboundStats[statId] = energy to dur
                                (if (prev != null) (dE / dD) else 0.0).coerceIn(0.0, 1.0)
                            }
                        }

                        if (ownerId != null && level != null) {
                            val prev = remoteLevels[ownerId] ?: 0.0
                            if (level > prev) remoteLevels[ownerId] = level
                        }
                    }
                }
            }
        }

        if (localLevel == null && outEnergy != null && outDur != null) {
            val prevE = prevOutboundEnergy
            val prevD = prevOutboundDur
            if (prevE != null && prevD != null) {
                val dE = (outEnergy - prevE).coerceAtLeast(0.0)
                val dD = (outDur - prevD).coerceAtLeast(1e-6)
                localLevel = (dE / dD).coerceIn(0.0, 1.0)
            }
            prevOutboundEnergy = outEnergy
            prevOutboundDur = outDur
        }

        updatePeerAudioLevels(
            levels = remoteLevels.mapValues { it.value.toFloat() },
            localLevel = (localLevel ?: 0.0).toFloat()
        )
    }

    private fun onRemoteReceiverTrack(receiverId: String, track: MediaStreamTrack?, streamOwner: String?) {
        val mediaTrack = track ?: return
        val trackId = mediaTrack.safeId()
        if (trackId.isNullOrBlank()) {
            diagnosticWarning(TAG, "Remote track without id")
            return
        }

        val previousOwner = when (mediaTrack.kind()) {
            "audio" -> remoteAudioTracks[receiverId]?.ownerId
            "video" -> remoteVideoTracks[receiverId]?.ownerId
            else -> null
        }

        val ownerId = resolveOwnerId(trackId, streamOwner, previousOwner)
        if (ownerId == null) {
            diagnosticWarning(TAG, "Remote ${mediaTrack.kind()} track is not mapped yet: receiver=$receiverId track=$trackId stream=$streamOwner")
            return
        }

        remoteTrackOwners[trackId] = ownerId
        knownPeerIds += ownerId
        ensurePeerPresenceEntry(ownerId)

        when (mediaTrack.kind()) {
            "audio" -> {
                val existing = remoteAudioTracks[receiverId]
                if (existing != null && existing.trackId == trackId && existing.ownerId == ownerId) {
                    runCatching { mediaTrack.setEnabled(true) }
                    if (setPeerAudioAttachment(ownerId, true)) publishPeerPresence()
                    return
                }

                removeRemoteReceiverAudio(receiverId)
                runCatching { mediaTrack.setEnabled(true) }
                remoteAudioTracks[receiverId] = RemoteTrackInfo(
                    receiverId = receiverId,
                    trackId = trackId,
                    track = mediaTrack,
                    ownerId = ownerId,
                    streamId = streamOwner
                )
                if (setPeerAudioAttachment(ownerId, true)) publishPeerPresence()
                debugLog(TAG, "Remote audio receiver=$receiverId track=$trackId owner=$ownerId stream=$streamOwner")
            }

            "video" -> {
                val videoTrack = mediaTrack as? VideoTrack ?: return


                runCatching { videoTrack.setEnabled(true) }

                val existing = remoteVideoTracks[receiverId]
                if (existing != null && existing.trackId == trackId && existing.ownerId == ownerId) {
                    callbacks.onRemoteVideoTrack(ownerId, videoTrack)
                    if (setPeerVideoAttachment(ownerId, true)) publishPeerPresence()
                    return
                }

                removeRemoteReceiverVideo(receiverId)

                remoteVideoTracks[receiverId] = RemoteVideoTrackInfo(
                    receiverId = receiverId,
                    trackId = trackId,
                    track = videoTrack,
                    ownerId = ownerId,
                    streamId = streamOwner
                )

                callbacks.onRemoteVideoTrack(ownerId, videoTrack)

                if (setPeerVideoAttachment(ownerId, true)) publishPeerPresence()

                debugLog(TAG, "Remote video receiver=$receiverId track=$trackId owner=$ownerId stream=$streamOwner")
            }

            else -> diagnosticWarning(TAG, "Unknown remote media kind=${mediaTrack.kind()} track=$trackId")
        }
    }

    private fun replacePeerList(array: JSONArray?) {
        val previous = HashMap(peerPresences)
        peerPresences.clear()

        val entryMap = mutableMapOf<String, org.json.JSONObject>()
        val newKnown = mutableSetOf<String>()

        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("peerId")
                if (id.isBlank()) continue
                entryMap[id] = obj
                newKnown += id
            }
        }

        selfPeerId?.takeIf { it.isNotBlank() }?.let { newKnown += it }

        knownPeerIds.clear()
        knownPeerIds.addAll(newKnown)

        val staleAudioReceiverIds = remoteAudioTracks
            .filterValues { !knownPeerIds.contains(it.ownerId) }
            .keys
            .toList()
        staleAudioReceiverIds.forEach { removeRemoteReceiverAudio(it) }

        val staleVideoReceiverIds = remoteVideoTracks
            .filterValues { !knownPeerIds.contains(it.ownerId) }
            .keys
            .toList()
        staleVideoReceiverIds.forEach { removeRemoteReceiverVideo(it) }

        entryMap.forEach { (id, obj) ->
            if (!knownPeerIds.contains(id)) return@forEach
            val resolvedName = when {
                selfPeerId != null && id == selfPeerId && callbacks.currentSelfUsername().isNotBlank() -> callbacks.currentSelfUsername()
                else -> obj.optString("username")
            }
            val hasRtt = obj.has("rtt") && !obj.isNull("rtt")
            val rtt = if (hasRtt) obj.optLong("rtt", -1L).takeIf { it >= 0 } else null
            val prev = previous[id]

            val remoteAudioAttached = remoteAudioTracks.values.any {
                it.ownerId == id && it.track.safeState() == MediaStreamTrack.State.LIVE
            }
            val remoteVideoAttached = remoteVideoTracks.values.any {
                it.ownerId == id && it.track.safeState() == MediaStreamTrack.State.LIVE
            }

            peerPresences[id] = PeerPresence(
                username = resolvedName.ifBlank { prev?.username ?: "" },
                pingMs = rtt ?: prev?.pingMs,
                audioAttached = if (id == selfPeerId) callbacks.isSelfAudioAttached() else remoteAudioAttached,
                videoAttached = if (id == selfPeerId) callbacks.isSelfVideoAttached() else remoteVideoAttached,
                audioLevel = prev?.audioLevel ?: 0f
            )
        }

        val self = selfPeerId
        if (self != null && knownPeerIds.contains(self) && !peerPresences.containsKey(self)) {
            val prev = previous[self]
            peerPresences[self] = PeerPresence(
                username = callbacks.currentSelfUsername(),
                pingMs = prev?.pingMs,
                audioAttached = callbacks.isSelfAudioAttached(),
                videoAttached = callbacks.isSelfVideoAttached(),
                audioLevel = prev?.audioLevel ?: 0f
            )
        }

        publishPeerPresence()
    }

    private fun applyPeerPresence(peerId: String?, username: String?, pingMs: Long?) {
        val id = peerId?.takeIf { it.isNotBlank() } ?: return
        if (!knownPeerIds.contains(id)) {
            knownPeerIds += id
        }

        val info = ensurePeerPresenceEntry(id) ?: return
        var changed = false

        val effectiveName = when {
            id == selfPeerId && callbacks.currentSelfUsername().isNotBlank() -> callbacks.currentSelfUsername()
            !username.isNullOrBlank() -> username
            else -> info.username
        }

        if (info.username != effectiveName) {
            info.username = effectiveName
            changed = true
        }
        if (pingMs != null && info.pingMs != pingMs) {
            info.pingMs = pingMs
            changed = true
        }
        if (id == selfPeerId) {
            if (setPeerAudioAttachment(id, callbacks.isSelfAudioAttached())) changed = true
            if (setPeerVideoAttachment(id, callbacks.isSelfVideoAttached())) changed = true
        }

        if (changed) publishPeerPresence()
    }

    private fun removePeerPresence(peerId: String?) {
        val id = peerId?.takeIf { it.isNotBlank() } ?: return
        if (peerPresences.remove(id) != null) publishPeerPresence()
    }

    private fun publishPeerPresence() {
        val snapshot = peerPresences.entries.map { (id, info) ->
            val name = if (id == selfPeerId && callbacks.currentSelfUsername().isNotBlank()) {
                callbacks.currentSelfUsername()
            } else {
                info.username
            }
            val audioAttached = if (id == selfPeerId) callbacks.isSelfAudioAttached() else info.audioAttached
            val videoAttached = if (id == selfPeerId) callbacks.isSelfVideoAttached() else info.videoAttached
            val level = if (audioAttached) info.audioLevel else 0f

            RtcPeerStatus(
                peerId = id,
                username = name,
                pingMs = info.pingMs,
                audioAttached = audioAttached,
                videoAttached = videoAttached,
                audioLevel = level
            )
        }.sortedWith(
            compareBy<RtcPeerStatus> { it.peerId != selfPeerId }
                .thenBy { (it.username.ifBlank { it.peerId }).lowercase() }
        )

        callbacks.onPeerPresenceChanged(snapshot)
    }

    private fun ensurePeerPresenceEntry(peerId: String): PeerPresence? {
        if (peerId.isBlank()) return null
        if (!knownPeerIds.contains(peerId)) knownPeerIds += peerId
        val existing = peerPresences[peerId]
        if (existing != null) return existing

        val presence = PeerPresence(
            username = if (peerId == selfPeerId) callbacks.currentSelfUsername() else "",
            pingMs = null,
            audioAttached = peerId == selfPeerId && callbacks.isSelfAudioAttached(),
            videoAttached = peerId == selfPeerId && callbacks.isSelfVideoAttached(),
            audioLevel = 0f
        )
        peerPresences[peerId] = presence
        return presence
    }

    private fun setPeerAudioAttachment(peerId: String, attached: Boolean): Boolean {
        if (peerId.isBlank()) return false
        val info = ensurePeerPresenceEntry(peerId) ?: return false

        var changed = false
        if (info.audioAttached != attached) {
            info.audioAttached = attached
            changed = true
        }
        if (!attached && info.audioLevel != 0f) {
            info.audioLevel = 0f
            changed = true
        }
        return changed
    }

    private fun setPeerVideoAttachment(peerId: String, attached: Boolean): Boolean {
        if (peerId.isBlank()) return false
        val info = ensurePeerPresenceEntry(peerId) ?: return false
        if (info.videoAttached == attached) return false
        info.videoAttached = attached
        return true
    }

    private fun recomputePeerAudioAttachment(peerId: String) {
        if (peerId.isBlank()) return
        val attached = remoteAudioTracks.values.any {
            it.ownerId == peerId && it.track.safeState() == MediaStreamTrack.State.LIVE
        }
        if (setPeerAudioAttachment(peerId, attached)) publishPeerPresence()
    }

    private fun recomputePeerVideoAttachment(peerId: String) {
        if (peerId.isBlank()) return
        val attached = remoteVideoTracks.values.any {
            it.ownerId == peerId && it.track.safeState() == MediaStreamTrack.State.LIVE
        }
        if (setPeerVideoAttachment(peerId, attached)) publishPeerPresence()
    }

    private fun updatePeerAudioLevels(levels: Map<String, Float>, localLevel: Float) {
        var changed = false

        selfPeerId?.let { id ->
            val info = ensurePeerPresenceEntry(id)
            if (info != null) {
                val clampedLocal = localLevel.coerceIn(0f, 1f)
                if (info.audioLevel != clampedLocal) {
                    info.audioLevel = clampedLocal
                    changed = true
                }
                if (setPeerAudioAttachment(id, callbacks.isSelfAudioAttached())) changed = true
                if (setPeerVideoAttachment(id, callbacks.isSelfVideoAttached())) changed = true
            }
        }

        levels.forEach { (peerId, level) ->
            if (peerId == selfPeerId) return@forEach
            val clamped = level.coerceIn(0f, 1f)
            val info = ensurePeerPresenceEntry(peerId) ?: return@forEach
            if (info.audioLevel != clamped) {
                info.audioLevel = clamped
                changed = true
            }
            if (clamped > 0f && setPeerAudioAttachment(peerId, true)) changed = true
        }

        peerPresences.forEach { (peerId, info) ->
            if (peerId == selfPeerId) return@forEach
            if (!levels.containsKey(peerId) && info.audioLevel != 0f) {
                info.audioLevel = 0f
                changed = true
            }
        }

        if (changed) publishPeerPresence()
    }

    private fun removeRemoteTracksForPeer(peerId: String?) {
        val id = peerId?.takeIf { it.isNotBlank() } ?: return

        val audioReceiverIds = remoteAudioTracks.filterValues { it.ownerId == id }.keys.toList()
        audioReceiverIds.forEach { removeRemoteReceiverAudio(it) }
        setPeerAudioAttachment(id, false)

        val videoReceiverIds = remoteVideoTracks.filterValues { it.ownerId == id }.keys.toList()
        videoReceiverIds.forEach { removeRemoteReceiverVideo(it) }
        callbacks.onRemoteVideoTrack(id, null)
        setPeerVideoAttachment(id, false)
    }

    private fun removeRemoteReceiverAudio(receiverId: String) {
        val removed = remoteAudioTracks.remove(receiverId) ?: return
        val sameTrackStillUsed = remoteAudioTracks.values.any { it.trackId == removed.trackId } ||
                remoteVideoTracks.values.any { it.trackId == removed.trackId }
        if (!sameTrackStillUsed) {
            remoteTrackOwners.remove(removed.trackId)
        }

        recomputePeerAudioAttachment(removed.ownerId)
    }

    private fun removeRemoteReceiverVideo(receiverId: String) {
        val removed = remoteVideoTracks.remove(receiverId) ?: return

        val sameTrackStillUsed = remoteVideoTracks.values.any { it.trackId == removed.trackId } ||
                remoteAudioTracks.values.any { it.trackId == removed.trackId }

        if (!sameTrackStillUsed) {
            remoteTrackOwners.remove(removed.trackId)
        }


        val replacement = remoteVideoTracks.values.firstOrNull {
            it.ownerId == removed.ownerId &&
                    it.track.safeState() == MediaStreamTrack.State.LIVE
        }

        if (replacement != null) {

            callbacks.onRemoteVideoTrack(removed.ownerId, replacement.track)
        } else {

            callbacks.onRemoteVideoTrack(removed.ownerId, null)
        }

        recomputePeerVideoAttachment(removed.ownerId)
    }
    fun onTracksSnapshot(tracks: JSONArray?) {
        checkThread()
        if (tracks == null) return

        for (i in 0 until tracks.length()) {
            val obj = tracks.optJSONObject(i) ?: continue

            onTrackPublished(
                peerId = obj.optString("peerId"),
                ownerId = obj.optString("ownerId"),
                trackKey = obj.optString("trackKey"),
                trackId = obj.optString("trackId"),
                kind = obj.optString("kind"),
                streamId = obj.optString("streamId")
            )
        }

        publishPeerPresence()
    }
    private fun resolveOwnerId(trackId: String, streamOwner: String?, fallbackOwner: String?): String? {
        if (!streamOwner.isNullOrBlank() && knownPeerIds.contains(streamOwner) && streamOwner != selfPeerId) {
            return streamOwner
        }

        if (!streamOwner.isNullOrBlank()) {
            announcedStreamOwners[streamOwner]?.let { owner ->
                if (owner != selfPeerId) return owner
            }
        }

        announcedTrackOwners[trackId]?.let { owner ->
            if (owner != selfPeerId) return owner
        }

        remoteTrackOwners[trackId]?.let { owner ->
            if (owner != selfPeerId) return owner
        }

        if (!fallbackOwner.isNullOrBlank() && knownPeerIds.contains(fallbackOwner) && fallbackOwner != selfPeerId) {
            return fallbackOwner
        }

        val remotePeers = knownPeerIds.filter { it != selfPeerId }
        if (remotePeers.size == 1) {
            return remotePeers.first()
        }

        return null
    }


    fun onSignalingStateChanged(newState: PeerConnection.SignalingState) {
        checkThread()
        debugLog(TAG, "Ignoring legacy signaling state callback in split-PC controller: $newState")
    }

    fun onIceFailed() {
        checkThread()
        debugLog(TAG, "Ignoring legacy ICE restart request in split-PC controller")
    }

    fun onServerRenegotiate() {
        checkThread()
        debugLog(TAG, "Ignoring legacy server-renegotiate in split-PC controller")
    }

    fun handleRemoteAnswerSdp(sdp: String) {
        checkThread()
        diagnosticWarning(TAG, "Ignoring legacy remote answer in split-PC controller")
    }

    fun handleRemoteOfferSdp(sdp: String) {
        checkThread()
        diagnosticWarning(TAG, "Ignoring legacy remote offer in split-PC controller")
    }

    fun addOrQueueRemoteIce(candidate: org.webrtc.IceCandidate) {
        checkThread()
        diagnosticWarning(TAG, "Ignoring legacy untargeted ICE in split-PC controller")
    }

    fun requestNegotiation(reason: String, iceRestart: Boolean = false) {
        checkThread()
        debugLog(TAG, "Ignoring legacy negotiation request in split-PC controller: reason=$reason iceRestart=$iceRestart")
    }

    private fun checkThread() {
        if (Thread.currentThread() !== ownerThread) {
            diagnosticWarning(TAG, "Called from unexpected thread: ${Thread.currentThread().name}, expected=${ownerThread.name}")
        }
    }

    private fun MediaStreamTrack?.safeState(): MediaStreamTrack.State? {
        return try {
            this?.state()
        } catch (_: Throwable) {
            null
        }
    }

    private fun MediaStreamTrack?.safeId(): String? {
        return try {
            this?.id()
        } catch (_: Throwable) {
            null
        }
    }

    private fun RtpReceiver.safeId(): String? {
        return try {
            id()
        } catch (_: Throwable) {
            null
        }
    }
}
