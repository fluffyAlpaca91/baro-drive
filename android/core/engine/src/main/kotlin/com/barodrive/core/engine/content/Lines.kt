package com.barodrive.core.engine.content

import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 앱의 코치 대사 (설계 6.6, 프로토타입 js/data/lines 폴더의 파일 12개 + core.js `renderLine`·`honorificText`·`speakDur`·`resetRotation`).
 *
 * 대사는 `resources/content/lines/<파일>.json`에 있고, 주차 단계 문장 `pk1.<종류>.<n>`은 `content/parking.json` 단계 표에서
 * 만든다(parking.js와 같음). 처음 쓸 때 한 번 읽는다(스레드 안전). 대사 JSON 모양:
 * `{ "about": 설명, "lines": { 키: { "sib": [문장…], "mom": […], "dad": […] } | { "same": 세 코치가 같은 문장 } } }`.
 */
object Lines {
    /** 대사 파일 이름, 프로토타입 index.html이 불러오는 순서. */
    internal val FILES = listOf(
        "prep", "drive", "predrive", "review", "safety3", "stage", "score", "parking", "hill", "weather", "license", "extra",
    )

    /** 앱 대사 표 전부. */
    val book: LineBook by lazy { load() }

    /** 대사 키 전부. */
    val keys: Set<String> get() = book.keys

    /** [key] 대사가 있는지 (프로토타입 `BaroData.lines[key]`). */
    fun has(key: String): Boolean = book.has(key)

    /** [key]의 [coach] 문장들을 자리 표시 그대로. 없으면 빈 목록. */
    fun sentences(key: String, coach: Coach): List<String> = book.sentences(key, coach)

    /** 앱 대사 한 줄을 그린다. 규칙은 [LineBook.render](core.js `renderLine`). */
    fun render(
        key: String,
        coach: Coach = Coach.DAD,
        vars: Map<String, Any?> = emptyMap(),
        honorific: Honorific? = null,
        rotation: LineRotation = LineRotation.shared,
        peek: Boolean = false,
        index: Int? = null,
    ): String = book.render(key, coach, vars, honorific, rotation, peek, index)

    /** 문장에 넣을 호칭 글자 (core.js `honorificText`). [Words.honorificText]와 같다. */
    fun honorificText(honorific: Honorific?, coach: Coach): String = Words.honorificText(honorific, coach)

    /** 문장을 말하는 시간(sim초) = 글자 수 × 0.15 + 0.4 (설계 2.6, core.js `speakDur`). 글자 수는 UTF-16 길이(JS `length`). */
    fun speakDur(text: String?): Double = (text ?: "").length * 0.15 + 0.4

    /** 공용 순번을 처음으로 (core.js `resetRotation`). */
    fun resetRotation() = LineRotation.shared.reset()

    private fun load(): LineBook {
        val all = LinkedHashMap<String, Map<Coach, List<String>>>()
        for (name in FILES) {
            val path = "content/lines/$name.json"
            val entries = parseLineFile(path, ContentFiles.read(path))
            val made = if (name == "parking") ParkingGuide.stepLines() else emptyMap()
            for ((key, byCoach) in entries.asSequence() + made.asSequence()) {
                if (key in all) throw ContentException("중복 등록: lines $key ($path)")
                all[key] = byCoach
            }
        }
        return LineBook.of(all)
    }
}

/**
 * 대사 파일 하나를 읽는다. 키마다 `{ "sib": […], "mom": […], "dad": […] }`(문장 하나면 글자만 써도 됨) 또는 `{ "same": 문장 }`.
 * 모르는 칸·코치, 글자가 아닌 문장은 [ContentException]. 코치가 빠진 키는 그 코치로 그릴 때 [LineRenderException]이 난다.
 */
internal fun parseLineFile(path: String, text: String): LinkedHashMap<String, Map<Coach, List<String>>> {
    val root = try {
        ContentFiles.json.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        throw ContentException("$path: JSON을 읽지 못했어요 — ${e.message}", e)
    }
    val obj = root as? JsonObject ?: throw ContentException("$path: 맨 위가 객체가 아니에요")
    val unknown = obj.keys - setOf("about", "lines")
    if (unknown.isNotEmpty()) throw ContentException("$path: 모르는 칸 $unknown")
    val lines = obj["lines"] as? JsonObject ?: throw ContentException("$path: lines 객체가 없어요")
    val out = LinkedHashMap<String, Map<Coach, List<String>>>()
    for ((key, value) in lines) {
        val entry = value as? JsonObject ?: throw ContentException("$path: ${key}의 값이 객체가 아니에요")
        out[key] = if ("same" in entry) {
            if (entry.size != 1) throw ContentException("$path: $key — same에는 다른 칸을 같이 쓰지 않아요")
            val same = sentence(path, key, entry.getValue("same"))
            Coach.entries.associateWith { listOf(same) }
        } else {
            entry.entries.associate { (name, list) ->
                val coach = Coach.entries.firstOrNull { it.key == name }
                    ?: throw ContentException("$path: $key — 모르는 코치 '$name'(sib·mom·dad·same)")
                coach to if (list is JsonArray) list.map { sentence(path, key, it) } else listOf(sentence(path, key, list))
            }
        }
    }
    return out
}

private fun sentence(path: String, key: String, element: JsonElement): String {
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw ContentException("$path: $key — 문장은 글자여야 해요: $element")
    return primitive.content
}
