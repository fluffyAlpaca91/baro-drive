package com.barodrive.core.engine.content

import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 대사 그리기 규칙 (프로토타입 tests/unit/t1c_engine.test.js '대사' 묶음, 설계 6.6·6.6.1·2.6).
 * 프로토타입처럼 시험용 대사(t1c.*)를 따로 만들어 쓴다.
 */
class LineRenderTest {
    private val book = LineBook.of(
        mapOf(
            "t1c.rot" to lines(sib = listOf("하나.", "둘."), mom = listOf("하나!", "둘!"), dad = listOf("첫째 문장.", "둘째 문장.")),
            "t1c.hon" to lines(
                sib = listOf("오, 웬일. 잘했네."),
                mom = listOf("어머 잘했다{ 호칭}! 딱 좋았어!"),
                dad = listOf("{호칭}, 오늘 고생 많았어."),
            ),
            "t1c.hon2" to lines(sib = listOf("됐다."), mom = listOf("오늘 끝! {호칭}, 고생했어."), dad = listOf("그래, {호칭}!")),
            "t1c.vars" to lines(
                sib = listOf("{N}미터 전. {dir}으로 가는 길이야."),
                mom = listOf("{wx} {L}까지야!"),
                dad = listOf("{N}미터 전에 켰어."),
            ),
            "t1c.sibOnly" to mapOf(Coach.SIB to listOf("형제만.")),
        ),
    )

    private val daughter = Honorific(HonorificType.DAUGHTER)
    private val none = Honorific(HonorificType.NONE)

    @Test
    fun sameKeySentencesAlternatePerRotationAndPeekDoesNotAdvance() {
        val rot = LineRotation()
        fun r(peek: Boolean = false, index: Int? = null) = book.render("t1c.rot", Coach.DAD, emptyMap(), null, rot, peek, index)

        assertEquals("첫째 문장.", r(peek = true))
        assertEquals("첫째 문장.", r())
        assertEquals("둘째 문장.", r())
        assertEquals("첫째 문장.", r())
        assertEquals("index로 고르면 순번을 안 넘김", "둘째 문장.", r(index = 1))
        assertEquals("둘째 문장.", r())
        assertEquals("음수 index는 문장 수로 나눈 나머지", "둘째 문장.", r(index = -1))
        assertEquals("넘는 index도 나머지", "첫째 문장.", r(index = 2))
        assertEquals(4, rot.count("t1c.rot"))
    }

    @Test
    fun eachSessionHasItsOwnRotation() {
        val a = SessionLines(Coach.SIB, null, book)
        val b = SessionLines(Coach.SIB, null, book)

        assertEquals("하나.", a.render("t1c.rot"))
        assertEquals("둘.", a.render("t1c.rot"))
        assertEquals("하나.", b.render("t1c.rot"))
        assertEquals("하나.", a.render("t1c.rot", peek = true))
    }

    @Test
    fun withoutARotationTheSharedOneIsUsedAndResetStartsOver() {
        Lines.resetRotation()
        assertEquals("하나!", book.render("t1c.rot", Coach.MOM))
        assertEquals("둘!", book.render("t1c.rot", Coach.MOM))
        assertEquals(2, LineRotation.shared.count("t1c.rot"))

        Lines.resetRotation()
        assertEquals("하나!", book.render("t1c.rot", Coach.MOM))
        Lines.resetRotation()
    }

