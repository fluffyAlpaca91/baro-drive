package com.barodrive.core.engine.detect.parking

import com.barodrive.core.engine.CoachEngine
import com.barodrive.core.engine.CoachSession
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.content.ParkingGuide
import com.barodrive.core.engine.content.ParkingSignal
import com.barodrive.core.engine.content.ParkingStep
import com.barodrive.core.engine.det
import com.barodrive.core.engine.of
import com.barodrive.core.engine.r1
import com.barodrive.core.engine.snap
import com.barodrive.core.engine.session.DetectorOff
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.BlockedUtterance
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.ParkingHandbrake
import com.barodrive.core.model.ParkingHint
import com.barodrive.core.model.ParkingSetup
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.ParkingVisual
import com.barodrive.core.model.ParkingWheel
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.SkipInfo
import com.barodrive.core.model.SkipPoint
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.Utterance
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프로토타입 t5b_guide.test.js의 엔진 부분 — 3종 × 2방향 단계 넘김, 신호 경계(설계 6.12.1·결정-61), [다음 단계], PK2 15초 다시 말하기,
 * PK3, 못 읽는 신호(설계 7.8), 건너뛰기, 힌트·회차 기록(rec.parking). 단계 표·문장은 [com.barodrive.core.engine.content.ParkingGuideTest].
 * 합성 스냅숏 세션은 parking.* + basic.PB2 검출기만 넣어 만든다(모드 6에서 도는 것, 결정사항 E T2a-1).
 * S6-P 시나리오(시뮬레이터)는 골든 회차로 [com.barodrive.core.engine.golden.GoldenModesScenarioTest]가 본다.
 */
class ParkingDetectorsTest {
    private val kinds = listOf(
        ParkingType.PARALLEL to TurnDir.RIGHT, ParkingType.PARALLEL to TurnDir.LEFT, ParkingType.BACK to TurnDir.RIGHT,
        ParkingType.BACK to TurnDir.LEFT, ParkingType.FRONT to TurnDir.RIGHT, ParkingType.FRONT to TurnDir.LEFT,
    )
    private val signals = mapOf(
        ParkingType.PARALLEL to listOf("press", "stop", "gearR", "steerFull", "steerStraight", "steerFull", "stop", "gearP"),
        ParkingType.BACK to listOf("press", "stop", "gearR", "steerFull", "steerStraight", "stop", "gearP"),
        ParkingType.FRONT to listOf("press", "steerFull", "steerStraight", "stop", "gearP"),
    )

    /** t5b `DETS()`: 등록 순서대로 basic.PB2 → parking.*. */
    private val dets = listOf("basic.PB2", "parking.PK1", "parking.PK2", "parking.PK3").map(::det)

    private fun steps(type: ParkingType, side: TurnDir): List<ParkingStep> = ParkingGuide.steps(type.code, side.code)

    /** t5b `rig(type, side, over)`: 0.1초씩 스냅숏(기본 D · 0km/h · 브레이크 · 핸들 0 · 최대각 34). */
    private inner class Rig(cfg: SessionConfig) {
        val ses: CoachSession = CoachEngine().createSession(cfg, dets)
        var t = 0.0
        var gear: Gear? = Gear.D
        var speed = 0.0
        var brake: Boolean? = true
        var steer = 0.0
        var handbrake: Boolean? = false
        var maxSteer: Double? = 34.0
        var tripDist = 0.0
        var skip = false
        var skipInfo: SkipInfo? = null
        val say = ArrayList<Utterance>()
        val blocked = ArrayList<BlockedUtterance>()
        val events = ArrayList<JudgeEvent>()
        var hint: ParkingHint? = null
        val perTick = ArrayList<Int?>()

        private fun take(o: StepOutput) {
            say += o.say
            blocked += o.blocked
            events += o.events
            o.hints.parking?.let { hint = it }
        }

        fun tick(patch: Rig.() -> Unit = {}): Rig {
            patch()
            val s = snap(t = r1(t), gear = gear, speed = speed, brake = brake, steer = steer, handbrake = handbrake, tripDist = tripDist)
            take(ses.step(s.copy(maxSteer = maxSteer, skip = skip, skipInfo = skipInfo)))
            perTick += hint?.step
            tripDist = Math.round((tripDist + abs(speed) / 36) * 1000) / 1000.0
            t = r1(t + 0.1)
            return this
        }

        fun run(sec: Double, patch: Rig.() -> Unit = {}): Rig {
            patch()
            repeat(Math.round(sec * 10).toInt()) { tick() }
            return this
        }

        fun press(step: Int? = null): Rig {
            take(ses.input(SessionInput.PkNext(step)))
            return this
        }

        val step: Int? get() = hint?.step

        fun said(key: String) = say.filter { it.key == key }

        fun ev(item: String, result: String? = null) = events.of(item, result)
    }

