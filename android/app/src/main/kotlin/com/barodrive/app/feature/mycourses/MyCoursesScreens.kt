package com.barodrive.app.feature.mycourses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.session.Josa
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroChip
import com.barodrive.app.ui.components.BaroConfirmDialog
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroSheet
import com.barodrive.app.ui.components.BaroTag
import com.barodrive.app.ui.components.EmptyState
import com.barodrive.app.ui.components.GroupHeader
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.ModeChips
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.components.SheetAction
import com.barodrive.app.ui.components.TagKind
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.Courses
import com.barodrive.core.model.MyCourse

// ================================================================ ST-3 내 코스

@Composable
fun MyCoursesRoute(onBack: () -> Unit, onImport: () -> Unit, viewModel: MyCoursesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    val removed = stringResource(R.string.mc_removed)
    var sheetFor by remember { mutableStateOf<MyCourse?>(null) }
    var removeFor by remember { mutableStateOf<MyCourse?>(null) }
    val movedText = { m: Int -> Josa.attach(MyCoursesRules.modeShort(m) + " 내 코스", "으로", "로") + " 옮겼어요" }
    MyCoursesScreen(
        state = state,
        onBack = onBack,
        onImport = onImport,
        onChangeMode = { sheetFor = it },
        onRemove = { removeFor = it },
        overlay = {
            sheetFor?.let { mc ->
                ModeSheet(
                    course = mc,
                    onClose = { sheetFor = null },
                    onSave = { m ->
                        viewModel.setMode(mc.id, m) { err ->
                            when (err) {
                                null -> {
                                    toast.show(movedText(m))
                                    sheetFor = null
                                }
                                MyCourseError.SAME -> sheetFor = null
                                else -> toast.show(MyCoursesRules.errorText(err, m))
                            }
                        }
                    },
                )
            }
            removeFor?.let { mc ->
                BaroConfirmDialog(
                    title = stringResource(R.string.mc_remove_title),
                    confirmLabel = stringResource(R.string.mc_remove),
                    danger = true,
                    onConfirm = {
                        viewModel.remove(mc.id) { ok -> if (ok) toast.show(removed) }
                        removeFor = null
                    },
                    onDismissRequest = { removeFor = null },
                ) { Text(stringResource(R.string.mc_remove_body, Josa.attach("‘${mc.name}’", "이", "가"))) }
            }
        },
    )
}

/** ST-3 내 코스 (mycourses.js ST-3): [내비 즐겨찾기에서 불러오기] · 모드별 묶음 줄([모드 바꾸기] [지우기]) · 빈 상태 · 안내. */
@Composable
fun MyCoursesScreen(
    state: MyCoursesUiState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onChangeMode: (MyCourse) -> Unit,
    onRemove: (MyCourse) -> Unit,
    overlay: @Composable () -> Unit = {},
) {
    val colors = BaroTheme.colors
    BaroScaffold(
        topBar = { BaroAppBar(title = stringResource(R.string.mc_title), sub = stringResource(R.string.mc_sub), nav = AppBarNav.Back, onNav = onBack) },
        overlay = { overlay() },
    ) {
        BaroButton(stringResource(R.string.mc_import), onClick = onImport, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_star)
        if (!state.loaded) return@BaroScaffold
        if (state.groups.isEmpty()) {
            EmptyState(title = stringResource(R.string.mc_empty_title), icon = R.drawable.ic_route, body = stringResource(R.string.mc_empty_body))
        } else {
            Column {
                state.groups.forEach { g ->
                    GroupHeader(g.title)
                    g.items.forEach { it ->
                        val sub = listOfNotNull(it.sourceLabel, it.km?.let { km -> stringResource(R.string.mc_km, km) }, it.townName).joinToString(" · ")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Dimens.listRowMinHeight)
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(56.dp).background(colors.brandSoft, CircleShape), contentAlignment = Alignment.Center) {
                                Text(it.course.mode.toString(), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold), color = colors.brandText)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(it.name, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                Text(sub, style = MaterialTheme.typography.labelLarge, color = colors.t3)
                            }
                            BaroButton(stringResource(R.string.mc_change_mode), onClick = { onChangeMode(it.course) }, size = BaroButtonSize.S)
                            BaroButton(stringResource(R.string.mc_remove), onClick = { onRemove(it.course) }, size = BaroButtonSize.S)
                        }
                    }
                }
            }
        }
        NoteRow(stringResource(R.string.mc_note))
    }
}

/** 모드 바꾸기 시트(mycourses.js `openModeSheet`): 모드 칩 + 모드 이름, [취소] [바꾸기]. */
@Composable
private fun ModeSheet(course: MyCourse, onClose: () -> Unit, onSave: (Int) -> Unit) {
    var picked by rememberSaveable(course.id) { mutableStateOf(course.mode) }
    BaroSheet(
        onDismissRequest = onClose,
        title = stringResource(R.string.mc_sheet_title),
        sub = course.name,
        actions = listOf(
            SheetAction(stringResource(R.string.ui_cancel), onClick = onClose, kind = BaroButtonKind.Ghost),
            SheetAction(stringResource(R.string.mc_sheet_change), onClick = { onSave(picked) }, kind = BaroButtonKind.Primary),
        ),
    ) {
        ModeChips(selected = picked, onSelect = { picked = it }, modes = MyCoursesRules.IMPORT_MODES)
        Text(MyCoursesRules.modeLabel(picked), style = MaterialTheme.typography.bodySmall, color = BaroTheme.colors.t2)
    }
}

// ================================================================ CR-2 내비에서 불러오기

