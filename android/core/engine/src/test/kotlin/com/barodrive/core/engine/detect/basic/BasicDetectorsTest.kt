package com.barodrive.core.engine.detect.basic

import com.barodrive.core.engine.CoachEngine
import com.barodrive.core.engine.CoachSession
import com.barodrive.core.engine.Drive
import com.barodrive.core.engine.SnapSeq
import com.barodrive.core.engine.blocked
import com.barodrive.core.engine.content.Items
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.det
import com.barodrive.core.engine.drive
import com.barodrive.core.engine.nav
import com.barodrive.core.engine.of
import com.barodrive.core.engine.r1
import com.barodrive.core.engine.said
import com.barodrive.core.engine.snap
import com.barodrive.core.engine.tbt
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionHistory
import com.barodrive.core.model.Severe
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.TbtItem
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프로토타입 t2a_detectors.test.js — basic.* 검출기(설계 6.3.1, 칭찬 규칙 6.5.4, 카드, 회차 지표). */
class BasicDetectorsTest {
    private val all = listOf("A1", "PB1", "B1", "C1", "C2", "C4", "C5", "F1", "F2", "E1", "E2", "G1", "PB2")

    private fun sess(cfg: SessionConfig = SessionConfig(mode = 1), ids: List<String> = all): CoachSession =
        CoachEngine().createSession(cfg, ids.map { det("basic.$it") }, Lines.book)

    /** t2a `NAV(tbt, extra)`: 안내 중, 제한 30, heading 0. */
    private fun nv(tbt: List<TbtItem> = emptyList(), limit: Int? = 30, voice: Boolean = false, school: Boolean = false, events: List<NavEvent> = emptyList()): NavSnapshot =
        nav(tbt = tbt, limit = limit, voice = voice, inSchoolZone = school, events = events)

    /** t2a `seq(base)`: 기본은 기어 D · 30km/h · 내비 안내 중(제한 30). */
    private fun seq(base: (VehicleSnapshot) -> VehicleSnapshot = { it }) = SnapSeq { t -> base(snap(t = t, gear = Gear.D, speed = 30.0, nav = nv())) }

    /** t2a `turnPass`: from m에서 0.1초마다 1m씩 다가가 0에 닿은 뒤 tbt를 비우고 after초 더 달림. blinkAt m부터 깜빡이(wrong이면 반대쪽). */
    private fun turnPass(
        b: SnapSeq, blinkAt: Int?, dir: TurnDir = TurnDir.RIGHT, type: String? = null, from: Int = 150, after: Double = 9.0,
        speed: Double = 30.0, id: String = "t1", wrong: Boolean = false,
    ): SnapSeq {
        val tp = type ?: if (dir == TurnDir.LEFT) "TURN_LEFT" else "TURN_RIGHT"
        val side = if ((dir == TurnDir.LEFT) != wrong) Blinker.LEFT else Blinker.RIGHT
        b.add(from + 1) { _, i, s ->
            val d = from - i
            s.copy(speed = speed, blinker = if (blinkAt != null && d <= blinkAt) side else Blinker.OFF, nav = nv(listOf(tbt(id, tp, d.toDouble(), dir))))
        }
        b.sec(after) { _, _, s -> s.copy(speed = speed, nav = nv()) }
        return b
    }

    /** t2a `launch`: 정지(부드럽게 멈춘 뒤 브레이크 밟고 stopSec초) → 1초에 gain km/h, 그 뒤 4.7km/h/s. */
    private fun launch(b: SnapSeq, gain: Double, stopSec: Double = 2.0): SnapSeq {
        b.to(0.0)
        b.sec(stopSec) { _, _, s -> s.copy(speed = 0.0, brake = true) }
        b.add(10) { _, i, s -> s.copy(speed = gain * (i + 1) / 10, brake = false) }
        b.add(60) { _, i, s -> s.copy(speed = gain + 0.47 * (i + 1), brake = false) }
        return b
    }

    /** t2a `brake`: v0에서 1초에 drop km/h씩 v1까지, 멈추면 hold초 서 있음. */
    private fun brake(b: SnapSeq, v0: Double, drop: Double, v1: Double, hold: Double = 3.0): SnapSeq {
        b.to(v0)
        b.sec(1.0) { _, _, s -> s.copy(speed = v0, brake = false) }
        val n = Math.ceil((v0 - v1) / (drop / 10) - 1e-9).toInt()
        b.add(n) { _, i, s -> s.copy(speed = maxOf(v1, v0 - drop * (i + 1) / 10), brake = true) }
        if (v1 < 0.5) b.sec(hold) { _, _, s -> s.copy(speed = 0.0, brake = true) } else b.sec(2.0) { _, _, s -> s.copy(speed = v1, brake = false) }
        return b
    }

