package com.barodrive.core.engine.detect.safety3

import com.barodrive.core.engine.CoachEngine
import com.barodrive.core.engine.CoachSession
import com.barodrive.core.engine.Drive
import com.barodrive.core.engine.SnapSeq
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.content.Items
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.det
import com.barodrive.core.engine.drive
import com.barodrive.core.engine.nav
import com.barodrive.core.engine.of
import com.barodrive.core.engine.r1
import com.barodrive.core.engine.said
import com.barodrive.core.engine.snap
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EngineFlags
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.NavStatus
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.TbtItem
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.abs
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프로토타입 t3_safety3.test.js — 안전3(C3·C6·J1) 경계와 스위치(설계 6.3.1·12, 결정-91). */
class Safety3DetectorsTest {
    private val ids = listOf("C3", "C6", "J1")

    private fun sess(cfg: SessionConfig = SessionConfig(mode = 1), only: List<String> = ids): CoachSession =
        CoachEngine().createSession(cfg, only.map { det("safety3.$it") }, Lines.book)

    /** t3 `NAV(tbt, extra)`: 안내 중, 제한 50. */
    private fun nv(tbt: List<TbtItem> = emptyList(), limit: Int? = 50, voice: Boolean = false, congested: Boolean = false, events: List<NavEvent> = emptyList()): NavSnapshot =
        nav(tbt = tbt, limit = limit, voice = voice, congested = congested, events = events)

    /** t3 `NONAV()`: 안내 없음. */
    private fun noNav(): NavSnapshot = nav(status = NavStatus.INACTIVE, limit = null)

    /** t3 `seq(base)`: 기어 D · 30km/h · 내비 안내 없음 · 핸들 0 · 깜빡이 끔 · 낮. */
    private fun seq(base: (VehicleSnapshot) -> VehicleSnapshot = { it }) = SnapSeq { t -> base(snap(t = t, gear = Gear.D, speed = 30.0, nav = noNav())) }

    /** t3 `approach`: from m에서 0.1초마다 step m씩 줄여 0까지(거리는 10m 내림), blink(d) → 그 거리의 깜빡이. */
    private fun approach(
        b: SnapSeq, id: String, dir: TurnDir = TurnDir.RIGHT, type: String? = null, from: Double = 150.0, step: Double = 1.0,
        speed: Double = 30.0, ic: Boolean = false, voice: Boolean = false, blink: (Double) -> Blinker = { Blinker.OFF },
    ): SnapSeq {
        val tp = type ?: if (dir == TurnDir.LEFT) "TURN_LEFT" else "TURN_RIGHT"
        var d = from
        while (d >= 0) {
            val dd = d
            b.add(1) { _, _, s -> s.copy(speed = speed, blinker = blink(dd), nav = nv(listOf(TbtItem(id = id, type = tp, distance = floor(dd / 10) * 10, dir = dir, ic = ic)), voice = voice)) }
            d -= step
        }
        return b
    }

    private fun right(on: Boolean) = if (on) Blinker.RIGHT else Blinker.OFF
    private fun left(on: Boolean) = if (on) Blinker.LEFT else Blinker.OFF

