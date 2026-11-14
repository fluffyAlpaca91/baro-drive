package com.barodrive.core.engine.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * 프로토타입이 만든 정답 파일(골든) 읽기 — 엔진 쪽.
 *
 * 만드는 법·파일 모양·비교하는 법은 `prototype/tools/golden/README.md`에 있다. 테스트 리소스 `golden/manifest.json`이
 * 회차 목록이고, 회차마다 `golden/<id>.json.gz`(gzip JSON)가 있다. 모델 타입이 아직 바뀌는 중이라 값은 kotlinx.serialization
 * [JsonElement] 그대로 준다(필드 이름은 프로토타입 JS와 같다). 줄여 쓴 스트림(스냅숏·엔진 출력)은 여기서 풀어 준다.
 *
 * ```
 * val g = GoldenFixtures.load("m1-S1-B-normal")
 * val session = engine.createSession(toConfig(g.cfg))
 * for (call in g.calls) when (call) {
 *     is GoldenCall.Step -> compare(call.output, session.step(toSnapshot(call.snapshot)))
 *     is GoldenCall.Input -> compare(call.output, session.input(toInput(call.event)))
 * }
 * compare(g.record, session.finish(arrived = g.finishArg.bool("arrived")))
 * ```
 */
object GoldenFixtures {
    const val MANIFEST_FORMAT = "baro-golden-manifest/1"
    const val FORMAT = "baro-golden-engine/1"
    private const val DIR = "/golden/"

    /** manifest.json 전체. */
    val manifest: JsonObject by lazy { Json.parseToJsonElement(readText("manifest.json")).jsonObject }

    /** 회차 목록(manifest `runs`, 만든 순서). */
    val runs: List<GoldenRun> by lazy { manifest.array("runs").map { GoldenRun(it.jsonObject) } }

    fun run(id: String): GoldenRun = runs.firstOrNull { it.id == id } ?: error("골든 회차 없음: $id")

    /** 회차 하나를 읽는다(gzip을 풀고 JSON으로). */
    fun load(id: String): EngineGolden = EngineGolden(Json.parseToJsonElement(readGzip(run(id).file)).jsonObject)

    fun readText(name: String): String = open(name).use { it.readBytes().decodeToString() }

    fun readGzip(name: String): String = GZIPInputStream(open(name)).use { it.readBytes().decodeToString() }

    private fun open(name: String) =
        GoldenFixtures::class.java.getResourceAsStream(DIR + name) ?: error("테스트 리소스 없음: golden/$name")
}

/** manifest `runs` 한 줄. [json]에 나머지 값(note, simSec, said, blocked, sha256 …)이 있다. */
class GoldenRun(val json: JsonObject) {
    val id: String = json.string("id")
    val file: String = json.string("file")
    val mode: Int = json.int("mode")
    val scenario: String = json.string("scenario")
    /** 'poor' 서툰 · 'normal' 보통 · 'good' 능숙. */
    val skill: String = json.string("skill")
    /** 회차 기록의 날씨 'clear' · 'rain' · 'snow'. */
    val weather: String = json.string("weather")
    /** 아주 나쁜 날 'ice' · 'fog' | null. */
    val severe: String? = json.stringOrNull("severe")
    /** 못 읽는 신호(cfg.caps에서 false인 키). */
    val capsOff: List<String> = json.array("capsOff").map { it.jsonPrimitive.content }
    /** 누른 주입 id(시연 패널 '코칭 이벤트 넣기'). */
    val injections: List<String> = json.array("injections").map { it.jsonPrimitive.content }
    /** 시뮬레이터 틱 수(세션 전 틱 포함). */
    val ticks: Int = json.int("ticks")
    /** session.step 횟수. */
    val steps: Int = json.int("steps")
    /** session.input 횟수. */
    val inputs: Int = json.int("inputs")

    override fun toString(): String = id
}

/** 회차 하나(엔진 파일). 키 이름은 README 7장. */
class EngineGolden(val json: JsonObject) {
    val id: String = json.string("id")
    val mode: Int = json.int("mode")
    val scenario: String = json.string("scenario")
    val skill: String = json.string("skill")
    /** buildConfig·review.compute·score.compute·fading.update에 넘긴 프로필(설계 8.1). */
    val profile: JsonObject = json.obj("profile")
    /** buildConfig의 나머지 인자 { mode, prep, panelState }. */
    val buildConfig: JsonObject = json.obj("buildConfig")
    /** createSession에 넘긴 cfg(= buildConfig 결과). */
    val cfg: JsonObject = json.obj("cfg")
    /** session.info() — 켜진 검출기 id와 꺼진 이유. */
    val sessionInfo: JsonObject = json.obj("sessionInfo")
    /** 첫 step의 시뮬레이터 틱 번호(재생 시작 뒤 1부터). step i의 틱 = firstTick + i. */
    val firstTick: Int = json.int("firstTick")
    val steps: Int = json.int("steps")
    val inputs: List<GoldenCall.Input> = json.array("inputs").map {
        val o = it.jsonObject
        GoldenCall.Input(o.int("afterSteps"), o.doubleOrNull("t"), o.obj("ev"), o.obj("out"))
    }
    /** finish에 넘긴 값 { arrived }. 마지막 step 뒤에 부른다. */
    val finishArg: JsonObject = json.obj("finish").obj("arg")
    /** session.finish(…) 결과(SessionRecord, 설계 8.1 — 리뷰 요약과 모드 5 점수가 합쳐진 것). */
    val record: JsonObject = json.obj("record")
    /** review.compute(record, profile). */
    val review: JsonObject = json.obj("review")
    /** score.compute(record, profile) — 모드 5만, 그 밖은 null. */
    val score: JsonObject? = json["score"] as? JsonObject
    /** fading.update(profile 복사본, record) → { mode, state, fadedNow, restoredNow }. */
    val fading: JsonObject = json.obj("fading")

