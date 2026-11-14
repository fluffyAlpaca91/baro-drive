package com.barodrive.app.demo

import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.data.repo.updateOnboarding
import com.barodrive.app.data.signal.sim.FakeDemoPanelRepository
import com.barodrive.app.data.signal.sim.SimController
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.core.model.AppState
import com.barodrive.core.model.Gear
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.PlayerState
import com.barodrive.core.sim.SimTarget
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시연 도구 동작 (프로토타입 screens/demo.js `BaroApp.demo`·panel.js `resetApp`) — 앱 저장·패널 값·시뮬레이터·말한 내용. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoActionsTest {
    private class Rig(scope: TestScope, state: DrivingState = DrivingState()) {
        val clock: Clock = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneId.of("Asia/Seoul"))
        val session = FakeSessionController(state)
        val store = InMemoryBaroStore(clock = clock)
        val panel = FakeDemoPanelRepository()
        val sim = SimController(panel, scope.backgroundScope)
        val voice = InMemoryVoiceLog()
        val actions = DemoActions(session, store, sim, voice, panel, clock)
    }

    @Test
    fun presetReplacesTheAppStateSetsPanelValuesAndRestartsTheSimulator() = runTest {
        val r = Rig(this)
        runCurrent()
        r.sim.load(SimTarget.Scenario("S1-B"))
        r.voice.sayLine("p.tasks")
        val res = r.actions.applyPreset("needs-check")
        assertEquals("데모 상태 '확인할 것 있음'을 적용했어요", res.notice)
        assertEquals(DemoGo.HOME, res.go)
        val p = r.store.current()
        assertTrue(p.onboarding.done)
        assertEquals(4, p.sessions.size)
        assertEquals(listOf(p.sessions[0].id), p.shareQueue)
        assertFalse("패널 네트워크 끊김", r.panel.state.value.demo.network)
        assertEquals("needs-check", r.panel.state.value.demoPreset)
        assertEquals("새로고침처럼 시뮬레이터 처음으로", PlayerState.IDLE, r.sim.view.value.player)
        assertTrue("말한 내용도 지움", r.voice.entries.value.isEmpty())
        // 처음 실행 → 저장을 지우고 첫 화면
        val first = r.actions.applyPreset("first-run")
        assertEquals(DemoGo.ONBOARDING, first.go)
        assertFalse(r.store.current().onboarding.done)
        assertTrue(r.store.current().sessions.isEmpty())
        assertTrue("패널 값도 처음 실행 값", r.panel.state.value.demo.network)
    }

    @Test
    fun presetCancelsARunningSessionWithoutARecord() = runTest {
        val r = Rig(this, DrivingState(phase = SessionPhase.READY, prep = PrepSession(mode = 1)))
        r.actions.applyPreset("stage1-session1")
        assertEquals(SessionPhase.IDLE, r.session.state.value.phase)
        assertEquals(1, r.store.current().sessions.size)
    }

    @Test
    fun resetAppClearsOnlyTheAppStoreAndGoesToTheFirstScreen() = runTest {
        val r = Rig(this)
        r.actions.applyPreset("graduated")
        r.panel.update { it.copy(demo = it.demo.copy(speed = 4.0)) }
        val res = r.actions.resetApp()
        assertEquals(DemoGo.ONBOARDING, res.go)
        assertTrue(r.store.current().sessions.isEmpty())
        assertEquals("패널 설정은 그대로", 4.0, r.panel.state.value.demo.speed, 0.0)
    }

    @Test
    fun profileSwitchGoesHomeOrToTheFirstScreenAndExampleCoursesAreAddedOnce() = runTest {
        val r = Rig(this)
        r.actions.applyPreset("stage1-session1")
        assertNull("같은 프로필이면 아무 일 없음", r.actions.switchProfile("p1"))
        val res = r.actions.switchProfile("p2")
        assertEquals("첫 설정 전 프로필 → 첫 화면", DemoGo.ONBOARDING, res?.go)
        assertEquals("p2", r.store.state.value().activeProfileId)
        assertEquals("p2", r.panel.state.value.demo.profileId)
        assertEquals(DemoGo.HOME, r.actions.switchProfile("p1")?.go)
        assertEquals(1, r.store.current().sessions.size)
        assertEquals("1단계 1회차 마침에는 예시 내 코스가 없다", 2, r.actions.addExampleCourses())
        assertEquals(0, r.actions.addExampleCourses())
        assertEquals(listOf("c1-C", "c2-A"), r.store.current().myCourses.mapNotNull { it.courseRef }.sorted())
    }

    @Test
    fun rulesRunPreparesStageOneAndAutoplaysS9R() = runTest {
        val r = Rig(this)
        runCurrent()
        assertEquals("첫 설정을 마친 뒤에 쓸 수 있어요", r.actions.startRules().notice)
        r.store.updateOnboarding { it.copy(done = true) }
        r.session.setState { it.copy(snapshot = VehicleSnapshot(t = 1.0, gear = Gear.D)) }
        assertEquals("기어를 P로 바꾼 뒤에 눌러 주세요", r.actions.startRules().notice)
        r.session.setState { it.copy(snapshot = VehicleSnapshot(t = 1.0, gear = Gear.P)) }
        val res = r.actions.startRules()
        assertEquals(DemoGo.PREDRIVE, res.go)
        assertEquals(1, r.session.state.value.prep?.mode)
        assertEquals("S9-R", r.sim.view.value.scenario?.id)
        assertEquals("자동 재생 대기", PlayerState.LOADED, r.sim.view.value.player)
    }

    @Test
    fun pickingADestinationSendsItWhenPreparingOtherwiseOnlyGuides() = runTest {
        val r = Rig(this)
        runCurrent()
        // 연습 준비가 없으면 내비 안내만(시나리오 없음)
        val guide = r.actions.pickDestination("mart")
        assertEquals("내비 목적지: 마트 · 안내를 시작해요", guide.notice)
        assertEquals("mart", r.sim.view.value.nav.placeId)
        assertEquals(PlayerState.IDLE, r.sim.view.value.player)
        // 자율 PR-1(코스 없음) → 그 장소를 준비 세션 목적지로 보낸다
        r.session.prepare(PrepSession(mode = 5))
        val sent = r.actions.pickDestination("company")
        assertEquals("내비에 company를 넣었어요", sent.notice)
        assertEquals("company", r.session.state.value.prep?.placeId)
        assertTrue(r.session.state.value.sent)
        assertEquals("모르는 장소", "목적지를 넣지 못했어요", r.actions.pickDestination("nowhere").notice)
    }

    @Test
    fun driverAskGoesToTheSessionOnlyWhilePracticing() = runTest {
        val r = Rig(this)
        assertEquals("연습 중이 아니라 답할 코치가 없어요", r.actions.driverAsk())
        assertTrue(r.session.inputs.isEmpty())
        r.session.setState { it.copy(phase = SessionPhase.ACTIVE) }
        assertEquals("운전자 질문 → 코치가 답해요(직전 30초의 회전·정지)", r.actions.driverAsk())
        assertEquals(listOf<SessionInput>(SessionInput.DriverAsk), r.session.inputs)
    }

    private suspend fun Flow<AppState>.value(): AppState = first()
}