    private fun rig(
        type: ParkingType?, side: TurnDir?, items: Map<String, Delivery> = emptyMap(), caps: SignalCaps = SignalCaps(),
        preEvents: List<JudgeEvent> = emptyList(),
    ) = Rig(
        SessionConfig(
            mode = 6, coach = Coach.DAD, honorific = Honorific(), parking = type?.let { ParkingSetup(it, side ?: TurnDir.RIGHT) }, items = items,
            caps = caps, preEvents = preEvents,
        ),
    )

    /** 그 단계 신호를 낸다(설계 6.12.1, t5b `satisfy`). */
    private fun satisfy(r: Rig, st: ParkingStep) {
        when (st.signal) {
            ParkingSignal.PRESS -> r.press(st.n)
            ParkingSignal.STOP -> {
                r.run(1.0) {
                    speed = if (gear == Gear.R) -4.0 else 4.0
                    brake = false
                }
                r.run(1.2) {
                    speed = 0.0
                    brake = true
                }
            }
            ParkingSignal.GEAR_R -> r.run(0.2) {
                gear = Gear.R
                speed = 0.0
                brake = true
            }
            ParkingSignal.GEAR_P -> r.run(0.2) {
                gear = Gear.P
                speed = 0.0
                brake = false
            }
            ParkingSignal.STEER_FULL -> r.run(0.2) { steer = (if (st.dir == "L") -1 else 1) * 31.0 }
            ParkingSignal.STEER_STRAIGHT -> r.run(1.2) { steer = 0.0 }
        }
    }

    /* ================================================================ 대사 */
    @Test
    fun linesPk2Pk3PkDone() {
        fun line(k: String) = Coach.entries.map { Lines.render(k, it, emptyMap(), Honorific(HonorificType.DAUGHTER), index = 0) }
        assertEquals(listOf("천천히.", "천천히, 천천히!", "천천히."), line("pk2"))
        assertEquals(listOf("바퀴 똑바로 해 둬. 다음 출발이 편해.", "바퀴 똑바로! 다음에 출발하기 편해!", "바퀴를 똑바로 두면 다음 출발이 편해."), line("pk3"))
        assertEquals(listOf("주차 끝. 생각보다 잘하네.", "어머 주차 성공이야! 대단해!", "주차 잘 마쳤어. 고생했어."), line("pk.done"))
        assertEquals("back-L", ParkingGuide.key("back", "left"))
        assertEquals("parallel-R", ParkingGuide.key("parallel", "R"))
    }

