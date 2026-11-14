package com.barodrive.core.engine.score

import com.barodrive.core.engine.api.EngineScore
import com.barodrive.core.engine.api.G1Input
import com.barodrive.core.engine.api.ScoreCounts
import com.barodrive.core.engine.content.Items
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.PreviousScore
import com.barodrive.core.model.Profile
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.ScoreResult
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionHistory
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.SignalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자율 모드 안전운전 점수 (프로토타입 tests/unit/t4_score.test.js의 점수 계산·RV-4 글·점수 대사, t4_autonomous.test.js의 점수 부분).
 * S5-M 시나리오를 끝까지 돌리는 시험은 엔진 코어·시뮬레이터 몫이고, 같은 회차는 골든(ReviewGoldenTest)으로 맞춘다.
 */
class ScoreTest {
    private var seq = 0
    private fun ev(item: String, result: EventResult, data: EventData = EventData.EMPTY, pre: Boolean = false, t: Double? = null) =
        JudgeEvent(t = t ?: (++seq).toDouble(), item = item, result = result, data = data, pre = pre)

    private val ok = EventResult.OK
    private val fail = EventResult.FAIL

    /** 목업 s07 회차: 늦음 1 · 거의 2 · 켜자마자 꺾음 1 · 급출발 1 · 핸들 0.96°(기준 0.8°) · 위험 회피 급제동 1 + 감점 없는 판정들. */
    private fun mockupRecord(caps: SignalCaps = SignalCaps(), items: Map<String, Delivery> = emptyMap(), extra: List<JudgeEvent> = emptyList()) =
        SessionRecord(
            mode = 5, caps = caps, items = items,
            events = listOf(
                ev("C2", ok, EventData(turnId = "t1", distance = 50.0)), ev("C4", ok, EventData(turnId = "t1", interval = 7.0)),
                ev("C5", ok, EventData(turnId = "t1")),
                ev("C2", EventResult.LATE, EventData(turnId = "t2", distance = 20.0)), ev("C4", ok, EventData(turnId = "t2")),
                ev("C5", ok, EventData(turnId = "t2")),
                ev("C2", EventResult.ALMOST, EventData(turnId = "t3", distance = 30.0)), ev("C5", ok, EventData(turnId = "t3")),
                ev("C2", ok, EventData(turnId = "t4", distance = 40.0)), ev("C4", fail, EventData(turnId = "t4", interval = 1.1)),
                ev("C5", ok, EventData(turnId = "t4")),
                ev("C2", EventResult.ALMOST, EventData(turnId = "t5", distance = 30.0)), ev("C5", ok, EventData(turnId = "t5")),
                ev("B1", ok), ev("B1", fail, EventData(maxGain1s = 13.0)), ev("B1", EventResult.EXCLUDED, EventData(reason = "F2")),
                ev("F1", ok, EventData(maxDrop1s = 5.0)), ev("F1", ok, EventData(maxDrop1s = 6.0)),
                ev("E1", ok, EventData(zoneId = "z1", limit = 50.0)), ev("E1", ok, EventData(zoneId = "z2", limit = 60.0)),
                ev("C1", EventResult.INFO, EventData(turnId = "t2")), ev("PB2", fail),
                ev("G1", EventResult.INFO, EventData(value = 0.96, windows = 83, base = 0.8, baseFirst = false, insufficient = false)),
            ) + extra,
            f2 = listOf(F2Check(id = "f2-1", v0 = 56.0, maxDrop1s = 13.0)),
            metrics = SessionMetrics(g1 = 0.96),
        )

    private fun compute(r: SessionRecord, baseline: Double? = null): ScoreResult =
        EngineScore.compute(r, SessionConfig(mode = 5, history = SessionHistory(baselineG1 = baseline)))

    private fun record(events: List<JudgeEvent>, caps: SignalCaps = SignalCaps(), metrics: SessionMetrics = SessionMetrics()) =
        SessionRecord(mode = 5, caps = caps, events = events, metrics = metrics)

    private fun row(sc: ScoreResult, id: String) = sc.rows.first { it.id == id }

