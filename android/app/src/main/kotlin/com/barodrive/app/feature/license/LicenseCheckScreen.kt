package com.barodrive.app.feature.license

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroCheckBox
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.CardEmphasis
import com.barodrive.app.ui.components.CheckRow
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.engine.api.LicenseChecks
import com.barodrive.core.model.LicenseState
import com.barodrive.core.model.LicenseStatus
import com.barodrive.core.model.SeatState

/** LC-1 경로 화면: ViewModel을 붙이고, 다시 보일 때마다 본인 체크를 비운다. */
@Composable
fun LicenseCheckRoute(
    onBack: () -> Unit,
    onIssueDate: () -> Unit,
    onCourses: () -> Unit,
    viewModel: LicenseCheckViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.resetChecks() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is LicenseCheckEffect.Toast -> if (e.text.isNotEmpty()) toast.show(e.text)
                LicenseCheckEffect.GoCourses -> onCourses()
            }
        }
    }
    LicenseCheckScreen(state, onBack = onBack, onIssueDate = onIssueDate, onCheck = viewModel::setCheck, onStart = viewModel::start)
}

/**
 * LC-1 운전면허 연습 모드 시작 체크 (목업 s09, license.js LC-1): 유효기간 카드(없음 · D-141 · 주의 · 지남) · 안내 · 체크 카드 4줄
 * (조수석은 차가 확인) · '앱은 면허를 확인하지 않아요' · 하단 [시험장 주변 코스 고르기](모두 되면) — 지났으면 안내 + [발급일 바꾸기].
 */
@Composable
fun LicenseCheckScreen(
    state: LicenseCheckUiState,
    onBack: () -> Unit,
    onIssueDate: () -> Unit,
    onCheck: (LicenseCheckItem, Boolean) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BaroTheme.colors
    BaroScaffold(
        modifier = modifier,
        topBar = {
            BaroAppBar(title = stringResource(R.string.lc1_title), sub = stringResource(R.string.lc1_sub), nav = AppBarNav.Back, onNav = onBack)
        },
    ) {
        ValidityCard(state.status, onIssueDate)
        Text(stringResource(R.string.lc1_intro), style = MaterialTheme.typography.bodySmall, color = colors.t2)
        BaroCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.Top) {
            SeatRow(state.seat)
            CheckRow(
                label = stringResource(R.string.lc1_check_companion),
                checked = state.checks.companion,
                onCheckedChange = { onCheck(LicenseCheckItem.COMPANION, it) },
            )
            CheckRow(
                label = stringResource(R.string.lc1_check_sign),
                checked = state.checks.sign,
                onCheckedChange = { onCheck(LicenseCheckItem.SIGN, it) },
                sub = stringResource(R.string.lc1_check_sign_sub),
            )
            CheckRow(
                label = stringResource(R.string.lc1_check_purpose),
                checked = state.checks.purpose,
                onCheckedChange = { onCheck(LicenseCheckItem.PURPOSE, it) },
                sub = stringResource(R.string.lc1_check_purpose_sub),
            )
        }
        NoteRow(stringResource(R.string.lc1_note))
        if (state.expired) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                Text(
                    stringResource(R.string.lc1_expired_guide),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.danger,
                    textAlign = TextAlign.Center,
                )
                BaroButton(
                    text = stringResource(R.string.lc1_change_date),
                    onClick = onIssueDate,
                    modifier = Modifier.fillMaxWidth(),
                    size = BaroButtonSize.L,
                    kind = BaroButtonKind.Primary,
                    icon = R.drawable.ic_pencil,
                )
            }
        } else if (!state.loading) {
            BaroButton(
                text = state.label,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                size = BaroButtonSize.L,
                kind = BaroButtonKind.Primary,
                enabled = state.ok,
                onDisabledClick = onStart,
            )
        }
    }
}