    /** session.step에 넘긴 스냅숏(풀어 놓은 것), step 순서. */
    val snapshots: List<JsonObject> by lazy { GoldenStreams.decodeSnapshots(json.array("snapshots")) }

    /** step마다 엔진 출력 { t, say, blocked, cards, hints, events, waiting, turn, muted } (풀어 놓은 것). */
    val outputs: List<JsonObject> by lazy { GoldenStreams.decodeOutputs(json.array("outputs"), snapshots) }

    /** step·input을 부른 순서 그대로. 이 순서로 엔진에 넣고 [GoldenCall.output]과 맞춰 본다. */
    val calls: List<GoldenCall> by lazy {
        val out = ArrayList<GoldenCall>(steps + inputs.size)
        var next = 0
        for (i in 0..steps) {
            while (next < inputs.size && inputs[next].afterSteps == i) out += inputs[next++]
            if (i < steps) out += GoldenCall.Step(i, firstTick + i, snapshots[i], outputs[i])
        }
        check(next == inputs.size) { "$id: input의 afterSteps가 순서대로가 아니에요" }
        out
    }

    /** 말한 것 전부(부른 순서). = record.utterances 중 spoken인 것과 같은 순서. */
    val said: List<JsonObject> by lazy { calls.flatMap { c -> c.output.array("say").map { it.jsonObject } } }

    /** step·input 출력에서 막힌 말(부른 순서). finish 때 막힌 것은 record.utterances에만 있다. */
    val blocked: List<JsonObject> by lazy { calls.flatMap { c -> c.output.array("blocked").map { it.jsonObject } } }

    override fun toString(): String = id
}

/** 엔진을 부른 한 번. */
sealed interface GoldenCall {
    val output: JsonObject

    /** session.step(snapshot) — [index]번째 step(0부터), [tick] = 시뮬레이터 틱 번호. */
    data class Step(val index: Int, val tick: Int, val snapshot: JsonObject, override val output: JsonObject) : GoldenCall

    /** session.input(event) — step을 [afterSteps]번 부른 뒤에 부름. [t] = 그때 세션 시각(마지막 step의 t, 첫 step 전이면 null). */
    data class Input(val afterSteps: Int, val t: Double?, val event: JsonObject, override val output: JsonObject) : GoldenCall
}

/** 줄여 쓴 스트림 풀기(README 7.2·7.3). export.js의 decodeSnapshots·decodeOutputs와 같은 규칙. */
object GoldenStreams {
    private const val DEL = "\$del"
    val OUTPUT_LISTS = listOf("say", "blocked", "events")
    private val OUTPUT_ORDER = listOf("t", "say", "blocked", "cards", "hints", "events", "waiting", "turn", "muted")

    /** 프로토타입 round1(x) = Math.round(x × 10) / 10 (반올림은 +∞ 쪽 — kotlin.math.round(짝수 쪽)와 다르다). */
    fun round1(x: Double): Double = Math.round(x * 10).toDouble() / 10.0

    /** 첫 줄은 스냅숏 그대로, 그 뒤는 앞 스냅숏에서 바뀐 키만(nav는 그 안에서 한 번 더). t가 없으면 round1(앞 t + 0.1). */
    fun decodeSnapshots(encoded: JsonArray): List<JsonObject> {
        val out = ArrayList<JsonObject>(encoded.size)
        for (element in encoded) {
            val e = element.jsonObject
            val prev = out.lastOrNull()
            if (prev == null) {
                out += e
                continue
            }
            val del = e.deleted()
            val cur = LinkedHashMap<String, JsonElement>(prev)
            del.forEach { cur.remove(it) }
            for ((k, v) in e) {
                if (k == DEL) continue
                val p = prev[k]
                cur[k] = if (k == "nav" && p is JsonObject && v is JsonObject) applyKeys(p, v) else v
            }
            if ("t" !in e && "t" !in del) cur["t"] = number(round1(prev.double("t") + 0.1))
            out += JsonObject(cur)
        }
        return out
    }

