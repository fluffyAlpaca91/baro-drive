package com.barodrive.core.engine.fading

import com.barodrive.core.engine.api.EngineFading
import com.barodrive.core.engine.api.EngineReview
import com.barodrive.core.engine.content.Items
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.FadingUpdate
import com.barodrive.core.model.FeedbackSettings
import com.barodrive.core.model.ItemStat
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 도움 줄여 가기 (프로토타입 tests/unit/t2a_fading.test.js). 프로토타입 `update`는 프로필을 고치고, 여기서는 돌려준 상태를
 * 시험이 프로필에 넣는다(앱에서는 [EngineReview.applyProgress]가 넣는다).
 * 전달 방식 결과(`resolveItems`·`buildConfig` — 엔진 코어 몫) 확인은 빠졌다.
 */
class FadingTest {
    /** 항목별 [ok, 판정]으로 회차 하나(t2a_fading `rec`). */
    private fun rec(mode: Int, stats: Map<String, Pair<Int, Int>>): SessionRecord {
        var t = 0.0
        val events = ArrayList<JudgeEvent>()
        for ((id, s) in stats) {
            val (ok, total) = s
            for (i in 0 until total) events += JudgeEvent(t = ++t, item = id, result = if (i < ok) EventResult.OK else EventResult.FAIL)
        }
        return SessionRecord(mode = mode, events = events)
    }

    /** update 결과를 프로필에 넣는다(fading.js `update`가 하던 일). */
    private class Run(var p: Profile = Profile()) {
        fun update(r: SessionRecord): FadingUpdate {
            val u = EngineFading.update(p, r)
            p = p.copy(fading = p.fading + (r.mode to u.state))
            return u
        }
    }

    @Test
    fun onlyTaskItemsFade() {
        assertEquals(listOf("B1", "C2", "C4", "C5", "F1"), EngineFading.items.sorted())
        assertEquals("콘텐츠 항목 정의의 fade와 같다", Items.all.filter { it.fade }.map { it.id }, EngineFading.items)
        assertEquals(EngineFading.items, FadingCalc.items)
        for (id in listOf("E1", "E2", "F2", "A1", "PB1", "PB2", "L1", "J3", "PK1", "HL1", "C1", "G1", "C3", "C6", "J1")) {
            assertFalse(id, EngineFading.eligible(id, 1, emptyMap()))
        }
    }

    @Test
    fun twoSessionsAt80PercentFadeAndShowTheNote() {
        val run = Run()
        val r1 = run.update(rec(1, mapOf("B1" to (5 to 6), "F1" to (4 to 4), "E1" to (3 to 3), "A1" to (0 to 1))))
        assertEquals(emptyList<String>(), r1.fadedNow)
        assertEquals(mapOf("B1" to FadeState(1, false), "F1" to FadeState(1, false)), run.p.fading[1])
        val r2 = run.update(rec(1, mapOf("B1" to (4 to 5), "F1" to (2 to 2), "E1" to (4 to 4))))
        assertEquals(listOf("B1", "F1"), r2.fadedNow)
        assertEquals(mapOf("B1" to FadeState(2, true), "F1" to FadeState(2, true)), run.p.fading[1])
        assertFalse("안전 항목은 상태도 없음", run.p.fading[1].orEmpty().containsKey("E1"))
        assertEquals("설계 4.2 RV-1 10번", "출발은 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.", EngineFading.note(listOf("B1")))
        assertEquals("출발·정지는 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.", EngineFading.note(listOf("F1", "B1")))
        assertEquals("깜빡이 타이밍은 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.", EngineFading.note(listOf("C2")))
        assertEquals(
            "출발·정지 등 5가지는 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.",
            EngineFading.note(listOf("B1", "C2", "C4", "C5", "F1")),
        )
        assertEquals(null, EngineFading.note(emptyList()))
    }

    @Test
    fun streakBreaksBelow80AndIgnoresSessionsWithFewJudgements() {
        val run = Run()
        run.update(rec(2, mapOf("C4" to (3 to 3))))
        run.update(rec(2, mapOf("C4" to (1 to 1)))) // 판정 1번 → 그대로
        assertEquals(FadeState(1, false), run.p.fading[2]?.get("C4"))
        run.update(rec(2, mapOf("C4" to (3 to 4)))) // 75% → 끊김
        assertEquals(FadeState(0, false), run.p.fading[2]?.get("C4"))
        run.update(rec(2, mapOf("C4" to (4 to 5))))
        val r = run.update(rec(2, mapOf("C4" to (2 to 2))))
        assertEquals("80%는 넘김(경계 포함)", listOf("C4"), r.fadedNow)
    }

