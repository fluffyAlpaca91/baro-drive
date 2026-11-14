package com.barodrive.app.feature.coach

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.components.BaroChip
import com.barodrive.app.ui.components.BaroTextField
import com.barodrive.app.ui.components.CoachAvatar
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.components.SpeechBubble
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType

/**
 * 호칭 고르기 (프로토타입 coach.js `honorificEditor`, coach.css `.hon-ed`): 칩 4개 + 직접 쓰기 칸(8자) + 미리보기(아바타 64 + 말풍선)
 * + 그 코치가 언제 부르는지 한 줄. CO-2 시트와 ON-5 화면 안에서 같이 쓴다. 직접 쓰기를 고르면 입력 칸에 포커스를 준다.
 *
 * @param showPreviewLabel '미리보기' 글(ON-5는 숨김 — onboarding.css `.hon-pv-l{display:none}`).
 * @param onSheet 시트 안(시트 바탕이 칩·말풍선 바탕과 같아 말풍선 바탕을 카드색으로, coach.css `.co2-sheet`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HonorificEditor(
    coach: Coach,
    draft: HonorificDraft,
    previewText: String,
    onPick: (HonorificType) -> Unit,
    onCustomText: (String) -> Unit,
    modifier: Modifier = Modifier,
    showPreviewLabel: Boolean = true,
    onSheet: Boolean = false,
) {
    val colors = BaroTheme.colors
    val focus = remember { FocusRequester() }
    var focusCustom by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap),
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            Honorifics.ORDER.forEach { t ->
                BaroChip(
                    text = t.label,
                    selected = draft.type == t,
                    onClick = {
                        if (draft.type != t) {
                            onPick(t)
                            focusCustom = t == HonorificType.CUSTOM
                        }
                    },
                )
            }
        }
        if (draft.type == HonorificType.CUSTOM) {
            BaroTextField(
                value = draft.customText,
                onValueChange = onCustomText,
                modifier = Modifier.focusRequester(focus),
                placeholder = stringResource(R.string.hon_custom_placeholder),
                maxLength = Honorific.MAX_LENGTH,
                label = stringResource(R.string.hon_custom_label),
            )
            LaunchedEffect(focusCustom) {
                if (focusCustom) {
                    focus.requestFocus()
                    focusCustom = false
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space1)) {
            if (showPreviewLabel) {
                Text(stringResource(R.string.hon_preview), style = MaterialTheme.typography.labelLarge, color = colors.t3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                CoachAvatar(coach.face(), size = Dimens.avatar)
                if (onSheet) {
                    // 시트 바탕(--bg-raised)이 말풍선 바탕과 같아 카드색으로(coach.css `.co2-sheet .hon-pv .bub`).
                    Text(
                        text = previewText,
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.bgCard, RoundedCornerShape(Dimens.radiusM))
                            .border(2.dp, colors.bubLine, RoundedCornerShape(Dimens.radiusM))
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, lineBreak = LineBreak.Heading),
                        color = colors.t1,
                    )
                } else {
                    SpeechBubble(
                        text = previewText,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                    )
                }
            }
        }
        NoteRow(honorNote(coach))
    }
}

/** 그 코치가 언제 부르는지 한 줄(coach.js `honorNote`, 설계 6.6.1). */
@Composable
fun honorNote(coach: Coach): String = stringResource(
    when (coach) {
        Coach.SIB -> R.string.coach_hon_note_sib
        Coach.MOM -> R.string.coach_hon_note_mom
        Coach.DAD -> R.string.coach_hon_note_dad
    },
)

@BaroPreviews
@Composable
private fun HonorificEditorPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            HonorificEditor(
                coach = Coach.MOM,
                draft = HonorificDraft(HonorificType.CUSTOM, "지은아"),
                previewText = "어머 잘했다 지은아! 딱 좋았어!",
                onPick = {},
                onCustomText = {},
            )
            HonorificEditor(
                coach = Coach.SIB,
                draft = HonorificDraft(HonorificType.DAUGHTER),
                previewText = "오, 웬일. 잘했네.",
                onPick = {},
                onCustomText = {},
                showPreviewLabel = false,
            )
        }
    }
}
