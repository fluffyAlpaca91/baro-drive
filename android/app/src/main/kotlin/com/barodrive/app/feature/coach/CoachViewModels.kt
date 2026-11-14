package com.barodrive.app.feature.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.setCoach
import com.barodrive.app.data.repo.setHonorific
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** CO-1 코치 고르기 화면 값. */
data class CoachUiState(
    val coach: Coach = Coach.DAD,
    val honorific: Honorific = Honorific(),
    /** 코치 줄 말풍선(co.preview, {T} = 40). */
    val previews: Map<Coach, String> = emptyMap(),
)

/**
 * CO-1 코치 고르기 (프로토타입 coach.js CO-1, 목업 s02, 설계 4.2 CO-1, 결정-21). 줄을 누르면 profile.coach를 바로 저장한다(토스트 없음).
 * [미리 듣기]는 그 코치의 '깜빡이 늦음' 문장을 정차 중 말로(코치를 바꾸지 않음).
 */
@HiltViewModel
class CoachViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val voice: VoiceLog,
) : ViewModel() {
    private val previews = CoachOrder.associateWith { CoachLines.previewText(it) }

    val uiState: StateFlow<CoachUiState> = profiles.profile
        .map { p -> CoachUiState(p.coach, p.honorific, previews) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CoachUiState(previews = previews))

    fun pick(c: Coach) {
        if (uiState.value.coach == c) return
        viewModelScope.launch { profiles.setCoach(c) }
    }

    /** [미리 듣기](coach.js `listen`): 그 코치의 '깜빡이 늦음' 문장을 그 코치 목소리로. 코치를 바꾸지는 않는다. */
    fun listen(coach: Coach) {
        voice.sayLine(CoachLines.PREVIEW_KEY, mapOf("T" to CoachLines.PREVIEW_T), category = Category.INFO, coach = coach)
    }
}

/** CO-2 호칭 정하기 시트 값. */
data class HonorificUiState(
    val coach: Coach = Coach.DAD,
    val draft: HonorificDraft = HonorificDraft(),
    /** 미리보기 문장(co.honor). */
    val preview: String = "",
)

/**
 * CO-2 호칭 정하기 (프로토타입 coach.js `openHonorificSheet`, 설계 4.2 CO-2): 칩 4개 · 직접 쓰기(8자) · 미리보기. [저장] → profile.honorific.
 * 직접 쓰기가 비어 있으면 [저장]이 막힌다.
 */
@HiltViewModel
class HonorificViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {
    private val draft = MutableStateFlow<HonorificDraft?>(null)

    val uiState: StateFlow<HonorificUiState> = combine(profiles.profile, draft) { p, d ->
        val cur = d ?: HonorificDraft.of(p.honorific)
        HonorificUiState(p.coach, cur, CoachLines.honorText(p.coach, cur.current))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HonorificUiState())

    private fun current(): HonorificDraft = draft.value ?: uiState.value.draft

    fun pick(t: HonorificType) {
        draft.value = current().copy(type = t)
    }

    fun setCustomText(text: String) {
        draft.value = current().copy(customText = text)
    }

    /** [저장]: 정리해서 저장하고 [onSaved](시트 닫기). 직접 쓰기가 비면 아무것도 안 한다. */
    fun save(onSaved: () -> Unit) {
        val d = current()
        if (!d.valid) return
        viewModelScope.launch {
            profiles.setHonorific(Honorifics.normalize(d.current))
            onSaved()
        }
    }
}
