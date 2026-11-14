package com.barodrive.core.engine.detect.license

import com.barodrive.core.engine.CoachEngine
import com.barodrive.core.engine.NAV0
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.detect.Detectors
import com.barodrive.core.engine.drive
import com.barodrive.core.engine.hold
import com.barodrive.core.engine.ramp
import com.barodrive.core.engine.review.LicenseCalc
import com.barodrive.core.engine.session.DetectorOff
import com.barodrive.core.engine.snap
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSegment
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.NavStatus
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프로토타입 t8_license.test.js의 검출기 부분 — 조수석 이탈(M2 + 안전 카드 lic.alone), 구간 진행(nav.segment·경유지 사건·도착 → 힌트·회차 메모),
 * lic.alone 대사. 유효기간·LC-1·장면 매핑·코스 진행 계산은 [com.barodrive.core.engine.review.LicenseCalcTest],
 * S8-C 시나리오(시뮬레이터)는 골든 회차로 [com.barodrive.core.engine.golden.GoldenModesScenarioTest]가 본다.
 */
class LicenseDetectorsTest {
    private val licDets = Detectors.all.filter { it.id.startsWith("license.") }

    private fun session(cfg: SessionConfig) = CoachEngine().createSession(cfg, licDets)

    /** t8 `aloneDrive()`: P → D 출발(6초) → 30km/h 4초 → 달리는 중 조수석이 빔(7초) → 신호 대기(5초 감속 + 4초 정지). */
    private fun aloneDrive(): com.barodrive.core.engine.Drive {
        val ses = session(SessionConfig(mode = 8, courseId = "c8-C", coach = Coach.DAD))
        val on = snap(gear = Gear.D, passenger = true)
        val off = on.copy(passenger = false)
        val seq = ArrayList<VehicleSnapshot>()
        fun push(list: List<VehicleSnapshot>) = seq.addAll(list)
        fun t() = seq.last().t
        push(listOf(snap(t = 0.0, gear = Gear.P, passenger = true)))
        push(ramp(0.0, 30.0, 6.0, t0 = 0.0, tpl = on)) // D 출발
        push(hold(30.0, 4.0, t0 = t(), tpl = on))
        push(hold(30.0, 3.0, t0 = t(), tpl = off)) // 달리는 중 조수석이 빔
        push(hold(30.0, 4.0, t0 = t(), tpl = off))
        push(ramp(30.0, 0.0, 5.0, t0 = t(), tpl = off)) // 신호 대기
        push(hold(0.0, 4.0, t0 = t(), brake = true, tpl = off))
        return drive(ses, seq)
    }

    @Test
    fun passengerLeavesWhileDrivingM2OnceAndSafetyCardAtNextWait() {
        val out = aloneDrive()
        val m2 = out.events.filter { it.item == "M2" }
        assertEquals("빈 동안 한 번", 1, m2.size)
        assertEquals(Triple(EventResult.FAIL, true, Gear.D), Triple(m2[0].result, m2[0].data.alone, m2[0].data.gear))
        assertEquals(1, out.cards.size)
        val c = out.cards[0]
        assertEquals(
            listOf<Any?>("M2", "lic.alone", CardCategory.SAFETY, "조수석이 비었어요", "동승자 없이 연습하면 안 돼요", "i-seat"),
            listOf(c.item, c.key, c.category, c.title, c.detail, c.icon),
        )
        assertTrue("비었을 때가 아니라 다음 신호 대기에", checkNotNull(c.shownAt) > m2[0].t)
        val s = out.say.filter { it.key == "lic.alone" }
        assertEquals("말 1번", 1, s.size)
        assertEquals(Category.SAFETY to "조수석이 비었어. 동승자 없이 연습하면 안 돼.", s[0].category to s[0].text)
    }

    @Test
    fun parkedPassengerCountsWhenDrivingAgainSeatAgainCountsAgainBlindSeatTurnsOff() {
        val ses = session(SessionConfig(mode = 8, courseId = "c8-A"))
        val parkedEmpty = snap(gear = Gear.P, passenger = false)
        var o = drive(ses, listOf(snap(t = 0.0, gear = Gear.P, passenger = false)) + hold(0.0, 2.0, t0 = 0.0, tpl = parkedEmpty))
        assertEquals("P에서는 세지 않음", 0, o.events.count { it.item == "M2" })
        o = drive(ses, ramp(0.0, 20.0, 4.0, t0 = 2.0, tpl = snap(gear = Gear.D, passenger = false)))
        assertEquals("빈 채로 D → 1번", 1, o.events.count { it.item == "M2" })
        o = drive(
            ses,
            hold(20.0, 3.0, t0 = 6.0, tpl = snap(gear = Gear.D, passenger = true)) + hold(20.0, 3.0, t0 = 9.0, tpl = snap(gear = Gear.D, passenger = false)),
        )
        assertEquals("다시 앉았다 비면 또 1번", 1, o.events.count { it.item == "M2" })
        val rec = ses.finish(arrived = false)
        assertEquals(2, rec.license?.alone)
        assertEquals(listOf(Gear.D, Gear.D), rec.license?.aloneAt?.map { it.gear })
        // 좌석을 못 읽으면(신호 지원 끔) license.alone은 꺼지고 구간 검출기는 그대로
        val blind = session(SessionConfig(mode = 8, courseId = "c8-A", caps = SignalCaps(passenger = false)))
        assertEquals(listOf(DetectorOff("license.alone", "caps:passenger")), blind.info().off)
        assertTrue("license.course" in blind.info().detectors)
        assertEquals("모드 8에서만 돈다", emptyList<String>(), session(SessionConfig(mode = 2)).info().detectors)
    }

