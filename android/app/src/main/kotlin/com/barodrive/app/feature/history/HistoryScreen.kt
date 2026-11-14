package com.barodrive.app.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroChip
import com.barodrive.app.ui.components.BaroConfirmDialog
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroIconButton
import com.barodrive.app.ui.components.BaroIconButtonSize
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroTag
import com.barodrive.app.ui.components.EmptyState
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.components.TagKind
import com.barodrive.app.ui.components.fadeWhenMoreBelow
import com.barodrive.app.ui.components.lineAt
import com.barodrive.app.ui.components.pressClickable
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/** ST-2 경로 화면. */
@Composable
fun HistoryRoute(
    onBack: () -> Unit,
    onOpen: (RecordTarget) -> Unit,
    onPractice: (Int) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LaunchedEffect(viewModel) { viewModel.toasts.collect { toast.show(it) } }
    HistoryScreen(
        state = state,
        onBack = onBack,
        onPick = viewModel::pick,
        onOpen = onOpen,
        onDelete = viewModel::askDelete,
        onDismiss = viewModel::dismiss,
        onConfirmDelete = viewModel::confirmDelete,
        onPractice = onPractice,
    )
}

/**
 * ST-2 연습 기록 (history.js ST-2, 설계 4.2 ST-2): 묶음 칩 · 안내 줄 · 회차 줄 목록(목록만 스크롤) · 빈 상태.
 * 줄: 모드 번호 원 · '2단계 3회차' + 코스 이름 · '오늘 09:12 · 18분 · 4.2km' · 상태 태그 · 꺾쇠(리뷰가 있을 때) · 지우기.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
    onOpen: (RecordTarget) -> Unit,
    onDelete: (RecordRowUi) -> Unit,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit,
    onPractice: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BaroScaffold(
        modifier = modifier,
        topBar = { BaroAppBar(title = stringResource(R.string.st2_title), sub = stringResource(R.string.st2_sub), nav = AppBarNav.Back, onNav = onBack) },
        scrollable = false,
        overlay = {
            state.confirm?.let { row ->
                BaroConfirmDialog(
                    title = stringResource(R.string.st2_delete_title),
                    onConfirm = onConfirmDelete,
                    onDismissRequest = onDismiss,
                    confirmLabel = stringResource(R.string.st2_delete),
                    danger = true,
                ) { Text(stringResource(R.string.st2_delete_body, row.title)) }
            }
        },
    ) {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap),
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            state.filters.forEach { f -> BaroChip(f.label, selected = f.id == state.selected, onClick = { onPick(f.id) }) }
        }
        NoteRow(
            if (state.total > 0) stringResource(R.string.st2_count_n, RecordText.MAX_TEXT, state.total) else RecordText.MAX_TEXT,
        )
        val scroll = rememberScrollState()
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadeWhenMoreBelow(scroll)
                .verticalScroll(scroll),
        ) {
            when (state.empty) {
                HistoryEmpty.ALL -> EmptyState(
                    title = stringResource(R.string.st2_empty_title),
                    icon = R.drawable.ic_history,
                    body = stringResource(R.string.st2_empty_body),
                    action = {
                        BaroButton(stringResource(R.string.st2_go_practice), onClick = { onPractice(state.stage) }, kind = BaroButtonKind.Primary)
                    },
                )
                HistoryEmpty.FILTER -> EmptyState(title = stringResource(R.string.st2_empty_filter, state.selectedLabel), icon = R.drawable.ic_history)
                HistoryEmpty.NONE -> state.rows.forEach { row -> RecordRow(row, onOpen = onOpen, onDelete = { onDelete(row) }) }
            }
        }
    }
}

/** 회차 줄(`.st-rec`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordRow(row: RecordRowUi, onOpen: (RecordTarget) -> Unit, onDelete: () -> Unit) {
    val colors = BaroTheme.colors
    val target = row.target
    val desc = listOf(row.title, row.place, row.sub).filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .lineAt(bottom = true, color = colors.line)
            .then(if (target != null) Modifier.pressClickable(enabled = true, onClick = { onOpen(target) }, role = Role.Button) else Modifier)
            .padding(vertical = Dimens.space2),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(if (row.virtual) Color.Transparent else colors.brandSoft, CircleShape)
                .border(2.dp, if (row.virtual) colors.lineStrong else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.mode?.toString() ?: "·",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold),
                color = if (row.virtual) colors.t2 else colors.brandText,
            )
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = desc }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                if (row.place.isNotEmpty()) {
                    Text(row.place, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = colors.t2)
                }
            }
            if (row.sub.isNotEmpty()) Text(row.sub, style = MaterialTheme.typography.labelLarge, color = colors.t3)
            if (row.tags.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { row.tags.forEach { RecordTagView(it) } }
            }
        }
        if (target != null) {
            BaroIcon(R.drawable.ic_chev, contentDescription = null, size = Dimens.iconS, tint = colors.t3)
        } else {
            Spacer(Modifier.width(Dimens.iconS))
        }
        BaroIconButton(
            icon = R.drawable.ic_close,
            contentDescription = stringResource(R.string.st2_delete_desc, row.title),
            onClick = onDelete,
            size = BaroIconButtonSize.S,
        )
    }
}

/** 상태 태그: 통과(민트) · 통과 회차(옅은 민트) · 공유됨(회색) · 확인 필요·보낼 예정(주의 테두리 + 본문 색 — 라이트 대비 때문). */
@Composable
private fun RecordTagView(t: RecordTag) {
    val colors = BaroTheme.colors
    val icon = when (t.icon) {
        "i-medal" -> R.drawable.ic_medal
        "i-check" -> R.drawable.ic_check
        "i-brake" -> R.drawable.ic_brake
        else -> R.drawable.ic_send
    }
    when (t.kind) {
        RecordTagKind.BRAND -> BaroTag(t.text, kind = TagKind.Brand, icon = icon)
        RecordTagKind.SOFT -> BaroTag(t.text, kind = TagKind.Soft, icon = icon)
        RecordTagKind.MUTE -> BaroTag(t.text, kind = TagKind.Mute, icon = icon)
        RecordTagKind.WARN -> Row(
            Modifier
                .height(Dimens.tagHeight)
                .border(2.dp, colors.caution, CircleShape)
                .padding(horizontal = Dimens.space2),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BaroIcon(icon, contentDescription = null, size = 24.dp, tint = colors.caution)
            Text(t.text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = colors.t1, maxLines = 1)
        }
    }
}

