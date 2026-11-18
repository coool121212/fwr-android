package com.fwr.app

import androidx.compose.runtime.Composable
import com.fwr.app.ui.theme.RwfPalette as ThemePalette
import com.fwr.app.ui.theme.RwfTheme as ComposeRwfTheme

typealias RwfPalette = ThemePalette

@Composable
fun RwfTheme(
    darkTheme: Boolean = true,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    ComposeRwfTheme(
        darkTheme = darkTheme,
        amoledBlack = amoledBlack,
        content = content
    )
}
