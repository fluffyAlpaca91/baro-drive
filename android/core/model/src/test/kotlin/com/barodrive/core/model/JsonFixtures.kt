package com.barodrive.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * 테스트 픽스처 도우미. `fixtures/proto-*.json`은 프로토타입을 node에서 실제로 돌려 받은 값이다
 * (2026-11-11, prototype/tests/lib-sim.js `runScenarioHeadless('S2-B', normal)` · 데모 상태 build — 오늘 2026-11-11).
 */
object JsonFixtures {
    fun text(name: String): String =
        requireNotNull(JsonFixtures::class.java.getResourceAsStream("/fixtures/$name")) { "픽스처가 없어요: $name" }
            .use { it.readBytes().decodeToString() }

    fun tree(name: String): JsonElement = BaroJson.parseToJsonElement(text(name))

    /**
     * 원본 JSON의 값이 모델을 거쳐 다시 쓴 JSON에 그대로 있는지 본다. 원본에서 null인 칸은 '없음'과 같게 본다.
     * 숫자는 값으로 비교한다(28 = 28.0). 결과는 "경로|종류" 목록(배열 자리는 []), 종류: missing · type · value.
     */
    fun lost(original: JsonElement, rewritten: JsonElement): Set<String> {
        val out = linkedSetOf<String>()
        walk("$", original, rewritten, out)
        return out
    }

    /** 원본을 [serializer]로 읽고 다시 써서 [lost]를 돌려준다. 읽은 값을 다시 읽어도 같은지도 확인한다. */
    fun <T> roundTripLoss(serializer: KSerializer<T>, original: JsonElement): Set<String> {
        val value = BaroJson.decodeFromJsonElement(serializer, original)
        val rewritten = BaroJson.encodeToJsonElement(serializer, value)
        val again = BaroJson.decodeFromJsonElement(serializer, rewritten)
        check(value == again) { "다시 읽은 값이 달라요" }
        return lost(original, rewritten)
    }

    private fun walk(path: String, a: JsonElement?, b: JsonElement?, out: MutableSet<String>) {
        if (a == null || a is JsonNull) return
        when (a) {
            is JsonObject -> {
                if (b !is JsonObject) {
                    out += "$path|type"
                    return
                }
                for ((k, v) in a) walk("$path.$k", v, b[k], out)
            }
            is JsonArray -> {
                if (b !is JsonArray || b.size != a.size) {
                    out += "$path|type"
                    return
                }
                a.forEachIndexed { i, v -> walk("$path[]", v, b[i], out) }
            }
            is JsonPrimitive -> when {
                b == null || b is JsonNull -> out += "$path|missing"
                b !is JsonPrimitive -> out += "$path|type"
                !samePrimitive(a, b) -> out += "$path|value"
            }
        }
    }

    private fun samePrimitive(a: JsonPrimitive, b: JsonPrimitive): Boolean {
        if (!a.isString && !b.isString) {
            val x = a.doubleOrNull
            val y = b.doubleOrNull
            if (x != null && y != null) return x == y
        }
        return a.isString == b.isString && a.content == b.content
    }
}
