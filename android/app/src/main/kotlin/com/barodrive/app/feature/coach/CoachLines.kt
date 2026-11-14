package com.barodrive.app.feature.coach

import com.barodrive.app.ui.components.CoachFace
import com.barodrive.core.engine.content.LineRenderException
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.LineRef
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 정차 중 화면이 그리는 코치 대사 (프로토타입 coach.js `render`·`previewText`·`honorText`, 화면들의 `renderLine(…, { peek: true })`).
 * 대사 콘텐츠([Lines])로 지금 코치·호칭 문장을 그린다. 순번은 넘기지 않는다(peek). 대사가 없거나 그리지 못하면 ''.
 */
object CoachLines {
    /** [미리 듣기] 문장의 거리 {T}(coach.js `PREVIEW_T`): '깜빡이 늦음' 문장을 40미터로. */
    const val PREVIEW_T = 40

    /** 대사 한 줄. 없는 키·빠진 변수·없는 코치 문장이면 ''. */
    fun render(key: String, coach: Coach, vars: Map<String, Any?> = emptyMap(), honorific: Honorific? = null): String {
        if (!Lines.has(key)) return ""
        return try {
            Lines.render(key, coach, vars, honorific, peek = true)
        } catch (_: LineRenderException) {
            ""
        }
    }

    /** CO-1·ON-5 코치 줄 말풍선 = 그 코치의 '깜빡이 늦음' 문장(co.preview, {T} = 40, coach.js `previewText`). 호칭 없이. */
    fun previewText(coach: Coach): String = render(PREVIEW_KEY, coach, mapOf("T" to PREVIEW_T))

    /** 호칭 미리보기 문장(co.honor, coach.js `honorText`): 직접 쓰기는 쓰는 중인 글을 앞뒤 공백 빼고 8자까지 그대로. */
    fun honorText(coach: Coach, honorific: Honorific): String {
        val v = if (honorific.type == HonorificType.CUSTOM) {
            Honorific(HonorificType.CUSTOM, honorific.text.trim().take(Honorific.MAX_LENGTH))
        } else {
            Honorifics.normalize(honorific)
        }
        return render("co.honor", coach, emptyMap(), v)
    }

    /** [미리 듣기]가 말할 대사 키(coach.js `listen`). */
    const val PREVIEW_KEY = "co.preview"

    /** 대사 고르기(키 + JSON 변수)를 지금 코치·호칭으로 — 코스 추천 한마디 등. */
    fun render(line: LineRef, coach: Coach, honorific: Honorific? = null): String = render(line.key, coach, line.plainVars(), honorific)
}

/** [LineRef]의 JSON 변수 → 대사 변수 값(글자·정수·실수·참거짓). */
fun LineRef.plainVars(): Map<String, Any?> = vars.mapValues { (_, v) ->
    val p = v as? JsonPrimitive ?: return@mapValues null
    when {
        p is JsonNull -> null
        p.isString -> p.content
        else -> p.booleanOrNull ?: p.longOrNull ?: p.doubleOrNull ?: p.content
    }
}

/** 모델 코치 → 화면 얼굴(아바타·이름·말투). */
fun Coach.face(): CoachFace = when (this) {
    Coach.SIB -> CoachFace.Sibling
    Coach.MOM -> CoachFace.Mom
    Coach.DAD -> CoachFace.Dad
}

/** 화면 순서(coach.js `ORDER`): 형제/자매 · 엄마 · 아빠/배우자. */
val CoachOrder: List<Coach> = listOf(Coach.SIB, Coach.MOM, Coach.DAD)
