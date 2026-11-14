package com.barodrive.core.engine.content

import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱 대사 전부 훑기 (프로토타입 t1c_engine.test.js '등록된 모든 키 × 코치 3명에 문장이 있고, 자리 표시를 채우면 그릴 수 있다',
 * 설계 6.13). 기획자가 JSON 문장을 고친 뒤에도 이 시험이 빈 문장·남은 `{…}`·모르는 변수 이름을 잡는다.
 */
class LineSweepTest {
    /** 프로토타입 시험의 표본 값. 표에 없는 변수는 '가'. */
    private val sample: Map<String, Any> = mapOf(
        "N" to 55, "T" to 40, "L" to 30, "dir" to "R", "wx" to "rain", "S" to 86, "X" to "B", "dest" to "마트", "nWord" to "세", "list" to "출발",
    )

    /** 대사에 쓰는 변수 이름(설계 6.6, lines JSON의 about 줄). 새 변수를 쓰면 부르는 쪽도 그 값을 넘겨야 해서 여기에 더한다. */
    private val knownVars = setOf(
        "N", "T", "L", "dir", "wx", "S", "X", "dest", "nWord", "list", "side",
        "taskSib", "taskMom", "taskDad", "fixSib", "fixMom", "fixDad", "호칭",
    )

    private val placeholder = Regex("\\{\\s*([^{}\\s]+)\\s*\\}")

    @Test
    fun everyKeyRendersForEveryCoachWithNoPlaceholderLeft() {
        val keys = Lines.keys
        assertTrue(keys.isNotEmpty())
        var rendered = 0
        for (key in keys) for (coach in Coach.entries) {
            val list = Lines.sentences(key, coach)
            assertTrue("$key · $coach: 문장 1~2개", list.size in 1..2)
            list.forEachIndexed { i, sentence ->
                assertTrue("$key · $coach ${i + 1}번 문장이 비어 있음", sentence.isNotBlank())
                val vars = placeholder.findAll(sentence).map { it.groupValues[1] }.filter { it != "호칭" }
                    .associateWith { sample[it] ?: "가" }
                for (hon in listOf(Honorific(HonorificType.DAUGHTER), Honorific(HonorificType.NONE))) {
                    val text = Lines.render(key, coach, vars, hon, index = i)
                    assertTrue("$key · $coach: $text", text.isNotBlank())
                    assertFalse("$key · $coach: 자리 표시가 남음 → $text", '{' in text || '}' in text)
                    rendered++
                }
            }
        }
        assertTrue(rendered >= keys.size * Coach.entries.size * 2)
    }

    @Test
    fun everyPlaceholderIsAKnownVariable() {
        for (key in Lines.keys) for (coach in Coach.entries) for (sentence in Lines.sentences(key, coach)) {
            for (m in placeholder.findAll(sentence)) {
                assertTrue("$key · $coach: 모르는 변수 {${m.groupValues[1]}}", m.groupValues[1] in knownVars)
            }
            assertEquals("$key · $coach: 짝이 안 맞는 중괄호 → $sentence", sentence.count { it == '{' }, sentence.count { it == '}' })
        }
    }

    @Test
    fun siblingCoachNeverSaysAnHonorific() {
        // 설계 6.6.1: 형제/자매 대사에는 {호칭} 자리를 두지 않는다(툴툴 말투와 안 맞음).
        for (key in Lines.keys) for (sentence in Lines.sentences(key, Coach.SIB)) assertFalse(key, sentence.contains("호칭"))
    }

    @Test
    fun holdsEveryPrototypeLineKey() {
        // 프로토타입 js/data/lines의 대사 키는 118개(2026-11-11 기준, pk1.* 20개 포함).
        assertEquals(118, Lines.keys.size)
        assertEquals(12, Lines.FILES.size)
    }
}
