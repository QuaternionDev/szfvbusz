package hu.szfvbus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import hu.szfvbus.data.ColorTheme

private val Brand = Color(0xFF0D5C91)
private val BrandLight = Color(0xFF9CCBFF)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E4FF),
    onPrimaryContainer = Color(0xFF001D35),
    secondary = Color(0xFF4F6070),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E4F5),
    onSecondaryContainer = Color(0xFF0B1D2E),
    tertiaryContainer = Color(0xFFFFE08A),
    onTertiaryContainer = Color(0xFF241A00),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFDFCFF),
    surfaceVariant = Color(0xFFE0E3EA),
    surfaceContainerLow = Color(0xFFF3F5F9),
    surfaceContainer = Color(0xFFEDF0F5),
    surfaceContainerHigh = Color(0xFFE7EAF0),
)

private val DarkColors = darkColorScheme(
    primary = BrandLight,
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD0E4FF),
    secondary = Color(0xFFB7C8DA),
    onSecondary = Color(0xFF213240),
    secondaryContainer = Color(0xFF384958),
    onSecondaryContainer = Color(0xFFD3E4F5),
    tertiaryContainer = Color(0xFF574500),
    onTertiaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF111418),
    surface = Color(0xFF111418),
    surfaceVariant = Color(0xFF42474E),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
)

// BudapestGO színvilág: a BKK arculatának sötétlilájánál (#4C0E5F, a bkk.hu stíluslapjából) egy árnyalattal világosabb.
private val BkkLightColors = lightColorScheme(
    primary = Color(0xFF5A1B6E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E1FA),
    onPrimaryContainer = Color(0xFF2E0039),
    secondary = Color(0xFF6B5870),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1DDF3),
    onSecondaryContainer = Color(0xFF251628),
    tertiaryContainer = Color(0xFFFFE08A),
    onTertiaryContainer = Color(0xFF241A00),
    background = Color(0xFFFBF8FC),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFEDE3ED),
    surfaceContainerLow = Color(0xFFF8F2F9),
    surfaceContainer = Color(0xFFF3EDF4),
    surfaceContainerHigh = Color(0xFFEEE6EF),
)

private val BkkDarkColors = darkColorScheme(
    primary = Color(0xFFE8C2F4),
    onPrimary = Color(0xFF46105A),
    primaryContainer = Color(0xFF6A2880),
    onPrimaryContainer = Color(0xFFF7D8FF),
    secondary = Color(0xFFD5C0D8),
    onSecondary = Color(0xFF3A2B3E),
    secondaryContainer = Color(0xFF524256),
    onSecondaryContainer = Color(0xFFF1DDF3),
    tertiaryContainer = Color(0xFF574500),
    onTertiaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF1B161E),
    surface = Color(0xFF1B161E),
    surfaceVariant = Color(0xFF504853),
    surfaceContainerLow = Color(0xFF231E26),
    surfaceContainer = Color(0xFF28222B),
    surfaceContainerHigh = Color(0xFF332C36),
)

private fun colorsFor(theme: ColorTheme, dark: Boolean) = when (theme) {
    ColorTheme.SZFVBUSZ -> if (dark) DarkColors else LightColors
    ColorTheme.BUDAPESTGO -> if (dark) BkkDarkColors else BkkLightColors
}

/** Egy színtéma fő színe, a választóban mintaként. */
fun ColorTheme.previewColor(dark: Boolean): Color = colorsFor(this, dark).primary

/** Állapotszínek, amelyek nincsenek benne a Material-sémában. */
@Immutable
data class TransitColors(
    val realtime: Color,
    val late: Color,
    val early: Color,
    val muted: Color,
)

val LocalTransitColors = staticCompositionLocalOf {
    TransitColors(Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFFEF6C00), Color.Gray)
}

@Composable
fun SzfvBusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorTheme: ColorTheme = ColorTheme.SZFVBUSZ,
    content: @Composable () -> Unit,
) {
    val transit = if (darkTheme) {
        TransitColors(Color(0xFF81C784), Color(0xFFFF8A80), Color(0xFFFFB74D), Color(0xFF9AA0A6))
    } else {
        TransitColors(Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFFE65100), Color(0xFF6B7178))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTransitColors provides transit) {
        MaterialTheme(colorScheme = colorsFor(colorTheme, darkTheme), content = content)
    }
}
