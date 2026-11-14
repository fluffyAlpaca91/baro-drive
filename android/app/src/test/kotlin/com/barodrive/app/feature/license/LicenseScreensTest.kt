package com.barodrive.app.feature.license

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.feature.share.ShareCenter
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.AppState
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.LicenseInfo
import com.barodrive.core.model.LicenseResult
import com.barodrive.core.model.LicenseState
import com.barodrive.core.model.NextTask
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SeatState
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.VehicleSnapshot
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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

/**
 * LC-1·LC-2·RV-5·PR-1 면허 변형 (프로토타입 tests/unit/t8_screens.test.js의 화면 쪽 값 — 세션을 끝까지 돌리는 부분은 세션 시험의 몫).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LicenseScreensTest {
    @get:Rule
    val main = MainDispatcherRule()

    /** 2026-11-11 15:00(서울). */
    private val clock = Clock.fixed(Instant.parse("2026-11-11T06:00:00Z"), ZoneId.of("Asia/Seoul"))

    private fun TestScope.collect(flow: kotlinx.coroutines.flow.Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    // ---------------- PR-1 면허 변형

    @Test
    fun predriveSubtitleCourseNoteAndPassenger() {
        assertEquals("운전면허 연습 · C 코스 · 3구간", LicenseText.predriveSubtitle(PrepSession(mode = 8, courseId = "c8-C", noInMode = 1)))
        assertEquals("운전면허 연습 · A 코스 · 3구간", LicenseText.predriveSubtitle(PrepSession(mode = 8, courseId = "c8-A", noInMode = 2)))
        assertEquals("운전면허 연습 · 동네 병원", LicenseText.predriveSubtitle(PrepSession(mode = 8, placeId = "clinic", noInMode = 1)))
        assertEquals("코스 없이 연습", "운전면허 연습 · 3회차", LicenseText.predriveSubtitle(PrepSession(mode = 8, noInMode = 3)))
        assertEquals("3구간으로 나눠 안내해요", LicenseText.predriveCourseNote(PrepSession(mode = 8, courseId = "c8-D")))
        assertNull(LicenseText.predriveCourseNote(PrepSession(mode = 8, placeId = "clinic")))
        val on = requireNotNull(LicenseText.predrivePassenger(checked = true, seat = true))
        assertEquals(Triple("동승자 확인 끝", "시작 체크에서 확인했어요", true), Triple(on.label, on.sub, on.ok))
        val off = requireNotNull(LicenseText.predrivePassenger(checked = true, seat = false))
        assertEquals(Triple("조수석이 비어 있어요", "동승자가 타야 연습할 수 있어요", false), Triple(off.label, off.sub, off.ok))
        val blind = requireNotNull(LicenseText.predrivePassenger(checked = true, seat = null))
        assertEquals("동승자 확인 끝" to false, blind.label to blind.ok)
        assertTrue(blind.sub.contains("조수석 신호를 못 읽어요"))
        assertNull("시작 체크를 안 거친 준비 세션은 기본 카드", LicenseText.predrivePassenger(checked = false, seat = true))
    }

    // ---------------- LC-2

    @Test
    fun issueDatePreviewText() {
        val today = LocalDate.of(2026, 11, 11)
        assertEquals("2027. 3. 31.까지 · D-140", LicenseText.previewText(BaroEngine.license.status("2026-04-01", today)))
        assertEquals("", LicenseText.previewText(BaroEngine.license.status(null, today)))
        assertEquals("2026. 3. 31.까지 · 유효기간이 지났어요", LicenseText.previewText(BaroEngine.license.status("2025-04-01", today)))
    }

    @Test
    fun issueDateSheetStartsFromSavedDateAndSaves() = runTest {
        val store = InMemoryBaroStore(AppState(profiles = mapOf("p1" to Profile(license = LicenseInfo(issueDate = "2026-04-01")))), clock)
        val vm = IssueDateViewModel(store, clock)
        assertEquals(LocalDate.of(2026, 4, 1), vm.uiState.value.value)
        assertEquals("2027. 3. 31.까지 · D-140", vm.uiState.value.preview)
        vm.effects.test {
            vm.limit(atMax = true)
            assertEquals(IssueDateEffect.Toast("오늘보다 뒤 날짜는 고를 수 없어요"), awaitItem())
            vm.setDate(LocalDate.of(2026, 10, 1))
            vm.save()
            assertEquals(IssueDateEffect.Close, awaitItem())
        }
        assertEquals("2026-10-01", store.current().license.issueDate)
    }

    @Test
    fun issueDateSheetStartsTodayWhenExpiredOrMissing() = runTest {
        val store = InMemoryBaroStore(AppState(profiles = mapOf("p1" to Profile(license = LicenseInfo(issueDate = "2024-01-01")))), clock)
        assertEquals("지났으면 오늘에서", LocalDate.of(2026, 11, 11), IssueDateViewModel(store, clock).uiState.value.value)
        val empty = InMemoryBaroStore(AppState(), clock)
        assertEquals(LocalDate.of(2026, 11, 11), IssueDateViewModel(empty, clock).uiState.value.value)
    }

    // ---------------- LC-1

    @Test
    fun startCheckFollowsSeatAndChecks() = runTest {
        val store = InMemoryBaroStore(AppState(profiles = mapOf("p1" to Profile(license = LicenseInfo(issueDate = "2026-04-01")))), clock)
        val session = FakeSessionController()
        session.setState { it.copy(snapshot = VehicleSnapshot(t = 0.0, passenger = true)) }
        val vm = LicenseCheckViewModel(store, session, clock)
        collect(vm.uiState)
        var s = vm.uiState.value
        assertEquals(LicenseState.OK, s.status.state)
        assertEquals("D-140", s.status.dday)
        assertEquals(SeatState.ON, s.seat)
        assertFalse(s.ok)
        assertEquals("모두 체크하면 시작할 수 있어요", s.label)
        vm.effects.test {
            vm.start()
            assertEquals(LicenseCheckEffect.Toast("세 가지를 모두 체크해 주세요"), awaitItem())
            LicenseCheckItem.entries.forEach { vm.setCheck(it, true) }
            s = vm.uiState.value
            assertTrue(s.ok)
            assertEquals("시험장 주변 코스 고르기", s.label)
            vm.start()
            assertEquals(LicenseCheckEffect.GoCourses, awaitItem())
            // 조수석이 비면 막는다
            session.setState { it.copy(snapshot = VehicleSnapshot(t = 1.0, passenger = false)) }
            assertEquals(SeatState.OFF, vm.uiState.value.seat)
            assertEquals("동승자가 타야 시작할 수 있어요", vm.uiState.value.label)
            vm.start()
            assertEquals(LicenseCheckEffect.Toast("조수석에 동승자가 타야 시작할 수 있어요"), awaitItem())
        }
        vm.resetChecks()
        assertEquals("들어올 때마다 비움", 0, listOf(vm.uiState.value.checks.companion, vm.uiState.value.checks.sign, vm.uiState.value.checks.purpose).count { it })
    }

    @Test
    fun unreadableSeatBlocksAndExpiredShowsGuide() = runTest {
        val store = InMemoryBaroStore(AppState(profiles = mapOf("p1" to Profile(license = LicenseInfo(issueDate = "2025-01-01")))), clock)
        val vm = LicenseCheckViewModel(store, FakeSessionController(), clock)
        collect(vm.uiState)
        assertTrue(vm.uiState.value.expired)
        assertEquals(LicenseState.EXPIRED, vm.uiState.value.status.state)
        assertEquals("2025. 12. 31.", vm.uiState.value.status.expiryText)
        assertEquals("좌석을 못 읽음(신호 없음)", SeatState.UNKNOWN, vm.uiState.value.seat)
    }

    // ---------------- RV-5

    private fun rv5Record(f2: List<F2Check> = emptyList(), after: List<String>? = listOf("C")) = SessionRecord(
        id = "sess-l", mode = 8, noInMode = 1, courseId = "c8-C", durationSec = 900.0, distanceM = 5300.0, arrived = true,
        tasks = listOf(TaskResult("T-LIMIT", label = "제한속도 지키기", success = 5, total = 6)), fewEvents = false,
        nextTask = NextTask("T-SCHOOL", label = "어린이보호구역 30 지키기"),
        license = LicenseResult(courseId = "c8-C", letter = "C", test = true, segments = 3, reached = 3, complete = true, coursesDoneAfter = after),
        f2 = f2,
    )

    @Test
    fun reviewSubAndProgressAtThatSession() {
        val rec = rv5Record()
        assertEquals("면허 · C 코스 · 1회차 · 15분 · 5.3km", LicenseText.reviewSub(rec))
        val p = Profile(license = LicenseInfo(coursesDone = listOf("A", "B", "C")))
        val v = LicenseText.review(rec, p)
        assertEquals("기록에서 다시 열면 그 회차 기준", "4개 코스 중 1개", v.progress.text)
        assertEquals("시험장 주변 C 코스를 끝까지 달렸어요", v.course.text)
        assertEquals("4개 코스 중 3개", LicenseText.review(rv5Record(after = null), p).progress.text)
    }

    @Test
    fun rv5ViewModelSpeaksHeroOnceAndF2MistakeAddsHardBrakeScene() = runTest {
        val rec = rv5Record(f2 = listOf(F2Check(id = "f2-x", t = 100.0, v0 = 30.0, v1 = 0.0, dur = 1.2, maxDrop1s = 13.0, stopped = true)))
        val store = InMemoryBaroStore(AppState(profiles = mapOf("p1" to Profile(sessions = listOf(rec)))), clock)
        val voice = InMemoryVoiceLog()
        val share = ShareCenter(store, store, FakeSessionController(), clock, backgroundScope)
        val vm = LicenseReviewViewModel(SavedStateHandle(mapOf("recordId" to "sess-l", "readOnly" to false)), store, store, voice, share)
        collect(vm.uiState)
        val s = vm.uiState.value
        assertTrue(s.found)
        assertEquals("면허 · C 코스 · 1회차 · 15분 · 5.3km", s.sub)
        assertTrue(s.sections.heroKey.startsWith("rv.end."))
        assertEquals("어린이보호구역 30 지키기", s.sections.next)
        assertEquals(listOf(s.sections.heroKey), voice.entries.value.map { it.key })
        fun hardBrake() = vm.uiState.value.scenes.firstOrNull { it.id == "hardBrake" }?.n ?: 0
        val n0 = hardBrake()
        vm.chooseF2("f2-x", F2Choice.MISTAKE)
        assertEquals("'내 실수' → 급브레이크 +1", n0 + 1, hardBrake())
        vm.chooseF2("f2-x", F2Choice.AVOID)
        assertEquals("'위험 피하기'로 바꾸면 빠짐", n0, hardBrake())
    }
}
