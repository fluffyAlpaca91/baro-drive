package com.barodrive.core.engine.review

import com.barodrive.core.engine.api.EngineReview
import com.barodrive.core.engine.review.ReviewTestKit.ALMOST
import com.barodrive.core.engine.review.ReviewTestKit.EXCLUDED
import com.barodrive.core.engine.review.ReviewTestKit.FAIL
import com.barodrive.core.engine.review.ReviewTestKit.INFO
import com.barodrive.core.engine.review.ReviewTestKit.OK
import com.barodrive.core.engine.review.ReviewTestKit.ev
import com.barodrive.core.engine.review.ReviewTestKit.itemsOf
import com.barodrive.core.engine.review.ReviewTestKit.many
import com.barodrive.core.engine.review.ReviewTestKit.profile
import com.barodrive.core.engine.review.ReviewTestKit.rec
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.FirstMetrics
import com.barodrive.core.model.HillResult
import com.barodrive.core.model.HillTaskCount
import com.barodrive.core.model.NextTask
import com.barodrive.core.model.NextTaskReason
import com.barodrive.core.model.ReviewSummary
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionHistory
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.TaskResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 리뷰 요약 (프로토타입 tests/unit/t2a_review.test.js의 summarize·k4·다음 과제·잘한 것/아쉬운 것·요약만 있는 회차·언덕 과제).
 * 진행 반영·오늘 과제는 [ProgressTest].
 */
class ReviewSummaryTest {
    private fun texts(list: List<com.barodrive.core.model.ReviewLine>) = list.map { it.text }

    private fun cfg(mode: Int, history: SessionHistory = SessionHistory()) = SessionConfig(mode = mode, history = history)

    private fun summarize(r: SessionRecord, mode: Int = r.mode, history: SessionHistory = SessionHistory()) =
        EngineReview.summarize(r, cfg(mode, history))

    private fun rows(s: ReviewSummary) = s.tasks.map { listOf(it.id, it.success, it.total) }

    // ================================================================ 목업·설계 예 재현

    @Test
    fun mockupS05Stage1SecondSession() {
        val r = rec(
            1,
            many(5, "C5", OK, EventData(before = true)) + many(1, "C5", FAIL) +
                many(7, "F1", OK, EventData(stopped = true)) + many(1, "F1", FAIL) +
                many(4, "B1", OK) + many(2, "B1", FAIL) +
                many(4, "C4", OK) + many(1, "C4", FAIL),
            metrics = SessionMetrics(g1 = 0.77, c2Avg = 52.0, stopFail = 1, stopTotal = 8),
        )
        val s = summarize(
            r,
            history = SessionHistory(
                baselineG1 = 1.1,
                firstSessionMetrics = FirstMetrics(c2Avg = 38.0, stopFail = 3, stopTotal = 8, stopFailRate = 0.375, g1 = 1.1),
            ),
        )
        assertEquals(listOf(listOf("T-START", 4, 6), listOf("T-STOP", 7, 8), listOf("T-SIGNAL1", 5, 6)), rows(s))
        assertEquals("16/20", 0.8, s.achievement)
        assertTrue("80% 이상이면 통과 회차(결정-24·36 — 급출발 2번이 있어도)", s.qualified)
        assertFalse(s.fewEvents)
        assertEquals(
            listOf("브레이크 부드럽게 밟아서 멈추기 7/8번", "핸들 돌리기 전에 깜빡이 켜기 5/6번", "직진할 때 핸들 첫날보다 30% 안정"),
            texts(s.good),
        )
        assertEquals(
            "잘한 것에 든 과제의 실패(깜빡이 1번·정지 1번)는 다시 적지 않는다",
            listOf("출발할 때 엑셀을 조금 세게 2번", "깜빡이 켜고 바로 꺾음 1번"), texts(s.bad),
        )
        assertEquals(NextTask("T-START", "엑셀 살살 밟아서 출발하기", "출발", NextTaskReason.LOWEST), s.nextTask)
        val k4 = s.metrics.k4
        assertEquals(false, k4?.first)
        assertEquals(
            listOf(listOf("c2Avg", "38m → 52m", true), listOf("stop", "3/8 → 1/8", true), listOf("g1", "첫날보다 30% 안정", true)),
            k4?.rows?.map { listOf(it.id, it.text, it.better) },
        )
        val json = BaroJson.encodeToString(ReviewSummary.serializer(), s)
        assertEquals("저장할 수 있는 값만", s, BaroJson.decodeFromString(ReviewSummary.serializer(), json))
    }