    @Test
    fun linesForSafety3AndStagePassKeys() {
        val keys = listOf("c3", "c6", "j1", "rv.pass.1", "rv.pass.2", "rv.pass.3", "rv.grad")
        for (k in keys) for (c in Coach.entries) {
            assertTrue("$k $c", Lines.sentences(k, c).isNotEmpty())
            for (h in listOf(Honorific(), Honorific(HonorificType.DAUGHTER))) {
                val text = Lines.render(k, c, mapOf("dir" to "R"), h, index = 0)
                assertTrue("$k $c → $text", text.length >= 4 && !text.contains('{'))
            }
        }
        fun l(k: String, c: Coach, vars: Map<String, Any?> = emptyMap(), h: Honorific? = null) = Lines.render(k, c, vars, h, index = 0)
        assertEquals("오른쪽으로 가는 길이야. 깜빡이 바꿔 보자.", l("c3", Coach.DAD, mapOf("dir" to "R")))
        assertEquals("어머, 반대! 왼쪽으로 가는 길이야!", l("c3", Coach.MOM, mapOf("dir" to "L")))
        assertEquals("반대야. 왼쪽으로 가는 길이야.", l("c3", Coach.SIB, mapOf("dir" to "L")))
        assertEquals("깜빡이가 아직 켜져 있어. 꺼 볼까?", l("c6", Coach.DAD))
        assertEquals("깜빡이 아직 켜져 있다. 꺼.", l("c6", Coach.SIB))
        assertEquals("어머, 깜깜해! 전조등 켜자!", l("j1", Coach.MOM))
        assertEquals("전조등이 꺼져 있어. 켜 볼까?", l("j1", Coach.DAD))
        assertEquals("1단계 통과 축하해. 골목도 지금처럼 천천히 가 보자.", l("rv.pass.1", Coach.DAD))
        assertEquals("2단계도 통과네. 큰길에선 제한속도 지켜.", l("rv.pass.2", Coach.SIB))
        assertEquals("어머 3단계도 통과야! 고속도로는 깜빡이 일찍!", l("rv.pass.3", Coach.MOM))
        assertEquals("4단계까지 다 마쳤어 우리 딸! 정말 고생 많았어.", l("rv.grad", Coach.DAD, h = Honorific(HonorificType.DAUGHTER)))
        assertEquals("4단계까지 다 마쳤어! 정말 고생 많았어.", l("rv.grad", Coach.DAD))
        assertEquals("세상에, 다 끝냈어 지은아! 너무 대견해!", l("rv.grad", Coach.MOM, h = Honorific(HonorificType.CUSTOM, "지은아")))
        assertEquals("다 했네. 이제 혼자서도 되겠다.", l("rv.grad", Coach.SIB, h = Honorific(HonorificType.SON)))
    }

    @Test
    fun registrationNeedsAndModes() {
        for (id in ids) {
            val d = det("safety3.$id")
            assertEquals(listOf(id), d.items)
            for (k in checkNotNull(Items.def(id)).needs) assertTrue("$id needs $k", d.needs.any { it.key == k })
            val modes = checkNotNull(d.modes)
            assertTrue("$id modes", 6 !in modes && listOf(1, 2, 3, 4, 5, 7, 8).all { it in modes })
        }
        assertEquals(emptyList<String>(), sess(SessionConfig(mode = 6)).info().detectors)
        assertEquals("깜빡이를 못 읽으면 C3·C6는 꺼짐", listOf("safety3.J1"), sess(SessionConfig(mode = 2, caps = SignalCaps(blinker = false))).info().detectors)
        assertEquals(listOf("safety3.C6", "safety3.J1"), sess(SessionConfig(mode = 2, caps = SignalCaps(nav = false))).info().detectors)
        for (m in listOf(1, 2, 3, 4, 5, 7, 8)) for (id in ids) assertEquals("$id 모드 $m", Delivery.NOW, BaroEngine.defaultFor(id, m))
    }

