package com.decentralprospect.symposium

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import java.util.UUID

internal fun CallRuntime.newTrackNamespace(): String = UUID.randomUUID().toString().replace("-", "")

internal fun CallRuntime.resetLocalTrackNamespace() {
    localTrackNamespace = newTrackNamespace()
    localAudioTrackSeq = 0
    localVideoTrackSeq = 0
}

internal fun CallRuntime.nextLocalAudioTrackId(): String {
    localAudioTrackSeq += 1
    return "audio-$localTrackNamespace-$localAudioTrackSeq"
}

internal fun CallRuntime.nextLocalVideoTrackId(): String {
    localVideoTrackSeq += 1
    return "video-$localTrackNamespace-$localVideoTrackSeq"
}

internal fun CallRuntime.headsetAvailable(am: AudioManager = callAudioManager()): Boolean {
    return hasBluetoothCallDevice(am) || hasBluetoothOutputDevice(am) || hasWiredHeadset(am)
}

internal fun CallRuntime.publishAudioRouteToUi() {
    val am = callAudioManager()
    postUi {
        uiStateBinder?.setAudioRoute(
            currentAudioRoute.name,
            headsetAvailable(am)
        )
    }
}

internal fun CallRuntime.setAudioRouteFromUi(raw: String) {
    val normalized = raw.trim().lowercase(Locale.US)
    val am = callAudioManager()

    val requestedRoute = when (normalized) {
        "speaker" -> AudioRoute.SPEAKER
        "headset" -> {
            when {
                hasBluetoothCallDevice(am) || hasBluetoothOutputDevice(am) -> AudioRoute.BLUETOOTH
                hasWiredHeadset(am) -> AudioRoute.WIRED_HEADSET
                else -> AudioRoute.EARPIECE
            }
        }
        "earpiece" -> AudioRoute.EARPIECE
        else -> AudioRoute.EARPIECE
    }

    preferredAudioRoute = requestedRoute
    setAudioRoute(requestedRoute, "ui-select:$normalized")
}

internal fun CallRuntime.isCallAudioActive(): Boolean {
    return webSocket != null || joinedRoom || mediaOnline || publishPeerConnection != null || subscribePeerConnection != null
}

internal fun CallRuntime.callAudioManager(): AudioManager {
    return appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}

internal fun CallRuntime.configureCallAudioMode(am: AudioManager = callAudioManager()) {
    am.mode = AudioManager.MODE_IN_COMMUNICATION
}

internal fun CallRuntime.startAudioRoutingMonitor() {
    if (audioRoutingMonitorStarted) return
    audioRoutingMonitorStarted = true

    val am = callAudioManager()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                postUi {
                    diagLog("Audio devices added", describeAudioDevices())
                    applyPreferredAudioRoute("audio-devices-added")
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                postUi {
                    diagLog("Audio devices removed", describeAudioDevices())
                    applyPreferredAudioRoute("audio-devices-removed")
                }
            }
        }
        audioDeviceCallback = callback
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }


    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return

            val state = intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR
            )
            val stateText = when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> "connected"
                AudioManager.SCO_AUDIO_STATE_CONNECTING -> "connecting"
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "disconnected"
                AudioManager.SCO_AUDIO_STATE_ERROR -> "error"
                else -> state.toString()
            }

            postUi {
                diagLog("Bluetooth SCO state", stateText)
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        bluetoothScoStarted = true
                        currentAudioRoute = AudioRoute.BLUETOOTH
                        speakerphoneOn = false
                        setSpeakerState(false)
                        updateCameraDebug("bt-sco-connected")
                    }

                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        bluetoothScoStarted = false
                        if (currentAudioRoute == AudioRoute.BLUETOOTH || preferredAudioRoute == AudioRoute.BLUETOOTH) {
                            applyPreferredAudioRoute("bt-sco-$stateText")
                        } else {
                            updateCameraDebug("bt-sco-$stateText")
                        }
                    }
                }
            }
        }
    }
    scoStateReceiver = receiver
    runCatching {
        appContext.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
    }.onFailure {
        Log.w(TAG, "Failed to register SCO receiver: ${it.message}")
    }

    diagLog("Audio routing monitor started", describeAudioDevices())
}