    private fun render(key: String, coach: Coach, vars: Map<String, Any?>, hon: Honorific? = null) =
        Lines.render(key, coach, vars, hon, peek = true)

    // ================================================================ 점수 계산

    @Test
    fun mockupS07Is86() {
        val sc = compute(mockupRecord())
        assertEquals(86, sc.total)
        assertEquals(14, sc.lost)
        assertEquals(
            listOf(listOf<Any?>("C2.late", 1, 3), listOf("C2.almost", 2, 2), listOf("C4", 1, 2), listOf("BF", 1, 3), listOf("G1", null, 4)),
            sc.deductions.map { listOf(it.id, it.count, it.points) },
        )
        assertEquals("설계 6.11 표 순서 7줄", listOf("C2.late", "C2.almost", "C4", "C5", "BF", "E1", "G1"), sc.rows.map { it.id })
        assertEquals(emptyList<Any>(), sc.excluded)
        assertEquals(1, sc.f2.count)
        assertEquals(0.96, sc.g1?.value)
        assertEquals(0.8, sc.g1?.base)
        assertEquals(1.2, sc.g1?.ratio)
        assertEquals("가장 많이 깎인 곳 = 깜빡이 타이밍(늦음 3 + 거의 2)", "C2", sc.top)
        assertEquals("14점 깎였어요", EngineScore.lostText(sc))
        assertEquals("기록에 그대로 저장할 수 있는 값", sc, BaroJson.decodeFromString(ScoreResult.serializer(), BaroJson.encodeToString(ScoreResult.serializer(), sc)))
    }

    @Test
    fun rv4TableGroupsZeroRowsAtTheEnd() {
        val sc = compute(mockupRecord())
        assertEquals(
            listOf(
                listOf("회전 전 깜빡이 '늦음'", "1번", "−3"), listOf("회전 전 깜빡이 '거의'", "2번", "−2"), listOf("깜빡이 켜자마자 꺾음", "1번", "−2"),
                listOf("급출발·급정지", "1번", "−3"), listOf("직진할 때 핸들 흔들림", "기준보다 큼", "−4"), listOf("제한속도 초과 · 깜빡이 없이 회전", "0번", "0"),
            ),
            EngineScore.table(sc).map { listOf(it.label, it.count, it.points) },
        )
        assertEquals(
            listOf("위험을 피한 급제동 1번은 감점하지 않았어요.", "늦음 = 회전 30m 안에서 켬 · 거의 = 30~39m"),
            EngineScore.footnotes(sc).map { it.text },
        )
        assertNull(EngineScore.excludedText(sc))
        // 100점: 깎인 줄 없음, 0번 줄 하나(늦음·거의는 한 이름으로)
        val perfect = compute(record(listOf(ev("G1", EventResult.INFO, EventData(value = 0.6, base = 0.8)))))
        assertEquals(100, perfect.total)
        assertEquals("한 점도 안 깎였어요", EngineScore.lostText(perfect))
        val t = EngineScore.table(perfect)
        assertEquals(listOf(listOf("기준 이하", "0"), listOf("0번", "0")), t.map { listOf(it.count, it.points) })
        assertEquals("제한속도 초과 · 깜빡이 없이 회전 · 급출발·급정지 · 깜빡이 켜자마자 꺾음 · 회전 전 깜빡이 '늦음'·'거의'", t[1].label)
        assertEquals(listOf("E1", "C5", "BF", "C4", "C2.late", "C2.almost"), t[1].ids)
        assertEquals(listOf("늦음 = 회전 30m 안에서 켬 · 거의 = 30~39m"), EngineScore.footnotes(perfect).map { it.text })
    }

    @Test
    fun scoreNeverGoesBelowZero() {
        val events = List(12) { ev("C5", fail, EventData(turnId = "x$it")) } + List(10) { ev("F1", fail) } +
            ev("G1", EventResult.INFO, EventData(value = 3.0, base = 0.8))
        val sc = compute(record(events))
        assertEquals(60 + 30 + 10, sc.sum)
        assertEquals(0, sc.total)
        assertEquals(100, sc.lost)
        assertEquals("rv.score.lo", EngineScore.line(sc, "마트").key)
        assertEquals("C5 12번(60) + F1 8번(24) = 84", 16, compute(record(events.subList(0, 20))).total)
    }

