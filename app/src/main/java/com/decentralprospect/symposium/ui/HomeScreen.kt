package com.decentralprospect.symposium

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.decentralprospect.symposium.ui.theme.Dos2000FontFamily

@Composable
internal fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ActionButtonKind = ActionButtonKind.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    textColorOverride: Color? = null,
    borderColorOverride: Color? = null
) {
    val danger = accessibleDangerColor()
    val (bg, baseTextColor, baseBorderColor) = when (kind) {
        ActionButtonKind.PRIMARY -> Triple(
            AppAccent,
            AppOnAccent,
            Color.Transparent
        )
        ActionButtonKind.SECONDARY -> Triple(
            appGrayControlColor(),
            appTextPrimaryColor(),
            appGrayControlBorderColor()
        )
        ActionButtonKind.DANGER -> Triple(
            danger,
            Color.White,
            danger.copy(alpha = 0.95f)
        )
        ActionButtonKind.GHOST -> Triple(
            Color.Transparent,
            appTextPrimaryColor(),
            appBorderColor(0.72f)
        )
    }
    val textColor = textColorOverride ?: baseTextColor
    val borderColor = borderColorOverride ?: baseBorderColor
    val fillModifier = if (kind == ActionButtonKind.PRIMARY) {
        Modifier.background(
            brush = appPrimaryGradient(if (enabled) 1f else 0.35f),
            shape = AppButtonShape
        )
    } else {
        Modifier.background(if (enabled) bg else bg.copy(alpha = 0.35f))
    }

    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(AppButtonShape)
            .then(fillModifier)
            .border(1.dp, if (enabled) borderColor else borderColor.copy(alpha = 0.35f), AppButtonShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (loading) stateDescription = tr("Загрузка")
                if (!enabled) stateDescription = tr("Недоступно")
            }
            .clickable(enabled = enabled && !loading, role = Role.Button) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = textColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
            }

            Text(
                text = label,
                color = textColor,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun QrCodeDialog(
    link: String,
    title: String = "QR-код",
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(link) { generateQrBitmap(link) }

    AppDialog(
        title = title,
        onDismiss = onDismiss,
        content = {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = tr("QR-код"),
                    modifier = Modifier.size(240.dp)
                )
            }
        },
        actions = {
            ActionButton(
                label = "Копировать",
                onClick = onCopy,
                kind = ActionButtonKind.SECONDARY,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                label = "Скачать",
                onClick = {
                    val uri = saveQrBitmapToPictures(context, bitmap)
                    if (uri != null) {
                        Toast.makeText(context, tr("QR-код сохранён"), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, tr("Не удалось сохранить QR-код"), Toast.LENGTH_SHORT).show()
                    }
                },
                kind = ActionButtonKind.PRIMARY,
                icon = Icons.Filled.Download,
                modifier = Modifier.weight(1f)
            )
        }
    )
}

@Composable
internal fun TopIconAction(
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    accent: Color = AppAccent,
    backgroundColor: Color = appGrayControlColor(),
    borderColor: Color = appGrayControlBorderColor(),
    contentDescription: String = "Действие",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled || loading) backgroundColor else backgroundColor.copy(alpha = 0.35f))
            .border(
                1.dp,
                if (enabled || loading) borderColor else borderColor.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp)
            )
            .semantics {
                role = Role.Button
                this.contentDescription = tr(contentDescription)
                if (loading) stateDescription = tr("Загрузка")
                if (!enabled) stateDescription = tr("Недоступно")
            }
            .clickable(enabled = enabled && !loading, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    maxWidth: Dp = 560.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val backdropColor = if (isAppLightTheme()) {
            Color(0xD9EEE7E0)
        } else {
            Color(0xA6121418)
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backdropColor)
                    .blur(2.dp)
                    .clickable(enabled = dismissEnabled) { onDismiss() }
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = maxWidth)
                    .padding(horizontal = 18.dp, vertical = 24.dp)
                    .then(modifier),
                color = appSurfaceColor(),
                shape = CardShape,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .border(1.dp, appBorderColor(0.9f), CardShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = title,
                            color = appTextPrimaryColor(),
                            fontSize = 18.sp,
                            lineHeight = 25.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(end = 54.dp)
                        )

                        if (dismissEnabled) {
                            TopIconAction(
                                icon = Icons.Filled.Close,
                                onClick = onDismiss,
                                accent = appTextSecondaryColor(),
                                contentDescription = tr("Закрыть окно"),
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content
                    )

                    if (actions != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    backgroundColor: Color = appSurfaceColor(),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        shape = CardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, appBorderColor(0.9f), CardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = title,
                            color = appTextPrimaryColor(),
                            fontSize = 18.sp,
                            lineHeight = 25.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() }
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                color = appTextSecondaryColor(),
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                    if (trailing != null) {
                        Spacer(Modifier.width(12.dp))
                        trailing()
                    }
                }

                content()
            }
        )
    }
}