    /* ================================================================ 단계 기계 */
    @Test
    fun threeKindsTwoSidesStepBySignalAndSayEachStepOnce() {
        for ((type, side) in kinds) {
            val tag = "${type.code}${side.code}"
            val r = rig(type, side)
            val steps = steps(type, side)
            r.tick()
            for (st in steps) {
                assertEquals("$tag 단계 ${st.n}", st.n, r.step)
                assertEquals(st.signal.id, r.hint?.signal)
                assertEquals(st.text, r.hint?.text)
                satisfy(r, st)
                if (st.n < steps.size) assertEquals("$tag ${st.n} → ${st.n + 1}", st.n + 1, r.step)
                r.run(8.0) // 그 단계 문장이 다 말해질 시간
            }
            assertEquals("$tag 다 마침", true, r.hint?.done)
            assertEquals(steps.size, r.hint?.step)
            assertTrue("$tag 다 마치면 hints.arrived(결정 T5b-Q1)", r.ses.hints.arrived)
            for (st in steps) {
                val u = r.said(st.key)
                assertEquals("${st.key} 한 번 말함", 1, u.size)
                assertEquals(st.text, u[0].text)
                assertEquals(Category.INFO, u[0].category)
            }
            val pk1 = r.ev("PK1", "ok")
            assertEquals(steps.map { it.n }, pk1.map { it.data.step })
            assertEquals(steps.map { if (it.signal == ParkingSignal.PRESS) "press" else "signal" }, pk1.map { it.data.by })
            assertEquals(true, pk1.last().data.last)
            // 끝 P: 바퀴가 꺾여 있으면(평행은 반대쪽 끝까지 돌린 채) PK3 → 3초 뒤 PB2
            val pk3 = r.ev("PK3")
            assertEquals(1, pk3.size)
            assertEquals("$tag 끝 바퀴 ${pk3[0].data.steer}", if (type == ParkingType.PARALLEL) EventResult.FAIL else EventResult.OK, pk3[0].result)
            assertEquals(1, r.ev("PB2", "fail").size)
            assertTrue(r.ses.errors.toString(), r.ses.errors.isEmpty())
        }
    }

    @Test
    fun oneStepPerSnapshotEvenWhenSignalsAreAlreadyMet() {
        val r = rig(ParkingType.PARALLEL, TurnDir.RIGHT)
        r.tick().press(1)
        r.run(1.0) { speed = 4.0; brake = false }.run(1.2) { speed = 0.0; brake = true } // 2 → 3
        assertEquals(3, r.step)
        r.perTick.clear()
        r.run(0.3) { gear = Gear.R; steer = 31.0 } // 3(기어 R)과 4(끝까지)가 이미 갖춰짐
        assertEquals(listOf<Int?>(4, 5, 5), r.perTick)
    }

    @Test
    fun steerFullIsMaxTimesPointNineAndStraightIsThreeDegreesForOneSecond() {
        fun to4(r: Rig): Rig {
            r.tick().press(1)
            r.press(2)
            r.press(3)
            assertEquals(4, r.step)
            return r
        }
        // 아반떼 34° → 30.6°
        var r = to4(rig(ParkingType.PARALLEL, TurnDir.RIGHT))
        r.run(0.5) { steer = 30.5 }
        assertEquals("30.5° 아님", 4, r.step)
        r.run(0.5) { steer = -33.0 }
        assertEquals("반대쪽 끝까지는 아님", 4, r.step)
        r.tick { steer = 30.6 }
        assertEquals("30.6°", 5, r.step)
        // 그랜저 33° → 29.7°, 최대각이 스냅숏에 없으면 34°
        r = to4(rig(ParkingType.PARALLEL, TurnDir.RIGHT))
        r.tick { steer = 29.7; maxSteer = 33.0 }
        assertEquals(5, r.step)
        r = to4(rig(ParkingType.BACK, TurnDir.LEFT))
        r.tick { steer = -30.5; maxSteer = null }
        assertEquals(4, r.step)
        r.tick { steer = -30.6 }
        assertEquals("왼쪽 칸은 왼쪽 끝까지", 5, r.step)
        // 똑바로: 3.1°는 아님, 0.9초는 아님, 3.0°가 1초면 넘어감
        r = to4(rig(ParkingType.PARALLEL, TurnDir.RIGHT))
        r.tick { steer = 31.0 }
        assertEquals(5, r.step)
        r.run(3.0) { steer = 3.1 }
        assertEquals("3.1°", 5, r.step)
        r.run(0.9) { steer = -3.0 }
        r.tick { steer = 4.0 }
        assertEquals("0.9초 뒤 다시 꺾음", 5, r.step)
        r.run(1.0) { steer = 3.0 }
        assertEquals("10개 표본 = 0.9초", 5, r.step)
        r.tick()
        assertEquals("1초", 6, r.step)
    }