internal fun CallRuntime.stopAudioRoutingMonitor() {
    if (!audioRoutingMonitorStarted) return
    audioRoutingMonitorStarted = false

    val am = callAudioManager()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        audioDeviceCallback?.let { callback ->
            runCatching { am.unregisterAudioDeviceCallback(callback) }
        }
    }
    audioDeviceCallback = null

    scoStateReceiver?.let { receiver ->
        runCatching { appContext.unregisterReceiver(receiver) }
    }
    scoStateReceiver = null

    stopBluetoothSco(am)
    runCatching { am.isSpeakerphoneOn = false }
    diagLog("Audio routing monitor stopped")
}

internal fun CallRuntime.applyPreferredAudioRoute(reason: String) {
    if (!isCallAudioActive()) {
        diagLog("Skip audio route while idle", reason)
        return
    }

    val am = callAudioManager()
    val route = when {
        preferredAudioRoute == AudioRoute.SPEAKER -> AudioRoute.SPEAKER
        preferredAudioRoute == AudioRoute.BLUETOOTH && (hasBluetoothCallDevice(am) || hasBluetoothOutputDevice(am)) -> AudioRoute.BLUETOOTH
        preferredAudioRoute == AudioRoute.WIRED_HEADSET && hasWiredHeadset(am) -> AudioRoute.WIRED_HEADSET
        hasBluetoothCallDevice(am) -> AudioRoute.BLUETOOTH
        hasWiredHeadset(am) -> AudioRoute.WIRED_HEADSET
        else -> AudioRoute.EARPIECE
    }
    setAudioRoute(route, reason)
}

internal fun CallRuntime.bestNonSpeakerAudioRoute(am: AudioManager = callAudioManager()): AudioRoute {
    return when {
        hasBluetoothCallDevice(am) -> AudioRoute.BLUETOOTH
        hasWiredHeadset(am) -> AudioRoute.WIRED_HEADSET
        else -> AudioRoute.EARPIECE
    }
}

internal fun CallRuntime.setAudioRoute(requestedRoute: AudioRoute, reason: String) {
    val am = callAudioManager()
    configureCallAudioMode(am)
    setAudioRouteLegacy(am, requestedRoute, reason)
}

@Suppress("DEPRECATION")

internal fun CallRuntime.setAudioRouteLegacy(
    am: AudioManager,
    requestedRoute: AudioRoute,
    reason: String
) {
    val route = when (requestedRoute) {
        AudioRoute.BLUETOOTH -> if (hasBluetoothCallDevice(am)) AudioRoute.BLUETOOTH else bestNonSpeakerAudioRoute(am)
        AudioRoute.WIRED_HEADSET -> if (hasWiredHeadset(am)) AudioRoute.WIRED_HEADSET else bestNonSpeakerAudioRoute(am)
        AudioRoute.SPEAKER -> AudioRoute.SPEAKER
        AudioRoute.EARPIECE -> AudioRoute.EARPIECE
    }

    if (route != AudioRoute.BLUETOOTH) {
        stopBluetoothSco(am)
    }

    runCatching {
        am.isSpeakerphoneOn = route == AudioRoute.SPEAKER
    }.onFailure {
        Log.w(TAG, "Failed to set speakerphone=${route == AudioRoute.SPEAKER}: ${it.message}")
    }

    when (route) {
        AudioRoute.BLUETOOTH -> {
            releaseProximityLock()
            startBluetoothSco(am)
        }
        AudioRoute.WIRED_HEADSET -> releaseProximityLock()
        AudioRoute.SPEAKER -> releaseProximityLock()
        AudioRoute.EARPIECE -> acquireProximityLock()
    }

    currentAudioRoute = route
    speakerphoneOn = route == AudioRoute.SPEAKER
    setSpeakerState(speakerphoneOn)
    updateCameraDebug("audio-route-legacy:$reason")
    diagLog("Legacy audio route set", "route=$route requested=$requestedRoute reason=$reason devices=${describeAudioDevices()}")
    trackRtcEvent(
        "audio.route.changed",
        nrAttrs("route" to route.name, "requestedRoute" to requestedRoute.name, "reason" to reason)
    )
    publishAudioRouteToUi()
}

@Suppress("DEPRECATION")

internal fun CallRuntime.startBluetoothSco(am: AudioManager) {
    if (!hasBluetoothCallDevice(am) && !hasBluetoothOutputDevice(am)) {
        bluetoothScoStarted = false
        return
    }

    if (am.isBluetoothScoOn || bluetoothScoStarted) {
        bluetoothScoStarted = true
        return
    }

    runCatching {
        am.startBluetoothSco()
        am.isBluetoothScoOn = true
        bluetoothScoStarted = true
    }.onSuccess {
        diagLog("Bluetooth SCO start requested")
    }.onFailure {
        bluetoothScoStarted = false
        Log.w(TAG, "Bluetooth SCO start failed: ${it.message}")
        diagLog("Bluetooth SCO start failed", it.message)
    }
}

