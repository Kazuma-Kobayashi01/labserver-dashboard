package com.labserver.lablink.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.labserver.lablink.R

/**
 * Nunito (SIL OFL, see NUNITO-OFL.txt): rounded terminals that match the round orb. It is a variable font, so one file
 * serves every weight. Chinese characters are not in Nunito and fall back to the system font.
 */
@OptIn(ExperimentalTextApi::class)
val Nunito = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map { w ->
        Font(R.font.nunito, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
    },
)

private fun TextStyle.nunito(weight: FontWeight? = null, lineHeight: Int? = null, letterSpacing: Float? = null): TextStyle =
    copy(
        fontFamily = Nunito,
        fontWeight = weight ?: fontWeight,
        lineHeight = lineHeight?.sp ?: this.lineHeight,
        letterSpacing = letterSpacing?.sp ?: this.letterSpacing,
    )

private val base = Typography()

/** Material 3 type scale in Nunito, with a looser body line height (chat text is read, not scanned). */
val LabTypography = Typography(
    displayLarge = base.displayLarge.nunito(FontWeight.Bold),
    displayMedium = base.displayMedium.nunito(FontWeight.Bold),
    displaySmall = base.displaySmall.nunito(FontWeight.Bold),
    headlineLarge = base.headlineLarge.nunito(FontWeight.ExtraBold),
    headlineMedium = base.headlineMedium.nunito(FontWeight.ExtraBold),
    headlineSmall = base.headlineSmall.nunito(FontWeight.Bold),
    titleLarge = base.titleLarge.nunito(FontWeight.Bold),
    titleMedium = base.titleMedium.nunito(FontWeight.Bold, letterSpacing = 0.1f),
    titleSmall = base.titleSmall.nunito(FontWeight.Bold),
    bodyLarge = base.bodyLarge.nunito(FontWeight.Normal, lineHeight = 26, letterSpacing = 0.15f),
    bodyMedium = base.bodyMedium.nunito(FontWeight.Normal, lineHeight = 21, letterSpacing = 0.1f),
    bodySmall = base.bodySmall.nunito(FontWeight.Normal, lineHeight = 17),
    labelLarge = base.labelLarge.nunito(FontWeight.SemiBold),
    labelMedium = base.labelMedium.nunito(FontWeight.SemiBold),
    labelSmall = base.labelSmall.nunito(FontWeight.SemiBold),
)