    @Test
    fun stopIsMovedThenStillOneSecondWithBrakeAndGearPCountsAsStop() {
        val r = rig(ParkingType.PARALLEL, TurnDir.RIGHT)
        r.tick().press(1)
        assertEquals(2, r.step)
        r.run(5.0) { speed = 0.0; brake = true }
        assertEquals("안 움직였으면 넘기지 않음", 2, r.step)
        r.run(1.0) { speed = 4.0; brake = false }
        r.run(3.0) { speed = 0.0; brake = false }
        assertEquals("브레이크 없이 선 것은 아님", 2, r.step)
        r.tick { brake = true }
        assertEquals("이미 1초 넘게 섰고 브레이크", 3, r.step)
        // 0.9초는 아님
        val q = rig(ParkingType.PARALLEL, TurnDir.RIGHT)
        q.tick().press(1)
        q.run(1.0) { speed = 4.0; brake = false }.run(1.0) { speed = 0.0; brake = true }
        assertEquals("정지 표본 10개 = 0.9초", 2, q.step)
        q.tick()
        assertEquals("1초", 3, q.step)
        // 7단계(멈춤)에서 바로 P → 7 → 8 → 끝
        val w = rig(ParkingType.PARALLEL, TurnDir.RIGHT)
        w.tick()
        for (n in 1..6) w.press(n)
        assertEquals(7, w.step)
        w.run(1.0) { gear = Gear.R; speed = -4.0; brake = false }
        w.tick { gear = Gear.P; speed = 0.0 }
        assertEquals(8, w.step)
        w.tick()
        assertEquals("다음 표본에 P로 끝", true, w.hint?.done)
    }

    @Test
    fun nextStepPressSkipsAnyStepIgnoresLatePressesAndFinishesOnLastPress() {
        val r = rig(ParkingType.FRONT, TurnDir.RIGHT)
        r.press(1)
        assertNull("첫 스냅숏 전 누름은 무시", r.step)
        r.tick()
        assertEquals(1, r.step)
        r.press(1)
        assertEquals(2, r.step)
        r.press(1)
        assertEquals("이미 넘어간 단계의 누름", 2, r.step)
        r.press(3)
        assertEquals("앞 단계 누름", 2, r.step)
        r.press()
        assertEquals("step 없는 누름", 3, r.step)
        r.press(3)
        r.press(4)
        assertEquals(5, r.step)
        r.run(1.0) { gear = Gear.R; speed = -3.0; brake = false; steer = 12.0 }
        r.press(5)
        assertEquals("마지막 단계 누름 → 끝", true, r.hint?.done)
        assertEquals("P가 아니면 아직 바퀴를 보지 않음", 0, r.ev("PK3").size)
        r.run(0.5) { speed = 0.0; brake = true }
        r.tick { gear = Gear.P }
        assertEquals("P가 되면 판정", 1, r.ev("PK3", "fail").size)
        r.press()
        r.press(6)
        assertEquals("끝난 뒤 누름은 무시", 5, r.ev("PK1", "ok").size)
        assertEquals(5, r.ses.finish(arrived = true).parking?.pressed)
    }

    /* ================================================================ PK2 */
    @Test
    fun pk2ReverseFasterThanSevenForOneSecond() {
        val r = rig(ParkingType.PARALLEL, TurnDir.RIGHT)
        r.tick()
        r.run(3.0) { gear = Gear.R; speed = -7.0; brake = false }
        assertEquals("7.0km/h", 0, r.ev("PK2").size)
        r.run(1.0) { speed = -7.1 }
        assertEquals("표본 10개 = 0.9초", 0, r.ev("PK2").size)
        r.tick()
        assertEquals("1초", 1, r.ev("PK2", "fail").size)
        assertEquals(1, r.said("pk2").size)
        assertEquals("천천히.", r.said("pk2")[0].text)
        assertEquals(Category.FIX, r.said("pk2")[0].category)
        assertEquals(1, r.hint?.pk2)
        val d = rig(ParkingType.PARALLEL, TurnDir.RIGHT)
        d.tick().run(3.0) { gear = Gear.D; speed = 12.0; brake = false }
        assertEquals("앞으로는 아님", 0, d.ev("PK2").size)
    }