    @Test
    fun design53Stage2SafetyFirst() {
        val r = rec(
            2,
            many(5, "C2", OK, EventData(turnId = "t")) + many(1, "C2", ALMOST, EventData(turnId = "tx")) +
                many(7, "F1", OK) + many(1, "F1", FAIL) +
                many(3, "C4", OK) + many(1, "C4", FAIL) +
                listOf(
                    ev("E1", OK, EventData(zoneId = "z1", limit = 30.0)), ev("E1", FAIL, EventData(zoneId = "z2", limit = 30.0)),
                    ev("E1", OK, EventData(zoneId = "z3", limit = 30.0)), ev("E1", OK, EventData(zoneId = "z4", limit = 50.0)),
                ),
            metrics = SessionMetrics(g1 = 0.77),
        )
        val s = summarize(r, history = SessionHistory(baselineG1 = 1.1))
        assertEquals(
            listOf(
                listOf("T-30", "시속 30 넘지 않기", 2, 3), listOf("T-SIGNAL30", "꺾기 30m 전에 깜빡이 켜기", 5, 6),
                listOf("T-WAIT3", "깜빡이 켜자마자 꺾지 않기", 3, 4), listOf("T-STOP", "멈출 때 부드럽게 멈추기", 7, 8),
            ),
            s.tasks.map { listOf(it.id, it.label, it.success, it.total) },
        )
        assertEquals(
            listOf("멈출 때 부드럽게 멈추기 7/8번", "꺾기 30m 전에 깜빡이 켜기 5/6번", "직진할 때 핸들 첫날보다 30% 안정"),
            texts(s.good),
        )
        assertEquals(listOf("30 구역 초과 1번", "깜빡이 켜고 바로 꺾음 1번"), texts(s.bad))
        assertEquals("깜빡이 5/6이어도 안전 위반이 먼저(결정-39)", NextTask("T-30", "30 구역 지키기", "30 구역", NextTaskReason.SAFETY), s.nextTask)
        assertEquals("17/21", 0.81, s.achievement)
        assertEquals("첫 회차 기준이 없으면 '첫 기록이에요'", true, s.metrics.k4?.first)
    }

    // ================================================================ 달성률·기록 적은 회차

    @Test
    fun achievementFewEventsAndJudgedOnly() {
        val few = summarize(rec(1, listOf(ev("B1", OK), ev("F1", OK), ev("C5", OK, EventData(before = true)))))
        assertEquals(listOf<Any?>(1.0, true, false), listOf(few.achievement, few.fewEvents, few.qualified))
        val four = summarize(rec(1, listOf(ev("B1", OK), ev("F1", OK), ev("F1", OK), ev("C5", OK, EventData(before = true)))))
        assertEquals(listOf<Any?>(1.0, false, true), listOf(four.achievement, four.fewEvents, four.qualified))
        val low = summarize(rec(1, many(4, "B1", OK) + many(2, "B1", FAIL)))
        assertEquals(listOf<Any?>(0.667, false), listOf(low.achievement, low.qualified))
        assertEquals(listOf(6, 0, 0), low.tasks.map { it.total })
        val none = summarize(rec(5, many(3, "B1", OK)))
        assertEquals("자율은 과제 없음", listOf<Any?>(emptyList<TaskResult>(), null, true, false, null),
            listOf(none.tasks, none.achievement, none.fewEvents, none.qualified, none.nextTask))
        // 판정이 아닌 기록(info·excluded·정차 중 판정)은 세지 않는다
        val ex = summarize(
            rec(
                1,
                listOf(
                    ev("B1", EXCLUDED), ev("B1", OK), ev("C5", OK, EventData(before = false)), ev("C5", FAIL),
                    ev("L1", INFO, pre = true, t = 0.0),
                ),
            ),
        )
        assertEquals(
            "T-SIGNAL1 성공 = 조향 시작 때 맞는 쪽 깜빡이(before)",
            listOf(listOf("T-START", 1, 1), listOf("T-STOP", 0, 0), listOf("T-SIGNAL1", 0, 2)), rows(ex),
        )
        // X 항목 판정도 달성률에 든다(설계 6.10.4)
        val x = summarize(rec(2, many(4, "C4", FAIL), items = itemsOf(2, mapOf("C4" to Delivery.OFF))))
        assertEquals(0.0, x.achievement)
        assertEquals("X 항목은 아쉬운 것에 넣지 않는다", emptyList<String>(), texts(x.bad))
    }

