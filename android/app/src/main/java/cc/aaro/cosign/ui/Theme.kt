package cc.aaro.cosign.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cc.aaro.cosign.R

// "Vault": warm black, amber wherever a key leaves this phone, cobalt (the accent) wherever one
// arrives. Amber is never used for "verified" or "safe" (§11.2b). In light mode the same
// calm: warm paper instead of warm black, the teal deeper so it reads on white.

object Vault {
    /** The phone's light or dark setting, kept by [CosignTheme]: every colour below follows it, live. */
    var dark by mutableStateOf(true)
    private fun pick(night: Long, day: Long) = Color(if (dark) night else day)

    val Ground get() = pick(0xFF13110F, 0xFFF7F3EC)
    val Surface get() = pick(0xFF1E1B18, 0xFFFFFDF9)
    val Edge get() = pick(0xFF2A2622, 0xFFE6DFD4)
    val Line get() = pick(0xFF34302B, 0xFFD6CDBF)
    val Field get() = pick(0xFF4A443C, 0xFFB3AA9B)
    val Text get() = pick(0xFFEDE6DA, 0xFF1F1B16)
    val Muted get() = pick(0xFFA69D90, 0xFF6B6257)
    val Amber get() = pick(0xFFF2B33D, 0xFFE9A21F)
    val OnAmber get() = pick(0xFF1A1406, 0xFF1A1406)
    val AmberEdge get() = pick(0xFF5A4520, 0xFFE3C27E)
    val AmberText get() = pick(0xFFE9CF97, 0xFF7A5210)
    val AmberGround get() = pick(0xFF221A0D, 0xFFFBF0D9)
    /**
     * The accent (links, buttons, topic labels, what's on): Marble's cobalt, the app icon's hue (227°).
     * Dark mode lighter so it reads: #8DA2F0 on the dark ground is 7.7:1; #2E4CB8 on paper 6.7:1.
     */
    val Accent get() = pick(0xFF8DA2F0, 0xFF2E4CB8)
    val AccentEdge get() = pick(0xFF2E3A66, 0xFFB9C5F0)
    val AccentGround get() = pick(0xFF161A2B, 0xFFE6EBFA)
    val Danger get() = pick(0xFFE5736B, 0xFFC2453C)
    /** Behind a sheet: the page dimmed. */
    val Scrim get() = Color(if (dark) 0xCC0B0A09 else 0x8C1F1B16)
}

// Kept for the few places that name the direction's color directly.
val Release get() = Vault.Amber
val Receipt get() = Vault.Accent
val Danger get() = Vault.Danger

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun sans(weight: Int) = Font(
    R.font.ibmplexsans_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val PlexSans = FontFamily(sans(400), sans(500), sans(600), sans(700))
val PlexMono = FontFamily(
    Font(R.font.ibmplexmono_regular, FontWeight.Normal),
    Font(R.font.ibmplexmono_medium, FontWeight.Medium),
)

private fun scheme() = (if (Vault.dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()).copy(
    primary = Vault.Text,
    onPrimary = Vault.Ground,
    secondary = Vault.Accent,
    background = Vault.Ground,
    surface = Vault.Ground,
    surfaceVariant = Vault.Surface,
    surfaceContainerHigh = Vault.Surface,
    onSurface = Vault.Text,
    onSurfaceVariant = Vault.Muted,
    outline = Vault.Line,
    error = Vault.Danger,
)

private val type = Typography(
    headlineMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 33.sp),
    titleLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = PlexSans, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = PlexSans, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = PlexSans, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

/** Marble's look, light or dark as the phone is set (and changing with it while open). */
@Composable
fun CosignTheme(content: @Composable () -> Unit) {
    Vault.dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme(), typography = type, content = content)
}