    @Test
    fun honorificIsInsertedOrRemovedBySection661() {
        fun r(key: String, coach: Coach, hon: Honorific?) = book.render(key, coach, emptyMap(), hon, index = 0)

        assertEquals("어머 잘했다 우리 딸! 딱 좋았어!", r("t1c.hon", Coach.MOM, daughter))
        assertEquals("어머 잘했다! 딱 좋았어!", r("t1c.hon", Coach.MOM, none))
        assertEquals("어머 잘했다! 딱 좋았어!", r("t1c.hon", Coach.MOM, null))
        assertEquals("어머 잘했다 우리 아들! 딱 좋았어!", r("t1c.hon", Coach.MOM, Honorific(HonorificType.SON)))
        assertEquals("어머 잘했다 지은아! 딱 좋았어!", r("t1c.hon", Coach.MOM, Honorific(HonorificType.CUSTOM, "  지은아  ")))
        assertEquals(
            "직접 쓴 말은 8자까지",
            "어머 잘했다 가나다라마바사아! 딱 좋았어!",
            r("t1c.hon", Coach.MOM, Honorific(HonorificType.CUSTOM, "가나다라마바사아자차")),
        )
        assertEquals("문장 맨 앞", "우리 딸, 오늘 고생 많았어.", r("t1c.hon", Coach.DAD, daughter))
        assertEquals("맨 앞이면 뒤 쉼표·공백도 지움", "오늘 고생 많았어.", r("t1c.hon", Coach.DAD, none))
        assertEquals("오늘 끝! 우리 딸, 고생했어.", r("t1c.hon2", Coach.MOM, daughter))
        assertEquals("둘째 문장 맨 앞", "오늘 끝! 고생했어.", r("t1c.hon2", Coach.MOM, none))
        assertEquals("그래, 우리 딸!", r("t1c.hon2", Coach.DAD, daughter))
        assertEquals("바로 앞 쉼표·공백을 지움", "그래!", r("t1c.hon2", Coach.DAD, none))

        assertEquals("형제/자매는 호칭을 쓰지 않음", "", Lines.honorificText(daughter, Coach.SIB))
        assertEquals("우리 딸", Lines.honorificText(daughter, Coach.MOM))
        assertEquals("", Lines.honorificText(Honorific(HonorificType.CUSTOM, ""), Coach.MOM))
        assertEquals("", Lines.honorificText(null, Coach.DAD))
        assertEquals(8, Words.honorificMaxLength)
    }

    @Test
    fun customHonorificIsInsertedLiterally() {
        // 프로토타입은 JS replace의 '$&'를 특별 글자로 읽어 이 경우 '대사 변수 빠짐'으로 멈췄다. 앱은 글자 그대로 넣는다.
        val text = book.render("t1c.hon", Coach.MOM, emptyMap(), Honorific(HonorificType.CUSTOM, "\$&민지"), index = 0)
        assertEquals("어머 잘했다 \$&민지! 딱 좋았어!", text)
    }

    @Test
    fun variablesAreFormattedAndMissingOnesFail() {
        fun r(coach: Coach, vars: Map<String, Any?>, rot: LineRotation = LineRotation()) =
            book.render("t1c.vars", coach, vars, null, rot)

        assertEquals("50미터 전에 켰어.", r(Coach.DAD, mapOf("N" to 57)))
        assertEquals("40미터 전에 켰어.", r(Coach.DAD, mapOf("N" to 40)))
        assertEquals("30미터 전. 오른쪽으로 가는 길이야.", r(Coach.SIB, mapOf("N" to 39.9, "dir" to "R")))
        assertEquals("120미터 전. 왼쪽으로 가는 길이야.", r(Coach.SIB, mapOf("N" to 120, "dir" to "LEFT")))
        assertEquals("비 오는 날은 40까지야!", r(Coach.MOM, mapOf("wx" to "rain", "L" to 40)))
        assertEquals("오늘 같은 날은 25까지야!", r(Coach.MOM, mapOf("wx" to "severe", "L" to 25)))

        val missingN = assertThrows(LineRenderException::class.java) { r(Coach.DAD, emptyMap()) }
        assertEquals("대사 변수 빠짐: t1c.vars {N}", missingN.message)
        val missingL = assertThrows(LineRenderException::class.java) { r(Coach.MOM, mapOf("wx" to "rain")) }
        assertTrue(missingL.message!!.contains("{L}"))
        val noKey = assertThrows(LineRenderException::class.java) { book.render("t1c.none", Coach.DAD) }
        assertEquals("대사 없음: t1c.none", noKey.message)
        val noCoach = assertThrows(LineRenderException::class.java) { book.render("t1c.sibOnly", Coach.MOM) }
        assertEquals("대사 없음: t1c.sibOnly (mom)", noCoach.message)

        // 오류가 나면 순번을 넘기지 않는다
        val rot = LineRotation()
        assertThrows(LineRenderException::class.java) { r(Coach.DAD, emptyMap(), rot) }
        assertEquals(0, rot.count("t1c.vars"))
    }

