package com.barodrive.app.feature.course

import androidx.lifecycle.SavedStateHandle
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.testing.FakeSignalSource
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.LicenseInfo
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.NaviPlace
import com.barodrive.core.model.ParkingSetup
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.PrepChecks
import com.barodrive.core.model.PrepLines
import com.barodrive.core.model.PrepWeather
import com.barodrive.core.model.Profile
import com.barodrive.core.model.RankedCourse
import com.barodrive.core.model.TurnDir
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 프로토타입 tests/unit/t2b_screens.test.js의 코스 추천 부분(패널 값·별·준비 세션·모드 칩) + CR-1 화면 흐름. */
@OptIn(ExperimentalCoroutinesApi::class)
class CourseRulesTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun fresh(): Profile = Profile.blank("p1")

    private fun Profile.atStage(n: Int): Profile = copy(progress = progress.copy(currentStage = n, unlockedStage = n))

    @Test
    fun inputsComeFromPanelProfileAndCaps() {
        val o = CourseRules.inputs(fresh(), town = "moved", congestionSeed = 3, tripOk = false)
        assertEquals("moved", o.town)
        assertEquals(3, o.congestionSeed)
        assertEquals(emptyList<MyCourse>(), o.myCourses)
        assertFalse(o.tripOk)
        assertEquals(emptyList<String>(), o.coursesDone)
        assertEquals(listOf("A", "B"), CourseRules.inputs(fresh().copy(license = LicenseInfo(coursesDone = listOf("A", "B")))).coursesDone)
    }

    @Test
    fun starSavesATeamCourseAndRemovesIt() {
        val b = RankedCourse(id = "c1-B", courseId = "c1-B", letter = "B", name = "시민체육관 주차장")
        val (p1, added) = CourseRules.toggleStar(fresh(), b, 1, "2026-11-10T09:12:00+09:00")
        assertTrue(added)
        assertEquals(1, p1.myCourses.size)
        val mc = p1.myCourses[0]
        assertEquals(listOf("mc-c1-B", "시민체육관 주차장", "1", "SAVED", "c1-B"), listOf(mc.id, mc.name, mc.mode.toString(), mc.source.name, mc.courseRef))
        assertTrue(mc.savedAt.orEmpty().startsWith("2026-11-10T09:12:00"))
        val (p2, added2) = CourseRules.toggleStar(p1, b.copy(mine = true, myCourseId = "mc-c1-B"), 1, "x")
        assertFalse(added2)
        assertTrue(p2.myCourses.isEmpty())
        // 장소에서 가져온 내 코스는 빼면 사라진다
        val clinic = fresh().copy(myCourses = listOf(MyCourse(id = "mc-clinic", mode = 2, name = "동네 병원", source = CourseSource.RECENT, placeId = "clinic", town = "moved")))
        val (p3, _) = CourseRules.toggleStar(clinic, RankedCourse(id = "mc-clinic", placeId = "clinic", mine = true, myCourseId = "mc-clinic"), 2, "x")
        assertTrue(p3.myCourses.isEmpty())
        // 같은 id가 이미 있으면 새 id
        val dup = fresh().copy(myCourses = listOf(MyCourse(id = "mc-c2-B", mode = 3, courseRef = "c3-A", name = "다른 줄")))
        val (p4, _) = CourseRules.toggleStar(dup, RankedCourse(id = "c2-B", courseId = "c2-B", letter = "B", name = "골목"), 2, "x")
        assertEquals(listOf("mc-c2-B", "mc-c2-B-2"), p4.myCourses.map { it.id })
    }

    @Test
    fun prepSessionHasThePlanShape() {
        val p = fresh().copy(sessionCount = fresh().sessionCount + (2 to 2))
        val b = RankedCourse(id = "c2-B", courseId = "c2-B", letter = "B")
        val prep = CourseRules.prepFor(2, b, p)
        assertEquals(2, prep.mode)
        assertEquals("c2-B", prep.courseId)
        assertNull(prep.placeId)
        assertNull(prep.presetId)
        assertEquals(3, prep.noInMode)
        assertEquals(PrepWeather(), prep.weather)
        assertEquals(PrepChecks(), prep.checks)
        assertTrue(prep.preEvents.isEmpty())
        assertEquals(PrepLines(), prep.lines)
        val none = CourseRules.prepFor(1, null, p)
        assertNull("코스 없이 연습(결정-85)", none.courseId)
        assertNull(none.placeId)
        assertEquals(1, none.noInMode)
        val place = CourseRules.prepFor(2, RankedCourse(id = "mc-clinic", placeId = "clinic", mine = true), p)
        assertNull(place.courseId)
        assertEquals("clinic", place.placeId)
        val park = ParkingSetup(ParkingType.PARALLEL, TurnDir.RIGHT)
        val ex = CourseRules.prepFor(6, null, p) { it.copy(parking = park, mode = 1) }
        assertEquals(park, ex.parking)
        assertEquals("모드는 덮어쓰지 않는다", 6, ex.mode)
    }

    @Test
    fun aNaviCourseCarriesItsCoordinatesToThePrepAndTheCard() {
        val home = NaviPlace(name = "우리 집", lat = 37.4401, lng = 127.1377, address = "성남시 수정구")
        val mc = MyCourse(id = "mc-navi", name = "우리 집", mode = 2, source = CourseSource.BOOKMARK, town = "home", navi = home)
        val p = fresh().atStage(2).copy(myCourses = listOf(mc))
        val res = CourseRules.rank(2, p)
        val row = res.list.single { it.navi != null }
        val prep = CourseRules.prepFor(2, row, p)
        assertEquals(home, prep.navi)
        assertNull(prep.courseId ?: prep.placeId)
        val card = CourseUiState.cards(res, row.id).single { it.id == "mc-navi" }
        assertTrue(card.navi && card.mine && card.selected)
        assertEquals("성남시 수정구", card.address)
        assertNull("혼잡을 모른다", card.congestion)
        assertFalse("팀 코스 카드는 그대로", CourseUiState.cards(res, null).first { it.id != "mc-navi" }.navi)
    }

    @Test
    fun modeChipsAndLockedStages() {
        assertEquals((1..8).toList(), CourseRules.visibleModes(7))
        assertEquals(mapOf(2 to 1, 3 to 2, 4 to 3), CourseRules.lockedModes(fresh()))
        assertEquals(mapOf(4 to 3), CourseRules.lockedModes(fresh().atStage(3)))
        assertEquals("잠긴 단계로 들어오면 지금 단계", 1, CourseRules.entryMode(3, fresh()))
        assertEquals(7, CourseRules.entryMode(7, fresh()))
        assertEquals("범위 밖이면 지금 단계", 2, CourseRules.entryMode(0, fresh().atStage(2)))
        assertEquals(ParkingSetup(ParkingType.BACK, TurnDir.LEFT), CourseViewModel.parkingOf("back", "L"))
        assertNull(CourseViewModel.parkingOf(null, "L"))
    }

    @Test
    fun loadsForPointEightSecondsThenStartsWithTheSelectedCourse() = runTest(main.dispatcher.scheduler) {
        val store = InMemoryBaroStore()
        store.update { it.atStage(2) }
        val session = FakeSessionController()
        val clock = Clock.fixed(ZonedDateTime.of(2026, 11, 10, 9, 12, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"))
        val vm = CourseViewModel(
            SavedStateHandle(mapOf(CourseViewModel.ARG_MODE to 2)), store, session, FakeSignalSource(), InMemoryVoiceLog(), CourseSelection(), clock,
        )
        runCurrent()
        assertEquals(2, vm.uiState.value.mode)
        assertEquals(CourseBodyState.LOADING, vm.uiState.value.body)
        advanceTimeBy(CourseViewModel.LOAD_MS + 1)
        val s = vm.uiState.value
        assertTrue("목록 또는 빈 상태: ${s.body}", s.body == CourseBodyState.LIST || s.body == CourseBodyState.EMPTY)
        if (s.body == CourseBodyState.LIST) {
            val pick = s.cards.last()
            vm.pick(pick.id)
            assertEquals(pick.id, vm.uiState.value.selected?.id)
            var ready = false
            vm.start { ready = true }
            runCurrent()
            assertTrue(ready)
            val prep = session.state.value.prep
            assertEquals(2, prep?.mode)
            assertEquals(pick.id, prep?.courseId)
        }
        vm.selectMode(5)
        assertEquals(CourseBodyState.AUTONOMOUS, vm.uiState.value.body)
        vm.selectMode(4)
        assertEquals("잠긴 단계는 고르지 않는다", 5, vm.uiState.value.mode)
    }
}
