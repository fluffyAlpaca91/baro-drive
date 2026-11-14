package com.barodrive.app.feature.parking

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.data.repo.setParkingDone
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ParkingProgress
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** PK-1 (프로토타입 parking-select.js `paint`·`lastKey`, 시험 t5a 화면 등록·e2e t5a-parking 머리 부제·완료 체크·내 차). */
class ParkingSelectViewModelTest {
    @get:org.junit.Rule
    val main = MainDispatcherRule()

    private val res = XmlTextRes

    @Before
    fun reset() {
        ParkingChoiceMemory.lastKey = ParkingSelectUiState.DEFAULT_KEY
    }

    @Test
    fun keysParseLikeTheSimulator() {
        assertEquals(ParkingSpotChoice("front", "L"), parseKey("front-L"))
        assertNull(parseKey("nope-R"))
        assertNull(parseKey("parallel-X"))
        assertEquals(6, PARKING_ORDER.size)
        assertEquals("평행 · 왼쪽", parkingLabel(PARKING_ORDER[0].first, PARKING_ORDER[0].second, res))
        assertEquals("전방 · 오른쪽", parkingLabel(PARKING_ORDER[5].first, PARKING_ORDER[5].second, res))
    }

    @Test
    fun routeChoiceIsRememberedForTheNextVisit() {
        assertEquals("parallel-R", ParkingSelectViewModel.initialKey(null, null))
        assertEquals("back-L", ParkingSelectViewModel.initialKey("back", "L"))
        assertEquals("다음에 인자 없이 열어도 지난번 칸", "back-L", ParkingSelectViewModel.initialKey(null, null))
        assertEquals("모르는 종류는 평행으로 정리(sim key)", "parallel-R", ParkingSelectViewModel.initialKey("nope", "R"))
    }

    @Test
    fun subtitleCountsVirtualSuccessesAndTheCarTagFollowsTheModel() {
        val none = ParkingSelectViewModel.stateOf("parallel-R", Profile(), DrivingState())
        assertEquals("6가지 중 하나를 골라요", none.sub(res))
        assertTrue(none.steerReadable)
        val two = ParkingSelectViewModel.stateOf(
            "back-L",
            Profile(parking = ParkingProgress(mapOf("parallel-R" to true, "back-L" to true, "front-R" to false))),
            DrivingState(snapshot = VehicleSnapshot(t = 0.0, gear = Gear.P, steer = null, model = "grandeur")),
        )
        assertEquals("6가지 중 2가지 해 봤어요", two.sub(res))
        assertEquals(setOf("parallel-R", "back-L"), two.done)
        assertFalse("핸들을 못 읽으면 세워 두고 연습을 막는다", two.steerReadable)
        assertEquals("내 차: 그랜저", res.text(com.barodrive.app.R.string.pk1_my_car, res.text(carNameRes(two.model))))
        assertEquals("아반떼", res.text(carNameRes(null)))
        assertEquals(ParkingSpotChoice("back", "L"), two.choice)
    }

    @Test
    fun choosingUpdatesTheStateAndTheMemory() = runTest {
        val store = InMemoryBaroStore()
        store.setParkingDone("front-L")
        val vm = ParkingSelectViewModel(SavedStateHandle(mapOf("type" to "front", "side" to "R")), FakeSessionController(), store)
        vm.uiState.test {
            var s = awaitItem()
            while (s.done.isEmpty()) s = awaitItem()
            assertEquals("front-R", s.selected)
            assertEquals(1, s.doneCount)
            vm.choose("back-R")
            assertEquals("back-R", awaitItem().selected)
            vm.choose("bad")
            expectNoEvents()
        }
        assertEquals("back-R", ParkingChoiceMemory.lastKey)
    }
}