@Composable
internal fun CollapsibleSectionCard(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        color = appSurfaceColor(),
        shape = CardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, appBorderColor(0.9f), CardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        color = appTextPrimaryColor(),
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() }
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, color = appTextSecondaryColor(), fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = AppAccent
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun InfoBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun CopyValueRow(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(appSurfaceElevatedColor())
            .border(1.dp, Color.Transparent, InnerCardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = appTextSecondaryColor(), fontSize = 12.sp)
            Text(value.ifBlank { "—" }, color = appTextPrimaryColor(), fontSize = 14.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = tr("Копировать"),
                tint = AppAccent
            )
        }
    }
}

@Composable
internal fun HomeConnectionCard(
    connectLink: String,
    onConnectLinkChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    parsed: ConnectLinkPayload?,
    reconnectMode: Boolean,
    onConnectClick: () -> Unit
) {
    SectionCard(
        title = "Подключение к конференции"
    ) {
        OutlinedTextField(
            value = connectLink,
            onValueChange = onConnectLinkChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text("Ссылка для подключения") },
            colors = textFieldColors()
        )

        if (parsed != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBadge(
                        label = if (parsed.moderatorKey.isBlank()) "Гость" else "Модератор",
                        color = if (parsed.moderatorKey.isBlank()) appTextSecondaryColor() else AppAccent
                    )
                }

                Text(
                    text = "IP: ${parsed.ip}",
                    color = appTextSecondaryColor(),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Имя") },
            colors = textFieldColors()
        )

        ActionButton(
            label = "Подключиться",
            onClick = onConnectClick,
            enabled = !reconnectMode && parsed != null,
            modifier = Modifier.fillMaxWidth(),
            textColorOverride = AppOnAccent
        )
    }
}

