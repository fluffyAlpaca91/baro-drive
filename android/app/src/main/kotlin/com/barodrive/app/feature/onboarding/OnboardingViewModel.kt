package com.barodrive.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.feature.coach.CoachLines
import com.barodrive.app.feature.coach.CoachOrder
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.LicenseAge
import com.barodrive.core.model.MonthlyDrives
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** ON-1~ON-5 화면 값 (프로토타입 onboarding.js 화면들이 draft에서 읽는 것). */
data class OnboardingUiState(
    val draft: OnboardingDraft = OnboardingDraft(),
    /** 코치 줄 말풍선(co.preview, {T} = 40) — 코치마다. */
    val coachPreviews: Map<Coach, String> = emptyMap(),
    /** 호칭 미리보기 문장(co.honor, 지금 고른 코치·호칭). */
    val honorPreview: String = "",
) {
    /** ON-2 [동의하고 계속]: 기록 저장(필수)에 동의했을 때만. */
    val canConsent: Boolean get() = draft.consentRecord

    /** ON-3 [다음]: 두 질문에 모두 답했을 때만. */
    val canAnswer: Boolean get() = draft.licenseAge != null && draft.monthlyDrives != null

    /** ON-5 [이 코치로 시작]: 직접 쓰기면 한 글자 이상. */
    val canFinish: Boolean get() = draft.honorific.valid
}

/**
 * 온보딩 ON-1~ON-5 (프로토타입 onboarding.js, 설계 4.2 ON-1~5·5.1). 다섯 화면이 같은 고르는 값([OnboardingDraftStore])을 쓴다.
 * 저장은 [finish] 한 번(ON-5) — 그 전에는 프로필에 쓰지 않는다.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val drafts: OnboardingDraftStore,
    private val profiles: ProfileRepository,
    private val voice: VoiceLog,
) : ViewModel() {
    private val previews: Map<Coach, String> = CoachOrder.associateWith { CoachLines.previewText(it) }

    val uiState: StateFlow<OnboardingUiState> = drafts.draft
        .map { d ->
            val draft = d ?: OnboardingDraft()
            OnboardingUiState(
                draft = draft,
                coachPreviews = previews,
                honorPreview = CoachLines.honorText(draft.coach, draft.honorific.current),
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingUiState(coachPreviews = previews))

    init {
        if (drafts.draft.value == null) viewModelScope.launch { drafts.ensure(profiles.current()) }
    }

    fun setConsentRecord(on: Boolean) = drafts.update { it.copy(consentRecord = on) }

    fun setConsentFamily(on: Boolean) = drafts.update { it.copy(consentFamily = on) }

    fun setLicenseAge(v: LicenseAge) = drafts.update { it.copy(licenseAge = v) }

    fun setMonthlyDrives(v: MonthlyDrives) = drafts.update { it.copy(monthlyDrives = v) }

    /** ON-3 [다음]: 추천 단계를 처음 선택으로(onboarding.js `d.stage = recommendStage(d.answers)`). */
    fun confirmAnswers() = drafts.update { it.copy(stage = it.recommended) }

    /** ON-4 단계 카드. 추천보다 높은 단계는 잠김이라 부르는 쪽이 토스트만 띄운다. */
    fun pickStage(n: Int) = drafts.update { if (n in 1..it.recommended) it.copy(stage = n) else it }

    fun pickCoach(c: Coach) = drafts.update { it.copy(coach = c) }

    fun pickHonorific(t: HonorificType) = drafts.update { it.copy(honorific = it.honorific.copy(type = t)) }

    fun setCustomText(text: String) = drafts.update { it.copy(honorific = it.honorific.copy(customText = text)) }

    /** [미리 듣기](coach.js `listen`): 그 코치의 '깜빡이 늦음' 문장을 그 코치 목소리로, 정차 중 말로(음성 자막·기록). 코치를 바꾸지는 않는다. */
    fun listen(coach: Coach) {
        voice.sayLine(CoachLines.PREVIEW_KEY, mapOf("T" to CoachLines.PREVIEW_T), category = Category.INFO, coach = coach)
    }

    /** ON-5 [이 코치로 시작]: 한 번에 저장하고(설계 5.1 4번) 고르던 값을 비운 뒤 [onDone](홈으로). */
    fun finish(onDone: () -> Unit) {
        val d = drafts.draft.value ?: return
        if (!d.honorific.valid) return
        viewModelScope.launch {
            profiles.update { OnboardingRules.apply(it, d, BaroEngine.examplePresets()) }
            drafts.reset()
            onDone()
        }
    }
}
