package com.barodrive.app.data.signal.sim

import androidx.datastore.preferences.core.emptyPreferences
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.data.signal.ActiveSignalSource
import com.barodrive.app.data.store.DeviceBaroStore
import com.barodrive.app.data.voice.CoachVoiceLog
import com.barodrive.app.demo.PanelSettings
import com.barodrive.app.session.DrivingSessionManager
import com.barodrive.app.session.EngineSession
import com.barodrive.app.session.FacadeSessionEngine
import com.barodrive.app.session.ScenarioState
import com.barodrive.app.session.SessionEngine
import com.barodrive.app.session.SimLink
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.testing.FakeCoachVoice
import com.barodrive.app.testing.FakeNaviController
import com.barodrive.app.testing.FakeSessionRecordDao
import com.barodrive.app.testing.FakeSignalSource
import com.barodrive.app.testing.InMemoryDataStore
import com.barodrive.core.model.DemoPanelState
import com.barodrive.core.model.Gear
import com.barodrive.core.model.ParkingSetup
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.PlayerState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시연 시뮬레이터를 앱처럼 잇는다: 진짜 [com.barodrive.core.sim.BaroSim] → [SimController] → 신호 → 세션 관리자 → 엔진 파사드 → 저장.
 * 실제 시간 고리는 [SimController.advance](0.1)를 손으로 돌린다(앱은 100ms마다). PR-1 [내비로 보내고 출발] → 자동 재생 → 출발 틱 t 0 →
 * 도착 → P 마무리 → 기록, 시나리오 입력이 그 틱 엔진 걸음 바로 전에 가는지, 시계 붙잡기를 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimWiringTest {
    /** 엔진 파사드를 감싸 엔진에 넣은 스냅숏과 부른 차례를 적는다. */
    private class RecordingEngine(private val facade: FacadeSessionEngine = FacadeSessionEngine()) : SessionEngine by facade {
        val steps = ArrayList<VehicleSnapshot>()
        val calls = ArrayList<String>()

        override fun createSession(cfg: SessionConfig): EngineSession {
            val inner = facade.createSession(cfg)
            return object : EngineSession {
                override val config: SessionConfig = inner.config

                override fun step(snapshot: VehicleSnapshot): StepOutput {
                    steps += snapshot
                    calls += "step"
                    return inner.step(snapshot)
                }

                override fun input(event: SessionInput): StepOutput {
                    calls += "input:${event::class.simpleName}"
                    return inner.input(event)
                }

                override fun finish(arrived: Boolean): SessionRecord = inner.finish(arrived)

                override fun deliver(item: String) = inner.deliver(item)
            }
        }
    }

    private class Rig(val scope: TestScope, panel: PanelSettings) {
        val repo = FakeDemoPanelRepository(panel)
        val controller = SimController(repo, scope.backgroundScope)
        val link = DemoSimLink(controller)
        val clock: Clock = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneId.of("Asia/Seoul"))
        val settings = SettingsRepository(InMemoryDataStore(emptyPreferences()))
        val sim = FakeSignalSource()
        val signals = ActiveSignalSource(settings, FakeSignalSource(), sim, scope.backgroundScope)
        val store = DeviceBaroStore(InMemoryDataStore(emptyPreferences()), FakeSessionRecordDao(), clock)
        val engine = RecordingEngine()
        val tts = FakeCoachVoice()
        val voice = CoachVoiceLog(tts, engine, store, scope.backgroundScope)
        val manager = DrivingSessionManager(
            signals, engine, tts, voice, store, store, settings, link, clock, scope.backgroundScope,
            UnconfinedTestDispatcher(scope.testScheduler), FakeNaviController(),
        )
        val events = ArrayList<SessionEvent>()
        val emitted = ArrayList<VehicleSnapshot>()

        suspend fun start() {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { manager.events.collect { events += it } }
            manager.start()
            sim.awaitCollector()
            scope.runCurrent()
        }

        /** 실제 시간 0.1초. 그동안 돈 틱 수. */
        suspend fun frame(): Int {
            val s = controller.advance(0.1)
            emitted += s
            sim.emit(*s.toTypedArray())
            scope.runCurrent()
            return s.size
        }

        suspend fun runUntil(maxFrames: Int, done: () -> Boolean): Int {
            var n = 0
            while (n < maxFrames && !done()) {
                frame()
                n++
            }
            return n
        }
    }

    /** 압축 × 배속 재생을 본다(패널 처음 값 '실제'를 끄고 ×[speed]). */
    private fun TestScope.rig(speed: Double = 4.0) = Rig(this, PanelSettings(demo = DemoPanelState(speed = speed, realtime = false)))

    @Test
    fun sendAutoplaysTheCourseAndTheSessionRunsToASavedRecord() = runTest {
        val r = rig()
        r.start()
        r.manager.prepare(PrepSession(mode = 1, courseId = "c1-B"))
        val sent = r.manager.send()
        assertEquals("내비에 B 코스를 넣었어요", sent?.toast?.substringBefore(" · "))
        assertEquals("불러온 채 자동 재생 대기", PlayerState.LOADED, r.controller.view.value.player)
        r.runUntil(30) { r.controller.view.value.player == PlayerState.PLAYING }
        assertEquals("자동 재생(실제 1초 뒤)", PlayerState.PLAYING, r.controller.view.value.player)

        val frames = r.runUntil(6000) { r.events.any { it is SessionEvent.Finished } }
        val started = r.events.filterIsInstance<SessionEvent.Started>().single()
        assertEquals("c1-B", started.courseId)
        val done = r.events.filterIsInstance<SessionEvent.Finished>().single()
        assertTrue("도착으로 끝남", done.arrived)
        assertNotNull(done.recordId)
        // 출발 틱에 시계 0: 처음 D 스냅숏의 t가 0, 엔진도 0부터 0.1초 간격
        val departure = r.emitted.first { it.gear != null && it.gear != Gear.P }
        assertEquals(0.0, departure.t, 0.0)
        assertEquals(0.0, r.engine.steps.first().t, 0.0)
        assertTrue(r.engine.steps.zipWithNext().all { (a, b) -> abs(b.t - a.t - 0.1) < 1e-6 })
        // 배속 ×4 · 압축: 실제 시간보다 훨씬 빨리 돈다
        val simSec = r.engine.steps.last().t
        assertTrue("sim ${simSec}초를 실제 ${frames / 10.0}초에", simSec > frames / 10.0 * 4)
        val rec = requireNotNull(r.store.get(requireNotNull(done.recordId)))
        assertEquals(1, rec.mode)
        assertEquals("c1-B", rec.courseId)
        assertTrue(rec.arrived)
        val tail = r.engine.steps.takeLastWhile { it.gear == Gear.P }.size
        assertTrue("P 마무리 표본 $tail(30~60)", tail in 30..60)
        // 마무리를 놓으면 서 있는 차는 시계가 선다
        repeat(200) { r.frame() }
        assertEquals(0, r.frame())
        assertEquals(ScenarioState.ENDED, r.link.scenario.value)
    }

    @Test
    fun scenarioAskReachesTheEngineRightBeforeItsTickStep() = runTest {
        val r = rig()
        r.start()
        r.manager.prepare(PrepSession(mode = 1))
        r.controller.loadAndAutoplay("S9-R")
        r.runUntil(8000) { r.events.any { it is SessionEvent.Finished } }
        val done = r.events.filterIsInstance<SessionEvent.Finished>().single()
        assertTrue(done.arrived)
        val i = r.engine.calls.indexOf("input:DriverAsk")
        assertTrue("운전자 질문이 엔진에 감: ${r.engine.calls.filter { it != "step" }}", i > 0)
        assertEquals("그 틱 걸음 바로 전", "step", r.engine.calls[i + 1])
        assertEquals(1, r.engine.calls.count { it.startsWith("input:") })
    }

    @Test
    fun parkingStartChoosesTheTypeAndSideThenLoadsS6P() = runTest {
        val r = rig()
        r.start()
        r.manager.prepare(PrepSession(mode = 6, parking = ParkingSetup(ParkingType.BACK, TurnDir.LEFT)))
        assertNotNull("모드 6은 코스 없이도 보낸다", r.manager.send())
        val loaded = requireNotNull(r.controller.view.value.scenario)
        assertEquals("S6-P", loaded.id)
        assertEquals("주차 연습 · 실제 주차 안내(후방(후진) · 왼쪽 칸)", loaded.title)
        assertEquals(PlayerState.LOADED, r.controller.view.value.player)
        r.runUntil(30) { r.controller.view.value.player == PlayerState.PLAYING }
        assertEquals("자동 재생", PlayerState.PLAYING, r.controller.view.value.player)
    }

    @Test
    fun departureGateResetsTheClockOnlyWhenASessionStarts() = runTest {
        val r = rig(speed = 1.0)
        runCurrent()
        // 세션 연결 없이 손으로 D: 시계는 그대로 흐른다
        r.controller.hold("x", true)
        repeat(5) { r.controller.advance(0.1) }
        val before = r.controller.view.value.simT
        assertTrue(before > 0)
        r.controller.departureGate = { false }
        r.controller.shift(Gear.D)
        val noReset = r.controller.advance(0.1)
        assertTrue(noReset.first().t > before)
        // 문이 참이면 P → D 그 틱에 0
        r.controller.shift(Gear.P)
        repeat(3) { r.controller.advance(0.1) }
        r.controller.departureGate = { true }
        r.controller.shift(Gear.D)
        assertEquals(0.1, r.controller.advance(0.1).first().t, 1e-9) // 틱 밖(패널)에서 바꿨으니 다음 틱이 0.1
    }

    @Test
    fun takeTickHandsOverScenarioStatesInOrderAndHoldKeepsAParkedClockRunning() = runTest {
        val r = rig(speed = 1.0)
        runCurrent()
        assertTrue("서 있는 차는 시계가 선다", r.controller.advance(0.1).isEmpty() || r.controller.advance(0.1).isEmpty())
        r.link.hold(SimLink.HOLD_FINISH, true)
        val held = r.controller.advance(0.1)
        assertTrue("붙잡으면 ×1로 돈다", held.isNotEmpty())
        r.link.hold(SimLink.HOLD_FINISH, false)
        // 불러오기·재생은 틱 밖 → 다음 틱 스냅숏에 붙는다
        r.controller.load(com.barodrive.core.sim.SimTarget.Scenario("S1-B"))
        r.controller.toggle(null)
        val snaps = r.controller.advance(0.1)
        assertTrue(snaps.isNotEmpty())
        assertEquals(ScenarioState.IDLE, r.link.scenario.value)
        val tick = r.link.takeTick(snaps.first())
        assertEquals(listOf(ScenarioState.LOADED, ScenarioState.PLAYING), tick.scenario)
        assertEquals("세션이 꺼낸 시점으로", ScenarioState.PLAYING, r.link.scenario.value)
        assertEquals(com.barodrive.app.session.SimTick.EMPTY, r.link.takeTick(snaps.last()))
    }
}
