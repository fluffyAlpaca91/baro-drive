package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 코치 얼굴 (목업 심볼 `av-sib`·`av-mom`·`av-dad`, 설계 4.2 CO-1, 프로토타입 data/copy.js `coaches`).
 * 형제/자매 '툴툴' · 엄마 '호들갑' · 아빠/배우자 '다정'. 4번째 코치는 없다(결정-21). 이 enum은 화면에 그릴 얼굴·이름만 안다.
 * [id]는 프로토타입 코치 id('sib'·'mom'·'dad')이고, 모델 쪽 코치 값과는 [fromId]로 잇는다.
 */
enum class CoachFace(
    val id: String,
    @param:DrawableRes val avatar: Int,
    @param:StringRes val nameRes: Int,
    @param:StringRes val toneRes: Int,
) {
    Sibling("sib", R.drawable.av_sib, R.string.coach_sib, R.string.coach_sib_tone),
    Mom("mom", R.drawable.av_mom, R.string.coach_mom, R.string.coach_mom_tone),
    Dad("dad", R.drawable.av_dad, R.string.coach_dad, R.string.coach_dad_tone),
    ;

    companion object {
        /** 코치 id → 얼굴. 모르는 값이면 아빠/배우자(프로토타입 `coachNow()` 기본값). */
        fun fromId(id: String?): CoachFace = entries.firstOrNull { it.id == id } ?: Dad
    }
}

/** 코치 아바타 (목업 `.av` 64 · `.av.m` 80 · `.av.l` 96 · `.av.xl` 112, components.js `avatar()`). 원으로 잘라 그린다. */
@Composable
fun CoachAvatar(
    face: CoachFace,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.avatar,
    contentDescription: String? = stringResource(face.nameRes),
) {
    Image(
        painter = painterResource(face.avatar),
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}

/** 코치 칩 (목업 `.coach-chip`, 홈 머리줄): 높이 80 알약 안에 아바타 64 + 코치 이름. 누르면 코치 고르기(CO-1). */
@Composable
fun CoachChip(face: CoachFace, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(Dimens.coachChipHeight)
            .pressClickable(enabled = true, onClick = onClick, role = Role.Button)
            .background(BaroTheme.colors.btnGhostBg, CircleShape)
            .padding(start = Dimens.space1, end = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoachAvatar(face, contentDescription = null)
        Text(stringResource(face.nameRes), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
    }
}

/**
 * 말풍선 (목업 `.bub`, components.js `bubble()`): 앱 화면 안에 코치 문장을 보여 주는 실제 UI(설계 2.4).
 * 주행 중은 약하게(보통), 리뷰는 강하게([strong] = `.bub.strong`, 옅은 민트 + 민트 테두리). 글자 모양은 둘레를 따르고
 * [textStyle]로 바꾼다(DR-1은 32/40 굵게 한 줄 — `maxLines = 1`).
 */
@Composable
fun SpeechBubble(
    text: String,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    Box(
        modifier = modifier
            .background(if (strong) colors.brandSoft else colors.bgRaised, shape)
            .border(2.dp, if (strong) colors.brandText else colors.bubLine, shape)
            .padding(contentPadding),
    ) {
        Text(
            text = text,
            style = textStyle.copy(lineBreak = LineBreak.Heading),
            color = colors.t1,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 코치 말 (목업 `.coach-say`, components.js `bubble(text, { coach })`): 아바타(기본 96) + 말풍선. */
@Composable
fun CoachSay(
    face: CoachFace,
    text: String,
    modifier: Modifier = Modifier,
    avatarSize: Dp = Dimens.avatarL,
    strong: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoachAvatar(face, size = avatarSize)
        SpeechBubble(text = text, modifier = Modifier.weight(1f, fill = false), strong = strong, textStyle = textStyle)
    }
}

/**
 * 코치 줄 (목업 `.coach-row`, 프로토타입 coach.js `picker()`): CO-1·ON-5에서 코치 하나를 고르는 줄.
 * 아바타(112, [compact]면 96) + 고른 표시(민트 원 체크) · 이름 + 말투 태그 + [미리 듣기] · 미리보기 말풍선.
 * 줄 어디를 눌러도 고른다(라디오). [미리 듣기]는 고르지 않고 [onListen]만 부른다.
 */
@Composable
fun CoachRow(
    face: CoachFace,
    preview: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onListen: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusL)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected, interactionSource = null, indication = BaroPressIndication, role = Role.RadioButton, onClick = onSelect)
            .background(colors.bgCard, shape)
            .border(if (selected) 3.dp else 2.dp, if (selected) colors.brandText else colors.cardLine, shape)
            .padding(
                horizontal = if (compact) Dimens.space3 else 28.dp,
                vertical = if (compact) 12.dp else Dimens.space3,
            ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) Dimens.space3 else 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            CoachAvatar(face, size = if (compact) Dimens.avatarL else Dimens.avatarXL)
            if (selected) {
                // `.chk`: 44 민트 원(오른쪽·아래로 4 삐져나옴) + 카드 바탕색 고리 4.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 8.dp, y = 8.dp)
                        .size(52.dp)
                        .background(colors.bgCard, CircleShape)
                        .padding(4.dp)
                        .background(colors.brand, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    BaroIcon(R.drawable.ic_check, contentDescription = null, size = Dimens.iconS, tint = colors.onBrand)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
            Row(
                modifier = Modifier.height(Dimens.minTouch),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(face.nameRes), style = MaterialTheme.typography.titleSmall)
                BaroTag(stringResource(face.toneRes))
                Spacer(Modifier.weight(1f))
                BaroButton(
                    text = stringResource(R.string.coach_listen),
                    onClick = onListen,
                    size = BaroButtonSize.S,
                    icon = R.drawable.ic_speaker,
                )
            }
            SpeechBubble(
                text = preview,
                textStyle = MaterialTheme.typography.labelLarge.copy(lineHeight = 32.sp),
                contentPadding = if (compact) {
                    PaddingValues(horizontal = 18.dp, vertical = Dimens.space1)
                } else {
                    PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                },
            )
        }
    }
}

@BaroPreviews
@Composable
private fun CoachPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                CoachAvatar(CoachFace.Sibling)
                CoachAvatar(CoachFace.Mom, size = Dimens.avatarM)
                CoachAvatar(CoachFace.Dad, size = Dimens.avatarL)
                CoachAvatar(CoachFace.Dad, size = Dimens.avatarXL)
                CoachChip(CoachFace.Dad, onClick = {})
            }
            CoachSay(CoachFace.Mom, text = "어머, 깜빡이를 조금만 더 일찍 켜 볼까?")
            CoachSay(CoachFace.Dad, text = "오늘 정말 잘했어.", strong = true)
            CoachRow(CoachFace.Sibling, preview = "\"깜빡이 좀 일찍 켜.\"", selected = false, onSelect = {}, onListen = {})
            CoachRow(CoachFace.Dad, preview = "\"조금만 일찍 켜 보자.\"", selected = true, onSelect = {}, onListen = {})
        }
    }
}
