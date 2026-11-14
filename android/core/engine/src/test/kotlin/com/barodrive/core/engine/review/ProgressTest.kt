package com.barodrive.core.engine.review

import com.barodrive.core.engine.api.EngineReview
import com.barodrive.core.engine.review.ReviewTestKit.OK
import com.barodrive.core.engine.review.ReviewTestKit.ev
import com.barodrive.core.engine.review.ReviewTestKit.itemsOf
import com.barodrive.core.engine.review.ReviewTestKit.profile
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.FirstMetrics
import com.barodrive.core.model.NextTask
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ProgressResult
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StageState
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.TodayTaskFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 진행 반영·첫 회차 기준값·오늘 과제 (프로토타입 tests/unit/t2a_review.test.js '통과'·'지난 단계 재연습'·'첫 회차 기준값'·'오늘 과제').
 * 프로토타입은 프로필을 고치고, 여기서는 [EngineReview.applyProgress]가 돌려준 새 프로필을 다음 회차에 넘긴다.
 */
class ProgressTest {
    /** 진행을 반영하고 새 프로필·결과를 돌려주는 짧은 이름. */
    private class Run(var p: Profile) {
        fun apply(r: SessionRecord): Pair<ProgressResult, SessionRecord> {
            val a = EngineReview.applyProgress(p, r)
            p = a.profile
            return a.result to a.record
        }
    }

    @Test
    fun passingNeedsTwoQualifiedSessionsAndAppliesOnce() {
        val run = Run(profile(1))
        fun mk(q: Boolean, i: Int) = SessionRecord(
            mode = 1, noInMode = i, qualified = q, achievement = if (q) 0.9 else 0.5, startedAt = "2026-11-10T09:0$i:00+09:00",
        )
        val (r1, a) = run.apply(mk(true, 1))
        assertEquals(listOf<Any?>(0, 1, false, 1, 1), listOf(r1.qualifiedBefore, r1.qualifiedAfter, r1.passed, run.p.progress.currentStage, run.p.sessionCount[1]))
        val before = run.p
        val again = EngineReview.applyProgress(run.p, a)
        assertSame("같은 기록 두 번", a.progress, again.result)
        assertSame("프로필도 그대로", before, again.profile)
        assertEquals(1, run.p.sessionCount[1])
        val (r2) = run.apply(mk(false, 2))
        assertEquals("미달 회차는 안 셈(연속이 아니라 누적)", listOf<Any?>(1, false), listOf(r2.qualifiedAfter, r2.passed))
        val (r3, c) = run.apply(mk(true, 3))
        assertEquals(listOf<Any?>(2, true, 2, "2026-11-10T09:03:00+09:00"), listOf(r3.qualifiedAfter, r3.passed, r3.nextStage, r3.passedAt))
        assertEquals(
            listOf<Any?>(2, 2, "2026-11-10T09:03:00+09:00"),
            listOf(run.p.progress.currentStage, run.p.progress.unlockedStage, run.p.progress.stages[1]?.passedAt),
        )
        assertEquals("회차 기록에도 남김(RV-1 '통과 회차 2/2'·[N단계 통과 확인])", r3, c.progress)
        assertEquals(3, run.p.sessionCount[1])
        // 기록이 적은 회차는 summarize가 qualified false로 낸다 → 통과 회차 아님
        val few = EngineReview.summarize(SessionRecord(mode = 2, events = listOf(ev("B1", OK)), items = itemsOf(2)), SessionConfig(mode = 2))
        assertFalse(few.qualified)
    }