    @Test
    fun pk2RepeatsOnlyAfterFifteenSeconds() {
        val r = rig(ParkingType.BACK, TurnDir.RIGHT)
        r.tick().run(1.1) { gear = Gear.R; speed = -9.0; brake = false }
        assertEquals(1, r.ev("PK2").size)
        val t1 = r.ev("PK2")[0]
        r.run(14.9)
        assertEquals("14.9초", 1, r.ev("PK2").size)
        r.tick()
        assertEquals("15.0초", 2, r.ev("PK2").size)
        assertTrue(abs(r.ev("PK2")[1].t - t1.t - 15) < 1e-6)
        assertEquals(2, r.said("pk2").size)
        // 느려졌다 다시 빨라짐: 앞 판정에서 15초가 지나야
        val q = rig(ParkingType.BACK, TurnDir.RIGHT)
        q.tick().run(1.1) { gear = Gear.R; speed = -9.0; brake = false }
        q.run(3.0) { speed = -4.0 }.run(6.0) { speed = -9.0 }
        assertEquals("10초 뒤 다시 빠름 → 말 없음", 1, q.ev("PK2").size)
        q.run(3.0) { speed = -4.0 }.run(3.0) { speed = -9.0 }
        assertEquals("15초 넘게 지난 뒤 1초", 2, q.ev("PK2").size)
        assertEquals(2, q.said("pk2").size)
    }

    @Test
    fun pk2WaitsForTheStepSentenceOnlyWhileInReverseAndIsBlockedWhenAfter() {
        val r = rig(ParkingType.PARALLEL, TurnDir.RIGHT)
        r.tick().press(1) // 2단계 문장(4.9초) 시작
        r.run(0.2) { gear = Gear.R; speed = -9.0; brake = false }.run(1.0)
        assertEquals(1, r.ev("PK2").size)
        assertEquals("단계 문장을 말하는 중", 0, r.said("pk2").size)
        r.run(8.0) // 1단계 문장(3.4초) → 2단계 문장(4.9초) → 천천히
        val u = r.said("pk2")
        assertEquals("문장이 끝난 뒤 말함", 1, u.size)
        val step2 = steps(ParkingType.PARALLEL, TurnDir.RIGHT)[1].text
        assertTrue(u[0].t >= r.said("pk1.parallel.2")[0].t + BaroEngine.speakDur(step2) - 1e-6)
        // 기다리는 사이 R에서 벗어나면 말하지 않음
        val q = rig(ParkingType.PARALLEL, TurnDir.RIGHT)
        q.tick().press(1)
        q.run(0.2) { gear = Gear.R; speed = -9.0; brake = false }.run(1.0)
        q.run(0.5) { speed = 0.0; brake = true }.run(4.0) { gear = Gear.D }
        assertEquals(0, q.said("pk2").size)
        assertEquals("막힌 말로 남음", 1, q.blocked.count { it.utterance.key == "pk2" })
        // 전달 방식 S
        val s = rig(ParkingType.PARALLEL, TurnDir.RIGHT, items = mapOf("PK2" to Delivery.AFTER))
        s.tick().run(1.2) { gear = Gear.R; speed = -9.0; brake = false }
        assertEquals(1, s.ev("PK2", "fail").size)
        assertEquals(0, s.said("pk2").size)
        assertTrue(s.blocked.any { it.utterance.key == "pk2" && it.reason == BlockReason.AFTER })
    }

    /* ================================================================ PK3 */
    @Test
    fun pk3AtPAfterAllStepsFiveDegreesOkAndUnfinishedRunIsJudgedAtFinish() {
        fun finishAt(steerAt: Double): Rig {
            val r = rig(ParkingType.FRONT, TurnDir.RIGHT)
            r.tick()
            for (n in 1..4) r.press(n)
            r.run(10.0) { steer = steerAt; speed = 0.0; brake = true } // 단계 문장이 다 끝난 뒤
            r.tick { gear = Gear.P }
            r.run(4.0)
            return r
        }
        val ok = finishAt(5.0)
        assertEquals(true, ok.hint?.done)
        assertEquals(listOf(EventResult.OK to 5.0), ok.ev("PK3").map { it.result to it.data.steer })
        assertEquals(0, ok.said("pk3").size)
        val bad = finishAt(-5.1)
        assertEquals(listOf(EventResult.FAIL to -5.1), bad.ev("PK3").map { it.result to it.data.steer })
        assertEquals("P에서 바로 말함", 1, bad.said("pk3").size)
        assertEquals("바퀴를 똑바로 두면 다음 출발이 편해.", bad.said("pk3")[0].text)
        // 3단계에서 P(DR-3) → 판정 없음 → '여기서 마치기'면 회차 끝에 적음(말 없음)
        val mid = rig(ParkingType.FRONT, TurnDir.RIGHT)
        mid.tick().press(1)
        mid.press(2)
        mid.run(1.0) { speed = 3.0; brake = false; steer = 20.0 }.run(1.0) { speed = 0.0; brake = true }
        mid.run(1.0) { gear = Gear.P }
        assertEquals(0, mid.ev("PK3").size)
        val rec = mid.ses.finish(arrived = false)
        assertEquals(listOf(EventResult.FAIL), rec.events.filter { it.item == "PK3" }.map { it.result })
        assertEquals(0, rec.utterances.count { it.key == "pk3" })
        val pk = checkNotNull(rec.parking)
        assertEquals(false, pk.done)
        assertEquals(3, pk.reached)
        assertEquals(ParkingWheel(steer = 20.0, straight = false), pk.wheel)
    }

