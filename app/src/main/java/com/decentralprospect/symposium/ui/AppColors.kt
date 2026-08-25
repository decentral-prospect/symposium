package com.decentralprospect.symposium

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val AppAccentStart = Color(0xFFFB8F46)
val AppAccentEnd = Color(0xFFDA551C)
val AppAccent = AppAccentStart
val AppAccentHover = Color(0xFFFFA267)
val AppAccentPressed = AppAccentEnd
val AppAccentSubtleDark = Color(0xFF3D1C09)
val AppAccentSubtleLight = Color(0xFFFFE3D1)
val AppOnAccent = Color.White

val DarkBackground = Color(0xFF0E0E10)
val DarkSurface = Color(0xFF0E0E10)
val DarkSurfaceElevated = Color(0xFF28272D)
val DarkGrayControl = Color(0xFF45434B)
val DarkMenuItem = Color(0xFF45434B)
val DarkRoomSurface = Color(0xFF28272D)
val DarkFieldFill = Color(0xFF28272D)
val DarkFieldBorder = Color(0xFF45434B)
val DarkBorder = Color(0xFF28272D)
val DarkBottomNav = Color(0xFF1E1D23)

val DarkTextPrimary = Color.White
val DarkTextSecondary = Color(0xFFA0A0A0)
val DarkTextMuted = Color(0xFF737373)

val LightBackground = Color.White
val LightSurface = Color.White
val LightSurfaceElevated = Color(0xFFF8F8F8)
val LightGrayControl = Color(0xFFDADADA)
val LightMenuItem = Color(0xFFDADADA)
val LightRoomSurface = Color(0xFFF8F8F8)
val LightFieldFill = Color(0xFFF8F8F8)
val LightFieldBorder = Color(0xFFDADADA)
val LightBorder = Color(0xFFE6E6E6)
val LightBottomNav = Color(0xFFF8F8F8)

val LightTextPrimary = Color(0xFF0E0E10)
val LightTextSecondary = Color(0xFF7F7F7F)
val LightTextMuted = Color(0xFF9A9A9A)

val AppSuccess = Color(0xFF00C950)
val AppSuccessPressed = Color(0xFF009E3F)
val AppSuccessContainerDark = Color(0xFF07371B)
val AppSuccessContainerLight = Color(0xFFD9F8E5)

val AppError = Color(0xFFF44336)
val AppErrorPressed = Color(0xFFD32F2F)
val AppErrorContainerDark = Color(0xFF481513)
val AppErrorContainerLight = Color(0xFFFFDEDC)

val AppWarning = Color(0xFFC58232)
val AppWarningContainerDark = Color(0xFF432A12)
val AppWarningContainerLight = Color(0xFFF7E7CF)

val AppIdle = Color(0xFF7D746D)

val CallBackground = DarkBackground
val CallSurface = DarkSurface
val CallSurfaceElevated = DarkSurfaceElevated
val CallControlBackground = DarkSurfaceElevated
val CallFieldFill = DarkBackground
val CallBorder = DarkBorder
val CallFieldBorder = DarkFieldBorder
val CallTextPrimary = DarkTextPrimary
val CallTextSecondary = DarkTextSecondary

val CallLightBackground = LightBackground
val CallLightSurface = LightSurface
val CallLightSurfaceElevated = LightSurfaceElevated
val CallLightControlBackground = Color(0xFFECECEC)
val CallLightFieldFill = LightFieldFill
val CallLightBorder = LightBorder
val CallLightFieldBorder = LightFieldBorder
val CallLightTextPrimary = LightTextPrimary
val CallLightTextSecondary = LightTextSecondary

val CallControlActive = AppAccent
val CallIcon = CallTextPrimary
val CallIconMuted = CallTextSecondary

internal fun appPrimaryGradient(alpha: Float = 1f): Brush {
    val resolvedAlpha = alpha.coerceIn(0f, 1f)
    return Brush.horizontalGradient(
        colors = listOf(
            AppAccentStart.copy(alpha = resolvedAlpha),
            AppAccentEnd.copy(alpha = resolvedAlpha)
        )
    )
}

@Composable
internal fun isAppLightTheme(): Boolean =
    MaterialTheme.colorScheme.background == LightBackground