    @Test
    fun c3WrongBlinkerInTheWindowOncePerTurn() {
        val b = seq()
        b.add(10)
        approach(b, "t1", TurnDir.RIGHT) { d -> if ((d <= 90 && d > 70) || (d <= 60 && d > 50)) Blinker.LEFT else right(d <= 50) }
        b.add(30) { _, _, s -> s.copy(nav = nv()) }
        approach(b, "t2", TurnDir.LEFT) { d -> left(d <= 60) }
        b.add(30) { _, _, s -> s.copy(nav = nv()) }
        approach(b, "t3", TurnDir.LEFT) { d -> right(d <= 120) }
        b.add(30) { _, _, s -> s.copy(nav = nv()) }
        val out = drive(sess(SessionConfig(mode = 3), listOf("C3")), b.list)
        val c3 = out.events.of("C3")
        assertEquals(
            listOf(listOf<Any?>(EventResult.FAIL, "t1", TurnDir.RIGHT, TurnDir.LEFT, 90.0), listOf<Any?>(EventResult.FAIL, "t3", TurnDir.LEFT, TurnDir.RIGHT, 120.0)),
            c3.map { listOf(it.result, it.data.turnId, it.data.dir, it.data.blink, it.data.distance) },
        )
        val lines = out.say.filter { it.key == "c3" }
        assertEquals(listOf(Category.SAFETY to "오른쪽으로 가는 길이야. 깜빡이 바꿔 보자.", Category.SAFETY to "왼쪽으로 가는 길이야. 깜빡이 바꿔 보자."),
            lines.map { it.category to it.text })
        assertTrue("켜는 그 순간 말함", abs(lines[0].t - c3[0].t) < 1e-6)
    }

    @Test
    fun c3IgnoresBlinkersFromThePreviousTurnOrOutsideTheWindowAndUses300ForIc() {
        val b = seq()
        approach(b, "a1", TurnDir.RIGHT) { d -> right(d <= 50) }
        b.add(20) { _, _, s -> s.copy(blinker = Blinker.RIGHT, nav = nv()) }
        approach(b, "a2", TurnDir.LEFT) { Blinker.RIGHT }
        b.add(20) { _, _, s -> s.copy(nav = nv()) }
        approach(b, "a3", TurnDir.RIGHT, from = 250.0) { d -> left(d <= 200 && d > 160) }
        b.add(20) { _, _, s -> s.copy(nav = nv()) }
        approach(b, "ic", TurnDir.RIGHT, type = "TURN_EXIT_IC_R", ic = true, from = 320.0, step = 2.0, speed = 80.0) { d -> left(d <= 280) }
        b.add(20) { _, _, s -> s.copy(nav = nv()) }
        val out = drive(sess(SessionConfig(mode = 2), listOf("C3")), b.list)
        assertEquals(listOf("ic" to true), out.events.of("C3").map { it.data.turnId to it.data.ic })
    }

    @Test
    fun c3CandidateVanishesWhenCorrectedAndMissedTurnsAreExcluded() {
        val b = seq()
        approach(b, "v1", TurnDir.RIGHT, voice = true) { d -> if (d <= 100 && d > 90) Blinker.LEFT else right(d <= 90) }
        b.add(20) { _, _, s -> s.copy(nav = nv()) }
        val out = drive(sess(SessionConfig(mode = 3), listOf("C3")), b.list)
        assertEquals(1, out.events.of("C3").size)
        assertFalse(said(out, "c3"))
        assertTrue(out.blocked.toString(), out.blocked.any { it.utterance.key == "c3" && it.reason == BlockReason.NAV_VOICE })
        val s2 = sess(SessionConfig(mode = 3), listOf("C3"))
        val b2 = seq()
        approach(b2, "m1", TurnDir.RIGHT, from = 120.0) { d -> left(d <= 80) }
        b2.add(1) { _, _, s -> s.copy(blinker = Blinker.LEFT, nav = nv(events = listOf(NavEvent.Reroute("m1")))) }
        b2.add(20) { _, _, s -> s.copy(nav = nv()) }
        drive(s2, b2.list)
        val rec = s2.finish(arrived = false)
        assertEquals(listOf(Triple(EventResult.EXCLUDED, EventResult.FAIL, "reroute")),
            rec.events.filter { it.item == "C3" }.map { Triple(it.result, it.data.was, it.data.excluded) })
    }