@Composable
internal fun HomeConnectScreen(
    connectLink: String,
    onConnectLinkChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    parsed: ConnectLinkPayload?,
    reconnectMode: Boolean,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val invalidLink = connectLink.isNotBlank() && parsed == null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(30.dp))
        SymposiumWordmark()
        Spacer(Modifier.height(44.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Подключение к конференции",
                color = appTextPrimaryColor(),
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Вы можете оставить поле \"Имя\" пустым",
                color = appTextSecondaryColor(),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Имя") },
                shape = AppButtonShape,
                colors = textFieldColors()
            )

            OutlinedTextField(
                value = connectLink,
                onValueChange = onConnectLinkChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                placeholder = { Text("Вставьте ссылку на встречу") },
                isError = invalidLink,
                shape = AppButtonShape,
                colors = textFieldColors()
            )

            if (invalidLink) {
                Text(
                    text = "Ссылка не распознана. Вставьте ссылку Symposium",
                    color = AppError,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else if (parsed != null) {
                Text(
                    text = "${if (parsed.moderatorKey.isBlank()) "Гость" else "Модератор"} · ${parsed.room}",
                    color = AppSuccess,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(Modifier.height(2.dp))
            ActionButton(
                label = "Подключиться",
                onClick = onConnect,
                enabled = !reconnectMode && parsed != null,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


internal data class PendingHomeSshHostKeyTrust(
    val ip: String,
    val username: String,
    val password: String,
    val observedPin: String
)

@Composable
internal fun HomeMainScreen(
    servers: List<InstallServer>,
    reconnectMode: Boolean,
    roomsRefreshing: Boolean,
    onOpenConnect: () -> Unit,
    onOpenCreateMeeting: () -> Unit,
    onRefreshRooms: () -> Unit,
    onMeetingClick: (InstallServer, OpenRoomInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val meetings = servers.flatMap { server ->
        server.openRooms.map { room -> server to room }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val hasMeetings = meetings.isNotEmpty()
        val centeredHeroTop = ((maxHeight - 250.dp) / 2).coerceAtLeast(32.dp)
        val heroTopPadding by animateDpAsState(
            targetValue = if (hasMeetings) 32.dp else centeredHeroTop,
            animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
            label = "homeHeroTopPadding"
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .padding(top = heroTopPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    SymposiumWordmark()

                    Text(
                        text = "Не телефонный разговор",
                        color = appTextSecondaryColor(),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }

                Spacer(Modifier.height(2.dp))

                HeroActionButton(
                    label = "Подключиться",
                    prompt = ">_",
                    primary = true,
                    enabled = !reconnectMode,
                    onClick = onOpenConnect,
                    modifier = Modifier.fillMaxWidth()
                )
                HeroActionButton(
                    label = "Создать встречу",
                    prompt = "+",
                    primary = false,
                    enabled = true,
                    onClick = onOpenCreateMeeting,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AnimatedVisibility(
                visible = hasMeetings,
                enter = fadeIn(tween(durationMillis = 220, delayMillis = 210)) +
                    expandVertically(tween(durationMillis = 360, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(durationMillis = 140)) +
                    shrinkVertically(tween(durationMillis = 320, easing = FastOutSlowInEasing))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Открытые комнаты",
                            color = appTextPrimaryColor(),
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        RefreshIconButton(
                            onClick = onRefreshRooms,
                            enabled = !roomsRefreshing,
                            loading = roomsRefreshing
                        )
                    }

                    meetings.forEach { (server, room) ->
                        HomeMeetingRow(
                            server = server,
                            room = room,
                            onClick = { onMeetingClick(server, room) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentDescription: String = "Обновить список комнат",
    loadingContentDescription: String = "Обновление списка комнат"
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(AppButtonShape)
            .background(
                brush = appPrimaryGradient(if (enabled || loading) 1f else 0.35f),
                shape = AppButtonShape
            )
            .border(1.dp, Color.Transparent, AppButtonShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.contentDescription = tr(if (loading) loadingContentDescription else contentDescription)
                if (loading) stateDescription = tr("Загрузка")
                if (!enabled) stateDescription = tr("Недоступно")
            }
            .clickable(enabled = enabled && !loading, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = AppOnAccent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = AppOnAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PlainRefreshButton(
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean
) {
    val iconColor = if (isAppLightTheme()) appTextPrimaryColor() else Color.White

    Box(
        modifier = Modifier
            .size(42.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = tr(if (loading) "Обновление списка серверов" else "Обновить список серверов")
                if (loading) stateDescription = tr("Загрузка")
                if (!enabled) stateDescription = tr("Недоступно")
            }
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = iconColor,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(23.dp)
            )
        }
    }
}


@Composable
private fun CompactTextButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    backgroundColor: Color = appGrayControlColor(),
    contentColor: Color = appTextPrimaryColor(),
    borderColor: Color = appGrayControlBorderColor()
) {
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(AppButtonShape)
            .background(if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.35f))
            .border(1.dp, if (enabled) borderColor else borderColor.copy(alpha = 0.35f), AppButtonShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (loading) stateDescription = tr("Загрузка")
                if (!enabled) stateDescription = tr("Недоступно")
            }
            .clickable(enabled = enabled && !loading, role = Role.Button) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = contentColor,
                strokeWidth = 2.dp,
                modifier = Modifier.size(17.dp)
            )
        } else {
            Text(
                text = label,
                color = contentColor,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HeroActionButton(
    label: String,
    prompt: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (primary) AppAccent else appGrayControlColor()
    val border = if (primary) Color.Transparent else appGrayControlBorderColor()
    val textColor = if (primary) AppOnAccent else appTextPrimaryColor()
    val promptColor = textColor
    val fillModifier = if (primary) {
        Modifier.background(
            brush = appPrimaryGradient(if (enabled) 1f else 0.35f),
            shape = AppButtonShape
        )
    } else {
        Modifier.background(if (enabled) background else background.copy(alpha = 0.3f))
    }

    val buttonModifier = if (primary) {
        modifier.shadow(
            elevation = 18.dp,
            shape = AppButtonShape,
            clip = false,
            ambientColor = AppAccent.copy(alpha = 0.5f),
            spotColor = AppAccentEnd.copy(alpha = 0.65f)
        )
    } else {
        modifier
    }

    Box(
        modifier = buttonModifier
            .heightIn(min = 54.dp)
            .clip(AppButtonShape)
            .then(fillModifier)
            .border(1.dp, if (enabled) border else border.copy(alpha = 0.35f), AppButtonShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) stateDescription = tr("Недоступно")
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(56.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = prompt,
                color = promptColor,
                fontSize = 23.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        val labelModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)

        Text(
            text = label,
            color = textColor,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = labelModifier
        )
    }
}

@Composable
private fun SymposiumWordmark(modifier: Modifier = Modifier) {
    Text(
        text = "SYMPOSIUM",
        color = appTextPrimaryColor(),
        fontFamily = Dos2000FontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 42.sp,
        lineHeight = 46.sp,
        letterSpacing = 0.2.sp,
        modifier = modifier
    )
}

@Composable
private fun HomeMeetingRow(
    server: InstallServer,
    room: OpenRoomInfo,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = appRoomSurfaceColor(),
        shape = InnerCardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, appGrayControlBorderColor(), InnerCardShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = room.name,
                    color = appTextPrimaryColor(),
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "IP: ${serverDisplayAddress(server)}",
                    color = AppAccent,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "Нажмите, чтобы открыть",
                    color = appTextSecondaryColor(),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier.size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MeetingRoom,
                    contentDescription = tr("Открыть комнату"),
                    tint = appTextPrimaryColor(),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
internal fun HomeConnectDialog(
    connectLink: String,
    onConnectLinkChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    parsed: ConnectLinkPayload?,
    reconnectMode: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit
) {
    AppDialog(
        title = "Подключиться",
        onDismiss = onDismiss,
        content = {
            OutlinedTextField(
                value = connectLink,
                onValueChange = onConnectLinkChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Ссылка на встречу") },
                colors = textFieldColors()
            )

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Имя (необязательно)") },
                colors = textFieldColors()
            )

            if (parsed == null && connectLink.isNotBlank()) {
                ErrorMessage("Ссылка не распознана. Вставьте ссылку в формате Symposium")
            }

            if (parsed != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InnerCardShape)
                        .background(appBackgroundColor())
                        .border(1.dp, appBorderColor(0.8f), InnerCardShape)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoBadge(
                            label = if (parsed.moderatorKey.isBlank()) "Гость" else "Модератор",
                            color = if (parsed.moderatorKey.isBlank()) appTextSecondaryColor() else AppAccent
                        )
                    }

                    Text(
                        text = "IP: ${parsed.ip}",
                        color = appTextSecondaryColor(),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Комната: ${parsed.room}",
                        color = appTextSecondaryColor(),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        actions = {
            ActionButton(
                label = "Подключиться",
                onClick = onConnect,
                enabled = !reconnectMode && parsed != null,
                modifier = Modifier.weight(1f),
                textColorOverride = AppOnAccent
            )
        }
    )
}

internal fun canCreateMeetingOnServer(server: InstallServer): Boolean {
    return server.installed && !server.relayTlsPin.isNullOrBlank() && !server.adminToken.isNullOrBlank()
}

@Composable
internal fun HomeCreateMeetingDialog(
    servers: List<InstallServer>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onAddServer: () -> Unit,
    onOpenServerGuide: () -> Unit,
    onRefresh: () -> Unit,
    onServerClick: (InstallServer) -> Unit
) {
    AppDialog(
        title = "Создать встречу",
        onDismiss = onDismiss,
        dismissEnabled = true,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Выберите сервер, на котором будет открыта новая комната.",
                    color = appTextSecondaryColor(),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactTextButton(
                        label = "Где взять сервер?",
                        onClick = onOpenServerGuide,
                        enabled = !loading,
                        backgroundColor = AppError,
                        contentColor = Color.White,
                        borderColor = AppError
                    )

                    Spacer(Modifier.width(12.dp))

                    PlainRefreshButton(
                        onClick = onRefresh,
                        enabled = !loading,
                        loading = loading
                    )
                }

                if (servers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(InnerCardShape)
                            .background(appSurfaceElevatedColor())
                            .border(1.dp, Color.Transparent, InnerCardShape)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Серверов пока нет. Для создания встречи добавьте сервер",
                            color = appTextSecondaryColor(),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                } else {
                    servers.forEach { server ->
                        HomeServerPickerRow(
                            server = server,
                            enabled = !loading,
                            onClick = { onServerClick(server) }
                        )
                    }
                }
            }
        },
        actions = {
            ActionButton(
                label = "Добавить сервер",
                onClick = onAddServer,
                kind = ActionButtonKind.PRIMARY,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
internal fun HomeServerGuideDialog(
    onDismiss: () -> Unit
) {
    val guideContentMaxHeight = (LocalConfiguration.current.screenHeightDp.dp - 260.dp)
        .coerceIn(220.dp, 540.dp)

    AppDialog(
        title = "Где взять сервер?",
        onDismiss = onDismiss,
        dismissEnabled = true,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = guideContentMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Для конференции нужен обычный VPS: маленький виртуальный сервер с публичным IPv4 и SSH-доступом. Приложение само установит на него SymposiumRelay",
                    color = appTextSecondaryColor(),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                ServerGuideBlock(
                    title = "Что покупать?",
                    body = "VPS или облачный сервер на Debian/Ubuntu. Минимум: 1 vCPU и 2 GB RAM. Лучше: 2vCPU и 2 GB RAM. Обязательно нужен публичный IPv4",
                    color = AppAccent
                )

                ServerGuideBlock(
                    title = "Где лучше покупать?",
                    body = "Покупать стоит у крупных VPS провайдеров не попадающих под ограничения в регионе участников конференции",
                    color = AppSuccess
                )

                ServerGuideBlock(
                    title = "Где не стоит покупать?",
                    body = "Лучше не брать сервера у провайдеров, которые попадают под ограничения в регионе участников. Так например сервера от Hetzner и DigitalOcean в России подвергаются ограничениям и работать не будут!",
                    color = AppError
                )

                ServerGuideBlock(
                    title = "Как настроить при покупке",
                    body = "Выберите операционную систему Debian, или Ubuntu. Включите IPv4. Сохраните IP-адрес, логин и пароль. Если провайдер просит настроить firewall, разрешите 443/tcp и 32768–60999/udp",
                    color = AppWarning
                )

                ServerGuideBlock(
                    title = "Что делать дальше?",
                    body = "После покупки нажмите «Добавить», введите IP, логин и пароль. При первом подключении подтвердите SSH-ключ, затем установите SymposiumRelay. После установки сервер появится в списке для создания встреч.",
                    color = appTextSecondaryColor()
                )
            }
        },
        actions = {
            ActionButton(
                label = "Понятно",
                onClick = onDismiss,
                kind = ActionButtonKind.PRIMARY,
                modifier = Modifier.weight(1f)
            )
        }
    )
}

@Composable
private fun ServerGuideBlock(
    title: String,
    body: String,
    color: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerCardShape)
            .background(appSurfaceElevatedColor())
            .border(1.dp, Color.Transparent, InnerCardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InfoBadge(label = title, color = color)
        Text(
            text = body,
            color = appTextPrimaryColor(),
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun HomeServerPickerRow(
    server: InstallServer,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val problem = when {
        !server.installed -> "SymposiumRelay не установлен. Нажмите, чтобы установить."
        server.relayTlsPin.isNullOrBlank() -> "Нет TLS pin. Нажмите, чтобы переустановить Relay."
        server.adminToken.isNullOrBlank() -> "Нет adminToken. Нажмите, чтобы переустановить Relay."
        else -> null
    }

    val actionLabel = when {
        problem == null -> "Выбрать"
        !server.installed -> "Установить"
        else -> "Исправить"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = appSurfaceElevatedColor(),
        shape = InnerCardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, appGrayControlBorderColor(), InnerCardShape)
                .clickable(enabled = enabled, role = Role.Button) { onClick() }
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = serverDisplayAddress(server),
                    color = if (enabled) appTextPrimaryColor() else appTextSecondaryColor(),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = problem ?: "Открытых комнат: ${openRoomsLabel(server.openRooms.size)}",
                    color = if (problem == null) appTextSecondaryColor() else AppWarning,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(10.dp))
            InfoBadge(
                label = actionLabel,
                color = if (problem == null) AppAccent else AppWarning
            )
        }
    }
}

@Composable
internal fun HomeAddServerDialog(
    ip: String,
    username: String,
    password: String,
    error: String?,
    loading: Boolean,
    onIpChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdd: () -> Unit
) {
    AppDialog(
        title = "Добавить сервер",
        onDismiss = onDismiss,
        dismissEnabled = !loading,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = onIpChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("IP адрес") },
                    colors = textFieldColors()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Логин") },
                    colors = textFieldColors()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Пароль") },
                    colors = textFieldColors()
                )
                if (error != null) {
                    ErrorMessage(error)
                }
            }
        },
        actions = {
            ActionButton(
                label = "Добавить",
                onClick = onAdd,
                enabled = !loading,
                loading = loading,
                modifier = Modifier.weight(1f)
            )
        }
    )
}

@Composable
internal fun HomeSshHostKeyTrustDialog(
    trust: PendingHomeSshHostKeyTrust,
    loading: Boolean,
    onCopy: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        title = "Запомнить SSH-ключ",
        onDismiss = onDismiss,
        dismissEnabled = !loading,
        content = {
            Text(
                text = "Первое подключение к серверу ${trust.ip}. Если IP указан верно, приложение запомнит fingerprint и предупредит при его изменении.",
                color = appTextPrimaryColor(),
                fontSize = 14.sp,
                lineHeight = 19.sp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InnerCardShape)
                    .background(appBackgroundColor())
                    .border(1.dp, appBorderColor(), InnerCardShape)
                    .padding(12.dp)
            ) {
                Text(trust.observedPin, color = appTextPrimaryColor(), fontSize = 13.sp, lineHeight = 18.sp)
            }
        },
        actions = {
            ActionButton(
                label = "Копировать",
                onClick = onCopy,
                kind = ActionButtonKind.SECONDARY,
                enabled = !loading,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                label = "Запомнить",
                onClick = onConfirm,
                enabled = !loading,
                loading = loading,
                modifier = Modifier.weight(1f)
            )
        }
    )
}

@Composable
internal fun HomeInstallRelayDialog(
    server: InstallServer,
    logs: List<String>,
    error: String?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit
) {
    val logListState = rememberLazyListState()
    val visibleLogCount = logs.takeLast(120).size

    LaunchedEffect(logs.size) {
        if (visibleLogCount > 0) {
            logListState.animateScrollToItem(visibleLogCount - 1)
        }
    }

    AppDialog(
        title = "Установить SymposiumRelay",
        onDismiss = onDismiss,
        dismissEnabled = !loading,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoBadge(label = serverDisplayAddress(server), color = AppAccent)
                Text(
                    text = "На этом сервере ещё нельзя создать встречу. Сперва установите SymposiumRelay",
                    color = appTextSecondaryColor(),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                if (server.username.isBlank() || server.password.isBlank()) {
                    ErrorMessage("Для установки нужны сохранённые SSH-логин и пароль. Добавьте сервер заново")
                }
                if (error != null) {
                    ErrorMessage(error)
                }
                if (logs.isNotEmpty()) {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 240.dp)
                            .clip(InnerCardShape)
                            .background(appSurfaceElevatedColor())
                            .border(1.dp, Color.Transparent, InnerCardShape),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(logs.takeLast(120)) { line ->
                            Text(
                                text = line,
                                color = appTextSecondaryColor(),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        },
        actions = {
            ActionButton(
                label = "Установить SymposiumRelay",
                onClick = onInstall,
                enabled = !loading && server.username.isNotBlank() && server.password.isNotBlank(),
                loading = loading,
                modifier = Modifier.weight(1f)
            )
        }
    )
}

@Composable
internal fun HomeCreateRoomDialog(
    server: InstallServer,
    roomName: String,
    error: String?,
    loading: Boolean,
    onRoomNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit
) {
    AppDialog(
        title = "Новая встреча",
        onDismiss = onDismiss,
        dismissEnabled = !loading,
        content = {
            OutlinedTextField(
                value = roomName,
                onValueChange = onRoomNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Название комнаты") },
                colors = textFieldColors()
            )
            if (error != null) {
                ErrorMessage(error)
            }
        },
        actions = {
            ActionButton(
                label = "Создать",
                onClick = onCreate,
                enabled = !loading,
                loading = loading,
                modifier = Modifier.weight(1f)
            )
        }
    )
}

@Composable
internal fun HomeMeetingDialog(
    server: InstallServer,
    room: OpenRoomInfo,
    loading: Boolean,
    onCopyGuest: () -> Unit,
    onQrGuest: () -> Unit,
    onCopyModerator: () -> Unit,
    onQrModerator: () -> Unit,
    onRotateModeratorLink: () -> Unit,
    onCloseMeeting: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        title = room.name,
        onDismiss = onDismiss,
        dismissEnabled = !loading,
        maxWidth = 680.dp,
        content = {
            RoomLinkActionsRow(
                title = "Гость",
                subtitle = "Ссылка для подключения без прав модератора",
                badgeColor = appTextSecondaryColor(),
                onCopy = onCopyGuest,
                onQr = onQrGuest,
                enabled = !loading,
                comfortable = true
            )
            RoomLinkActionsRow(
                title = "Модератор",
                subtitle = if (room.moderatorKey.isBlank()) {
                    "moderator_key не получен"
                } else {
                    "Ссылка с правами управления комнатой"
                },
                badgeColor = AppAccent,
                onCopy = onCopyModerator,
                onQr = onQrModerator,
                enabled = !loading && room.moderatorKey.isNotBlank(),
                comfortable = true
            )
            RoomDialogTextAction(
                label = "Сменить ссылку модератора",
                onClick = onRotateModeratorLink,
                enabled = !loading
            )
        },
        actions = {
            ActionButton(
                label = "Закрыть комнату",
                onClick = onCloseMeeting,
                kind = ActionButtonKind.GHOST,
                textColorOverride = AppError,
                borderColorOverride = AppError.copy(alpha = 0.35f),
                enabled = !loading,
                loading = loading,
                modifier = Modifier.weight(1f)
            )
        }
    )
}

@Composable
private fun RoomDialogTextAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .clip(AppButtonShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) stateDescription = tr("Недоступно")
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            tint = if (enabled) appTextSecondaryColor() else appTextMutedColor(),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            color = if (enabled) appTextSecondaryColor() else appTextMutedColor(),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

internal fun isInitialConnectionFailure(status: String): Boolean {
    val normalized = status.trim().lowercase()
    return normalized.contains("invalid") ||
        normalized.contains("failed") ||
        normalized.contains("server error")
}

internal fun initialConnectionStatusMessage(status: String): String {
    val normalized = status.trim().lowercase()
    return when {
        normalized.contains("invalid e2ee") -> "Ссылка содержит некорректный ключ шифрования."
        normalized.contains("invalid tls") -> "Не удалось проверить защищённое соединение с сервером."
        normalized.contains("e2ee initialization failed") -> "Не удалось включить сквозное шифрование."
        normalized.contains("server error") -> "Сервер отклонил подключение."
        normalized.contains("reconnecting") -> "Повторяем попытку подключения…"
        normalized.contains("connecting") -> "Устанавливаем защищённое соединение…"
        else -> "Готовим подключение…"
    }
}

@Composable
internal fun ConnectingRoomView(
    roomName: String,
    status: String,
    onCancel: () -> Unit
) {
    val failed = isInitialConnectionFailure(status)
    val accent = if (failed) accessibleDangerColor() else accessibleAccentColor()
    val transition = rememberInfiniteTransition(label = "room_connection")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "room_connection_pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundColor())
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .graphicsLayer {
                            val scale = if (failed) 1f else pulse
                            scaleX = scale
                            scaleY = scale
                            alpha = if (failed) 0.42f else 0.3f + (pulse - 0.88f)
                        }
                        .border(2.dp, accent, CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (failed) "Не удалось подключиться" else "Подключаемся к комнате",
                    color = appTextPrimaryColor(),
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                if (roomName.isNotBlank()) {
                    Text(
                        text = roomName,
                        color = accent,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = initialConnectionStatusMessage(status),
                    color = appTextSecondaryColor(),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
            }

            ActionButton(
                label = if (failed) "Вернуться" else "Отменить подключение",
                onClick = onCancel,
                kind = ActionButtonKind.SECONDARY,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun LobbyWaitingView(onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundColor())
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        SectionCard(
            title = "Ожидание допуска",
            subtitle = "Вы подключились как гость. Модератор должен впустить вас в комнату."
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = AppAccent, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(
                    text = "Ожидаем решения модератора…",
                    color = appTextPrimaryColor(),
                    fontSize = 14.sp
                )
            }
            ActionButton(
                label = "Отменить подключение",
                onClick = onCancel,
                kind = ActionButtonKind.SECONDARY,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