    /** t2a `steer`: 깜빡이를 blinkOnT에 켜고(null이면 안 켬) steerT부터 dur초 동안 angle°, 그 뒤 똑바로 3초(20km/h, 내비 없음). */
    private fun steer(b: SnapSeq, steerT: Double, blinkOnT: Double?, blinkOffT: Double? = null, dir: TurnDir = TurnDir.RIGHT, dur: Double = 3.0, angle: Double = 15.0): SnapSeq {
        val side = if (dir == TurnDir.LEFT) Blinker.LEFT else Blinker.RIGHT
        val sign = if (dir == TurnDir.LEFT) -1 else 1
        val end = steerT + dur
        val until = r1(end + 3)
        while (b.t < until - 1e-9) {
            b.add(1) { t, _, s ->
                val on = blinkOnT != null && t >= blinkOnT - 1e-9 && (blinkOffT == null || t < blinkOffT - 1e-9)
                s.copy(speed = 20.0, nav = nv(limit = null), blinker = if (on) side else Blinker.OFF,
                    steer = if (t >= steerT - 1e-9 && t < end - 1e-9) sign * angle else 0.0)
            }
        }
        return b
    }

    /** t2a `wait`: 부드럽게 멈추고 브레이크 밟고 sec초. */
    private fun wait(b: SnapSeq, sec: Double): SnapSeq {
        b.to(0.0) { _, _, s -> s.copy(nav = nv(limit = null)) }
        b.sec(sec) { _, _, s -> s.copy(speed = 0.0, brake = true, nav = nv(limit = null)) }
        return b
    }

    private fun run(s: CoachSession, b: SnapSeq): Drive = drive(s, b.list)

    @Test
    fun linesForTheBasicKeysExistForAllCoaches() {
        val keys = listOf("a1", "pb1.on", "b1.fix", "b1.ok", "c1", "c2.ok", "c2.almost", "c2.late", "c4.fix", "c5.fix", "f1.fix", "f1.ok", "e1", "e1.wx", "e2", "pb2")
        val vars = mapOf("N" to 55, "T" to 40, "L" to 30, "dir" to "R", "wx" to "rain")
        for (k in keys) for (c in Coach.entries) {
            val list = Lines.sentences(k, c)
            assertTrue("$k $c", list.isNotEmpty() && list.all { it.isNotBlank() })
            for (i in list.indices) {
                val text = Lines.render(k, c, vars, Honorific(HonorificType.DAUGHTER), index = i)
                assertTrue("$k $c → $text", text.length >= 4 && !text.contains('{') && !text.contains('}'))
            }
        }
        assertEquals("{N} 10m 내림", "좋았어, 50미터 전에 켰어.", Lines.render("c2.ok", Coach.DAD, mapOf("N" to 57), index = 0))
        assertEquals("어머 잘했다 우리 딸! 딱 좋았어!", Lines.render("c2.ok", Coach.MOM, mapOf("N" to 55), Honorific(HonorificType.DAUGHTER), index = 1))
        assertEquals("왼쪽 깜빡이 미리 켜 볼까?", Lines.render("c1", Coach.DAD, mapOf("dir" to "L"), index = 0))
        assertEquals("비 오는 날은 40까지야, 조금 줄이자.", Lines.render("e1.wx", Coach.DAD, mapOf("wx" to "rain", "L" to 40), index = 0))
        assertEquals("야, 지금 켜면 늦어. 다음엔 110미터 전에 켜라.", Lines.render("c2.late", Coach.SIB, mapOf("T" to 110), index = 0))
    }

    @Test
    fun oneDetectorPerItemWithTheItemNeedsAndOnlyPb2InParking() {
        for (id in all) {
            val d = det("basic.$id")
            assertEquals(listOf(id), d.items)
            for (k in checkNotNull(Items.def(id)).needs) assertTrue("$id needs $k", d.needs.any { it.key == k })
        }
        assertEquals(listOf("basic.PB2"), sess(SessionConfig(mode = 6)).info().detectors)
        val s1 = sess(SessionConfig(mode = 1, caps = SignalCaps(speed = false)))
        for (id in listOf("B1", "F1", "F2", "E1", "E2")) assertTrue(id, s1.info().off.any { it.id == "basic.$id" && it.reason == "caps:speed" })
    }

    @Test
    fun a1BeltUnbuckledWhenLeavingParkEveryTime() {
        val b = seq().add(5) { _, _, s -> s.copy(beltDriver = false) }.add(5) { _, _, s -> s.copy(gear = Gear.P, speed = 0.0, beltDriver = false) }
            .add(5) { _, _, s -> s.copy(gear = Gear.R, speed = 0.0, beltDriver = false) }
        val out = run(sess(ids = listOf("A1")), b)
        assertEquals("P → D, P → R", 2, out.events.of("A1", "fail").size)
        assertEquals(listOf(Gear.D, Gear.R), out.events.of("A1").map { it.data.gear })
        assertTrue(said(out, "a1"))
        assertEquals(0, run(sess(ids = listOf("A1")), seq().add(5)).events.size)
    }

    @Test
    fun pb1HandbrakeOnForMoreThanTwoSeconds() {
        fun run(sec: Double): Drive = run(sess(ids = listOf("PB1")), seq().add(5) { _, _, s -> s.copy(speed = 0.0) }
            .add(Math.round(sec * 10).toInt() + 1) { _, _, s -> s.copy(speed = 0.0, handbrake = true) }.add(5) { _, _, s -> s.copy(speed = 0.0) })
        assertEquals("2.0초", 0, run(2.0).events.of("PB1").size)
        val o = run(2.1)
        assertEquals(1, o.events.of("PB1", "fail").size)
        assertTrue(said(o, "pb1.on"))
        val b = seq().add(40) { _, _, s -> s.copy(speed = 0.0, handbrake = true) }.add(5) { _, _, s -> s.copy(speed = 0.0) }
            .add(40) { _, _, s -> s.copy(speed = 0.0, handbrake = true) }
        assertEquals("다시 채우면 또", 2, run(sess(ids = listOf("PB1")), b).events.of("PB1").size)
    }

