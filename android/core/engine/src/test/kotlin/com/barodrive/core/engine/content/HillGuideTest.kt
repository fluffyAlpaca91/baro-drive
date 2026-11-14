package com.barodrive.core.engine.content

import com.barodrive.core.model.HillSlope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 언덕 연습 모양 (프로토타입 t6_hill.test.js '언덕 데이터(BaroData.hill)', 설계 4.2 HL-1·HL-2, 종합 4장 표). */
class HillGuideTest {
    @Test
    fun practicesStepsTapStepsAndSituations() {
        assertEquals(listOf("up" to 4, "down" to 3, "park" to 3), HillGuide.practices.map { it.id to it.steps.size })
        for (p in HillGuide.practices) for (s in p.steps) {
            assertEquals("hl1.${p.id}.${s.n}", s.key)
            assertTrue(s.key, Lines.has(s.key))
            assertTrue(p.id + s.n, s.label.isNotBlank() && s.sig.isNotBlank() && s.icon.isNotBlank())
        }
        val taps = HillGuide.practices.map { p -> p.id + ":" + p.steps.filter { it.tap }.joinToString(",") { it.n.toString() } }
        assertEquals("화면 누름 단계 = 오르막 2, 내리막 1·3(설계 4.2 HL-1)", listOf("up:2", "down:1,3", "park:"), taps)
        assertEquals(
            listOf(Triple("down", "R", "연석 쪽(오른쪽)"), Triple("up", "L", "연석 반대쪽(왼쪽)"), Triple("none", "R", "길 가장자리 쪽(오른쪽)")),
            HillGuide.situations.map { Triple(it.id, it.side, it.sideText) },
        )
        assertEquals("오르막·연석 있음", HillGuide.situation("up")?.label)
        assertEquals("나눠 밟기", HillGuide.step("down", 2)?.label)
        val t = HillGuide.text
        assertTrue(t.holdWarn.isNotBlank() && t.safety.isNotBlank() && t.retry.isNotBlank() && t.rollbackOff.isNotBlank())
    }

    @Test
    fun findersAndEngineSlopeValues() {
        assertEquals("up", HillGuide.situation(HillSlope.UP)?.id)
        assertEquals("down", HillGuide.situation(HillSlope.DOWN)?.id)
        assertEquals("none", HillGuide.situation(HillSlope.NONE)?.id)
        assertNull(HillGuide.practice("nope"))
        assertNull(HillGuide.situation("nope"))
        assertNull(HillGuide.step("up", 9))
        assertNull(HillGuide.step(null, 1))
        assertEquals("오르막 출발", HillGuide.practice("up")?.name)
        assertEquals(mapOf("up" to "오르막 출발을 한 번 마쳤어요", "down" to "내리막 감속을 마쳤어요", "park" to "언덕 주차를 마쳤어요"), HillGuide.text.done)
        // 상황의 sideText가 언덕 주차 2단계 문장의 {side} 자리에 들어간다(설계 6.6.2 아래 줄)
        val side = HillGuide.situation(HillSlope.UP)!!.sideText
        assertEquals("브레이크를 밟은 채 앞바퀴를 연석 반대쪽(왼쪽)으로 돌리세요.", Lines.render("hl1.park.2", vars = mapOf("side" to side), index = 0))
    }
}
