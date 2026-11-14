package com.barodrive.app.feature.drive

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.feature.coach.face
import com.barodrive.app.ui.components.BaroContentColumn
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroIconButton
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.components.CoachAvatar
import com.barodrive.app.ui.components.GearChip
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.WeatherBadge
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Card
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Gear
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.WeatherBadge as Badge

/** 주행 덮개 안 화면(BaroRoot의 덮개가 그린다). 어느 화면(DR-0·1·2)인지는 세션 상태로 고른다. */
@Composable
fun DriveRoute(viewModel: DriveViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    val on = stringResource(R.string.drive_mute_on_toast)
    val off = stringResource(R.string.drive_mute_off_toast)
    DriveScreen(state = state, onMute = { viewModel.toggleMute { muted -> toast.show(if (muted) on else off, if (muted) R.drawable.ic_mute else R.drawable.ic_speaker) } })
}

/**
 * 주행·신호 대기 화면 (drive.js DR-0·DR-1·DR-2, 목업 s04, 설계 2.5): 1~2초 안에 읽히게 큰 히어로 하나 + 아래 코치 말풍선 한 줄.
 * 목록·스크롤·입력·움직이는 그림 없음. 터치는 소리 끄기 하나(DR-0은 없음).
 */
@Composable
fun DriveScreen(state: DriveUiState, onMute: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(BaroTheme.colors.bgWindow)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        BaroContentColumn {
            Column(Modifier.fillMaxSize()) {
                DriveHeader(state, onMute)
                if (state.screen == DriveNotPracticing) {
                    NotPracticing(Modifier.weight(1f))
                } else {
                    val wait = state.cards.isNotEmpty()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.edge),
                        verticalArrangement = Arrangement.spacedBy(if (wait) Dimens.space3 else Dimens.space5, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        state.badge?.let { WeatherBadge(it.label, icon = badgeIcon(it)) }
                        if (wait) WaitCards(state.cards, state.coach) else Hero(state.hero, state.mockupHero)
                    }
                    CoachLine(state.say, state.coach)
                }
            }
        }
    }
}

/** 머리줄(drive.js `driveHead`): 기어 칩 · '1단계 · 2회차' · 소리 끄기. */
@Composable
private fun DriveHeader(state: DriveUiState, onMute: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.appBarHeight)
            .padding(start = Dimens.edge, end = Dimens.edge, top = Dimens.appBarTopPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = gearText(state.gearLabel)
        GearChip(state.gear, label = label, mint = state.gear == Gear.D && state.gearLabel == DriveGearLabel.DRIVING)
        Text(
            state.sub,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = BaroTheme.colors.t2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.screen != DriveNotPracticing) {
            BaroIconButton(
                icon = if (state.muted) R.drawable.ic_mute else R.drawable.ic_speaker,
                contentDescription = stringResource(if (state.muted) R.string.drive_mute_on_desc else R.string.drive_mute_off_desc),
                onClick = onMute,
                selected = state.muted,
            )
        }
    }
}

@Composable
private fun gearText(l: DriveGearLabel): String = stringResource(
    when (l) {
        DriveGearLabel.PARKED -> R.string.gear_label_p
        DriveGearLabel.DRIVING -> R.string.gear_label_d_moving
        DriveGearLabel.STOPPED -> R.string.gear_label_d_stopped
        DriveGearLabel.REVERSE -> R.string.gear_label_r
        DriveGearLabel.NEUTRAL -> R.string.gear_label_n
    },
)

private fun badgeIcon(b: Badge): Int = when (b) {
    Badge.SNOW -> R.drawable.ic_snow
    Badge.SEVERE -> R.drawable.ic_hazard
    Badge.RAIN -> R.drawable.ic_rain
}

