# Symposium Code Documentation


### `MainActivity`

Main Android entry point. It owns shared runtime state for the active call, WebRTC peer connections, media tracks, WebSocket signaling, reconnect timers, moderation state, diagnostics, telemetry state, and UI binding.

Important responsibilities:

- initialize activity, UI, WebRTC, and runtime callbacks;
- hold current room, role, peer ID, reconnect token, and moderator key;
- coordinate local microphone, camera, audio routing, and wake locks;
- coordinate publish and subscribe peer connections;
- expose state mutation helpers used by split extension files.

### `PeerStatus`

UI model for a peer visible in the call. It carries peer identity, display name, moderation flags, hand-raise state, media availability, and optional video track references.

## `activity/`

### `ActivityConstants.kt`

Central constants used by the activity layer. This file keeps shared values out of `MainActivity` and avoids magic strings in lifecycle/control code.

### `ActivityLifecycle.kt`

Lifecycle and Android-permission integration for `MainActivity`.

Key functions:

- `initializeMainActivityAfterCreate()` sets up runtime dependencies after `onCreate`.
- `onRecordAudioPermissionResult()` continues or cancels microphone activation after the runtime permission result.
- `onCameraPermissionResult()` continues or cancels camera activation after the runtime permission result.
- `onPostNotificationsPermissionResult()` handles notification permission results.
- `startCallService()` starts the foreground service for an active call/listen session.
- `stopCallService()` stops the foreground service after disconnect or teardown.

### `ActivityModels.kt`

Activity-level models and state containers used by the call screen and runtime logic.

### `CallControlsAndStats.kt`

User control handlers and RTC statistics polling.

Key functions:

- `toggleMic()` enables or disables local audio publication.
- `toggleVideo()` enables or disables local video publication.
- `toggleOutput()` switches output behavior from the UI.
- `switchCamera()` changes the active camera source.
- `toggleSpeakerphone()` toggles speaker output.
- `acquireProximityLock()` and `releaseProximityLock()` manage screen behavior during phone-style calls.
- `acquirePartialWakeLock()` and `releasePartialWakeLock()` keep the session alive when needed.
- `disconnect()` leaves the room and returns the app to idle state.
- `teardown()` releases call, media, signaling, and WebRTC resources.
- `startStatsPolling()` and `stopStatsPolling()` manage periodic WebRTC stats collection.
- `recordRtcStatsTelemetry()` extracts selected metrics from WebRTC stats reports.
- `configureVideoSenderForMobile()` applies mobile-friendly video sender settings.

### `RuntimeCallbacks.kt`

Factory and timer callbacks used by `MainActivity`.

Key functions:

- `onWakeLockRefreshTick()` refreshes wake-lock state.
- `onReconnectTick()` runs reconnect checks.
- `createRtcTrackNegotiationController()` creates the remote-track coordination controller.
- `onStatsTick()` collects periodic RTC statistics.

### `ScreenState.kt`

Thread-safe UI state updates and diagnostic helpers.

Key functions:

- `setStatus()`, `setConnected()`, `setPeerId()`, `setIceState()`, and `setPcState()` update visible connection state.
- `refreshCombinedStates()` merges publish/subscribe connection state into the displayed state.
- `nextPublishGeneration()` and `nextSubscribeGeneration()` advance local generation counters.
- `resetSubscribeProtocolGeneration()` and `retireSubscribeProtocolGeneration()` manage server-side subscribe generation tracking.
- `syncModerationStateToUi()` sends moderation state to Compose UI.
- `resetModerationState()` clears lobby, mute, and hand-raise state.
- `diagLog()` and `diagState()` write sanitized diagnostic entries.
- `publishDiagnosticPanel()` refreshes diagnostic UI output.
- `postUi()` runs state updates on the main thread.

### `Telemetry.kt`

Telemetry preference, session, sanitization, and event helpers.

Key functions:

- `loadTelemetryPrivacyPrefs()` and `persistTelemetryPrivacyPrefs()` read/write privacy settings.
- `setExternalTelemetryEnabled()` toggles external telemetry.
- `startNewRelicTelemetry()` initializes New Relic when configured and allowed.
- `shutdownNewRelicTelemetry()` stops telemetry on teardown.
- `trackRtcEvent()` records high-level RTC events.
- `shouldSendImportantTelemetry()` filters noisy events.
- `sanitizeTelemetryAttrs()` removes sensitive values before telemetry export.
- `newTelemetrySessionId()` creates an anonymous per-session ID.
- `markConferenceConnected()` and `finishConferenceTelemetry()` track call lifecycle duration.
- `noteAudioTelemetryError()`, `noteCameraTelemetryError()`, `noteIceBadStateTelemetry()`, and `notePcBadStateTelemetry()` record important error classes.
- `recordRtcMetric()` and `recordInstallerMetric()` record numeric metrics.

## `install/RemoteInstaller.kt`

### `OpenRoomInfo`

Represents a room returned by relay admin endpoints. Contains room name and associated admin-facing metadata.

### `RemoteInstaller`

Remote relay management client. It uses SSH for installation/probing and HTTPS admin endpoints for room/version actions when available.

Nested result models:

- `RelayRemovalResult` describes relay removal outcome.
- `ProbeResult` describes detected remote installation state.
- `RoomAdminResult` describes room open/close or room list operations.
- `RelayVersionResult` describes remote relay version checks.
- `RelayInfo` describes relay host, ports, URLs, token, and TLS pin data.
- `InstallResult` describes completed installation data.

Key functions:

- `performInstallationDetailed()` installs or updates the relay and emits progress.
- `removeRelayFromServer()` removes relay service/files from a server.
- `probeInstallationState()` checks whether a server already has a relay installation.
- `setRoomOpenState()` opens or closes a room through available admin channels.
- `fetchOpenRoomsOverSsh()` reads open room state over SSH.
- `fetchRelayVersionOverHttps()` checks relay version through HTTPS.
- `fetchOpenRoomsOverHttps()` reads open room state through HTTPS.
- `setRoomOpenStateOverHttps()` changes room state through HTTPS.
- `observeSshHostKeyPin()` reads the SSH host key pin before trust is stored.

Security helpers:

- SSH host key pin normalization and comparison.
- HTTPS certificate SPKI pinning.
- Shell argument quoting for remote commands.
- Privileged remote file reads through root or sudo.

### `InstallServersStore`

Persistent store for relay servers configured in the app.

Key functions:

- `load()` returns saved servers.
- `save()` persists the current server list.
- `upsert()` creates or updates a server entry.
- `delete()` removes a saved server entry.

## `media/`

### `AudioRouting.kt`

Audio route detection and switching.

Key functions:

- `newTrackNamespace()`, `resetLocalTrackNamespace()`, `nextLocalAudioTrackId()`, and `nextLocalVideoTrackId()` generate stable local track IDs.
- `headsetAvailable()` checks whether wired or Bluetooth output is available.
- `publishAudioRouteToUi()` updates the UI with current route state.
- `setAudioRouteFromUi()` applies a route selected by the user.
- `configureCallAudioMode()` configures Android audio mode for a call.
- `startAudioRoutingMonitor()` and `stopAudioRoutingMonitor()` listen for route changes.
- `applyPreferredAudioRoute()` chooses the best output route for current device state.
- `bestNonSpeakerAudioRoute()` chooses Bluetooth, wired, or earpiece output.
- `setAudioRoute()` applies speaker, earpiece, wired, or Bluetooth routing.
- `resetAudioRoutingForIdle()` restores normal audio state after a call.

### `CameraCapture.kt`

Camera setup, switching, capture lifecycle, and local video track handling.

Core responsibilities:

- create camera capturer and video source;
- attach local video track to the publish peer connection;
- switch front/back camera;
- release camera resources on teardown.

### `PermissionsAndMicrophone.kt`

Microphone permission and local audio track handling.

Core responsibilities:

- request microphone permission only when needed;
- create, attach, detach, and release local audio track;
- keep listener-only mode separate from microphone-enabled mode.

### `VideoTracksStore.kt`

Remote and local video track storage used by Compose UI. It keeps track ownership and video rendering state synchronized with peer status models.

## `network/NetworkSecurity.kt`

Network normalization and validation helpers.

Core responsibilities:

