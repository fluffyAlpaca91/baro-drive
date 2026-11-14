package com.barodrive.app.feature.feedback

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroChip
import com.barodrive.app.ui.components.BaroConfirmDialog
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroIconButton
import com.barodrive.app.ui.components.BaroIconButtonSize
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroSheet
import com.barodrive.app.ui.components.BaroTextButton
import com.barodrive.app.ui.components.BaroTextField
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.SheetAction
import com.barodrive.app.ui.components.fadeWhenMoreBelow
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Delivery

/** FB-2 경로 화면: ViewModel을 붙이고 토스트·PR-1 이동을 받는다. */
@Composable
fun MyPresetsRoute(
    onBack: () -> Unit,
    onStart: () -> Unit,
    viewModel: MyPresetsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MyPresetsEffect.Toast -> toast.show(effect.text)
                MyPresetsEffect.GoPredrive -> onStart()
            }
        }
    }
    MyPresetsScreen(
        state = state,
        name = viewModel.nameText,
        editField = viewModel.editField,
        actions = MyPresetsActions(
            onBack = onBack,
            onSelect = viewModel::selectPreset,
            onSelectNew = viewModel::selectNew,
            onEdit = viewModel::openEdit,
            onChoose = viewModel::choose,
            onBlocked = viewModel::blocked,
            onResetItems = viewModel::resetItems,
            onName = viewModel::setName,
            onSave = viewModel::save,
            onStart = viewModel::start,
            onEditName = viewModel::setEditName,
            onCloseEdit = viewModel::closeEdit,
            onAskDelete = viewModel::askDelete,
            onCancelDelete = viewModel::cancelDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onRename = viewModel::rename,
        ),
    )
}

/** FB-2 화면이 부르는 일 묶음. */
class MyPresetsActions(
    val onBack: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onSelectNew: () -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onChoose: (String, Delivery) -> Unit = { _, _ -> },
    val onBlocked: (FeedbackRow, Delivery) -> Unit = { _, _ -> },
    val onResetItems: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onEditName: (String) -> Unit = {},
    val onCloseEdit: () -> Unit = {},
    val onAskDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onRename: () -> Unit = {},
)

