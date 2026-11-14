package com.barodrive.app.feature.settings

import app.cash.turbine.test
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.feature.feedback.PresetDrafts
import com.barodrive.app.feature.history.HistoryFilterMemory
import com.barodrive.app.feature.history.HistoryViewModel
import com.barodrive.app.feature.share.ShareCenter
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.AppState
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Consent
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.FeedbackSettings
import com.barodrive.core.model.FirstMetrics
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.LicenseInfo
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.Onboarding
import com.barodrive.core.model.ParkingProgress
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.ShareStatus
import com.barodrive.core.model.StageProgress
import com.barodrive.core.model.StageState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 설정·공유와 개인정보·연습 기록 화면 (프로토타입 tests/unit/t9_settings.test.js '설정' 묶음 + 화면 동작). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-11-11T06:00:00Z"), ZoneId.of("Asia/Seoul"))

    private fun TestScope.collect(flow: kotlinx.coroutines.flow.Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    @Test
    fun st1RowsValuesAndStaticCarRows() {
        val p = Profile(
            honorific = Honorific(HonorificType.DAUGHTER), myPresets = BaroEngine.examplePresets(),
            sessions = listOf(SessionRecord(id = "sess-a", mode = 2)),
        )
        val rs = SettingsRules.rows(p, "아반떼")
        assertEquals(listOf("coach", "honorific", "feedback", "presets", "my-courses", "history", "privacy", "car", "driver", "about"), rs.map { it.id })
        val by = rs.associateBy { it.id }
        assertEquals("아빠/배우자", by.getValue("coach").value)
        assertEquals(SettingsTarget.CoachPicker, by.getValue("coach").target)
        assertEquals("우리 딸", by.getValue("honorific").value)
        assertEquals(SettingsTarget.Honorific, by.getValue("honorific").target)
        assertEquals(SettingsTarget.Feedback(1), by.getValue("feedback").target)
        assertEquals("설정에서 연 FB-2는 관리만", SettingsTarget.Presets, by.getValue("presets").target)
        assertEquals("설정 2개", by.getValue("presets").value)
        assertEquals("1회차", by.getValue("history").value)
        assertEquals("0개", by.getValue("my-courses").value)
        assertEquals("가족 공유 끔", by.getValue("privacy").value)
        assertEquals("아반떼", by.getValue("car").value)
        assertNull(by.getValue("car").target)
        assertEquals("운전자 1", by.getValue("driver").value)
        assertNull(by.getValue("driver").target)
        assertEquals("투산", SettingsRules.modelName("tucson"))
        assertEquals("아반떼", SettingsRules.modelName(null))
        assertEquals("지지", SettingsRules.honorificLabel(Honorific(HonorificType.CUSTOM, "지지")))
    }

    @Test
    fun recipientNameAndConsentRows() {
        assertEquals("우리 아버지", SettingsRules.cleanName("  우리   아버지  "))
        assertEquals("가나다라마바사아자차", SettingsRules.cleanName("가나다라마바사아자차카타"))
        assertEquals("가족", SettingsRules.familyName(Profile(consent = Consent(familyName = "   "))))
        assertEquals("아버지", SettingsRules.familyName(Profile(consent = Consent(familyName = "아버지"))))
        assertEquals("가족", SettingsRules.familyName(Profile()))
        assertEquals(
            listOf(Triple(ConsentKey.RECORD, false, true), Triple(ConsentKey.FAMILY, false, false), Triple(ConsentKey.ACADEMY, false, false), Triple(ConsentKey.INSURER, false, false)),
            SettingsRules.consentRows(Profile()).map { Triple(it.key, it.on, it.required) },
        )
        assertTrue(SettingsRules.consentRows(Profile())[2].sub.contains("연결할 곳이 생기면 그때 따로 동의를 받아요"))
    }

    @Test
    fun clearRecordsKeepsSettingsAndRewindsProgress() {
        val p = Profile(
            onboarding = Onboarding(done = true, recommendedStage = 2), coach = Coach.MOM, honorific = Honorific(HonorificType.SON),
            consent = Consent(record = true, family = true, familyName = "아버지"), feedback = FeedbackSettings(mapOf(2 to mapOf("G1" to Delivery.OFF))),
            myPresets = BaroEngine.examplePresets(), lastPresetId = "ex-commute",
            myCourses = listOf(MyCourse(id = "mc-c2-B", name = "초등학교 뒤 골목", mode = 2, courseRef = "c2-B")),
            progress = StageProgress(currentStage = 3, unlockedStage = 3, stages = mapOf(1 to StageState(), 2 to StageState(2, "x"), 3 to StageState(1), 4 to StageState())),
            sessionCount = mapOf(1 to 0, 2 to 4, 3 to 1, 4 to 0, 5 to 2, 6 to 1, 7 to 0, 8 to 1), fading = mapOf(2 to mapOf("B1" to FadeState(2, true))),
            g1Baseline = 1.1, firstMetrics = FirstMetrics(c2Avg = 38.0), parking = ParkingProgress(mapOf("parallel-R" to true)),
            license = LicenseInfo(issueDate = "2026-04-01", coursesDone = listOf("A", "B")), sessions = listOf(SessionRecord(id = "sess-a", mode = 2)),
            shareQueue = listOf("sess-a"),
        )
        val c = SettingsRules.clearRecords(p)
        assertEquals(listOf(p.coach, p.honorific, p.consent, p.feedback, p.myPresets, p.lastPresetId, p.myCourses, p.onboarding), listOf(c.coach, c.honorific, c.consent, c.feedback, c.myPresets, c.lastPresetId, c.myCourses, c.onboarding))
        assertEquals(emptyList<SessionRecord>(), c.sessions)
        assertEquals(emptyList<String>(), c.shareQueue)
        assertEquals("진행은 온보딩 직후(처음 추천 단계)로", 2 to 2, c.progress.currentStage to c.progress.unlockedStage)
        assertEquals(StageState(), c.progress.stages[2])
        assertNull(c.progress.graduatedAt)
        assertEquals((1..8).associateWith { 0 }, c.sessionCount)
        assertEquals(emptyMap<Int, Map<String, FadeState>>(), c.fading)
        assertNull(c.g1Baseline)
        assertNull(c.firstMetrics)
        assertEquals(emptyMap<String, Boolean>(), c.parking.done)
        assertEquals(LicenseInfo(issueDate = "2026-04-01", coursesDone = emptyList()), c.license)
        assertEquals(1, SettingsRules.restartStage(Profile(onboarding = Onboarding(recommendedStage = 3), progress = StageProgress(unlockedStage = 1))))
        assertEquals(1, SettingsRules.restartStage(Profile()))
    }

    private fun privacyVm(store: InMemoryBaroStore, scope: TestScope, drafts: PresetDrafts = PresetDrafts()) =
        PrivacyViewModel(store, store, ShareCenter(store, store, FakeSessionController(), clock, scope.backgroundScope), drafts)

    @Test
    fun turningFamilyOffCancelsQueuedSummaries() = runTest {
        val rec = SessionRecord(id = "sess-a", mode = 2, share = ShareState(ShareStatus.QUEUED, to = "가족"))
        val store = InMemoryBaroStore(AppState(profiles = mapOf("p1" to Profile(consent = Consent(record = true, family = true), sessions = listOf(rec), shareQueue = listOf("sess-a")))), clock)
        val vm = privacyVm(store, this)
        collect(vm.uiState)
        vm.effects.test {
            vm.toggle(ConsentKey.FAMILY)
            assertEquals(PrivacyEffect.Toast("보낼 예정이던 요약 1건은 보내지 않아요"), awaitItem())
            vm.toggle(ConsentKey.ACADEMY)
            assertEquals(PrivacyEffect.Toast("연결할 곳이 생기면 그때 따로 물어볼게요"), awaitItem())
        }
        val p = store.current()
        assertFalse(p.consent.family)
        assertTrue(p.consent.academy)
        assertEquals(emptyList<String>(), p.shareQueue)
        assertEquals(ShareStatus.NONE, store.get("sess-a")?.share?.status)
    }

    @Test
    fun recipientNameSavesTrimmedAndFallsBackToFamily() = runTest {
        val store = InMemoryBaroStore(AppState(), clock)
        val vm = privacyVm(store, this)
        assertEquals("가족", vm.nameText)
        vm.setName("  아버지 ")
        assertEquals("아버지", store.current().consent.familyName)
        vm.setName("")
        assertEquals("가족", store.current().consent.familyName)
        vm.nameBlur()
        assertEquals("가족", vm.nameText)
    }

    @Test
    fun withdrawingResetsTheOnlyProfileOrJustThisOne() = runTest {
        val one = InMemoryBaroStore(AppState(profiles = mapOf("p1" to Profile(onboarding = Onboarding(done = true), consent = Consent(record = true), sessions = listOf(SessionRecord(id = "s", mode = 2))))), clock)
        val vm = privacyVm(one, this)
        collect(vm.uiState)
        vm.toggle(ConsentKey.RECORD)
        assertEquals(PrivacyDialog.WITHDRAW, vm.uiState.value.dialog)
        vm.effects.test {
            vm.confirmWithdraw()
            assertEquals(PrivacyEffect.GoOnboarding, awaitItem())
        }
        assertFalse(one.current().onboarding.done)
        assertEquals(emptyList<SessionRecord>(), one.current().sessions)

        val two = InMemoryBaroStore(
            AppState(
                activeProfileId = "p1",
                profiles = mapOf(
                    "p1" to Profile(id = "p1", name = "운전자 1", onboarding = Onboarding(done = true), sessions = listOf(SessionRecord(id = "s1", mode = 2))),
                    "p2" to Profile(id = "p2", name = "운전자 2", onboarding = Onboarding(done = true), coach = Coach.MOM, sessions = listOf(SessionRecord(id = "sess-p2", mode = 2))),
                ),
            ),
            clock,
        )
        val vm2 = privacyVm(two, this)
        vm2.confirmWithdraw()
        val st = two.snapshot.value
        assertFalse(st.profiles.getValue("p1").onboarding.done)
        assertEquals(emptyList<SessionRecord>(), st.profiles.getValue("p1").sessions)
        assertEquals("운전자 1", st.profiles.getValue("p1").name)
        assertTrue("다른 운전자 프로필은 남김", st.profiles.getValue("p2").onboarding.done)
        assertEquals(listOf("sess-p2"), st.profiles.getValue("p2").sessions.map { it.id })
    }

    @Test
    fun clearAllRecordsFromPrivacy() = runTest {
        val store = InMemoryBaroStore(
            AppState(profiles = mapOf("p1" to Profile(coach = Coach.SIB, progress = StageProgress(currentStage = 2, unlockedStage = 2), sessions = listOf(SessionRecord(id = "a", mode = 1))))),
            clock,
        )
        val vm = privacyVm(store, this)
        collect(vm.uiState)
        vm.askClear()
        assertEquals(PrivacyDialog.CLEAR, vm.uiState.value.dialog)
        vm.effects.test {
            vm.confirmClear()
            assertEquals(PrivacyEffect.Toast("연습 기록을 모두 지웠어요"), awaitItem())
        }
        assertEquals(emptyList<SessionRecord>(), store.current().sessions)
        assertEquals(1, store.current().progress.currentStage)
        assertEquals(Coach.SIB, store.current().coach)
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    fun historyFilterIsRememberedAndDeleteAsksFirst() = runTest {
        val recs = listOf(SessionRecord(id = "a", mode = 2, noInMode = 1), SessionRecord(id = "b", mode = 5, noInMode = 1))
        val store = InMemoryBaroStore(AppState(profiles = mapOf("p1" to Profile(sessions = recs))), clock)
        val memory = HistoryFilterMemory()
        val vm = HistoryViewModel(androidx.lifecycle.SavedStateHandle(mapOf("filter" to "")), store, store, memory, clock)
        collect(vm.uiState)
        assertEquals("all", vm.uiState.value.selected)
        assertEquals(listOf("a", "b"), vm.uiState.value.rows.map { it.id })
        vm.pick("free")
        assertEquals(listOf("b"), vm.uiState.value.rows.map { it.id })
        assertEquals("고른 묶음 기억", "free", memory.last)
        val again = HistoryViewModel(androidx.lifecycle.SavedStateHandle(mapOf("filter" to "")), store, store, memory, clock)
        collect(again.uiState)
        assertEquals("free", again.uiState.value.selected)
        vm.pick("license")
        assertEquals(com.barodrive.app.feature.history.HistoryEmpty.FILTER, vm.uiState.value.empty)
        vm.pick("all")
        vm.askDelete(vm.uiState.value.rows.first())
        assertEquals("a", vm.uiState.value.confirm?.id)
        vm.toasts.test {
            vm.confirmDelete()
            assertEquals("기록을 지웠어요", awaitItem())
        }
        assertEquals(listOf("b"), store.current().sessions.map { it.id })
        assertNull(vm.uiState.value.confirm)
    }
}