    @Test
    fun numbersAndDirectionsReadLikeThePrototype() {
        fun dad(n: Any?) = book.render("t1c.vars", Coach.DAD, mapOf("N" to n), index = 0)
        fun mom(l: Any?, wx: Any = "snow") = book.render("t1c.vars", Coach.MOM, mapOf("wx" to wx, "L" to l), index = 0)
        fun sib(dir: Any?) = book.render("t1c.vars", Coach.SIB, mapOf("N" to 50, "dir" to dir), index = 0)

        assertEquals("{N}은 0 아래로 내려가지 않음", "0미터 전에 켰어.", dad(-5))
        assertEquals("120미터 전에 켰어.", dad(123.4))
        assertEquals("숫자가 아니면 그대로", "55미터 전에 켰어.", dad("55"))
        assertEquals("눈 오는 날은 40.3까지야!", mom(40.25))
        assertEquals("정수가 되면 소수점 없이", "눈 오는 날은 13까지야!", mom(12.96))
        assertEquals("눈 오는 날은 40까지야!", mom(40.0))
        assertEquals("눈 오는 날은 123456789.1까지야!", mom(123456789.12))
        assertEquals("모르는 날씨는 글자 그대로", "fog 30까지야!", mom(30, wx = "fog"))
        assertEquals("50미터 전. 오른쪽으로 가는 길이야.", sib("right"))
        assertEquals("열거형 이름도 방향으로", "50미터 전. 왼쪽으로 가는 길이야.", sib(Side.LEFT))
        assertEquals("모르는 방향은 글자 그대로", "50미터 전. 앞으로 가는 길이야.", sib("앞"))
        assertThrows(LineRenderException::class.java) { dad(Double.NaN) }
        assertThrows(LineRenderException::class.java) { dad(Double.POSITIVE_INFINITY) }
        assertThrows(LineRenderException::class.java) { dad(null) }
    }

    @Test
    fun speakingTimeIsLengthTimesPointFifteenPlusPointFour() {
        assertEquals(r1(4 * 0.15 + 0.4), r1(Lines.speakDur("좋았어.")), 0.0)
        assertEquals(r1(16 * 0.15 + 0.4), r1(Lines.speakDur("좋았어, 50미터 전에 켰어.")), 0.0)
        assertEquals(0.4, Lines.speakDur(""), 0.0)
        assertEquals(0.4, Lines.speakDur(null), 0.0)
    }

    @Test
    fun wordsForPlaceholders() {
        assertEquals("세", Words.numWord(3))
        assertEquals("영", Words.numWord(0))
        assertEquals("표 밖이면 숫자 그대로", "11", Words.numWord(11))
        assertEquals("왼쪽", Words.dirName("L"))
        assertEquals("오른쪽", Words.dirName("R"))
        assertEquals("오른쪽", Words.dirName(null))
        assertEquals("비 오는 날은", Words.wx["rain"])
    }

    @Test
    fun booksMergeButRefuseDuplicateKeys() {
        val extra = LineBook.of(mapOf("t1c.extra" to lines(listOf("가."), listOf("나."), listOf("다."))))
        val merged = Lines.book + extra

        assertTrue(merged.has("t1c.extra") && merged.has("c2.ok"))
        assertEquals("다.", merged.render("t1c.extra", Coach.DAD, peek = true))
        val dup = assertThrows(IllegalArgumentException::class.java) { merged + extra }
        assertEquals("중복 등록: lines t1c.extra", dup.message)
    }

    @Test
    fun theAppBookIsLoadedOnceAcrossThreads() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val books = pool.invokeAll(List(16) { Callable { Lines.book } }).map { it.get() }
            books.forEach { assertSame(books.first(), it) }
        } finally {
            pool.shutdown()
        }
    }

    private enum class Side { LEFT }

    private fun r1(x: Double) = (x * 10).roundToInt() / 10.0
}

/** 코치 셋 문장 묶음(시험용). */
internal fun lines(sib: List<String>, mom: List<String>, dad: List<String>): Map<Coach, List<String>> =
    mapOf(Coach.SIB to sib, Coach.MOM to mom, Coach.DAD to dad)