    @Test
    fun b1GainOver11IsHardAndWeatherLowersTo8() {
        fun one(gain: Double, cfg: SessionConfig = SessionConfig(mode = 2)) = run(sess(cfg, listOf("B1")), launch(seq(), gain)).events.of("B1").single()
        assertEquals(EventResult.OK, one(11.0).result)
        assertEquals(11.0, one(11.0).data.maxGain1s)
        assertEquals(EventResult.FAIL, one(11.01).result)
        assertEquals(EventResult.OK, one(8.0, SessionConfig(mode = 2, weather = Weather(rain = true))).result)
        val w = one(8.01, SessionConfig(mode = 2, weather = Weather(rain = true)))
        assertEquals(EventResult.FAIL, w.result)
        assertEquals(8.0, w.data.threshold)
        assertEquals("눈도 8", EventResult.FAIL, one(8.01, SessionConfig(mode = 2, weather = Weather(snow = true))).result)
    }

    @Test
    fun b1CardAtTheNextSignalWaitOnlyWhenNow() {
        val b = brake(launch(seq(), 13.0), 30.0, 4.0, 0.0, 4.0)
        val out = run(sess(SessionConfig(mode = 2), listOf("B1")), b)
        assertEquals(1, out.cards.size)
        assertEquals("B1", out.cards[0].item)
        assertEquals("출발이 조금 셌어요", out.cards[0].title)
        assertTrue(out.cards[0].detail.orEmpty().contains("13km/h"))
        assertTrue("맨 앞 카드 문장 하나를 말한다", said(out, "b1.fix"))
        val sOut = run(sess(SessionConfig(mode = 2, items = mapOf("B1" to Delivery.AFTER)), listOf("B1")), launch(seq(), 13.0))
        assertEquals(0, sOut.cards.size)
        assertTrue(blocked(sOut, "b1.fix", BlockReason.AFTER))
    }

    @Test
    fun praiseRules() {
        assertTrue("모드 1: 첫 성공에 칭찬", said(run(sess(SessionConfig(mode = 1), listOf("B1")), launch(seq(), 4.0)), "b1.ok"))
        val b2 = launch(seq(), 4.0)
        launch(b2, 13.0, stopSec = 3.0)
        wait(b2, 25.0)
        launch(b2, 4.0, stopSec = 0.0)
        b2.sec(10.0)
        val m2 = run(sess(SessionConfig(mode = 2), listOf("B1")), b2)
        assertEquals(listOf(EventResult.OK, EventResult.FAIL, EventResult.OK), m2.events.of("B1").map { it.result })
        fun cand(o: Drive) = o.say.filter { it.key == "b1.ok" }.map { it.t } + o.blocked.filter { it.utterance.key == "b1.ok" }.map { it.utterance.t }
        assertEquals("실패 다음 첫 성공만", 1, cand(m2).size)
        assertTrue(cand(m2)[0] > m2.events.of("B1", "fail")[0].t)
        assertTrue(said(m2, "b1.ok"))
        val b3 = launch(seq(), 4.0)
        b3.sec(30.0)
        launch(b3, 13.0, stopSec = 3.0)
        b3.sec(30.0)
        launch(b3, 4.0, stopSec = 3.0)
        val m3 = run(sess(SessionConfig(mode = 1), listOf("B1")), b3)
        assertEquals(listOf(EventResult.OK, EventResult.FAIL, EventResult.OK), m3.events.of("B1").map { it.result })
        assertEquals("5분에 1번", 1, cand(m3).size)
        val fOut = run(sess(SessionConfig(mode = 1), listOf("F1")), brake(seq(), 40.0, 5.0, 20.0))
        assertEquals(1, fOut.events.of("F1", "ok").size)
        assertFalse("감속만 한 제동은 칭찬하지 않음", said(fOut, "f1.ok"))
        assertTrue("멈춘 첫 성공은 칭찬(모드 1)", said(run(sess(SessionConfig(mode = 1), listOf("F1")), brake(seq(), 30.0, 5.0, 0.0)), "f1.ok"))
    }

    @Test
    fun f1DropOver7point5AndWeather6AndTheCardShowsAtTheStop() {
        fun one(drop: Double, cfg: SessionConfig = SessionConfig(mode = 2)): Pair<com.barodrive.core.model.JudgeEvent, Drive> {
            val out = run(sess(cfg, listOf("F1")), brake(seq(), 30.0, drop, 0.0, 3.0))
            return out.events.of("F1").single() to out
        }
        val (e, out) = one(7.5)
        assertEquals(EventResult.FAIL, e.result)
        assertEquals(7.5, e.data.maxDrop1s)
        assertEquals(true, e.data.stopped)
        assertEquals(EventResult.OK, one(7.49).first.result)
        assertEquals(EventResult.FAIL, one(6.0, SessionConfig(mode = 2, weather = Weather(rain = true))).first.result)
        assertEquals(EventResult.OK, one(5.99, SessionConfig(mode = 2, weather = Weather(snow = true))).first.result)
        val c = out.cards[0]
        assertEquals("F1", c.item)
        assertEquals("조금 급하게 섰어요", c.title)
        assertTrue(c.detail.orEmpty(), Regex("^30km/h에서 [\\d.]+초 만에 정지 · 가장 셀 때 1초에 7\\.5km/h$").matches(c.detail.orEmpty()))
        assertEquals("정지가 신호 대기면 바로 그때 카드", e.t, c.shownAt)
        assertTrue(said(out, "f1.fix"))
    }

