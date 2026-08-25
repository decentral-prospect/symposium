package com.decentralprospect.symposium.ui.theme

import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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

val Typography = MaterialTypography(
    displayLarge = appTextStyle(24, FontWeight.Medium, 30),
    displayMedium = appTextStyle(24, FontWeight.Medium, 30),
    displaySmall = appTextStyle(24, FontWeight.Medium, 30),
    headlineLarge = appTextStyle(24, FontWeight.Medium, 30),
    headlineMedium = appTextStyle(18, FontWeight.Bold, 24),
    headlineSmall = appTextStyle(16, FontWeight.Normal, 22),
    titleLarge = appTextStyle(18, FontWeight.Bold, 24),
    titleMedium = appTextStyle(16, FontWeight.Medium, 22),
    titleSmall = appTextStyle(14, FontWeight.Medium, 20),
    bodyLarge = appTextStyle(16, FontWeight.Normal, 24),
    bodyMedium = appTextStyle(16, FontWeight.Normal, 22),
    bodySmall = appTextStyle(14, FontWeight.Normal, 20),
    labelLarge = appTextStyle(16, FontWeight.Medium, 22),
    labelMedium = appTextStyle(14, FontWeight.Medium, 20),
    labelSmall = appTextStyle(12, FontWeight.Medium, 16)
)

private fun appTextStyle(
    size: Int,
    weight: FontWeight,
    lineHeight: Int
): TextStyle = TextStyle(
    fontFamily = RobotoFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp
)