    // ================================================================ 다음 과제 하나

    @Test
    fun nextTaskPriority() {
        fun nt(mode: Int, evs: List<com.barodrive.core.model.JudgeEvent>) = summarize(rec(mode, evs)).nextTask
        val base = many(5, "B1", OK) + many(5, "F1", OK)
        assertEquals(
            "어린이보호구역 30 지키기",
            nt(1, base + listOf(ev("E2", FAIL, EventData(zoneId = "z9")), ev("E1", FAIL, EventData(limit = 30.0))))?.label,
        )
        assertEquals("어린이보호구역의 E1", "T-SCHOOL", nt(1, base + ev("E1", FAIL, EventData(limit = 30.0, school = true)))?.id)
        assertEquals(
            NextTask("T-30", "30 구역 지키기", "30 구역", NextTaskReason.SAFETY),
            nt(1, base + listOf(ev("E1", FAIL, EventData(limit = 50.0)), ev("E1", FAIL, EventData(limit = 30.0)))),
        )
        assertEquals(
            NextTask("T-LIMIT", "제한속도 지키기", "제한속도", NextTaskReason.SAFETY),
            nt(3, base + ev("E1", FAIL, EventData(limit = 60.0))),
        )
        // 같은 성공률이면 모드 과제 순서
        assertEquals("T-START", nt(1, many(3, "B1", OK) + ev("B1", FAIL) + many(3, "F1", OK) + ev("F1", FAIL))?.id)
        assertEquals("T-STOP", nt(1, many(3, "B1", OK) + many(1, "F1", FAIL) + many(1, "F1", OK))?.id)
        // 모두 100%면 판정이 가장 적은 과제(더 연습할 것)
        val all = nt(1, many(6, "B1", OK) + many(9, "F1", OK) + many(2, "C5", OK, EventData(before = true)))
        assertEquals(listOf<Any?>("T-SIGNAL1", NextTaskReason.LEAST), listOf(all?.id, all?.reason))
        // 판정이 하나도 없으면 첫 과제
        assertEquals(NextTask("T-30", "시속 30 넘지 않기", "30 구역", NextTaskReason.FIRST), nt(2, emptyList()))
    }

    // ================================================================ 잘한 것·아쉬운 것 세부

