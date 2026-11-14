package com.barodrive.core.engine.golden

import com.barodrive.core.engine.CoachEngine
import com.barodrive.core.engine.CoachSession
import com.barodrive.core.engine.detect.Detectors
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 골든 회차 하나를 엔진 포트로 다시 돌려 프로토타입 출력과 맞춰 본다(README 9.1).
 * cfg·스냅숏·입력은 골든 JSON을 모델 타입으로 그대로 읽고(BaroJson), 출력·기록은 다시 JSON으로 써서 [GoldenCompare.firstDiff]로 본다.
 * 회차 기록은 세션 finish 결과 전체(리뷰 요약·자율 점수 포함 — 엔진 세션이 review.summarize·score.compute를 붙인다)를 본다.
 */
object GoldenReplay {
    /** 리뷰 요약·점수가 채우는 기록 칸([recordDiff]에서 `withReview = false`면 뺌). */
    private val REVIEW_KEYS = setOf(
        "tasks", "achievement", "qualified", "fewEvents", "nextTask", "good", "bad", "itemStats", "fadedNow", "fadedNote", "score", "progress",
    )

    /** 엔진 포트로 다시 돌린 회차: 걸음마다 출력(부른 순서)과 회차 기록. */
    class Replayed(val outputs: List<StepOutput>, val record: SessionRecord, val session: CoachSession) {
        val said get() = outputs.flatMap { it.say }

        /** 보인 카드(겹친 것 뺌, id로). */
        val cards get() = outputs.flatMap { it.cards }.distinctBy { it.id }
        val lastHints get() = outputs.last().hints
    }

    /** 골든 회차를 비교 없이 다시 돌린다(시나리오 시험 단언용). */
    fun replay(id: String): Replayed {
        val g = GoldenFixtures.load(id)
        val session = CoachEngine().createSession(BaroJson.decodeFromJsonElement(SessionConfig.serializer(), g.cfg))
        val outputs = g.calls.map { call ->
            when (call) {
                is GoldenCall.Step -> session.step(BaroJson.decodeFromJsonElement(VehicleSnapshot.serializer(), call.snapshot))
                is GoldenCall.Input -> session.input(BaroJson.decodeFromJsonElement(SessionInput.serializer(), call.event))
            }
        }
        return Replayed(outputs, session.finish(arrived = g.finishArg.bool("arrived")), session)
    }

    /** 비교 결과: 처음 다른 곳(없으면 null)과 세션 오류. */
    class Result(val id: String, val diff: String?, val errors: List<String>, val steps: Int)

    fun run(id: String): Result {
        val g = GoldenFixtures.load(id)
        val cfg = BaroJson.decodeFromJsonElement(SessionConfig.serializer(), g.cfg)
        val session = CoachEngine().createSession(cfg)
        infoDiff(g, session)?.let { return Result(id, it, session.errors, 0) }
        var n = 0
        for (call in g.calls) {
            val (label, out) = when (call) {
                is GoldenCall.Step -> "step #${call.index} t=${call.snapshot["t"]}" to
                    session.step(BaroJson.decodeFromJsonElement(VehicleSnapshot.serializer(), call.snapshot))
                is GoldenCall.Input -> "input after ${call.afterSteps} ${call.event}" to
                    session.input(BaroJson.decodeFromJsonElement(SessionInput.serializer(), call.event))
            }
            n++
            outputDiff(call.output, out)?.let { return Result(id, "$label: $it", session.errors, n) }
        }
        val rec = session.finish(arrived = g.finishArg.bool("arrived"))
        recordDiff(g.record, rec)?.let { return Result(id, "record: $it", session.errors, n) }
        return Result(id, null, session.errors, n)
    }

    /** 켜진 검출기 목록(아직 옮기지 않은 검출기는 뺌)과 꺼진 이유. */
    private fun infoDiff(g: EngineGolden, session: CoachSession): String? {
        val ported = Detectors.all.map { it.id }.toSet()
        val want = g.sessionInfo.array("detectors").map { it.jsonPrimitive.content }.filter { it in ported }
        val got = session.info().detectors
        if (want != got) return "detectors $want ≠ $got"
        val wantOff = g.sessionInfo.array("off").map { it.jsonObject }.filter { it.string("id") in ported }.map { it.string("id") to it.string("reason") }
        val gotOff = session.info().off.map { it.id to it.reason }
        return if (wantOff != gotOff) "off $wantOff ≠ $gotOff" else null
    }

    fun outputDiff(expected: JsonObject, actual: StepOutput): String? =
        GoldenCompare.firstDiff(normalize(expected), normalize(BaroJson.encodeToJsonElement(StepOutput.serializer(), actual)))

    fun recordDiff(expected: JsonObject, actual: SessionRecord, withReview: Boolean = true): String? {
        val a = BaroJson.encodeToJsonElement(SessionRecord.serializer(), actual).jsonObject
        return if (withReview) {
            GoldenCompare.firstDiff(normalize(expected), normalize(a))
        } else {
            GoldenCompare.firstDiff(normalize(stripReview(expected)), normalize(stripReview(a)))
        }
    }

    private fun stripReview(o: JsonObject): JsonObject {
        val m = LinkedHashMap(o)
        REVIEW_KEYS.forEach { m.remove(it) }
        (m["metrics"] as? JsonObject)?.let { m["metrics"] = JsonObject(it - "k4") }
        return JsonObject(m)
    }

    /**
     * 양쪽에 똑같이: 프로토타입만 쓰는 `speaker`를 빼고, 기본값 false인 칸(`pre`·`arrived`·`severe`·`virtual`)은 false면 뺀다
     * (Kotlin은 기본값도 쓰고 프로토타입은 안 쓰거나 false를 씀 — `virtual`은 rec.parking을 세워 두고 연습(PK-2)과 같이 쓰는 모델 칸이라
     * 실제 주차 안내 기록에는 프로토타입에 없다). null과 없는 키는 firstDiff가 같게 본다.
     */
    fun normalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(
            e.filter { (k, v) -> k != "speaker" && !(k in FALSE_DEFAULTS && v is JsonPrimitive && v !is JsonNull && v.booleanOrNull == false) }
                .mapValues { normalize(it.value) },
        )
        is JsonArray -> JsonArray(e.map(::normalize))
        else -> e
    }

    private val FALSE_DEFAULTS = setOf("pre", "arrived", "severe", "virtual")
}
