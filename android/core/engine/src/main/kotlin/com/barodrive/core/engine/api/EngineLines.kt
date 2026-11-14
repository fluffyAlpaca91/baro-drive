package com.barodrive.core.engine.api

import com.barodrive.core.engine.content.LineRenderException
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.LineRef
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 대사 (설계 6.6, 6.1.1 `lines.render(itemKey, coach, vars, honorific)`, 프로토타입 core.js `renderLine`).
 * 대사 콘텐츠(프로토타입 data/lines 폴더 → 엔진 리소스 JSON)를 코치·호칭·변수로 그린다. 정차 중 말(p.tasks·rv.end.good·pk.done …)도 이것으로 그린다.
 */
object EngineLines {
    /**
     * 문장 하나 (core.js `renderLine(key, coach, vars, honorific, { rotation, peek, index })`).
     * 규칙: 같은 키의 문장이 2개면 번갈아([RenderOptions.rotation] 키 → 순번, 없으면 공용 순번) · [RenderOptions.peek]이면 순번을 안 넘김 ·
     * [RenderOptions.index]면 그 문장(순번 그대로) · `{호칭}`·`{ 호칭}`은 6.6.1(형제/자매는 늘 뺌) · `{N}`은 10m 내림 ·
     * `{dir}`은 'L'/'R' → 왼쪽/오른쪽 · `{wx}`는 rain·snow·severe → 문구 · 숫자는 정수면 그대로, 아니면 소수 한 자리.
     * 키·코치 문장이 없거나 변수가 빠지면 [LineRenderException](프로토타입과 같음) — 이때 순번은 넘기지 않는다.
     */
    fun render(
        key: String,
        coach: Coach = Coach.DAD,
        vars: Map<String, Any?> = emptyMap(),
        honorific: Honorific? = null,
        opts: RenderOptions = RenderOptions(),
    ): String {
        val rot = opts.rotation
        val index = opts.index
        if (rot == null || index != null) return Lines.render(key, coach, vars, honorific, peek = opts.peek, index = index)
        val n = rot[key] ?: 0
        val text = Lines.render(key, coach, vars, honorific, index = n)
        if (!opts.peek) rot[key] = n + 1
        return text
    }

    /** [LineRef](대사 키 + JSON 변수)를 그린다 — 코스 추천 한마디·점수 대사 등. */
    fun render(line: LineRef, coach: Coach = Coach.DAD, honorific: Honorific? = null, opts: RenderOptions = RenderOptions()): String =
        render(line.key, coach, line.vars.mapValues { (_, v) -> (v as? JsonPrimitive)?.toPlain() }, honorific, opts)

    /** 공용 번갈아 쓰기 순번을 비운다(core.js `resetRotation`, 테스트·새 회차). */
    fun resetRotation() = Lines.resetRotation()

    /** JSON 값 → 대사 변수 값(글자·숫자·참거짓). */
    private fun JsonPrimitive.toPlain(): Any? = when {
        this is JsonNull -> null
        isString -> content
        else -> booleanOrNull ?: doubleOrNull ?: content
    }
}