    @Test
    fun goodAndBadLines() {
        // 3단계: B1·C4는 완료 후(S) — 과제가 아니어도 아쉬운 것에 들어감
        val s3 = summarize(
            rec(
                3,
                many(2, "B1", FAIL) + many(3, "B1", OK) + ev("A1", FAIL) + many(1, "C4", FAIL) + many(4, "C2", OK) + many(5, "F1", OK) +
                    listOf(ev("E1", OK, EventData(limit = 50.0)), ev("E1", OK, EventData(limit = 60.0))),
            ),
        )
        assertEquals("많은 순, 같으면 안전·벨트 먼저", listOf("출발할 때 엑셀을 조금 세게 2번", "벨트 안 매고 출발 1번", "깜빡이 켜고 바로 꺾음 1번"), texts(s3.bad))
        assertEquals(
            "성공률 높은 순, 같으면 판정이 많은 쪽",
            listOf("신호 앞에서 부드럽게 멈추기 5/5번", "꺾기 전에 미리 깜빡이 켜기 4/4번", "제한속도 다 지켰어요"), texts(s3.good),
        )
        // X로 바꾼 항목은 리뷰 줄에서 빠짐(과제 줄도)
        val x = summarize(
            rec(2, many(4, "C4", OK) + many(2, "B1", FAIL) + many(3, "F1", OK), items = itemsOf(2, mapOf("C4" to Delivery.OFF, "B1" to Delivery.OFF))),
        )
        assertFalse(x.good.toString(), texts(x.good).any { "깜빡이 켜자마자" in it })
        assertEquals(emptyList<String>(), texts(x.bad))
        // 1단계(구역 과제 없음): 구역을 모두 지키면 한 줄, 하나라도 넘으면 없음
        val z = summarize(rec(1, many(5, "B1", OK) + listOf(ev("E1", OK, EventData(limit = 30.0)), ev("E1", OK, EventData(limit = 30.0)))))
        assertTrue("30 구역 다 지켰어요" in texts(z.good))
        val z2 = summarize(rec(1, many(5, "B1", OK) + listOf(ev("E1", OK, EventData(limit = 30.0)), ev("E1", OK, EventData(limit = 50.0)))))
        assertTrue("제한속도 다 지켰어요" in texts(z2.good))
        val z3 = summarize(
            rec(1, many(5, "B1", OK) + listOf(ev("E2", FAIL, EventData(zoneId = "z1")), ev("E1", OK, EventData(zoneId = "z1", limit = 30.0)))),
        )
        assertFalse(texts(z3.good).any { "다 지켰어요" in it })
        assertEquals(listOf("어린이보호구역 초과 1번"), texts(z3.bad))
        // 같은 구역의 E2·E1은 한 번으로, 깜빡이 안 켬은 같은 회전 C5가 있으면 그 줄로
        val d = summarize(
            rec(
                2,
                listOf(
                    ev("E2", FAIL, EventData(zoneId = "zs")), ev("E1", FAIL, EventData(zoneId = "zs", limit = 30.0, school = true)),
                    ev("C2", FAIL, EventData(turnId = "t4")), ev("C5", FAIL, EventData(turnId = "t4")), ev("C2", FAIL, EventData(turnId = "t5")),
                ),
            ),
        )
        assertEquals(listOf("어린이보호구역 초과 1번", "깜빡이 없이 회전 1번", "회전 전 깜빡이 안 켬 1번"), texts(d.bad))
        // 3줄까지
        val many3 = summarize(rec(2, many(3, "B1", FAIL) + many(2, "F1", FAIL) + many(4, "C4", FAIL) + ev("A1", FAIL) + ev("PB2", FAIL)))
        assertEquals(3, many3.bad.size)
        assertEquals(listOf("깜빡이 켜고 바로 꺾음 4번", "출발할 때 엑셀을 조금 세게 3번", "멈출 때 브레이크를 조금 세게 2번"), texts(many3.bad))
        // G1: 첫 기준이 있고 10% 넘게 좋아졌을 때만, X면 없음
        fun g(value: Double, items: Map<String, Delivery> = itemsOf(1)) =
            texts(summarize(rec(1, many(5, "B1", OK), items = items, metrics = SessionMetrics(g1 = value)), history = SessionHistory(baselineG1 = 1.0)).good)
        assertTrue("직진할 때 핸들 첫날보다 15% 안정" in g(0.85))
        assertFalse("10% 아래", g(0.91).any { "핸들" in it })
        assertFalse(g(0.5, itemsOf(1, mapOf("G1" to Delivery.OFF))).any { "핸들" in it })
        assertFalse("첫 회차면 없음", texts(summarize(rec(1, many(5, "B1", OK), metrics = SessionMetrics(g1 = 0.5))).good).any { "핸들" in it })
    }

    // ================================================================ K4

    @Test
    fun k4ComparesWithFirstSession() {
        assertEquals(com.barodrive.core.model.K4Compare(first = true), EngineReview.k4(SessionMetrics(c2Avg = 50.0), SessionHistory()))
        val k = EngineReview.k4(
            SessionMetrics(c2Avg = 31.6, stopFail = 2, stopTotal = 5, g1 = 1.2),
            SessionHistory(firstSessionMetrics = FirstMetrics(c2Avg = 45.0, stopFailRate = 0.2, g1 = null), baselineG1 = 1.0),
        )
        assertEquals(
            "첫 회차 횟수가 없으면 비율로, G1 기준은 g1Baseline",
            listOf(listOf("c2Avg", "45m → 32m", false), listOf("stop", "20% → 40%", false), listOf("g1", "첫날보다 20% 더 흔들려요", false)),
            k.rows.map { listOf(it.id, it.text, it.better) },
        )
        val k2 = EngineReview.k4(SessionMetrics(g1 = 0.98), SessionHistory(firstSessionMetrics = FirstMetrics(g1 = 1.0)))
        assertEquals(listOf(listOf("g1", "첫날과 비슷해요")), k2.rows.map { listOf(it.id, it.text) })
    }

