package com.lux032.musicbridge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Design D type system: an editorial serif carries display / headline / large titles (page
 * names, album and artist names, section headers), the system sans carries everything people
 * scan and operate (lists, labels, controls). On Android `FontFamily.Serif` resolves to Noto
 * Serif, with Noto Serif CJK for Chinese/Japanese where the device ships it.
 */
val TitleFontFamily: FontFamily = FontFamily.Serif

private val Base = Typography()

private fun TextStyle.serif(weight: FontWeight = FontWeight.Bold) =
    copy(fontFamily = TitleFontFamily, fontWeight = weight, letterSpacing = 0.sp)

val Typography = Typography(
    displayLarge = Base.displayLarge.serif(),
    displayMedium = Base.displayMedium.serif(),
    displaySmall = Base.displaySmall.serif(),
    headlineLarge = Base.headlineLarge.serif(),
    headlineMedium = Base.headlineMedium.serif(),
    headlineSmall = Base.headlineSmall.serif(),
    titleLarge = Base.titleLarge.serif(),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp,
    ),
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.SemiBold),
)
