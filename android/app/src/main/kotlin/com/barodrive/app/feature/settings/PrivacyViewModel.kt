package com.barodrive.app.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.feature.feedback.PresetDrafts
import com.barodrive.app.feature.share.ShareCenter
import com.barodrive.core.model.UiMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** ST-4 확인 대화상자: 기록 저장 동의 철회 · 모든 연습 기록 지우기. */
enum class PrivacyDialog { WITHDRAW, CLEAR }

/** ST-4 공유와 개인정보에 그릴 값. 받는 사람 이름 칸 글은 [PrivacyViewModel.nameText]. */
data class PrivacyUiState(
    val consents: List<ConsentRow> = emptyList(),
    val dialog: PrivacyDialog? = null,
)

/** ST-4가 화면에 시키는 일. */
sealed interface PrivacyEffect {
    data class Toast(val text: String) : PrivacyEffect

    /** 기록 저장 동의를 거둠 → 처음 화면(ON-1)으로, 뒤로 스택을 비운다. */
    data object GoOnboarding : PrivacyEffect
}

/**
 * ST-4 공유와 개인정보 (프로토타입 screens/settings.js ST-4, 설계 4.2 ST-4, 8.1 consent, 결정-45·52, 결정사항 E T9-4·T9-6):
 * 받는 사람 이름(10자, 비우면 '가족') · 동의 스위치 4개 · [모든 연습 기록 지우기].
 * '연습 기록 저장'을 끄면 확인 → 이 프로필 기록을 지우고 처음 상태로(프로필이 하나뿐이면 저장 전체를 처음으로) → ON-1. 다른 운전자는 남긴다.
 * 가족 공유를 끄면 보낼 예정이던 요약도 거둔다.
 */
@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val records: RecordRepository,
    private val share: ShareCenter,
    private val drafts: PresetDrafts,
) : ViewModel() {
    private val dialog = MutableStateFlow<PrivacyDialog?>(null)
    private val _effects = Channel<PrivacyEffect>(Channel.BUFFERED)

    val effects: Flow<PrivacyEffect> = _effects.receiveAsFlow()

    val uiState: StateFlow<PrivacyUiState> = combine(profiles.profile, dialog) { p, d -> PrivacyUiState(SettingsRules.consentRows(p), d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PrivacyUiState())

    /** 받는 사람 이름 칸 글(입력 중에는 그대로, 저장은 다듬은 이름). */
    var nameText: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch { nameText = SettingsRules.familyName(profiles.current()) }
    }

    /** 이름 쓰기 — 바로 저장(비우면 '가족'). */
    fun setName(text: String) {
        nameText = text
        viewModelScope.launch {
            val name = SettingsRules.cleanName(text).ifEmpty { SettingsRules.DEFAULT_TO }
            profiles.update { p -> if (SettingsRules.familyName(p) == name) p else p.copy(consent = p.consent.copy(familyName = name)) }
        }
    }

    /** 칸을 떠날 때 비어 있으면 '가족'으로 보인다. */
    fun nameBlur() {
        if (SettingsRules.cleanName(nameText).isEmpty()) nameText = SettingsRules.DEFAULT_TO
    }

    /** 스위치 누름. */
    fun toggle(key: ConsentKey) {
        viewModelScope.launch {
            val on = SettingsRules.consentOn(profiles.current(), key)
            when {
                key == ConsentKey.RECORD && on -> dialog.value = PrivacyDialog.WITHDRAW
                key == ConsentKey.FAMILY && on -> {
                    profiles.update { SettingsRules.withConsent(it, ConsentKey.FAMILY, false) }
                    val n = share.cancelQueued()
                    if (n > 0) _effects.send(PrivacyEffect.Toast("보낼 예정이던 요약 ${n}건은 보내지 않아요"))
                }
                else -> {
                    profiles.update { SettingsRules.withConsent(it, key, !on) }
                    if (!on && (key == ConsentKey.ACADEMY || key == ConsentKey.INSURER)) {
                        _effects.send(PrivacyEffect.Toast("연결할 곳이 생기면 그때 따로 물어볼게요"))
                    }
                }
            }
        }
    }

    /** [모든 연습 기록 지우기] → 확인 대화상자. */
    fun askClear() {
        dialog.value = PrivacyDialog.CLEAR
    }

    fun dismissDialog() {
        dialog.value = null
    }

    /** 확인: 기록·진행만 지움(코치·설정·내 코스·동의는 남김). */
    fun confirmClear() {
        dialog.value = null
        viewModelScope.launch {
            records.clear()
            profiles.update { SettingsRules.clearRecords(it) }
            _effects.send(PrivacyEffect.Toast("연습 기록을 모두 지웠어요"))
        }
    }

    /** 확인: 기록 저장 동의 철회(settings.js `withdraw`). */
    fun confirmWithdraw() {
        dialog.value = null
        viewModelScope.launch {
            val st = profiles.state.first()
            val id = st.activeProfileId
            drafts.drop(id)
            if (st.profiles.size <= 1) {
                profiles.reset()
            } else {
                profiles.updateState { s ->
                    val cur = s.profiles[id] ?: return@updateState s
                    s.copy(profiles = s.profiles + (id to SettingsRules.resetProfile(cur)), ui = UiMemory())
                }
            }
            _effects.send(PrivacyEffect.GoOnboarding)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
