package com.barodrive.core.engine.detect.hill

import com.barodrive.core.engine.CoachEngine
import com.barodrive.core.engine.CoachSession
import com.barodrive.core.engine.Drive
import com.barodrive.core.engine.NAV0
import com.barodrive.core.engine.content.Items
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.det
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.Detectors
import com.barodrive.core.engine.drive
import com.barodrive.core.engine.of
import com.barodrive.core.engine.r1
import com.barodrive.core.engine.snap
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.BlockedUtterance
import com.barodrive.core.model.CautionItem
import com.barodrive.core.model.CautionType
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillHint
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.HillTaskCount
import com.barodrive.core.model.HillUpAttempt
import com.barodrive.core.model.HillUpSummary
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.Utterance
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프로토타입 t6_hill.test.js의 엔진 부분 — 오르막 단계 넘김, 밀림 0.5초 경계, 내리막 8초 계속 밟음, 바퀴 방향 세 상황, 검출기 등록·신호 지원,
 * 연습 칩·단계 칸, 화면 힌트, 회차 결과(rec.hill)·리뷰 과제, 언덕 주차 P의 hints.arrived, P 때 말 순서.
 * 합성 스냅숏 시험은 언덕 검출기 셋만 넣은 세션으로 본다. 언덕 데이터는 [com.barodrive.core.engine.content.HillGuideTest],
 * S7-B 시나리오(시뮬레이터)는 골든 회차로 [com.barodrive.core.engine.golden.GoldenModesScenarioTest]가 본다.
 */
class HillDetectorsTest {
    private val hillDets: List<DetectorDef> = Detectors.all.filter { it.id.startsWith("hill.") }

    /** 한 표본에 바꿀 값 (t6 `add`의 patch). nav만 그 표본에 쓰고 나머지는 이어 쓴다. null이면 그대로. */
    private class Pt(
        val gear: Gear? = null, val speed: Double? = null, val brake: Boolean? = null, val steer: Double? = null, val handbrake: Boolean? = null,
        val nav: NavSnapshot? = null,
    )

    /** t6 `seq()`: 0.1초 간격 스냅숏(기본 D · 0km/h · 브레이크 · 핸들 0 · 사이드브레이크 풂). [take]는 지난번 뒤에 더한 것만. */
    private class Seq {
        private val list = ArrayList<Any>()
        private var taken = 0
        private var trip = 0.0
        var t = 0.0
        private var gear = Gear.D
        private var speed = 0.0
        private var brake = true
        private var steer = 0.0
        private var handbrake = false

        fun add(n: Int, fn: (Double, Int) -> Pt): Seq {
            for (i in 0 until n) {
                t = r1(t + 0.1)
                val p = fn(t, i)
                p.gear?.let { gear = it }
                p.speed?.let { speed = it }
                p.brake?.let { brake = it }
                p.steer?.let { steer = it }
                p.handbrake?.let { handbrake = it }
                trip += abs(speed) / 3.6 * 0.1
                list += snap(
                    t = t, gear = gear, speed = speed, brake = brake, steer = steer, handbrake = handbrake, tripDist = r1(trip), nav = p.nav ?: NAV0,
                )
            }
            return this
        }

        fun add(n: Int, p: Pt): Seq = add(n) { _, _ -> p }

        fun input(ev: SessionInput): Seq {
            list += ev
            return this
        }

        fun take(): List<Any> = list.subList(taken, list.size).toList().also { taken = list.size }
    }

    /** t6 `runner(over, dets)`: 세션 하나 + 스냅숏 + 모은 출력. */
    private inner class Runner(cfg: SessionConfig = SessionConfig(mode = 7, coach = Coach.DAD), dets: List<DetectorDef> = hillDets) {
        val s: CoachSession = CoachEngine().createSession(cfg, dets)
        val b = Seq()
        val say = ArrayList<Utterance>()
        val blocked = ArrayList<BlockedUtterance>()
        val events = ArrayList<JudgeEvent>()

        fun go(): Drive {
            val o = drive(s, b.take())
            say += o.say
            blocked += o.blocked
            events += o.events
            return o
        }

        fun hint(): HillHint = checkNotNull(s.hints.hill)

        fun finish(): SessionRecord = s.finish(arrived = true)

        fun said(key: String) = say.any { it.key == key }

        fun count(key: String) = say.count { it.key == key }

        fun ev(item: String, result: String? = null) = events.of(item, result)
    }

    private fun capsRunner(caps: SignalCaps) = Runner(SessionConfig(mode = 7, coach = Coach.DAD, caps = caps))