    @Test
    fun offItemsStillCount() {
        val allX = Items.ids().associateWith { Delivery.OFF }
        val a = compute(mockupRecord(items = allX))
        val b = compute(mockupRecord(items = mapOf("C2" to Delivery.NOW, "C4" to Delivery.AFTER, "B1" to Delivery.NOW)))
        assertEquals(86, a.total)
        assertEquals("전달 방식은 점수와 상관없음(결정-19)", a, b)
    }

    @Test
    fun unreadableSpeedDropsStartStopAndSpeedRows() {
        val caps = SignalCaps(speed = false)
        val sc = compute(mockupRecord(caps = caps, extra = listOf(ev("E1", fail, EventData(zoneId = "z9")))))
        assertEquals(listOf("BF" to listOf(SignalKey.SPEED), "E1" to listOf(SignalKey.SPEED)), sc.excluded.map { it.id to it.signals })
        assertEquals(listOf("B1", "E1", "E2", "F1"), sc.excludedItems.sorted())
        assertEquals(listOf(SignalKey.SPEED), sc.excludedSignals)
        assertEquals("급출발 3점과 제한속도는 빠짐", 100 - 3 - 2 - 2 - 4, sc.total)
        assertTrue(row(sc, "BF").excluded && row(sc, "BF").points == 0)
        assertEquals("차속을 못 읽어 급출발·급정지·제한속도는 점수에서 뺐어요", EngineScore.excludedText(sc))
        assertFalse("뺀 줄은 감점 표에 없음", EngineScore.table(sc).any { "급출발" in it.label || "제한속도" in it.label })
        assertEquals("못 읽음", EngineScore.countText(row(sc, "BF"), sc))
    }

    @Test
    fun unreadableSteerAndBlinkerAndMore() {
        val sc = compute(record(listOf(ev("C2", fail, EventData(turnId = "t1")), ev("C2", EventResult.LATE, EventData(turnId = "t2"))), caps = SignalCaps(steer = false)))
        assertEquals(listOf("C4", "G1"), sc.excluded.map { it.id })
        assertEquals(1, row(sc, "C5").count)
        assertEquals(100 - 5 - 3, sc.total)
        assertEquals("핸들을 못 읽어 켜자마자 꺾음·핸들 흔들림은 점수에서 뺐어요", EngineScore.excludedText(sc))
        assertNull("G1 줄을 빼면 G1 값도 없음", sc.g1)
        // 깜빡이를 못 읽으면 깜빡이 줄 셋이 다 빠진다(이름은 한 번씩)
        val sc2 = compute(record(emptyList(), caps = SignalCaps(blinker = false)))
        assertEquals(listOf("C2.late", "C2.almost", "C4", "C5"), sc2.excluded.map { it.id })
        assertEquals("깜빡이를 못 읽어 깜빡이 타이밍·켜자마자 꺾음·깜빡이 없이 회전은 점수에서 뺐어요", EngineScore.excludedText(sc2))
        // 둘 이상
        val sc3 = compute(record(emptyList(), caps = SignalCaps(speed = false, nav = false)))
        assertEquals("내비·차속을 못 읽어 깜빡이 타이밍·급출발·급정지·제한속도는 점수에서 뺐어요", EngineScore.excludedText(sc3))
        assertFalse("깜빡이 타이밍 줄이 빠지면 늦음·거의 설명도 뺌", EngineScore.footnotes(sc3).any { "늦음 =" in it.text })
    }

