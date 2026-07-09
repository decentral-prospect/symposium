package com.decentralprospect.symposium

import androidx.compose.runtime.mutableStateMapOf
import org.webrtc.EglBase
import org.webrtc.VideoTrack

object VideoTracksStore {
    private const val SELF_FALLBACK_KEY = "__self__"
    private val tracks = mutableStateMapOf<String, VideoTrack>()

    fun snapshot(): Map<String, VideoTrack> = tracks

    fun setTrack(peerId: String?, track: VideoTrack?) {
        val key = peerId?.takeIf { it.isNotBlank() } ?: SELF_FALLBACK_KEY
        if (track == null) {
            tracks.remove(key)
        } else {
            tracks[key] = track
        }
    }

    fun moveSelfTrack(selfPeerId: String?) {
        val id = selfPeerId?.takeIf { it.isNotBlank() } ?: return
        val selfTrack = tracks.remove(SELF_FALLBACK_KEY) ?: return
        tracks[id] = selfTrack
    }

    fun retainOnlySelf(selfPeerId: String?) {
        val keep = mutableSetOf(SELF_FALLBACK_KEY)
        selfPeerId?.takeIf { it.isNotBlank() }?.let { keep += it }

        tracks.keys.toList()
            .filter { it !in keep }
            .forEach { tracks.remove(it) }
    }

    fun removePeer(peerId: String?) {
        val key = peerId?.takeIf { it.isNotBlank() } ?: SELF_FALLBACK_KEY
        tracks.remove(key)
    }

    fun clear() {
        tracks.clear()
    }
}

object VideoRenderContext {
    val eglBase: EglBase by lazy { EglBase.create() }
}
