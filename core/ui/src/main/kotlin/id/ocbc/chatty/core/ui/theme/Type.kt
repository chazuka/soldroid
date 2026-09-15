package id.ocbc.chatty.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import id.ocbc.chatty.core.ui.R

/**
 * Public Sans, the design canvas's typeface, as a single variable font.
 *
 * One `.ttf` carries every weight from Thin to Black, so the app ships one 100 kB file instead of
 * five static cuts — and any weight the design reaches for later is already present. Variable
 * settings need API 26; this app's floor is 29.
 */
@OptIn(ExperimentalTextApi::class)
private val PublicSans = FontFamily(
    listOf(
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
        FontWeight.SemiBold to 600,
        FontWeight.Bold to 700,
        FontWeight.ExtraBold to 800,
    ).map { (weight, axis) ->
        Font(
            resId = R.font.public_sans_variable,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(axis)),
        )
    },
)

/**
 * The type scale, with the sizes the design actually specifies rather than Material's defaults.
 *
 * The canvas is explicit about a handful of roles and silent about the rest, so the named ones are
 * matched exactly and the others are left to Material with the family swapped:
 *
 * | Role | Design | Used for |
 * |---|---|---|
 * | `headlineSmall` | 22sp / 1.35 / 600 | the spoken question, quoted large in voice mode |
 * | `bodyLarge` | 15sp / 1.45 | chat bubbles, the composer |
 * | `titleMedium` | 16sp / 700 | the assistant's name in the header |
 * | `labelLarge` | 14sp / 600 | shortcut chips |
 * | `labelMedium` | 12.5sp / 700 | the masked-balance pill |
 * | `labelSmall` | 12sp | "Secure session", timestamps, footnotes |
 */
internal val ChattyTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = PublicSans),
        displayMedium = displayMedium.copy(fontFamily = PublicSans),
        displaySmall = displaySmall.copy(fontFamily = PublicSans),
        headlineLarge = headlineLarge.copy(fontFamily = PublicSans),
        headlineMedium = headlineMedium.copy(fontFamily = PublicSans),
        headlineSmall = TextStyle(
            fontFamily = PublicSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 30.sp,
        ),
        titleLarge = titleLarge.copy(fontFamily = PublicSans),
        titleMedium = TextStyle(
            fontFamily = PublicSans,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        titleSmall = titleSmall.copy(fontFamily = PublicSans),
        bodyLarge = TextStyle(
            fontFamily = PublicSans,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        ),
        bodyMedium = bodyMedium.copy(fontFamily = PublicSans),
        bodySmall = bodySmall.copy(fontFamily = PublicSans),
        labelLarge = TextStyle(
            fontFamily = PublicSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = PublicSans,
            fontWeight = FontWeight.Bold,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = PublicSans,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
    )
}