    /* ================================================================ 못 읽는 신호(설계 7.8) */
    @Test
    fun unreadableSteerOrGearTurnsThoseStepsIntoPresses() {
        val s = rig(ParkingType.PARALLEL, TurnDir.RIGHT, caps = SignalCaps(steer = false))
        s.tick()
        assertEquals(listOf(DetectorOff("parking.PK3", "caps:steer")), s.ses.info().off.filter { it.id.startsWith("parking") })
        s.press(1)
        s.press(2)
        s.press(3)
        assertEquals("press", s.hint?.via)
        assertEquals(ParkingGuide.pressText, s.hint?.signalText)
        s.run(2.0) { steer = 33.0 }
        assertEquals("핸들 값이 와도 못 읽음(null)", 4, s.step)
        s.press(4)
        assertEquals(5, s.step)
        assertEquals("press", s.hint?.via)
        val g = rig(ParkingType.BACK, TurnDir.LEFT, caps = SignalCaps(gear = false))
        g.tick().press(1)
        g.run(1.0) { speed = 4.0; brake = false }.run(1.2) { speed = 0.0; brake = true }
        assertEquals(3, g.step)
        assertEquals("press", g.hint?.via)
        g.run(1.0) { gear = Gear.R }
        assertEquals("기어를 못 읽음", 3, g.step)
        g.press(3)
        assertEquals("4단계 핸들은 신호", "signal", g.hint?.via)
        g.run(0.2) { steer = -31.0 }
        g.run(1.2) { steer = 0.0 }
        g.run(1.0) { speed = -4.0; brake = false }.run(1.2) { speed = 0.0; brake = true }
        assertEquals(7, g.step)
        g.run(0.5) { steer = 9.0 }.press(7)
        assertEquals(true, g.hint?.done)
        g.tick()
        assertEquals("기어를 못 읽으면 끝낼 때 바퀴를 봄", listOf(EventResult.FAIL), g.ev("PK3").map { it.result })
    }

    @Test
    fun unreadableSpeedTurnsPk2OffAndStopComesFromTripDistance() {
        val r = rig(ParkingType.PARALLEL, TurnDir.RIGHT, caps = SignalCaps(speed = false))
        r.tick()
        assertTrue(r.ses.info().off.any { it.id == "parking.PK2" && it.reason == "caps:speed" })
        r.press(1)
        r.run(2.0) { speed = 5.0; brake = false }.run(2.5) { speed = 0.0; brake = true }
        assertEquals("트립 거리가 멈추면 정지", 3, r.step)
        r.run(2.0) { gear = Gear.R; speed = -9.0; brake = false }
        assertEquals(0, r.ev("PK2").size)
    }

