package com.barodrive.app.feature.settings

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.barodrive.app.R
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroListRow
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.CoachAvatar
import com.barodrive.app.ui.components.CoachFace
import com.barodrive.app.ui.components.GroupHeader
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** ST-1 설정에 그릴 값. */
data class SettingsUiState(val rows: List<SettingsRow> = emptyList())

/** ST-1 설정 (프로토타입 screens/settings.js ST-1, 설계 4.2 ST-1·5.10): 지금 프로필과 차종(차에서 읽음)으로 줄을 만든다. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    profiles: ProfileRepository,
    session: SessionController,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = combine(
        profiles.profile,
        session.state.map { it.snapshot?.model }.distinctUntilChanged(),
    ) { p, model -> SettingsUiState(SettingsRules.rows(p, SettingsRules.modelName(model))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** ST-1 경로 화면. */
@Composable
fun SettingsRoute(onBack: () -> Unit, onOpen: (SettingsTarget) -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state, onBack = onBack, onOpen = onOpen)
}

/**
 * ST-1 설정 (settings.js ST-1, 목업에 없는 화면 — 결정-44): 줄 높이 88 목록. 코치 묶음 · '기록과 개인정보' · '차와 앱'.
 * 줄 오른쪽에 지금 값('아빠/배우자' · '설정 2개' · '가족 공유 끔'). 내 차·운전자 프로필은 차에서 읽어 누름이 없다.
 */
@Composable
fun SettingsScreen(state: SettingsUiState, onBack: () -> Unit, onOpen: (SettingsTarget) -> Unit, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    BaroScaffold(
        modifier = modifier,
        topBar = { BaroAppBar(title = stringResource(R.string.st1_title), sub = stringResource(R.string.st1_sub), nav = AppBarNav.Back, onNav = onBack) },
        bodyArrangement = androidx.compose.foundation.layout.Arrangement.Top,
    ) {
        var group: SettingsGroup? = null
        state.rows.forEach { r ->
            if (r.group != group) {
                group = r.group
                when (r.group) {
                    SettingsGroup.DATA -> GroupHeader(stringResource(R.string.st1_group_data))
                    SettingsGroup.CAR -> GroupHeader(stringResource(R.string.st1_group_car))
                    SettingsGroup.COACH -> Unit
                }
            }
            val target = r.target
            BaroListRow(
                title = r.title,
                sub = r.sub,
                icon = if (r.coach == null) r.icon else null,
                leading = r.coach?.let { c -> { CoachAvatar(CoachFace.fromId(c.name.lowercase()), size = 44.dp, contentDescription = null) } },
                trailing = r.value.takeIf { it.isNotEmpty() }?.let { v ->
                    {
                        Text(
                            v,
                            modifier = Modifier.widthIn(max = 280.dp),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = colors.t2,
                            textAlign = TextAlign.End,
                        )
                    }
                },
                onClick = target?.let { t -> { onOpen(t) } },
            )
        }
    }
}

private val previewRows = SettingsRules.rows(
    Profile(coach = Coach.DAD, consent = com.barodrive.core.model.Consent(record = true, family = true)),
    model = "아반떼",
)

@BaroScreenPreviews
@Composable
private fun SettingsPreview() {
    BaroTheme { SettingsScreen(SettingsUiState(previewRows), onBack = {}, onOpen = {}) }
}

@BaroWideScreenPreviews
@Composable
private fun SettingsWidePreview() {
    BaroTheme { SettingsScreen(SettingsUiState(previewRows), onBack = {}, onOpen = {}) }
}
