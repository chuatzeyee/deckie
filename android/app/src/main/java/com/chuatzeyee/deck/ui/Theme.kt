package com.chuatzeyee.deck.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * The exact Deck palette (see the plan's Global Constraints) - the single source of truth for
 * every color used in the app's UI. Dark-only: there is no light variant.
 */
object DeckColors {
    val Background = Color(0xFF031203)
    val Text = Color(0xFF56D156)
    val Bright = Color(0xFF8CFF8C)
    val Dim = Color(0xFF1F5A1F)
    val Attention = Color(0xFFFF9A1F)
    val RailBackground = Color(0xFF051805)
    val SelectedRow = Color(0xFF0F3A0F)
    val Divider = Color(0xFF0C2A0C)
}

// surfaceContainer*/primaryContainer/tertiary/outline are set explicitly (fix round 1, important
// #6): left unset, Material3 defaults them from its own baseline palette, not this one, so a
// dialog/field drawn with them (e.g. AlertDialog's container, which defaults to
// surfaceContainerHigh) would come out off-palette. outline = Dim (not the darker Divider) so a
// field border meets 3:1 contrast against Background.
private val deckColorScheme = darkColorScheme(
    background = DeckColors.Background,
    onBackground = DeckColors.Text,
    surface = DeckColors.RailBackground,
    onSurface = DeckColors.Text,
    surfaceVariant = DeckColors.SelectedRow,
    onSurfaceVariant = DeckColors.Text,
    surfaceContainer = DeckColors.RailBackground,
    surfaceContainerHigh = DeckColors.SelectedRow,
    primary = DeckColors.Bright,
    onPrimary = DeckColors.Background,
    primaryContainer = DeckColors.SelectedRow,
    onPrimaryContainer = DeckColors.Bright,
    secondary = DeckColors.Attention,
    onSecondary = DeckColors.Background,
    tertiary = DeckColors.Bright,
    onTertiary = DeckColors.Background,
    error = DeckColors.Attention,
    onError = DeckColors.Background,
    outline = DeckColors.Dim,
    outlineVariant = DeckColors.Divider,
)

private val deckBaseStyle = TextStyle(fontFamily = FontFamily.Monospace, color = DeckColors.Text)

private val deckTypography = Typography(
    displayLarge = deckBaseStyle,
    displayMedium = deckBaseStyle,
    displaySmall = deckBaseStyle,
    headlineLarge = deckBaseStyle,
    headlineMedium = deckBaseStyle,
    headlineSmall = deckBaseStyle,
    titleLarge = deckBaseStyle,
    titleMedium = deckBaseStyle,
    titleSmall = deckBaseStyle,
    bodyLarge = deckBaseStyle,
    bodyMedium = deckBaseStyle,
    bodySmall = deckBaseStyle,
    labelLarge = deckBaseStyle,
    labelMedium = deckBaseStyle,
    labelSmall = deckBaseStyle,
)

/** Wraps [content] in the app's dark, monospace, exact-palette [MaterialTheme]. */
@Composable
fun DeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = deckColorScheme, typography = deckTypography, content = content)
}
