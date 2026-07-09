package com.decentralprospect.symposium

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    val accent = accessibleAccentColor()
    val bg = if (active) accent.copy(alpha = 0.18f) else callControlBackgroundColor()
    val border = if (active) accent.copy(alpha = 0.85f) else callBorderColor()

    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (active) "Выбрано" else "Не выбрано"
            }
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = callIconColor(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    background: Color,
    tint: Color = CallIcon,
    border: Color = Color.Transparent,
    enabled: Boolean = true
) {
    val resolvedBorder = when {
        border != Color.Transparent -> border
        isAppLightTheme() -> callBorderColor(0.92f)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(if (enabled) background else background.copy(alpha = 0.42f))
            .border(1.dp, resolvedBorder, CircleShape)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (!enabled) stateDescription = "Недоступно"
            }
            .clickable(enabled = enabled, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun RoundTextButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    background: Color,
    tint: Color = CallIcon,
    border: Color = Color.Transparent,
    enabled: Boolean = true
) {
    val resolvedBorder = when {
        border != Color.Transparent -> border
        isAppLightTheme() -> callBorderColor(0.92f)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(if (enabled) background else background.copy(alpha = 0.42f))
            .border(1.dp, resolvedBorder, CircleShape)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (!enabled) stateDescription = "Недоступно"
            }
            .clickable(enabled = enabled, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) tint else tint.copy(alpha = 0.5f),
            fontSize = 23.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ControlBar(
    speakerOn: Boolean,
    audioRoute: AudioOutputRoute,
    headsetAvailable: Boolean,
    micEnabled: Boolean,
    micLocked: Boolean = false,
    handRaised: Boolean = false,
    outputOn: Boolean,
    onSpeaker: () -> Unit,
    onAudioRouteSelected: (AudioOutputRoute) -> Unit,
    onMic: () -> Unit,
    onHand: () -> Unit,
    videoEnabled: Boolean,
    onVideo: () -> Unit,
    onOutput: () -> Unit,
    onDisconnect: () -> Unit,
    showModeratorButton: Boolean = false,
    moderatorPendingCount: Int = 0,
    moderatorMuteAllEnabled: Boolean = false,
    showModeratorBubble: Boolean = false,
    onModeratorBubbleDismiss: () -> Unit = {},
    onModerator: () -> Unit = {}
) {
    Surface(color = callBackgroundColor()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(
                icon = if (videoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                contentDescription = if (videoEnabled) "Выключить камеру" else "Включить камеру",
                onClick = onVideo,
                background = callControlBackgroundColor(),
                tint = if (videoEnabled) callIconColor() else callIconMutedColor(),
                border = if (videoEnabled) AppAccent.copy(alpha = 0.95f) else Color.Transparent
            )

            Spacer(Modifier.width(10.dp))

            RoundTextButton(
                text = "✋",
                contentDescription = if (handRaised) "Опустить руку" else "Поднять руку",
                onClick = onHand,
                background = if (handRaised) accessibleAccentColor() else callControlBackgroundColor(),
                tint = if (handRaised) AppOnAccent else callIconColor(),
                border = if (handRaised) accessibleAccentColor().copy(alpha = 0.95f) else Color.Transparent
            )

            Spacer(Modifier.width(10.dp))

            AudioRouteButton(
                route = audioRoute,
                headsetAvailable = headsetAvailable,
                onToggleSpeaker = onSpeaker,
                onSelectRoute = onAudioRouteSelected
            )

            Spacer(Modifier.width(10.dp))

            RoundIconButton(
                icon = if (micEnabled && !micLocked) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = if (micLocked) "Микрофон заблокирован модератором" else if (micEnabled) "Выключить микрофон" else "Включить микрофон",
                onClick = onMic,
                background = if (micEnabled && !micLocked) callControlBackgroundColor() else accessibleDangerColor(),
                tint = if (micEnabled && !micLocked) callIconColor() else Color.White,
                enabled = !micLocked
            )

            Spacer(Modifier.width(10.dp))

            RoundIconButton(
                icon = Icons.Filled.CallEnd,
                contentDescription = "Завершить звонок",
                onClick = onDisconnect,
                background = accessibleDangerColor(),
                tint = Color.White
            )

            if (showModeratorButton) {
                Spacer(Modifier.width(10.dp))

                ModeratorBottomButton(
                    pendingCount = moderatorPendingCount,
                    muteAllEnabled = moderatorMuteAllEnabled,
                    showBubble = showModeratorBubble,
                    onDismissBubble = onModeratorBubbleDismiss,
                    onClick = onModerator
                )
            }
        }
    }
}

@Composable
internal fun AudioRouteButton(
    route: AudioOutputRoute,
    headsetAvailable: Boolean,
    onToggleSpeaker: () -> Unit,
    onSelectRoute: (AudioOutputRoute) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val icon = when (route) {
        AudioOutputRoute.HEADSET -> Icons.Filled.Headset
        AudioOutputRoute.SPEAKER -> Icons.Filled.VolumeUp
        AudioOutputRoute.EARPIECE -> Icons.Filled.PhoneInTalk
    }

    val border = when (route) {
        AudioOutputRoute.HEADSET -> AppAccent.copy(alpha = 0.95f)
        AudioOutputRoute.SPEAKER -> AppAccent.copy(alpha = 0.95f)
        AudioOutputRoute.EARPIECE -> Color.Transparent
    }

    Box(
        modifier = Modifier.size(58.dp),
        contentAlignment = Alignment.Center
    ) {
        RoundIconButton(
            icon = icon,
            contentDescription = "Выбрать вывод звука",
            onClick = {
                if (headsetAvailable) {
                    menuOpen = true
                } else {
                    onToggleSpeaker()
                }
            },
            background = callControlBackgroundColor(),
            tint = callIconColor(),
            border = border
        )

        if (menuOpen) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = with(density) {
                    IntOffset(
                        x = 0,
                        y = (-72).dp.roundToPx()
                    )
                },
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(
                    focusable = true,
                    clippingEnabled = true
                )
            ) {
                AudioRouteMenu(
                    selected = route,
                    onSelect = { selected ->
                        menuOpen = false
                        onSelectRoute(selected)
                    }
                )
            }
        }
    }
}