@Composable
fun NaviImportRoute(onBack: () -> Unit, viewModel: NaviImportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    NaviImportScreen(
        state = state,
        onBack = onBack,
        onTab = viewModel::selectTab,
        onToggle = viewModel::toggle,
        onMode = viewModel::pickMode,
        onSave = { id ->
            viewModel.save(id) { m, err ->
                toast.show(if (err == null) Josa.attach(MyCoursesRules.modeShort(m) + " 내 코스", "으로", "로") + " 저장했어요" else MyCoursesRules.errorText(err, m))
            }
        },
    )
}

/**
 * CR-2 내비에서 불러오기 (mycourses.js CR-2): 탭(즐겨찾기 · 최근 목적지) · 장소 줄([가져오기] → 모드 칩 + [저장]) · 안내.
 * '집'(지금 위치)은 [가져오기] 대신 '지금 있는 곳이에요'. 진짜 차는 차 내비의 장소(주소만, 거리는 모름)이고, 받는 동안·받지 못하면 그렇다고 쓴다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NaviImportScreen(
    state: NaviImportUiState,
    onBack: () -> Unit,
    onTab: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onMode: (Int) -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = BaroTheme.colors
    BaroScaffold(
        topBar = { BaroAppBar(title = stringResource(R.string.cr2_title), sub = stringResource(R.string.cr2_sub), nav = AppBarNav.Back, onNav = onBack) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
            BaroChip(stringResource(R.string.cr2_tab_bookmark), selected = !state.recent, onClick = { onTab(false) }, icon = R.drawable.ic_star, role = Role.RadioButton)
            BaroChip(stringResource(R.string.cr2_tab_recent), selected = state.recent, onClick = { onTab(true) }, icon = R.drawable.ic_history, role = Role.RadioButton)
        }
        if (!state.loaded) {
            NoteRow(stringResource(R.string.cr2_loading))
            return@BaroScaffold
        }
        if (state.naviFailed) {
            EmptyState(title = stringResource(R.string.cr2_navi_failed), icon = R.drawable.ic_info, body = stringResource(R.string.cr2_navi_failed_body))
        } else if (state.rows.isEmpty()) {
            EmptyState(title = stringResource(R.string.cr2_empty), icon = if (state.recent) R.drawable.ic_history else R.drawable.ic_star)
        } else {
            Column {
                state.rows.forEach { r ->
                    val sub = listOfNotNull(
                        r.addr.takeIf { it.isNotEmpty() },
                        if (r.here) stringResource(R.string.cr2_here_sub) else r.km?.let { stringResource(R.string.mc_km, it) },
                    ).joinToString(" · ")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.listRowMinHeight)
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BaroIcon(
                            if (r.here) R.drawable.ic_user else if (state.recent) R.drawable.ic_history else R.drawable.ic_star,
                            contentDescription = null,
                            tint = colors.t2,
                        )
                        Column(Modifier.weight(1f)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                if (r.savedModes.isNotEmpty()) {
                                    BaroTag(stringResource(R.string.cr2_saved_tag, r.savedModes.joinToString("·") { MyCoursesRules.modeShort(it) }), kind = TagKind.Soft)
                                }
                            }
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelLarge, color = colors.t3)
                        }
                        if (r.here) {
                            Text(stringResource(R.string.cr2_here), style = MaterialTheme.typography.labelLarge, color = colors.t3)
                        } else {
                            BaroButton(
                                stringResource(if (r.open) R.string.cr2_close else R.string.cr2_import),
                                onClick = { onToggle(r.id) },
                                size = BaroButtonSize.S,
                                kind = if (r.open) BaroButtonKind.Ghost else BaroButtonKind.Primary,
                            )
                        }
                    }
                    if (r.open) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(colors.bgCard, RoundedCornerShape(Dimens.radiusM))
                                .padding(Dimens.space3),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
                        ) {
                            Text(stringResource(R.string.cr2_pick), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                            ModeChips(selected = state.mode, onSelect = onMode, modes = MyCoursesRules.IMPORT_MODES)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(MyCoursesRules.modeLabel(state.mode), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.t2)
                                BaroButton(stringResource(R.string.cr2_save), onClick = { onSave(r.id) }, size = BaroButtonSize.S, kind = BaroButtonKind.Primary)
                            }
                        }
                    }
                }
            }
        }
        NoteRow(stringResource(R.string.cr2_note))
    }
}

@BaroScreenPreviews
@Composable
private fun MyCoursesPreview() {
    BaroTheme {
        MyCoursesScreen(
            MyCoursesUiState(
                loaded = true,
                groups = listOf(
                    MyCourseGroup(
                        2, "2단계 · 동네 골목 다니기",
                        listOf(MyCourseItem(MyCourse(id = "mc-c2-B", name = "초등학교 뒤 골목", mode = 2, source = CourseSource.SAVED, courseRef = "c2-B"), "초등학교 뒤 골목", "연습한 길", 2.9, null)),
                    ),
                ),
            ),
            onBack = {}, onImport = {}, onChangeMode = {}, onRemove = {},
        )
    }
}

@BaroScreenPreviews
@Composable
private fun NaviImportPreview() {
    BaroTheme {
        val places = Courses.places.filter { it.list == com.barodrive.core.model.PlaceList.BOOKMARK }
        NaviImportScreen(
            NaviImportUiState(
                loaded = true,
                rows = places.mapIndexed { i, p ->
                    PlaceRowUi(p.id, p.name, p.addr, p.km.takeIf { it > 0 }, p.here, if (i == 2) listOf(1) else emptyList(), open = i == 1)
                },
                mode = 2,
            ),
            onBack = {}, onTab = {}, onToggle = {}, onMode = {}, onSave = {},
        )
    }
}