    @Test
    fun pastStagesOnlyCountThemselvesAndStage4Graduates() {
        val start = profile(3)
        val run = Run(
            start.copy(progress = start.progress.copy(stages = start.progress.stages + (1 to StageState(2, "2026-11-01T09:00:00+09:00")))),
        )
        fun q(m: Int, i: Int) = SessionRecord(mode = m, noInMode = i, qualified = true, startedAt = "2026-11-10T10:0$i:00+09:00")
        val (r1) = run.apply(q(1, 1))
        assertEquals(listOf<Any?>(true, true, 2, false), listOf(r1.alreadyPassed, r1.pastStage, r1.qualifiedAfter, r1.passed))
        val (r2) = run.apply(q(2, 1))
        assertEquals(listOf<Any?>(false, true, 1, false), listOf(r2.alreadyPassed, r2.pastStage, r2.qualifiedAfter, r2.passed))
        val (r3) = run.apply(q(2, 2))
        assertEquals("건너뛴 단계가 2번이 되면 통과로 적지만", listOf<Any?>(2, false, true), listOf(r3.qualifiedAfter, r3.passed, r3.passedAt != null))
        assertEquals("지금 단계·열린 단계는 그대로", listOf(3, 3), listOf(run.p.progress.currentStage, run.p.progress.unlockedStage))
        // 4단계 통과 = 졸업
        val g0 = profile(4)
        val g = Run(g0.copy(progress = g0.progress.copy(stages = g0.progress.stages + (4 to StageState(qualified = 1)))))
        val (rg) = g.apply(q(4, 2))
        assertEquals(
            listOf<Any?>(true, true, null, 4, true),
            listOf(rg.passed, rg.graduated, rg.nextStage, g.p.progress.currentStage, g.p.progress.graduatedAt != null),
        )
        val (after) = g.apply(q(4, 3))
        assertEquals("졸업 뒤 4단계는 변화 없음", listOf<Any?>(true, 2), listOf(after.alreadyPassed, after.qualifiedAfter))
        // 모드 5~8: 회차 수만
        val (r5) = g.apply(SessionRecord(mode = 5, qualified = false))
        assertEquals(listOf<Any?>(null, 1), listOf(r5.stage, g.p.sessionCount[5]))
    }

    @Test
    fun passedAtFallsBackToRecordIdThenSessionNumber() {
        val p0 = profile(1)
        val p = p0.copy(progress = p0.progress.copy(stages = p0.progress.stages + (1 to StageState(qualified = 1))))
        assertEquals("2026-11-11T10:00:00+09:00", EngineReview.applyProgress(p, SessionRecord(mode = 1, qualified = true, startedAt = "x"), now = "2026-11-11T10:00:00+09:00").result.passedAt)
        assertEquals("sess-1", EngineReview.applyProgress(p, SessionRecord(id = "sess-1", mode = 1, qualified = true)).result.passedAt)
        assertEquals("회차 1-1", EngineReview.applyProgress(p, SessionRecord(mode = 1, qualified = true)).result.passedAt)
    }

    @Test
    fun firstMetricsAreSetOncePerMetric() {
        val run = Run(profile(1))
        run.apply(SessionRecord(mode = 6, metrics = SessionMetrics(stopTotal = 0)))
        assertEquals("지표가 없는 회차(주차)는 기준이 안 됨", listOf<Any?>(null, null), listOf(run.p.g1Baseline, run.p.firstMetrics))
        run.apply(SessionRecord(mode = 1, qualified = true, metrics = SessionMetrics(c2Avg = 46.7, g1 = 1.14, stopTotal = 8, stopFail = 3, stopFailRate = 0.375)))
        assertEquals(1.14, run.p.g1Baseline)
        assertEquals(FirstMetrics(c2Avg = 46.7, g1 = 1.14, stopTotal = 8, stopFail = 3, stopFailRate = 0.375), run.p.firstMetrics)
        run.apply(SessionRecord(mode = 1, qualified = true, metrics = SessionMetrics(c2Avg = 60.0, g1 = 0.6, stopTotal = 9, stopFail = 0, stopFailRate = 0.0)))
        assertEquals(1.14, run.p.g1Baseline)
        assertEquals(46.7, run.p.firstMetrics?.c2Avg)
        val run2 = Run(profile(1))
        run2.apply(SessionRecord(mode = 1, metrics = SessionMetrics(g1 = 0.9, stopTotal = 4, stopFail = 1, stopFailRate = 0.25)))
        run2.apply(SessionRecord(mode = 2, metrics = SessionMetrics(c2Avg = 52.0, g1 = 0.7)))
        assertEquals("첫 회차에 없던 지표는 그 지표가 처음 나온 회차 값", listOf<Any?>(52.0, 0.9), listOf(run2.p.firstMetrics?.c2Avg, run2.p.firstMetrics?.g1))
        // 급정지 비율이 없으면 횟수로(소수 셋째 자리)
        val run3 = Run(profile(1))
        run3.apply(SessionRecord(mode = 1, metrics = SessionMetrics(stopTotal = 3, stopFail = 1)))
        assertEquals(0.333, run3.p.firstMetrics?.stopFailRate)
    }

