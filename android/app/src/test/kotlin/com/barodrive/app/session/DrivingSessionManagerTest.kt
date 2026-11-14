package com.barodrive.app.session

import app.cash.turbine.test
import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.session.api.ActivePhase
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.testing.FakeSessionEngine
import com.barodrive.app.data.repo.updateLicense
import com.barodrive.app.testing.SessionFixture
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Hints
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 세션 상태 기계 (session.js — 설계 3.2 수명): 가짜 엔진으로 P → D → P, 도착·DR-3·버림·단계 통과. */
@OptIn(ExperimentalCoroutinesApi::class)
class DrivingSessionManagerTest {
    @Test
    fun preparedSessionStartsWhenLeavingParkAtSessionZero() = runTest {
        val f = SessionFixture(this, FakeSessionEngine(script = { s -> StepOutput(t = s.t, say = sayAt(s, 1.0, "s1")) }))
        f.manager.start()
        f.sim.awaitCollector()
        val prep = f.manager.prepare(PrepSession(mode = 1))
        assertEquals("회차 번호 = sessionCount + 1", 1, prep?.noInMode)
        assertEquals(SessionPhase.READY, f.manager.state.value.phase)
        assertEquals("1단계 1회차 준비", f.manager.state.value.label)

        f.manager.events.test {
            f.sim.emit(*SessionFixture.driveOff(startT = 100.0).toTypedArray())
            runCurrent()

            val started = awaitItem() as SessionEvent.Started
            assertEquals(1, started.mode)
            assertEquals(1, started.noInMode)
            val s = f.manager.state.value
            assertEquals(SessionPhase.ACTIVE, s.phase)
            assertEquals(ActivePhase.DRIVING, s.activePhase)
            assertEquals("driving", s.phaseName)
            assertEquals("D가 된 100.5초가 세션 0초", 0.0, f.engine.sessions.single().steps.first().t, 1e-9)
            assertEquals(8.4, s.elapsedSec, 1e-6)
            assertEquals("s1", s.lastSay?.key)
            assertEquals(listOf("s1"), f.tts.spoken.map { it.key })
            assertEquals(listOf("s1"), f.voice.entries.value.map { it.key })
            assertTrue("A5 거울 점검은 출발 때 기록", f.engine.sessions.single().config.preEvents.any { it.item == "A5" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun arrivalThenParkFinishesAfterThreeSimSecondsAndSaves() = runTest {
        val f = SessionFixture(this)
        f.manager.start()
        f.sim.awaitCollector()
        f.manager.prepare(PrepSession(mode = 2))

        f.manager.events.test {
            f.sim.emit(*SessionFixture.driveOff().toTypedArray())
            assertTrue(awaitItem() is SessionEvent.Started)
            val nav = NavSnapshot(events = listOf(NavEvent.Arrived))
            f.sim.emit(VehicleSnapshot(t = 9.0, gear = Gear.D, speed = 0.0, nav = nav))
            runCurrent()
            assertTrue(f.manager.state.value.arrived)

            f.sim.emit(*SessionFixture.parked(9.1, 29).toTypedArray())
            runCurrent()
            assertEquals("P 29개는 아직 마무리", ActivePhase.FINISHING, f.manager.state.value.activePhase)
            assertEquals("2단계 1회차 마무리", f.manager.state.value.label)

            f.sim.emit(*SessionFixture.parked(12.0, 1).toTypedArray())
            val done = awaitItem() as SessionEvent.Finished
            assertTrue(done.arrived)
            assertEquals(2, done.mode)
            assertEquals("sess-20251112-0726", done.recordId)
            cancelAndIgnoreRemainingEvents()
        }
        val s = f.manager.state.value
        assertEquals(SessionPhase.IDLE, s.phase)
        assertNull(s.prep)
        assertEquals("sess-20251112-0726", s.lastRecordId)
        val saved = f.store.get("sess-20251112-0726")
        assertEquals(true, saved?.arrived)
        assertEquals("진행 반영(sessionCount)", 1, f.store.current().sessionCount[2])
        assertEquals(true, f.engine.sessions.single().finishedWith)
    }

    @Test
    fun parkWithoutArrivalStopsAtDr3AndContinuesInDrive() = runTest {
        val f = SessionFixture(this)
        f.manager.start()
        f.sim.awaitCollector()
        f.manager.prepare(PrepSession(mode = 1))
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        f.sim.emit(*SessionFixture.parked(9.0, 3).toTypedArray())
        runCurrent()

        assertEquals(ActivePhase.STOPPED, f.manager.state.value.activePhase)
        assertTrue(f.manager.state.value.stopped)
        assertEquals("1단계 1회차 중간 정차", f.manager.state.value.label)
        assertTrue(f.manager.continueHere())
        assertTrue(f.manager.state.value.dr3Continue)

        f.sim.emit(VehicleSnapshot(t = 9.5, gear = Gear.D, speed = 0.0))
        runCurrent()
        assertEquals(ActivePhase.DRIVING, f.manager.state.value.activePhase)
        assertFalse(f.manager.state.value.dr3Continue)
        assertTrue(f.store.list().isEmpty())
    }

    @Test
    fun endHereSavesWithoutArrival() = runTest {
        val f = SessionFixture(this)
        f.manager.start()
        f.sim.awaitCollector()
        f.manager.prepare(PrepSession(mode = 1))
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        f.sim.emit(*SessionFixture.parked(9.0, 2).toTypedArray())
        runCurrent()

        f.manager.events.test {
            val id = f.manager.endHere()
            val done = awaitItem() as SessionEvent.Finished
            assertEquals(id, done.recordId)
            assertFalse(done.arrived)
        }
        assertEquals(false, f.engine.sessions.single().finishedWith)
        assertEquals(SessionPhase.IDLE, f.manager.state.value.phase)
    }

    @Test
    fun parkWithoutPreparingDoesNotStartOrSave() = runTest {
        val f = SessionFixture(this)
        f.manager.start()
        f.sim.awaitCollector()

        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        f.sim.emit(VehicleSnapshot(t = 20.0, gear = Gear.P, speed = 0.0))
        runCurrent()

        assertEquals(SessionPhase.IDLE, f.manager.state.value.phase)
        assertEquals(Gear.P, f.manager.state.value.gear)
        assertTrue(f.engine.sessions.isEmpty())
        assertTrue(f.store.list().isEmpty())
    }

    @Test
    fun switchingDataSourceWhileActiveDiscardsTheSession() = runTest {
        val f = SessionFixture(this)
        f.manager.start()
        f.sim.awaitCollector()
        f.manager.prepare(PrepSession(mode = 1))
        f.sim.emit(VehicleSnapshot(t = 0.0, gear = Gear.P), VehicleSnapshot(t = 0.1, gear = Gear.D, speed = 0.0))
        runCurrent()
        assertEquals(SessionPhase.ACTIVE, f.manager.state.value.phase)

        f.manager.events.test {
            f.settings.setDataSource(DataSource.CAR)
            f.car.awaitCollector()
            // 진짜 차는 P에 서 있다 — 새 출처의 P로 앞 세션이 저장되면 안 된다.
            f.car.emit(VehicleSnapshot(t = 5000.0, gear = Gear.P, speed = 0.0))
            runCurrent()
            expectNoEvents()
        }
        assertEquals(SessionPhase.IDLE, f.manager.state.value.phase)
        assertEquals(Gear.P, f.manager.state.value.gear)
        assertTrue(f.store.list().isEmpty())
        assertEquals(1, f.tts.stopCount)
    }

    @Test
    fun stagePassEventFollowsFinished() = runTest {
        val f = SessionFixture(this, FakeSessionEngine(passes = true, script = { s -> StepOutput(t = s.t, hints = Hints(arrived = s.gear == Gear.P)) }))
        f.manager.start()
        f.sim.awaitCollector()
        f.manager.prepare(PrepSession(mode = 3))

        f.manager.events.test {
            f.sim.emit(*SessionFixture.driveOff().toTypedArray())
            assertTrue(awaitItem() is SessionEvent.Started)
            f.sim.emit(*SessionFixture.parked(9.0, 30).toTypedArray())
            val done = awaitItem() as SessionEvent.Finished
            assertTrue("검출기의 도착(hints.arrived)도 도착", done.arrived)
            val passed = awaitItem() as SessionEvent.StagePassed
            assertEquals(3, passed.stage)
            assertEquals(4, passed.nextStage)
            assertEquals(done.recordId, passed.recordId)
        }
    }

    @Test
    fun licenseRunSavesCoursesDoneOnProfileAndRecord() = runTest {
        val f = SessionFixture(this, FakeSessionEngine(script = { s -> StepOutput(t = s.t, hints = Hints(arrived = s.gear == Gear.P)) }))
        f.store.updateLicense { it.copy(coursesDone = listOf("A")) }
        f.manager.start()
        f.sim.awaitCollector()
        f.manager.prepare(PrepSession(mode = 8))

        f.manager.events.test {
            f.sim.emit(*SessionFixture.driveOff().toTypedArray())
            assertTrue(awaitItem() is SessionEvent.Started)
            f.sim.emit(*SessionFixture.parked(9.0, 30).toTypedArray())
            val done = awaitItem() as SessionEvent.Finished
            val rec = f.store.get(done.recordId ?: "")
            assertEquals("그 회차 기준 진행(T8-14)", listOf("A", "B"), rec?.license?.coursesDoneAfter)
            assertNotNull("진행 반영도 함께", rec?.progress)
        }
        assertEquals(listOf("A", "B"), f.store.current().license.coursesDone)
        assertEquals(1, f.store.current().sessionCount[8])
    }

    @Test
    fun cancelDropsTheRunWithoutRecord() = runTest {
        val f = SessionFixture(this)
        f.manager.start()
        f.sim.awaitCollector()
        f.manager.prepare(PrepSession(mode = 6))
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        runCurrent()

        f.manager.events.test {
            assertTrue(f.manager.cancel())
            assertEquals(SessionEvent.Cancelled(mode = 6, noInMode = 1, active = true), awaitItem())
        }
        assertEquals(SessionPhase.IDLE, f.manager.state.value.phase)
        assertTrue(f.store.list().isEmpty())
        assertEquals("회차 수 그대로", 0, f.store.current().sessionCount[6])
        assertFalse(f.manager.cancel())
    }

    @Test
    fun muteGoesThroughTheEngineAndResetsAfterTheRun() = runTest {
        val f = SessionFixture(this)
        f.manager.start()
        f.sim.awaitCollector()
        assertNull("회차가 없으면 null", f.manager.toggleMute())
        f.manager.prepare(PrepSession(mode = 1))
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        runCurrent()

        assertEquals(true, f.manager.toggleMute())
        assertTrue(f.manager.state.value.muted)
        f.manager.endHere()
        assertFalse(f.manager.state.value.muted)
    }

    private fun sayAt(s: VehicleSnapshot, at: Double, key: String): List<Utterance> =
        if (kotlin.math.abs(s.t - at) < 1e-9) listOf(Utterance(key = key, text = key, t = s.t, dur = 1.0)) else emptyList()
}
