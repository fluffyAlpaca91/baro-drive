package com.barodrive.core.sim.golden

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
 * 프로토타입이 만든 정답 파일(골든) 읽기 — 시뮬레이터 쪽.
 *
 * 만드는 법·파일 모양·비교하는 법은 `prototype/tools/golden/README.md`에 있다. 테스트 리소스 `golden/manifest.json`이
 * 회차 목록이고, 회차마다 `golden/<id>.json.gz`(gzip JSON)가 있다. 엔진 쪽(`:core:engine` 테스트 리소스)과 같은 회차 id ·
 * 같은 스냅숏이다: 이 파일의 세션 부분([SimGolden.sessionSnapshots])이 엔진 파일의 `snapshots`와 같다(export.js가 확인).
 * 값은 kotlinx.serialization [JsonElement] 그대로 준다(필드 이름은 프로토타입 JS와 같다).
 *
 * ```
 * val g = GoldenFixtures.load("m1-S1-B-normal")
 * val run = ScenarioRunner.run(SimTarget.Scenario(g.scenario), ScenarioRunner.Options(skill = skillOf(g.skill), caps = …, parking = …,
 *     injections = …, until = ScenarioRunner.Until.Ticks(g.ticks)))   // 세션 시작 틱에 sim 시계 0, 주입은 그 시나리오 시각에
 * for (tick in 1..g.ticks) compare(g.snapshotAt(tick), run.snapshotAt(tick))
 * ```
 * 실제 비교는 [SimGoldenTest].
 */
object GoldenFixtures {
    const val MANIFEST_FORMAT = "baro-golden-manifest/1"
    const val FORMAT = "baro-golden-sim/1"
    private const val DIR = "/golden/"

    /** manifest.json 전체. */
    val manifest: JsonObject by lazy { Json.parseToJsonElement(readText("manifest.json")).jsonObject }

    /** 회차 목록(manifest `runs`, 만든 순서). */
    val runs: List<GoldenRun> by lazy { manifest.array("runs").map { GoldenRun(it.jsonObject) } }

    fun run(id: String): GoldenRun = runs.firstOrNull { it.id == id } ?: error("골든 회차 없음: $id")

    /** 회차 하나를 읽는다(gzip을 풀고 JSON으로). */
    fun load(id: String): SimGolden = SimGolden(Json.parseToJsonElement(readGzip(run(id).file)).jsonObject)

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
    /** 못 읽는 신호(시연 패널 '신호 지원'에서 끈 것). */
    val capsOff: List<String> = json.array("capsOff").map { it.jsonPrimitive.content }
    /** 누른 주입 id(시연 패널 '코칭 이벤트 넣기'). */
    val injections: List<String> = json.array("injections").map { it.jsonPrimitive.content }
    /** 시뮬레이터 틱 수(세션 전 틱 포함). */
    val ticks: Int = json.int("ticks")
    /** 엔진 session.step 횟수 = 세션 시작 틱부터 끝 틱까지. */
    val steps: Int = json.int("steps")

    override fun toString(): String = id
}

/** 회차 하나(시뮬레이터 파일). 키 이름은 README 8장. 틱 번호는 재생을 시작한 뒤 1부터. */
class SimGolden(val json: JsonObject) {
    val id: String = json.string("id")
    val mode: Int = json.int("mode")
    /** 불러온 시나리오 id(BaroSim.player.load 대상). */
    val scenario: String = json.string("scenario")
    val skill: String = json.string("skill")
    /** 운전자 모델 시드 'driver:<시나리오 id>:<실력>'(Baro.util.rng — FNV-1a + mulberry32, manifest rngCheck로 포트 확인). */
    val driverSeed: String = json.obj("seeds").string("driver")
    /** 불러오기 인자 { target, skill, caps, parking }. */
    val load: JsonObject = json.obj("load")
    /** 못 읽는 신호로 끈 caps(그 키만 false) | null = 모두 읽음. 불러오기 전에 BaroSim.setCaps. */
    val caps: JsonObject? = load["caps"] as? JsonObject
    /** 모드 6 실제 주차 종류·방향 { type, side } | null. 불러오기 전에 BaroData.parking.choose. */
    val parking: JsonObject? = load["parking"] as? JsonObject
    /** 실제로 누른 주입(누른 순서). [GoldenInjection.beforeTick] 틱을 돌리기 바로 전에 누른다. */
    val injections: List<GoldenInjection> = json.array("injected").map {
        val o = it.jsonObject
        GoldenInjection(o.string("id"), o.int("beforeTick"), o.double("playerT"), o.double("t"), o.stringOrNull("result"))
    }
    /** 불러온 직후(재생 전) 상태 { info, caps, vehicle, snapshot, nav, driver: { plan, info } }. */
    val initial: JsonObject = json.obj("initial")
    val ticks: Int = json.int("ticks")
    /** 기어가 P를 벗어나 세션이 시작된 틱. 이 틱 안에서 sim 시계를 0으로 맞춰 이 틱 스냅숏의 t가 0이다. */
    val sessionStartTick: Int = json.int("sessionStartTick")
    /** session.finish를 부른 틱(= 마지막 틱). */
    val finishTick: Int = json.int("finishTick")
    /** 끝난 때 { playerState, playerT(시나리오 시각), simT }. */
    val end: JsonObject = json.obj("end")
    /** 내비 음성 { text, dur, t, key } · 재탐색 · 경유지 · 도착 여부. */
    val nav: JsonObject = json.obj("nav")
    /** 'sim:scenario' 사건 { tick, t, playerT, state, id, mark, … } — 장면(mark)을 지난 틱. */
    val scenarioEvents: List<JsonObject> = json.array("scenarioEvents").map { it.jsonObject }

    /** 틱마다 스냅숏(풀어 놓은 것). [snapshotAt]으로 틱 번호로 꺼낸다. */
    val snapshots: List<JsonObject> by lazy { GoldenStreams.decodeSnapshots(json.array("snapshots")) }

    /** 틱 번호(1부터)의 스냅숏. */
    fun snapshotAt(tick: Int): JsonObject = snapshots[tick - 1]

    /** 엔진에 넘긴 스냅숏들(세션 시작 틱 ~ 끝) = 엔진 파일의 snapshots. */
    val sessionSnapshots: List<JsonObject> get() = snapshots.subList(sessionStartTick - 1, finishTick)

    override fun toString(): String = id
}

/** 누른 주입 하나. [playerT] = 누른 때 시나리오 시각(재생기 t), [t] = sim 시계. */
data class GoldenInjection(val id: String, val beforeTick: Int, val playerT: Double, val t: Double, val result: String?)

/** 줄여 쓴 스냅숏 스트림 풀기(README 7.2). export.js decodeSnapshots·엔진 쪽 GoldenStreams와 같은 규칙. */
object GoldenStreams {
    private const val DEL = "\$del"

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
    /** 시각 허용 차이(초). 스냅숏이 0.1초마다라 같은 틱인지만 본다. */
    const val TIME_TOLERANCE = 0.05

    /** 그 밖의 수 허용 차이(프로토타입이 반올림해 내는 값 — speed·steer 0.01, tripDist·heading 0.1 — 은 README 9.2). */
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