    @Test
    fun applyProgressStoresFadingAndRecordFields() {
        val r = SessionRecord(mode = 1, items = itemsOf(1), events = List(5) { ev("B1", OK) })
        val a = EngineReview.applyProgress(profile(1), r)
        assertEquals(mapOf("B1" to FadeState(streak = 1, faded = false)), a.profile.fading[1])
        assertEquals(emptyList<String>(), a.record.fadedNow)
        assertNull(a.record.fadedNote)
        assertNotNull(a.record.progress)
        assertTrue(a.result.applied)
        assertEquals(2, a.result.need)
    }

    // ================================================================ 오늘 과제 (결정-76)

    @Test
    fun todayTasksStartWithLastNextTaskThenLowestRates() {
        var p = profile(2)
        assertEquals(
            listOf(listOf<Any>("T-START", TodayTaskFrom.MODE), listOf("T-STOP", TodayTaskFrom.MODE), listOf("T-SIGNAL1", TodayTaskFrom.MODE)),
            EngineReview.todayTasks(p, 1).map { listOf(it.id, it.from) },
        )
        assertEquals("2단계 과제 4개 중 3개", listOf("T-30", "T-SIGNAL30", "T-WAIT3"), EngineReview.todayTasks(p, 2).map { it.id })
        assertEquals(emptyList<Any>(), EngineReview.todayTasks(p, 5))
        p = p.copy(
            sessions = listOf(
                SessionRecord(
                    id = "s2", mode = 2, nextTask = NextTask("T-30", "30 구역 지키기", "30 구역"),
                    tasks = listOf(
                        TaskResult("T-30", success = 2, total = 3), TaskResult("T-SIGNAL30", success = 5, total = 6),
                        TaskResult("T-WAIT3", success = 1, total = 4), TaskResult("T-STOP", success = 7, total = 8),
                    ),
                ),
                SessionRecord(id = "s1", mode = 1, nextTask = NextTask("T-STOP", "브레이크 부드럽게 밟아서 멈추기", "멈출 때 브레이크")),
            ),
        )
        assertEquals(
            listOf(
                listOf<Any>("T-30", TodayTaskFrom.NEXT, "30 구역 지키기"),
                listOf("T-WAIT3", TodayTaskFrom.MODE, "깜빡이 켜자마자 꺾지 않기"),
                listOf("T-SIGNAL30", TodayTaskFrom.MODE, "꺾기 30m 전에 깜빡이 켜기"),
            ),
            EngineReview.todayTasks(p, 2).map { listOf(it.id, it.from, it.label) },
        )
        assertEquals(listOf(null, 0.25, 0.833), EngineReview.todayTasks(p, 2).map { it.rate })
        p = p.copy(fading = mapOf(2 to mapOf("C4" to FadeState(streak = 2, faded = true))))
        assertEquals("내려간 항목(C4)의 과제는 뺌", listOf("T-30", "T-SIGNAL30", "T-STOP"), EngineReview.todayTasks(p, 2).map { it.id })
        assertEquals("모드마다 그 모드 지난 회차", listOf("T-STOP", "T-START", "T-SIGNAL1"), EngineReview.todayTasks(p, 1).map { it.id })
        p = p.copy(sessions = listOf(SessionRecord(id = "pk", mode = 1, kind = RecordKind.VIRTUAL_PARKING)) + p.sessions)
        assertEquals("가상 주차 기록은 건너뜀", "T-STOP", EngineReview.todayTasks(p, 1)[0].id)
    }
}
