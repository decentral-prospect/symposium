package com.decentralprospect.symposium

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
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
            appSurfaceElevatedColor(),
            appTextPrimaryColor(),
            appBorderColor(0.95f)
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

    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(AppButtonShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.35f))
            .border(1.dp, if (enabled) borderColor else borderColor.copy(alpha = 0.35f), AppButtonShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (loading) stateDescription = "Загрузка"
                if (!enabled) stateDescription = "Недоступно"
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
                    contentDescription = "QR-код",
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
                        Toast.makeText(context, "QR-код сохранён", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Не удалось сохранить QR-код", Toast.LENGTH_SHORT).show()
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
    contentDescription: String = "Действие",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(appSurfaceElevatedColor())
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (loading) stateDescription = "Загрузка"
                if (!enabled) stateDescription = "Недоступно"
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
                    .widthIn(max = 560.dp)
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
                                contentDescription = "Закрыть окно",
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
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = appSurfaceColor(),
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
            .border(1.dp, appBorderColor(0.8f), InnerCardShape)
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
                contentDescription = "Копировать",
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
    onMeetingClick: (InstallServer, OpenRoomInfo) -> Unit
) {
    val meetings = servers.flatMap { server ->
        server.openRooms.map { room -> server to room }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "SYMPOSIUM",
                color = appTextPrimaryColor(),
                fontFamily = Dos2000FontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 34.sp,
                letterSpacing = 0.2.sp
            )

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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Открытые комнаты",
                    color = appTextPrimaryColor(),
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                RefreshIconButton(
                    onClick = onRefreshRooms,
                    enabled = !roomsRefreshing,
                    loading = roomsRefreshing
                )
            }

            if (meetings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InnerCardShape)
                        .background(appSurfaceColor())
                        .border(1.dp, appBorderColor(0.75f), InnerCardShape)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Открытые комнаты появятся здесь.",
                        color = appTextSecondaryColor(),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            } else {
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

@Composable
private fun RefreshIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(AppButtonShape)
            .background(if (enabled || loading) AppAccent else AppAccent.copy(alpha = 0.35f))
            .border(1.dp, Color.Transparent, AppButtonShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (loading) "Обновление списка комнат" else "Обновить список комнат"
                if (loading) stateDescription = "Загрузка"
                if (!enabled) stateDescription = "Недоступно"
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
private fun CompactTextButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    backgroundColor: Color = appSurfaceElevatedColor(),
    contentColor: Color = appTextPrimaryColor(),
    borderColor: Color = appBorderColor(0.95f)
) {
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(AppButtonShape)
            .background(if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.35f))
            .border(1.dp, if (enabled) borderColor else borderColor.copy(alpha = 0.35f), AppButtonShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (loading) stateDescription = "Загрузка"
                if (!enabled) stateDescription = "Недоступно"
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
    val background = if (primary) AppAccent else Color.Transparent
    val border = if (primary) Color.Transparent else appBorderColor(0.95f)
    val textColor = if (primary) AppOnAccent else appTextPrimaryColor()
    val promptColor = textColor

    Box(
        modifier = modifier
            .heightIn(min = 60.dp)
            .clip(AppButtonShape)
            .background(if (enabled) background else background.copy(alpha = 0.3f))
            .border(1.dp, if (enabled) border else border.copy(alpha = 0.35f), AppButtonShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) stateDescription = "Недоступно"
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
            fontSize = 18.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = labelModifier
        )
    }
}

@Composable
private fun HomeMeetingRow(
    server: InstallServer,
    room: OpenRoomInfo,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = appSurfaceColor(),
        shape = InnerCardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, appBorderColor(0.85f), InnerCardShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = room.name,
                    color = appTextPrimaryColor(),
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

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
                color = appTextSecondaryColor().copy(alpha = 0.82f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
                    horizontalArrangement = Arrangement.Start,
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
                }

                if (servers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(InnerCardShape)
                            .background(appSurfaceElevatedColor())
                            .border(1.dp, appBorderColor(), InnerCardShape)
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
                label = "Обновить",
                onClick = onRefresh,
                kind = ActionButtonKind.SECONDARY,
                enabled = !loading,
                loading = loading,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                label = "Добавить сервер",
                onClick = onAddServer,
                kind = ActionButtonKind.PRIMARY,
                enabled = !loading,
                modifier = Modifier.weight(1f)
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
            .border(1.dp, appBorderColor(), InnerCardShape)
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
                .border(1.dp, appBorderColor(), InnerCardShape)
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
                            .border(1.dp, appBorderColor(), InnerCardShape),
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
    onCloseMeeting: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        title = room.name,
        onDismiss = onDismiss,
        dismissEnabled = !loading,
        content = {
            RoomLinkActionsRow(
                title = "Гость",
                subtitle = "",
                badgeColor = appTextSecondaryColor(),
                onCopy = onCopyGuest,
                onQr = onQrGuest,
                enabled = !loading
            )
            RoomLinkActionsRow(
                title = "Модератор",
                subtitle = if (room.moderatorKey.isBlank()) "moderator_key не получен" else "",
                badgeColor = AppAccent,
                onCopy = onCopyModerator,
                onQr = onQrModerator,
                enabled = !loading && room.moderatorKey.isNotBlank()
            )
        },
        actions = {
            ActionButton(
                label = "Закрыть встречу",
                onClick = onCloseMeeting,
                kind = ActionButtonKind.DANGER,
                enabled = !loading,
                loading = loading,
                modifier = Modifier.weight(1f)
            )
        }
    )
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