/**
 * FB-2 내 음성 피드백 설정(자율) (목업 s12, feedback.js FB-2): 저장된 설정 칩('예시' 태그, 고른 칩 옆 연필) + '새로 만들기' · 설명 줄 ·
 * 안전 항목 고정 카드 · '항목 12개' + [기본값으로 되돌리기] · 항목 목록(목록만 스크롤) · 이름 칸 + [저장] · [이 설정으로 출발].
 * 칩을 길게 누르거나 연필 → 이름 바꾸기·지우기 시트(이 화면 안 시트).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyPresetsScreen(
    state: MyPresetsUiState,
    name: String,
    editField: String,
    actions: MyPresetsActions,
    modifier: Modifier = Modifier,
) {
    val colors = BaroTheme.colors
    BaroScaffold(
        modifier = modifier,
        topBar = {
            BaroAppBar(
                title = stringResource(R.string.fb2_title),
                sub = stringResource(R.string.fb2_sub),
                nav = AppBarNav.Back,
                onNav = actions.onBack,
            )
        },
        scrollable = false,
        overlay = { EditOverlay(state.edit, editField, actions) },
    ) {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            state.chips.forEach { chip ->
                PresetChip(chip, onClick = { actions.onSelect(chip.id) }, onLongClick = { actions.onEdit(chip.id) })
                if (chip.selected) {
                    BaroIconButton(
                        icon = R.drawable.ic_pencil,
                        contentDescription = stringResource(R.string.fb2_edit, chip.name),
                        onClick = { actions.onEdit(chip.id) },
                        size = BaroIconButtonSize.S,
                        modifier = Modifier.offset(x = (-8).dp),
                    )
                }
            }
            BaroChip(
                text = stringResource(R.string.fb2_new),
                selected = state.newSelected,
                onClick = actions.onSelectNew,
                icon = R.drawable.ic_plus,
            )
        }
        Text(state.desc, style = MaterialTheme.typography.bodySmall, color = colors.t2)
        BaroCard(contentPadding = PaddingValues(horizontal = 28.dp, vertical = 18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                BaroIcon(R.drawable.ic_lock, contentDescription = null, tint = colors.t3)
                Column(Modifier.weight(1f)) {
                    Text(state.safeNames, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                    Text(stringResource(R.string.fb2_safe_sub), style = MaterialTheme.typography.labelLarge, color = colors.t3)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
                // 목록 위 줄(.fb2-lh): '항목 12개'(.grp-h, 위아래 여백 없이) + [기본값으로 되돌리기]
                Text(
                    stringResource(R.string.fb2_count, state.rows.size),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.t3,
                )
                Spacer(Modifier.weight(1f))
                BaroTextButton(
                    text = stringResource(R.string.fb2_reset),
                    onClick = actions.onResetItems,
                    leadingIcon = R.drawable.ic_refresh,
                    trailingIcon = null,
                )
            }
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .weight(1f)
                    .fadeWhenMoreBelow(scroll)
                    .verticalScroll(scroll),
            ) {
                state.rows.forEach { row ->
                    FeedbackRowItem(row, onSelect = { actions.onChoose(row.id, it) }, onBlocked = { actions.onBlocked(row, it) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
            BaroTextField(
                value = name,
                onValueChange = actions.onName,
                modifier = Modifier.weight(1f),
                placeholder = stringResource(R.string.fb2_name_placeholder),
                maxLength = PresetRules.MAX_NAME,
                onDone = { actions.onSave() },
            )
            BaroButton(text = stringResource(R.string.fb2_save), onClick = actions.onSave)
        }
        if (state.canStart) {
            BaroButton(
                text = stringResource(R.string.fb2_start),
                onClick = actions.onStart,
                modifier = Modifier.fillMaxWidth(),
                size = BaroButtonSize.L,
                kind = BaroButtonKind.Primary,
            )
        }
    }
}

/** 저장된 설정 칩(목업 `.chip` + `.fb2-ex` '예시' 태그). 누르면 고르고, 길게 누르면 이름 바꾸기·지우기. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(chip: PresetChipUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = BaroTheme.colors
    val (background, content) = if (chip.selected) colors.brand to colors.onBrand else colors.btnGhostBg to colors.t1
    Row(
        modifier = Modifier
            .height(Dimens.chipHeight)
            .semantics {
                role = Role.RadioButton
                selected = chip.selected
            }
            .combinedClickable(
                interactionSource = null,
                indication = BaroPressIndication,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .background(background, CircleShape)
            .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(chip.name, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = content, maxLines = 1)
        if (chip.example) {
            Text(
                text = stringResource(R.string.fb2_example),
                modifier = Modifier
                    .background(if (chip.selected) colors.onBrand else colors.brandSoft, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = if (chip.selected) colors.brand else colors.brandText,
            )
        }
    }
}

/** 이름 바꾸기·지우기 시트와 지우기 확인 대화상자(feedback.js `openEdit`). */
@Composable
private fun EditOverlay(edit: PresetEditUi?, field: String, actions: MyPresetsActions) {
    if (edit == null) return
    BaroSheet(
        onDismissRequest = actions.onCloseEdit,
        title = stringResource(R.string.fb2_sheet_title, edit.name),
        sub = stringResource(R.string.fb2_sheet_sub),
        actions = listOf(
            SheetAction(stringResource(R.string.fb2_delete), onClick = actions.onAskDelete),
            SheetAction(stringResource(R.string.fb2_rename), onClick = actions.onRename, kind = BaroButtonKind.Primary),
        ),
    ) {
        BaroTextField(
            value = field,
            onValueChange = actions.onEditName,
            placeholder = stringResource(R.string.fb2_name_placeholder),
            maxLength = PresetRules.MAX_NAME,
            label = stringResource(R.string.fb2_sheet_name),
            onDone = { actions.onRename() },
        )
    }
    if (edit.confirmDelete) {
        BaroConfirmDialog(
            title = stringResource(R.string.fb2_delete_title, edit.name),
            onConfirm = actions.onConfirmDelete,
            onDismissRequest = actions.onCancelDelete,
            confirmLabel = stringResource(R.string.fb2_delete),
            danger = true,
        ) {
            Text(stringResource(R.string.fb2_delete_body))
        }
    }
}

private val previewState = MyPresetsUiState(
    loading = false,
    chips = listOf(
        PresetChipUi("ex-commute", "출퇴근용", example = true, selected = false),
        PresetChipUi("ex-rain", "비 오는 날", example = true, selected = true),
    ),
    desc = "부드러운 출발·정지와 날씨 안내도 바로 피드백해요.",
    safeNames = "제한속도 초과 · 어린이보호구역 · 깜빡이 없이 회전 · 차로 바꿀 때 깜빡이 · 끼어들 때 거리",
    rows = listOf(FeedbackSamples.j3, FeedbackSamples.b1, FeedbackSamples.c1),
    canStart = true,
)

@BaroScreenPreviews
@Composable
private fun MyPresetsPreview() {
    BaroTheme { MyPresetsScreen(previewState, name = "비 오는 날", editField = "", actions = MyPresetsActions()) }
}

@BaroScreenPreviews
@Composable
private fun MyPresetsEditPreview() {
    BaroTheme {
        MyPresetsScreen(
            previewState.copy(edit = PresetEditUi("ex-rain", "비 오는 날")),
            name = "비 오는 날",
            editField = "장마철",
            actions = MyPresetsActions(),
        )
    }
}

@BaroWideScreenPreviews
@Composable
private fun MyPresetsWidePreview() {
    BaroTheme { MyPresetsScreen(previewState.copy(canStart = false), name = "", editField = "", actions = MyPresetsActions()) }
}
