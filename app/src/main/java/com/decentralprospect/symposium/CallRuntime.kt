package com.decentralprospect.symposium

import android.content.BroadcastReceiver
import android.content.Context
import android.media.AudioDeviceCallback
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class PeerStatus(
    val peerId: String,
    val username: String,
    val pingMs: Long?,
    val audioAttached: Boolean,
    val videoAttached: Boolean,
    val audioLevel: Float
)

internal interface CallPermissionRequester {
    fun requestPostNotifications()
    fun requestRecordAudio(startServiceAfterGrant: Boolean)
    fun requestCamera()
}

interface CallForegroundController {
    fun startForegroundForCall(microphone: Boolean)
    fun stopForegroundForCall()
}

internal class CallRuntime(
    internal val appContext: Context,
    private val foregroundController: CallForegroundController
) {
    internal val pinnedClients = ConcurrentHashMap<String, OkHttpClient>()
    internal var webSocket: WebSocket? = null
    internal var intentionalDisconnect = false
    internal var pendingStartCallServiceAfterMicPermission = false

    internal var telemetryEnabledState by mutableStateOf(false)
    internal var telemetryPromptShownState by mutableStateOf(false)
    internal var newRelicStarted = false
    internal var newRelicShutdownInThisProcess = false

    internal var eglBase: EglBase? = null
    internal var pcFactory: PeerConnectionFactory? = null
    internal var publishPeerConnection: PeerConnection? = null
    internal var subscribePeerConnection: PeerConnection? = null
    internal var audioDeviceModule: JavaAudioDeviceModule? = null
    internal var powerManager: PowerManager? = null
    internal var proximityWakeLock: PowerManager.WakeLock? = null
    internal var partialWakeLock: PowerManager.WakeLock? = null
    internal val wakeLockRefreshHandler = Handler(Looper.getMainLooper())
    internal var wakeLockRefreshRunning = false
    internal val wakeLockRefreshRunnable = object : Runnable {
        override fun run() {
            onWakeLockRefreshTick(this)
        }
    }

    internal var audioSource: AudioSource? = null
    internal var localAudioTrack: AudioTrack? = null
    internal var localAudioSender: RtpSender? = null
    internal var localAudioTransceiver: RtpTransceiver? = null
    internal val remoteAudioTracks = ConcurrentHashMap<String, AudioTrack>()

    internal var videoCapturer: CameraVideoCapturer? = null
    internal var activeCapturerName: String? = null
    internal var activeCameraFacing: String = "unknown"
    internal var activeCapturerBackend: String = "none"
    internal var surfaceTextureHelper: SurfaceTextureHelper? = null
    internal var videoSource: VideoSource? = null
    internal var localVideoTrack: VideoTrack? = null
    internal var localVideoSender: RtpSender? = null
    internal var localVideoTransceiver: RtpTransceiver? = null
    internal var lastCaptureWidth: Int = 0
    internal var lastCaptureHeight: Int = 0
    internal var lastCaptureFps: Int = 0
    internal var firstFrameSeen = false
    internal var lastFirstFrameUptimeMs: Long? = null
    internal var cameraRuntimeState: String = "idle"
    internal val cameraWatchdogHandler = Handler(Looper.getMainLooper())
    internal var cameraWatchdogToken = 0L

    internal val diagLogLines = java.util.ArrayDeque<String>()
    internal val diagLogMaxLines = 180
    internal var lastCameraDebugLine: String = ""

    internal var uiStateBinder: UiBinder? = null
    internal var permissionRequester: CallPermissionRequester? = null

    internal var statusUiState = "offline"
    internal var connectedUiState = false
    internal var peerIdUiState = "—"
    internal var iceUiState = "—"
    internal var pcUiState = "—"
    internal var cameraDebugUiState = "camera: idle"
    internal var pendingLobbyPeersUiState: List<LobbyPeerStatus> = emptyList()

    internal var selfUsername: String = ""
    internal var micEnabledState = false
    internal var videoEnabledState = false
    internal var outputEnabled = true
    internal var speakerphoneOn = false

    internal var preferredAudioRoute: AudioRoute = AudioRoute.EARPIECE
    internal var currentAudioRoute: AudioRoute = AudioRoute.EARPIECE
    internal var bluetoothScoStarted = false
    internal var audioRoutingMonitorStarted = false
    internal var audioDeviceCallback: AudioDeviceCallback? = null
    internal var scoStateReceiver: BroadcastReceiver? = null

    internal var localRole: String = ROLE_GUEST
    internal var lobbyWaiting = false
    internal var forcedMutedByModerator = false
    internal var selfHandRaised = false

    internal var joinedRoom = false
    internal var mediaOnline = false
    internal var publishPcState: String = "new"
    internal var subscribePcState: String = "new"
    internal var publishIceState: String = "new"
    internal var subscribeIceState: String = "new"

    internal var publishPcGeneration = 0L
    internal var subscribePcGeneration = 0L

    internal var subscribeProtocolGeneration = 0L
    internal var retiredSubscribeProtocolGeneration = 0L

    internal val publishNegotiationHandler = Handler(Looper.getMainLooper())
    internal var publishNegotiationScheduled = false
    internal var publishMakingOffer = false
    internal var publishPendingOffer = false
    internal var publishBootstrapDone = false
    internal val publishIceRestartHandler = Handler(Looper.getMainLooper())
    internal var publishIceRestartRunnable: Runnable? = null
    internal val subscribeRecoveryHandler = Handler(Looper.getMainLooper())
    internal var subscribeRecoveryRunnable: Runnable? = null

    internal var handlingSubscribeOffer = false
    internal var pendingSubscribeOffer: JSONObject? = null

    internal val queuedRemoteIcePublish = mutableListOf<QueuedIce>()
    internal val queuedRemoteIceSubscribe = mutableListOf<QueuedIce>()

    internal val pingHandler = Handler(Looper.getMainLooper())
    internal var pingLoopRunning = false
    internal var pingSeq = 0L
    internal val inFlightPings = ConcurrentHashMap<Long, Long>()
    internal var badPingStreak = 0
    internal var lastPingMs: Long? = null

    internal val statsHandler = Handler(Looper.getMainLooper())
    internal var statsPolling = false
    internal var lastStatsTelemetryAtMs = 0L
    internal var lastPingTelemetryAtMs = 0L

    internal var telemetrySessionId: String = newTelemetrySessionId()
    internal var conferenceConnectStartedAtMs = 0L
    internal var conferenceMediaOnlineAtMs = 0L
    internal var conferenceEndedSent = false
    internal var reconnectStartedAtMs = 0L
    internal var reconnectsInSession = 0
    internal var maxPingMsInSession = 0L
    internal var slowPingCountInSession = 0
    internal var criticalPingCountInSession = 0
    internal var pingReconnectCountInSession = 0
    internal var wsErrorCountInSession = 0
    internal var iceBadStateCountInSession = 0
    internal var pcBadStateCountInSession = 0
    internal var audioErrorCountInSession = 0
    internal var cameraErrorCountInSession = 0
    internal var statsWarningCountInSession = 0

    internal var lastWsUrl: String = ""
    internal var lastRoom: String = ""
    internal var lastUsername: String = ""
    internal var lastTlsPin: String = ""
    internal var lastModKey: String = ""
    internal var reconnectToken: String = ""

    internal var micPermissionInFlight = false
    internal var cameraPermissionInFlight = false
    internal var pendingVideoEnableAfterMicPermission = false
    internal var videoStartInProgress = false

    internal var reconnectMode by mutableStateOf(false)
    internal var reconnectAttemptCount by mutableStateOf(0)

    internal val reconnectHandler = Handler(Looper.getMainLooper())
    internal val reconnectRunnable = object : Runnable {
        override fun run() {
            onReconnectTick(this)
        }
    }

    internal var localTrackNamespace: String = newTrackNamespace()
    internal var localAudioTrackSeq = 0
    internal var localVideoTrackSeq = 0
    internal val stableClientId: String by lazy {
        val prefs = appContext.getSharedPreferences("rtc_client", Context.MODE_PRIVATE)
        prefs.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("client_id", it).apply()
        }
    }

    internal val rtcController by lazy {
        createRtcTrackNegotiationController()
    }

    internal val statsTick = object : Runnable {
        override fun run() {
            onStatsTick(this)
        }
    }

    internal fun initialize() {
        loadTelemetryPrivacyPrefs()
        startNewRelicTelemetry()
        powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val pm = powerManager!!

        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "${appContext.packageName}:RTC_Proximity"
            ).apply { setReferenceCounted(false) }
        }

        partialWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${appContext.packageName}:RTC_Partial"
        ).apply { setReferenceCounted(false) }

        initWebRtc()
        startAudioRoutingMonitor()
        updateCameraDebug("service-start")
    }

    internal fun attachUiBinder(binder: UiBinder) {
        uiStateBinder = binder
        binder.setStatus(statusUiState)
        binder.setConnected(connectedUiState)
        binder.setPeerId(peerIdUiState)
        binder.setIceState(iceUiState)
        binder.setPcState(pcUiState)
        binder.setMic(micEnabledState)
        binder.setVideo(videoEnabledState)
        binder.setOutput(outputEnabled)
        binder.setSpeaker(speakerphoneOn)
        binder.setCameraDebug(cameraDebugUiState)
        binder.updateLobbyPending(pendingLobbyPeersUiState)
        publishAudioRouteToUi()
        syncModerationStateToUi()
        publishDiagnosticPanel()
    }

    internal fun detachUiBinder(binder: UiBinder?) {
        if (binder == null || uiStateBinder === binder) {
            uiStateBinder = null
        }
    }

    internal fun attachPermissionRequester(requester: CallPermissionRequester) {
        permissionRequester = requester
    }

    internal fun detachPermissionRequester(requester: CallPermissionRequester?) {
        if (requester == null || permissionRequester === requester) {
            permissionRequester = null
        }
    }

    internal fun startCallService(microphone: Boolean = false) {
        foregroundController.startForegroundForCall(microphone)
    }

    internal fun stopCallService() {
        foregroundController.stopForegroundForCall()
    }

    internal fun shutdownBecauseServiceDestroyed() {
        intentionalDisconnect = true
        teardown()
        releaseWebRtc()
    }
}
