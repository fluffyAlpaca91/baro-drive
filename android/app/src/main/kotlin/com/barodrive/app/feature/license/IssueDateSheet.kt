package com.barodrive.app.feature.license

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.barodrive.app.R
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.updateLicense
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroDatePicker
import com.barodrive.app.ui.components.BaroSheet
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.SheetAction
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.LicenseState
import com.barodrive.core.model.LicenseStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** LC-2 연습면허 발급일 시트에 그릴 값. */
data class IssueDateUiState(
    val value: LocalDate,
    val today: LocalDate,
    /** 미리보기 '2027. 3. 31.까지 · D-141'. */
    val preview: String,
    val status: LicenseStatus,
)

/** LC-2가 화면에 시키는 일. */
sealed interface IssueDateEffect {
    data class Toast(val text: String) : IssueDateEffect

    data object Close : IssueDateEffect
}

/**
 * LC-2 연습면허 발급일(시트, 정차 중) (프로토타입 screens/license.js `openIssueSheet`, 설계 4.2 LC-2): 날짜 고르기 + 미리보기 + [취소] [저장].
 * 지금 발급일에서 시작하고, 없거나 유효기간이 지났으면 오늘에서(새 연습면허를 받은 경우가 많음). 오늘보다 뒤는 고를 수 없다.
 * 저장하면 profile.license.issueDate('YYYY-MM-DD'). 유효기간 = 발급일 + 1년 − 1일(엔진 license.status).
 */
@HiltViewModel
class IssueDateViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val clock: Clock,
) : ViewModel() {
    private val today: LocalDate = LocalDate.now(clock)
    private val _state = MutableStateFlow(stateFor(today))
    private val _effects = Channel<IssueDateEffect>(Channel.BUFFERED)

    val uiState: StateFlow<IssueDateUiState> = _state.asStateFlow()
    val effects: Flow<IssueDateEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            val cur = profiles.current().license.issueDate
            val start = cur?.takeIf {
                BaroEngine.license.validIssueDate(it, today) && !BaroEngine.license.status(it, today).expired
            }?.let(LocalDate::parse) ?: today
            _state.value = stateFor(start)
        }
    }

    /** 날짜 칸을 옮김. */
    fun setDate(d: LocalDate) {
        _state.value = stateFor(d)
    }

    /** 날짜 고르기 끝에 닿음(오늘보다 뒤). */
    fun limit(atMax: Boolean) {
        if (atMax) _effects.trySend(IssueDateEffect.Toast(FUTURE))
    }

    /** [저장]. 오늘보다 뒤면 토스트만(시트는 그대로). */
    fun save() {
        viewModelScope.launch {
            val v = _state.value.value.toString()
            if (!BaroEngine.license.validIssueDate(v, LocalDate.now(clock))) {
                _effects.send(IssueDateEffect.Toast(FUTURE))
                return@launch
            }
            profiles.updateLicense { it.copy(issueDate = v) }
            _effects.send(IssueDateEffect.Close)
        }
    }

    private fun stateFor(d: LocalDate): IssueDateUiState {
        val st = BaroEngine.license.status(d.toString(), today)
        return IssueDateUiState(value = d, today = today, preview = LicenseText.previewText(st), status = st)
    }

    private companion object {
        const val FUTURE = "오늘보다 뒤 날짜는 고를 수 없어요"
    }
}

/** LC-2 시트 경로: ViewModel을 붙인다. [onClose] = 시트 닫기(취소·저장 뒤·뒤로 키·출발). */
@Composable
fun IssueDateSheetRoute(onClose: () -> Unit, viewModel: IssueDateViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is IssueDateEffect.Toast -> toast.show(e.text)
                IssueDateEffect.Close -> onClose()
            }
        }
    }
    IssueDateSheet(state, onDate = viewModel::setDate, onLimit = viewModel::limit, onCancel = onClose, onSave = viewModel::save)
}

/** LC-2 연습면허 발급일 시트(license.css `.lc2-sheet`): 연·월·일 고르기 + 가운데 미리보기(30일 이하 주의 색, 지났으면 위험 색). */
@Composable
fun IssueDateSheet(
    state: IssueDateUiState,
    onDate: (LocalDate) -> Unit,
    onLimit: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = BaroTheme.colors
    BaroSheet(
        onDismissRequest = onCancel,
        title = stringResource(R.string.lc2_title),
        sub = stringResource(R.string.lc2_sub),
        actions = listOf(
            SheetAction(stringResource(R.string.ui_cancel), onClick = onCancel),
            SheetAction(stringResource(R.string.lc2_save), onClick = onSave, kind = BaroButtonKind.Primary),
        ),
    ) {
        BaroDatePicker(value = state.value, onValueChange = onDate, max = state.today, onLimit = onLimit)
        if (state.preview.isNotEmpty()) {
            Text(
                state.preview,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = when {
                    state.status.expired -> colors.danger
                    state.status.soon || state.status.state == LicenseState.SOON -> colors.cautionText
                    else -> colors.brandText
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

@BaroScreenPreviews
@Composable
private fun IssueDateSheetPreview() {
    BaroTheme {
        IssueDateSheet(
            IssueDateUiState(
                value = LocalDate.of(2026, 4, 1),
                today = LocalDate.of(2026, 11, 11),
                preview = "2027. 3. 31.까지 · D-141",
                status = LicenseStatus(state = LicenseState.OK, dday = "D-141"),
            ),
            onDate = {}, onLimit = {}, onCancel = {}, onSave = {},
        )
    }
}