    @Test
    fun f1ShortBrakingIsNotEvaluatedButSlowingMoreThan10Is() {
        fun run(v0: Double, v1: Double) = run(sess(SessionConfig(mode = 2), listOf("F1")), brake(seq(), v0, 5.0, v1))
        assertEquals("9km/h에서 정지", 0, run(9.0, 0.0).events.of("F1").size)
        assertEquals("8km/h만 줄임", 0, run(30.0, 22.0).events.of("F1").size)
        val d = run(40.0, 28.0).events.of("F1")
        assertEquals("12km/h 줄임", 1, d.size)
        assertEquals(false, d[0].data.stopped)
        val dec = run(sess(SessionConfig(mode = 2), listOf("F1")), wait(brake(seq(), 40.0, 8.0, 25.0), 3.0))
        assertEquals("카드는 다음 신호 대기에", "조금 급하게 줄였어요", dec.cards[0].title)
        assertEquals("40km/h에서 25km/h로 · 가장 셀 때 1초에 8km/h", dec.cards[0].detail)
    }

    @Test
    fun f2HardBrakeIsExcludedFromF1SilentAndTheNextStartIsExcluded() {
        val b = launch(brake(seq(), 33.0, 11.0, 0.0, 3.0), 13.0, stopSec = 1.0)
        val s = sess(SessionConfig(mode = 2), listOf("F1", "F2", "B1"))
        val out = run(s, b)
        assertEquals(0, out.events.of("F1").size)
        val f2 = out.events.of("F2")
        assertEquals(1, f2.size)
        assertEquals(11.0, f2[0].data.maxDrop1s)
        assertEquals("급제동 뒤 첫 출발은 B1에서 뺌", listOf(EventResult.EXCLUDED to "F2"), out.events.of("B1").map { it.result to it.data.reason })
        assertEquals(0, out.cards.size)
        assertEquals("F2는 말하지 않는다", 0, out.say.size)
        val rec = s.finish(arrived = true)
        assertEquals(1, rec.f2.size)
        assertEquals("f2-1", rec.f2[0].id)
        assertNull(rec.f2[0].choice)
        assertEquals(33.0, rec.f2[0].v0)
        assertEquals(true, rec.f2[0].stopped)
        assertFalse("F2 정지는 기록(events)에서 뺀다", rec.events.any { it.item == "F2" })
        val near = run(sess(SessionConfig(mode = 2), listOf("F1", "F2")), brake(seq(), 32.97, 10.99, 0.0, 3.0))
        assertEquals(listOf(EventResult.FAIL), near.events.of("F1").map { it.result })
        assertEquals(0, near.events.of("F2").size)
        val q = brake(seq(), 33.0, 11.0, 0.0, 1.0)
        q.add(1) { _, _, x -> x.copy(speed = 0.0, gear = Gear.P, beltDriver = true) }
        q.add(5) { _, _, x -> x.copy(speed = 0.0, gear = Gear.D, beltDriver = false) }
        q.sec(40.0) { _, _, x -> x.copy(speed = 20.0) }
        val qo = run(sess(SessionConfig(mode = 2), listOf("F2", "A1")), q)
        val endT = qo.events.of("F2")[0].t
        val a1 = qo.say.firstOrNull { it.key == "a1" }
        assertTrue("벨트 판정은 침묵 중에 났지만", qo.events.of("A1").size == 1 && qo.events.of("A1")[0].t < endT + 30)
        assertTrue("말은 급제동 뒤 30초 침묵이 끝난 뒤에(${a1?.t})", a1 != null && a1.t >= endT + 30 - 1e-6)
    }

    @Test
    fun c2Distances() {
        class One(val e: com.barodrive.core.model.JudgeEvent, val out: Drive, val rec: com.barodrive.core.model.SessionRecord)
        fun one(blinkAt: Int?): One {
            val s = sess(SessionConfig(mode = 1), listOf("C2"))
            val out = run(s, turnPass(seq(), blinkAt))
            return One(out.events.of("C2").single(), out, s.finish())
        }
        val ok = one(40)
        assertEquals(EventResult.OK, ok.e.result)
        assertEquals(40.0, ok.e.data.distance)
        assertEquals("t1", ok.e.data.turnId)
        val okSay = ok.out.say.first { it.key == "c2.ok" }
        assertEquals("성공은 깜빡이를 켜는 순간(결정-43)", ok.e.t, okSay.t, 1e-9)
        assertEquals("좋았어, 40미터 전에 켰어.", okSay.text)
        val a39 = one(39)
        assertEquals(EventResult.ALMOST, a39.e.result)
        assertEquals(39.0, a39.e.data.distance)
        val sayA = a39.out.say.first { it.key == "c2.almost" }
        assertTrue("거의는 회전이 끝난 뒤", sayA.t >= a39.e.t)
        assertEquals("거의 됐어. 다음엔 40미터쯤 전에 켜 보자.", sayA.text)
        assertEquals(EventResult.ALMOST, one(30).e.result)
        val l29 = one(29)
        assertEquals(EventResult.LATE, l29.e.result)
        assertTrue(said(l29.out, "c2.late"))
        val none = one(null)
        assertEquals(EventResult.FAIL, none.e.result)
        assertNull(none.e.data.distance)
        assertEquals("안 켬은 C5가 카드로 잡는다", 0, none.out.say.size)
        assertEquals(40.0, ok.rec.metrics.c2Avg)
        assertEquals(1, ok.rec.metrics.c2N)
        assertNull(none.rec.metrics.c2Avg)
    }