private val previewState = HistoryUiState(
    loading = false,
    filters = RecordText.FILTERS,
    total = 3,
    rows = listOf(
        RecordRowUi(
            "sess-a", 2, false, "2단계 3회차", "초등학교 뒤 골목", "오늘 09:12 · 18분 · 4.2km",
            listOf(RecordTag("qualified", "통과 회차", RecordTagKind.SOFT, "i-check"), RecordTag("f2", "급제동 확인 1건", RecordTagKind.WARN, "i-brake")),
            RecordTarget(2, "sess-a"),
        ),
        RecordRowUi("sess-b", 5, false, "자율 2회차", "마트까지", "오늘 10:00 · 31분 · 12.4km · 86점", listOf(RecordTag("share", "보냈어요", RecordTagKind.MUTE, "i-send")), RecordTarget(5, "sess-b")),
        RecordRowUi("sess-v", 6, true, "세워 두고 연습", "평행 · 오른쪽", "어제 20:05 · 42초 · 성공", emptyList(), null),
    ),
)

@BaroScreenPreviews
@Composable
private fun HistoryPreview() {
    BaroTheme { HistoryScreen(previewState, {}, {}, {}, {}, {}, {}, {}) }
}

@BaroScreenPreviews
@Composable
private fun HistoryEmptyPreview() {
    BaroTheme { HistoryScreen(HistoryUiState(loading = false, filters = RecordText.FILTERS, empty = HistoryEmpty.ALL), {}, {}, {}, {}, {}, {}, {}) }
}

@BaroWideScreenPreviews
@Composable
private fun HistoryWidePreview() {
    BaroTheme { HistoryScreen(previewState, {}, {}, {}, {}, {}, {}, {}) }
}