    @Test
    fun g1Formula() {
        fun g(value: Double?, base: Double, baseFirst: Boolean? = null, insufficient: Boolean? = null, windows: Int? = null) =
            compute(record(listOf(ev("G1", EventResult.INFO, EventData(value = value, base = base, baseFirst = baseFirst, insufficient = insufficient, windows = windows)))))
        assertEquals(4, row(g(0.96, 0.8), "G1").points)
        assertEquals("0.175 × 20 = 3.5 → 4(반올림)", 4, row(g(0.94, 0.8), "G1").points)
        assertEquals(5, row(g(0.98, 0.8), "G1").points)
        assertEquals(10, row(g(1.2, 0.8), "G1").points)
        assertEquals("최대 10점", 10, row(g(2.5, 0.8), "G1").points)
        assertEquals("기준과 같으면 0", 0, row(g(0.8, 0.8), "G1").points)
        assertEquals("기준보다 작으면 0", 0, row(g(0.6, 0.8), "G1").points)
        assertEquals("조금 크면 0(반올림)", 0, row(g(0.81, 0.8), "G1").points)
        assertEquals("기준과 비슷", EngineScore.table(g(0.81, 0.8))[0].count)
        assertEquals("기준 이하", EngineScore.table(g(0.6, 0.8))[0].count)
        assertEquals("첫 회차 기준 1.1°", 4, row(g(1.32, 1.1, baseFirst = true), "G1").points)
        val ins = g(null, 0.8, insufficient = true, windows = 2)
        assertEquals(0, row(ins, "G1").points)
        assertEquals(true, ins.g1?.insufficient)
        assertEquals("측정 부족", EngineScore.table(ins)[0].count)
        // G1 판정이 없으면 회차 지표와 설정 기준(프로필을 넘겨도 됨)
        assertEquals(96, compute(record(emptyList(), metrics = SessionMetrics(g1 = 1.32)), baseline = 1.1).total)
        assertEquals(96, EngineScore.compute(record(emptyList(), metrics = SessionMetrics(g1 = 1.32)), Profile(g1Baseline = 1.1)).total)
        assertEquals("기준이 없으면 0.8°", 96, compute(record(emptyList(), metrics = SessionMetrics(g1 = 0.96))).total)
        assertEquals(4, EngineScore.g1Points(0.96, 0.8))
        assertEquals(0, EngineScore.g1Points(null, 0.8))
        assertEquals(0, EngineScore.g1Points(1.0, 0.0))
    }

    @Test
    fun c2NoSignalDoesNotDoubleCountWithC5() {
        fun c(events: List<JudgeEvent>) = row(compute(record(events)), "C5").count
        assertEquals("같은 회전 → 한 번(5점)", 1, c(listOf(ev("C2", fail, EventData(turnId = "t1")), ev("C5", fail, EventData(turnId = "t1")))))
        assertEquals("C5가 깜빡이를 봤으면 안 깎음", 0, c(listOf(ev("C2", fail, EventData(turnId = "t1")), ev("C5", ok, EventData(turnId = "t1")))))
        assertEquals("C5가 못 본 회전(IC 등)", 1, c(listOf(ev("C2", fail, EventData(turnId = "t1")))))
        assertEquals(2, c(listOf(ev("C2", fail, EventData(turnId = "t1")), ev("C5", fail, EventData(turnId = "t2")))))
        assertEquals("주차장 같은 내비 없는 회전도 C5로", 2, c(listOf(ev("C5", fail), ev("C5", fail))))
    }

    @Test
    fun speedingCountsOncePerZoneAndSkipsExcludedAndPre() {
        val sc = compute(
            record(
                listOf(
                    ev("E2", fail, EventData(zoneId = "z1")), ev("E1", fail, EventData(zoneId = "z1")), // 같은 어린이보호구역 → 1번
                    ev("E2", fail, EventData(zoneId = "z2")), // E2만 난 구역도 1번
                    ev("E1", fail, EventData(zoneId = "z3")), ev("E1", ok, EventData(zoneId = "z4")),
                    ev("C2", EventResult.EXCLUDED, EventData(turnId = "t9", was = EventResult.LATE, excluded = "reroute"), t = 50.0),
                    ev("C4", fail, pre = true, t = 0.0),
                ),
            ),
        )
        assertEquals(3, row(sc, "E1").count)
        assertEquals(0, row(sc, "C2.late").count)
        assertEquals(0, row(sc, "C4").count)
        assertEquals(100 - 15, sc.total)
        assertEquals("E1", sc.top)
        assertEquals("구역 id가 없으면 판정마다 한 번", 2, row(compute(record(listOf(ev("E1", fail), ev("E2", fail)))), "E1").count)
    }

