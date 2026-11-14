package com.barodrive.app.feature.predrive

import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.session.api.SendResult
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.testing.FakeSignalSource
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.NaviPlace
import com.barodrive.core.model.PassengerAnswer
import com.barodrive.core.model.PrepChecks
import com.barodrive.core.model.PrepLines
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.TodayTask
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** PR-1 규칙: 프로토타입 t7_weather(날씨 줄 모델) · t10_caps(신호 확인 줄·시작 막기) + predrive.js 점검·조수석 줄 + 화면 흐름. */
@OptIn(ExperimentalCoroutinesApi::class)
class PredriveRulesTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun snap(wiper: Boolean? = false, temp: Double? = 15.0) = VehicleSnapshot(t = 0.0, wiper = wiper, outsideTemp = temp)

    @Test
    fun signalLineAndStartBlock() {
        val all = SignalCaps()
        assertEquals(SignalLine(true, emptyList(), "차 신호 확인 끝", "깜빡이·브레이크·핸들·좌석"), PredriveRules.signals(1, all))
        val sp = PredriveRules.signals(1, all.copy(speed = false))
        assertEquals("못 읽는 신호: 차속", sp.title)
        assertEquals("이번엔 출발·정지·속도 과제를 빼요", sp.text)
        val many = PredriveRules.signals(2, all.copy(speed = false, nav = false, handbrake = false))
        assertEquals("못 읽는 신호: 차속·내비·사이드브레이크", many.title)
        assertEquals("이번엔 출발·정지·속도·회전 안내·제한속도 과제를 빼요 · 목적지는 내비에 직접 넣어요 · 사이드브레이크는 말로 물어봐요", many.text)
        assertEquals("자율은 점수에서", "이번엔 출발·정지·속도 항목을 점수에서 빼요", PredriveRules.signals(5, all.copy(speed = false)).text)
        assertEquals("핸들 단계는 화면을 눌러 넘겨요", PredriveRules.signals(6, all.copy(steer = false)).text)
        assertEquals("뒤로 밀림은 기록하지 않아요", PredriveRules.signals(7, all.copy(reverseSpeed = false)).text)
        assertEquals("", PredriveRules.signals(1, all.copy(reverseSpeed = false)).text)
        assertEquals("코스는 가까운 순이에요", PredriveRules.signals(1, all.copy(trip = false)).text)

        val off = all.copy(blinker = false)
        assertEquals(
            StartBlock("깜빡이 신호를 못 읽어 2단계를 시작할 수 없어요", "깜빡이 과제가 중심인 연습이라 깜빡이 신호가 꼭 필요해요. 다른 연습을 골라 주세요."),
            PredriveRules.startBlock(2, off),
        )
        assertEquals("깜빡이 신호를 못 읽어 운전면허 연습을 시작할 수 없어요", PredriveRules.startBlock(8, off)?.title)
        for (m in listOf(1, 3, 4, 5, 6, 7)) assertNull("모드 $m", PredriveRules.startBlock(m, off))
        assertNull(PredriveRules.startBlock(2, all))
    }

    @Test
    fun checksAndPassengerRows() {
        val prep = PrepSession(mode = 3)
        assertEquals("1단계 · 차량 감 익히기 · 2회차", PredriveRules.subtitle(PrepSession(mode = 1, noInMode = 2)))
        assertEquals("자율 모드 · 1회차", PredriveRules.subtitle(PrepSession(mode = 5, noInMode = 1)))
        assertEquals(BeltStatus.UNKNOWN, PredriveRules.belt(VehicleSnapshot(t = 0.0)))
        assertEquals(BeltStatus.OFF, PredriveRules.belt(VehicleSnapshot(t = 0.0, beltDriver = false)))
        assertEquals("PB1이 안 함이면 숨김", HandbrakeStatus.HIDDEN, PredriveRules.handbrake(null, prep, Delivery.OFF))
        assertEquals(HandbrakeStatus.ASK, PredriveRules.handbrake(VehicleSnapshot(t = 0.0), prep, Delivery.NOW))
        assertEquals(HandbrakeStatus.CONFIRMED, PredriveRules.handbrake(VehicleSnapshot(t = 0.0), prep.copy(checks = PrepChecks(pbConfirmed = true)), Delivery.NOW))
        assertEquals(HandbrakeStatus.ON, PredriveRules.handbrake(VehicleSnapshot(t = 0.0, handbrake = true), prep, Delivery.NOW))
        assertEquals(HandbrakeStatus.OFF, PredriveRules.handbrake(VehicleSnapshot(t = 0.0, handbrake = false), prep, Delivery.AFTER))
        val aboard = VehicleSnapshot(t = 0.0, passenger = true)
        assertEquals(PassengerStatus.ABOARD_PENDING, PredriveRules.passenger(aboard, prep, Delivery.NOW))
        assertEquals(PassengerStatus.ABOARD_SAID, PredriveRules.passenger(aboard, prep.copy(lines = PrepLines(l1Said = true)), Delivery.NOW))
        assertEquals(PassengerStatus.ABOARD_QUIET, PredriveRules.passenger(aboard, prep, Delivery.AFTER))
        assertEquals(PassengerStatus.EMPTY, PredriveRules.passenger(VehicleSnapshot(t = 0.0, passenger = false), prep, Delivery.NOW))
        assertTrue("3·4단계에 조수석이 비면 L2", PredriveRules.l2(VehicleSnapshot(t = 0.0, passenger = false), 3))
        assertFalse(PredriveRules.l2(VehicleSnapshot(t = 0.0, passenger = false), 2))
    }

    @Test
    fun courseCardComesFromTheCourseOrPlace() {
        val p = InMemoryBaroStore().snapshot.value.activeProfile
        val c = PredriveRules.course(PrepSession(mode = 1, courseId = "c1-B"), p)
        assertEquals(CourseKind.COURSE, c?.kind)
        assertEquals("B", c?.letter)
        assertEquals("시민체육관 주차장", c?.name)
        val place = PredriveRules.course(PrepSession(mode = 5, placeId = "mart"), p)
        assertEquals(CourseKind.DESTINATION, place?.kind)
        assertNull("코스 없이 연습", PredriveRules.course(PrepSession(mode = 1), p))
    }

    private val home = NaviPlace(name = "우리 집", lat = 37.4401, lng = 127.1377, address = "성남시 수정구", poiId = "p-home")

    @Test
    fun naviCourseCardShowsTheNameWithoutDistance() {
        val mc = MyCourse(id = "mc-navi", name = "우리 집", mode = 2, source = CourseSource.BOOKMARK, town = "home", navi = home)
        val p = InMemoryBaroStore().snapshot.value.activeProfile.copy(myCourses = listOf(mc))
        val c = PredriveRules.course(PrepSession(mode = 2, navi = home), p)
        assertEquals(CourseKind.MINE, c?.kind)
        assertEquals("우리 집", c?.name)
        assertTrue("내 코스 글자", c?.letter?.isNotEmpty() == true)
        assertNull(c?.km)
        assertNull(c?.min)
        assertNull(c?.congestion)
        val notMine = PredriveRules.course(PrepSession(mode = 2, navi = home), p.copy(myCourses = emptyList()))
        assertEquals(CourseKind.DESTINATION, notMine?.kind)
    }

    @Test
    fun aRefusedSendShowsWhyAndKeepsTheSendButton() = runTest(main.dispatcher.scheduler) {
        val session = FakeSessionController()
        session.prepare(PrepSession(mode = 1, courseId = "c1-B"))
        session.refuseSend = "이 코스는 좌표가 없어 내비로 보낼 수 없어요. 내비에서 목적지를 넣고 출발해 주세요"
        val vm = PredriveViewModel(session, InMemoryBaroStore(), FakeSignalSource(), emptySet())
        runCurrent()
        var got: SendResult? = null
        vm.send { got = it }
        runCurrent()
        assertFalse(got?.sent ?: true)
        assertEquals(session.refuseSend, got?.toast)
        assertEquals("보내지 않았으면 [내비로 보내고 출발] 그대로", PredrivePrimary.Send, vm.uiState.value.primary)
    }

    @Test
    fun aNaviPrepIsSendable() = runTest(main.dispatcher.scheduler) {
        val session = FakeSessionController()
        session.prepare(PrepSession(mode = 2, navi = home))
        val vm = PredriveViewModel(session, InMemoryBaroStore(), FakeSignalSource(), emptySet())
        runCurrent()
        assertEquals(PredrivePrimary.Send, vm.uiState.value.primary)
    }

    @Test
    fun viewModelEntersOnceShowsTasksAndSends() = runTest(main.dispatcher.scheduler) {
        val tasks = listOf(TodayTask(id = "T-START", label = "엑셀 살살 밟아서 출발하기"))
        val session = FakeSessionController(today = tasks)
        session.prepare(PrepSession(mode = 1, courseId = "c1-B"))
        session.setState { it.copy(snapshot = VehicleSnapshot(t = 0.0, beltDriver = true, passenger = true, wiper = false)) }
        val vm = PredriveViewModel(session, InMemoryBaroStore(), FakeSignalSource(), emptySet())
        runCurrent()
        assertTrue("들어오면 오늘 과제를 읽는다", "p.tasks" in session.linesSaid)
        assertTrue("조수석이 앉아 있으면 l1", "l1" in session.linesSaid)
        val s = vm.uiState.value
        assertFalse(s.noPrep)
        assertEquals(tasks, s.tasks)
        assertEquals(BeltStatus.ON, s.belt)
        assertEquals(PredrivePrimary.Send, s.primary)
        var toast: String? = "x"
        vm.send { toast = it?.toast }
        runCurrent()
        assertEquals("내비에 c1-B를 넣었어요", toast)
        assertTrue(vm.uiState.value.primary is PredrivePrimary.Sent)
        vm.answerPassenger(PassengerAnswer.ALONE)
        runCurrent()
        assertEquals(PassengerAnswer.ALONE, session.state.value.prep?.passengerAnswer)
    }

    @Test
    fun variantsReshapeTheScreen() = runTest(main.dispatcher.scheduler) {
        val voice = InMemoryVoiceLog()
        val variants = setOf(AutonomousPredriveVariant(), HillPredriveVariant(voice), fixedPassengerVariant)
        // 자율: 과제·코스 카드 없음, 맨 위 카드, 날씨 줄 있음
        val auto = FakeSessionController(today = listOf(TodayTask(id = "T-START", label = "x")))
        auto.prepare(PrepSession(mode = 5))
        var vm = PredriveViewModel(auto, InMemoryBaroStore(), FakeSignalSource(), variants)
        runCurrent()
        var s = vm.uiState.value
        assertEquals(listOf(false, false, true), listOf(s.showTasks, s.showCourse, s.variantTop))
        assertFalse("자율은 오늘 과제를 읽지 않는다", "p.tasks" in auto.linesSaid)
        assertEquals(40, s.weatherOrder)
        // 언덕: 들어올 때 p.hill
        val hill = FakeSessionController()
        hill.prepare(PrepSession(mode = 7))
        vm = PredriveViewModel(hill, InMemoryBaroStore(), FakeSignalSource(), variants)
        runCurrent()
        assertEquals(listOf("p.hill"), voice.entries.value.map { it.key })
        assertTrue(vm.uiState.value.variantTop)
        // 고정 동승자 카드 + L1 문장(조수석에 앉음)
        val lic = FakeSessionController()
        lic.prepare(PrepSession(mode = 8, courseId = "c8-C"))
        lic.setState { it.copy(snapshot = VehicleSnapshot(t = 0.0, passenger = true)) }
        vm = PredriveViewModel(lic, InMemoryBaroStore(), FakeSignalSource(), variants)
        runCurrent()
        s = vm.uiState.value
        assertEquals("동승자 확인 끝", s.fixedPassenger?.label)
        assertTrue("L1 문장이 붙는다: ${s.fixedQuote}", !s.fixedQuote.isNullOrEmpty())
        assertEquals("운전면허 연습 · C 코스 · 3구간", s.subtitle)
        // 주차(6)는 날씨 줄이 없다
        val park = FakeSessionController()
        park.prepare(PrepSession(mode = 6))
        vm = PredriveViewModel(park, InMemoryBaroStore(), FakeSignalSource(), variants)
        runCurrent()
        assertNull(vm.uiState.value.weatherOrder)
    }

    /** 면허 변형과 같은 모양(Context 없이): 부제 · 고정 동승자 카드. */
    private val fixedPassengerVariant = object : PredriveVariant {
        override val mode: Int = 8
        override fun subtitle(prep: PrepSession): String = com.barodrive.app.feature.license.LicenseText.predriveSubtitle(prep)
        override fun passengerCard(prep: PrepSession, snapshot: VehicleSnapshot?): FixedPassenger? =
            com.barodrive.app.feature.license.LicenseText.predrivePassenger(true, snapshot?.passenger)?.let {
                FixedPassenger(title = "동승자", label = it.label, sub = it.sub, ok = it.ok, l1Quote = true)
            }
    }

    @Test
    fun noPrepSendsBackToCourses() = runTest(main.dispatcher.scheduler) {
        val session = FakeSessionController(DrivingState(phase = SessionPhase.IDLE))
        val vm = PredriveViewModel(session, InMemoryBaroStore(), FakeSignalSource(), emptySet())
        runCurrent()
        assertTrue(vm.uiState.value.noPrep)
        assertEquals(1, vm.uiState.value.stage)
    }
}