    /** t3 `turnKeepOn`: 2초에 오른쪽 깜빡이, 4~7초 15° 조향(7.0초에 풀림), offAt초에 끔, stopFrom초부터 섬. */
    private fun turnKeepOn(offAt: Double?, end: Double, stopFrom: Double? = null): List<VehicleSnapshot> =
        seq { it.copy(speed = 20.0) }.until(end) { t, s ->
            s.copy(speed = if (stopFrom != null && t >= stopFrom - 1e-9) 0.0 else 20.0, steer = if (t >= 4 - 1e-9 && t < 7 - 1e-9) 15.0 else 0.0,
                blinker = right(t >= 2 - 1e-9 && (offAt == null || t < offAt - 1e-9)))
        }.list

    @Test
    fun c6AfterTheTurnMoreThanTenSeconds() {
        assertEquals("핸들이 풀린 7.0초부터 10.0초에 끔", 0, drive(sess(SessionConfig(mode = 2), listOf("C6")), turnKeepOn(17.0, 25.0)).events.of("C6").size)
        val on = drive(sess(SessionConfig(mode = 2), listOf("C6")), turnKeepOn(17.2, 25.0))
        val c6 = on.events.of("C6")
        assertEquals(1, c6.size)
        assertEquals(17.1, c6[0].t, 1e-9)
        assertEquals(listOf<Any?>(TurnDir.RIGHT, true, 10.1), listOf(c6[0].data.dir, c6[0].data.afterTurn, c6[0].data.sec))
        assertEquals(listOf(Triple(17.1, Category.SAFETY, "깜빡이가 아직 켜져 있어. 꺼 볼까?")), on.say.filter { it.key == "c6" }.map { Triple(it.t, it.category, it.text) })
        assertEquals("깜빡이 한 번 켠 동안 1번", 1, drive(sess(SessionConfig(mode = 2), listOf("C6")), turnKeepOn(null, 60.0)).events.of("C6").size)
        assertEquals(listOf(17.1), drive(sess(SessionConfig(mode = 2), listOf("C6")), turnKeepOn(null, 30.0, stopFrom = 9.0)).events.of("C6").map { it.t })
    }

    @Test
    fun c6WithoutATurnCountsOnlyStraightDriving() {
        fun mk(offAt: Double, extra: (Double, VehicleSnapshot) -> VehicleSnapshot = { _, s -> s }): Drive =
            drive(sess(SessionConfig(mode = 1), listOf("C6")), seq().until(25.0) { t, s -> extra(t, s.copy(blinker = left(t >= 1 - 1e-9 && t < offAt - 1e-9))) }.list)
        assertEquals(0, mk(11.0).events.of("C6").size)
        assertEquals(listOf(Triple(11.1, false, 10.1)), mk(11.2).events.of("C6").map { Triple(it.t, it.data.afterTurn, it.data.sec) })
        assertEquals(listOf(16.2), mk(30.0) { t, s -> s.copy(speed = if (t >= 4 - 1e-9 && t < 9 - 1e-9) 0.0 else 30.0) }.events.of("C6").map { it.t })
        assertEquals(listOf(15.1), mk(30.0) { t, s -> s.copy(steer = if (t >= 3 - 1e-9 && t < 5 - 1e-9) 4.0 else 0.0) }.events.of("C6").map { it.t })
        val b = seq { it.copy(speed = 0.0) }.until(40.0) { _, s -> s.copy(blinker = Blinker.RIGHT) }
        assertEquals(0, drive(sess(SessionConfig(mode = 1), listOf("C6")), b.list).events.of("C6").size)
    }