- strip URL schemes from host input;
- validate IP/host-like values before connection or installation flows;
- reduce accidental malformed relay configuration.

## `rtc/`

### `PeerConnections.kt`

Creation, restart, cleanup, and observer setup for WebRTC peer connections.

Core responsibilities:

- create publish and subscribe peer connections;
- handle ICE and connection state callbacks;
- guard callbacks by generation counters;
- release peer connection resources safely.

### `ReconnectController.kt`

Reconnect loop and reconnect decision logic.

Core responsibilities:

- detect broken signaling/media states;
- schedule reconnect attempts;
- avoid duplicate reconnect attempts;
- preserve reconnect token state when available.

### `RtcConfiguration.kt`

WebRTC configuration construction.

Core responsibilities:

- build ICE server list;
- apply transport and policy settings;
- centralize peer connection configuration.

### `RtcTrackNegotiationController.kt`

Remote track ownership, replacement, and UI synchronization.

Core responsibilities:

- map incoming WebRTC tracks to peer IDs;
- consume server track-published and track-removed metadata;
- remove stale tracks only when safe;
- update peer audio/video state;
- keep remote video rendering stable across reconnects and renegotiation.

### `SignalingConnection.kt`

WebSocket signaling client and protocol message handling.

Key functions:

- `connect()` opens signaling and joins the selected room.
- `handleSignalingMessage()` dispatches incoming JSON messages.
- `updateReconnectTokenFromServer()` stores private reconnect data.
- Lobby handlers update waiting, approval, and rejection states.
- Moderation handlers process kick, mute, mute-all, and hand-raise state.
- `handleJoinAccepted()` moves the session into active state after server acceptance.
- `sendModeratorTargetCommand()` sends moderator actions for a peer.
- `sendSetMuteAll()` toggles room-wide mute.
- `sendSetHandRaised()` updates local hand-raise state.
- `sendSelfMediaState()` publishes local mic/camera state.
- `schedulePublishNegotiation()` and `flushPublishNegotiation()` coordinate local offer creation.
- `handlePublishAnswer()` applies the server answer for publishing.
- `handleSubscribeOffer()` accepts server subscribe offers.
- `handleRemoteTrickle()` handles remote ICE candidates.
- `addOrQueueRemoteIce()`, `queueRemoteIce()`, and `drainQueuedRemoteIce()` manage ICE arrival order.
- `sendLocalIce()` and `sendIceComplete()` send local ICE updates to the relay.
- `schedulePublishIceRestart()` requests ICE repair when publishing looks broken.

### `SubscribeRecovery.kt`

Subscribe-side recovery for failed or stalled remote media.

Key functions:

- `cancelSubscribeRecovery()` clears pending recovery attempts.
- `scheduleSubscribeRecovery()` schedules a subscribe repair after a failure signal.

### `WebRtcCore.kt`

WebRTC factory, audio module, local sender, and resource lifecycle.

Key functions:

- `ensurePublishBootstrapTransceivers()` prepares publish transceivers.
- `ensureLocalAudioSenderInternal()` creates or reuses local audio sender.
- `ensureLocalVideoSenderInternal()` creates or reuses local video sender.
- `isSelfAudioAttached()` and `isSelfVideoAttached()` report current sender state.
- `initWebRtc()` initializes WebRTC runtime.
- `recreateAdm()` recreates Android audio device module with chosen processing settings.
- `releaseWebRtc()` releases WebRTC resources.
- `buildIceServers()` builds ICE server configuration.

## `service/CallService.kt`

### `NotificationDismissedReceiver`

Broadcast receiver for notification-dismiss events.

### `CallService`

Foreground service that keeps the active call/listen session visible to Android.

Key functions:

- `startCallForeground()` starts foreground mode with the right service type.
- `dismissedIntent()` builds notification-dismiss pending intent.
- `openAppIntent()` builds notification tap intent.
- `buildNotification()` creates the active call notification.
- `createNotificationChannel()` creates the Android notification channel.

## `ui/`

### `AppScreen.kt`

Top-level Compose navigation and settings/menu screens.

Key functions/composables:

- `AppScreen()` renders the main app container.
- `MinimalBottomNav()` renders bottom navigation.
- `TelemetryConsentDialog()` asks for telemetry consent.
- `SettingsScreen()` renders privacy and settings controls.
- `MenuScreen()` renders secondary navigation.

### `CallControls.kt`

Reusable Compose controls for call UI.

Key functions/composables:

- `AppButton()` renders a primary app button.
- `RoundIconButton()` renders a circular icon control.
- `RoundTextButton()` renders a circular text control.
- `ControlBar()` renders mic/video/output/hangup controls.
- `AudioRouteButton()` and `AudioRouteMenu()` render audio output selection.
- `ModeratorBottomButton()` and `ModeratorComicBubble()` render moderation entry controls.
- `StatusPill()` renders compact status text.

### `CallScreen.kt`

Main in-call Compose UI.

Key functions/composables:

- `ParticipantsTopBar()` renders participant summary.
- `ReconnectOverlay()` renders reconnect state.
- `ModeratorPanelOverlay()` renders lobby and peer moderation tools.
- `LobbyPeerRow()` and `ModeratorPeerRow()` render moderator rows.
- `CallInProgressView()` renders active call layout.
- `DraggablePip()` and `SelfVideoPip()` render local preview.
- `CallFocusStage()` renders focused peer view.
- `CallGridStage()` renders grid view.
- `PeerVideoTile()` renders a single peer tile.
- `WebRtcVideoSurface()` renders a WebRTC video track.

### `DesignSystem.kt`

Shared UI models, colors, formatting, parsing, and QR helpers.

Important types:

- `AccessibilityFontScale` defines UI text scaling options.
- `MeetLayout` defines grid layout behavior.
- `CallViewMode` defines focus/grid call modes.
- `AudioOutputRoute` defines audio output options.
- `LobbyPeerStatus` models a waiting peer.
- `RelayVersionState` models relay compatibility state.
- `InstallServer` models a saved relay server.
- `UiBinder` defines the interface used by runtime code to update Compose UI.

Key functions:

- `parseConnectLink()` parses deep links and redirect links.
- `buildConnectDeepLink()` creates app deep links.
- `buildConnectHttpRedirectLink()` creates HTTP redirect links.
- `generateQrBitmap()` creates QR images.
- `saveQrBitmapToPictures()` saves QR images to the Android media store.
- `normalizeIpInput()` and `isValidIpAddress()` validate connection input.
- `displayName()` builds stable display names for peers.
- `tileColorFromName()` assigns deterministic avatar colors.
- `serverStateLabel()` and `serverStateColor()` describe relay state in UI.

### `HomeScreen.kt`

Home/join screen and QR/link presentation components.

Key functions/composables:

- `ActionButton()` renders major actions.
- `QrCodeDialog()` displays a QR code.
- `SectionCard()` and `CollapsibleSectionCard()` render grouped content.
- `InfoBadge()` renders compact labels.
- `CopyValueRow()` renders copyable values.
- `HomeConnectionCard()` renders connection form and invite data.
- `LobbyWaitingView()` renders waiting-for-approval state.

### `InstallTab.kt`

Compose UI and orchestration for relay server management.

Key functions/composables:

- `InstallTab()` renders the full server-management tab.
- `persistServers()` saves configured servers.
- `copyToClipboard()` copies links or values.
- `updateServer()` updates server state in UI and storage.
- `relayVersionStateFor()` compares relay version with app expectation.
- `refreshRelayVersion()` checks remote relay version.
- `deleteServer()` removes a saved relay.
- `addServer()` adds a relay after host-key trust flow.
- `startInstallation()`, `removeRelay()`, and `updateRelay()` run server operations.
- `applyRoomAction()` opens or closes a room.
- `refreshOpenRooms()` updates room list.
- `guestLink()`, `guestQrLink()`, `moderatorLink()`, and `moderatorQrLink()` build invite links.
- `ServerListCard()` renders a saved server.
- `RoomItemCard()` renders an open room.
- `RoomLinkActionsRow()` renders copy/QR actions.
- `EditableFieldRow()` and `FieldEditDialog()` render editable server fields.

## `ui/theme/`

### `Color.kt`

Shared Compose color constants.

### `Theme.kt`

Application Material theme setup.

### `Type.kt`

Application typography setup.
