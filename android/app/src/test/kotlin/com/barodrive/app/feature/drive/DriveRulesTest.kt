package com.barodrive.app.feature.drive

import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.feature.hill.HillDrive
import com.barodrive.app.feature.parking.ParkingGuide
import com.barodrive.app.session.api.ActivePhase
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.app.ui.navigation.driveRouteFor
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Card
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Hints
import com.barodrive.core.model.NavSegment
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.NavStatus
import com.barodrive.core.model.ParkingSetup
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TodayTask
import com.barodrive.core.model.TurnApproach
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WeatherBadge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 주행 화면 규칙(drive.js `heroModel`·`headSub`·`gearText`·`remainText`, session.js `driveScreenId`) + DR-3 흐름. */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveRulesTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val on = { _: String -> Delivery.NOW }

    private fun active(mode: Int = 1, snap: VehicleSnapshot = VehicleSnapshot(t = 10.0, gear = Gear.D, speed = 20.0, blinker = Blinker.OFF), out: StepOutput = StepOutput(t = 10.0)) =
        DrivingState(
            phase = SessionPhase.ACTIVE, activePhase = ActivePhase.DRIVING, snapshot = snap, gear = snap.gear, speed = snap.speed,
            prep = PrepSession(mode = mode, noInMode = 2), mode = mode, noInMode = 2, output = out,
        )

    @Test
    fun coverScreenFollowsTheSession() {
        assertEquals(DriveNotPracticing, DriveRules.screen(DrivingState(gear = Gear.D)))
        assertEquals(DriveOneLine, DriveRules.screen(active()))
        assertEquals(DriveWaitCard, DriveRules.screen(active(out = StepOutput(waiting = true))))
    }

    @Test
    fun gearLabelAndHeadLine() {
        assertEquals(DriveGearLabel.DRIVING, DriveRules.gearLabel(Gear.D, moving = false, screen = DriveOneLine))
        assertEquals(DriveGearLabel.STOPPED, DriveRules.gearLabel(Gear.D, moving = true, screen = DriveWaitCard))
        assertEquals(DriveGearLabel.STOPPED, DriveRules.gearLabel(Gear.N, moving = false, screen = DriveWaitCard))
        assertEquals(DriveGearLabel.NEUTRAL, DriveRules.gearLabel(Gear.N, moving = false, screen = DriveNotPracticing))
        assertEquals(DriveGearLabel.REVERSE, DriveRules.gearLabel(Gear.R, moving = true, screen = DriveOneLine))
        assertEquals(DriveGearLabel.DRIVING, DriveRules.gearLabel(null, moving = true, screen = DriveNotPracticing))
        assertEquals("1단계 · 2회차", DriveRules.headSub(active()))
        val auto = active(mode = 5).let { it.copy(prep = it.prep?.copy(placeId = "mart")) }
        assertEquals("자율 · 마트까지", DriveRules.headSub(auto))
        assertEquals("자율 · 목적지까지", DriveRules.headSub(active(mode = 5)))
        val lic = active(mode = 8, snap = VehicleSnapshot(t = 1.0, gear = Gear.D, nav = NavSnapshot(segment = NavSegment(2, 3))))
        assertEquals("면허 · 구간 2/3", DriveRules.headSub(lic))
        assertEquals("", DriveRules.headSub(DrivingState()))
    }

    @Test
    fun heroPriorityReverseTurnSaidTask() {
        val rev = active(snap = VehicleSnapshot(t = 1.0, gear = Gear.R, blinker = Blinker.OFF))
        assertEquals(DriveHero.Reverse, DriveRules.hero(rev, on))
        assertTrue("주차 모드는 후진 히어로를 쓰지 않는다", DriveRules.hero(rev.copy(mode = 6), on) !is DriveHero.Reverse)

        val turn = TurnApproach(id = "t1", type = "TURN_RIGHT", dir = TurnDir.RIGHT, distance = 77.0, window = 150.0)
        val s = active(out = StepOutput(t = 10.0, turn = turn))
        assertEquals(DriveHero.Turn(TurnDir.RIGHT, 70, BlinkState.OFF, null, ic = false, enter = false), DriveRules.hero(s, on))
        val lit = s.copy(snapshot = s.snapshot?.copy(blinker = Blinker.RIGHT))
        assertEquals(BlinkState.ON, (DriveRules.hero(lit, on) as DriveHero.Turn).state)
        val wrong = s.copy(snapshot = s.snapshot?.copy(blinker = Blinker.LEFT))
        assertEquals(BlinkState.WRONG, (DriveRules.hero(wrong, on) as DriveHero.Turn).state)
        assertFalse("C2가 안 함이면 회전 히어로 없음", DriveRules.hero(s) { Delivery.OFF } is DriveHero.Turn)
        val blind = s.copy(snapshot = s.snapshot?.copy(blinker = null))
        assertFalse("깜빡이를 못 읽으면 회전 히어로 없음", DriveRules.hero(blind, on) is DriveHero.Turn)

        val said = active().copy(lastSay = Utterance(key = "e2", text = "어린이보호구역이야.", item = "E2", t = 5.0))
        assertEquals("항목 아이콘은 items.js(E2 i-shield)", DriveHero.Said("i-shield", "어린이보호구역"), DriveRules.hero(said, on))
        val old = said.copy(output = StepOutput(t = 13.1))
        assertTrue("8 sim초가 지나면 기본 히어로", DriveRules.hero(old, on) is DriveHero.Task)
        val j3 = active().copy(lastSay = Utterance(key = "j3.cold", text = "언 길 조심", item = "J3", t = 9.0))
        assertEquals("언 길 조심", (DriveRules.hero(j3, on) as DriveHero.Said).name)

        val task = active().copy(today = listOf(TodayTask(id = "T-START", label = "엑셀 살살 밟아서 출발하기")))
        assertEquals(DriveHero.Task("오늘 과제", "엑셀 살살 밟아서 출발하기"), DriveRules.hero(task, on))
        assertEquals(DriveHero.Task("오늘 연습", "차량 감 익히기 중이에요"), DriveRules.hero(active(), on))
        assertEquals(DriveHero.Task("자율 모드", "안전하게 목적지까지"), DriveRules.defaultHero(active(mode = 5)))
        val lic = active(mode = 8).let { it.copy(prep = it.prep?.copy(courseId = "c8-C")) }
        assertEquals(DriveHero.Task("운전면허 연습", "시험장 주변 C 코스"), DriveRules.defaultHero(lic))
        assertEquals(DriveHero.Task("", "엔진 힌트"), DriveRules.defaultHero(active(out = StepOutput(hints = Hints(hero = "엔진 힌트")))))
    }

    @Test
    fun badgeRoundAndRemain() {
        assertEquals(WeatherBadge.RAIN, DriveRules.badge(active(out = StepOutput(hints = Hints(badge = WeatherBadge.RAIN)))))
        assertEquals(50, DriveRules.round10(57.0))
        assertEquals(90, DriveRules.round10(99.9))
        assertEquals("여기서 마치면 지금까지 달린 것으로 돌아봐요", DriveRules.remainText(active()))
        val guiding = { m: Double -> active(snap = VehicleSnapshot(t = 1.0, gear = Gear.P, nav = NavSnapshot(status = NavStatus.ROUTE_DRIVE, remain = m))) }
        assertEquals("목적지까지 1.2km 남았어요", DriveRules.remainText(guiding(1234.0)))
        assertEquals("목적지까지 350m 남았어요", DriveRules.remainText(guiding(357.0)))
        assertEquals("목적지까지 10m 남았어요", DriveRules.remainText(guiding(3.0)))
    }

    @Test
    fun movingModesHaveTheirOwnDriveScreen() {
        assertEquals(HillDrive, driveRouteFor(7, PrepSession(mode = 7)))
        assertEquals(ParkingGuide("back", "L"), driveRouteFor(6, PrepSession(mode = 6, parking = ParkingSetup(ParkingType.BACK, TurnDir.LEFT))))
        assertEquals(ParkingGuide(), driveRouteFor(6, null))
        assertNull(driveRouteFor(1, PrepSession(mode = 1)))
    }

    @Test
    fun driveViewModelMapsWaitCardsAndMute() = runTest(main.dispatcher.scheduler) {
        val session = FakeSessionController(active(out = StepOutput(t = 10.0, waiting = true)))
        session.setState { it.copy(cards = listOf(Card(title = "브레이크를 세게 밟았어요"))) }
        val vm = DriveViewModel(session, InMemoryBaroStore())
        val job = launch { vm.uiState.collect {} }
        runCurrent()
        val s = vm.uiState.value
        assertEquals(DriveWaitCard, s.screen)
        assertEquals(1, s.cards.size)
        assertNull("카드가 있으면 아래 말풍선 없음", s.say)
        var muted: Boolean? = null
        vm.toggleMute { muted = it }
        runCurrent()
        assertEquals(true, muted)
        assertTrue(vm.uiState.value.muted)
        job.cancel()
    }

    @Test
    fun stopHereContinuesOrEnds() = runTest(main.dispatcher.scheduler) {
        val session = FakeSessionController(active().copy(activePhase = ActivePhase.STOPPED))
        val vm = StopHereViewModel(session)
        runCurrent()
        assertTrue(vm.uiState.value.active)
        vm.continueHere()
        runCurrent()
        assertTrue(vm.uiState.value.waiting)
        val finished = launch { session.events.first { it is SessionEvent.Finished } }
        runCurrent()
        vm.endHere { error("저장돼야 해요") }
        runCurrent()
        assertTrue(finished.isCompleted)
        assertFalse(vm.uiState.value.active)
    }
}
