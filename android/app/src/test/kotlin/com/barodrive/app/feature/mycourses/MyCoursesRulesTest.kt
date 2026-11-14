package com.barodrive.app.feature.mycourses

import androidx.lifecycle.SavedStateHandle
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.Profile
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 프로토타입 tests/unit/t9_settings.test.js의 내 코스 부분(CR-2 가져오기 · ST-3 모드 바꾸기·지우기·묶음). */
@OptIn(ExperimentalCoroutinesApi::class)
class MyCoursesRulesTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun fresh() = Profile.blank("p1")

    @Test
    fun placesAndImportModes() {
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8), MyCoursesRules.IMPORT_MODES)
        assertEquals(listOf("home", "company", "mart", "daycare", "testcenter"), MyCoursesRules.places(recent = false).map { it.id })
        assertEquals(listOf("gym", "clinic", "cafe"), MyCoursesRules.places(recent = true).map { it.id })
    }

    @Test
    fun importAPlace() {
        var p = fresh()
        val (p1, c, err) = MyCoursesRules.addPlace(p, "clinic", 2, "home", "2026-11-11T15:00:00+09:00")
        assertNull(err)
        assertEquals(MyCourse(id = "mc-clinic", name = "동네 병원", mode = 2, source = CourseSource.RECENT, placeId = "clinic", town = "home", savedAt = "2026-11-11T15:00:00+09:00"), c)
        p = p1
        assertEquals(MyCourseError.DUP, MyCoursesRules.addPlace(p, "clinic", 2, "home", "x").third)
        val (p2, c2, _) = MyCoursesRules.addPlace(p, "clinic", 3, "home", "x")
        assertEquals("같은 장소를 다른 모드로", "mc-clinic-2", c2?.id)
        val (p3, c3, _) = MyCoursesRules.addPlace(p2, "clinic", 2, "moved", "x")
        assertEquals("다른 동네는 따로", "moved", c3?.town)
        val (p4, c4, _) = MyCoursesRules.addPlace(p3, "mart", 7, "home", "x")
        assertEquals(CourseSource.BOOKMARK, c4?.source)
        assertEquals(MyCourseError.HERE, MyCoursesRules.addPlace(p4, "home", 2, "home", "x").third)
        assertEquals(MyCourseError.MODE, MyCoursesRules.addPlace(p4, "mart", 5, "home", "x").third)
        assertEquals(MyCourseError.PLACE, MyCoursesRules.addPlace(p4, "없는곳", 2, "home", "x").third)
        assertEquals(4, p4.myCourses.size)
        assertEquals("2단계 내 코스에 이미 있어요", MyCoursesRules.errorText(MyCourseError.DUP, 2))
        assertEquals("지금 있는 곳은 코스로 만들 수 없어요", MyCoursesRules.errorText(MyCourseError.HERE, null))
        assertEquals(8, MyCoursesRules.addPlace(fresh(), "testcenter", 8, "home", "x").second?.mode)
    }

    @Test
    fun changeModeRemoveAndGroups() {
        var p = fresh()
        p = MyCoursesRules.addPlace(p, "clinic", 2, "home", "x").first
        p = MyCoursesRules.addPlace(p, "clinic", 3, "home", "x").first
        p = p.copy(myCourses = p.myCourses + MyCourse(id = "mc-c2-B", name = "초등학교 뒤 골목", mode = 2, source = CourseSource.SAVED, courseRef = "c2-B"))
        p = MyCoursesRules.addPlace(p, "gym", 1, "moved", "x").first
        assertEquals("그 모드에 같은 장소가 이미 있음", MyCourseError.DUP, MyCoursesRules.setMode(p, "mc-clinic", 3).second)
        assertEquals(MyCourseError.SAME, MyCoursesRules.setMode(p, "mc-clinic", 2).second)
        assertEquals(MyCourseError.MODE, MyCoursesRules.setMode(p, "mc-clinic", 5).second)
        assertEquals(MyCourseError.MISSING, MyCoursesRules.setMode(p, "없음", 4).second)
        val (moved, e) = MyCoursesRules.setMode(p, "mc-c2-B", 4)
        assertNull("팀 코스도 다른 모드로 옮길 수 있음", e)
        p = moved
        val gs = MyCoursesRules.groups(p, "home")
        assertEquals(
            listOf(Triple(1, "1단계 · 차량 감 익히기", 1), Triple(2, "2단계 · 동네 골목 다니기", 1), Triple(3, "3단계 · 큰 도로 달리기", 1), Triple(4, "4단계 · 고속도로 타 보기", 1)),
            gs.map { Triple(it.mode, it.title, it.items.size) },
        )
        val first = gs[0].items[0]
        assertEquals("최근 목적지|이사 간 동네|2.6", "${first.sourceLabel}|${first.townName}|${first.km}")
        assertEquals("연습한 길", gs[3].items[0].sourceLabel)
        assertNull("지금 동네면 동네 이름 없음", gs[1].items[0].townName)
        val (p2, ok) = MyCoursesRules.remove(p, "mc-clinic")
        assertTrue(ok)
        assertEquals(false, MyCoursesRules.remove(p2, "mc-clinic").second)
        assertEquals(3, p2.myCourses.size)
        assertTrue("새 프로필 = 내 코스 없음(결정 T1c-Q1)", MyCoursesRules.groups(fresh()).isEmpty())
    }

    @Test
    fun naviImportSavesWithThePickedMode() = runTest(main.dispatcher.scheduler) {
        val store = InMemoryBaroStore()
        val clock = Clock.fixed(ZonedDateTime.of(2026, 11, 11, 15, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"))
        val vm = NaviImportViewModel(SavedStateHandle(mapOf(NaviImportViewModel.ARG_MODE to 2, NaviImportViewModel.ARG_TAB to NaviImport.TAB_RECENT)), store, clock)
        runCurrent()
        assertTrue(vm.uiState.value.recent)
        assertEquals(2, vm.uiState.value.mode)
        vm.toggle("clinic")
        assertTrue(vm.uiState.value.rows.first { it.id == "clinic" }.open)
        var result: Pair<Int, MyCourseError?>? = null
        vm.save("clinic") { m, e -> result = m to e }
        runCurrent()
        assertEquals(2 to null, result)
        assertEquals(listOf(2), vm.uiState.value.rows.first { it.id == "clinic" }.savedModes)
        assertEquals("mc-clinic", store.current().myCourses.single().id)
    }
}
