package com.decentralprospect.symposium

import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

@Composable
internal fun ParticipantsTopBar(
    peers: List<PeerStatus>,
    selfPeerId: String,
    pinnedPeerId: String?,
    peerHandStates: Map<String, Boolean>,
    peerMicOffStates: Map<String, Boolean>,
    peerVideoOffStates: Map<String, Boolean>,
    onPin: (String?) -> Unit,
    mode: CallViewMode,
    onToggleMode: () -> Unit
) {
    Surface(color = callBackgroundColor()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(peers.sortedBy { displayName(it).lowercase() }, key = { it.peerId }) { peer ->
                    val isPinned = peer.peerId == pinnedPeerId
                    TopBarPeerVideoTile(
                        peer = peer,
                        selfPeerId = selfPeerId,
                        pinned = isPinned,
                        handRaised = peerHandStates[peer.peerId] == true,
                        audioMuted = peerMicOffStates[peer.peerId] == true,
                        videoOff = peerVideoOffStates[peer.peerId] == true,
                        modifier = Modifier.width(120.dp),
                        onClick = { onPin(if (isPinned) null else peer.peerId) }
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            SecureCallBadge()

            Spacer(Modifier.width(8.dp))

            RoundIconButton(
                icon = if (mode == CallViewMode.FOCUS) Icons.Filled.GridView else Icons.Filled.CenterFocusStrong,
                contentDescription = tr("Toggle view"),
                onClick = onToggleMode,
                background = callControlBackgroundColor(),
                tint = callIconColor()
            )
        }
    }
}

@Composable
private fun SecureCallBadge() {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(AppSuccess.copy(alpha = 0.14f))
            .semantics(mergeDescendants = true) {
                contentDescription = tr("Сквозное шифрование включено")
            }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = AppSuccess,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = "E2EE",
            color = AppSuccess,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun ReconnectOverlay(
    visible: Boolean,
    onCancel: () -> Unit
) {
    if (!visible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .graphicsLayer { alpha = 1f }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(16.dp))
                .background(callSurfaceColor().copy(alpha = 0.94f))
                .border(1.dp, callBorderColor(), RoundedCornerShape(16.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = AppAccent)
            Spacer(Modifier.height(12.dp))
            Text("RECONNECTING", color = callIconColor(), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            ActionButton(
                label = "Cancel",
                onClick = onCancel,
                kind = ActionButtonKind.SECONDARY
            )
        }
    }
}

@Composable
internal fun ModeratorFloatingButton(
    pendingCount: Int,
    raisedHandsCount: Int,
    muteAllEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (muteAllEnabled) Color.White else AppOnAccent

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (muteAllEnabled) AppError else AppAccent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Text("МОД", color = contentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (pendingCount > 0) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(contentColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(pendingCount.toString(), color = contentColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
            if (raisedHandsCount > 0) {
                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(contentColor.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✋ $raisedHandsCount", color = contentColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }
    }
}


@Composable
internal fun ModeratorPanelOverlay(
    visible: Boolean,
    pendingPeers: List<LobbyPeerStatus>,
    peers: List<PeerStatus>,
    selfPeerId: String,
    peerMuteStates: Map<String, Boolean>,
    peerMicOffStates: Map<String, Boolean>,
    peerHandStates: Map<String, Boolean>,
    muteAllEnabled: Boolean,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onKick: (String) -> Unit,
    onMute: (String) -> Unit,
    onUnmute: (String) -> Unit,
    onLowerHand: (String) -> Unit,
    onSetMuteAll: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    if (!visible) return

    val sortedPending = pendingPeers.sortedBy { displayName(it).lowercase() }
    val sortedPeers = peers
        .filter { it.peerId != selfPeerId }
        .sortedWith(
            compareByDescending<PeerStatus> { peerHandStates[it.peerId] == true }
                .thenBy { displayName(it).lowercase() }
        )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(horizontal = 12.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
            color = callSurfaceColor(),
            shape = CardShape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .border(1.dp, callBorderColor(), CardShape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Панель модерации",
                            color = callTextPrimaryColor(),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = "В лобби: ${pendingPeers.size} · Участников: ${peers.size} · Руки: ${peerHandStates.count { it.value && it.key != selfPeerId }}",
                            color = callTextSecondaryColor(),
                            fontSize = 12.sp
                        )
                    }

                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = tr("Закрыть"),
                            tint = callTextSecondaryColor()
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InnerCardShape)
                        .background(callFieldBackgroundColor().copy(alpha = 0.78f))
                        .border(1.dp, callBorderColor().copy(alpha = 0.65f), InnerCardShape)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Заглушить всех",
                            color = callTextPrimaryColor(),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )

                        Text(
                            text = if (muteAllEnabled) {
                                "Все гости заглушены"
                            } else {
                                "Гости могут говорить"
                            },
                            color = callTextSecondaryColor(),
                            fontSize = 11.sp
                        )
                    }

                    ModeratorSmallToggleButton(
                        active = muteAllEnabled,
                        onClick = { onSetMuteAll(!muteAllEnabled) }
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    item {
                        ModeratorSectionHeader(
                            title = "Ожидают входа",
                            count = sortedPending.size
                        )
                    }

                    if (sortedPending.isEmpty()) {
                        item {
                            ModeratorEmptyRow("В лобби никого нет.")
                        }
                    } else {
                        items(sortedPending, key = { "pending:${it.peerId}" }) { p ->
                            LobbyPeerRowCompact(
                                peer = p,
                                onApprove = { onApprove(p.peerId) },
                                onReject = { onReject(p.peerId) }
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(2.dp))
                        ModeratorSectionHeader(
                            title = "Участники",
                            count = sortedPeers.size
                        )
                    }

                    if (sortedPeers.isEmpty()) {
                        item {
                            ModeratorEmptyRow("Других участников пока нет.")
                        }
                    } else {
                        items(sortedPeers, key = { "peer:${it.peerId}" }) { p ->
                            val muted = peerMuteStates[p.peerId] ?: muteAllEnabled
                            val micOff = peerMicOffStates[p.peerId] == true
                            val handRaised = peerHandStates[p.peerId] == true

                            ModeratorPeerRowCompact(
                                peer = p,
                                muted = muted,
                                micOff = micOff,
                                handRaised = handRaised,
                                onKick = { onKick(p.peerId) },
                                onMute = { onMute(p.peerId) },
                                onUnmute = { onUnmute(p.peerId) },
                                onLowerHand = { onLowerHand(p.peerId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModeratorSmallToggleButton(
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (active) AppError.copy(alpha = 0.18f)
                else callControlBackgroundColor().copy(alpha = 0.85f)
            )
            .border(
                width = 1.dp,
                color = if (active) AppError.copy(alpha = 0.65f) else callBorderColor(),
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (active) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = null,
                tint = if (active) AppError else callIconColor(),
                modifier = Modifier.size(16.dp)
            )

            Text(
                text = if (active) "Вкл." else "Выкл.",
                color = if (active) callTextPrimaryColor() else callIconColor(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
internal fun ModeratorSectionHeader(
    title: String,
    count: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = callTextPrimaryColor(),
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )

        InfoBadge(
            label = count.toString(),
            color = callTextSecondaryColor()
        )
    }
}

@Composable
internal fun ModeratorEmptyRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(callFieldBackgroundColor())
            .border(1.dp, callBorderColor(), InnerCardShape)
            .padding(12.dp)
    ) {
        Text(
            text = text,
            color = callTextSecondaryColor(),
            fontSize = 13.sp
        )
    }
}

@Composable
internal fun LobbyPeerRowCompact(
    peer: LobbyPeerStatus,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(callFieldBackgroundColor())
            .border(1.dp, callBorderColor(), InnerCardShape)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = displayName(peer),
                color = callTextPrimaryColor(),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = peer.peerId,
                color = callTextSecondaryColor(),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        ActionButton(
            label = "Да",
            onClick = onApprove,
            modifier = Modifier.width(64.dp),
            kind = ActionButtonKind.PRIMARY
        )

        ActionButton(
            label = "Нет",
            onClick = onReject,
            modifier = Modifier.width(68.dp),
            kind = ActionButtonKind.DANGER
        )
    }
}

@Composable
internal fun ModeratorPeerRowCompact(
    peer: PeerStatus,
    muted: Boolean,
    micOff: Boolean,
    handRaised: Boolean,
    onKick: () -> Unit,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
    onLowerHand: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(callFieldBackgroundColor())
            .border(
                width = 1.dp,
                color = if (handRaised) AppAccent.copy(alpha = 0.75f) else callBorderColor(),
                shape = InnerCardShape
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (handRaised) {
                        Text("✋", fontSize = 14.sp)
                    }

                    Text(
                        text = displayName(peer),
                        color = callTextPrimaryColor(),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = peer.peerId,
                    color = callTextSecondaryColor(),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InfoBadge(
                    label = if (micOff) "Mic off" else "Mic on",
                    color = if (micOff) AppError else AppSuccess
                )

                InfoBadge(
                    label = if (muted) "Muted" else "Open",
                    color = if (muted) AppError else AppSuccess
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton(
                label = if (muted) "Разглушить" else "Заглушить",
                onClick = if (muted) onUnmute else onMute,
                modifier = Modifier.weight(1f),
                kind = ActionButtonKind.SECONDARY,
                icon = if (muted) Icons.Filled.Mic else Icons.Filled.MicOff
            )

            if (handRaised) {
                ActionButton(
                    label = "Опустить руку",
                    onClick = onLowerHand,
                    modifier = Modifier.weight(1f),
                    kind = ActionButtonKind.SECONDARY
                )
            }

            ActionButton(
                label = "Выгнать",
                onClick = onKick,
                modifier = Modifier.weight(1f),
                kind = ActionButtonKind.DANGER
            )
        }
    }
}

@Composable
internal fun LobbyPeerRow(
    peer: LobbyPeerStatus,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(callFieldBackgroundColor())
            .border(1.dp, callBorderColor(), InnerCardShape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(displayName(peer), color = callTextPrimaryColor(), fontWeight = FontWeight.SemiBold)
            Text(peer.peerId, color = callTextSecondaryColor(), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ActionButton(label = "Впустить", onClick = onApprove, modifier = Modifier.width(102.dp), kind = ActionButtonKind.PRIMARY)
        ActionButton(label = "Отклонить", onClick = onReject, modifier = Modifier.width(106.dp), kind = ActionButtonKind.DANGER)
    }
}

@Composable
internal fun ModeratorPeerRow(
    peer: PeerStatus,
    muted: Boolean,
    micOff: Boolean,
    handRaised: Boolean,
    onKick: () -> Unit,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
    onLowerHand: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(callFieldBackgroundColor())
            .border(1.dp, callBorderColor(), InnerCardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(displayName(peer), color = callTextPrimaryColor(), fontWeight = FontWeight.SemiBold)
                Text(peer.peerId, color = callTextSecondaryColor(), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (handRaised) {
                    InfoBadge(label = "✋ Рука", color = AppAccent)
                }
                InfoBadge(
                    label = if (micOff) "Mic off" else "Mic on",
                    color = if (micOff) AppError else AppSuccess
                )
                InfoBadge(label = if (muted) "Muted" else "Can speak", color = if (muted) AppError else AppSuccess)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                label = if (muted) "Размьют" else "Мьют",
                onClick = if (muted) onUnmute else onMute,
                modifier = Modifier.weight(1f),
                kind = ActionButtonKind.SECONDARY,
                icon = if (muted) Icons.Filled.Mic else Icons.Filled.MicOff
            )
            ActionButton(
                label = "Выгнать",
                onClick = onKick,
                modifier = Modifier.weight(1f),
                kind = ActionButtonKind.DANGER
            )
        }
        if (handRaised) {
            ActionButton(
                label = "Опустить руку",
                onClick = onLowerHand,
                modifier = Modifier.fillMaxWidth(),
                kind = ActionButtonKind.SECONDARY
            )
        }
    }
}

@Composable
internal fun CallInProgressView(
    peers: List<PeerStatus>,
    selfPeerId: String,
    mode: CallViewMode,
    pinnedPeerId: String?,
    peerHandStates: Map<String, Boolean>,
    peerMicOffStates: Map<String, Boolean>,
    peerVideoOffStates: Map<String, Boolean>,
    onPin: (String?) -> Unit,
    onToggleMode: () -> Unit,
    onSwitchCamera: () -> Unit,
    pipOffsetX: Float,
    pipOffsetY: Float,
    onMovePip: (dx: Float, dy: Float) -> Unit,
    pipExpanded: Boolean,
    onTogglePipExpanded: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(callFieldBackgroundColor())
    ) {
        ParticipantsTopBar(
            peers = peers,
            selfPeerId = selfPeerId,
            pinnedPeerId = pinnedPeerId,
            peerHandStates = peerHandStates,
            peerMicOffStates = peerMicOffStates,
            peerVideoOffStates = peerVideoOffStates,
            onPin = onPin,
            mode = mode,
            onToggleMode = onToggleMode
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (mode == CallViewMode.FOCUS) {
                CallFocusStage(
                    peers = peers,
                    selfPeerId = selfPeerId,
                    pinnedPeerId = pinnedPeerId,
                    peerHandStates = peerHandStates,
                    peerMicOffStates = peerMicOffStates,
                    peerVideoOffStates = peerVideoOffStates,
                    pipOffsetX = pipOffsetX,
                    pipOffsetY = pipOffsetY,
                    onMovePip = onMovePip,
                    pipExpanded = pipExpanded,
                    onTogglePipExpanded = onTogglePipExpanded,
                    onSwitchCamera = onSwitchCamera
                )
            } else {
                CallGridStage(
                    peers = peers,
                    selfPeerId = selfPeerId,
                    peerHandStates = peerHandStates,
                    peerMicOffStates = peerMicOffStates,
                    peerVideoOffStates = peerVideoOffStates,
                    onSelectPeer = { peerId ->
                        onPin(peerId)
                        onToggleMode()
                    },
                    pinnedPeerId = pinnedPeerId,
                    onSwitchCamera = onSwitchCamera
                )
            }
        }
    }
}

@Composable
internal fun DraggablePip(
    modifier: Modifier = Modifier,
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { _, dragAmount ->
                onDragDelta(dragAmount.x, dragAmount.y)
            }
        }
    ) { content() }
}

@Composable
internal fun SelfVideoPip(
    peer: PeerStatus,
    selfPeerId: String,
    handRaised: Boolean,
    audioMuted: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pipWidth by animateDpAsState(
        targetValue = if (expanded) 156.dp else 116.dp,
        animationSpec = tween(durationMillis = 180),
        label = "pip_width"
    )
    Box(
        modifier = modifier
            .width(pipWidth)
            .aspectRatio(9f / 16f)
            .background(Color.Black)
    ) {
        PeerVideoTile(
            peer = peer,
            selfPeerId = selfPeerId,
            bigName = false,
            handRaised = handRaised,
            audioMuted = audioMuted,
            videoOff = false,
            modifier = Modifier.matchParentSize(),
            cornerRadius = 0.dp,
            showStatusOverlay = false,
            surfaceOnTop = true
        )

        if (handRaised) {
            HandRaisedBadge(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                .clickable(onClick = onSwitchCamera),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Cameraswitch,
                contentDescription = tr("Перевернуть камеру"),
                tint = Color.White,
                modifier = Modifier.size(21.dp)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleExpanded),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = tr(if (expanded) "Уменьшить миниатюру" else "Увеличить миниатюру"),
                tint = Color.White,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
internal fun CallFocusStage(
    peers: List<PeerStatus>,
    selfPeerId: String,
    pinnedPeerId: String?,
    peerHandStates: Map<String, Boolean>,
    peerMicOffStates: Map<String, Boolean>,
    peerVideoOffStates: Map<String, Boolean>,
    pipOffsetX: Float,
    pipOffsetY: Float,
    onMovePip: (dx: Float, dy: Float) -> Unit,
    pipExpanded: Boolean,
    onTogglePipExpanded: () -> Unit,
    onSwitchCamera: () -> Unit
) {
    val self = peers.firstOrNull { it.peerId == selfPeerId }
    val others = peers.filter { it.peerId != selfPeerId }
    val pinned = pinnedPeerId?.let { id -> peers.firstOrNull { it.peerId == id } }

    if (pinned != null) {
        Box(modifier = Modifier.fillMaxSize()) {
            PeerVideoTile(
                peer = pinned,
                selfPeerId = selfPeerId,
                bigName = true,
                handRaised = peerHandStates[pinned.peerId] == true,
                audioMuted = peerMicOffStates[pinned.peerId] == true,
                videoOff = peerVideoOffStates[pinned.peerId] == true,
                showPin = true,
                modifier = Modifier.fillMaxSize(),
                onSwitchCamera = if (pinned.peerId == selfPeerId) onSwitchCamera else null
            )

            if (
                self != null &&
                self.videoAttached &&
                peerVideoOffStates[self.peerId] != true &&
                pinned.peerId != self.peerId
            ) {
                DraggablePip(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 96.dp)
                        .offset { IntOffset(pipOffsetX.roundToInt(), pipOffsetY.roundToInt()) },
                    onDragDelta = { dx, dy -> onMovePip(dx, dy) }
                ) {
                    SelfVideoPip(
                        peer = self,
                        selfPeerId = selfPeerId,
                        handRaised = peerHandStates[self.peerId] == true,
                        audioMuted = peerMicOffStates[self.peerId] == true,
                        expanded = pipExpanded,
                        onToggleExpanded = onTogglePipExpanded,
                        onSwitchCamera = onSwitchCamera
                    )
                }
            }
        }
        return
    }

    var focusedPeerId by remember { mutableStateOf<String?>(null) }

    val key = remember(peers, peerMicOffStates) {
        peers.joinToString("|") { p ->
            "${p.peerId}:${p.audioAttached}:${peerMicOffStates[p.peerId] == true}:${(p.audioLevel * 100).toInt()}"
        }
    }

    LaunchedEffect(key) {
        when {
            others.isEmpty() -> focusedPeerId = self?.peerId ?: peers.firstOrNull()?.peerId
            others.size == 1 -> focusedPeerId = others.first().peerId
            else -> {
                val candidate = others
                    .filter { it.audioAttached && peerMicOffStates[it.peerId] != true }
                    .maxByOrNull { it.audioLevel }
                val candidateOk = candidate != null && candidate.audioLevel > 0.03f

                val stillExists = others.any { it.peerId == focusedPeerId }
                if (!stillExists) focusedPeerId = others.first().peerId

                if (candidateOk && candidate!!.peerId != focusedPeerId) focusedPeerId = candidate.peerId
                if (focusedPeerId == null) focusedPeerId = others.first().peerId
            }
        }
    }

    val mainPeer = peers.firstOrNull { it.peerId == focusedPeerId }
        ?: (others.firstOrNull() ?: self)
        ?: return

    val showPip =
        self != null &&
                self.videoAttached &&
                peerVideoOffStates[self.peerId] != true &&
                others.isNotEmpty() &&
                mainPeer.peerId != self.peerId

    Box(modifier = Modifier.fillMaxSize()) {
        PeerVideoTile(
            peer = mainPeer,
            selfPeerId = selfPeerId,
            bigName = true,
            handRaised = peerHandStates[mainPeer.peerId] == true,
            audioMuted = peerMicOffStates[mainPeer.peerId] == true,
            videoOff = peerVideoOffStates[mainPeer.peerId] == true,
            modifier = Modifier.fillMaxSize(),
            onSwitchCamera = if (mainPeer.peerId == selfPeerId) onSwitchCamera else null
        )

        if (showPip) {
            DraggablePip(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 96.dp)
                    .offset { IntOffset(pipOffsetX.roundToInt(), pipOffsetY.roundToInt()) },
                onDragDelta = { dx, dy -> onMovePip(dx, dy) }
            ) {
                SelfVideoPip(
                    peer = self!!,
                    selfPeerId = selfPeerId,
                    handRaised = peerHandStates[self.peerId] == true,
                    audioMuted = peerMicOffStates[self.peerId] == true,
                    expanded = pipExpanded,
                    onToggleExpanded = onTogglePipExpanded,
                    onSwitchCamera = onSwitchCamera
                )
            }
        }
    }
}

@Composable
internal fun PeerUsernameRow(
    name: String,
    pingText: String?,
    handRaised: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(callBackgroundColor())
            .border(
                width = 1.dp,
                color = callBorderColor().copy(alpha = 0.45f),
                shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (handRaised) {
            Text("✋", fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = name,
            color = callTextPrimaryColor(),
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (pingText != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = pingText,
                color = callTextSecondaryColor(),
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun TopBarPeerVideoTile(
    peer: PeerStatus,
    selfPeerId: String,
    pinned: Boolean,
    handRaised: Boolean,
    audioMuted: Boolean,
    videoOff: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val name = displayName(peer)
    val pingText = peer.pingMs?.let { "${it}ms" }
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(callBackgroundColor())
            .border(1.dp, callBorderColor(), shape)
            .clickable(onClick = onClick)
    ) {
        PeerVideoTile(
            peer = peer,
            selfPeerId = selfPeerId,
            bigName = false,
            handRaised = handRaised,
            audioMuted = audioMuted,
            videoOff = videoOff,
            showPin = pinned,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            cornerRadius = 0.dp,
            showNameInTile = false,
            showTileBorder = false,
            renderedVideoFit = RenderedVideoFit.PORTRAIT_INSIDE_FRAME
        )

        PeerUsernameRow(name = name, pingText = pingText, handRaised = handRaised)
    }
}

@Composable
internal fun GridPeerVideoTile(
    peer: PeerStatus,
    selfPeerId: String,
    pinnedPeerId: String?,
    handRaised: Boolean,
    audioMuted: Boolean,
    videoOff: Boolean,
    onClick: () -> Unit,
    onSwitchCamera: (() -> Unit)?
) {
    val name = displayName(peer)
    val pingText = peer.pingMs?.let { "${it}ms" }
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(callBackgroundColor())
            .border(1.dp, callBorderColor(), shape)
            .clickable(onClick = onClick)
    ) {
        PeerVideoTile(
            peer = peer,
            selfPeerId = selfPeerId,
            bigName = true,
            handRaised = handRaised,
            audioMuted = audioMuted,
            videoOff = videoOff,
            showPin = peer.peerId == pinnedPeerId,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            cornerRadius = 0.dp,
            showNameInTile = false,
            showTileBorder = false,
            renderedVideoFit = RenderedVideoFit.PORTRAIT_INSIDE_FRAME,
            onSwitchCamera = onSwitchCamera
        )

        PeerUsernameRow(name = name, pingText = pingText, handRaised = handRaised)
    }
}

@Composable
internal fun CallGridStage(
    peers: List<PeerStatus>,
    selfPeerId: String,
    peerHandStates: Map<String, Boolean>,
    peerMicOffStates: Map<String, Boolean>,
    peerVideoOffStates: Map<String, Boolean>,
    onSelectPeer: (String) -> Unit,
    pinnedPeerId: String?,
    onSwitchCamera: () -> Unit
) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE

    val sorted = peers.sortedBy { displayName(it).lowercase() }
    val count = sorted.size.coerceAtLeast(1)

    val cols = when {
        isLandscape && count > 9 -> 4
        isLandscape -> 3
        count <= 4 -> 2
        else -> 3
    }

    val rows = 3
    val perPage = cols * rows
    val pages = (sorted.size + perPage - 1) / perPage

    var page by remember { mutableStateOf(0) }
    LaunchedEffect(pages) { if (page >= pages) page = 0 }

    val pagePeers = if (pages <= 1) sorted else sorted.drop(page * perPage).take(perPage)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 52.dp, bottom = 96.dp)
        ) {
            items(pagePeers, key = { it.peerId }) { peer ->
                GridPeerVideoTile(
                    peer = peer,
                    selfPeerId = selfPeerId,
                    pinnedPeerId = pinnedPeerId,
                    handRaised = peerHandStates[peer.peerId] == true,
                    audioMuted = peerMicOffStates[peer.peerId] == true,
                    videoOff = peerVideoOffStates[peer.peerId] == true,
                    onClick = { onSelectPeer(peer.peerId) },
                    onSwitchCamera = if (peer.peerId == selfPeerId) onSwitchCamera else null
                )
            }
        }

        if (pages > 1) {
            PageTabs(
                pages = pages,
                current = page,
                onSelect = { page = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            )
        }
    }
}

@Composable
internal fun PageTabs(
    pages: Int,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(callSurfaceColor().copy(alpha = 0.9f))
            .border(1.dp, callBorderColor(), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pages) { i ->
            val active = i == current
            Box(
                modifier = Modifier
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (active) AppAccent else callBorderColor())
                    .clickable { onSelect(i) }
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("${current + 1}/$pages", color = callTextSecondaryColor(), fontSize = 12.sp)
    }
}

internal enum class RenderedVideoFit { FILL_FRAME, PORTRAIT_INSIDE_FRAME }

@Composable
internal fun RenderedVideoLayer(
    track: VideoTrack,
    isSelf: Boolean,
    fit: RenderedVideoFit,
    modifier: Modifier = Modifier,
    surfaceOnTop: Boolean = false
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val videoModifier = when (fit) {
            RenderedVideoFit.FILL_FRAME -> Modifier.matchParentSize()
            RenderedVideoFit.PORTRAIT_INSIDE_FRAME -> Modifier
                .fillMaxHeight()
                .aspectRatio(9f / 16f)
        }

        WebRtcVideoSurface(
            track = track,
            isSelf = isSelf,
            modifier = videoModifier,
            surfaceOnTop = surfaceOnTop
        )
    }
}

@Composable
internal fun HandRaisedBadge(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .border(1.dp, AppAccent.copy(alpha = 0.75f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("✋", color = Color.White, fontSize = 15.sp)
    }
}

@Composable
internal fun PeerVideoTile(
    peer: PeerStatus,
    selfPeerId: String,
    bigName: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    handRaised: Boolean = false,
    audioMuted: Boolean = false,
    videoOff: Boolean = false,
    showPin: Boolean = false,
    cornerRadius: Dp = 14.dp,
    showStatusOverlay: Boolean = true,
    showNameInTile: Boolean = true,
    showTileBorder: Boolean = true,
    renderedVideoFit: RenderedVideoFit = RenderedVideoFit.FILL_FRAME,
    onSwitchCamera: (() -> Unit)? = null,
    surfaceOnTop: Boolean = false
) {
    val name = displayName(peer)
    val base = tileColorFromName(name)

    val level = if (peer.audioAttached && !audioMuted) peer.audioLevel.coerceIn(0f, 1f) else 0f
    val glowColor = AppAccent
    val shape = RoundedCornerShape(cornerRadius)
    val pingText = peer.pingMs?.let { "${it}ms" }

    val videoTracks: Map<String, VideoTrack> = VideoTracksStore.snapshot()
    val track = videoTracks[peer.peerId] ?: if (peer.peerId == selfPeerId) videoTracks["__self__"] else null
    val hasVideo = track != null && !videoOff
    val micOff = audioMuted || !peer.audioAttached

    Box(
        modifier = modifier
            .clip(shape)
            .background(if (hasVideo) Color.Black else base)
            .let { m -> if (showTileBorder) m.border(1.dp, callBorderColor(), shape) else m }
            .let { m -> if (onClick != null) m.clickable { onClick() } else m }
            .drawBehind {
                if (level > 0f) {
                    val a1 = (0.06f + 0.20f * level).coerceIn(0f, 0.30f)
                    val a2 = (0.03f + 0.10f * level).coerceIn(0f, 0.18f)
                    val outerRadius = (cornerRadius + 4.dp).toPx()
                    val innerRadius = (cornerRadius + 2.dp).toPx()
                    drawRoundRect(
                        color = glowColor.copy(alpha = a2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(outerRadius, outerRadius),
                        style = Stroke(width = 14.dp.toPx())
                    )
                    drawRoundRect(
                        color = glowColor.copy(alpha = a1),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(innerRadius, innerRadius),
                        style = Stroke(width = 6.dp.toPx())
                    )
                }
            }
    ) {
        if (hasVideo) {
            androidx.compose.runtime.key(peer.peerId, track!!.id()) {
                RenderedVideoLayer(
                    track = track,
                    isSelf = peer.peerId == selfPeerId,
                    fit = renderedVideoFit,
                    modifier = Modifier.matchParentSize(),
                    surfaceOnTop = surfaceOnTop
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(base)
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(12.dp)
        ) {
            if (showStatusOverlay && hasVideo && onSwitchCamera != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(if (bigName) 38.dp else 32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onSwitchCamera),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cameraswitch,
                        contentDescription = tr("Перевернуть камеру"),
                        tint = Color.White,
                        modifier = Modifier.size(if (bigName) 23.dp else 19.dp)
                    )
                }
            }

            if (showStatusOverlay && micOff) {
                Icon(
                    imageVector = Icons.Filled.MicOff,
                    contentDescription = tr("Mic off"),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .size(if (bigName) 26.dp else 20.dp)
                        .align(Alignment.TopEnd)
                        .padding(top = if (hasVideo && onSwitchCamera != null) 42.dp else 0.dp)
                )
            }

            if (showStatusOverlay && showPin) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = tr("Pinned"),
                    tint = AppAccent.copy(alpha = 0.95f),
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.TopStart)
                )
            }

            if (showStatusOverlay) {
                Icon(
                    imageVector = if (hasVideo) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    contentDescription = tr(if (hasVideo) "Video on" else "Video off"),
                    tint = if (hasVideo) AppAccent else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(if (bigName) 20.dp else 16.dp)
                        .align(Alignment.TopStart)
                        .padding(top = if (showPin) 20.dp else 0.dp)
                )
            }

            if (showStatusOverlay && handRaised) {
                HandRaisedBadge(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (bigName) 2.dp else 0.dp)
                )
            }

            if (showNameInTile && !hasVideo) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (handRaised && !showStatusOverlay) {
                        Text("✋", fontSize = if (bigName) 24.sp else 16.sp)
                    }
                    Text(
                        text = name,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (bigName) 22.sp else 14.sp,
                        maxLines = if (bigName) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (showStatusOverlay && showNameInTile && hasVideo) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.58f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (handRaised) {
                        Text("✋", fontSize = if (bigName) 14.sp else 12.sp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = name,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (bigName) 14.sp else 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (pingText != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = pingText,
                            color = Color.White.copy(alpha = 0.74f),
                            fontSize = if (bigName) 12.sp else 11.sp,
                            maxLines = 1
                        )
                    }
                }
            } else if (showStatusOverlay && showNameInTile && pingText != null) {
                Text(
                    text = pingText,
                    color = Color.White.copy(alpha = 0.74f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        }
    }
}

@Composable
internal fun WebRtcVideoSurface(
    track: VideoTrack,
    isSelf: Boolean,
    modifier: Modifier = Modifier,
    surfaceOnTop: Boolean = false
) {
    val context = LocalContext.current

    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            setZOrderMediaOverlay(surfaceOnTop)
            init(VideoRenderContext.eglBase.eglBaseContext, null)
            setEnableHardwareScaler(false)
            setMirror(isSelf)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            clearImage()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { renderer },
        update = { view ->
            view.setMirror(isSelf)
        }
    )

    DisposableEffect(track, renderer) {
        track.addSink(renderer)

        onDispose {
            track.removeSink(renderer)
            renderer.clearImage()
        }
    }

    DisposableEffect(renderer) {
        onDispose {
            renderer.clearImage()
            renderer.release()
        }
    }
}