    /** 걸음 i마다 { i, …바뀐 것 }: say·blocked·events는 그 걸음 것(없으면 빈 배열), 그 밖은 바뀔 때까지 앞 값을 이어 쓴다. */
    fun decodeOutputs(encoded: JsonArray, snapshots: List<JsonObject>): List<JsonObject> {
        val out = ArrayList<JsonObject>(snapshots.size)
        var state = LinkedHashMap<String, JsonElement>()
        var j = 0
        for (i in snapshots.indices) {
            val e = encoded.getOrNull(j)?.jsonObject?.takeIf { it.int("i") == i }
            if (e != null) {
                j++
                state = LinkedHashMap(state)
                e.deleted().forEach { state.remove(it) }
                for ((k, v) in e) if (k != "i" && k != DEL && k != "t" && k !in OUTPUT_LISTS) state[k] = v
            }
            val o = LinkedHashMap<String, JsonElement>()
            o["t"] = e?.get("t") ?: snapshots[i].getValue("t")
            for (k in OUTPUT_LISTS) o[k] = e?.get(k) ?: JsonArray(emptyList())
            o.putAll(state)
            val ordered = LinkedHashMap<String, JsonElement>()
            for (k in OUTPUT_ORDER) o[k]?.let { ordered[k] = it }
            for ((k, v) in o) if (k !in ordered) ordered[k] = v
            out += JsonObject(ordered)
        }
        check(j == encoded.size) { "출력 줄 ${encoded.size - j}개가 걸음 번호 순서를 벗어났어요" }
        return out
    }

    /** JS가 쓰는 모양으로: 정수면 "405", 아니면 Double 그대로. */
    fun number(x: Double): JsonPrimitive = if (x == Math.rint(x) && abs(x) < 1e15) JsonPrimitive(x.toLong()) else JsonPrimitive(x)

    private fun applyKeys(prev: JsonObject, d: JsonObject): JsonObject {
        val cur = LinkedHashMap<String, JsonElement>(prev)
        d.deleted().forEach { cur.remove(it) }
        for ((k, v) in d) if (k != DEL) cur[k] = v
        return JsonObject(cur)
    }

    private fun JsonObject.deleted(): List<String> = (this[DEL] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
}

/** 골든 값과 포트 결과 맞춰 보기(README 9장). */
object GoldenCompare {
    /** 시각(말·막힌 말·판정·카드의 t, createdAt …) 허용 차이(초). 스냅숏이 0.1초마다라 같은 틱인지만 본다. */
    const val TIME_TOLERANCE = 0.05

    /** 그 밖의 수 허용 차이. */
    const val NUMBER_TOLERANCE = 1e-6

    /**
     * 처음 다른 곳(경로와 두 값)을 글로, 같으면 null. 키 순서는 보지 않고, 없는 키와 null은 같다고 본다(JS undefined).
     * 수는 [tolerance] 안이면 같다(정수·실수 표기 차이 "405" · "405.0"도 같다).
     */
    fun firstDiff(expected: JsonElement?, actual: JsonElement?, tolerance: Double = NUMBER_TOLERANCE, path: String = "$"): String? {
        val e = expected ?: JsonNull
        val a = actual ?: JsonNull
        if (e is JsonNull || a is JsonNull) return if (e is JsonNull && a is JsonNull) null else "$path: $e ≠ $a"
        if (e is JsonObject && a is JsonObject) {
            for (k in e.keys + a.keys) firstDiff(e[k], a[k], tolerance, "$path.$k")?.let { return it }
            return null
        }
        if (e is JsonArray && a is JsonArray) {
            if (e.size != a.size) return "$path: 개수 ${e.size} ≠ ${a.size}"
            for (i in e.indices) firstDiff(e[i], a[i], tolerance, "$path[$i]")?.let { return it }
            return null
        }
        if (e is JsonPrimitive && a is JsonPrimitive) {
            if (e.isString || a.isString) return if (e.isString == a.isString && e.content == a.content) null else "$path: $e ≠ $a"
            val en = e.doubleOrNull
            val an = a.doubleOrNull
            if (en != null && an != null) return if (abs(en - an) <= tolerance) null else "$path: $en ≠ $an"
            return if (e.booleanOrNull != null && e.booleanOrNull == a.booleanOrNull) null else "$path: $e ≠ $a"
        }
        return "$path: $e ≠ $a"
    }
}

/* ---------------------------------------------------------------- JsonObject 읽기 도우미 */

fun JsonObject.string(key: String): String = req(key).jsonPrimitive.content

fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

fun JsonObject.double(key: String): Double = req(key).jsonPrimitive.doubleOrNull ?: error("수가 아님: $key")

fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.int(key: String): Int = double(key).toInt()

fun JsonObject.bool(key: String): Boolean = req(key).jsonPrimitive.booleanOrNull ?: error("참·거짓이 아님: $key")

fun JsonObject.obj(key: String): JsonObject = req(key).jsonObject

fun JsonObject.array(key: String): JsonArray = req(key).jsonArray

private fun JsonObject.req(key: String): JsonElement = this[key] ?: error("키 없음: $key (있는 키: ${keys.take(20)})")
