package com.barodrive.app.feature.parking

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.barodrive.app.R
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.Gear
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.parking.VirtualParking as ParkModel
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PK-2 (프로토타입 parking-virtual.js, 시험 t5a_parking의 화면 부분·e2e t5a-parking): 지금 신호의 핸들·브레이크와 가상 기어로 시뮬레이터 모델을
 * 0.1초씩 나아가게 하고, 성공·닿음·그만하기·P를 벗어남을 처리한다.
 */
class VirtualParkingViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneId.of("Asia/Seoul"))

    private fun signal(steer: Double? = 0.0, brake: Boolean = false, gear: Gear? = Gear.P, model: String? = "avante", speed: Double? = 0.0) =
        DrivingState(snapshot = VehicleSnapshot(t = 0.0, gear = gear, speed = speed, brake = brake, steer = steer, model = model), gear = gear)

    private class Rig(val c: FakeSessionController, val store: InMemoryBaroStore, val vm: VirtualParkingViewModel)

    private fun rig(type: String, side: String, model: String = "avante"): Rig {
        val c = FakeSessionController(signal(model = model))
        val store = InMemoryBaroStore(clock = clock)
        val vm = VirtualParkingViewModel(SavedStateHandle(mapOf("type" to type, "side" to side)), c, store, store, clock)
        return Rig(c, store, vm)
    }

    /** 신호를 바꾸고 틱 [n]번. */
    private fun Rig.run(n: Int, steer: Double, brake: Boolean, gear: Gear? = Gear.P) {
        c.setState { signal(steer = steer, brake = brake, gear = gear) }
        repeat(n) { vm.tick() }
    }

    @Test
    fun plannedDriveParksSavesTheRecordAndTheCheck() = runTest {
        for ((type, side) in listOf("parallel" to "R", "back" to "L", "front" to "R")) {
            val r = rig(type, side)
            for (s in ParkModel.plan(type, side, "avante")) {
                r.vm.setGear(s.dir)
                r.run(s.ticks, s.steer, brake = false)
                r.run(s.brakeTicks, s.steer, brake = true)
            }
            val ui = r.vm.uiState.value
            assertTrue("$type-$side 주차 완료", ui.done)
            assertEquals(true, ui.result?.wheelStraight)
            val rec = r.store.list().single()
            assertEquals(RecordKind.VIRTUAL_PARKING, rec.kind)
            assertEquals(6, rec.mode)
            val pk = rec.parking!!
            assertEquals(listOf("success", "$type-$side", "avante"), listOf(pk.result, pk.key, pk.model))
            assertTrue(pk.virtual)
            assertEquals(true, pk.wheelStraight)
            assertEquals(pk.sec!!, rec.durationSec, 0.0)
            assertEquals("2026-11-11T10:00:00+09:00", rec.startedAt)
            assertEquals(mapOf("$type-$side" to true), r.store.current().parking.done)
            assertEquals("성공하면 PK-4가 그리는 글", "${parkingTitle(type, side, XmlTextRes)} · 세워 두고 연습", ParkingDoneText.headline(rec, XmlTextRes))
        }
    }

    @Test
    fun brakeMustBePressedOnceBeforeMoving() = runTest {
        val r = rig("parallel", "R")
        val start = r.vm.uiState.value.pose
        r.run(10, steer = 0.0, brake = false)
        assertEquals("처음에는 브레이크를 밟아야 움직인다(결정사항 E T5a-3)", start, r.vm.uiState.value.pose)
        assertEquals("브레이크를 밟았다 떼면 움직여요", VirtualParkingText.hint(r.vm.uiState.value, XmlTextRes))
        r.run(2, steer = 0.0, brake = true)
        assertEquals("브레이크를 떼면 천천히 움직여요", VirtualParkingText.hint(r.vm.uiState.value, XmlTextRes))
        r.run(10, steer = 18.0, brake = false)
        val ui = r.vm.uiState.value
        assertTrue(ui.moved)
        assertNull(VirtualParkingText.hint(ui, XmlTextRes))
        assertEquals("앞바퀴 오른쪽 18°", VirtualParkingText.wheel(ui.steer, XmlTextRes))
        assertEquals("앞바퀴 똑바로", VirtualParkingText.wheel(0.4, XmlTextRes))
        assertEquals("앞바퀴 왼쪽 34°", VirtualParkingText.wheel(-34.0, XmlTextRes))
    }

    @Test
    fun touchingStopsTheCarAndToasts() = runTest {
        val r = rig("parallel", "R")
        r.vm.effects.test {
            r.run(2, steer = 0.0, brake = true)
            var n = 0
            while (!r.vm.uiState.value.touched && n < 400) {
                r.run(1, steer = 34.0, brake = false)
                n++
            }
            assertTrue("앞차 옆에서 오른쪽 끝까지 꺾어 나아가면 닿는다", r.vm.uiState.value.touched)
            val toast = cancelAndConsumeRemainingEvents().mapNotNull { (it as? app.cash.turbine.Event.Item)?.value }.last() as VirtualParkingEffect.Toast
            assertTrue(toast.text == R.string.pk2_touch_car || toast.text == R.string.pk2_touch_curb)
            assertEquals(VirtualParkingText.touch(r.vm.uiState.value.touch), toast.text)
            // 처음부터: 다시 브레이크부터
            r.vm.restart()
            val ui = r.vm.uiState.value
            assertFalse(ui.touched)
            assertFalse(ui.armed)
            assertEquals("D", ui.vgear)
        }
    }

    @Test
    fun leavingParkFreezesAndComingBackGoesToTheSelectScreen() = runTest {
        val r = rig("back", "R")
        r.vm.effects.test {
            r.run(2, steer = 0.0, brake = true)
            r.run(5, steer = 0.0, brake = false)
            val before = r.vm.uiState.value.pose
            r.run(10, steer = 0.0, brake = false, gear = Gear.D)
            assertEquals("P가 아니면 화면 속 차를 세워 둔다", before, r.vm.uiState.value.pose)
            r.run(1, steer = 0.0, brake = true, gear = Gear.P)
            assertEquals(VirtualParkingEffect.ToSelect("back", "R"), awaitItem())
        }
        assertTrue("기어를 벗어나 닫히면 기록하지 않는다", r.store.list().isEmpty())
    }

    @Test
    fun stopRecordsOnlyAfterMoving() = runTest {
        val idle = rig("front", "L")
        idle.vm.effects.test {
            idle.vm.stop()
            assertEquals(VirtualParkingEffect.ToSelect("front", "L"), awaitItem())
        }
        assertTrue(idle.store.list().isEmpty())

        val moved = rig("front", "L")
        moved.run(2, steer = 0.0, brake = true)
        moved.run(8, steer = -10.0, brake = false)
        moved.vm.stop()
        val pk = moved.store.list().single().parking!!
        assertEquals(listOf("stopped", "front-L"), listOf(pk.result, pk.key))
        assertEquals(-10.0, pk.wheelAngle!!, 1e-9)
        assertEquals(false, pk.wheelStraight)
        assertTrue("그만한 연습은 완료 체크가 없다", moved.store.current().parking.done.isEmpty())
    }

    @Test
    fun carModelChangeRebuildsTheScene() = runTest {
        val r = rig("parallel", "R")
        assertEquals("avante", r.vm.uiState.value.car.id)
        r.c.setState { signal(model = "grandeur") }
        assertEquals("grandeur", r.vm.uiState.value.car.id)
        assertEquals(5.035, r.vm.uiState.value.car.length, 1e-9)
        assertEquals("앞차 뒷범퍼 끝이 내 어깨와 나란히 오면 멈추세요.", VirtualParkingText.tipText(r.vm.uiState.value))
    }

    @Test
    fun framesStepInTenthsAndInterpolate() = runTest {
        val r = rig("parallel", "R")
        r.c.setState { signal(brake = true) }
        r.vm.onFrame(0L)
        r.vm.onFrame(250_000_000L) // 0.25초 → 틱 2번
        assertTrue(r.vm.uiState.value.armed)
        r.c.setState { signal(brake = false) }
        r.vm.onFrame(500_000_000L)
        r.vm.onFrame(550_000_000L) // 틱 사이(alpha 0.5)
        val mid = r.vm.uiState.value.pose
        r.vm.onFrame(600_000_000L)
        val next = r.vm.uiState.value.pose
        assertTrue("틱 사이를 이어 그림", mid.x < next.x)
        r.vm.onPause()
        r.vm.onFrame(10_000_000_000L)
        assertEquals("멈췄다 돌아오면 몰아서 가지 않음", next, r.vm.uiState.value.pose)
    }
}