    @Test
    fun c2IcUses110And100() {
        fun one(blinkAt: Int): Drive = run(sess(SessionConfig(mode = 4), listOf("C2")),
            turnPass(seq { it.copy(speed = 60.0) }, blinkAt, type = "TURN_ENTER_IC_R", from = 300, speed = 60.0))
        assertEquals(EventResult.OK, one(110).events.of("C2")[0].result)
        assertEquals(true, one(110).events.of("C2")[0].data.ic)
        assertEquals(EventResult.ALMOST, one(109).events.of("C2")[0].result)
        assertEquals(EventResult.ALMOST, one(100).events.of("C2")[0].result)
        val late = one(99)
        assertEquals(EventResult.LATE, late.events.of("C2")[0].result)
        assertTrue("모드 4 C2는 완료 후(S)", blocked(late, "c2.late", BlockReason.AFTER))
        val one1 = run(sess(SessionConfig(mode = 1), listOf("C2")), turnPass(seq { it.copy(speed = 60.0) }, 99, type = "TURN_EXIT_IC_R", from = 300, speed = 60.0))
        assertTrue(one1.say.first { it.key == "c2.late" }.text.contains("110미터"))
    }

    @Test
    fun c2MissedTurnIsExcludedAndLeftOutOfTheAverage() {
        val s = sess(SessionConfig(mode = 1), listOf("C2"))
        val b = seq().add(101) { _, i, x -> x.copy(blinker = if (100 - i <= 50) Blinker.RIGHT else Blinker.OFF, nav = nv(listOf(tbt("t1", "TURN_RIGHT", (100 - i).toDouble(), TurnDir.RIGHT)))) }
        b.add(1) { _, _, x -> x.copy(nav = nv(events = listOf(NavEvent.Reroute("t1")))) }
        b.sec(10.0) { _, _, x -> x.copy(nav = nv()) }
        val out = run(s, b)
        assertEquals("켜는 순간엔 성공으로 냈다가", listOf(EventResult.OK), out.events.of("C2").map { it.result })
        val rec = s.finish()
        assertEquals(listOf(EventResult.EXCLUDED to "reroute"), rec.events.of("C2").map { it.result to it.data.excluded })
        assertNull(rec.metrics.c2Avg)
    }

    @Test
    fun c1PreAnnouncementAt70mPairedWithC2() {
        val out = run(sess(SessionConfig(mode = 1), listOf("C1", "C2")), turnPass(seq(), 55))
        assertEquals(listOf(70.0), out.events.of("C1").map { it.data.distance })
        val c1 = out.say.first { it.key == "c1" }
        val c2 = out.say.first { it.key == "c2.ok" }
        assertEquals("오른쪽 깜빡이 미리 켜 볼까?", c1.text)
        assertTrue("같은 회전 짝이라 20초 간격을 기다리지 않음", c2.t - (c1.t + (c1.dur ?: 0.0)) < 20)
        assertEquals(0, run(sess(SessionConfig(mode = 1), listOf("C1")), turnPass(seq(), 80)).events.of("C1").size)
        val nvb = seq().add(151) { _, i, x ->
            val d = 150 - i
            x.copy(blinker = Blinker.OFF, nav = nv(listOf(tbt("t1", "TURN_RIGHT", d.toDouble(), TurnDir.RIGHT)), voice = d <= 72 && d >= 59))
        }
        val nvOut = run(sess(SessionConfig(mode = 1), listOf("C1")), nvb)
        assertEquals(1, nvOut.events.of("C1").size)
        assertFalse(said(nvOut, "c1"))
        assertTrue(blocked(nvOut, "c1", BlockReason.UNDER_50))
        val m3 = run(sess(SessionConfig(mode = 3), listOf("C1")), turnPass(seq(), 40))
        assertEquals(1, m3.events.of("C1").size)
        assertTrue(blocked(m3, "c1", BlockReason.OFF))
    }

