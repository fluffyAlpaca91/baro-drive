package com.barodrive.app.feature.feedback

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** FB-1 음성 피드백 설정(모드별)에 그릴 값 (feedback.js FB-1 `paint`). */
data class FeedbackSettingsUiState(
    val loading: Boolean = true,
    val mode: Int = 1,
    /** '1 · 차량 감 익히기'. */
    val title: String = "",
    val talk: String = "",
    /** 모드 5에서만 '새로 만들기' 안내. */
    val extraNote: String? = null,
    val groups: List<FeedbackGroup> = emptyList(),
) {
    /** 5번에서만 [내 음성 피드백 설정](FB-2). */
    val showMyPresets: Boolean get() = mode == 5
}

/**
 * FB-1 음성 피드백 설정(모드별) (프로토타입 screens/feedback.js FB-1, 설계 4.2 FB-1, 목업 s10).
 * 모드 칩 기본값 = 들어올 때 넘긴 모드(없으면 지금 단계). 바꾸면 바로 저장(profile.feedback.overrides[모드]).
 * [기본값으로] → 확인 없이 되돌리고 토스트. 막힌 칸을 누르면 그 이유 토스트(결정-20).
 */
@HiltViewModel
class FeedbackSettingsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
) : ViewModel() {
    private val modeArg: StateFlow<Int> = savedStateHandle.getStateFlow(KEY_MODE, 0)
    private val _toasts = Channel<String>(Channel.BUFFERED)

    /** 띄울 토스트 글. */
    val toasts: Flow<String> = _toasts.receiveAsFlow()

    val uiState: StateFlow<FeedbackSettingsUiState> = combine(profiles.profile, modeArg) { p, m -> build(p, m) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FeedbackSettingsUiState())

    /** 모드 칩(목업 `.mchips`). 프로토타입은 같은 화면을 그 모드로 바꿔 연다(뒤로 스택은 그대로). */
    fun selectMode(mode: Int) {
        FeedbackRules.validMode(mode)?.let { savedStateHandle[KEY_MODE] = it }
    }

    /** 칸 고르기 — 바로 저장. */
    fun choose(item: String, value: Delivery) {
        viewModelScope.launch {
            profiles.update { p -> FeedbackRules.setOverride(p, modeOf(p), item, value) ?: p }
        }
    }

    /** 막힌 칸을 누름 → 이유 토스트. */
    fun blocked(row: FeedbackRow, value: Delivery) {
        _toasts.trySend(row.lockText(value))
    }

    /** [기본값으로]: 확인 없이 그 모드 사용자 변경을 지우고 토스트. */
    fun reset() {
        viewModelScope.launch {
            var mode = 1
            profiles.update { p ->
                mode = modeOf(p)
                FeedbackRules.resetMode(p, mode).first
            }
            _toasts.send(FeedbackRules.resetToast(mode))
        }
    }

    private fun modeOf(p: Profile): Int = FeedbackRules.validMode(modeArg.value) ?: FeedbackRules.currentStage(p)

    private fun build(p: Profile, arg: Int): FeedbackSettingsUiState {
        val m = FeedbackRules.validMode(arg) ?: FeedbackRules.currentStage(p)
        val rows = FeedbackRules.modeRows(m, p)
        return FeedbackSettingsUiState(
            loading = false, mode = rows.mode, title = rows.title, talk = rows.talk, extraNote = rows.extraNote, groups = rows.groups,
        )
    }

    companion object {
        /** 경로 인자 이름([FeedbackSettings.mode]). */
        const val KEY_MODE = "mode"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