@Composable
internal fun callBackgroundColor(): Color =
    if (isAppLightTheme()) CallLightBackground else CallBackground

@Composable
internal fun callSurfaceColor(): Color =
    if (isAppLightTheme()) CallLightSurface else CallSurface

@Composable
internal fun callSurfaceElevatedColor(): Color =
    if (isAppLightTheme()) CallLightSurfaceElevated else CallSurfaceElevated

@Composable
internal fun callControlBackgroundColor(): Color =
    if (isAppLightTheme()) CallLightControlBackground else CallControlBackground

@Composable
internal fun callFieldBackgroundColor(): Color =
    if (isAppLightTheme()) CallLightFieldFill else CallFieldFill

@Composable
internal fun callBorderColor(alpha: Float = 1f): Color {
    val background = if (isAppLightTheme()) CallLightSurface else CallSurface
    val border = if (isAppLightTheme()) CallLightBorder else CallBorder
    return blendSolid(border, background, alpha)
}

@Composable
internal fun callFieldBorderColor(alpha: Float = 1f): Color {
    val background = callFieldBackgroundColor()
    val border = if (isAppLightTheme()) CallLightFieldBorder else CallFieldBorder
    return blendSolid(border, background, alpha)
}

@Composable
internal fun callTextPrimaryColor(): Color =
    if (isAppLightTheme()) CallLightTextPrimary else CallTextPrimary

@Composable
internal fun callTextSecondaryColor(): Color =
    if (isAppLightTheme()) CallLightTextSecondary else CallTextSecondary

@Composable
internal fun callIconColor(): Color = callTextPrimaryColor()

@Composable
internal fun callIconMutedColor(): Color = callTextSecondaryColor()

@Composable
internal fun appBackgroundColor(): Color =
    MaterialTheme.colorScheme.background

@Composable
internal fun appSurfaceColor(): Color =
    MaterialTheme.colorScheme.surface

@Composable
internal fun appSurfaceElevatedColor(): Color =
    MaterialTheme.colorScheme.surfaceVariant

@Composable
internal fun appGrayControlColor(): Color =
    if (isAppLightTheme()) LightGrayControl else DarkGrayControl

@Composable
internal fun appMenuItemColor(): Color =
    if (isAppLightTheme()) LightMenuItem else DarkMenuItem

@Composable
internal fun appRoomSurfaceColor(): Color =
    if (isAppLightTheme()) LightRoomSurface else DarkRoomSurface

@Composable
internal fun appBottomNavColor(): Color =
    if (isAppLightTheme()) LightBottomNav else DarkBottomNav

@Composable
internal fun appGrayControlBorderColor(alpha: Float = 1f): Color {
    return Color.Transparent
}

private fun blendSolid(foreground: Color, background: Color, alpha: Float): Color {
    val a = alpha.coerceIn(0f, 1f)
    return Color(
        red = foreground.red * a + background.red * (1f - a),
        green = foreground.green * a + background.green * (1f - a),
        blue = foreground.blue * a + background.blue * (1f - a),
        alpha = 1f
    )
}

@Composable
internal fun appBorderColor(alpha: Float = 1f): Color {
    val background = MaterialTheme.colorScheme.surface
    val border = MaterialTheme.colorScheme.outline
    return blendSolid(border, background, alpha)
}

@Composable
internal fun appFieldContainerColor(): Color =
    if (MaterialTheme.colorScheme.background == LightBackground) {
        LightFieldFill
    } else {
        DarkFieldFill
    }

@Composable
internal fun appFieldBorderColor(alpha: Float = 1f): Color {
    val background = appFieldContainerColor()
    val border =
        if (MaterialTheme.colorScheme.background == LightBackground) {
            LightFieldBorder
        } else {
            DarkFieldBorder
        }

    return blendSolid(border, background, alpha)
}

@Composable
internal fun appTextPrimaryColor(): Color =
    MaterialTheme.colorScheme.onSurface

@Composable
internal fun appTextSecondaryColor(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant

@Composable
internal fun appTextMutedColor(): Color =
    if (MaterialTheme.colorScheme.background == LightBackground) {
        LightTextMuted
    } else {
        DarkTextMuted
    }
