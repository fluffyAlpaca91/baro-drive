package com.barodrive.core.engine.content

import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific

/**
 * 대사 표 (프로토타입 `BaroData.lines`, 설계 6.6): 키 → 코치 → 문장 1~2개. 그리기는 [render](core.js `renderLine`).
 * 앱이 쓰는 표는 [Lines.book](resources/content/lines 폴더의 JSON)이고, 시험용 표는 [of]로 만든다.
 * 한 번 만들면 바뀌지 않는다(여러 스레드에서 같이 써도 된다).
 */
class LineBook private constructor(private val entries: Map<String, Map<Coach, List<String>>>) {

    /** 대사 키 전부(파일 순서). */
    val keys: Set<String> get() = entries.keys

    /** [key] 대사가 있는지. */
    fun has(key: String): Boolean = key in entries

    /** [key]의 [coach] 문장들을 자리 표시 그대로(없으면 빈 목록). score.js처럼 글 조각을 꺼낼 때 쓴다. */
    fun sentences(key: String, coach: Coach): List<String> = entries[key]?.get(coach).orEmpty()

    /**
     * 대사 한 줄을 그린다 (core.js `renderLine(key, coach, vars, honorific, { rotation, peek, index })`).
     *
     * - 같은 키에 문장이 둘이면 [rotation] 순번대로 번갈아 쓴다. [peek]이면 순번을 넘기지 않는다(미리 보기).
     *   [index]를 주면 그 문장(음수·넘는 값은 문장 수로 나눈 나머지)을 쓰고 순번은 그대로다.
     * - `{호칭}`·`{ 호칭}`은 설계 6.6.1 규칙([Words.honorificText] — 형제/자매는 늘 뺌), 다른 `{이름}`은 [vars] 값으로 채운다
     *   (`{N}` 10m 내림 · `{dir}` 'L'/'R' → 왼쪽/오른쪽 · `{wx}` rain/snow/severe → 글자 · 숫자는 JS 글자처럼).
     * - 키가 없거나 그 코치 문장이 없거나 변수가 빠지면(null · 유한하지 않은 숫자) [LineRenderException]. 이때 순번은 넘기지 않는다.
     */
    fun render(
        key: String,
        coach: Coach = Coach.DAD,
        vars: Map<String, Any?> = emptyMap(),
        honorific: Honorific? = null,
        rotation: LineRotation = LineRotation.shared,
        peek: Boolean = false,
        index: Int? = null,
    ): String {
        val entry = entries[key] ?: throw LineRenderException("대사 없음: $key")
        val list = entry[coach]
        if (list.isNullOrEmpty()) throw LineRenderException("대사 없음: $key (${coach.key})")
        if (index != null) return draw(key, list[Math.floorMod(index, list.size)], coach, vars, honorific)
        synchronized(rotation) {
            val n = rotation.count(key)
            val text = draw(key, list[n % list.size], coach, vars, honorific)
            if (!peek) rotation.set(key, n + 1)
            return text
        }
    }

    /** 두 표를 합친다. 같은 키가 있으면 하나도 넣지 않고 던진다(ns.js `Baro.lines`의 '중복 등록'). */
    operator fun plus(other: LineBook): LineBook {
        val dup = other.entries.keys.firstOrNull { it in entries }
        require(dup == null) { "중복 등록: lines $dup" }
        return LineBook(LinkedHashMap(entries).apply { putAll(other.entries) })
    }

    private fun draw(key: String, sentence: String, coach: Coach, vars: Map<String, Any?>, honorific: Honorific?): String {
        val text = applyHonorific(sentence, Words.honorificText(honorific, coach))
        return VAR_RE.replace(text) { match ->
            val name = match.groupValues[1]
            val value = vars[name]
            if (isMissingVar(value)) throw LineRenderException("대사 변수 빠짐: $key {$name}")
            formatVar(name, value!!)
        }
    }

    companion object {
        /** 대사 표를 만든다. 문장 목록은 복사해 둔다(시험용 대사, 합칠 표 등). */
        fun of(entries: Map<String, Map<Coach, List<String>>>): LineBook =
            LineBook(entries.entries.associateTo(LinkedHashMap()) { (key, byCoach) -> key to byCoach.mapValues { it.value.toList() } })
    }
}

/** 대사를 그리지 못함: 키가 없음 · 그 코치 문장이 없음 · 변수가 빠짐 (core.js `renderLine`이 던지는 Error). */
class LineRenderException(message: String) : IllegalArgumentException(message)

/** 대사 JSON·오류 글에 쓰는 코치 이름 'sib'·'mom'·'dad'. */
internal val Coach.key: String get() = name.lowercase()