/** 히어로 하나(drive.js `buildHero`). */
@Composable
private fun Hero(hero: DriveHero, mockup: Boolean = false) {
    when (hero) {
        DriveHero.Reverse -> ReverseHero()
        is DriveHero.Turn -> if (mockup) OrigTurnHero(hero) else BlinkHero(hero)
        is DriveHero.Said -> SaidHero(hero)
        is DriveHero.Task -> TaskHero(hero)
    }
}

/** 깜빡이 중심 히어로(결정-92 B, 기본): 왼·오 화살표 칸, 켜야 할 쪽 테두리 강조 · 꺼짐 주황 테두리 · 맞게 켬 초록 채움 · 반대로 켬 주황. */
@Composable
private fun BlinkHero(h: DriveHero.Turn) {
    val colors = BaroTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space6)) {
            listOf(TurnDir.LEFT, TurnDir.RIGHT).forEach { side ->
                val need = side == h.dir
                val shape = RoundedCornerShape(56.dp)
                val (bg, border, tint) = when {
                    need && h.state == BlinkState.ON -> Triple(colors.active, null, colors.onCaution)
                    need -> Triple(colors.bgCard, 8.dp to colors.caution, colors.t1)
                    h.state == BlinkState.WRONG -> Triple(colors.caution, null, colors.onCaution)
                    else -> Triple(colors.bgCard, 2.dp to colors.cardLine, colors.t4)
                }
                Box(
                    Modifier
                        .size(208.dp)
                        .background(bg, shape)
                        .then(if (border != null) Modifier.border(border.first, border.second, shape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    BaroIcon(
                        if (side == TurnDir.LEFT) R.drawable.ic_blink_l else R.drawable.ic_blink_r,
                        contentDescription = null,
                        size = 136.dp,
                        tint = tint,
                    )
                }
            }
        }
        val labelStyle = MaterialTheme.typography.titleLarge
        if (h.state == BlinkState.WRONG) {
            Text(
                stringResource(R.string.drive_blink_wrong),
                modifier = Modifier
                    .background(colors.caution, CircleShape)
                    .padding(horizontal = 28.dp, vertical = 4.dp),
                style = labelStyle,
                color = colors.onCaution,
            )
        } else {
            val on = h.state == BlinkState.ON
            val res = when {
                h.dir == TurnDir.LEFT && on -> R.string.drive_blink_left_on
                h.dir == TurnDir.LEFT -> R.string.drive_blink_left
                on -> R.string.drive_blink_right_on
                else -> R.string.drive_blink_right
            }
            Text(stringResource(res), style = labelStyle)
        }
        val sub = when {
            h.ic && h.enter -> stringResource(R.string.drive_ic_enter_to, h.dist)
            h.ic -> stringResource(R.string.drive_ic_exit_to, h.dist)
            else -> stringResource(R.string.drive_turn_to, h.dist)
        }
        Text(sub, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = colors.t2)
    }
}

/**
 * 목업 원안 회전 히어로(목업 s04, drive.js `paintOrig` — 시연 패널 '보기' [목업 원안 보기]): 회전 화살표 타일 + '다음 회전' 거리 +
 * 깜빡이 띠(맞게 켬 초록 · 반대로 켬 주황 · 꺼짐 주황 띠).
 */