    @Test
    fun c6IsNotCountedInsideTheTurnWindow() {
        val b = seq { it.copy(speed = 15.0) }
        var d = 140.0
        while (d > 30) {
            val dd = d
            b.add(1) { _, _, s -> s.copy(blinker = Blinker.RIGHT, nav = nv(listOf(TbtItem(id = "p1", type = "TURN_RIGHT", distance = floor(dd / 10) * 10, dir = TurnDir.RIGHT)))) }
            d -= 0.4
        }
        b.add(200) { _, _, s -> s.copy(speed = 0.0, blinker = Blinker.RIGHT, nav = nv(listOf(TbtItem(id = "p1", type = "TURN_RIGHT", distance = 30.0, dir = TurnDir.RIGHT)))) }
        assertEquals("회전을 기다리는 깜빡이", 0, drive(sess(SessionConfig(mode = 2), listOf("C6")), b.list).events.of("C6").size)
        fun near(type: String, dist: Double, dir: TurnDir, speed: Double = 30.0, mode: Int = 2, ic: Boolean = false) =
            drive(sess(SessionConfig(mode = mode), listOf("C6")), seq { it.copy(speed = speed) }.until(14.0) { _, s ->
                s.copy(blinker = Blinker.RIGHT, nav = nv(listOf(TbtItem(id = "p", type = type, distance = dist, dir = dir, ic = ic))))
            }.list).events.of("C6").size
        assertEquals(0, near("TURN_LEFT", 120.0, TurnDir.LEFT))
        assertEquals("반대쪽 회전이 창 밖이면 센다", 1, near("TURN_LEFT", 400.0, TurnDir.LEFT))
        assertEquals(1, near("TURN_RIGHT", 200.0, TurnDir.RIGHT))
        assertEquals(0, near("TURN_EXIT_IC_R", 250.0, TurnDir.RIGHT, speed = 80.0, mode = 4, ic = true))
    }

    @Test
    fun c6AfterAGentleIcEntryWithoutSteering() {
        val b = seq { it.copy(speed = 60.0) }
        approach(b, "ic1", TurnDir.RIGHT, type = "TURN_ENTER_IC_R", ic = true, from = 300.0, step = 1.5, speed = 60.0) { d -> right(d <= 140) }
        val passedT = r1(b.t + 0.1)
        b.add(250) { _, _, s -> s.copy(speed = 60.0, blinker = Blinker.RIGHT, nav = nv(listOf(TbtItem(id = "ic2", type = "TURN_EXIT_IC_R", distance = 9000.0, dir = TurnDir.RIGHT, ic = true)))) }
        val c6 = drive(sess(SessionConfig(mode = 4), listOf("C6")), b.list).events.of("C6")
        assertEquals(1, c6.size)
        assertEquals("회전 지점을 지난 때부터", r1(passedT + 10.1), c6[0].t, 1e-9)
        assertEquals(true, c6[0].data.afterTurn)
    }

    @Test
    fun c6DebounceAndRefire() {
        fun run(gapFrom: Double, gapTo: Double, end: Double = 30.0) = drive(sess(SessionConfig(mode = 1), listOf("C6")),
            seq().until(end) { t, s -> s.copy(blinker = left(t >= 1 - 1e-9 && !(t >= gapFrom - 1e-9 && t < gapTo - 1e-9))) }.list).events.of("C6").map { it.t }
        assertEquals("0.5초 꺼짐: 이어짐", listOf(11.7), run(6.0, 6.5))
        assertEquals("1.5초 꺼짐: 7.5초부터 새로", listOf(17.6), run(6.0, 7.5))
        assertEquals("한 번 말한 뒤 끄고 다시 켜 둠 → 또 1번", listOf(11.1, 24.1), run(12.0, 14.0, 40.0))
    }

    @Test
    fun c6SpeaksInJamsAndNotInPark() {
        val out = drive(sess(SessionConfig(mode = 3), listOf("C6")), seq { it.copy(speed = 20.0) }.until(25.0) { t, s ->
            s.copy(steer = if (t >= 4 - 1e-9 && t < 7 - 1e-9) 15.0 else 0.0, blinker = right(t >= 2 - 1e-9), nav = nv(congested = true))
        }.list)
        assertEquals(listOf("c6" to 17.1), out.say.map { it.key to it.t })
        val p = seq { it.copy(gear = Gear.P, speed = 0.0) }.until(30.0) { _, s -> s.copy(blinker = Blinker.RIGHT) }
        assertEquals(0, drive(sess(SessionConfig(mode = 1), listOf("C6")), p.list).events.of("C6").size)
    }

