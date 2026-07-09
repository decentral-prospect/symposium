package com.decentralprospect.symposium

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AppAccent = Color(0xFFFB8F46)
val AppAccentHover = Color(0xFFFF966B)
val AppAccentPressed = Color(0xFFE7652E)
val AppAccentSubtleDark = Color(0xFF3A1D12)
val AppAccentSubtleLight = Color(0xFFFFE1D2)
val AppOnAccent = Color(0xFF1A0B06)

val DarkBackground = Color(0xFF0E1013)
val DarkSurface = Color(0xFF16191E)
val DarkSurfaceElevated = Color(0xFF1F232A)
val DarkFieldFill = Color(0xFF12151A)
val DarkFieldBorder = Color(0xFF333842)
val DarkBorder = Color(0xFF2B3038)

val DarkTextPrimary = Color(0xFFF7F1EC)
val DarkTextSecondary = Color(0xFFC8BDB5)
val DarkTextMuted = Color(0xFF8B817A)

val LightBackground = Color(0xFFF5EFE9)
val LightSurface = Color(0xFFFFF9F4)
val LightSurfaceElevated = Color(0xFFF8EFE7)
val LightFieldFill = Color(0xFFF0E5DC)
val LightFieldBorder = Color(0xFFD7C8BC)
val LightBorder = Color(0xFFD1C2B6)

val LightTextPrimary = Color(0xFF1D120D)
val LightTextSecondary = Color(0xFF5C473B)
val LightTextMuted = Color(0xFF7F6D62)

val AppSuccess = Color(0xFF2FA866)
val AppSuccessPressed = Color(0xFF207849)
val AppSuccessContainerDark = Color(0xFF153C28)
val AppSuccessContainerLight = Color(0xFFDDF5E8)

val AppError = Color(0xFFB63A32)
val AppErrorPressed = Color(0xFF84231E)
val AppErrorContainerDark = Color(0xFF471916)
val AppErrorContainerLight = Color(0xFFF7D8D4)

val AppWarning = Color(0xFFC58232)
val AppWarningContainerDark = Color(0xFF432A12)
val AppWarningContainerLight = Color(0xFFF7E7CF)

val AppIdle = Color(0xFF7D746D)

val CallBackground = Color(0xFF08090B)
val CallSurface = DarkSurface
val CallSurfaceElevated = DarkSurfaceElevated
val CallControlBackground = Color(0xFF171A20)
val CallFieldFill = DarkBackground
val CallBorder = DarkBorder
val CallFieldBorder = DarkFieldBorder
val CallTextPrimary = DarkTextPrimary
val CallTextSecondary = DarkTextSecondary

val CallLightBackground = Color(0xFFF1E8E0)
val CallLightSurface = Color(0xFFFFFBF7)
val CallLightSurfaceElevated = Color(0xFFF7EEE6)
val CallLightControlBackground = Color(0xFFEADDD1)
val CallLightFieldFill = Color(0xFFE7D9CC)
val CallLightBorder = Color(0xFFC9B6A7)
val CallLightFieldBorder = Color(0xFFB9A392)
val CallLightTextPrimary = Color(0xFF21140F)
val CallLightTextSecondary = Color(0xFF624D41)

val CallControlActive = AppAccent
val CallIcon = CallTextPrimary
val CallIconMuted = CallTextSecondary


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