@Suppress("DEPRECATION")

internal fun CallRuntime.stopBluetoothSco(am: AudioManager = callAudioManager()) {
    runCatching {
        if (am.isBluetoothScoOn || bluetoothScoStarted) {
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
        }
    }.onFailure {
        Log.w(TAG, "Bluetooth SCO stop failed: ${it.message}")
    }
    bluetoothScoStarted = false
}

internal fun CallRuntime.resetAudioRoutingForIdle() {
    val am = callAudioManager()

    stopBluetoothSco(am)
    runCatching { am.isSpeakerphoneOn = false }

    runCatching { am.mode = AudioManager.MODE_NORMAL }
    currentAudioRoute = AudioRoute.EARPIECE
    preferredAudioRoute = AudioRoute.EARPIECE
    speakerphoneOn = false
    bluetoothScoStarted = false
    setSpeakerState(false)
    releaseProximityLock()
    updateCameraDebug("audio-idle")
    publishAudioRouteToUi()
}

@Suppress("DEPRECATION")

internal fun CallRuntime.hasBluetoothCallDevice(am: AudioManager = callAudioManager()): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        return runCatching {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            (outputs.asSequence() + inputs.asSequence()).any { device ->
                isBluetoothCommunicationDevice(device) ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        }.getOrElse {
            Log.w(TAG, "Bluetooth device lookup failed: ${it.message}")
            false
        }
    }
    return am.isBluetoothScoAvailableOffCall || am.isBluetoothScoOn
}

@Suppress("DEPRECATION")

internal fun CallRuntime.hasWiredHeadset(am: AudioManager = callAudioManager()): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.isSink && isWiredLikeDevice(device)
            }
        }.getOrElse {
            Log.w(TAG, "Wired output device lookup failed: ${it.message}")
            false
        }
    }
    return am.isWiredHeadsetOn
}

internal fun CallRuntime.hasBluetoothOutputDevice(am: AudioManager = callAudioManager()): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
    return runCatching {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.isSink && isBluetoothOutputDevice(device)
        }
    }.getOrElse {
        Log.w(TAG, "Bluetooth output device lookup failed: ${it.message}")
        false
    }
}

internal fun CallRuntime.isBluetoothCommunicationDevice(device: AudioDeviceInfo): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false

    if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        device.type == AudioDeviceInfo.TYPE_HEARING_AID
    ) {
        return true
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) return true
        if (device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) return true
    }

    return false
}

internal fun CallRuntime.isBluetoothOutputDevice(device: AudioDeviceInfo): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false

    if (isBluetoothCommunicationDevice(device)) return true
    if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return true

    return false
}

internal fun CallRuntime.audioRouteFromDevice(device: AudioDeviceInfo): AudioRoute {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        return when {
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
            isBluetoothCommunicationDevice(device) -> AudioRoute.BLUETOOTH
            isWiredLikeDevice(device) -> AudioRoute.WIRED_HEADSET
            else -> AudioRoute.EARPIECE
        }
    }
    return AudioRoute.EARPIECE
}

internal fun CallRuntime.isWiredLikeDevice(device: AudioDeviceInfo): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
    if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET) return true
    if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && device.type == AudioDeviceInfo.TYPE_USB_HEADSET) return true
    return false
}

@Suppress("DEPRECATION")

internal fun CallRuntime.describeAudioDevices(): String {
    val am = callAudioManager()

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return "legacy wired=${am.isWiredHeadsetOn} btSco=${am.isBluetoothScoOn} speaker=${am.isSpeakerphoneOn}"
    }

    val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .joinToString(prefix = "out=[", postfix = "]") { audioDeviceToString(it) }
    val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .joinToString(prefix = "in=[", postfix = "]") { audioDeviceToString(it) }

    return "$outs $ins speaker=${am.isSpeakerphoneOn} btSco=${am.isBluetoothScoOn}"
}

internal fun CallRuntime.audioDeviceToString(device: AudioDeviceInfo): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "unknown"
    val type = when {
        device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && device.type == AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        else -> "type_${device.type}"
    }
    val name = runCatching { device.productName?.toString().orEmpty() }.getOrDefault("")
    return if (name.isBlank()) type else "$type:$name"
}
