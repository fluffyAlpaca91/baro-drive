package com.barodrive.app.session

import app.cash.turbine.test
import com.barodrive.app.data.navi.NaviDestination
import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.session.api.ActivePhase
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.session.api.Speaker
import com.barodrive.app.testing.FakeSessionEngine
import com.barodrive.app.testing.SessionFixture
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Hints
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.NaviPlace
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TodayTask
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t2c_session.test.js의 세션 규칙을 가짜 엔진으로: P 마무리 길이, DR-3 [여기서 마치기], 시나리오, 기어 없음, PR-1 정차 중 말. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleTest {
    private suspend fun TestScope.ready(f: SessionFixture, prep: PrepSession = PrepSession(mode = 1, courseId = "c1-B")) {
        f.manager.start()
        f.sim.awaitCollector()
        f.manager.prepare(prep)
        runCurrent()
    }

    private fun pSteps(f: SessionFixture) = f.engine.sessions.single().steps.count { it.gear == Gear.P }

    @Test
    fun endHereFillsTheThreeSecondParkTailThenSavesNotArrived() = runTest {
        val f = SessionFixture(this)
        ready(f)
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        f.sim.emit(*SessionFixture.parked(9.0, 2).toTypedArray())
        runCurrent()
        assertEquals(ActivePhase.STOPPED, f.manager.state.value.activePhase)
        assertEquals("stopped", f.manager.state.value.phaseName)

        val id = f.manager.endHere()
        assertEquals("P 표본 30개(3 sim초)를 채워 P 때 판정(PB2)을 마저 본다", 30, pSteps(f))
        assertEquals(false, f.engine.sessions.single().finishedWith)
        assertEquals(id, f.store.list().single().id)
        assertTrue("마지막 표본이 세션 시각으로 이어진다", f.engine.sessions.single().steps.last().t > 11.0)
    }

    @Test
    fun parkTailWaitsForTheLastLineButNotLongerThanSixSeconds() = runTest {
        // P 순간 4초짜리 말 → 3초가 지나도 '말하는 중'이라 말이 끝나고 한 틱 뒤에 끝낸다.
        val engine = FakeSessionEngine(script = { s ->
            val say = if (s.gear == Gear.P && s.t > 8.45 && s.t < 8.55) listOf(Utterance(key = "pb1.on", t = s.t, dur = 4.0)) else emptyList()
            StepOutput(t = s.t, say = say, hints = Hints(arrived = s.gear == Gear.P))
        })
        val f = SessionFixture(this, engine)
        ready(f)
        f.manager.events.test {
            f.sim.emit(*SessionFixture.driveOff().toTypedArray())
            assertTrue(awaitItem() is SessionEvent.Started)
            f.sim.emit(*SessionFixture.parked(9.0, 80).toTypedArray())
            val done = awaitItem() as SessionEvent.Finished
            assertTrue(done.arrived)
        }
        val steps = pSteps(f)
        // 말 t = 8.5(세션 초), 끝 12.5 → 12.6 이상에서 끝: 첫 P(8.5)부터 42번째 표본.
        assertEquals(42, steps)
        assertTrue(steps in SessionController.P_MIN..SessionController.P_MAX)
    }

    @Test
    fun parkTailNeverExceedsSixtySamples() = runTest {
        val engine = FakeSessionEngine(script = { s ->
            val say = if (s.gear == Gear.P && s.t > 8.45 && s.t < 8.55) listOf(Utterance(key = "long", t = s.t, dur = 30.0)) else emptyList()
            StepOutput(t = s.t, say = say, hints = Hints(arrived = true))
        })
        val f = SessionFixture(this, engine)
        ready(f)
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        f.sim.emit(*SessionFixture.parked(9.0, 100).toTypedArray())
        runCurrent()
        assertEquals(60, pSteps(f))
        assertEquals(SessionPhase.IDLE, f.manager.state.value.phase)
    }

    @Test
    fun navVoiceAtParkDelaysTheEndByOneSecond() = runTest {
        val f = SessionFixture(this, FakeSessionEngine(script = { s -> StepOutput(t = s.t, hints = Hints(arrived = true)) }))
        ready(f)
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        // 26번째 P 표본(세션 11.0초)에 내비 음성 → 12.0초까지 기다림 → 36번째 표본에서 끝.
        val park = SessionFixture.parked(9.0, 80).mapIndexed { i, s -> if (i == 25) s.copy(nav = NavSnapshot(voice = true)) else s }
        f.sim.emit(*park.toTypedArray())
        runCurrent()
        assertEquals(36, pSteps(f))
    }

    @Test
    fun playingScenarioWaitsTwoSecondsBeforeDr3() = runTest {
        val f = SessionFixture(this)
        ready(f)
        f.simLink.scenario.value = com.barodrive.app.session.ScenarioState.PLAYING
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        f.sim.emit(*SessionFixture.parked(9.0, 19).toTypedArray())
        runCurrent()
        assertEquals("재생 중 P → 잠깐 기다림", ActivePhase.PARK_WAIT, f.manager.state.value.activePhase)
        f.sim.emit(*SessionFixture.parked(10.9, 1).toTypedArray())
        runCurrent()
        assertEquals(ActivePhase.STOPPED, f.manager.state.value.activePhase)
    }

    @Test
    fun scenarioEndingAfterParkFinishesInsteadOfDr3() = runTest {
        val f = SessionFixture(this)
        ready(f)
        f.simLink.scenario.value = com.barodrive.app.session.ScenarioState.PLAYING
        runCurrent()
        f.manager.events.test {
            f.sim.emit(*SessionFixture.driveOff().toTypedArray())
            assertTrue(awaitItem() is SessionEvent.Started)
            f.sim.emit(*SessionFixture.parked(9.0, 10).toTypedArray())
            runCurrent()
            assertEquals(ActivePhase.PARK_WAIT, f.manager.state.value.activePhase)
            f.simLink.scenario.value = com.barodrive.app.session.ScenarioState.ENDED
            runCurrent()
            f.sim.emit(*SessionFixture.parked(10.0, 30).toTypedArray())
            val done = awaitItem() as SessionEvent.Finished
            assertFalse("시나리오 끝은 도착 표시가 아니다", done.arrived)
        }
    }

    @Test
    fun dr3ThenDriveResumesThePausedScenario() = runTest {
        val f = SessionFixture(this)
        ready(f)
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        f.simLink.scenario.value = com.barodrive.app.session.ScenarioState.PAUSED // 패널 손 조작 P = 재생 멈춤
        f.sim.emit(*SessionFixture.parked(9.0, 1).toTypedArray())
        runCurrent()
        assertTrue(f.manager.state.value.stopped)
        f.sim.emit(VehicleSnapshot(t = 9.2, gear = Gear.D, speed = 0.0))
        runCurrent()
        assertEquals(1, f.simLink.resumes)
        assertEquals(ActivePhase.DRIVING, f.manager.state.value.activePhase)
    }

    @Test
    fun cancelPausesAPlayingScenario() = runTest {
        val f = SessionFixture(this)
        ready(f)
        f.simLink.scenario.value = com.barodrive.app.session.ScenarioState.PLAYING
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        runCurrent()
        assertTrue(f.manager.cancel())
        assertEquals(1, f.simLink.pauses)
        // 준비만 한 상태도 취소할 수 있다
        f.manager.prepare(PrepSession(mode = 1, courseId = "c1-A"))
        assertTrue(f.manager.cancel())
        assertEquals(null, f.manager.state.value.prep)
    }

    @Test
    fun gearlessDrivesOffByTripAndParksAfterTenStillSeconds() = runTest {
        val f = SessionFixture(this)
        ready(f)
        fun at(t: Double, trip: Double, speed: Double) = VehicleSnapshot(t = t, gear = null, speed = speed, tripDist = trip)
        var t = 50.0
        var trip = 10.0
        val still = List(5) { at(t + it / 10.0, trip, 0.0) }
        t += 0.5
        val moving = List(50) { i -> trip += 0.01; at(t + i / 10.0, trip, 20.0) }
        t += 5.0
        f.sim.emit(*(still + moving).toTypedArray())
        runCurrent()
        val s = f.manager.state.value
        assertEquals("트립 거리가 늘면 시작", SessionPhase.ACTIVE, s.phase)
        assertTrue(s.gearless)
        assertEquals("움직인 첫 표본이 세션 0초", 0.0, f.engine.sessions.single().steps.first().t, 1e-9)

        f.sim.emit(*List(99) { at(t + it / 10.0, trip, 0.0) }.toTypedArray())
        runCurrent()
        assertEquals(ActivePhase.DRIVING, f.manager.state.value.activePhase)
        f.sim.emit(at(t + 9.9, trip, 0.0), at(t + 10.0, trip, 0.0))
        runCurrent()
        assertEquals("도착 전 10초 정차 → 중간 정차", ActivePhase.STOPPED, f.manager.state.value.activePhase)

        f.sim.emit(at(t + 10.1, trip + 0.01, 5.0))
        runCurrent()
        assertEquals("다시 움직이면 주행", ActivePhase.DRIVING, f.manager.state.value.activePhase)
        f.sim.emit(at(t + 10.2, trip + 0.02, 5.0).copy(nav = NavSnapshot(events = listOf(com.barodrive.core.model.NavEvent.Arrived))))
        f.manager.events.test {
            f.sim.emit(*List(101) { at(t + 10.3 + it / 10.0, trip + 0.02, 0.0) }.toTypedArray())
            val done = awaitItem() as SessionEvent.Finished
            assertTrue("도착 뒤 10초 정차 → 바로 저장", done.arrived)
        }
    }

    @Test
    fun predriveLinesOncePerPrepAndPreEvents() = runTest {
        val engine = FakeSessionEngine(today = listOf(TodayTask("T-START", "엑셀 살살"), TodayTask("T-STOP", "부드럽게 서기")))
        engine.items = mapOf("L1" to Delivery.NOW, "PB1" to Delivery.NOW)
        val f = SessionFixture(this, engine)
        f.manager.start()
        f.sim.awaitCollector()
        f.sim.emit(VehicleSnapshot(t = 0.0, gear = Gear.P, passenger = false, handbrake = true))
        runCurrent()
        f.manager.prepare(PrepSession(mode = 1, courseId = "c1-B"))
        assertTrue(f.manager.enterPredrive())
        f.manager.enterPredrive()
        val said = { key: String -> f.voice.entries.value.filter { it.key == key && it.spoken } }
        assertEquals("오늘 과제를 한 번 읽음", 1, said("p.tasks").size)
        assertEquals("p.tasks|nWord=두,list=엑셀 살살, 부드럽게 서기", said("p.tasks").single().text)
        assertTrue(f.manager.state.value.prep?.lines?.tasksRead == true)
        assertEquals("비어 있으면 l1 없음", 0, said("l1").size)

        f.sim.emit(VehicleSnapshot(t = 0.1, gear = Gear.P, passenger = true, handbrake = true))
        f.sim.emit(VehicleSnapshot(t = 0.2, gear = Gear.P, passenger = false, handbrake = true))
        f.sim.emit(VehicleSnapshot(t = 0.3, gear = Gear.P, passenger = true, handbrake = true))
        runCurrent()
        assertEquals("앉는 순간 l1, 회차에 한 번", 1, said("l1").size)
        assertEquals(Speaker.COACH, said("l1").single().speaker)
        assertTrue(f.manager.state.value.prep?.preEvents.orEmpty().any { it.item == "L1" && it.data.toString().contains("\"spoken\":true") })

        // 사이드브레이크를 못 읽음: 들어올 때 한 번 묻고 [풀었어요]
        f.sim.emit(VehicleSnapshot(t = 0.4, gear = Gear.P, passenger = true, handbrake = null))
        runCurrent()
        f.manager.prepare(PrepSession(mode = 1, courseId = "c1-A"))
        f.manager.enterPredrive(readTasks = false)
        assertEquals(1, said("pb1.ask").size)
        assertTrue(f.manager.confirmHandbrake())
        assertFalse("한 번만", f.manager.confirmHandbrake())
        assertTrue(f.manager.setMirror(true))
        val prep = f.manager.state.value.prep
        assertEquals(true, prep?.checks?.pbConfirmed)
        assertEquals(true, prep?.checks?.mirror)
        assertTrue(prep?.preEvents.orEmpty().any { it.item == "PB1" && it.data.toString().contains("\"confirmed\":true") })

        // L1 X → 막힌 말(X)로 기록만, PB1 X → 묻지 않음
        engine.items = mapOf("L1" to Delivery.OFF, "PB1" to Delivery.OFF)
        f.manager.prepare(PrepSession(mode = 3, courseId = "c3-A"))
        f.manager.enterPredrive()
        assertEquals(1, said("pb1.ask").size)
        val blockedL1 = f.voice.entries.value.filter { it.key == "l1" && !it.spoken }
        assertEquals(BlockReason.OFF, blockedL1.single().reason)
        assertTrue(f.manager.state.value.prep?.preEvents.orEmpty().any { it.item == "L1" && it.data.toString().contains("\"spoken\":false") })
    }

    @Test
    fun sendGoesThroughTheSimulatorAndPrepareIsRefusedWhileActive() = runTest {
        val f = SessionFixture(this)
        ready(f)
        val sent = f.manager.send()
        assertEquals("내비에 B 코스를 넣었어요", sent?.toast)
        assertEquals(listOf<Pair<String?, String?>>("c1-B" to null), f.simLink.sent)
        assertTrue(f.manager.state.value.sent)

        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        runCurrent()
        val keep = f.manager.state.value.prep
        assertEquals("회차 중에는 준비 세션을 바꾸지 않음", keep, f.manager.prepare(PrepSession(mode = 2, courseId = "c2-B")))
        assertEquals(1, f.manager.state.value.mode)
        assertFalse("회차 중에는 버리지 않음", f.manager.drop())
    }

    /** 진짜 차 자료 출처로 바꾸고 차가 P에 서 있는 신호 하나. */
    private suspend fun TestScope.realCar(f: SessionFixture, prep: PrepSession) {
        f.manager.start()
        f.settings.setDataSource(DataSource.CAR)
        f.car.awaitCollector()
        f.car.emit(VehicleSnapshot(t = 0.0, gear = Gear.P, speed = 0.0))
        runCurrent()
        f.manager.prepare(prep)
        runCurrent()
    }

    private val home = NaviPlace(name = "우리 집", lat = 37.4401, lng = 127.1377, address = "성남시 수정구", poiId = "p-home", poiSubId = "0")

    @Test
    fun realCarSendAsksTheCarNaviForAPlaceWithCoordinates() = runTest {
        val f = SessionFixture(this)
        realCar(f, PrepSession(mode = 2, navi = home))
        val sent = f.manager.send()
        assertEquals("내비에 우리 집을 넣었어요", sent?.toast)
        assertTrue(sent?.sent == true)
        assertEquals(listOf(NaviDestination.of(home)), f.navi.requested)
        assertEquals("p-home", f.navi.requested.single().poiId)
        assertTrue("시연 시뮬레이터로는 보내지 않는다", f.simLink.sent.isEmpty())
        assertTrue(f.manager.state.value.sent)
    }

    @Test
    fun realCarSendIsHonestAboutTeamCoursesWithoutCoordinates() = runTest {
        val f = SessionFixture(this)
        realCar(f, PrepSession(mode = 1, courseId = "c1-B"))
        val r = f.manager.send()
        assertEquals("이 코스는 좌표가 없어 내비로 보낼 수 없어요. 내비에서 목적지를 넣고 출발해 주세요", r?.toast)
        assertFalse(r?.sent ?: true)
        assertTrue(f.navi.requested.isEmpty())
        assertTrue(f.simLink.sent.isEmpty())
        assertFalse("보내지 않았다", f.manager.state.value.sent)
    }

    @Test
    fun realCarSendSaysSoWhenTheNaviIsNotThere() = runTest {
        val f = SessionFixture(this)
        f.navi.connected = false
        realCar(f, PrepSession(mode = 2, navi = home))
        val r = f.manager.send()
        assertEquals("내비에 목적지를 넣지 못했어요. 내비에서 목적지를 넣고 출발해 주세요", r?.toast)
        assertFalse(r?.sent ?: true)
        assertFalse(f.manager.state.value.sent)
    }

    @Test
    fun simulatorSendIsUnchangedEvenWithANaviPlace() = runTest {
        val f = SessionFixture(this)
        ready(f)
        f.manager.send()
        assertEquals("시뮬레이터 자료 출처는 시뮬레이터로", listOf<Pair<String?, String?>>("c1-B" to null), f.simLink.sent)
        assertTrue(f.navi.requested.isEmpty())
    }

    @Test
    fun dropForgetsThePrepSoDriveIsDr0() = runTest {
        val f = SessionFixture(this)
        ready(f)
        assertTrue(f.manager.drop())
        assertEquals(SessionPhase.IDLE, f.manager.state.value.phase)
        f.sim.emit(*SessionFixture.driveOff().toTypedArray())
        runCurrent()
        assertTrue("준비 세션이 없으면 D여도 시작하지 않음", f.engine.sessions.isEmpty())
    }

}