    /** t8 `nav(i, events)`: 안내 중, 면허 구간 i/3. */
    private fun nav(i: Int, events: List<NavEvent> = emptyList()): NavSnapshot =
        NAV0.copy(status = NavStatus.ROUTE_DRIVE, segment = NavSegment(index = i, count = 3), events = events)

    @Test
    fun segmentProgressFromNavSegmentWaypointsAndArrival() {
        val ses = session(SessionConfig(mode = 8, courseId = "c8-C"))
        var o = drive(ses, listOf(snap(t = 0.0, gear = Gear.D, speed = 20.0, nav = nav(1))))
        val h1 = checkNotNull(o.hints.license)
        assertEquals(Triple("구간 1/3", "구간 1/3 · 시험장 주변 C 코스", "면허 · 구간 1/3"), Triple(h1.text, h1.line, h1.head))
        o = drive(ses, listOf(snap(t = 0.1, gear = Gear.D, speed = 20.0, nav = nav(2, listOf(NavEvent.WaypointArrived(index = 1, segment = 1, kind = "segment"))))))
        assertEquals(2 to 1, o.hints.license?.segment to o.hints.license?.done)
        o = drive(ses, listOf(snap(t = 0.2, gear = Gear.D, speed = 20.0, nav = nav(2, listOf(NavEvent.WaypointArrived(index = 1, segment = 2))))))
        assertEquals("구간 안 경유지(kind 없음)는 구간 끝이 아님", 1, o.hints.license?.done)
        drive(ses, listOf(snap(t = 0.3, gear = Gear.D, speed = 20.0, nav = nav(3, listOf(NavEvent.WaypointArrived(index = 2, segment = 2, kind = "segment"))))))
        o = drive(ses, listOf(snap(t = 0.4, gear = Gear.D, speed = 5.0, nav = nav(3, listOf(NavEvent.Arrived)))))
        assertEquals(true to "구간 3/3", o.hints.license?.complete to o.hints.license?.text)
        val rec = ses.finish(arrived = true)
        val lic = checkNotNull(rec.license)
        assertEquals(listOf<Any?>(true, 3, 3, 3, "C", true), listOf(lic.complete, lic.reached, lic.segmentsDone, lic.segments, lic.letter, lic.test))
        // 1구간에서 강제 도착(주입 '목적지 도착') → 도착이지만 완주 아님
        val s2 = session(SessionConfig(mode = 8, courseId = "c8-B"))
        drive(s2, listOf(snap(t = 0.0, gear = Gear.D, speed = 20.0, nav = nav(1)), snap(t = 0.1, gear = Gear.D, speed = 0.0, nav = nav(1, listOf(NavEvent.Arrived)))))
        val r2 = s2.finish(arrived = true)
        assertEquals(
            listOf<Any?>(true, false, 1, "1구간까지 달렸어요(완주 아님)"),
            listOf(r2.arrived, r2.license?.complete, r2.license?.reached, LicenseCalc.courseResult(r2).text),
        )
        assertEquals(emptyList<String>(), LicenseCalc.coursesAfter(emptyList(), r2))
    }

    @Test
    fun hintAtStartWithoutNavAndPlaceDestination() {
        // 내비 구간 값이 오기 전에도 코스 데이터의 구간 수로 첫 힌트(세션을 만들 때)
        val ses = session(SessionConfig(mode = 8, courseId = "c8-D"))
        assertEquals("구간 1/3 · 시험장 주변 D 코스", ses.hints.license?.line)
        // 시험장 코스가 아닌 장소: 이름은 목적지, 구간 1개, 시험장 코스 아님
        val place = session(SessionConfig(mode = 8, placeId = "clinic", dest = "동네 병원"))
        drive(place, listOf(snap(t = 0.0, gear = Gear.D, speed = 20.0, nav = NAV0.copy(status = NavStatus.ROUTE_DRIVE, events = listOf(NavEvent.Arrived)))))
        assertEquals("구간 1/1 · 동네 병원", place.hints.license?.line)
        val lic = checkNotNull(place.finish(arrived = true).license)
        assertEquals(listOf<Any?>(null, "clinic", null, "동네 병원", false, 1, 1, 1, true), listOf(lic.courseId, lic.placeId, lic.letter, lic.name, lic.test, lic.segments, lic.reached, lic.segmentsDone, lic.complete))
    }

    @Test
    fun licAloneLinesForEveryCoach() {
        val want = mapOf(
            Coach.SIB to "조수석이 비었어. 혼자 연습하면 안 돼.",
            Coach.MOM to "어머, 옆자리가 비었어! 혼자 연습하면 안 돼!",
            Coach.DAD to "조수석이 비었어. 동승자 없이 연습하면 안 돼.",
        )
        for ((c, text) in want) assertEquals("$c", text, Lines.render("lic.alone", c, emptyMap(), Honorific(HonorificType.DAUGHTER), index = 0))
    }
}
