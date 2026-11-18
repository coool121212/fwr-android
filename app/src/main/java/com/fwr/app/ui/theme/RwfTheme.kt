package com.fwr.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Dynamic RWF Light / Dark / AMOLED Black palette tokens.
 * Reading these properties inside Compose scopes subscribes composables to theme updates.
 */
object RwfPalette {
    var isDark by mutableStateOf(true)
    var isAmoled by mutableStateOf(false)

    // AppBar, Toolbar, TabLayout & Surface backgrounds
    val PrimaryDarkTheme: Color
        get() = if (!isDark) Color(0xFFCDE3F9) else if (isAmoled) Color.Black else Color(0xFF212121)

    val PrimaryDarkStatus: Color
        get() = if (!isDark) Color(0xFF9ABCE0) else Color.Black

    val BackgroundMaterialDark: Color
        get() = if (!isDark) Color(0xFFFAFAFA) else if (isAmoled) Color.Black else Color(0xFF303030)

    val SelectedPostRowDark: Color
        get() = if (!isDark) Color(0xFFD6E8FA) else Color(0xFF232A35)

    val ActionStripTextDark: Color
        get() = if (!isDark) Color(0xFF1A1A1A) else Color(0xFFDDDDDD)

    val DarkGraySurface: Color
        get() = if (!isDark) Color(0xFFEEEEEE) else if (isAmoled) Color(0xFF0A0A0A) else Color(0xFF1F1F1F)

    val CardBackgroundDark: Color
        get() = if (!isDark) Color.White else if (isAmoled) Color(0xFF121212) else Color(0xFF383838)

    val CardActionsBackgroundDark: Color
        get() = if (!isDark) Color(0xFFE8EEF5) else Color(0xFF484848)

    // Active tab underline indicator & accents
    val AccentDarkTheme: Color
        get() = if (!isDark) Color(0xFF2B6CB0) else Color(0xFFAADFF1)

    val CyanLinkDark: Color
        get() = if (!isDark) Color(0xFF006699) else Color(0xFF66CCDD)

    val DeepTeal200 = Color(0xFF80CBC4)

    // Tab text colors
    val TabTextSelectedDark: Color
        get() = if (!isDark) Color(0xFF0D1B2A) else Color.White

    val TabTextUnselectedDark: Color
        get() = if (!isDark) Color(0x990D1B2A) else Color(0xB3FFFFFF)

    // Post title text colors (read vs unread vs sticky)
    val TitleUnreadDark: Color
        get() = if (!isDark) Color(0xFF111111) else Color.White

    val TitleReadDark: Color
        get() = if (!isDark) Color(0xFF551A8B) else Color(0xFF8296D8)

    val StickyTitleDark: Color
        get() = if (!isDark) Color(0xFF2E7D32) else Color(0xFF99CC00)

    // Metadata, domain & flair colors
    val MetadataTextDark: Color
        get() = if (!isDark) Color(0xFF4A4A4A) else Color(0xB3FFFFFF)

    val DomainTextDark: Color
        get() = if (!isDark) Color(0xFF666666) else Color(0xFFABABAB)

    val CardDomainTextDark: Color
        get() = if (!isDark) Color(0xFF666666) else Color(0xFF707070)

    val FlairBackgroundDark: Color
        get() = if (!isDark) Color(0xFFE0E0E0) else Color(0xFF3F3F3F)

    val FlairTextDark: Color
        get() = if (!isDark) Color(0xFF333333) else Color(0xFFBFBFBF)

    val SubredditBadgeDark: Color
        get() = if (!isDark) Color(0xFFE8EEF5) else Color(0xFF484848)

    // Special status badges
    val NsfwRed = Color(0xFFFF0000)
    val SpoilerYellow: Color
        get() = if (!isDark) Color(0xFFB26A00) else Color(0xFFFFFF00)
    val DistinguishedModGreen = Color(0xFF228822)
    val DistinguishedAdminRed = Color(0xFFFF0011)
    val GildedGold = Color(0xFF99895F)

    // Score & voting arrows
    val VoteArrowNeutralGray: Color
        get() = if (!isDark) Color(0xFF757575) else Color(0xFFC0C0C0)
    val VoteArrowUpvoteRed = Color(0xFFFF8B60)
    val VoteArrowDownvoteBlue = Color(0xFF9494FF)
    val OrangeHighlight = Color(0xFFF88017)

    // Item separator divider lines
    val DividerLineDark: Color
        get() = if (!isDark) Color(0xFFDCDCDC) else Color(0xFF3F3F3F)
    val PaneDividerGray50 = Color(0x7F7F7F7F)

    // Explicit Light theme reference tokens
    val PrimaryLightTheme = Color(0xFFCDE3F9)
    val AccentLightTheme = Color(0xFF669999)
    val BackgroundMaterialLight = Color(0xFFFAFAFA)
    val TitleUnreadLight = Color(0xFF0000FF)
    val TitleReadLight = Color(0xFF551A8B)
}

@Composable
fun RwfTheme(
    darkTheme: Boolean = true,
    amoledBlack: Boolean = false,
    uiScalePercent: Int = 100,
    content: @Composable () -> Unit
) {
    RwfPalette.isDark = darkTheme
    RwfPalette.isAmoled = darkTheme && amoledBlack
    val baseDensity = LocalDensity.current
    val scaleFactor = (uiScalePercent / 100f).coerceIn(0.75f, 1.6f)
    val scaledDensity = Density(
        density = baseDensity.density * scaleFactor,
        fontScale = baseDensity.fontScale
    )

    val colorScheme = if (darkTheme) {
        val bg = if (amoledBlack) Color.Black else Color(0xFF303030)
        val surface = if (amoledBlack) Color.Black else Color(0xFF212121)
        val card = if (amoledBlack) Color(0xFF121212) else Color(0xFF383838)
        darkColorScheme(
            primary = Color(0xFFAADFF1),
            onPrimary = Color.Black,
            primaryContainer = surface,
            onPrimaryContainer = Color.White,
            secondary = Color(0xFF66CCDD),
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF3F3F3F),
            onSecondaryContainer = Color(0xFFBFBFBF),
            tertiary = Color(0xFFFF8B60),
            background = bg,
            onBackground = Color.White,
            surface = bg,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF3F3F3F),
            onSurfaceVariant = Color(0xB3FFFFFF),
            surfaceContainer = surface,
            surfaceContainerLow = card,
            outline = Color(0x7F7F7F7F),
            outlineVariant = Color(0xFF3F3F3F),
            error = Color(0xFFFF0000)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2B6CB0),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFCDE3F9),
            onPrimaryContainer = Color(0xFF0D1B2A),
            secondary = Color(0xFF006699),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE0E0E0),
            onSecondaryContainer = Color(0xFF333333),
            tertiary = Color(0xFFFF8B60),
            background = Color(0xFFFAFAFA),
            onBackground = Color(0xFF111111),
            surface = Color.White,
            onSurface = Color(0xFF111111),
            surfaceVariant = Color(0xFFEEEEEE),
            onSurfaceVariant = Color(0xFF4A4A4A),
            surfaceContainer = Color(0xFFCDE3F9),
            surfaceContainerLow = Color.White,
            outline = Color(0x7F7F7F7F),
            outlineVariant = Color(0xFFDCDCDC),
            error = Color(0xFFB00020)
        )
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
