package com.decentralprospect.symposium

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.decentralprospect.symposium.ui.theme.MyApplicationTheme

internal fun MainActivity.initializeMainActivityAfterCreate() {
    loadLanguagePrefs()
    loadTelemetryPrivacyPrefs()
    loadAppearancePrefs()
    startTelemetry()
    incomingConnectLink = intent?.dataString
    requestNotificationPermissionIfNeeded()
    ensureCallServiceBound()

    setContent {
        val callRuntime = callRuntimeState

        MyApplicationTheme(themeMode = appThemeModeState) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppScreen(
                    appVersion = APP_VERSION,
                    expectedRelayVersion = EXPECTED_RELAY_VERSION,
                    reconnectMode = callRuntime?.reconnectMode ?: false,
                    telemetryEnabled = telemetryEnabledState,
                    telemetryPromptShown = telemetryPromptShownState,
                    themeMode = appThemeModeState,
                    appLanguage = appLanguageState,
                    onThemeModeChange = { mode ->
                        setAppThemeMode(mode, showToast = true)
                    },
                    onAppLanguageChange = { language ->
                        setAppLanguage(language)
                    },
                    onTelemetryConsentResult = { enabled ->
                        setExternalTelemetryEnabled(enabled, showToast = true)
                        callRuntimeState?.setExternalTelemetryEnabled(enabled, showToast = false)
                    },
                    onTelemetryEnabledChange = { enabled ->
                        setExternalTelemetryEnabled(enabled, showToast = true)
                        callRuntimeState?.setExternalTelemetryEnabled(enabled, showToast = false)
                    },
                    onRequestBind = { binder ->
                        attachCallUiBinder(binder)
                    },
                    initialConnectLink = incomingConnectLink,
                    onConnect = { url, room, username, tlsPin, modKey, e2eeSecret ->
                        connectViaCallService(url, room, username, tlsPin, modKey, e2eeSecret)
                    },
                    onCancelReconnect = {
                        callRuntimeState?.stopReconnectMode()
                        callRuntimeState?.disconnect()
                    },
                    onDisconnect = {
                        callRuntimeState?.disconnect()
                    },
                    onToggleSpeaker = {
                        callRuntimeState?.toggleSpeakerphone()
                        callRuntimeState?.speakerphoneOn ?: false
                    },
                    onSetAudioRoute = { route ->
                        callRuntimeState?.setAudioRouteFromUi(route)
                    },
                    onToggleMic = {
                        callRuntimeState?.toggleMic()
                        callRuntimeState?.micEnabledState ?: false
                    },
                    onToggleVideo = {
                        callRuntimeState?.toggleVideo()
                        callRuntimeState?.videoEnabledState ?: false
                    },
                    onToggleOutput = {
                        callRuntimeState?.toggleOutput()
                        callRuntimeState?.outputEnabled ?: true
                    },
                    onSwitchCamera = {
                        callRuntimeState?.switchCamera()
                    },
                    onApproveLobbyPeer = { peerId ->
                        callRuntimeState?.sendModeratorTargetCommand("lobby-approve", peerId)
                    },
                    onRejectLobbyPeer = { peerId ->
                        callRuntimeState?.sendModeratorTargetCommand("lobby-reject", peerId)
                    },
                    onKickPeer = { peerId ->
                        callRuntimeState?.sendModeratorTargetCommand("kick", peerId)
                    },
                    onMutePeer = { peerId ->
                        callRuntimeState?.sendModeratorTargetCommand("mute", peerId)
                    },
                    onUnmutePeer = { peerId ->
                        callRuntimeState?.sendModeratorTargetCommand("unmute", peerId)
                    },
                    onSetMuteAll = { enabled ->
                        callRuntimeState?.sendSetMuteAll(enabled)
                    },
                    onSetHandRaised = { enabled ->
                        callRuntimeState?.sendSetHandRaised(enabled)
                    },
                    onLowerPeerHand = { peerId ->
                        callRuntimeState?.sendLowerPeerHand(peerId)
                    },
                    onSetMicAudioEnabled = { enabled ->
                        callRuntimeState?.sendSelfMediaState(audioEnabled = enabled)
                    },
                    onInstall = { ip, login, pass, expectedSshHostKeyPin, deploymentProfile, logger ->
                        performRelayInstallationWithTelemetry(
                            serverIp = ip,
                            login = login,
                            password = pass,
                            expectedSshHostKeyPin = expectedSshHostKeyPin,
                            existingProfile = deploymentProfile,
                            logger = logger
                        )
                    },
                    onRemoveRelay = { ip, login, pass, expectedSshHostKeyPin, deploymentProfile, logger ->
                        remoteInstaller.removeRelayFromServer(
                            serverIp = ip,
                            login = login,
                            password = pass,
                            expectedSshHostKeyPin = expectedSshHostKeyPin,
                            deploymentProfile = deploymentProfile,
                            logger = logger
                        )
                    },
                    onObserveSshHostKeyPin = { ip ->
                        remoteInstaller.observeSshHostKeyPin(ip)
                    },
                    onProbeServer = { ip, login, pass, expectedSshHostKeyPin ->
                        remoteInstaller.probeInstallationState(
                            serverIp = ip,
                            login = login,
                            password = pass,
                            expectedSshHostKeyPin = expectedSshHostKeyPin
                        )
                    },
                    onSetRoomOpenState = { ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?, roomName: String, open: Boolean ->
                        remoteInstaller.setRoomOpenStateOverHttps(
                            serverIp = ip,
                            httpsPort = httpsPort,
                            relayTlsPin = relayTlsPin,
                            adminToken = adminToken,
                            roomName = roomName,
                            open = open
                        )
                    },
                    onRotateModeratorKey = { ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String?, roomName: String ->
                        remoteInstaller.rotateModeratorKeyOverHttps(
                            serverIp = ip,
                            httpsPort = httpsPort,
                            relayTlsPin = relayTlsPin,
                            adminToken = adminToken,
                            roomName = roomName
                        )
                    },
                    onFetchOpenRooms = { ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String? ->
                        remoteInstaller.fetchOpenRoomsOverHttps(
                            serverIp = ip,
                            httpsPort = httpsPort,
                            relayTlsPin = relayTlsPin,
                            adminToken = adminToken
                        )
                    },
                    onFetchRelayVersion = { ip: String, httpsPort: Int?, relayTlsPin: String?, adminToken: String? ->
                        remoteInstaller.fetchRelayVersionOverHttps(
                            serverIp = ip,
                            httpsPort = httpsPort,
                            relayTlsPin = relayTlsPin,
                            adminToken = adminToken
                        )
                    }
                )
            }
        }
    }
}
