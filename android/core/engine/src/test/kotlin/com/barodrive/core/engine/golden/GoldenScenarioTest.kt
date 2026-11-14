package com.barodrive.core.engine.golden

import com.barodrive.core.engine.of
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.WeatherBadge
import com.barodrive.core.model.WeatherSource
import com.barodrive.core.model.WeatherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프로토타입 시나리오 시험(t2a_m1 S1-B · t10 S9-R · t7 S5-R·S2-S)의 엔진 쪽 단언을 골든 회차(같은 시뮬레이터 스냅숏)로 다시 돌려 본다.
 * 프로토타입은 시뮬레이터로 회차를 만들지만 엔진 포트는 시뮬레이터를 모르므로 골든 스냅숏을 입력으로 쓴다.
 */
class GoldenScenarioTest {
    private fun all(r: GoldenReplay.Replayed): List<JudgeEvent> = r.outputs.flatMap { it.events } + r.record.events

    @Test
    fun s1bNormalJudgmentsLinesCardsAndK6() {
        val r = GoldenReplay.replay("m1-S1-B-normal")
        val rec = r.record
        for (it in listOf("A1", "B1", "C1", "C2", "C4", "C5", "F1", "E1", "G1", "PB2")) assertTrue(it, all(r).any { e -> e.item == it })
        assertTrue("주차장 입구에서 내비 도착", rec.arrived)
        for (k in listOf("a1", "c1", "c2.ok", "f1.fix", "e1", "pb2")) assertTrue(k, rec.utterances.any { it.spoken && it.key == k })
        val c1 = r.said.first { it.key == "c1" }
        val c2 = r.said.first { it.key == "c2.ok" }
        assertTrue("1번 회전 사전 안내 → 켜는 순간 칭찬", c1.t < c2.t && c2.t - c1.t < 10)
        assertEquals("좋았어, 50미터 전에 켰어.", c2.text)
        assertEquals(listOf("F1", "C4", "C5", "B1"), r.cards.filter { it.category == CardCategory.FIX }.map { it.item })
        val f1card = r.cards.first()
        assertEquals("조금 급하게 섰어요", f1card.title)
        assertTrue(f1card.detail.orEmpty(), Regex("40km/h에서 [\\d.]+초 만에 정지 · 가장 셀 때 1초에 10km/h").containsMatchIn(f1card.detail.orEmpty()))
        assertTrue("보통도 통과 회차 ${rec.achievement}", (rec.achievement ?: 0.0) >= 0.8 && rec.qualified)
        assertEquals("T-30", rec.nextTask?.id)
        assertEquals(listOf("30 구역 초과 1번", "벨트 안 매고 출발 1번", "깜빡이 켜고 바로 꺾음 1번"), rec.bad.map { it.text })
        assertEquals(3, rec.good.size)
        assertEquals(1, rec.events.of("E1", "fail").size)
        assertEquals("주차장 안(내비 없음) 회전", listOf<String?>(null), rec.events.of("C5", "fail").map { it.data.turnId })
        assertEquals("내비 회전 3개", 3, rec.events.of("C2").size)
        assertEquals("조향 회전 13개", 13, rec.events.of("C5").size)
        val reasons = rec.utterances.filter { !it.spoken }.mapNotNull { it.reason }.toSet()
        assertTrue(reasons.toString(), BlockReason.CAP in reasons && BlockReason.RATIO in reasons)
        val drv = rec.utterances.filter { it.spoken && it.key != "pb2" }
        assertEquals(drv.size, rec.metrics.spoken)
        assertEquals(Math.round(drv.size / (rec.driveSec / 600) * 10) / 10.0, rec.metrics.per10min)
        assertEquals(drv.count { it.category == Category.PRAISE } to drv.count { it.category == Category.FIX }, rec.metrics.praise to rec.metrics.correction)
        assertEquals(listOf<Any?>(40.0, 3, 1), listOf(rec.metrics.c2Avg, rec.metrics.c2N, rec.metrics.stopFail))
        assertTrue("G1 ${rec.metrics.g1}", (rec.metrics.g1 ?: 0.0) > 0.8 && (rec.metrics.g1 ?: 0.0) < 1.4)
        assertTrue(r.session.errors.isEmpty())
    }

    @Test
    fun s1bPoorIsNotQualified() {
        val rec = GoldenReplay.replay("m1-S1-B-poor").record
        assertTrue("서툰 ${rec.achievement}", (rec.achievement ?: 1.0) < 0.8)
        assertTrue(!rec.qualified)
        assertTrue("급출발", rec.events.of("B1", "fail").size >= 3)
        assertTrue("급정지", rec.events.of("F1", "fail").size >= 5)
        assertTrue("켜자마자 꺾음", rec.events.of("C4", "fail").size >= 5)
        assertTrue("깜빡이 없이 두 번", rec.events.of("C5", "fail").size >= 2)
        assertEquals("깜빡이가 늦음", 0, rec.events.of("C2", "ok").size)
        assertTrue("핸들도 더 흔들림", (rec.metrics.g1 ?: 0.0) > (GoldenReplay.replay("m1-S1-B-normal").record.metrics.g1 ?: 0.0))
    }

