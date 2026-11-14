package com.barodrive.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 색 토큰 (프로토타입 css/tokens.css 앱 창 변수 전부 + css/voice.css 겹침 변수, 설계 2.3).
 * 이름은 CSS 변수에서 '-'를 뺀 낙타 표기(`--bg-window` → [bgWindow]). 16진수 색은 이 파일에만 둔다.
 * 낮·밤 테마는 시스템을 따른다(Pleos UX 2.5). Material 색 체계에 없는 토큰은 [BaroTheme.colors]로 꺼낸다.
 * 프레임 밖 페이지 색(`--page-*`)·Driving view(`--dv-*`)·시스템 장식(`--sys-*`)은 앱 창이 아니라서 옮기지 않았다.
 */
@Immutable
data class BaroColors(
    /** 다크 테마인지. 그림자·대비처럼 테마마다 그리는 법이 다를 때만 쓴다. */
    val isDark: Boolean,
    /** `--bg-window` 창 바탕. */
    val bgWindow: Color,
    /** `--bg-card` 카드 바탕. */
    val bgCard: Color,
    /** `--bg-raised` 떠 있는 면(말풍선·시트·대화상자). */
    val bgRaised: Color,
    /** `--btn-ghost-bg` 보통 버튼·칩 바탕. */
    val btnGhostBg: Color,
    /** `--line` 구분선. */
    val line: Color,
    /** `--line-strong` 진한 구분선·막대 빈 칸·잠긴 테두리. */
    val lineStrong: Color,
    /** `--card-line` 카드 안쪽 테두리(다크는 없음). */
    val cardLine: Color,
    /** `--bub-line` 말풍선 안쪽 테두리(다크는 없음). */
    val bubLine: Color,
    /** `--t1` 본문 글자. */
    val t1: Color,
    /** `--t2` 보조 글자(84%). */
    val t2: Color,
    /** `--t3` 3차 글자(64%). */
    val t3: Color,
    /** `--t4` 비활성 글자(32%, 대비 미달이라 비활성에만). */
    val t4: Color,
    /** `--brand` '바로 민트' 바탕. */
    val brand: Color,
    /** `--on-brand` 민트 위 글자. */
    val onBrand: Color,
    /** `--brand-text` 민트 글자(라이트는 진한 민트). */
    val brandText: Color,
    /** `--brand-soft` 옅은 민트 바탕. */
    val brandSoft: Color,
    /** `--caution` 주의 주황 바탕·테두리. */
    val caution: Color,
    /** `--on-caution` 주황 위 글자. */
    val onCaution: Color,
    /** `--caution-text` 주황 글자. 라이트는 흰 바탕 대비 때문에 #B35600(결정사항 E T11-12). */
    val cautionText: Color,
    /** `--positive` 정보 파랑. */
    val positive: Color,
    /** `--active` 켜짐 초록. */
    val active: Color,
    /** `--danger` 위험 빨강. */
    val danger: Color,
    /** 위험 바탕 위 글자(`.btn.danger`는 `--on-brand`, 대비 때문에 어두운 글자). */
    val onDanger: Color,
    /** `--park-car` 주차 그림의 옆 차. */
    val parkCar: Color,
    /** `--ui-scrim` 시트·대화상자 뒤 어두운 판. */
    val scrim: Color,
    /** `--ui-toast-bg` 토스트 바탕. */
    val toastBg: Color,
    /** `--ui-toast-t` 토스트 글자. */
    val toastText: Color,
)

// :root(두 테마 공통)
private val Brand = Color(0xFF35D6C0) // '바로 민트'
private val Ink = Color(0xFF131417) // 민트·주황 위 글자
private val Caution = Color(0xFFFF8A00)
private val White = Color(0xFFFFFFFF)
private val Black = Color(0xFF000000)

val DarkBaroColors = BaroColors(
    isDark = true,
    bgWindow = Color(0xFF131417),
    bgCard = Color(0xFF1D1E21),
    bgRaised = Color(0xFF313236),
    btnGhostBg = Color(0xFF313236),
    line = Color(0xFF313236),
    lineStrong = Color(0xFF44464E),
    cardLine = Color.Transparent,
    bubLine = Color.Transparent,
    t1 = White,
    t2 = White.copy(alpha = 0.84f),
    t3 = White.copy(alpha = 0.64f),
    t4 = White.copy(alpha = 0.32f),
    brand = Brand,
    onBrand = Ink,
    brandText = Brand,
    brandSoft = Brand.copy(alpha = 0.14f),
    caution = Caution,
    onCaution = Ink,
    cautionText = Caution,
    positive = Color(0xFF0082FF),
    active = Color(0xFF00E074),
    danger = Color(0xFFFF4C28),
    onDanger = Ink,
    parkCar = Color(0xFF5C5F68),
    scrim = Black.copy(alpha = 0.56f),
    toastBg = Color(0xFF44464E), // = line-strong
    toastText = White, // = t1
)

val LightBaroColors = BaroColors(
    isDark = false,
    bgWindow = White,
    bgCard = Color(0xFFF7F8FA),
    bgRaised = White,
    btnGhostBg = Color(0xFFEDEEF2),
    line = Color(0xFFEDEEF2),
    lineStrong = Color(0xFFE0E1E6),
    cardLine = Color(0xFFEDEEF2),
    bubLine = Color(0xFFE0E1E6),
    t1 = Ink,
    t2 = Ink.copy(alpha = 0.84f),
    t3 = Ink.copy(alpha = 0.64f),
    t4 = Ink.copy(alpha = 0.32f),
    brand = Brand,
    onBrand = Ink,
    brandText = Color(0xFF006F62),
    brandSoft = Color(0xFF006F62).copy(alpha = 0.07f),
    caution = Caution,
    onCaution = Ink,
    cautionText = Color(0xFFB35600), // 흰 바탕 위 주황 글자는 대비가 모자라 글자용만 어둡게(결정사항 E T11-12)
    positive = Color(0xFF0064FF),
    active = Color(0xFF02C265),
    danger = Color(0xFFFE3D16),
    onDanger = Ink,
    parkCar = Color(0xFFB4B7BE),
    scrim = Ink.copy(alpha = 0.4f),
    toastBg = Ink, // = t1
    toastText = White, // = bg-window
)

/** 토큰 → Material3 색 체계. Material 컴포넌트(글자 선택 손잡이 등)도 토큰 색을 쓰게 한다. */
internal fun BaroColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = brand,
        onPrimary = onBrand,
        primaryContainer = brandSoft,
        onPrimaryContainer = brandText,
        secondaryContainer = btnGhostBg,
        onSecondaryContainer = t1,
        tertiary = caution,
        onTertiary = onCaution,
        background = bgWindow,
        onBackground = t1,
        surface = bgWindow,
        onSurface = t1,
        surfaceVariant = bgCard,
        onSurfaceVariant = t3,
        surfaceContainerLowest = bgWindow,
        surfaceContainerLow = bgCard,
        surfaceContainer = bgCard,
        surfaceContainerHigh = bgRaised,
        surfaceContainerHighest = bgRaised,
        outline = lineStrong,
        outlineVariant = line,
        scrim = scrim,
        error = danger,
        onError = onDanger,
    )
}