    @Test
    fun fadedItemComesBackBelow80() {
        val run = Run(Profile(fading = mapOf(1 to mapOf("F1" to FadeState(3, true)))))
        val keep = run.update(rec(1, mapOf("F1" to (1 to 1))))
        assertEquals("판정 1번으로는 안 되돌림", listOf<Any?>(emptyList<String>(), true), listOf(keep.restoredNow, run.p.fading[1]?.get("F1")?.faded))
        val back = run.update(rec(1, mapOf("F1" to (3 to 5))))
        assertEquals(listOf("F1"), back.restoredNow)
        assertEquals(FadeState(0, false), run.p.fading[1]?.get("F1"))
    }

    @Test
    fun c2FadesInStage2() {
        val run = Run()
        run.update(rec(2, mapOf("C2" to (5 to 5))))
        assertEquals(listOf("C2"), run.update(rec(2, mapOf("C2" to (4 to 5)))).fadedNow)
    }

    @Test
    fun userChoiceWinsAndDefaultSDoesNotFade() {
        val run = Run(Profile(feedback = FeedbackSettings(overrides = mapOf(1 to mapOf("B1" to Delivery.NOW)))))
        run.update(rec(1, mapOf("B1" to (5 to 5), "C5" to (3 to 3))))
        val r = run.update(rec(1, mapOf("B1" to (5 to 5), "C5" to (3 to 3))))
        assertEquals(listOf("C5"), r.fadedNow)
        assertFalse("사용자가 바꾼 B1은 상태 없음", run.p.fading[1].orEmpty().containsKey("B1"))
        val q = Run()
        q.update(rec(3, mapOf("B1" to (5 to 5))))
        val r3 = q.update(rec(3, mapOf("B1" to (5 to 5))))
        assertEquals("3단계 B1은 기본이 완료 후(S)", emptyList<String>(), r3.fadedNow)
        assertEquals(false, q.p.fading[3]?.get("B1")?.faded)
    }

    @Test
    fun nextChangesNothingAndStatsPreferTheRecordSummary() {
        val prev = mapOf("B1" to FadeState(1, false))
        val r = EngineFading.next(prev, mapOf("B1" to ItemStat(4, 4)), 1, emptyMap())
        assertEquals(listOf("B1"), r.fadedNow)
        assertEquals(mapOf("B1" to FadeState(1, false)), prev)
        assertEquals(
            mapOf("B1" to ItemStat(1, 2)),
            EngineFading.stats(SessionRecord(mode = 1, itemStats = mapOf("B1" to ItemStat(1, 2)))),
        )
        val evs = listOf(
            JudgeEvent(item = "C4", result = EventResult.OK), JudgeEvent(item = "C4", result = EventResult.EXCLUDED),
            JudgeEvent(item = "C4", result = EventResult.LATE),
        )
        assertEquals(mapOf("C4" to ItemStat(1, 2)), EngineFading.stats(SessionRecord(mode = 1, events = evs)))
        assertEquals("빈 itemStats도 그대로(없음과 다름)", emptyMap<String, ItemStat>(), EngineFading.stats(SessionRecord(mode = 1, events = evs, itemStats = emptyMap())))
    }

    @Test
    fun applyProgressAppliesFadingAndLeavesTheNoteOnTheRecord() {
        var p = Profile()
        fun mk(): SessionRecord {
            val r = rec(1, mapOf("B1" to (5 to 5), "F1" to (6 to 6), "C5" to (4 to 4), "E1" to (2 to 2)))
                .copy(items = mapOf("B1" to Delivery.NOW, "F1" to Delivery.NOW, "C5" to Delivery.NOW, "E1" to Delivery.NOW))
            val cfg = SessionConfig(mode = 1, fading = p.fading[1].orEmpty())
            return r.withReview(EngineReview.summarize(r, cfg))
        }
        val a = EngineReview.applyProgress(p, mk())
        p = a.profile
        assertEquals(emptyList<String>(), a.record.fadedNow)
        val b = mk()
        assertEquals("summarize가 미리 계산", listOf("B1", "C5", "F1"), b.fadedNow)
        val applied = EngineReview.applyProgress(p, b)
        assertEquals(listOf("B1", "C5", "F1"), applied.record.fadedNow)
        assertEquals("출발·정지 등 3가지는 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.", applied.record.fadedNote)
        assertEquals(listOf("B1", "C5", "F1"), applied.record.progress?.fadedNow)
        assertTrue(applied.profile.fading[1].orEmpty().getValue("B1").faded)
    }
}
