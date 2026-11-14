package com.barodrive.app.session.api

import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.BlockedUtterance
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** t1d_voice.test.js — 기록 줄 모양·막힌 말·내비 음성·정차 중 말 차례·빨리 감기·시각 표기. */
class VoiceLogTest {
    private fun coachLine(text: String, key: String = "", t: Double = 0.0, dur: Double? = null, category: Category = Category.INFO, item: String? = null) =
        Utterance(key = key, text = text, t = t, dur = dur, category = category, item = item)

    @Test
    fun sayRecordsTheEntryShapeAndSpeakingTime() {
        val v = InMemoryVoiceLog()
        val e = v.say(coachLine("좋았어, 50미터 전에 켰어.", key = "c2.ok", t = 12.3, category = Category.PRAISE, item = "C2"))
        assertEquals(Speaker.COACH, e.speaker)
        assertEquals("프로필이 없으면 아빠/배우자", Coach.DAD, e.coach)
        assertEquals(12.3, e.t)
        assertTrue(e.spoken)
        assertFalse(e.blocked)
        assertNull(e.reason)
        assertEquals("C2", e.item)
        assertEquals(Category.PRAISE, e.category)
        assertEquals("글자 16개 × 0.15 + 0.4", 2.8, e.dur, 1e-9)
        assertEquals(2.8, InMemoryVoiceLog.speakDur("좋았어, 50미터 전에 켰어."), 1e-9)
        assertEquals("넘겨받은 dur를 씀", 5.0, v.say(coachLine("천천히.", t = 1.0, dur = 5.0)).dur, 0.0)
        assertEquals(2, v.entries.value.size)
        assertEquals(listOf(1L, 2L), v.entries.value.map { it.seq })
        assertEquals("빈 키 글은 그대로", "천천히.", v.captions.value.coach?.text)
    }

    @Test
    fun blockedIsLogOnlyWithItsReason() {
        val v = InMemoryVoiceLog()
        val b = v.blocked(BlockedUtterance(coachLine("거의 됐어.", key = "c2.almost", t = 30.0, item = "C2"), BlockReason.GAP))
        assertFalse(b.spoken)
        assertTrue(b.blocked)
        assertEquals(BlockReason.GAP, b.reason)
        assertNull("막힌 말은 자막에 안 나옴", v.captions.value.coach)
        assertEquals("간격", BlockReason.GAP.label)
        assertEquals("50m 안", BlockReason.UNDER_50.label)
        assertEquals("완료 후", BlockReason.AFTER.label)
        assertEquals("안 함", BlockReason.OFF.label)
        assertEquals("소리 끔", BlockReason.MUTED.label)
        assertEquals("사전 안내", Category.PRE.label)
        assertEquals("엄마", Coach.MOM.title)
    }

    @Test
    fun naviVoiceGoesToTheNaviSlot() {
        val v = InMemoryVoiceLog()
        val e = v.navi("300미터 앞에서 우회전입니다", t = 3.0, dur = 2.5)
        assertEquals(Speaker.NAVI, e?.speaker)
        assertNull(e?.coach)
        assertEquals(2.5, e?.dur ?: 0.0, 0.0)
        assertEquals(e, v.captions.value.navi)
        assertNull(v.captions.value.coach)
        assertNull("빈 글은 무시", v.navi("", t = 4.0))
        assertEquals(1, v.entries.value.size)
    }

    @Test
    fun sayLineRendersWithTheCurrentCoachAsAStationaryLine() {
        val v = InMemoryVoiceLog(render = { key, vars -> "[dad] $key ${vars["N"]}" })
        val e = v.sayLine("t1d.test", mapOf("N" to 50))
        assertEquals("[dad] t1d.test 50", e?.text)
        assertEquals(true, e?.stationary)
        assertEquals(Category.INFO, e?.category)
        val e2 = v.sayLine("t1d.test", mapOf("N" to 40), item = "C2", category = Category.PRAISE)
        assertEquals(Category.PRAISE, e2?.category)
        assertEquals("C2", e2?.item)
        assertEquals("앞 정차 중 말이 끝날 때까지 기다림", e, v.captions.value.coach)
        assertEquals(listOf(e2?.seq), v.pendingLines.map { it.seq })
        val broken = InMemoryVoiceLog(render = { _, _ -> throw IllegalArgumentException("대사 없음") })
        assertNull(broken.sayLine("없는.키"))
    }

    @Test
    fun sayLineWithACoachRendersAndRecordsThatCoach() {
        val v = InMemoryVoiceLog(render = { key, _ -> "[dad] $key" }, renderAs = { key, _, coach -> "[$coach] $key" })
        val e = v.sayLine("co.preview", coach = Coach.SIB)
        assertEquals("voice.js sayLine opts.coach", "[SIB] co.preview", e?.text)
        assertEquals(Coach.SIB, e?.coach)
        assertEquals("코치를 안 주면 지금 프로필 코치", Coach.DAD, v.sayLine("co.preview")?.coach)
    }

    @Test
    fun stationaryQueueReplacesSameKeyKeepsThreeAndDrivingLinesShowAtOnce() {
        val v = InMemoryVoiceLog(render = { key, _ -> "$key 문장" })
        val a = v.sayLine("p.tasks")
        v.sayLine("l1")
        val l1b = v.sayLine("l1")
        assertEquals(a, v.captions.value.coach)
        assertEquals("같은 키는 하나만", listOf(l1b?.seq), v.pendingLines.map { it.seq })
        repeat(4) { v.sayLine("k$it") }
        assertEquals("기다리는 말은 3개까지", 3, v.pendingLines.size)
        val drive = v.say(coachLine("벨트부터 매자.", key = "a1", item = "A1"))
        assertEquals("엔진 말은 바로 자막에", drive, v.captions.value.coach)
        assertEquals(3, v.pendingLines.size)
        assertEquals("기록은 부른 때 모두 남음", 8, v.entries.value.size)
        v.dropStationary()
        assertEquals("출발하면 기다리던 정차 중 말은 버림", 0, v.pendingLines.size)
        assertEquals("주행 중 말은 그대로", drive, v.captions.value.coach)
    }

    @Test
    fun fastForwardLogsOnlyAndShowsTheLastLineAfterwards() {
        val v = InMemoryVoiceLog()
        val before = v.say(coachLine("빨리 감기 전 말", t = 1.0))
        v.setFastForward(true)
        val x = v.say(coachLine("첫째", key = "a", t = 3.0))
        v.navi("잠시 후 우회전입니다", t = 3.5, dur = 2.5)
        v.blocked(BlockedUtterance(coachLine("막힘", key = "b", t = 4.0), BlockReason.CAP))
        val last = v.say(coachLine("마지막", key = "c", t = 5.0))
        assertEquals("빨리 감기 동안에는 자막을 바꾸지 않음", before, v.captions.value.coach)
        assertTrue(x.ff && last.ff)
        v.setFastForward(false)
        assertEquals("마지막 말 하나만", last, v.captions.value.coach)
        assertNull("다른 자리는 비움", v.captions.value.navi)
        assertEquals(5, v.entries.value.size)
        val after = v.say(coachLine("그 뒤", t = 6.0))
        assertFalse(after.ff)
        assertEquals(after, v.captions.value.coach)
    }

    @Test
    fun clearEmptiesTheLogAndTimesFormat() {
        val v = InMemoryVoiceLog()
        v.say(coachLine("하나"))
        v.clear()
        assertTrue(v.entries.value.isEmpty())
        assertEquals("04:12", fmtT(252.0))
        assertEquals("1:02:05", fmtT(3725.9))
        assertEquals("--:--", fmtT(null))
    }
}