    /** 오르막: 출발해 20km/h로 달리다 2초에 걸쳐 섬(마지막 표본이 0) (t6 `driveAndStop`). */
    private fun driveAndStop(b: Seq): Seq = b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }.add(30, Pt(speed = 20.0, brake = false))
        .add(20) { _, i -> Pt(speed = 20.0 - (i + 1), brake = true) }

    /** 오르막 3단계까지: 움직였다 섬 → 1초(2단계) → 2단계 문장이 끝날 때까지 서 있음 → [했어요] → 3단계 문장이 끝날 때까지 (t6 `toStep3`). */
    private fun toStep3(r: Runner) {
        driveAndStop(r.b).add(10, Pt(speed = 0.0, brake = true)).add(45, Pt(speed = 0.0, brake = true)).input(SessionInput.HillPress)
            .add(60, Pt(speed = 0.0, brake = true))
        r.go()
        assertEquals("3단계", 3, r.hint().step)
    }

    /** 내리막 주의 구간 하나(남은 거리, id) (t6 `cau`). */
    private fun cau(remain: Double, id: String = "ca1") = NAV0.copy(cautions = listOf(CautionItem(id, CautionType.DOWNHILL_CAUTION_AREA, remain)))

    private fun task(rec: SessionRecord, id: String) = checkNotNull(rec.hill).tasks[id]

    private fun tasks(rec: SessionRecord) = rec.tasks.map { Triple(it.id, it.success, it.total) }

    /* ================================================================ 대사 */
    @Test
    fun hillLinesExistForEveryCoachAndStepSentencesAreTheSame() {
        val steps = listOf("hl1.up.1", "hl1.up.2", "hl1.up.3", "hl1.up.4", "hl1.down.1", "hl1.down.2", "hl1.down.3", "hl1.park.1", "hl1.park.2", "hl1.park.3")
        for (k in listOf("hl2", "hl3.ok", "hl3.wrong", "hl1.cheer", "pb2.hill", "p.hill", "rv.hill") + steps) {
            for (c in Coach.entries) assertTrue("$k $c", Lines.sentences(k, c).let { l -> l.isNotEmpty() && l.all { it.isNotBlank() } })
        }
        for (k in steps) assertEquals("$k 세 코치 같은 문장", 1, Coach.entries.map { Lines.sentences(k, it)[0] }.toSet().size)
        assertEquals("브레이크에서 엑셀로 발을 빠르게 옮기고, 엑셀을 살살 밟으세요.", Lines.sentences("hl1.up.3", Coach.DAD)[0])
        assertEquals("브레이크를 계속 밟지 말고 여러 번 나눠 밟으세요.", Lines.sentences("hl1.down.2", Coach.MOM)[0])
        fun l(k: String, c: Coach, vars: Map<String, Any?> = emptyMap(), h: Honorific? = null) = Lines.render(k, c, vars, h, index = 0)
        assertEquals("브레이크를 밟은 채 앞바퀴를 연석 쪽(오른쪽)으로 돌리세요.", l("hl1.park.2", Coach.SIB, mapOf("side" to "연석 쪽(오른쪽)")))
        assertEquals("앞바퀴를 오른쪽으로 돌려 보자.", l("hl3.wrong", Coach.DAD, mapOf("dir" to "R")))
        assertEquals("바퀴 왼쪽으로 돌리자!", l("hl3.wrong", Coach.MOM, mapOf("dir" to "L")))
        assertEquals("언덕에선 사이드브레이크 꼭 채우자.", l("pb2.hill", Coach.DAD))
        assertEquals("어머 언덕도 해냈어 우리 딸! 다음엔 오르막 출발만 천천히!", l("rv.hill", Coach.MOM, mapOf("taskMom" to "오르막 출발만 천천히"), Honorific(HonorificType.DAUGHTER)))
        assertEquals("어머 언덕도 해냈어! 다음엔 언덕 주차만 꼼꼼히!", l("rv.hill", Coach.MOM, mapOf("taskMom" to "언덕 주차만 꼼꼼히"), Honorific()))
        assertEquals("언덕 연습 고생했어. 다음엔 내리막 브레이크 하나만 해 보자.", l("rv.hill", Coach.DAD, mapOf("taskDad" to "내리막 브레이크")))
    }

    /* ================================================================ 검출기 등록·신호 지원 */
    @Test
    fun hillDetectorsRunInMode7OnlyAndTurnOffWithoutReverseSpeedOrSteer() {
        assertEquals(
            listOf(listOf(7) to emptyList(), listOf(7) to listOf(SignalKey.SPEED, SignalKey.REVERSE_SPEED), listOf(7) to listOf(SignalKey.STEER)),
            listOf("hill.HL1", "hill.HL2", "hill.HL3").map { det(it).modes to det(it).needs },
        )
        fun info(cfg: SessionConfig) = CoachEngine().createSession(cfg).info()
        assertEquals(listOf("hill.HL1", "hill.HL2", "hill.HL3"), info(SessionConfig(mode = 7)).detectors.filter { it.startsWith("hill.") })
        assertEquals(emptyList<String>(), info(SessionConfig(mode = 1)).detectors.filter { it.startsWith("hill.") })
        val off = info(SessionConfig(mode = 7, caps = SignalCaps(reverseSpeed = false, steer = false)))
        assertEquals(listOf("hill.HL1"), off.detectors.filter { it.startsWith("hill.") })
        assertTrue(off.off.any { it.id == "hill.HL2" && it.reason == "caps:reverseSpeed" })
        assertTrue(off.off.any { it.id == "hill.HL3" && it.reason == "caps:steer" })
        assertEquals(listOf("HL2"), Items.itemsOffByCaps(SignalCaps(reverseSpeed = false)))
    }

    /* ================================================================ 오르막 출발 */
    @Test
    fun uphillStepsFromStopToPressToReleaseToThreeSecondsAbove3() {
        val r = Runner()
        r.b.add(30, Pt(speed = 0.0, brake = true)) // 출발 전 D로 3초 서 있음
        r.go()
        assertEquals(Triple(HillPractice.UP, 1, 4), Triple(r.hint().practice, r.hint().step, r.hint().steps))
        assertEquals("처음 움직이기 전에는 말하지 않음", 0, r.say.size)
        driveAndStop(r.b).add(9, Pt(speed = 0.0, brake = true))
        r.go()
        assertTrue("움직일 때 1단계 문장", r.said("hl1.up.1"))
        assertEquals("0.9초 섬 — 아직 1단계", 1, r.hint().step)
        r.b.add(1, Pt(speed = 0.0, brake = true))
        r.go()
        assertEquals("1초 섬 — 2단계", 2, r.hint().step)
        assertTrue("2단계는 화면 누름", r.hint().tap)
        r.b.add(45, Pt(speed = 0.0, brake = true)).input(SessionInput.HillPress)
        r.go()
        assertTrue(r.said("hl1.up.2"))
        assertEquals("3단계: 말풍선(hl1.cheer)", Triple(3, false, true), Triple(r.hint().step, r.hint().tap, r.hint().cheer))
        r.b.add(60, Pt(speed = 0.0, brake = true))
        r.go()
        assertTrue(r.said("hl1.up.3"))
        r.b.add(5, Pt(speed = 0.0, brake = false)) // 브레이크 뗌(아직 안 움직임)
        r.go()
        assertEquals(4, r.hint().step)
        assertFalse("4단계 문장은 차가 앞으로 나갈 때", r.said("hl1.up.4"))
        r.b.add(10) { _, i -> Pt(speed = (i + 1) * 0.5, brake = false) } // 0.5 → 5km/h(3.5부터 3 넘음)
        r.go()
        assertTrue("앞으로 1km/h 나가면 4단계 문장", r.said("hl1.up.4"))
        r.b.add(26, Pt(speed = 5.0, brake = false)) // 3km/h 넘은 지 2.9초
        r.go()
        assertEquals("2.9초 — 아직", 4, r.hint().step)
        r.b.add(1, Pt(speed = 5.0, brake = false))
        r.go()
        assertEquals("3초 — 시도 끝, 다시 1단계", Triple(1, 1, 0), Triple(r.hint().step, r.hint().up.attempts, r.hint().up.rollbacks))
        assertEquals(listOf(EventResult.OK to 1), r.ev("HL2").map { it.result to it.data.attempt })
        assertEquals(listOf("hl1.up.1", "hl1.up.2", "hl1.up.3", "hl1.up.4"), r.say.map { it.key }.take(4))
        assertTrue(r.say.all { it.category == Category.INFO })
        r.b.add(40, Pt(speed = 5.0, brake = false))
        r.go()
        assertEquals("다시 시도 — 1단계 문장을 다시", 2, r.count("hl1.up.1"))
        val rec = r.finish()
        assertEquals(HillTaskCount(success = 1, total = 1), task(rec, "T-HILLUP"))
        assertEquals(HillUpSummary(attempts = 1, rollbacks = 0, rollOff = false, list = listOf(HillUpAttempt(1, rolled = false, completed = true))), rec.hill?.up)
        assertEquals(listOf(Triple("T-HILLUP", 1, 1), Triple("T-HILLDOWN", 0, 0), Triple("T-HILLPARK", 0, 0)), tasks(rec))
    }

    @Test
    fun uphillStartWithoutPressCountsAndPBeforeReleaseIsNotAnAttempt() {
        val r = Runner()
        driveAndStop(r.b).add(15, Pt(speed = 0.0, brake = true))
        r.go()
        assertEquals(2, r.hint().step)
        r.b.add(10) { _, i -> Pt(speed = (i + 1) * 0.6, brake = false) }.add(35, Pt(speed = 6.0, brake = false))
        r.go()
        assertEquals("누르지 않고 출발 → 4단계 → 끝", 1 to 1, r.hint().step to r.hint().up.attempts)
        driveAndStop(r.b).add(15, Pt(speed = 0.0, brake = true)).input(SessionInput.HillPress).add(5, Pt(speed = 0.0, brake = true))
            .add(30, Pt(gear = Gear.P, speed = 0.0, brake = false))
        r.go()
        val h = r.hint()
        assertEquals("3단계에서 P → 버림", listOf<Any>(HillPractice.UP, 1, 1, true), listOf(h.practice, h.step, h.up.attempts, h.gearP))
        r.b.add(20, Pt(gear = Gear.D, speed = 0.0, brake = true))
        r.go()
        val n = r.count("hl1.up.1")
        r.b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }
        r.go()
        assertEquals("P에서 나와 다시 움직이면 지금 단계 문장을 다시", n + 1, r.count("hl1.up.1"))
    }

    @Test
    fun rollbackBoundaryHalfSecondOncePerAttempt() {
        val r = Runner()
        toStep3(r)
        r.b.add(5, Pt(speed = -2.0, brake = false)) // 브레이크 뗌 + 뒤로 0.4초(표본 5개)
        r.b.add(10, Pt(speed = 0.0, brake = true))
        r.go()
        assertEquals("브레이크를 떼서 4단계", 4, r.hint().step)
        assertEquals("0.4초는 밀림 아님", 0, r.ev("HL2").size)
        r.b.add(6, Pt(speed = -2.0, brake = false)).add(10, Pt(speed = 0.0, brake = true)) // 0.5초(표본 6개)
        r.go()
        val f = r.ev("HL2", "fail")
        assertEquals("0.5초 → HL2", 1, f.size)
        assertEquals(Triple(1, -2.0, 0.5), Triple(f[0].data.attempt, f[0].data.speed, f[0].data.dur))
        assertTrue("hl2를 말함(안전)", r.say.any { it.key == "hl2" && it.category == Category.SAFETY })
        assertTrue(r.hint().up.rolled)
        r.b.add(12, Pt(speed = -2.0, brake = false)).add(10, Pt(speed = 0.0, brake = true))
        r.go()
        assertEquals("같은 시도에서는 한 번만", 1, r.ev("HL2", "fail").size)
        r.b.add(10) { _, i -> Pt(speed = (i + 1) * 0.6, brake = false) }.add(40, Pt(speed = 6.0, brake = false))
        r.go()
        assertEquals(Triple(1, 1, 1), Triple(r.hint().step, r.hint().up.attempts, r.hint().up.rollbacks))
        assertEquals("밀린 시도는 ok를 내지 않음", 0, r.ev("HL2", "ok").size)
        // 1단계(아직 시도 아님)와 기어 R에서는 밀림이 아님
        val q = Runner()
        q.b.add(20, Pt(speed = -2.0, brake = false)).add(10, Pt(speed = 0.0, brake = true))
        q.go()
        toStep3(q)
        q.b.add(10, Pt(gear = Gear.R, speed = -3.0, brake = false)).add(10, Pt(gear = Gear.R, speed = 0.0, brake = true))
        q.go()
        assertEquals("1단계·기어 R의 뒤로 움직임은 HL2가 아님", 0, q.ev("HL2").size)
        val rec = r.finish()
        assertEquals(HillTaskCount(success = 0, total = 1), task(rec, "T-HILLUP"))
        assertEquals(1, rec.hill?.up?.rollbacks)
        assertTrue("리뷰 아쉬운 것(review.js 기본 글) ${rec.bad}", rec.bad.any { it.text == "오르막에서 뒤로 밀림 1번" })
        assertEquals("T-HILLUP", rec.nextTask?.id)
    }

    @Test
    fun withoutReverseSpeedHl2IsOffAndUphillTaskIsNotJudged() {
        for (caps in listOf(SignalCaps(reverseSpeed = false), SignalCaps(speed = false))) {
            val r = capsRunner(caps)
            toStep3(r)
            r.b.add(8, Pt(speed = -2.0, brake = false)).add(10) { _, i -> Pt(speed = (i + 1) * 0.6, brake = false) }.add(40, Pt(speed = 6.0, brake = false))
            r.go()
            assertEquals("$caps", 0, r.ev("HL2").size)
            assertTrue(r.hint().up.rollOff)
            val rec = r.finish()
            assertEquals(HillTaskCount(success = 0, total = 0, off = "reverseSpeed"), task(rec, "T-HILLUP"))
            assertEquals("시도 수는 셈(RV-6 '오르막 출발 n번')", 1, rec.hill?.up?.attempts)
            assertEquals(0, rec.tasks.first { it.id == "T-HILLUP" }.total)
        }
    }

    /* ================================================================ 내리막 감속 */
    @Test
    fun downhillAutoStartAt300mEnterSplitsHoldOver8SecondsAndEnd() {
        val r = Runner()
        r.b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }.add(10, Pt(speed = 25.0, brake = false))
            .add(5, Pt(speed = 25.0, brake = false, nav = cau(400.0))).add(1, Pt(speed = 25.0, brake = false, nav = cau(310.0)))
        r.go()
        assertEquals("310m — 아직", HillPractice.UP, r.hint().practice)
        r.b.add(1, Pt(speed = 25.0, brake = false, nav = cau(300.0)))
        r.go()
        assertEquals(
            "300m — 내리막 1단계", listOf<Any>(HillPractice.DOWN, 1, true, true),
            listOf(r.hint().practice, r.hint().step, r.hint().tap, r.hint().down.bound),
        )
        r.b.add(40) { _, i -> Pt(speed = 25.0, brake = false, nav = cau(max(0.0, 290.0 - i * 10))) }
        r.go()
        assertEquals("남은 거리 0 = 구간 안 → 2단계", 2, r.hint().step)
        assertFalse("내비 구간에 묶인 2단계는 화면 누름 아님", r.hint().tap)
        val z = cau(0.0)
        r.b.add(81, Pt(speed = 25.0, brake = true, nav = z)) // 1번째: 8.0초(표본 81개)
        r.b.add(10, Pt(speed = 25.0, brake = false, nav = z))
        r.go()
        assertEquals("8.0초 — 계속 밟음 아님", Triple(1, 0, false), r.hint().down.let { Triple(it.presses, it.holds, it.warn) })
        r.b.add(82, Pt(speed = 25.0, brake = true, nav = z)) // 2번째: 8.1초
        r.go()
        assertEquals("8.1초 — 계속 밟음 + 화면 줄", Triple(2, 1, true), r.hint().down.let { Triple(it.presses, it.holds, it.warn) })
        r.b.add(10, Pt(speed = 25.0, brake = false, nav = z))
        r.go()
        assertTrue("뗀 뒤에도 다음에 밟을 때까지 줄을 보임", r.hint().down.warn)
        r.b.add(10, Pt(speed = 25.0, brake = true, nav = z)).add(10, Pt(speed = 25.0, brake = false, nav = z))
        r.go()
        assertEquals(3 to false, r.hint().down.presses to r.hint().down.warn)
        r.b.add(5, Pt(speed = 25.0, brake = false)) // 구간 끝
        r.go()
        assertEquals("구간이 끝나면 3단계(화면 누름)", 3 to true, r.hint().step to r.hint().tap)
        assertEquals(listOf(Triple(EventResult.FAIL, 3, 1)), r.ev("HL1").map { Triple(it.result, it.data.presses, it.data.holds) })
        r.b.input(SessionInput.HillPress)
        r.go()
        assertTrue("[했어요] → 내리막 끝", r.hint().done)
        r.b.add(30, Pt(speed = 25.0, brake = false))
        r.go()
        assertEquals(listOf("hl1.down.1", "hl1.down.2", "hl1.down.3"), r.say.map { it.key }.filter { it.startsWith("hl1.down") })
        val rec = r.finish()
        assertEquals(HillTaskCount(success = 0, total = 1, badText = "내리막에서 브레이크 계속 밟음 {n}번"), task(rec, "T-HILLDOWN"))
        assertEquals(Triple(1, 3, 1), checkNotNull(rec.hill).down.let { Triple(it.runs, it.presses, it.holds) })
        assertTrue(rec.bad.any { it.text == "내리막에서 브레이크 계속 밟음 1번" })
    }

    @Test
    fun downhillSplitsTwiceIsOkStandingBrakeNotCountedAndChipStartedUsesPress() {
        val r = Runner()
        r.b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }.add(5, Pt(speed = 20.0, brake = false, nav = cau(250.0, "k1")))
            .add(5, Pt(speed = 20.0, brake = false, nav = cau(0.0, "k1")))
        repeat(4) {
            r.b.add(12, Pt(speed = 20.0, brake = true, nav = cau(0.0, "k1"))).add(20, Pt(speed = 20.0, brake = false, nav = cau(0.0, "k1")))
        }
        r.b.add(130, Pt(speed = 0.0, brake = true, nav = cau(0.0, "k1"))) // 서서 13초 밟고 있음 — 나눠 밟기로도, 계속 밟음으로도 안 셈
        r.b.add(5, Pt(speed = 10.0, brake = false))
        r.go()
        assertEquals(listOf(Triple(EventResult.OK, 4, 0)), r.ev("HL1").map { Triple(it.result, it.data.presses, it.data.holds) })
        // 칩으로 고른 내리막(내비 구간 없음): 멈췄을 때만 칩이 받음 → 1단계 [했어요] → 2단계 [했어요] → 3단계 [했어요] → 끝
        val q = Runner()
        q.b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }.input(SessionInput.PickHillPractice(HillPractice.DOWN)).add(5, Pt(speed = 20.0, brake = false))
        q.go()
        assertEquals("달리는 중엔 연습 칩을 받지 않음", HillPractice.UP, q.hint().practice)
        q.b.add(20) { _, i -> Pt(speed = max(0.0, 19.0 - i), brake = true) }.add(10, Pt(speed = 0.0, brake = true))
            .input(SessionInput.PickHillPractice(HillPractice.DOWN))
        q.go()
        assertEquals(Triple(HillPractice.DOWN, 1, false), Triple(q.hint().practice, q.hint().step, q.hint().down.bound))
        q.b.input(SessionInput.HillPress).add(10) { _, i -> Pt(speed = i + 1.0, brake = false) }
        q.go()
        assertEquals("묶인 구간이 없으면 2단계도 [했어요]", 2 to true, q.hint().step to q.hint().tap)
        q.b.add(10, Pt(speed = 15.0, brake = true)).add(10, Pt(speed = 15.0, brake = false)).add(10, Pt(speed = 15.0, brake = true))
            .add(5, Pt(speed = 15.0, brake = false)).input(SessionInput.HillPress)
        q.go()
        assertEquals(3, q.hint().step)
        assertEquals(listOf(EventResult.OK to 2), q.ev("HL1").map { it.result to it.data.presses })
        // 끝난 연습에 새 내리막 구간이 오면 새 구간으로
        q.b.input(SessionInput.HillPress).add(3, Pt(speed = 15.0, brake = false, nav = cau(300.0, "k2")))
        q.go()
        val h = q.hint()
        assertEquals(listOf<Any>(HillPractice.DOWN, 1, false, true), listOf(h.practice, h.step, h.done, h.down.bound))
    }

    /* ================================================================ 언덕 주차 */
    private class Park(val r: Runner, val at2: HillHint, val at3: HillHint, val rec: SessionRecord)

    /** t6 `parkRun(sit, steer, hbAt, caps)`: 달리다 섬 → 상황 고름 → 핸들을 [steer]까지 2초에 → P(사이드브레이크는 P 뒤 [hbAt]초부터). */
    private fun parkRun(sit: HillSlope, steer: Double, hbAt: Double?): Park {
        val r = Runner()
        r.b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }.add(20) { _, i -> Pt(speed = max(0.0, 19.0 - i), brake = true) }
            .add(5, Pt(speed = 0.0, brake = true)).input(SessionInput.HillSituation(sit)).add(60, Pt(speed = 0.0, brake = true))
        r.go()
        val at2 = r.hint()
        r.b.add(20) { _, i -> Pt(steer = r1(steer * (i + 1) / 20)) }.add(50, Pt(steer = steer))
        r.go()
        val at3 = r.hint()
        r.b.add(40) { _, i -> Pt(gear = Gear.P, speed = 0.0, brake = false, handbrake = hbAt != null && i >= hbAt * 10) }
        r.go()
        return Park(r, at2, at3, r.finish())
    }

    /** 상황 · 맞는 쪽 · 돌릴 핸들 · 2단계 문장의 방향 글. */
    private class Case(val sit: HillSlope, val need: TurnDir, val steer: Double, val text: String)

    @Test
    fun wheelDirectionForThreeSituations() {
        val cases = listOf(
            Case(HillSlope.DOWN, TurnDir.RIGHT, 20.0, "연석 쪽(오른쪽)"),
            Case(HillSlope.UP, TurnDir.LEFT, -20.0, "연석 반대쪽(왼쪽)"),
            Case(HillSlope.NONE, TurnDir.RIGHT, 15.0, "길 가장자리 쪽(오른쪽)"),
        )
        for (c in cases) {
            val sit = c.sit
            val need = c.need
            val p = parkRun(sit, c.steer, 1.5)
            assertEquals(
                "$sit", listOf<Any?>(HillPractice.PARK, 2, sit, need, c.text),
                listOf(p.at2.practice, p.at2.step, p.at2.park.situation, p.at2.park.need, p.at2.vars?.get("side")?.jsonPrimitive?.content),
            )
            assertTrue("$sit 2단계 문장", p.r.say.any { it.key == "hl1.park.2" && it.text == "브레이크를 밟은 채 앞바퀴를 ${c.text}으로 돌리세요." })
            assertEquals("$sit 바퀴를 돌림 → 3단계", Triple(3, need, true), Triple(p.at3.step, p.at3.park.wheel?.side, p.at3.park.wheel?.ok))
            assertTrue(p.r.said("hl1.park.3"))
            assertEquals(
                listOf(listOf<Any?>(EventResult.OK, sit, need, need)),
                p.r.ev("HL3").map { listOf(it.result, it.data.situation, it.data.need, it.data.side) },
            )
            assertTrue("$sit hl3.ok", p.r.say.any { it.key == "hl3.ok" && it.category == Category.PRAISE })
            assertTrue(p.r.hint().done)
            val park = checkNotNull(p.rec.hill?.park)
            assertEquals(listOf<Any?>(sit, true, HandbrakeState.ON, true), listOf(park.situation, park.wheelOk, park.handbrake, park.ok))
            assertEquals(HillTaskCount(success = 1, total = 1), task(p.rec, "T-HILLPARK"))
        }
        // 반대로 돌리면 2단계에 머물고, P에서 틀림 → hl3.wrong(맞는 쪽 {dir})
        val w = parkRun(HillSlope.UP, 20.0, 1.0)
        assertEquals(Triple(2, TurnDir.RIGHT, false), Triple(w.at3.step, w.at3.park.wheel?.side, w.at3.park.wheel?.ok))
        assertEquals(listOf(EventResult.FAIL), w.r.ev("HL3").map { it.result })
        assertTrue(w.r.say.any { it.key == "hl3.wrong" && it.text == "앞바퀴를 왼쪽으로 돌려 보자." && it.category == Category.FIX })
        assertEquals(HillTaskCount(success = 0, total = 1, badText = "언덕 주차 바퀴 방향 틀림 {n}번"), task(w.rec, "T-HILLPARK"))
    }

    @Test
    fun wheelBoundaryEightDegreesAndHandbrakeWindowThreeSeconds() {
        val a = parkRun(HillSlope.DOWN, 7.9, 1.0)
        assertEquals(2, a.at3.step)
        assertEquals(listOf(EventResult.FAIL to 7.9), a.r.ev("HL3").map { it.result to it.data.steer })
        val b = parkRun(HillSlope.DOWN, 8.0, 1.0)
        assertEquals(3, b.at3.step)
        assertEquals(listOf(EventResult.OK), b.r.ev("HL3").map { it.result })
        val c = parkRun(HillSlope.NONE, 12.0, null) // 사이드브레이크 안 채움
        val cp = checkNotNull(c.rec.hill?.park)
        assertEquals(Triple(true, HandbrakeState.OFF, false), Triple(cp.wheelOk, cp.handbrake, cp.ok))
        assertEquals(HillTaskCount(success = 0, total = 1, badText = "언덕 주차 사이드브레이크 안 채움 {n}번"), task(c.rec, "T-HILLPARK"))
        val d = parkRun(HillSlope.NONE, 12.0, 2.8) // P 뒤 2.8초에 채움 — 3초 창 안
        assertEquals(HandbrakeState.ON, d.rec.hill?.park?.handbrake)
        val e = parkRun(HillSlope.NONE, 12.0, 3.1) // 3초 창이 지난 뒤
        assertEquals(HandbrakeState.OFF, e.rec.hill?.park?.handbrake)
    }

    @Test
    fun withoutSteerHl3IsOffStep2IsAPressAndParkTaskUsesHandbrakeOnly() {
        val r = capsRunner(SignalCaps(steer = false))
        r.b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }.add(20) { _, i -> Pt(speed = max(0.0, 19.0 - i), brake = true) }
            .add(5, Pt(speed = 0.0, brake = true)).input(SessionInput.HillSituation(HillSlope.DOWN)).add(10, Pt(speed = 0.0, brake = true))
        r.go()
        val h = r.hint()
        assertEquals(listOf<Any?>(2, true, true, null), listOf(h.step, h.tap, h.park.steerOff, h.park.wheel))
        r.b.input(SessionInput.HillPress)
        r.go()
        assertEquals(3, r.hint().step)
        r.b.add(35) { _, i -> Pt(gear = Gear.P, speed = 0.0, brake = false, handbrake = i >= 10) }
        r.go()
        assertEquals(0, r.ev("HL3").size)
        val rec = r.finish()
        val park = checkNotNull(rec.hill?.park)
        assertEquals(Triple(null, HandbrakeState.ON, true), Triple(park.wheelOk, park.handbrake, park.ok))
        assertEquals(HillTaskCount(success = 1, total = 1), task(rec, "T-HILLPARK"))
        val q = capsRunner(SignalCaps(steer = false, handbrake = false))
        q.b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }.add(20) { _, i -> Pt(speed = max(0.0, 19.0 - i), brake = true) }
            .input(SessionInput.HillSituation(HillSlope.UP)).input(SessionInput.HillPress).add(35, Pt(gear = Gear.P, speed = 0.0, brake = false))
        q.go()
        val rq = q.finish()
        assertEquals(HandbrakeState.UNKNOWN to null, rq.hill?.park?.handbrake to rq.hill?.park?.ok)
        assertEquals(HillTaskCount(success = 0, total = 0), task(rq, "T-HILLPARK"))
    }

    /* ================================================================ 연습 칩·단계 칸·힌트 */
    @Test
    fun chipsAndStepCellsOnlyWhenStillAndHintChangesOnlyWhenSomethingChanges() {
        val r = Runner()
        driveAndStop(r.b).add(15, Pt(speed = 0.0, brake = true))
        r.go()
        assertEquals(2, r.hint().step)
        val seq0 = r.hint().seq
        r.b.add(10, Pt(speed = 0.0, brake = true))
        r.go()
        assertEquals("바뀐 것이 없으면 힌트 그대로", seq0, r.hint().seq)
        r.b.input(SessionInput.PickHillPractice(HillPractice.PARK))
        r.go()
        assertEquals(Triple(HillPractice.PARK, 1, null), Triple(r.hint().practice, r.hint().step, r.hint().park.situation))
        assertTrue(r.hint().seq > seq0)
        r.b.input(SessionInput.HillStep(3))
        r.go()
        assertEquals("상황을 고르기 전엔 2·3단계로 못 감", 1, r.hint().step)
        r.b.input(SessionInput.PickHillPractice(HillPractice.UP))
        r.go()
        assertEquals("서 있는 채로 오르막 칩 → 지금 멈춘 것이 1단계", HillPractice.UP to 2, r.hint().practice to r.hint().step)
        r.b.input(SessionInput.HillStep(3))
        r.go()
        assertEquals(3, r.hint().step)
        r.b.input(SessionInput.HillStep(1))
        r.go()
        assertEquals(1, r.hint().step)
        r.b.add(10) { _, i -> Pt(speed = i + 1.0, brake = false) }.input(SessionInput.HillStep(3))
        r.go()
        assertEquals("달리는 중엔 단계 칸을 받지 않음", 1, r.hint().step)
        val h = BaroJson.encodeToJsonElement(HillHint.serializer(), r.hint()).jsonObject
        for (k in listOf("practice", "step", "steps", "done", "tap", "key", "cheer", "up", "down", "park", "gearP", "seq")) assertTrue(k, k in h)
        assertEquals("hl1.up.1", r.hint().key)
    }

    @Test
    fun parkingPracticeIgnoresDownhillZonesAndSituationJumpsToStep2Or3() {
        val r = Runner()
        r.b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }.add(20) { _, i -> Pt(speed = max(0.0, 19.0 - i), brake = true) }
            .add(5, Pt(speed = 0.0, brake = true, steer = 12.0)).input(SessionInput.HillSituation(HillSlope.NONE))
        r.go()
        assertEquals("이미 오른쪽 12° → 3단계", HillPractice.PARK to 3, r.hint().practice to r.hint().step)
        r.b.input(SessionInput.HillSituation(HillSlope.UP))
        r.go()
        assertEquals("왼쪽이 맞는 상황으로 바꾸면 2단계", 2, r.hint().step)
        r.b.add(5, Pt(speed = 0.0, brake = true, nav = cau(200.0, "z9")))
        r.go()
        assertEquals("언덕 주차 중엔 내리막으로 안 바뀜", HillPractice.PARK, r.hint().practice)
    }

    /* ================================================================ 언덕 주차 P = 회차 끝 · P 때 말 순서 */
    @Test
    fun hillParkingPSetsArrivedButOtherPDoesNot() {
        val r = Runner()
        r.b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }.add(20) { _, i -> Pt(speed = max(0.0, 19.0 - i), brake = true) }
            .add(5, Pt(speed = 0.0, brake = true)).input(SessionInput.HillSituation(HillSlope.DOWN)).add(10, Pt(speed = 0.0, brake = true, steer = 12.0))
        r.go()
        assertEquals(3, r.hint().step)
        assertFalse("P 전에는 도착 아님", r.s.hints.arrived)
        r.b.add(5, Pt(gear = Gear.P, speed = 0.0, brake = false, steer = 12.0))
        val o = r.go()
        assertTrue("P 첫 표본 출력에서 바로 — 세션이 그 틱에 마무리를 정함", o.steps[0].hints.arrived)
        // 오르막 연습 중 P(중간 정차)는 도착이 아님
        val q = Runner()
        driveAndStop(q.b).add(15, Pt(speed = 0.0, brake = true)).add(10, Pt(gear = Gear.P, speed = 0.0, brake = false))
        q.go()
        assertFalse(q.s.hints.arrived)
    }

    @Test
    fun atPHl3IsSpokenBeforePb2Hill() {
        val r = Runner(dets = Detectors.all) // 기본 검출기(PB2 포함) 모두
        r.b.add(20) { _, i -> Pt(speed = i + 1.0, brake = false) }.add(20) { _, i -> Pt(speed = max(0.0, 19.0 - i), brake = true) }
            .add(5, Pt(speed = 0.0, brake = true)).input(SessionInput.HillSituation(HillSlope.NONE)).add(70, Pt(speed = 0.0, brake = true))
            .add(3, Pt(speed = 0.0, brake = true, steer = 10.0)).add(100, Pt(gear = Gear.P, speed = 0.0, brake = false, steer = 10.0))
        r.go()
        val keys = r.say.map { it.key }.filter { Regex("^hl1\\.park|^hl3|^pb2").containsMatchIn(it) }
        assertEquals(listOf("hl1.park.2", "hl1.park.3", "hl3.ok", "pb2.hill"), keys)
        assertTrue(r.s.errors.toString(), r.s.errors.isEmpty())
    }
}
