package com.barodrive.app.feature.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroSheet
import com.barodrive.app.ui.components.CoachRow
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.components.SheetAction
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType

@Composable
fun CoachRoute(onBack: () -> Unit, onHonorific: () -> Unit, viewModel: CoachViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CoachScreen(state, onBack = onBack, onPick = viewModel::pick, onListen = viewModel::listen, onHonorific = onHonorific)
}

/**
 * CO-1 코치 고르기 (coach.js CO-1, 목업 s02): 코치 줄 3개(미리 듣기) · 4번째 칸 자리에 호칭 줄 '부르는 말 · 우리 딸 ›'(결정-21) ·
 * 안내 '코치가 달라도 알려 주는 내용과 때는 같아요…'. 줄을 누르면 바로 바뀐다.
 */
@Composable
fun CoachScreen(state: CoachUiState, onBack: () -> Unit, onPick: (Coach) -> Unit, onListen: (Coach) -> Unit, onHonorific: () -> Unit) {
    val colors = BaroTheme.colors
    BaroScaffold(
        topBar = { BaroAppBar(title = stringResource(R.string.coach_title), sub = stringResource(R.string.coach_sub), nav = AppBarNav.Back, onNav = onBack) },
    ) {
        Column {
            Text(stringResource(R.string.coach_ask), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.coach_ask_hint), style = MaterialTheme.typography.labelLarge, color = colors.t3)
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            CoachOrder.forEach { c ->
                CoachRow(
                    face = c.face(),
                    preview = stringResource(R.string.coach_preview_quoted, state.previews[c].orEmpty()),
                    selected = state.coach == c,
                    onSelect = { onPick(c) },
                    onListen = { onListen(c) },
                )
            }
        }
        HonorificRow(Honorifics.label(state.honorific), onHonorific)
        NoteRow(stringResource(R.string.coach_note))
    }
}

/** 호칭 줄(coach.css `.hon-row`): 아이콘 칸 112×72 · '부르는 말 · 우리 딸' · 보조 글 · 꺾쇠. 누르면 CO-2 시트. */
@Composable
private fun HonorificRow(label: String, onClick: () -> Unit) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusL)
    val desc = stringResource(R.string.coach_hon_row_desc)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .semantics { contentDescription = desc }
            .clickable(interactionSource = null, indication = BaroPressIndication, role = Role.Button, onClick = onClick)
            .background(colors.bgCard, shape)
            .border(2.dp, colors.cardLine, shape)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 112.dp, height = 72.dp), contentAlignment = Alignment.Center) {
            BaroIcon(R.drawable.ic_voice, contentDescription = null, size = 56.dp, tint = colors.brandText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                buildAnnotatedString {
                    append(stringResource(R.string.coach_hon_row_prefix))
                    withStyle(SpanStyle(color = colors.brandText)) { append(label) }
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
            )
            Text(stringResource(R.string.coach_hon_row_sub), style = MaterialTheme.typography.labelLarge, color = colors.t3)
        }
        BaroIcon(R.drawable.ic_chev, contentDescription = null, size = Dimens.iconS, tint = colors.t3)
    }
}

@Composable
fun HonorificSheetRoute(onClose: () -> Unit, viewModel: HonorificViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HonorificSheetContent(state, onClose = onClose, onPick = viewModel::pick, onCustomText = viewModel::setCustomText, onSave = { viewModel.save(onClose) })
}

/** CO-2 호칭 정하기 시트(coach.js `openHonorificSheet`): [취소] [저장]. 직접 쓰기가 비면 [저장]이 막히고 누르면 토스트. */
@Composable
fun HonorificSheetContent(state: HonorificUiState, onClose: () -> Unit, onPick: (HonorificType) -> Unit, onCustomText: (String) -> Unit, onSave: () -> Unit) {
    val toast = LocalBaroToast.current
    val hint = stringResource(R.string.hon_empty_hint)
    BaroSheet(
        onDismissRequest = onClose,
        title = stringResource(R.string.hon_sheet_title),
        sub = stringResource(R.string.hon_sheet_sub),
        actions = listOf(
            SheetAction(stringResource(R.string.ui_cancel), onClick = onClose, kind = BaroButtonKind.Ghost),
            SheetAction(
                stringResource(R.string.hon_save), onClick = onSave, kind = BaroButtonKind.Primary,
                enabled = state.draft.valid, onDisabledClick = { toast.show(hint) },
            ),
        ),
    ) {
        HonorificEditor(
            coach = state.coach,
            draft = state.draft,
            previewText = state.preview,
            onPick = onPick,
            onCustomText = onCustomText,
            modifier = Modifier.padding(bottom = Dimens.space1),
            onSheet = true,
        )
    }
}

@BaroScreenPreviews
@Composable
private fun CoachScreenPreview() {
    BaroTheme {
        CoachScreen(
            CoachUiState(
                Coach.DAD, Honorific(HonorificType.DAUGHTER),
                mapOf(Coach.SIB to "야, 지금 켜면 늦어. 다음엔 40미터 전에 켜라.", Coach.MOM to "어머, 지금 켜면 늦어! 다음엔 40미터 전에 켜 보자!", Coach.DAD to "괜찮아, 다음엔 40미터쯤 전에 켜 보자."),
            ),
            onBack = {}, onPick = {}, onListen = {}, onHonorific = {},
        )
    }
}

@BaroScreenPreviews
@Composable
private fun HonorificSheetPreview() {
    BaroTheme {
        HonorificSheetContent(
            HonorificUiState(Coach.MOM, HonorificDraft(HonorificType.CUSTOM, ""), "어머 잘했다! 딱 좋았어!"),
            onClose = {}, onPick = {}, onCustomText = {}, onSave = {},
        )
    }
}
