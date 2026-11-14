package com.barodrive.core.model

import kotlin.math.floor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 판정 기록의 `data` (설계 6.9, 프로토타입 `ctx.emit({ item, result, data })`). 항목마다 모양이 달라서 JSON 객체를 그대로 들고,
 * 자주 쓰는 키는 타입이 있는 속성으로 꺼낸다 — 키가 없거나 타입이 다르면 null이고 예외를 던지지 않는다.
 * 만들 때는 이름 붙은 인자로: `EventData(turnId = "t3", distance = 45.0, type = "TURN_LEFT", ic = false, dir = TurnDir.LEFT)`.
 *
 * 정규형: 숫자는 정수 값이면 정수(28.0 → 28, JS와 같게), null 값 칸은 뺀다(프로토타입은 null과 '없음'을 같게 본다).
 * 그래서 프로토타입 골든과 `==`로 비교해도 된다.
 *
 * 항목별 키(프로토타입 검출기 머리 주석 그대로):
 * - A1 fail `{gear}` · PB1 fail `{dur}` · PB2 ok|fail|info `{dur?, unreadable?}`
 * - B1 ok|fail|excluded `{maxGain1s, threshold, gear, reason?('F2')}`
 * - C1 info `{turnId, distance, dir}` · C2 ok|almost|late|fail `{turnId, distance(fail이면 없음), type, ic, dir}`
 * - C3 fail `{turnId, dir, blink, distance, type, ic}` · C4 ok|fail `{turnId, dir, interval, blinkOnT, steerStartT}`
 * - C5 ok|fail `{turnId, dir, before}` · C6 fail `{dir, sec, afterTurn, afterTurnId}` · J1 fail `{sec, n}`
 * - F1 ok|fail `{v0, v1, dur, maxDrop1s, stopped, threshold}` · F2 info `{t, v0, v1, dur, maxDrop1s, stopped}`
 * - E1 ok|fail `{zoneId, limit, effLimit, school, peak?, overSec?}` · E2 fail `{zoneId, limit, effLimit, speed}`
 * - E3 fail `{zoneId, limit, effLimit, school, peak, overSec, via:'navi'}`(차속을 못 읽을 때, 결정사항 E T10-8)
 * - G1 info `{value, windows, base, baseFirst, better, insufficient}`
 * - J3 info `{kind: 'snow'|'rain'|'cold'|'slip'|'tip', when?, by?, severe?, wiperSec?, temp?, cautionId?, remain?, tip?, set?}`
 * - K8 info `{kind: 'turn'|'stop'|'none', key, turnId?, distance?, maxDrop1s?, ago}` · M2 fail `{alone, t, gear, speed, segment}`
 * - HL1 ok|fail `{practice:'down', run, presses, holds}` · HL2 ok `{attempt}` / fail `{attempt, speed, dur}`
 * - HL3 ok|fail `{situation, need, steer, side}` · PK1 ok `{step, signal, by, last}` / excluded `{step, signal, excluded:'skipped'}`
 * - PK2 fail `{speed, n, step}` · PK3 ok|fail `{steer, threshold}`
 * - 출발 전 사건: L1 info `{spoken, value('O'|'S'|'X')}` · PB1 info `{asked, spoken, value}`·`{confirmed}` · A5 info `{checked}`
 *   · PK1 `{setup: true, type, side}`(주차 종류·방향)
 * - 회차 끝에 뺀 판정: 원래 data + `{was: 원래 결과, excluded: 'reroute'|'skipped'}`
 */