    @Test
    fun c4BlinkThenSteerUnderThreeSecondsAndC5Before() {
        fun run(steerT: Double): Drive {
            val b = seq().sec(5.0) { _, _, x -> x.copy(speed = 20.0, nav = nv(limit = null)) }
            steer(b, steerT, 10.0)
            wait(b, 3.0)
            return run(sess(SessionConfig(mode = 2), listOf("C4", "C5")), b)
        }
        val f = run(12.9)
        assertEquals(listOf(EventResult.FAIL to 2.9), f.events.of("C4").map { it.result to it.data.interval })
        assertEquals("C4", f.cards[0].item)
        assertEquals("깜빡이 켜고 2.9초 만에 핸들을 돌렸어요", f.cards[0].detail)
        assertTrue(said(f, "c4.fix"))
        assertEquals(listOf(EventResult.OK to true), f.events.of("C5").map { it.result to it.data.before })
        assertEquals(listOf(EventResult.OK to 3.0), run(13.0).events.of("C4").map { it.result to it.data.interval })
        val b = seq().sec(5.0) { _, _, x -> x.copy(speed = 20.0, nav = nv(limit = null)) }
        steer(b, 10.0, 10.5)
        val late = run(sess(SessionConfig(mode = 2), listOf("C4", "C5")), b)
        assertEquals(listOf(EventResult.FAIL to -0.5), late.events.of("C4").map { it.result to it.data.interval })
        assertEquals(listOf(EventResult.OK to false), late.events.of("C5").map { it.result to it.data.before })
    }

    @Test
    fun c5NoBlinkInTheWindowAroundTheSteerTurn() {
        fun run(blinkOnT: Double?, blinkOffT: Double? = null): Drive {
            val b = seq().sec(5.0) { _, _, x -> x.copy(speed = 20.0, nav = nv(limit = null)) }
            steer(b, 20.0, blinkOnT, blinkOffT)
            wait(b, 3.0)
            return run(sess(SessionConfig(mode = 1), listOf("C4", "C5")), b)
        }
        val none = run(null)
        assertEquals(listOf(EventResult.FAIL), none.events.of("C5").map { it.result })
        assertEquals("깜빡이가 없으면 C4는 판정하지 않음", 0, none.events.of("C4").size)
        assertEquals("C5", none.cards[0].item)
        assertEquals("오른쪽으로 꺾을 때 깜빡이가 꺼져 있었어요", none.cards[0].detail)
        assertTrue(said(none, "c5.fix"))
        assertEquals("5초 창 바로 앞(14.9)은 안 셈", listOf(EventResult.FAIL), run(14.9, 15.0).events.of("C5").map { it.result })
        assertEquals("창 시작(15.0)은 셈", listOf(EventResult.OK), run(15.0, 15.1).events.of("C5").map { it.result })
        assertEquals("시작 1초 뒤(21.0)까지 셈", listOf(EventResult.OK), run(21.0, 21.1).events.of("C5").map { it.result })
        assertEquals("1초 넘으면 안 셈", listOf(EventResult.FAIL), run(21.1, 21.2).events.of("C5").map { it.result })
    }

    @Test
    fun steerTurnBoundaryTwoSeconds() {
        fun run(dur: Double): Drive {
            val b = seq().sec(5.0) { _, _, x -> x.copy(speed = 20.0, nav = nv(limit = null)) }
            steer(b, 10.0, null, dur = dur)
            return run(sess(SessionConfig(mode = 1), listOf("C4", "C5")), b)
        }
        assertEquals(0, run(2.0).events.of("C5").size)
        assertEquals(1, run(2.2).events.of("C5").size)
    }

    @Test
    fun blinkerLeftOnFromTheLastTurnDoesNotCount() {
        val b = seq().sec(5.0) { _, _, x -> x.copy(speed = 20.0, nav = nv(limit = null)) }
        steer(b, 10.0, 6.0)
        steer(b, r1(b.t + 1), 6.0)
        val out = run(sess(SessionConfig(mode = 1), listOf("C4", "C5")), b)
        assertEquals("첫 회전만 C4", listOf(EventResult.OK), out.events.of("C4").map { it.result })
        assertEquals(listOf(EventResult.OK to true, EventResult.OK to false), out.events.of("C5").map { it.result to it.data.before })
    }

    @Test
    fun praiseCardAfterAGoodNavTurnInModes128() {
        fun build(mode: Int, withFix: Boolean): Drive {
            val b = seq()
            b.add(81) { _, i, x -> x.copy(speed = 20.0, blinker = if (80 - i <= 50) Blinker.RIGHT else Blinker.OFF, nav = nv(listOf(tbt("t1", "TURN_RIGHT", (80 - i).toDouble(), TurnDir.RIGHT)))) }
            b.add(40) { _, i, x -> x.copy(speed = 15.0, steer = if (i < 35) 15.0 else 0.0, blinker = if (i < 35) Blinker.RIGHT else Blinker.OFF, nav = nv()) }
            b.sec(3.0) { _, _, x -> x.copy(speed = 20.0, nav = nv()) }
            if (withFix) brake(b, 30.0, 9.0, 0.0, 4.0) else brake(b, 30.0, 5.0, 0.0, 4.0)
            return run(sess(SessionConfig(mode = mode), listOf("C2", "C4", "C5", "F1")), b)
        }
        val ok = build(1, false)
        assertEquals(listOf(EventResult.OK), ok.events.of("C2").map { it.result })
        assertEquals(listOf(EventResult.OK to "t1"), ok.events.of("C4").map { it.result to it.data.turnId })
        val pc = ok.cards.filter { it.category == CardCategory.PRAISE }
        assertEquals(1, pc.size)
        assertEquals("C2", pc[0].item)
        assertEquals("깜빡이 타이밍 좋았어요", pc[0].title)
        assertTrue(pc[0].detail.orEmpty(), Regex("^50m 전에 켜고 [\\d.]+초 뒤에 꺾었어요$").matches(pc[0].detail.orEmpty()))
        assertEquals("모드 3은 없음", 0, build(3, false).cards.count { it.category == CardCategory.PRAISE })
        assertEquals("교정 카드가 있으면 칭찬 카드는 안 보임", listOf(CardCategory.FIX), build(2, true).cards.map { it.category })
    }