@Composable
private fun OrigTurnHero(h: DriveHero.Turn) {
    val colors = BaroTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            Box(Modifier.size(200.dp).background(colors.bgCard, RoundedCornerShape(40.dp)), contentAlignment = Alignment.Center) {
                BaroIcon(
                    R.drawable.ic_turn_r,
                    contentDescription = null,
                    size = 136.dp,
                    tint = colors.t1,
                    modifier = if (h.dir == TurnDir.LEFT) Modifier.graphicsLayer(scaleX = -1f) else Modifier,
                )
            }
            Column {
                Text(stringResource(R.string.drive_orig_next), style = MaterialTheme.typography.bodyMedium, color = colors.t2)
                Text(stringResource(R.string.drive_orig_dist, h.dist), style = MaterialTheme.typography.displayLarge, color = colors.t1)
            }
        }
        val on = h.state == BlinkState.ON
        val litSide = when (h.state) {
            BlinkState.ON -> h.dir
            BlinkState.WRONG -> h.lit
            BlinkState.OFF -> null
        }
        Row(
            Modifier
                .background(if (on) colors.active else colors.caution, CircleShape)
                .padding(horizontal = 32.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        ) {
            listOf(TurnDir.LEFT, TurnDir.RIGHT).forEachIndexed { i, side ->
                if (i == 1) {
                    Text(
                        stringResource(if (on) R.string.drive_orig_on else if (h.state == BlinkState.WRONG) R.string.drive_orig_wrong else R.string.drive_orig_off),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onCaution,
                    )
                }
                BaroIcon(
                    if (side == TurnDir.LEFT) R.drawable.ic_blink_l else R.drawable.ic_blink_r,
                    contentDescription = null,
                    size = 56.dp,
                    tint = if (litSide == side) colors.onCaution else colors.onCaution.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
private fun ReverseHero() {
    val colors = BaroTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(
            Modifier
                .size(248.dp)
                .background(colors.bgCard, RoundedCornerShape(56.dp))
                .border(2.dp, colors.cardLine, RoundedCornerShape(56.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("R", style = MaterialTheme.typography.displayLarge.copy(fontSize = 144.sp, lineHeight = 144.sp))
        }
        Text(stringResource(R.string.drive_reverse), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SaidHero(h: DriveHero.Said) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        BaroIcon(SymbolIcons.of(h.icon, R.drawable.ic_voice), contentDescription = null, size = 160.dp, tint = BaroTheme.colors.brandText)
        Text(h.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TaskHero(h: DriveHero.Task) {
    val colors = BaroTheme.colors
    Column(
        modifier = Modifier.widthIn(max = 680.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
    ) {
        BaroIcon(R.drawable.ic_flag, contentDescription = null, size = 112.dp, tint = colors.brandText, modifier = Modifier.padding(bottom = Dimens.space1))
        if (h.label.isNotEmpty()) {
            Text(h.label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = colors.brandText)
        }
        Text(
            h.text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, lineBreak = LineBreak.Heading),
            textAlign = TextAlign.Center,
        )
    }
}

/** 신호 대기 카드(DR-2, 설계 2.4: 아이콘 64 + 제목 36/44 + 수치 28/36 + 코치 문장). 분류별 테두리 색. */
@Composable
private fun WaitCards(cards: List<Card>, coach: Coach) {
    val colors = BaroTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        cards.forEach { c ->
            val tone = when (c.category) {
                CardCategory.SAFETY -> colors.danger
                CardCategory.PRAISE -> colors.brandText
                CardCategory.TIP -> colors.positive
                CardCategory.FIX -> colors.caution
            }
            val shape = RoundedCornerShape(Dimens.radiusL)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.bgCard, shape)
                    .border(3.dp, tone, shape)
                    .padding(horizontal = Dimens.cardPaddingH, vertical = Dimens.cardPaddingV),
                verticalArrangement = Arrangement.spacedBy(Dimens.space3),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(96.dp).background(colors.btnGhostBg, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                        BaroIcon(SymbolIcons.of(c.icon), contentDescription = null, size = 64.dp, tint = tone)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                        c.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.t2) }
                    }
                }
                c.text?.takeIf { it.isNotEmpty() }?.let { text ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                        CoachAvatar(coach.face())
                        Bubble(text, MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), Modifier.weight(1f), oneLine = false)
                    }
                }
            }
        }
    }
}

/** 아래 코치 말풍선(마지막 코치 문장 8 sim초). 없을 때도 자리는 그대로(가운데 히어로가 위아래로 움직이지 않게). */
@Composable
private fun CoachLine(text: String?, coach: Coach) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Dimens.edge, end = Dimens.edge, bottom = 56.dp)
            .alpha(if (text == null) 0f else 1f),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoachAvatar(coach.face(), size = Dimens.avatarL)
        Bubble(text ?: " ", MaterialTheme.typography.titleSmall, Modifier.weight(1f), oneLine = true, padding = 28.dp)
    }
}