@Serializable(with = EventDataSerializer::class)
class EventData private constructor(
    /** 정규형 JSON 객체. */
    val json: JsonObject,
) {
    /** 키의 JSON 값. */
    operator fun get(key: String): JsonElement? = json[key]

    /** 키가 있나(null 값은 정규형에서 빠지므로 '값이 있나'와 같다). */
    operator fun contains(key: String): Boolean = key in json

    /** 글자 값(숫자·참거짓이면 그 글자). 없으면 null. */
    fun string(key: String): String? = primitive(key)?.content

    /** 숫자 값. 숫자가 아니면 null. */
    fun double(key: String): Double? = primitive(key)?.takeIf { !it.isString }?.doubleOrNull

    /** 정수 값(소수면 null). */
    fun int(key: String): Int? = double(key)?.takeIf { it == floor(it) }?.toInt()

    /** 참거짓 값. */
    fun boolean(key: String): Boolean? = primitive(key)?.takeIf { !it.isString }?.booleanOrNull

    private fun primitive(key: String): JsonPrimitive? = (json[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    // ---- 회전·깜빡이 ----
    val turnId: String? get() = string("turnId")
    val distance: Double? get() = double("distance")
    /** TBT 회전 종류(C2·C3) 또는 주차 종류(PK1 setup: 'parallel'·'back'·'front'). */
    val type: String? get() = string("type")
    val ic: Boolean? get() = boolean("ic")
    val dir: TurnDir? get() = TurnDir.fromCode(string("dir"))
    /** C3: 켠 반대쪽 깜빡이. */
    val blink: TurnDir? get() = TurnDir.fromCode(string("blink"))
    val interval: Double? get() = double("interval")
    val blinkOnT: Double? get() = double("blinkOnT")
    val steerStartT: Double? get() = double("steerStartT")
    /** C5: 조향 시작 때 맞는 쪽 깜빡이가 켜져 있었음(T-SIGNAL1 성공). */
    val before: Boolean? get() = boolean("before")
    val afterTurn: Boolean? get() = boolean("afterTurn")
    val afterTurnId: String? get() = string("afterTurnId")

    // ---- 출발·제동 ----
    val gear: Gear? get() = string("gear")?.let { g -> Gear.entries.firstOrNull { it.name == g } }
    val maxGain1s: Double? get() = double("maxGain1s")
    /** 판정에 쓴 기준(B1 11/8, F1 7.5/6, PK3 5). */
    val threshold: Double? get() = double("threshold")
    /** B1 excluded 까닭('F2'). */
    val reason: String? get() = string("reason")
    val v0: Double? get() = double("v0")
    val v1: Double? get() = double("v1")
    val dur: Double? get() = double("dur")
    val maxDrop1s: Double? get() = double("maxDrop1s")
    val stopped: Boolean? get() = boolean("stopped")
    /** F2 줄 시각, M2 시각. */
    val t: Double? get() = double("t")

    // ---- 구역·속도 ----
    val zoneId: String? get() = string("zoneId")
    val limit: Double? get() = double("limit")
    val effLimit: Double? get() = double("effLimit")
    val school: Boolean? get() = boolean("school")
    val peak: Double? get() = double("peak")
    val overSec: Double? get() = double("overSec")
    val speed: Double? get() = double("speed")
    /** E3: 'navi'. */
    val via: String? get() = string("via")

    // ---- G1 ----
    /** G1 회차 값(직진 창 표준편차 평균). 출발 전 사건의 'value' 글자는 [delivery]. */
    val value: Double? get() = double("value")
    val windows: Int? get() = int("windows")
    val base: Double? get() = double("base")
    val baseFirst: Boolean? get() = boolean("baseFirst")
    val better: Double? get() = double("better")
    val insufficient: Boolean? get() = boolean("insufficient")

    // ---- 그 밖 항목 ----
    val unreadable: Boolean? get() = boolean("unreadable")
    val sec: Double? get() = double("sec")
    val n: Int? get() = int("n")
    val attempt: Int? get() = int("attempt")
    val practice: HillPractice? get() = HillPractice.entries.firstOrNull { it.name.equals(string("practice"), ignoreCase = true) }
    val run: Int? get() = int("run")
    val presses: Int? get() = int("presses")
    val holds: Int? get() = int("holds")
    val situation: HillSlope? get() = HillSlope.entries.firstOrNull { it.name.equals(string("situation"), ignoreCase = true) }
    val need: TurnDir? get() = TurnDir.fromCode(string("need"))
    val steer: Double? get() = double("steer")
    val side: TurnDir? get() = TurnDir.fromCode(string("side"))
    val step: Int? get() = int("step")
    /** PK1 단계 넘김 신호('press'·'stop'·'gearR'·'gearP'·'steerFull'·'steerStraight'). */
    val signal: String? get() = string("signal")
    /** PK1 넘긴 방법('signal'·'press'·'skip'), J3 날씨 까닭('snow'·'rain'·'severe'·'wiper'). */
    val by: String? get() = string("by")
    val last: Boolean? get() = boolean("last")
    /** J3·K8 종류. */
    val kind: String? get() = string("kind")
    /** K8 답 대사 키. */
    val key: String? get() = string("key")
    val ago: Double? get() = double("ago")
    /** J3 안내 때(JSON 키 `when`: 'depart'·'wiper'). */
    val trigger: String? get() = string("when")
    val severe: Severe? get() = Severe.fromCode(string("severe"))
    val wiperSec: Double? get() = double("wiperSec")
    val temp: Double? get() = double("temp")
    val cautionId: String? get() = string("cautionId")
    val remain: Double? get() = double("remain")
    /** J3 요령 대사 키. */
    val tip: String? get() = string("tip")
    /** J3 요령 묶음('rain'·'snow'). */
    val set: String? get() = string("set")
    val alone: Boolean? get() = boolean("alone")
    val segment: Int? get() = int("segment")

    // ---- 출발 전 사건 ----
    val setup: Boolean? get() = boolean("setup")
    val spoken: Boolean? get() = boolean("spoken")
    val asked: Boolean? get() = boolean("asked")
    val confirmed: Boolean? get() = boolean("confirmed")
    val checked: Boolean? get() = boolean("checked")
    /** L1·PB1 출발 전 사건의 'value'(그 회차 전달 방식 글자). */
    val delivery: Delivery? get() = Delivery.fromCode(string("value"))

    // ---- 뺀 판정 ----
    /** 빼기 전 결과. */
    val was: EventResult? get() = EventResult.fromCode(string("was"))
    /** 뺀 까닭('reroute'·'skipped'). */
    val excluded: String? get() = string("excluded")

    /** [key]를 [value]로 바꾼(null이면 뺀) 사본. */
    fun with(key: String, value: JsonElement?): EventData =
        of(JsonObject(if (value == null || value is JsonNull) json - key else json + (key to value)))

    /** [other]의 칸을 덮어쓴 사본(JS `Object.assign({}, this, other)`). */
    operator fun plus(other: EventData): EventData = of(JsonObject(json + other.json))

    override fun equals(other: Any?): Boolean = other is EventData && other.json == json

    override fun hashCode(): Int = json.hashCode()

    override fun toString(): String = json.toString()

    companion object {
        /** 빈 data(`{}`). */
        val EMPTY: EventData = EventData(JsonObject(emptyMap()))

        /** JSON 객체 → 정규형 data. */
        fun of(json: JsonObject): EventData = EventData(canonicalJson(json) as JsonObject)

        /**
         * 이름 붙은 인자로 만든다. null인 인자는 칸을 만들지 않는다. 여기 없는 키는 [extra]로.
         * [delivery]는 'value' 키에 글자로, [trigger]는 'when' 키에 쓴다.
         */
        operator fun invoke(
            turnId: String? = null, distance: Double? = null, type: String? = null, ic: Boolean? = null, dir: TurnDir? = null,
            blink: TurnDir? = null, interval: Double? = null, blinkOnT: Double? = null, steerStartT: Double? = null,
            before: Boolean? = null, afterTurn: Boolean? = null, afterTurnId: String? = null,
            gear: Gear? = null, maxGain1s: Double? = null, threshold: Double? = null, reason: String? = null,
            v0: Double? = null, v1: Double? = null, dur: Double? = null, maxDrop1s: Double? = null, stopped: Boolean? = null,
            t: Double? = null,
            zoneId: String? = null, limit: Double? = null, effLimit: Double? = null, school: Boolean? = null, peak: Double? = null,
            overSec: Double? = null, speed: Double? = null, via: String? = null,
            value: Double? = null, windows: Int? = null, base: Double? = null, baseFirst: Boolean? = null, better: Double? = null,
            insufficient: Boolean? = null,
            unreadable: Boolean? = null, sec: Double? = null, n: Int? = null, attempt: Int? = null, practice: HillPractice? = null,
            run: Int? = null, presses: Int? = null, holds: Int? = null, situation: HillSlope? = null, need: TurnDir? = null,
            steer: Double? = null, side: TurnDir? = null, step: Int? = null, signal: String? = null, by: String? = null,
            last: Boolean? = null, kind: String? = null, key: String? = null, ago: Double? = null, trigger: String? = null,
            severe: Severe? = null, wiperSec: Double? = null, temp: Double? = null, cautionId: String? = null, remain: Double? = null,
            tip: String? = null, set: String? = null, alone: Boolean? = null, segment: Int? = null,
            setup: Boolean? = null, spoken: Boolean? = null, asked: Boolean? = null, confirmed: Boolean? = null,
            checked: Boolean? = null, delivery: Delivery? = null,
            was: EventResult? = null, excluded: String? = null,
            extra: JsonObject? = null,
        ): EventData {
            val m = LinkedHashMap<String, JsonElement>()
            m.text("turnId", turnId); m.num("distance", distance); m.text("type", type); m.bool("ic", ic); m.text("dir", dir?.code)
            m.text("blink", blink?.code); m.num("interval", interval); m.num("blinkOnT", blinkOnT); m.num("steerStartT", steerStartT)
            m.bool("before", before); m.bool("afterTurn", afterTurn); m.text("afterTurnId", afterTurnId)
            m.text("gear", gear?.name); m.num("maxGain1s", maxGain1s); m.num("threshold", threshold); m.text("reason", reason)
            m.num("v0", v0); m.num("v1", v1); m.num("dur", dur); m.num("maxDrop1s", maxDrop1s); m.bool("stopped", stopped)
            m.num("t", t)
            m.text("zoneId", zoneId); m.num("limit", limit); m.num("effLimit", effLimit); m.bool("school", school); m.num("peak", peak)
            m.num("overSec", overSec); m.num("speed", speed); m.text("via", via)
            m.num("value", value); m.int("windows", windows); m.num("base", base); m.bool("baseFirst", baseFirst)
            m.num("better", better); m.bool("insufficient", insufficient)
            m.bool("unreadable", unreadable); m.num("sec", sec); m.int("n", n); m.int("attempt", attempt)
            m.text("practice", practice?.name?.lowercase()); m.int("run", run); m.int("presses", presses); m.int("holds", holds)
            m.text("situation", situation?.name?.lowercase()); m.text("need", need?.code); m.num("steer", steer)
            m.text("side", side?.code); m.int("step", step); m.text("signal", signal); m.text("by", by); m.bool("last", last)
            m.text("kind", kind); m.text("key", key); m.num("ago", ago); m.text("when", trigger); m.text("severe", severe?.code)
            m.num("wiperSec", wiperSec); m.num("temp", temp); m.text("cautionId", cautionId); m.num("remain", remain)
            m.text("tip", tip); m.text("set", set); m.bool("alone", alone); m.int("segment", segment)
            m.bool("setup", setup); m.bool("spoken", spoken); m.bool("asked", asked); m.bool("confirmed", confirmed)
            m.bool("checked", checked); m.text("value", delivery?.code)
            m.text("was", was?.code); m.text("excluded", excluded)
            extra?.forEach { (k, v) -> m[k] = v }
            return of(JsonObject(m))
        }

        private fun MutableMap<String, JsonElement>.text(k: String, v: String?) { if (v != null) this[k] = JsonPrimitive(v) }
        private fun MutableMap<String, JsonElement>.num(k: String, v: Double?) { if (v != null) this[k] = numberPrimitive(v) }
        private fun MutableMap<String, JsonElement>.int(k: String, v: Int?) { if (v != null) this[k] = JsonPrimitive(v) }
        private fun MutableMap<String, JsonElement>.bool(k: String, v: Boolean?) { if (v != null) this[k] = JsonPrimitive(v) }
    }
}

/** [EventData] ↔ JSON 객체(정규형으로 읽는다). JSON 전용. */
internal object EventDataSerializer : KSerializer<EventData> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: EventData) = JsonObject.serializer().serialize(encoder, value.json)

    override fun deserialize(decoder: Decoder): EventData {
        val input = decoder as? JsonDecoder ?: throw SerializationException("EventData는 JSON으로만 읽어요")
        return (input.decodeJsonElement() as? JsonObject)?.let(EventData::of) ?: EventData.EMPTY
    }
}
