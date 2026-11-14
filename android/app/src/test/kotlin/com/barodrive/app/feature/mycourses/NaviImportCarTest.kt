package com.barodrive.app.feature.mycourses

import androidx.lifecycle.SavedStateHandle
import com.barodrive.app.data.navi.NaviPlaceSource
import com.barodrive.app.data.navi.NaviPlaces
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.demo.FixedDemoPanel
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.NaviPlace
import com.barodrive.core.model.PlaceList
import com.barodrive.core.model.Profile
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** CR-2 진짜 차: 차 내비의 즐겨찾기·최근 목적지를 보이고 좌표째 내 코스로 저장한다(프로토타입에 없는 것 — 팀 장소와 같은 규칙). */
@OptIn(ExperimentalCoroutinesApi::class)
class NaviImportCarTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val home = NaviPlace(name = "우리 집", lat = 37.4401, lng = 127.1377, address = "성남시 수정구 시흥동", poiId = "p-home")
    private val office = NaviPlace(name = "회사", lat = 37.3947, lng = 127.1112, address = "성남시 분당구 판교역로")
    private val clinic = NaviPlace(name = "동네 병원", lat = 37.4412, lng = 127.1290, poiId = "p-clinic")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 11, 11, 15, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"))

    /** 탭마다 차 내비 답(또는 답 없음). */
    private class CarPlaces(private val byList: Map<PlaceList, NaviPlaces>) : NaviPlaceSource {
        val asked = mutableListOf<PlaceList>()

        override suspend fun load(list: PlaceList): NaviPlaces {
            asked += list
            return byList.getValue(list)
        }
    }

    @Test
    fun rulesSaveANaviPlaceWithItsCoordinates() {
        val p = Profile.blank("p1")
        val (p1, mc, err) = MyCoursesRules.addNaviPlace(p, home, PlaceList.BOOKMARK, 2, "home", "2026-11-11T15:00:00+09:00")
        assertNull(err)
        assertEquals("mc-navi", mc?.id)
        assertEquals(home, mc?.navi)
        assertEquals(CourseSource.BOOKMARK, mc?.source)
        assertEquals("우리 집", mc?.name)
        assertNull("팀 장소가 아니다", mc?.placeId ?: mc?.courseRef)
        assertEquals(MyCourseError.DUP, MyCoursesRules.addNaviPlace(p1, home, PlaceList.BOOKMARK, 2, "home", "x").third)
        assertEquals(MyCourseError.MODE, MyCoursesRules.addNaviPlace(p1, home, PlaceList.BOOKMARK, 5, "home", "x").third)
        val (p2, mc2, _) = MyCoursesRules.addNaviPlace(p1, office, PlaceList.RECENT, 2, "home", "x")
        assertEquals("같은 base면 -2", "mc-navi-2", mc2?.id)
        assertEquals(CourseSource.RECENT, mc2?.source)
        assertEquals(listOf(2), MyCoursesRules.savedNaviModes(p2, home.key, "home"))
        assertTrue("다른 동네", MyCoursesRules.savedNaviModes(p2, home.key, "moved").isEmpty())
        val item = MyCoursesRules.groups(p2).single().items.first { it.course.id == "mc-navi" }
        assertNull("거리는 모른다", item.km)
        assertEquals("즐겨찾기", item.sourceLabel)
        assertEquals("다른 동네에서 보면 그 코스의 동네 이름", "우리 동네", MyCoursesRules.groups(p2, town = "moved").single().items.first().townName)
    }

    @Test
    fun realCarListsTheCarNaviPlacesAndSavesThem() = runTest(main.dispatcher.scheduler) {
        val store = InMemoryBaroStore()
        val places = CarPlaces(mapOf(PlaceList.BOOKMARK to NaviPlaces.Car(listOf(home, office)), PlaceList.RECENT to NaviPlaces.Car(listOf(clinic))))
        val vm = NaviImportViewModel(SavedStateHandle(mapOf(NaviImportViewModel.ARG_MODE to 2)), store, clock, FixedDemoPanel, places)
        runCurrent()
        val s = vm.uiState.value
        assertTrue(s.loaded)
        assertFalse(s.naviFailed)
        assertEquals(listOf("우리 집", "회사"), s.rows.map { it.name })
        assertEquals(listOf(home.key, office.key), s.rows.map { it.id })
        assertEquals("성남시 수정구 시흥동", s.rows.first().addr)
        assertNull("거리는 모른다", s.rows.first().km)
        vm.toggle(home.key)
        var result: Pair<Int, MyCourseError?>? = null
        vm.save(home.key) { m, e -> result = m to e }
        runCurrent()
        assertEquals(2 to null, result)
        assertEquals(home, store.current().myCourses.single().navi)
        assertEquals(listOf(2), vm.uiState.value.rows.first { it.id == home.key }.savedModes)
        vm.selectTab(true)
        runCurrent()
        assertEquals(listOf(PlaceList.BOOKMARK, PlaceList.RECENT), places.asked)
        assertEquals(listOf("동네 병원"), vm.uiState.value.rows.map { it.name })
        vm.save(clinic.key) { _, _ -> }
        runCurrent()
        assertEquals(CourseSource.RECENT, store.current().myCourses.last().source)
    }

    @Test
    fun noAnswerFromTheCarNaviIsShownHonestly() = runTest(main.dispatcher.scheduler) {
        val places = CarPlaces(mapOf(PlaceList.BOOKMARK to NaviPlaces.Unavailable))
        val vm = NaviImportViewModel(SavedStateHandle(), InMemoryBaroStore(), clock, FixedDemoPanel, places)
        runCurrent()
        assertTrue(vm.uiState.value.loaded)
        assertTrue(vm.uiState.value.naviFailed)
        assertTrue(vm.uiState.value.rows.isEmpty())
    }

    @Test
    fun simulatorStillUsesTheTeamPlaces() = runTest(main.dispatcher.scheduler) {
        val vm = NaviImportViewModel(SavedStateHandle(), InMemoryBaroStore(), clock)
        runCurrent()
        assertTrue(vm.uiState.value.rows.any { it.id == "mart" && it.km == 12.4 })
        assertTrue("집은 지금 있는 곳", vm.uiState.value.rows.first { it.id == "home" }.here)
    }
}