    // ================================================================ 미리 만든 상태(요약만)

    @Test
    fun summaryOnlyRecordUsesTasks() {
        val s = summarize(
            SessionRecord(
                mode = 2, items = itemsOf(2),
                tasks = listOf(TaskResult("T-30", success = 2, total = 3), TaskResult("T-SIGNAL30", success = 5, total = 6), TaskResult("T-STOP", success = 7, total = 8)),
                metrics = SessionMetrics(g1 = 0.9),
            ),
        )
        assertEquals(listOf(listOf("T-30", 2, 3), listOf("T-SIGNAL30", 5, 6), listOf("T-WAIT3", 0, 0), listOf("T-STOP", 7, 8)), rows(s))
        assertEquals(0.824, s.achievement)
        assertTrue(s.qualified)
        assertEquals(NextTask("T-30", "30 구역 지키기", "30 구역", NextTaskReason.SAFETY), s.nextTask)
        assertEquals(listOf("30 구역 초과 1번"), texts(s.bad))
        assertEquals(listOf("멈출 때 부드럽게 멈추기 7/8번", "꺾기 30m 전에 깜빡이 켜기 5/6번"), texts(s.good))
        // compute(rec, profile) = 프로필 값으로 summarize(설계 6.1.1 이름)
        val p = profile(2).copy(firstMetrics = FirstMetrics(g1 = 1.0), g1Baseline = 1.0)
        val c = EngineReview.compute(rec(2, many(4, "F1", OK), metrics = SessionMetrics(g1 = 0.7)), p)
        assertTrue("직진할 때 핸들 첫날보다 30% 안정" in texts(c.good))
    }

    @Test
    fun hillTasksComeFromTheHillNote() {
        val s = summarize(
            SessionRecord(
                mode = 7, items = itemsOf(7), events = listOf(ev("B1", OK)),
                hill = HillResult(
                    tasks = mapOf(
                        "T-HILLUP" to HillTaskCount(success = 1, total = 2),
                        "T-HILLDOWN" to HillTaskCount(success = 1, total = 1),
                        "T-HILLPARK" to HillTaskCount(success = 0, total = 1, badText = "언덕 주차 바퀴 방향 {n}번"),
                    ),
                ),
            ),
        )
        assertEquals(listOf(listOf("T-HILLUP", 1, 2), listOf("T-HILLDOWN", 1, 1), listOf("T-HILLPARK", 0, 1)), rows(s))
        assertEquals(0.5, s.achievement)
        assertEquals(listOf("오르막에서 뒤로 밀림 1번", "언덕 주차 바퀴 방향 1번"), texts(s.bad))
        assertEquals("T-HILLPARK", s.nextTask?.id)
    }

    @Test
    fun weatherSessionRewritesThe30InTheNextTask() {
        // 설계 6.8: 눈 회차는 30 구역 기준이 24(T11-6) — 다음 과제 글의 '30'을 그 회차 기준으로
        val snow = rec(2, many(5, "B1", OK) + ev("E2", FAIL, EventData(zoneId = "z1")))
            .copy(weather = com.barodrive.core.model.SessionWeather(type = com.barodrive.core.model.WeatherType.SNOW))
        assertEquals("어린이보호구역 24 지키기", summarize(snow).nextTask?.label)
        val clear = rec(2, many(5, "B1", OK) + ev("E2", FAIL, EventData(zoneId = "z1")))
            .copy(weather = com.barodrive.core.model.SessionWeather())
        assertEquals("어린이보호구역 30 지키기", summarize(clear).nextTask?.label)
        assertNull("날씨가 없는 기록은 그대로", summarize(rec(5, emptyList())).nextTask)
    }
}
