package com.barodrive.app.feature.onboarding

import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.feature.coach.HonorificDraft
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.Coach
import com.barodrive.core.model.FeedbackPreset
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.LicenseAge
import com.barodrive.core.model.MonthlyDrives
import com.barodrive.core.model.Profile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 프로토타입 tests/unit/t2b_screens.test.js의 온보딩 부분(추천 단계 표 결정-34, 저장 설계 5.1 4번) + 화면 값. */
class OnboardingRulesTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun recommendStageFollowsTheSixteenCellTable() {
        val lic = listOf(LicenseAge.LEARNER, LicenseAge.UNDER_1, LicenseAge.YEARS_1_3, LicenseAge.OVER_3)
        val want = mapOf(
            MonthlyDrives.NONE to listOf(1, 1, 1, 1),
            MonthlyDrives.FEW to listOf(1, 1, 2, 2),
            MonthlyDrives.SOME to listOf(2, 2, 2, 3),
            MonthlyDrives.MANY to listOf(2, 2, 3, 3),
        )
        for ((n, row) in want) assertEquals("한 달 $n", row, lic.map { OnboardingRules.recommendStage(it, n) })
        assertEquals(1, OnboardingRules.recommendStage(LicenseAge.UNDER_1, MonthlyDrives.NONE))
        assertEquals(2, OnboardingRules.recommendStage(LicenseAge.OVER_3, MonthlyDrives.FEW))
        assertEquals(3, OnboardingRules.recommendStage(LicenseAge.YEARS_1_3, MonthlyDrives.MANY))
        assertEquals("답이 없으면 1단계", 1, OnboardingRules.recommendStage(null, null))
        assertEquals(listOf("연습면허예요", "1년이 안 됐어요", "1~3년 됐어요", "3년 넘었어요"), LicenseAge.entries.map { it.label })
        assertEquals(listOf("안 했어요", "1~3번", "4~8번", "9번 넘게"), MonthlyDrives.entries.map { it.label })
    }

    @Test
    fun applySavesEverythingAtOnce() {
        val examples = examples()
        val p = OnboardingRules.apply(
            Profile.blank("p1"),
            OnboardingDraft(
                consentRecord = true, consentFamily = true, licenseAge = LicenseAge.OVER_3, monthlyDrives = MonthlyDrives.SOME,
                stage = 2, coach = Coach.MOM, honorific = HonorificDraft(HonorificType.CUSTOM, " 지은아 "),
            ),
            examples,
        )
        assertTrue(p.onboarding.done)
        assertEquals(LicenseAge.OVER_3, p.onboarding.answers.licenseAge)
        assertEquals(MonthlyDrives.SOME, p.onboarding.answers.monthlyDrives)
        assertEquals(3, p.onboarding.recommendedStage)
        assertTrue(p.consent.record)
        assertTrue(p.consent.family)
        assertFalse("학원·보험사 동의는 설정에서(결정-45)", p.consent.academy)
        assertEquals("가족", p.consent.familyName)
        assertEquals(Coach.MOM, p.coach)
        assertEquals(Honorific(HonorificType.CUSTOM, "지은아"), p.honorific)
        assertEquals("추천보다 낮은 단계를 고를 수 있다", 2, p.progress.currentStage)
        assertEquals("고른 단계까지 열린다", 2, p.progress.unlockedStage)
        assertEquals(listOf("ex-commute", "ex-rain"), p.myPresets.map { it.id })
        assertTrue("새 프로필에 예시 내 코스는 넣지 않는다(결정 T1c-Q1)", p.myCourses.isEmpty())

        val q = OnboardingRules.apply(
            Profile.blank("p1"),
            OnboardingDraft(
                consentRecord = true, licenseAge = LicenseAge.UNDER_1, monthlyDrives = MonthlyDrives.MANY, stage = 4,
                honorific = HonorificDraft(HonorificType.CUSTOM, ""),
            ),
            examples,
        )
        assertEquals("추천(2)보다 높은 단계는 고를 수 없다(결정-35)", 2, q.progress.currentStage)
        assertEquals(Honorific(), q.honorific)
        assertFalse(q.consent.family)

        val r = OnboardingRules.apply(
            Profile.blank("p1").copy(myPresets = listOf(FeedbackPreset(id = "mine", name = "주말 장보기"))),
            OnboardingDraft(
                consentRecord = true, licenseAge = LicenseAge.LEARNER, monthlyDrives = MonthlyDrives.NONE, stage = null,
                coach = Coach.SIB, honorific = HonorificDraft(HonorificType.SON),
            ),
            examples,
        )
        assertEquals("이미 있는 자율 설정은 그대로", listOf("mine"), r.myPresets.map { it.id })
        assertEquals(1, r.progress.currentStage)
        assertEquals(Honorific(HonorificType.SON, ""), r.honorific)
    }

    @Test
    fun chosenStageStaysAtOrBelowTheRecommendation() {
        val d = OnboardingDraft(licenseAge = LicenseAge.OVER_3, monthlyDrives = MonthlyDrives.SOME)
        assertEquals(3, d.recommended)
        assertEquals("고르지 않았으면 추천 단계", 3, d.chosenStage)
        assertEquals(2, d.copy(stage = 2).chosenStage)
        assertEquals("추천보다 높으면 추천 단계로(onboarding.js ON-4)", 3, d.copy(stage = 4).chosenStage)
    }

    @Test
    fun viewModelSavesOnlyOnFinishAndClearsTheDraft() = runTest {
        val store = InMemoryBaroStore()
        val drafts = OnboardingDraftStore()
        val vm = OnboardingViewModel(drafts, store, InMemoryVoiceLog())
        assertEquals(Coach.DAD, vm.uiState.value.draft.coach)
        assertFalse(vm.uiState.value.canConsent)
        vm.setConsentRecord(true)
        vm.setLicenseAge(LicenseAge.YEARS_1_3)
        assertFalse(vm.uiState.value.canAnswer)
        vm.setMonthlyDrives(MonthlyDrives.MANY)
        assertTrue(vm.uiState.value.canAnswer)
        vm.confirmAnswers()
        assertEquals(3, vm.uiState.value.draft.stage)
        vm.pickStage(4)
        assertEquals("잠긴 단계는 고르지 않는다", 3, vm.uiState.value.draft.stage)
        vm.pickStage(1)
        vm.pickCoach(Coach.SIB)
        vm.pickHonorific(HonorificType.CUSTOM)
        assertFalse("빈 직접 쓰기는 막힘", vm.uiState.value.canFinish)
        vm.setCustomText("민수야")
        assertTrue(vm.uiState.value.canFinish)
        assertFalse("ON-5 전에는 저장하지 않는다", store.current().onboarding.done)

        var done = false
        vm.finish { done = true }
        assertTrue(done)
        val p = store.current()
        assertTrue(p.onboarding.done)
        assertEquals(1, p.progress.currentStage)
        assertEquals(Coach.SIB, p.coach)
        assertEquals(Honorific(HonorificType.CUSTOM, "민수야"), p.honorific)
        assertNull("끝나면 고르던 값을 비운다", drafts.draft.value)
    }

    private fun examples(): List<FeedbackPreset> = BaroEngine.examplePresets()
}
