package com.barodrive.app.feature.onboarding

import com.barodrive.app.feature.coach.HonorificDraft
import com.barodrive.app.feature.coach.Honorifics
import com.barodrive.core.model.Coach
import com.barodrive.core.model.FeedbackPreset
import com.barodrive.core.model.LicenseAge
import com.barodrive.core.model.MonthlyDrives
import com.barodrive.core.model.Onboarding
import com.barodrive.core.model.OnboardingAnswers
import com.barodrive.core.model.Profile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 온보딩 중 고르는 값 (프로토타입 onboarding.js `draft`): 동의 · 두 답 · 고른 단계 · 코치 · 호칭. 저장은 ON-5에서 한 번에 한다(설계 5.1 4번).
 */
data class OnboardingDraft(
    val consentRecord: Boolean = false,
    val consentFamily: Boolean = false,
    val licenseAge: LicenseAge? = null,
    val monthlyDrives: MonthlyDrives? = null,
    /** 고른 시작 단계(ON-4). 아직 안 골랐으면 null — 추천 단계로 본다. */
    val stage: Int? = null,
    val coach: Coach = Coach.DAD,
    val honorific: HonorificDraft = HonorificDraft(),
) {
    /** 추천 단계(결정-34). */
    val recommended: Int get() = OnboardingRules.recommendStage(licenseAge, monthlyDrives)

    /** ON-4에서 보이는 고른 단계: 추천 이하만(결정-35), 아니면 추천 단계. */
    val chosenStage: Int get() = stage?.takeIf { it in 1..recommended } ?: recommended

    companion object {
        /** 새로 시작(onboarding.js `fresh`): 코치·호칭은 프로필 값에서(새 프로필이면 아빠/배우자·부르지 않기, 설계 5.1 3번). */
        fun fresh(profile: Profile?): OnboardingDraft = OnboardingDraft(
            coach = profile?.coach ?: Coach.DAD,
            honorific = HonorificDraft.of(profile?.honorific),
        )
    }
}

/** 온보딩 규칙 (onboarding.js `recommendStage`·`apply`, 결정-34·35, 설계 5.1 4번). 순수 함수. */
object OnboardingRules {
    /**
     * 추천 시작 단계(결정-34): 한 달 0번 → 1 · 1~3번 → 면허 1년 미만·연습면허면 1, 아니면 2 · 4~8번 → 면허 3년 넘으면 3, 아니면 2
     * · 9번 넘게 → 면허 1년 이상이면 3, 아니면 2. 4단계는 처음에 권하지 않는다. 답이 없으면 1.
     */
    fun recommendStage(licenseAge: LicenseAge?, monthlyDrives: MonthlyDrives?): Int = when (monthlyDrives) {
        MonthlyDrives.FEW -> if (licenseAge == LicenseAge.LEARNER || licenseAge == LicenseAge.UNDER_1) 1 else 2
        MonthlyDrives.SOME -> if (licenseAge == LicenseAge.OVER_3) 3 else 2
        MonthlyDrives.MANY -> if (licenseAge == LicenseAge.YEARS_1_3 || licenseAge == LicenseAge.OVER_3) 3 else 2
        MonthlyDrives.NONE, null -> 1
    }

    /**
     * ON-5 [이 코치로 시작] 저장(onboarding.js `apply`, 설계 5.1 4번): 완료 · 동의(기록 저장 필수 + 가족 선택) · 답 · 추천 단계 ·
     * 지금 단계와 열린 단계 = 고른 단계(추천 이하) · 코치 · 호칭 · 예시 자율 설정 2개(이미 있으면 그대로).
     */
    fun apply(p: Profile, d: OnboardingDraft, examples: List<FeedbackPreset>): Profile {
        val rec = recommendStage(d.licenseAge, d.monthlyDrives)
        val stage = maxOf(1, minOf(rec, d.stage?.takeIf { it != 0 } ?: rec))
        return p.copy(
            onboarding = Onboarding(done = true, answers = OnboardingAnswers(d.licenseAge, d.monthlyDrives), recommendedStage = rec),
            consent = p.consent.copy(record = true, family = d.consentFamily),
            coach = d.coach,
            honorific = Honorifics.normalize(d.honorific.current),
            progress = p.progress.copy(currentStage = stage, unlockedStage = stage),
            myPresets = p.myPresets.ifEmpty { examples },
        )
    }
}

/**
 * 고르는 중인 온보딩 값을 화면 사이에 두는 곳 (onboarding.js 파일 안 `draft` — 뒤로 갔다 와도 남고, 끝나면 비운다).
 * ON-1~ON-5 화면의 ViewModel이 같이 쓴다. 앱이 다시 켜지면 비어 있다(라우터가 온보딩 중 화면을 복원하지 않는 것과 같다).
 */
@Singleton
class OnboardingDraftStore @Inject constructor() {
    private val _draft = MutableStateFlow<OnboardingDraft?>(null)

    /** 지금 고르는 값. 아직 시작하지 않았으면 null. */
    val draft: StateFlow<OnboardingDraft?> = _draft.asStateFlow()

    /** 없으면 [profile]로 새로 시작한다. */
    fun ensure(profile: Profile?) {
        _draft.update { it ?: OnboardingDraft.fresh(profile) }
    }

    fun update(transform: (OnboardingDraft) -> OnboardingDraft) {
        _draft.update { transform(it ?: OnboardingDraft()) }
    }

    /** 온보딩이 끝나면 비운다(onboarding.js `resetDraft`). */
    fun reset() {
        _draft.value = null
    }
}