    @Test
    fun e1OverTheLimitMoreThanThreeSecondsOncePerZone() {
        class R(val out: Drive, val rec: com.barodrive.core.model.SessionRecord)
        fun run(sec: Double): R {
            val s = sess(SessionConfig(mode = 1), listOf("E1"))
            val b = seq().sec(5.0).add(Math.round(sec * 10).toInt() + 1) { _, _, x -> x.copy(speed = 31.0) }.sec(3.0) { _, _, x -> x.copy(speed = 28.0) }
                .sec(5.0) { _, _, x -> x.copy(speed = 28.0, nav = nv(limit = 50)) }
            val out = run(s, b)
            return R(out, s.finish())
        }
        assertEquals("30 구역·50 구역 모두 지킴", listOf(EventResult.OK, EventResult.OK), run(3.0).rec.events.of("E1").map { it.result })
        val y = run(3.1)
        val f = y.out.events.of("E1", "fail")
        assertEquals(1, f.size)
        assertEquals(listOf(30.0, 30.0, 3.1), listOf(f[0].data.limit, f[0].data.effLimit, f[0].data.overSec))
        assertEquals(1, y.out.say.count { it.key == "e1" })
        assertEquals("여기 30km 구간이야, 조금 줄이자.", y.out.say.first { it.key == "e1" }.text)
        assertEquals(Category.SAFETY, y.out.say.first { it.key == "e1" }.category)
        val out = run(sess(SessionConfig(mode = 1), listOf("E1")), seq().sec(5.0).sec(5.0) { _, _, x -> x.copy(speed = 36.0) }
            .sec(3.0) { _, _, x -> x.copy(speed = 28.0) }.sec(5.0) { _, _, x -> x.copy(speed = 38.0) })
        assertEquals("같은 구역에서 다시 넘어도 1번", 1, out.events.of("E1", "fail").size)
        assertEquals(1, out.say.count { it.key == "e1" })
    }

    @Test
    fun e1WeatherLowersTheLimitEvenWhenJ3IsOff() {
        val out = run(sess(SessionConfig(mode = 1, weather = Weather(rain = true), items = mapOf("J3" to Delivery.OFF)), listOf("E1")),
            seq { it.copy(nav = nv(limit = 50)) }.sec(5.0) { _, _, x -> x.copy(speed = 38.0) }.sec(5.0) { _, _, x -> x.copy(speed = 43.0) })
        val f = out.events.of("E1", "fail")
        assertEquals(1, f.size)
        assertEquals(40.0, f[0].data.effLimit)
        assertEquals("e1.wx", out.say[0].key)
        assertEquals("비 오는 날은 40까지야, 조금 줄이자.", out.say[0].text)
        val sv = run(sess(SessionConfig(mode = 1, weather = Weather(severe = Severe.ICE)), listOf("E1")), seq().sec(5.0) { _, _, x -> x.copy(speed = 16.0) })
        assertEquals(15.0, sv.events.of("E1", "fail")[0].data.effLimit)
        assertEquals("오늘 같은 날은 15까지야, 조금 줄이자.", sv.say[0].text)
    }

    @Test
    fun e2SchoolZoneWithinThreeSecondsAndE1StaysQuietThere() {
        val out = run(sess(SessionConfig(mode = 1), listOf("E1", "E2")), seq().sec(3.0) { _, _, x -> x.copy(speed = 35.0, nav = nv(limit = 50)) }
            .sec(8.0) { _, _, x -> x.copy(speed = 35.0, nav = nv(limit = 30, school = true)) }.sec(3.0) { _, _, x -> x.copy(speed = 30.0, nav = nv(limit = 50)) })
        assertEquals(1, out.events.of("E2", "fail").size)
        assertEquals(30.0, out.events.of("E2")[0].data.effLimit)
        assertEquals("E1 기록은 남음", 1, out.events.of("E1", "fail").size)
        assertTrue(said(out, "e2"))
        assertEquals("어린이보호구역이야. 30 아래로 가자.", out.say.first { it.key == "e2" }.text)
        assertFalse("E2를 말한 구역은 E1을 말하지 않음(결정-70)", said(out, "e1"))
        val o2 = run(sess(SessionConfig(mode = 1), listOf("E1", "E2")), seq().sec(3.0) { _, _, x -> x.copy(speed = 28.0, nav = nv(limit = 50)) }
            .sec(3.1) { _, _, x -> x.copy(speed = 28.0, nav = nv(limit = 30, school = true)) }.sec(5.0) { _, _, x -> x.copy(speed = 36.0, nav = nv(limit = 30, school = true)) })
        assertEquals(0, o2.events.of("E2").size)
        assertTrue(said(o2, "e1"))
        val o3 = run(sess(SessionConfig(mode = 1, weather = Weather(rain = true)), listOf("E2")),
            seq().sec(2.0) { _, _, x -> x.copy(speed = 26.0, nav = nv(limit = 30, school = true)) })
        assertEquals("비 오는 날 어린이보호구역 기준 24", 24.0, o3.events.of("E2")[0].data.effLimit)
    }