@Composable
internal fun AudioRouteMenu(
    selected: AudioOutputRoute,
    onSelect: (AudioOutputRoute) -> Unit
) {
    Row(
        modifier = Modifier
            .width(184.dp)
            .height(62.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(callSurfaceColor())
            .border(1.dp, if (isAppLightTheme()) callBorderColor() else AppAccent.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AudioRouteMenuItem(
            icon = Icons.Filled.Headset,
            selected = selected == AudioOutputRoute.HEADSET,
            onClick = { onSelect(AudioOutputRoute.HEADSET) }
        )

        AudioRouteMenuItem(
            icon = Icons.Filled.VolumeUp,
            selected = selected == AudioOutputRoute.SPEAKER,
            onClick = { onSelect(AudioOutputRoute.SPEAKER) }
        )

        AudioRouteMenuItem(
            icon = Icons.Filled.PhoneInTalk,
            selected = selected == AudioOutputRoute.EARPIECE,
            onClick = { onSelect(AudioOutputRoute.EARPIECE) }
        )
    }
}

@Composable
internal fun AudioRouteMenuItem(
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (selected) AppAccent.copy(alpha = 0.22f) else callControlBackgroundColor())
            .border(
                width = 1.dp,
                color = if (selected) AppAccent.copy(alpha = 0.95f) else callBorderColor(0.65f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) AppAccent else callIconColor(),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
internal fun ModeratorBottomButton(
    pendingCount: Int,
    muteAllEnabled: Boolean,
    showBubble: Boolean,
    onDismissBubble: () -> Unit,
    onClick: () -> Unit
) {
    val hasLobbyNotifications = pendingCount > 0

    Box(
        modifier = Modifier.size(58.dp),
        contentAlignment = Alignment.Center
    ) {
        RoundIconButton(
            icon = Icons.Filled.AdminPanelSettings,
            contentDescription = "Панель модератора",
            onClick = onClick,
            background = if (muteAllEnabled) accessibleDangerColor() else callControlBackgroundColor(),
            tint = if (muteAllEnabled) Color.White else callIconColor(),
            border = when {
                hasLobbyNotifications -> accessibleAccentColor().copy(alpha = 0.95f)
                muteAllEnabled -> accessibleDangerColor().copy(alpha = 0.95f)
                else -> Color.Transparent
            }
        )

        if (hasLobbyNotifications) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(AppAccent)
                    .border(1.dp, callBackgroundColor(), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = pendingCount.coerceAtMost(99).toString(),
                    color = AppOnAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (showBubble && hasLobbyNotifications) {
            val density = LocalDensity.current

            Popup(
                alignment = Alignment.TopEnd,
                offset = with(density) {
                    IntOffset(
                        x = 0,
                        y = (-88).dp.roundToPx()
                    )
                },
                properties = PopupProperties(
                    focusable = false,
                    clippingEnabled = false
                )
            ) {
                ModeratorComicBubble(
                    count = pendingCount,
                    onDismiss = onDismissBubble
                )
            }
        }

        if (muteAllEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(AppError)
                    .border(1.dp, callBackgroundColor(), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MicOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
internal fun ModeratorComicBubble(
    count: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val maxBubbleWidth = minOf(
        LocalConfiguration.current.screenWidthDp.dp - 28.dp,
        260.dp
    )

    Box(
        modifier = modifier.widthIn(max = maxBubbleWidth),
        contentAlignment = Alignment.BottomEnd
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(callSurfaceColor())
                .border(
                    width = 1.dp,
                    color = AppAccent.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = count.coerceAtMost(99).toString(),
                    color = AppAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp
                )

                Text(
                    text = if (count == 1) "ждёт входа" else "ждут входа",
                    color = callTextPrimaryColor(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(AppAccent.copy(alpha = 0.14f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Скрыть",
                        tint = AppAccent,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 21.dp)
                .size(14.dp)
                .offset(y = (-4).dp)
                .graphicsLayer {
                    rotationZ = 45f
                }
                .background(callSurfaceColor())
        )
    }
}

@Composable
fun textFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = appFieldContainerColor(),
    unfocusedContainerColor = appFieldContainerColor(),
    disabledContainerColor = appFieldContainerColor().copy(alpha = 0.55f),
    focusedIndicatorColor = AppAccent,
    unfocusedIndicatorColor = appFieldBorderColor(),
    disabledIndicatorColor = appFieldBorderColor(0.45f),
    cursorColor = AppAccent,
    focusedTextColor = appTextPrimaryColor(),
    unfocusedTextColor = appTextPrimaryColor(),
    focusedLabelColor = AppAccent,
    unfocusedLabelColor = appTextSecondaryColor(),
)

@Composable
fun StatusPill(text: String) {
    val color = when {
        text.contains("reconnecting", ignoreCase = true) -> AppAccent
        text.contains("connecting", ignoreCase = true) -> accessibleAccentColor()
        text.lowercase().contains("online") -> accessiblePositiveColor()
        else -> accessibleDangerColor()
    }

    Box(
        modifier = Modifier
            .height(54.dp)
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(6.dp))
            .background(callSurfaceColor())
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            color = color,
            fontSize = 14.sp,
        )
    }
}
