package com.barodrive.app.demo

import android.view.KeyEvent
import androidx.datastore.preferences.core.emptyPreferences
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.data.signal.sim.FakeDemoPanelRepository
import com.barodrive.app.data.signal.sim.SimController
import com.barodrive.app.demo.keyboard.KeyboardDriver
import com.barodrive.app.demo.keyboard.SimKeyboardCar
import com.barodrive.app.feature.share.ShareCenter
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.testing.InMemoryDataStore
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.SignalKey
import com.barodrive.core.sim.PlayerState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 시연 패널 VM: 화면이 시계를 붙잡는 일(PK-2·PR-1 와이퍼), 네트워크 → 공유, 시나리오 고르기, 상태 줄·안내 줄, 신호 지원, 복사 글,
 * 비상등 토글(키보드 W와 같은 값).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class Rig(scope: TestScope) {
        val clock: Clock = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneId.of("Asia/Seoul"))
        val session = FakeSessionController()
        val store = InMemoryBaroStore(clock = clock)
        val panel = FakeDemoPanelRepository()
        val sim = SimController(panel, scope.backgroundScope)
        val voice = InMemoryVoiceLog()
        val share = ShareCenter(store, store, session, clock, scope.backgroundScope)
        val vm = DemoViewModel(
            SettingsRepository(InMemoryDataStore(emptyPreferences())), sim, panel, session, voice, store, share,
            DemoActions(session, store, sim, voice, panel, clock), DemoNaviPicker(),
        )

        init {
            scope.backgroundScope.launch { vm.uiState.collect {} }
            scope.runCurrent()
        }

        /** 실제 [sec]초 동안 돈 틱 수. */
        fun ticks(sec: Double): Int = (0 until Math.round(sec * 10).toInt()).sumOf { sim.advance(0.1).size }
    }

    @Test
    fun pk2KeepsTheParkedClockRunningOnlyWhileOpen() = runTest {
        val r = Rig(this)
        assertEquals("서 있는 차는 시계가 선다", 0, r.ticks(1.0))
        r.vm.onScreen(ScreenId.PK_2)
        runCurrent()
        assertTrue("PK-2가 열려 있으면 ×1로 돈다", r.ticks(1.0) in 9..11)
        r.vm.onScreen(ScreenId.PK_1)
        runCurrent()
        r.ticks(1.0)
        assertEquals(0, r.ticks(1.0))
    }

    @Test
    fun predriveHoldsTheClockWhileTheWiperRunsUpToThirtySeconds() = runTest {
        val r = Rig(this)
        r.session.prepare(PrepSession(mode = 1))
        r.vm.onScreen(ScreenId.PR_1)
        r.vm.setWiper(true)
        runCurrent()
        r.ticks(5.0) // 손 조작 뒤 4초 더 도는 것(keepAlive)을 넘긴다
        assertTrue("와이퍼 30초 전에는 붙잡음", r.ticks(1.0) in 9..11)
        r.session.updatePrep { it.copy(weather = it.weather.copy(wiperSec = 30.0)) }
        runCurrent()
        r.ticks(0.2)
        assertEquals("30초가 지나면 놓음", 0, r.ticks(1.0))
    }

    @Test
    fun networkSwitchGoesToShareAndPanelValuesFollow() = runTest {
        val r = Rig(this)
        assertTrue(r.share.online.value)
        r.vm.setNetwork(false)
        runCurrent()
        assertFalse(r.share.online.value)
        assertFalse(r.vm.uiState.value.tools.network)
        r.vm.setTown("moved")
        r.vm.reloadCongestion()
        runCurrent()
        assertEquals("moved", r.vm.uiState.value.tools.town)
        assertEquals(2, r.vm.uiState.value.tools.congestionSeed)
        assertEquals("코스 혼잡도를 다시 받았어요(시드 2)", r.vm.uiState.value.notice)
    }

    @Test
    fun scenarioPickerOffersTheAppsCourseAndPlaysIt() = runTest {
        val r = Rig(this)
        r.session.prepare(PrepSession(mode = 1, courseId = "c1-B"))
        r.sim.advance(0.1)
        runCurrent()
        val s = r.vm.uiState.value.scenario
        assertEquals("prep", s.options.first().key)
        assertEquals("앱이 고른 코스 (c1-B)", s.options.first().label)
        assertEquals("prep", s.selected)
        assertTrue(s.options.any { it.key == "S9-R" && it.group == "1단계" || it.key == "S9-R" })
        r.vm.togglePlay()
        r.sim.advance(0.1)
        runCurrent()
        assertEquals(PlayerState.PLAYING, r.sim.view.value.player)
        assertEquals("S1-B", r.vm.uiState.value.scenario.selected)
        assertEquals("재생해요", r.vm.uiState.value.notice)
        assertTrue(r.vm.uiState.value.status.startsWith("P · 0km/h"))
    }

    @Test
    fun capsTogglesSummaryStatusAndDriverAskNotice() = runTest {
        val r = Rig(this)
        r.vm.setCap(SignalKey.SPEED, false)
        r.sim.advance(0.1)
        runCurrent()
        val caps = r.vm.uiState.value.caps
        assertEquals("못 읽는 신호 1개: 차속", caps.summary)
        assertEquals(listOf("B1", "E1", "E2", "F1", "F2", "HL2", "PK2"), caps.rows.first { it.row.key == SignalKey.SPEED }.items.sorted())
        assertTrue(r.vm.uiState.value.status.contains("못 읽는 신호 1"))
        assertFalse(r.sim.caps.value.speed)
        r.vm.allCaps()
        runCurrent()
        assertTrue(r.sim.caps.value.speed)
        r.vm.inject(DemoTables.DRIVER_ASK_ID)
        runCurrent()
        assertEquals("연습 중이 아니라 답할 코치가 없어요", r.vm.uiState.value.notice)
        assertEquals(listOf("운전자"), r.vm.uiState.value.injections.map { it.group }.filter { it == "운전자" })
    }

    @Test
    fun blinkerOffEndsAStageTwoRunWithoutARecordAndThemeFollowsNight() = runTest {
        val r = Rig(this)
        r.vm.setCap(SignalKey.BLINKER, false)
        runCurrent()
        r.session.setState { it.copy(phase = SessionPhase.ACTIVE, mode = 2, noInMode = 1) }
        r.session.emit(SessionEvent.Started(mode = 2, noInMode = 1, stage = 2))
        runCurrent()
        assertEquals("기록 없이 끝냄", SessionPhase.IDLE, r.session.state.value.phase)
        assertEquals("깜빡이 신호를 못 읽어 2단계를 시작할 수 없어요 · 회차를 기록 없이 끝냈어요", r.vm.uiState.value.notice)
        // 다른 모드는 막지 않는다
        r.session.setState { it.copy(phase = SessionPhase.ACTIVE, mode = 1, noInMode = 1) }
        r.session.emit(SessionEvent.Started(mode = 1, noInMode = 1, stage = 1))
        runCurrent()
        assertEquals(SessionPhase.ACTIVE, r.session.state.value.phase)
        assertEquals("8은 '운전면허 연습을'", "깜빡이 신호를 못 읽어 운전면허 연습을 시작할 수 없어요", DemoTables.startBlock(8, r.sim.caps.value))
        // 앱 테마: 패널 주간/야간(야간이 처음 값)
        assertTrue(r.vm.night.value)
        r.vm.setNight(false)
        runCurrent()
        assertFalse(r.vm.night.value)
    }

    @Test
    fun paceSegmentStartsAtRealTimeAndXnTurnsItOff() = runTest {
        val r = Rig(this)
        r.sim.advance(0.1)
        runCurrent()
        assertTrue("처음 값 '실제'", r.vm.uiState.value.scenario.realtime)
        r.vm.setSpeed(4)
        r.sim.advance(0.1)
        runCurrent()
        val x4 = r.vm.uiState.value.scenario
        assertFalse(x4.realtime)
        assertEquals(4, x4.speed)
        assertFalse(r.panel.state.value.demo.realtime)
        r.vm.setRealtime()
        r.sim.advance(0.1)
        runCurrent()
        assertTrue(r.vm.uiState.value.scenario.realtime)
        r.vm.setSpeed(2)
        r.vm.resetPanel()
        r.sim.advance(0.1)
        runCurrent()
        assertTrue("[패널 설정 초기화] → '실제'", r.vm.uiState.value.scenario.realtime)
    }

    @Test
    fun gearLaterNoticeComesFromTheSimulatorSoKeysGetItToo() = runTest {
        val r = Rig(this)
        r.vm.shift(com.barodrive.core.model.Gear.D)
        r.vm.setTargetSpeed(30f)
        r.ticks(10.0)
        r.vm.shift(com.barodrive.core.model.Gear.P)
        runCurrent()
        assertEquals("멈춘 뒤 P로 바꿔요(브레이크)", r.vm.uiState.value.notice)
    }

    @Test
    fun hazardToggleAndTheWKeyShareOneValueAndACarResetTurnsItOff() = runTest {
        val r = Rig(this)
        val keys = KeyboardDriver(SimKeyboardCar(r.sim))
        r.vm.setBlinker(Blinker.LEFT)
        r.vm.setHazard(true)
        runCurrent()
        assertTrue("패널 토글 → 켬", r.vm.uiState.value.car.hazard)
        assertEquals("깜빡이와 따로", Blinker.LEFT, r.vm.uiState.value.car.car.blinker)
        keys.keyDown(KeyEvent.KEYCODE_W, 0)
        keys.keyUp(KeyEvent.KEYCODE_W, 20)
        runCurrent()
        assertFalse("패널로 켠 것을 W가 끈다(같은 호출·같은 값)", r.vm.uiState.value.car.hazard)
        assertEquals(Blinker.LEFT, r.vm.uiState.value.car.car.blinker)
        keys.keyDown(KeyEvent.KEYCODE_W, 100)
        keys.keyUp(KeyEvent.KEYCODE_W, 120)
        runCurrent()
        assertTrue("W → 켬, 패널 토글에 보임", r.vm.uiState.value.car.hazard)
        r.vm.applyPreset("needs-check")
        runCurrent()
        assertFalse("데모 상태 적용 → 꺼짐", r.vm.uiState.value.car.hazard)
        r.vm.setHazard(true)
        runCurrent()
        assertTrue(r.vm.uiState.value.car.hazard)
        r.vm.resetApp()
        runCurrent()
        assertFalse("처음 실행으로 되돌리기 → 꺼짐", r.vm.uiState.value.car.hazard)
    }

    @Test
    fun logTextFollowsTheFilter() = runTest {
        val r = Rig(this)
        r.voice.sayLine("p.tasks")
        runCurrent()
        assertEquals("[--:--] 코치(아빠/배우자) · 안내 · p.tasks — p.tasks", r.vm.logText())
        assertEquals(1, r.vm.uiState.value.voice.coach)
        r.vm.setLogFilter(LogFilter(coach = false))
        runCurrent()
        assertEquals("", r.vm.logText())
        assertTrue(r.vm.uiState.value.voice.rows.isEmpty())
    }
}
