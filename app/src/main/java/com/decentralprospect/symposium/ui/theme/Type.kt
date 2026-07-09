package com.decentralprospect.symposium.ui.theme

import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.decentralprospect.symposium.R

val Dos2000FontFamily = FontFamily(
    Font(R.font.dos2000, weight = FontWeight.ExtraBold)
)

val RobotoFontFamily = FontFamily(
    Font(R.font.roboto_w450, weight = FontWeight.Normal),
    Font(R.font.roboto_w550, weight = FontWeight.Medium),
    Font(R.font.roboto_w650, weight = FontWeight.SemiBold),
    Font(R.font.roboto_w750, weight = FontWeight.Bold),
    Font(R.font.roboto_w850, weight = FontWeight.ExtraBold)
)

private val BaseTypography = MaterialTypography()

val Typography = MaterialTypography(
    displayLarge = BaseTypography.displayLarge.withAppFont(),
    displayMedium = BaseTypography.displayMedium.withAppFont(),
    displaySmall = BaseTypography.displaySmall.withAppFont(),
    headlineLarge = BaseTypography.headlineLarge.withAppFont(),
    headlineMedium = BaseTypography.headlineMedium.withAppFont(),
    headlineSmall = BaseTypography.headlineSmall.withAppFont(),
    titleLarge = BaseTypography.titleLarge.withAppFont(),
    titleMedium = BaseTypography.titleMedium.withAppFont(),
    titleSmall = BaseTypography.titleSmall.withAppFont(),
    bodyLarge = BaseTypography.bodyLarge.withAppFont(),
    bodyMedium = BaseTypography.bodyMedium.withAppFont(),
    bodySmall = BaseTypography.bodySmall.withAppFont(),
    labelLarge = BaseTypography.labelLarge.withAppFont(),
    labelMedium = BaseTypography.labelMedium.withAppFont(),
    labelSmall = BaseTypography.labelSmall.withAppFont()
)

private fun TextStyle.withAppFont(): TextStyle = copy(
    fontFamily = RobotoFontFamily
)