@Composable
private fun Bubble(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier, oneLine: Boolean, padding: androidx.compose.ui.unit.Dp = 22.dp) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    Text(
        text,
        modifier = modifier
            .background(colors.bgRaised, shape)
            .border(2.dp, colors.bubLine, shape)
            .padding(horizontal = padding, vertical = if (oneLine) padding else 14.dp),
        style = style.copy(fontWeight = FontWeight.Bold),
        color = colors.t1,
        maxLines = if (oneLine) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
}

/** DR-0: 세션 없이 움직임. 회색 로고 + 두 줄. 말·기록·소리 끄기 없음. */
@Composable
private fun NotPracticing(modifier: Modifier) {
    val colors = BaroTheme.colors
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.space3, Alignment.CenterVertically),
    ) {
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = null,
            modifier = Modifier.size(160.dp),
            alpha = 0.5f,
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
        )
        Text(stringResource(R.string.drive_not_practicing), style = MaterialTheme.typography.titleLarge, color = colors.t2)
        Text(stringResource(R.string.drive_not_practicing_hint), style = MaterialTheme.typography.bodyMedium, color = colors.t3)
    }
}

// ================================================================ 미리보기

private val turnState = DriveUiState(
    screen = DriveOneLine,
    gear = Gear.D,
    gearLabel = DriveGearLabel.DRIVING,
    sub = "1단계 · 2회차",
    badge = Badge.RAIN,
    hero = DriveHero.Turn(TurnDir.RIGHT, 70, BlinkState.OFF, null, ic = false, enter = false),
    say = "빗길이야, 브레이크 일찍 나눠서 밟자.",
)

@BaroScreenPreviews
@Composable
private fun DriveTurnPreview() {
    BaroTheme { DriveScreen(turnState, onMute = {}) }
}

@BaroScreenPreviews
@Composable
private fun DriveTurnMockupPreview() {
    BaroTheme { DriveScreen(turnState.copy(mockupHero = true), onMute = {}) }
}

@BaroScreenPreviews
@Composable
private fun DriveTaskPreview() {
    BaroTheme {
        DriveScreen(turnState.copy(badge = null, muted = true, hero = DriveHero.Task("오늘 과제", "엑셀 살살 밟아서 출발하기"), say = null), onMute = {})
    }
}

@BaroScreenPreviews
@Composable
private fun DriveWaitPreview() {
    BaroTheme {
        DriveScreen(
            turnState.copy(
                screen = DriveWaitCard,
                gearLabel = DriveGearLabel.STOPPED,
                badge = null,
                cards = listOf(
                    Card(category = CardCategory.FIX, title = "브레이크를 세게 밟았어요", detail = "28km/h에서 3초 만에 정지", icon = "i-brake", text = "다음엔 미리 살살 밟아 보자."),
                    Card(category = CardCategory.TIP, title = "엑셀을 아주 살살 밟아 출발해요.", icon = "i-snow"),
                ),
                say = null,
            ),
            onMute = {},
        )
    }
}

@BaroScreenPreviews
@Composable
private fun DriveNotPracticingPreview() {
    BaroTheme { DriveScreen(DriveUiState(gear = Gear.D, gearLabel = DriveGearLabel.DRIVING), onMute = {}) }
}

@BaroWideScreenPreviews
@Composable
private fun DriveTurnWidePreview() {
    BaroTheme { DriveScreen(turnState.copy(hero = DriveHero.Turn(TurnDir.LEFT, 120, BlinkState.WRONG, TurnDir.RIGHT, ic = false, enter = false)), onMute = {}) }
}