    /* ================================================================ 종류·방향, 건너뛰기, 힌트·기록 */
    @Test
    fun kindAndSideComeFromCfgThenPreEventThenDefault() {
        fun setup(type: String, side: String) =
            JudgeEvent(t = 0.0, item = "PK1", result = EventResult.INFO, data = EventData(setup = true, type = type, side = TurnDir.fromCode(side)))
        fun hintOf(r: Rig): List<Any?> {
            r.tick()
            val h = checkNotNull(r.hint)
            return listOf(h.type.code, h.side.code, h.total, h.key)
        }
        assertEquals(listOf("back", "L", 7, "back-L"), hintOf(rig(ParkingType.BACK, TurnDir.LEFT)))
        assertEquals(listOf("front", "L", 5, "front-L"), hintOf(rig(null, null, preEvents = listOf(setup("front", "L")))))
        assertEquals(listOf("parallel", "R", 8, "parallel-R"), hintOf(rig(null, null)))
        assertEquals("cfg.parking이 먼저", listOf("back", "R", 7, "back-R"), hintOf(rig(ParkingType.BACK, TurnDir.RIGHT, preEvents = listOf(setup("front", "L")))))
        // 모르는 종류·방향(프로토타입 JSON 'oops'·'?')은 평행 · 오른쪽
        val odd = BaroJson.decodeFromString(SessionConfig.serializer(), """{"mode":6,"parking":{"type":"oops","side":"?"}}""")
        assertEquals(listOf("parallel", "R", 8, "parallel-R"), hintOf(Rig(odd)))
        // buildConfig가 준비 세션 prep.parking을 cfg.parking으로 싣는다(결정 T5b-Q3) — 사전 사건보다 먼저
        val prof = Profile.blank("p1")
        val cfgP = BaroEngine.buildConfig(6, prof, PrepSession(mode = 6, parking = ParkingSetup(ParkingType.FRONT, TurnDir.LEFT)))
        assertEquals(ParkingSetup(ParkingType.FRONT, TurnDir.LEFT), cfgP.parking)
        assertNull("주차가 아니면 null", BaroEngine.buildConfig(1, prof, PrepSession(mode = 1)).parking)
        assertEquals("front-L", CoachEngine().createSession(cfgP, dets).step(snap(t = 0.0, gear = Gear.D)).hints.parking?.key)
        // 사전 사건(T5b-3)으로 넘긴 경우도 그대로 — 기록에도 남음
        val cfg = BaroEngine.buildConfig(6, prof, PrepSession(mode = 6, preEvents = listOf(setup("back", "L"))))
        val ses = CoachEngine().createSession(cfg, dets)
        assertEquals("back-L", ses.step(snap(t = 0.0, gear = Gear.D)).hints.parking?.key)
        assertEquals("기록에도 남음", 1, ses.finish(arrived = false).events.count { it.pre && it.item == "PK1" })
    }

    @Test
    fun skipSyncsToTheSceneStepAndMarksPassedStepsExcluded() {
        val r = rig(ParkingType.PARALLEL, TurnDir.RIGHT)
        r.tick()
        r.tick {
            skip = true
            skipInfo = SkipInfo(from = SkipPoint(t = 1.0), to = SkipPoint(t = 30.0), label = "5단계")
            gear = Gear.R
            steer = 31.0
        }
        r.skip = false
        r.skipInfo = null
        assertEquals(5, r.step)
        assertEquals((1..4).map { it to "skipped" }, r.ev("PK1", "excluded").map { it.data.step to it.data.excluded })
        r.run(6.0)
        assertEquals(0, (2..4).sumOf { r.said("pk1.parallel.$it").size })
        assertEquals(1, r.said("pk1.parallel.5").size)
        r.run(1.2) { steer = 0.0 }
        assertEquals("건너뛴 뒤에도 신호로 이어짐", 6, r.step)
        val rec = r.ses.finish(arrived = false)
        assertEquals(listOf("skip", "skip", "skip", "skip", "signal"), rec.parking?.steps?.map { it.by })
    }

