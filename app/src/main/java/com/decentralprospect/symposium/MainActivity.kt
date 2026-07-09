package com.decentralprospect.symposium

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : ComponentActivity() {

    internal val remoteInstaller by lazy { RemoteInstaller(applicationContext) }

    internal var telemetryEnabledState by mutableStateOf(false)
    internal var telemetryPromptShownState by mutableStateOf(false)
    internal var appThemeModeState by mutableStateOf(AppThemeMode.SYSTEM)
    internal var newRelicStarted = false
    internal var newRelicShutdownInThisProcess = false

    internal var relayInstallStartedAtMs = 0L
    internal var relayInstallLastStage = "not_started"

    internal var incomingConnectLink by mutableStateOf<String?>(null)
    internal var callRuntimeState by mutableStateOf<CallRuntime?>(null)

    private var boundCallService: CallService? = null
    private var callServiceBound = false
    private var pendingUiBinder: UiBinder? = null
    private var lastAttachedBinder: UiBinder? = null
    private var pendingConnect: PendingConnect? = null

    internal var telemetrySessionId: String = UUID.randomUUID().toString().replace("-", "").take(16)
    internal var localRole: String = ROLE_GUEST
    internal var reconnectMode = false
    internal var reconnectAttemptCount = 0
    internal var publishPcState: String = "new"
    internal var subscribePcState: String = "new"
    internal var publishIceState: String = "new"
    internal var subscribeIceState: String = "new"
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
    internal var lastPingTelemetryAtMs = 0L
    internal var lastStatsTelemetryAtMs = 0L

    private data class PendingConnect(
        val url: String,
        val room: String,
        val username: String,
        val tlsPin: String,
        val modKey: String
    )

    private val permissionRequester = object : CallPermissionRequester {
        override fun requestPostNotifications() {
            if (Build.VERSION.SDK_INT >= 33) {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        override fun requestRecordAudio(startServiceAfterGrant: Boolean) {
            callRuntimeState?.pendingStartCallServiceAfterMicPermission = startServiceAfterGrant
            requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
        }

        override fun requestCamera() {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val callService = (service as CallService.LocalBinder).service()
            boundCallService = callService
            callServiceBound = true
            val runtime = callService.runtime
            runtime.attachPermissionRequester(permissionRequester)
            callRuntimeState = runtime
            pendingUiBinder?.let { binder ->
                runtime.attachUiBinder(binder)
                lastAttachedBinder = binder
            }
            pendingConnect?.let { pending ->
                pendingConnect = null
                runtime.connect(pending.url, pending.room, pending.username, pending.tlsPin, pending.modKey)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            callRuntimeState?.detachPermissionRequester(permissionRequester)
            boundCallService = null
            callServiceBound = false
            callRuntimeState = null
        }
    }

    internal val requestRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        callRuntimeState?.onRecordAudioPermissionResult(granted)
    }

    internal val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        callRuntimeState?.onCameraPermissionResult(granted)
    }

    internal val requestPostNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "POST_NOTIFICATIONS granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeMainActivityAfterCreate()
    }

    override fun onStart() {
        super.onStart()
        ensureCallServiceBound()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingConnectLink = intent.dataString
    }

    override fun onStop() {
        super.onStop()
        callRuntimeState?.detachPermissionRequester(permissionRequester)
    }

    override fun onDestroy() {
        lastAttachedBinder?.let { binder -> callRuntimeState?.detachUiBinder(binder) }
        lastAttachedBinder = null
        if (callServiceBound) {
            runCatching { unbindService(serviceConnection) }
            callServiceBound = false
        }
        boundCallService = null
        callRuntimeState = null
        super.onDestroy()
    }

    internal fun requestNotificationPermissionIfNeeded() {
        permissionRequester.requestPostNotifications()
    }

    internal fun ensureCallServiceBound() {
        if (callServiceBound) return
        val intent = Intent(this, CallService::class.java).apply {
            action = CallService.ACTION_BIND_ONLY
        }
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    internal fun attachCallUiBinder(binder: UiBinder) {
        pendingUiBinder = binder
        lastAttachedBinder = binder
        callRuntimeState?.attachUiBinder(binder)
    }

    internal fun connectViaCallService(
        url: String,
        room: String,
        username: String,
        tlsPin: String,
        modKey: String
    ) {
        val runtime = callRuntimeState
        if (runtime != null) {
            runtime.connect(url, room, username, tlsPin, modKey)
        } else {
            pendingConnect = PendingConnect(url, room, username, tlsPin, modKey)
            ensureCallServiceBound()
        }
    }
}