    @Test
    fun s9rRuleScenarioBlocksForEveryReason() {
        val rec = GoldenReplay.replay("m1-S9-R-normal").record
        assertTrue(rec.arrived)
        val reasons = rec.utterances.filter { !it.spoken }.groupingBy { it.reason }.eachCount()
        for (why in listOf(BlockReason.GAP, BlockReason.CAP, BlockReason.RATIO, BlockReason.SILENCE, BlockReason.NAV_VOICE, BlockReason.UNDER_50)) {
            assertTrue("$why 막힘이 없음: $reasons", (reasons[why] ?: 0) > 0)
        }
        fun blk(key: String, why: BlockReason) = rec.utterances.any { !it.spoken && it.key == key && it.reason == why }
        assertTrue("내비 음성과 겹친 C1이 50m 안으로", blk("c1", BlockReason.UNDER_50))
        assertTrue("교정 연속(카드 문장이 비율에 막힘)", blk("b1.fix", BlockReason.RATIO))
        assertTrue("최근 10분 6번 상한", blk("c2.ok", BlockReason.CAP))
        assertTrue(blk("c2.ok", BlockReason.SILENCE) && blk("c2.ok", BlockReason.NAV_VOICE))
        val spoken = rec.utterances.filter { it.spoken && it.item != "PB2" }
        assertEquals(6, spoken.size)
        assertTrue(spoken.any { it.item == "E1" } && spoken.any { it.item == "K8" })
        assertEquals("K6에도 셈", 6, rec.metrics.spoken)
        assertEquals("위험 회피 급제동(30초 침묵)", 1, rec.f2.size)
        assertTrue("9분 안", rec.durationSec > 200 && rec.durationSec < 540)
    }

    @Test
    fun s5rRainViaWiperE1WxSlipperyAndTipCards() {
        val r = GoldenReplay.replay("m5-S5-R-rain")
        val rec = r.record
        assertTrue(rec.arrived)
        assertEquals(5, rec.mode)
        assertEquals("company", rec.placeId)
        val rain = r.said.first { it.key == "j3.rain" }
        assertTrue("와이퍼(60초) + 30초 ${rain.t}", rain.t >= 89 && rain.t <= 90.5)
        assertEquals(WeatherType.RAIN to WeatherSource.WIPER, rec.weather?.type to rec.weather?.by)
        assertEquals(WeatherBadge.RAIN, r.lastHints.badge)
        assertEquals("낮춘 기준 40을 넘은 한 번", listOf("비 오는 날은 40까지야, 조금 줄이자."), r.said.filter { it.key == "e1.wx" }.map { it.text })
        assertEquals(listOf(50.0 to 40.0), rec.events.of("E1", "fail").map { it.data.limit to it.data.effLimit })
        assertEquals("미끄러운 길 주의 구간 300m 앞", 1, r.said.count { it.key == "j3.slip" })
        val tips = r.cards.filter { it.category == CardCategory.TIP }
        assertTrue("빗길 요령 카드", tips.size >= 2 && tips[0].title == "평소보다 속도를 줄여요." && tips[0].icon == "i-rain")
        assertEquals(tips.size, tips.map { it.title }.toSet().size)
        val b1 = rec.events.of("B1", "fail")
        assertTrue(b1.size == 1 && (b1[0].data.maxGain1s ?: 99.0) <= 11 && b1[0].data.threshold == 8.0)
        val f1 = rec.events.of("F1", "fail")
        assertTrue(f1.size == 1 && (f1[0].data.maxDrop1s ?: 99.0) < 7.5 && f1[0].data.threshold == 6.0)
        assertEquals(listOf("빗길 회차예요. 빗길에선 브레이크를 일찍, 나눠서 밟아요."), rec.tips.map { it.text })
        val total = rec.score?.total ?: 0
        assertTrue("자율 점수 $total", total in 80 until 90)
        assertTrue("sim ${rec.durationSec}", rec.durationSec > 450 && rec.durationSec < 700)
    }

    @Test
    fun s2sSnowAndColdLowerThresholdsAndSchoolZone24() {
        val r = GoldenReplay.replay("m2-S2-S-snow")
        val rec = r.record
        assertEquals(listOf("j3.snow", "j3.cold"), r.said.filter { it.key.startsWith("j3.") }.map { it.key })
        assertTrue("출발 때", r.said.first { it.key == "j3.snow" }.t < 1)
        assertEquals(listOf<Any?>(WeatherType.SNOW, true, WeatherSource.SNOW), listOf(rec.weather?.type, rec.weather?.cold, rec.weather?.by))
        assertEquals(WeatherBadge.SNOW, r.lastHints.badge)
        assertEquals("1초 10km/h — 맑은 날 11이면 괜찮음", listOf(10.0 to 8.0), rec.events.of("B1", "fail").map { it.data.maxGain1s to it.data.threshold })
        assertEquals("1초 7km/h — 맑은 날 7.5면 괜찮음", listOf(7.0 to 6.0), rec.events.of("F1", "fail").map { it.data.maxDrop1s to it.data.threshold })
        val e2 = rec.events.of("E2", "fail")
        assertEquals(1, e2.size)
        assertEquals(24.0, e2[0].data.effLimit)
        assertEquals(listOf("어린이보호구역이야. 24 아래로 가자."), r.said.filter { it.key == "e2" }.map { it.text })
        assertEquals("어린이보호구역 앞에서 E1을 먼저 말하지 않음", 0, r.said.count { it.key == "e1.wx" })
        val tips = r.cards.filter { it.category == CardCategory.TIP }
        assertTrue("눈길 요령 카드 ${tips.size}", tips.size >= 3 && tips.all { it.icon == "i-snow" })
        assertEquals("엑셀을 아주 살살 밟아 출발해요.", tips[0].title)
        assertEquals(listOf("눈길 회차예요. 눈길은 엔진브레이크로 천천히 속도를 줄여요."), rec.tips.map { it.text })
        assertEquals("다음 과제: 어린이보호구역", "T-SCHOOL", rec.nextTask?.id)
    }
}
