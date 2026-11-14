package com.barodrive.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// 아래 두 값은 BaroTypography보다 먼저 선언한다(파일 안 최상위 값은 선언 순서대로 초기화된다).
private val KoreanLineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

/** CSS `line-height`처럼 줄 높이 안에서 글자를 가운데에 두고 첫 줄 위·끝 줄 아래를 깎지 않는다(목업 높이 그대로). */
private val CssLineHeight = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

/**
 * 글자 크기·줄 높이·굵기 (프로토타입 tokens.css `--fs-*`/`--lh-*` + components.css 글자 클래스, 설계 2.3, Pleos UX 2.3).
 * 목업의 px = 840dp 창의 dp(= sp, 글자 배율 1). 20 아래는 쓰지 않는다. 주행 화면의 핵심 글자는 36 이상.
 *
 * | 목업 클래스 | Material 이름                         | 크기/줄 높이 | 굵기 |
 * |---|---|---|---|
 * | `.hl`       | headlineLarge (= displayLarge)       | 60/80 | 800 |
 * | `--fs-hm`   | headlineMedium (= displayMedium)     | 56/72 | 800 |
 * | `.hs`       | headlineSmall (= displaySmall)       | 48/64 | 800 |
 * | `.tl`       | titleLarge                           | 40/52 | 800 |
 * | `.tm`       | titleMedium                          | 36/44 | 700 |
 * | `.ts`       | titleSmall                           | 32/40 | 700 |
 * | `.bl`       | bodyLarge                            | 30/38 | 400 |
 * | `.bm`       | bodyMedium                           | 28/36 | 400 |
 * | `.bs`       | bodySmall (화면 기본 글자, `.scr`)     | 26/34 | 400 |
 * | `.lm`       | labelLarge                           | 24/28 | 400 |
 * | `.ls`       | labelMedium (= labelSmall)           | 20/24 | 400 |
 *
 * 목업의 `.b`(굵게)는 `.copy(fontWeight = FontWeight.Bold)`로 쓴다. 자간은 `.scr`처럼 −0.2.
 * 한국어 줄바꿈은 낱말 단위(`word-break: keep-all`)에 가깝게 [LineBreak.Paragraph]의 구 단위 끊기를 쓴다.
 * TODO: 글꼴 Asta Sans·Pretendard(설계 2.3). 지금은 시스템 글꼴(차량 기본 한글 글꼴).
 */
val BaroTypography = Typography(
    displayLarge = style(60, 80, FontWeight.ExtraBold),
    displayMedium = style(56, 72, FontWeight.ExtraBold),
    displaySmall = style(48, 64, FontWeight.ExtraBold),
    headlineLarge = style(60, 80, FontWeight.ExtraBold),
    headlineMedium = style(56, 72, FontWeight.ExtraBold),
    headlineSmall = style(48, 64, FontWeight.ExtraBold),
    titleLarge = style(40, 52, FontWeight.ExtraBold),
    titleMedium = style(36, 44, FontWeight.Bold),
    titleSmall = style(32, 40, FontWeight.Bold),
    bodyLarge = style(30, 38),
    bodyMedium = style(28, 36),
    bodySmall = style(26, 34),
    labelLarge = style(24, 28),
    labelMedium = style(20, 24),
    labelSmall = style(20, 24),
)

/** 화면 기본 글자(`.scr`: 26/34, 자간 −0.2). [BaroTheme]이 기본 글자 모양으로 깐다. */
internal val BaroBaseTextStyle: TextStyle = BaroTypography.bodySmall

private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = (-0.2).sp,
    lineBreak = KoreanLineBreak,
    lineHeightStyle = CssLineHeight,
)
