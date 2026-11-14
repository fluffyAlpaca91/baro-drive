package com.barodrive.core.engine.content

import com.barodrive.core.model.Coach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 대사 JSON 읽기 규칙: 기획자가 고친 파일이 틀리면 처음 읽을 때 바로 알린다. */
class ContentLoadTest {
    private fun parse(lines: String) = parseLineFile("test.json", """{ "about": "시험", "lines": { $lines } }""")

    @Test
    fun sameFillsEveryCoachAndASingleSentenceMayBeAString() {
        val book = parse(""" "a": { "same": "모두 같은 문장." }, "b": { "sib": "하나.", "mom": ["엄마 하나.", "엄마 둘."], "dad": ["아빠."] } """)

        assertEquals(Coach.entries.associateWith { listOf("모두 같은 문장.") }, book["a"])
        assertEquals(mapOf(Coach.SIB to listOf("하나."), Coach.MOM to listOf("엄마 하나.", "엄마 둘."), Coach.DAD to listOf("아빠.")), book["b"])
        assertEquals(listOf("a", "b"), book.keys.toList())
    }

    @Test
    fun badFilesFailWithTheFileAndKeyInTheMessage() {
        fun fails(json: String, part: String) {
            val e = assertThrows(ContentException::class.java) { parseLineFile("test.json", json) }
            assertTrue("${e.message} ⊇ $part", e.message!!.contains("test.json") && e.message!!.contains(part))
        }
        fails("""{ "lines": { "a": { "dda": ["오타."] } } }""", "dda")
        fails("""{ "lines": { "a": { "same": "같음.", "sib": ["다름."] } } }""", "same")
        fails("""{ "lines": { "a": { "sib": [1] } } }""", "a")
        fails("""{ "lines": { "a": ["코치 없음."] } }""", "a")
        fails("""{ "about": "x", "line": { } }""", "line")
        fails("""{ "lines": { "a": """, "JSON")
    }

    @Test
    fun everyContentFileLoads() {
        assertEquals(24, Items.all.size)
        assertTrue(Lines.keys.isNotEmpty())
        assertEquals(3, ParkingGuide.TYPES.size)
        assertEquals(3, HillGuide.practices.size)
        assertEquals("왼쪽", Words.dirName("L"))
    }
}