    @Test
    fun fromCountsMatchesTheRealRecord() {
        val sc = EngineScore.fromCounts(ScoreCounts(late = 1, almost = 2, c4 = 1, bf = 1, g1Points = 4, f2 = 1))
        val real = compute(mockupRecord())
        assertEquals(86, sc.total)
        assertEquals(real.deductions.map { listOf(it.id, it.count, it.points) }, sc.deductions.map { listOf(it.id, it.count, it.points) })
        assertEquals(EngineScore.table(real), EngineScore.table(sc))
        assertEquals(84, EngineScore.fromCounts(ScoreCounts(late = 2, c5 = 1, e1 = 1, g1 = G1Input(value = 0.7, base = 0.8))).total)
        val off = EngineScore.fromCounts(ScoreCounts(bf = 2, e1 = 1, caps = SignalCaps(speed = false)))
        assertEquals(100, off.total)
        assertEquals("차속을 못 읽어 급출발·급정지·제한속도는 점수에서 뺐어요", EngineScore.excludedText(off))
        val listed = EngineScore.fromCounts(ScoreCounts(bf = 2, off = listOf("B1", "F1")))
        assertEquals("못 읽는 신호를 모르면 '못 읽는 신호가 있어'", "못 읽는 신호가 있어 급출발·급정지는 점수에서 뺐어요", EngineScore.excludedText(listed))
        assertEquals("데모: 늦음 6번 = 82점(t4_autonomous 지난 점수)", 82, EngineScore.fromCounts(ScoreCounts(late = 6)).total)
        assertEquals(0, EngineScore.fromCounts(ScoreCounts(c5 = 30)).total)
    }

    @Test
    fun previousScoreIsTheOlderAutonomousOne() {
        val recs = listOf(
            SessionRecord(id = "r5", mode = 5, score = ScoreResult(total = 86)), SessionRecord(id = "r4", mode = 2),
            SessionRecord(id = "r3", mode = 5), SessionRecord(id = "r2", mode = 5, score = ScoreResult(total = 82)),
            SessionRecord(id = "r1", mode = 5, score = ScoreResult(total = 78)),
        )
        assertEquals(PreviousScore("r2", 82), EngineScore.previous(recs, "r5"))
        assertEquals("recId가 없으면 맨 앞이 오늘", PreviousScore("r2", 82), EngineScore.previous(recs, null))
        assertEquals(PreviousScore("r1", 78), EngineScore.previous(recs, "r2"))
        assertNull("첫 자율 회차", EngineScore.previous(recs, "r1"))
        assertNull(EngineScore.previous(recs, "nope"))
        assertNull(EngineScore.previous(emptyList(), "r5"))
        val virtual = listOf(recs[0], SessionRecord(id = "v", mode = 5, kind = RecordKind.VIRTUAL_PARKING, score = ScoreResult(total = 10))) + recs.drop(1)
        assertEquals("세워 두고 연습은 건너뜀", PreviousScore("r2", 82), EngineScore.previous(virtual, "r5"))
    }

    // ================================================================ 점수 대사

    @Test
    fun scoreLinesRenderForEveryFixAndCoach() {
        val fixes = listOf("C2", "C4", "C5", "BF", "E1", "G1")
        for (k in fixes) for (c in Coach.entries) {
            val l = Lines.sentences("rv.score.fix.$k", c)
            assertTrue("$k $c", l.size == 1 && l[0].isNotBlank())
        }
        for (key in listOf("rv.score.hi", "rv.score.mid", "rv.score.lo")) for (c in Coach.entries) for (fix in fixes) {
            val sc = EngineScore.fromCounts(ScoreCounts(c5 = 1)).copy(top = fix)
            val vars = ScoreText.vars(EngineScore.line(sc, "마트"))
            for (hon in listOf(Honorific(), Honorific(HonorificType.DAUGHTER))) {
                val t = render(key, c, vars, hon)
                assertTrue("$key $c $fix: $t", t.length > 4 && !t.contains('{') && !t.contains('}'))
            }
        }
    }

