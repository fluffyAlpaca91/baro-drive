package com.barodrive.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 간격·모서리·터치·컴포넌트 크기 (프로토타입 tokens.css `--sp-*`·`--r-*`·`--btn-*`, components.css·voice.css 값, 설계 2.3·2.4)
 * + Pleos 크기(Pleos UX 2.4: 버튼 높이 100/80/64, 아이콘 버튼 80/64, 토스트 792, 터치 64·간격 24).
 * 기준 창은 Connect-S 세로 840×1184dp(Pleos UX 0장). 목업의 px = dp.
 */
object Dimens {
    // ---- 간격 (--sp-1 ~ --sp-6)
    val space1 = 8.dp
    val space2 = 16.dp
    val space3 = 24.dp
    val space4 = 32.dp
    val space5 = 40.dp
    val space6 = 48.dp

    /** `--edge` 창 가장자리 여백. */
    val edge = 40.dp

    // ---- 모서리 (--r-s · --r-m · --r-l · --r-win, 알약은 CircleShape)
    val radiusS = 16.dp
    val radiusM = 24.dp
    val radiusL = 32.dp
    val radiusWindow = 40.dp

    // ---- 터치·버튼 (--btn-l · --btn-m · --btn-s · --touch-gap)
    /** Pleos 버튼 높이 Large·Medium·Small. */
    val buttonL = 100.dp
    val buttonM = 80.dp
    val buttonS = 64.dp

    /** 가장 작은 터치 대상(Pleos·AAOS 64dp)과 터치 대상 사이 간격(24dp 이상). */
    val minTouch = 64.dp
    val touchGap = 24.dp

    /** 아이콘 버튼 `.ibtn` 80×80(모서리 20) · 작은 아이콘 버튼 64×64(모서리 16). */
    val iconButton = 80.dp
    val iconButtonRadius = 20.dp
    val iconButtonSmall = 64.dp

    // ---- 아이콘 (.ic 40 · .ic.s 32 · .ic.l 48)
    val icon = 40.dp
    val iconS = 32.dp
    val iconL = 48.dp

    /** 코치 아바타 `.av` 64 · `.av.m` 80 · `.av.l` 96 · `.av.xl` 112. */
    val avatar = 64.dp
    val avatarM = 80.dp
    val avatarL = 96.dp
    val avatarXL = 112.dp

    // ---- 화면 틀
    /** 앱 바 `.ab` 높이 112(위 여백 32 + 내용 80). */
    val appBarHeight = 112.dp
    val appBarTopPadding = 32.dp

    /** 카드 `.card` 안쪽 여백 위아래 28 · 좌우 32. */
    val cardPaddingV = 28.dp
    val cardPaddingH = 32.dp

    /** 칩 `.chip` 64 · 태그 `.tag` 40 · 기어 칩 `.gear` 56 · 코치 칩 `.coach-chip` 80. */
    val chipHeight = 64.dp
    val tagHeight = 40.dp
    val gearChipHeight = 56.dp
    val coachChipHeight = 80.dp

    /** 목록 줄 `.row` 최소 높이 88. */
    val listRowMinHeight = 88.dp

    /** 모드 카드 `.mode` 높이 112. */
    val modeCardHeight = 112.dp

    /** 글자 입력 `.field` 높이 80. */
    val fieldHeight = 80.dp

    /** 진행 막대·단계 막대 높이 14. */
    val barHeight = 14.dp

    /** 스위치 96×56, 손잡이 40. */
    val switchWidth = 96.dp
    val switchHeight = 56.dp
    val switchKnob = 40.dp

    // ---- 겹침 (voice.css)
    /** 토스트: 창 안쪽 아래·좌우 24, 최대 폭 792, 최소 높이 88, 2.2초(Pleos UX 2.6). */
    val toastInset = 24.dp
    val toastMaxWidth = 792.dp
    val toastMinHeight = 88.dp

    /** 시트 위쪽 비움(창 높이 − 112까지)·대화상자 폭 760·여백 48·모서리 48(Pleos 팝업 Medium). */
    val sheetTopGap = 112.dp
    val dialogWidth = 760.dp
    val dialogPadding = 48.dp
    val dialogRadius = 48.dp

    /** 기준 앱 창(Connect-S 세로). 미리보기 크기에 쓴다. */
    const val WINDOW_WIDTH_DP = 840
    const val WINDOW_HEIGHT_DP = 1184

    /** 넓은 앱 창(Pleos 에뮬레이터 가로 창). 미리보기 크기에 쓴다. */
    const val WIDE_WIDTH_DP = 1682
    const val WIDE_HEIGHT_DP = 1210
}