    @Test
    fun hintAndRecordForThePk4Screen() {
        val r = rig(ParkingType.PARALLEL, TurnDir.LEFT)
        r.tick()
        val h1 = checkNotNull(r.hint)
        assertEquals(
            listOf<Any?>(1, 8, 0, false, false, "press", "press", "켰으면 다음 단계를 눌러 주세요", 0, 0),
            listOf(h1.step, h1.total, h1.index, h1.last, h1.done, h1.signal, h1.via, h1.signalText, h1.tip, h1.pk2),
        )
        assertEquals("주차할 곳을 찾으면 비상등을 켜세요.", h1.text)
        assertEquals("pk1.parallel.1", h1.line)
        assertEquals(ParkingVisual(kind = "hazard", label = "비상등 켜기"), h1.visual)
        assertEquals(0.0, h1.startedAt)
        val steps = steps(ParkingType.PARALLEL, TurnDir.LEFT)
        for (st in steps.take(3)) {
            satisfy(r, st)
            r.run(5.0)
        }
        val h4 = checkNotNull(r.hint)
        assertEquals(
            listOf<Any?>(4, "steerFull", TurnDir.LEFT, "signal", "핸들을 끝까지 돌리면 다음으로 넘어가요", 1),
            listOf(h4.step, h4.signal, h4.dir, h4.via, h4.signalText, h4.tip),
        )
        assertEquals(ParkingVisual(kind = "wheel", rot = -120.0, label = "왼쪽 끝까지"), h4.visual)
        assertEquals(JsonObject(mapOf("dir" to JsonPrimitive("L"))), h4.vars)
        assertNotEquals("바뀌면 새 값", h1, h4)
        for (st in steps.drop(3)) {
            satisfy(r, st)
            r.run(5.0)
        }
        val h8 = checkNotNull(r.hint)
        assertEquals(listOf<Any?>(8, true, true, 2, "final"), listOf(h8.step, h8.last, h8.done, h8.tip, h8.visual?.kind))
        assertTrue(checkNotNull(h8.doneAt) > checkNotNull(h8.startedAt))
        val rec = r.ses.finish(arrived = true)
        val pk = checkNotNull(rec.parking)
        assertEquals(
            listOf<Any?>(ParkingType.PARALLEL, TurnDir.LEFT, "parallel-L", 8, 8, true, 1, 0),
            listOf(pk.type, pk.side, pk.key, pk.total, pk.reached, pk.done, pk.pressed, pk.pk2),
        )
        assertEquals(r1(checkNotNull(h8.doneAt) - checkNotNull(h8.startedAt)), pk.sec)
        assertEquals(signals.getValue(ParkingType.PARALLEL), pk.steps.map { it.signal })
        assertTrue(pk.steps.zipWithNext().all { (a, b) -> checkNotNull(b.t) >= checkNotNull(a.t) })
        assertEquals("평행 왼쪽 칸 8단계: 오른쪽 끝까지 돌린 채 P", ParkingWheel(steer = 31.0, straight = false), pk.wheel)
        assertEquals("basic.PB2 판정", ParkingHandbrake(result = EventResult.FAIL, on = false), pk.handbrake)
        assertEquals(6, rec.mode)
        // 사이드브레이크를 채우면 ok
        val h = rig(ParkingType.FRONT, TurnDir.RIGHT)
        h.tick()
        for (n in 1..4) h.press(n)
        h.run(0.3) { speed = 0.0; brake = true }.tick { gear = Gear.P }.run(1.0) { handbrake = true }.run(3.0)
        assertEquals(ParkingHandbrake(result = EventResult.OK, on = true), h.ses.finish(arrived = true).parking?.handbrake)
    }

    @Test
    fun pk1OffStillStepsAndHintsOnlyTheLinesAreBlocked() {
        val r = rig(ParkingType.FRONT, TurnDir.RIGHT, items = mapOf("PK1" to Delivery.OFF))
        r.tick().press(1)
        assertEquals(2, r.step)
        assertEquals(0, r.say.size)
        assertTrue(r.blocked.any { it.utterance.key == "pk1.front.1" && it.reason == BlockReason.OFF })
    }

    @Test
    fun registrationModesNeedsAndNoRecordWithoutParkingMode() {
        assertEquals(listOf(6), det("parking.PK1").modes)
        assertEquals(listOf(com.barodrive.core.model.SignalKey.SPEED), det("parking.PK2").needs)
        assertEquals(listOf(com.barodrive.core.model.SignalKey.STEER), det("parking.PK3").needs)
        val two = CoachEngine().createSession(SessionConfig(mode = 2), dets)
        assertEquals(listOf("basic.PB2"), two.info().detectors)
        assertNull(two.finish(arrived = false).parking)
    }
}