    @Test
    fun g1AverageStdOfStraightWindowsAgainstTheFirstSessionBase() {
        fun wave(amp: Double) = { _: Double, i: Int, x: VehicleSnapshot -> x.copy(speed = 40.0, steer = (if (i % 2 != 0) 1 else -1) * amp, nav = nv(limit = 50)) }
        val s = sess(SessionConfig(mode = 1, history = SessionHistory(baselineG1 = 1.25)), listOf("G1"))
        val b = seq()
        for (amp in listOf(0.5, 1.0, 1.5)) b.add(101, wave(amp))
        drive(s, b.list)
        val rec = s.finish(arrived = true)
        val g = rec.events.of("G1")[0]
        assertEquals(EventResult.INFO, g.result)
        assertEquals(3, g.data.windows)
        assertEquals(1.0, g.data.value)
        assertEquals(1.25, g.data.base)
        assertEquals("(1.25 − 1.0) / 1.25", 0.2, g.data.better)
        assertEquals(1.0 to 3, rec.metrics.g1 to rec.metrics.g1Windows)
        val s2 = sess(SessionConfig(mode = 1), listOf("G1"))
        val b2 = seq()
        repeat(2) { b2.add(101, wave(1.0)) }
        drive(s2, b2.list)
        val rec2 = s2.finish()
        val g2 = rec2.events.of("G1")[0]
        assertEquals(listOf<Any?>(2, null, true, 0.8), listOf(g2.data.windows, g2.data.value, g2.data.insufficient, g2.data.base))
        assertNull(rec2.metrics.g1)
    }

    @Test
    fun pb2HandbrakeAfterParkWithinThreeSeconds() {
        fun run(cfg: SessionConfig = SessionConfig(mode = 1), hbAt: Double? = null): Drive = run(sess(cfg, listOf("PB2")),
            seq().sec(3.0).add(30) { _, i, x -> x.copy(gear = Gear.P, speed = 0.0, handbrake = hbAt != null && i >= hbAt * 10) })
        val no = run()
        assertEquals(listOf(EventResult.FAIL to 3.0), no.events.of("PB2").map { it.result to it.data.dur })
        assertEquals(1, no.say.size)
        assertEquals("pb2", no.say[0].key)
        assertEquals("P(3.1초) 뒤 30번째 표본(6.0초)", 6.0, no.say[0].t, 1e-9)
        val yes = run(hbAt = 1.5)
        assertEquals(listOf(EventResult.OK), yes.events.of("PB2").map { it.result })
        assertEquals(0, yes.say.size)
        assertTrue("모드 6(주차)에서도 O", said(run(SessionConfig(mode = 6)), "pb2"))
        assertTrue("모드 3은 안 함(X)", blocked(run(SessionConfig(mode = 3)), "pb2", BlockReason.OFF))
        assertEquals("pb2.hill", run(SessionConfig(mode = 7)).say[0].key)
        assertEquals("pb2", run(SessionConfig(mode = 1)).say[0].key)
    }

    @Test
    fun pb2AsksOnceAfterArrivalWhenTheHandbrakeIsUnreadable() {
        val s = sess(SessionConfig(mode = 1, caps = SignalCaps(handbrake = false)), listOf("PB2"))
        val b = seq().sec(2.0).add(1) { _, _, x -> x.copy(nav = nv(events = listOf(NavEvent.Arrived))) }.sec(1.0)
            .sec(3.0) { _, _, x -> x.copy(gear = Gear.P, speed = 0.0) }
        val out = run(s, b)
        assertEquals(listOf(EventResult.INFO to true), out.events.of("PB2").map { it.result to it.data.unreadable })
        assertTrue(said(out, "pb2"))
        val pre = run(sess(SessionConfig(mode = 1, caps = SignalCaps(handbrake = false)), listOf("PB2")),
            seq().sec(2.0).sec(4.0) { _, _, x -> x.copy(gear = Gear.P, speed = 0.0) })
        assertEquals("도착 전 P(중간 정차)에서는 묻지 않음", 0, pre.events.size)
    }

    @Test
    fun nonNowItemsStillJudgeButOnlyLogBlockedLines() {
        val b = brake(launch(seq(), 13.0), 30.0, 9.0, 0.0, 4.0)
        val out = run(sess(SessionConfig(mode = 3), listOf("B1", "F1")), b)
        assertEquals(listOf(EventResult.FAIL), out.events.of("B1").map { it.result })
        assertEquals(listOf(EventResult.FAIL), out.events.of("F1").map { it.result })
        assertEquals(0, out.cards.size)
        assertTrue(blocked(out, "b1.fix", BlockReason.AFTER))
        assertTrue(blocked(out, "f1.fix", BlockReason.AFTER))
        val x = run(sess(SessionConfig(mode = 1, items = mapOf("F1" to Delivery.OFF)), listOf("F1")), brake(seq(), 30.0, 9.0, 0.0, 4.0))
        assertEquals(1, x.events.of("F1", "fail").size)
        assertTrue(blocked(x, "f1.fix", BlockReason.OFF))
    }
}
