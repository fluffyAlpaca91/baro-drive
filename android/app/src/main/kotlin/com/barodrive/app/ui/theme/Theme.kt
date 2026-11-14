package com.barodrive.app.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalBaroColors = staticCompositionLocalOf { DarkBaroColors }

private val BaroShapes = Shapes(
    small = RoundedCornerShape(Dimens.radiusS),
    medium = RoundedCornerShape(Dimens.radiusM),
    large = RoundedCornerShape(Dimens.radiusL),
    extraLarge = RoundedCornerShape(Dimens.radiusWindow),
)

/**
 * 바로운전 테마 (프로토타입 css/tokens.css `[data-theme]`, 설계 2.3). 낮·밤은 시스템을 따른다 — Pleos는 시스템이 낮·밤을 바꾸고
 * 앱이 따른다(Pleos UX 2.5). 화면 기본 글자는 `.scr`처럼 26/34 본문 글자, 기본 글자색은 `--t1`.
 * 눌림은 물결 대신 Pleos 눌림(95%로 줄고 배경이 조금 어두워짐, [BaroPressIndication])이다(Pleos UX 2.4).
 */
@Composable
fun BaroTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkBaroColors else LightBaroColors
    MaterialTheme(
        colorScheme = colors.toColorScheme(),
        typography = BaroTypography,
        shapes = BaroShapes,
    ) {
        CompositionLocalProvider(
            LocalBaroColors provides colors,
            LocalContentColor provides colors.t1,
            LocalIndication provides BaroPressIndication,
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colors.brand,
                backgroundColor = colors.brand.copy(alpha = 0.4f),
            ),
        ) {
            ProvideTextStyle(BaroBaseTextStyle, content)
        }
    }
}

/** Material 색 체계에 없는 토큰(주의·켜짐·글자 단계 …). */
object BaroTheme {
    val colors: BaroColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBaroColors.current
}