    @Test
    fun scoreLineBandsAndTopFix() {
        val sc = compute(mockupRecord())
        val l = EngineScore.line(sc, "마트")
        assertEquals("rv.score.mid", l.key)
        assertEquals("C2", l.fix)
        assertEquals(
            mapOf("S" to 86, "dest" to "마트", "fixSib" to "깜빡이나 일찍 켜", "fixMom" to "깜빡이만 조금 일찍 켜자", "fixDad" to "깜빡이만 조금 일찍 켜 보자"),
            ScoreText.vars(l),
        )
        val v = ScoreText.vars(l)
        assertEquals("마트까지 잘 왔어. 깜빡이만 조금 일찍 켜 보자.", render(l.key, Coach.DAD, v))
        assertEquals("어머 86점이야! 깜빡이만 조금 일찍 켜자, 응?", render(l.key, Coach.MOM, v))
        assertEquals("86점이면 봐줄 만하네. 깜빡이나 일찍 켜.", render(l.key, Coach.SIB, v))
        assertEquals("90점 = hi", "rv.score.hi", EngineScore.line(EngineScore.fromCounts(ScoreCounts(almost = 10))).key)
        assertEquals("89점 = mid", "rv.score.mid", EngineScore.line(EngineScore.fromCounts(ScoreCounts(almost = 11))).key)
        assertEquals("70점 = mid", "rv.score.mid", EngineScore.line(EngineScore.fromCounts(ScoreCounts(almost = 30))).key)
        val lo = EngineScore.line(EngineScore.fromCounts(ScoreCounts(almost = 31)), "회사")
        assertEquals("69점 = lo", "rv.score.lo", lo.key)
        assertEquals("오늘은 69점이야. 다음엔 깜빡이만 조금 일찍 켜 보자.", render(lo.key, Coach.DAD, ScoreText.vars(lo)))
        val hi = EngineScore.line(EngineScore.fromCounts(ScoreCounts()), "마트")
        assertEquals("100점이야. 마트까지 아주 안전하게 왔어.", render(hi.key, Coach.DAD, ScoreText.vars(hi)))
        assertEquals("어머 100점! 너무 잘했어 우리 딸!", render(hi.key, Coach.MOM, ScoreText.vars(hi), Honorific(HonorificType.DAUGHTER)))
        assertEquals("목적지 이름이 없을 때", "목적지", ScoreText.vars(EngineScore.line(EngineScore.fromCounts(ScoreCounts()))).getValue("dest"))
        val noDest = EngineScore.line(sc)
        assertTrue("목적지 이름이 없으면 '목적지'", render(noDest.key, Coach.DAD, ScoreText.vars(noDest)).startsWith("목적지까지"))
        // 같은 점수로 깎이면 안전에 가까운 곳 먼저: 제한속도(5) = 깜빡이 없이 회전(5) = 늦음+거의(5)
        assertEquals("E1", EngineScore.fromCounts(ScoreCounts(e1 = 1, c5 = 1, late = 1, almost = 2)).top)
        assertEquals("C5", EngineScore.fromCounts(ScoreCounts(c5 = 1, late = 1, almost = 2)).top)
        assertEquals("BF", EngineScore.fromCounts(ScoreCounts(bf = 2, g1Points = 6)).top)
        assertEquals("G1", EngineScore.fromCounts(ScoreCounts(bf = 1, g1Points = 4)).top)
    }

    @Test
    fun recordWithoutScoreIsComputedFromEvents() {
        // t4_autonomous '점수가 없는 기록(요약만)은 그 자리에서 판정으로 계산하고, 못 읽은 신호 줄을 낸다'
        val r = SessionRecord(
            mode = 5, caps = SignalCaps(speed = false),
            events = listOf(ev("C2", EventResult.LATE, EventData(turnId = "t1"), t = 1.0), ev("G1", EventResult.INFO, EventData(value = 0.8, base = 0.8), t = 2.0)),
        )
        val sc = EngineScore.compute(r, Profile())
        assertEquals(97, sc.total)
        assertEquals("차속을 못 읽어 급출발·급정지·제한속도는 점수에서 뺐어요", EngineScore.excludedText(sc))
        assertFalse(EngineScore.table(sc).any { "급출발" in it.label || "제한속도" in it.label })
    }
}