/** 유효기간 카드(목업 s09 `.dday`): 발급일 없음(보통 카드) · D-day(옅은 민트, 30일 이하 주의 색) · 지남(위험 색 테두리). */
@Composable
private fun ValidityCard(st: LicenseStatus, onIssueDate: () -> Unit) {
    val colors = BaroTheme.colors
    val emphasis = when (st.state) {
        LicenseState.NONE -> CardEmphasis.None
        LicenseState.EXPIRED -> CardEmphasis.Danger
        else -> CardEmphasis.Hero
    }
    BaroCard(Modifier.fillMaxWidth(), emphasis = emphasis) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.lc1_validity),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.t2,
                )
                when (st.state) {
                    LicenseState.NONE -> Text(
                        stringResource(R.string.lc1_none),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    )
                    LicenseState.EXPIRED -> {
                        Text(
                            stringResource(R.string.lc1_expired, st.expiryText.orEmpty()),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = colors.danger,
                        )
                        Text(stringResource(R.string.lc1_issued, st.issueText.orEmpty()), style = MaterialTheme.typography.labelLarge, color = colors.t2)
                    }
                    else -> {
                        Text(
                            st.dday.orEmpty(),
                            style = MaterialTheme.typography.headlineLarge,
                            color = if (st.state == LicenseState.SOON || st.soon) colors.cautionText else colors.brandText,
                        )
                        Text(
                            stringResource(R.string.lc1_issued_until, st.issueText.orEmpty(), st.expiryText.orEmpty()),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.t2,
                        )
                    }
                }
            }
            if (st.state != LicenseState.EXPIRED) {
                BaroButton(
                    text = stringResource(if (st.state == LicenseState.NONE) R.string.lc1_add_date else R.string.lc1_change_date),
                    onClick = onIssueDate,
                    size = BaroButtonSize.S,
                    icon = if (st.state == LicenseState.NONE) R.drawable.ic_calendar else R.drawable.ic_pencil,
                )
            }
        }
    }
}

/** 조수석 줄(차가 확인 — 누를 수 없음, `.ckr.auto`): 탔으면 체크 + 민트 글, 비었거나 못 읽으면 빈 칸 + 주의 색 글. */
@Composable
private fun SeatRow(seat: SeatState) {
    val colors = BaroTheme.colors
    val (label, sub) = when (seat) {
        SeatState.ON -> R.string.lc1_seat_on to R.string.lc1_seat_on_sub
        SeatState.OFF -> R.string.lc1_seat_off to R.string.lc1_seat_off_sub
        SeatState.UNKNOWN -> R.string.lc1_seat_unknown to R.string.lc1_seat_unknown_sub
    }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroCheckBox(checked = seat == SeatState.ON)
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            Text(
                stringResource(sub),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = if (seat == SeatState.ON) colors.brandText else colors.cautionText,
            )
        }
    }
}

private val previewState = LicenseCheckUiState(
    loading = false,
    status = LicenseStatus(
        issueDate = "2026-04-01", expiresOn = "2027-03-31", days = 141, dday = "D-141", state = LicenseState.OK,
        issueText = "2026. 4. 1.", expiryText = "2027. 3. 31.",
    ),
    seat = SeatState.ON,
    checks = LicenseChecks(companion = true),
    label = "모두 체크하면 시작할 수 있어요",
)

@BaroScreenPreviews
@Composable
private fun LicenseCheckPreview() {
    BaroTheme { LicenseCheckScreen(previewState, onBack = {}, onIssueDate = {}, onCheck = { _, _ -> }, onStart = {}) }
}

@BaroScreenPreviews
@Composable
private fun LicenseCheckExpiredPreview() {
    BaroTheme {
        LicenseCheckScreen(
            previewState.copy(
                status = previewState.status.copy(state = LicenseState.EXPIRED, expired = true, dday = null),
                expired = true,
                seat = SeatState.OFF,
            ),
            onBack = {}, onIssueDate = {}, onCheck = { _, _ -> }, onStart = {},
        )
    }
}

@BaroWideScreenPreviews
@Composable
private fun LicenseCheckWidePreview() {
    BaroTheme {
        LicenseCheckScreen(
            previewState.copy(status = LicenseStatus(state = LicenseState.NONE), seat = SeatState.UNKNOWN),
            onBack = {}, onIssueDate = {}, onCheck = { _, _ -> }, onStart = {},
        )
    }
}