    /** t3 `night`: 밤 · 하향등 켬 기본, dark(t)면 끔. */
    private fun night(end: Double, dark: (Double) -> Boolean, base: (VehicleSnapshot) -> VehicleSnapshot = { it }, extra: (Double, VehicleSnapshot) -> VehicleSnapshot = { _, s -> s }) =
        seq { base(it.copy(night = true, lowBeam = true)) }.until(end) { t, s -> extra(t, s.copy(lowBeam = !dark(t))) }.list

    @Test
    fun j1DarkForMoreThanFiveSeconds() {
        assertEquals("1.0~5.9초 꺼짐(5.0초 사이)", 0, drive(sess(SessionConfig(mode = 3), listOf("J1")), night(20.0, { it >= 1 - 1e-9 && it < 6.0 - 1e-9 })).events.of("J1").size)
        val out = drive(sess(SessionConfig(mode = 3), listOf("J1")), night(20.0, { it >= 1 - 1e-9 && it < 6.2 - 1e-9 }))
        assertEquals(listOf(listOf<Any?>(6.1, EventResult.FAIL, 5.1, 1)), out.events.of("J1").map { listOf(it.t, it.result, it.data.sec, it.data.n) })
        assertEquals(listOf(listOf<Any?>("j1", 6.1, Category.SAFETY, "전조등이 꺼져 있어. 켜 볼까?")), out.say.map { listOf(it.key, it.t, it.category, it.text) })
        assertEquals("낮", 0, drive(sess(only = listOf("J1")), night(20.0, { true }, base = { it.copy(night = false) })).events.of("J1").size)
        assertEquals("켬", 0, drive(sess(only = listOf("J1")), night(20.0, { false })).events.of("J1").size)
    }

    @Test
    fun j1CountsDrivingTimeOnlyAndAtMostTwice() {
        val stop = drive(sess(only = listOf("J1")), night(30.0, { it >= 1 - 1e-9 }, extra = { t, s -> s.copy(speed = if (t >= 2 - 1e-9 && t < 10 - 1e-9) 0.0 else 30.0) }))
        assertEquals(listOf(14.2), stop.events.of("J1").map { it.t })
        assertEquals("기어 P", 0, drive(sess(only = listOf("J1")), night(30.0, { true }, base = { it.copy(gear = Gear.P, speed = 0.0) })).events.of("J1").size)
        val three = drive(sess(only = listOf("J1")), night(50.0, { (it % 15) >= 1 - 1e-9 && (it % 15) < 9 - 1e-9 }))
        assertEquals(listOf(1, 2), three.events.of("J1").map { it.data.n })
        assertEquals(2, three.say.count { it.key == "j1" })
    }

    @Test
    fun switchOffRemovesTheThreeItemsAndTheirDetectors() {
        fun scene(): List<VehicleSnapshot> {
            val b = seq { it.copy(night = true, lowBeam = false) }
            approach(b, "w1", TurnDir.RIGHT) { d -> left(d <= 90) }
            b.until(40.0) { _, s -> s.copy(blinker = Blinker.LEFT, nav = nv()) }
            return b.list
        }
        val cfg = BaroEngine.buildConfig(2, null, flags = EngineFlags(extraSafety = false))
        for (id in ids) assertFalse(id, id in cfg.items)
        val s = CoachEngine().createSession(cfg)
        assertEquals(listOf("items", "items", "items"), s.info().off.filter { it.id.startsWith("safety3.") }.map { it.reason })
        val out = drive(s, scene())
        assertEquals(0, out.events.count { it.item in ids })
        assertEquals(0, (out.say.map { it.key } + out.blocked.map { it.utterance.key }).count { it in listOf("c3", "c6", "j1") })
        val on = drive(CoachEngine().createSession(BaroEngine.buildConfig(2, null)), scene())
        assertEquals(listOf(1, 1, 1), ids.map { id -> minOf(1, on.events.count { it.item == id }) })
    }
}
