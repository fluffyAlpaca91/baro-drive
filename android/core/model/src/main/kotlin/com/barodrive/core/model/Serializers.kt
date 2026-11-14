package com.barodrive.core.model

import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * 프로토타입 JSON의 흔들리는 값을 받아 주는 직렬화 도구들. 프로토타입은 같은 칸에 여러 타입을 쓰는 곳이 있다
 * (예: 기록 날씨 severe = false | 'ice' | 'fog', 면허 결과 alone = false | 2). 읽을 때만 너그럽고 쓸 때는 한 모양으로 쓴다.
 */

/** 숫자 JSON 값의 정규형: 정수 값이면 정수(JS처럼 '28'), 아니면 실수. 골든과 비교할 때 28과 28.0이 같게. */
internal fun numberPrimitive(d: Double): JsonPrimitive =
    if (d.isFinite() && d == floor(d) && abs(d) < MAX_SAFE_INTEGER) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

/** JS `Number.MAX_SAFE_INTEGER` 근처 — 이보다 큰 정수는 실수로 둔다. */
private const val MAX_SAFE_INTEGER = 9.007199254740991E15

/**
 * JSON 값의 정규형: 숫자는 [numberPrimitive], 객체 안 null 값 칸은 뺀다(프로토타입은 null과 '없음'을 같게 본다).
 * 배열 안 null은 자리가 뜻이 있어 그대로 둔다.
 */
internal fun canonicalJson(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.filterValues { it !is JsonNull }.mapValues { canonicalJson(it.value) })
    is JsonArray -> JsonArray(e.map(::canonicalJson))
    JsonNull -> JsonNull
    is JsonPrimitive -> when {
        e.isString -> e
        e.booleanOrNull != null -> e
        else -> e.doubleOrNull?.let(::numberPrimitive) ?: e
    }
}

/**
 * 아주 나쁜 날 값 읽기: null·false → null, 'ice'·'fog' → 그 값, true·모르는 글자 → [Severe.OTHER].
 * 쓸 때는 글자 또는 null.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object SevereSerializer : KSerializer<Severe?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.barodrive.core.model.Severe", PrimitiveKind.STRING).nullable

    override fun serialize(encoder: Encoder, value: Severe?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.code)
    }

    override fun deserialize(decoder: Decoder): Severe? {
        if (decoder !is JsonDecoder) return if (decoder.decodeNotNullMark()) Severe.fromCode(decoder.decodeString()) else decoder.decodeNull()
        val e = decoder.decodeJsonElement()
        if (e !is JsonPrimitive || e is JsonNull) return null
        if (e.isString) return Severe.fromCode(e.content) ?: Severe.OTHER
        return if (e.booleanOrNull == true) Severe.OTHER else null
    }
}

/** 개수 읽기: 숫자는 정수로(소수는 내림), true → 1, false·null → 0. 쓸 때는 정수. */
internal object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.barodrive.core.model.LenientInt", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val e = decoder.decodeJsonElement()
        if (e !is JsonPrimitive || e is JsonNull) return 0
        e.booleanOrNull?.let { return if (it) 1 else 0 }
        return e.doubleOrNull?.let { floor(it).toInt() } ?: 0
    }
}

/**
 * 내비 사건 읽기: 프로토타입은 글자('reroute')도 객체(`{ type: 'reroute', turnId }`)도 쓴다(core.js navEvents).
 * 글자는 객체로 바꾸고, 다른 이름('destinationArrived'·'waypoint')은 이 앱 이름으로 맞춘다.
 */
internal object NavEventSerializer : JsonTransformingSerializer<NavEvent>(NavEvent.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = when {
            element is JsonPrimitive && element.isString -> JsonObject(mapOf(TYPE to element))
            element is JsonObject -> element
            else -> return element
        }
        val type = (obj[TYPE] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (obj["evt"] as? JsonPrimitive)?.content
            ?: (obj["name"] as? JsonPrimitive)?.content
        val name = when (type) {
            "destinationArrived" -> "arrived"
            "waypoint" -> "waypointArrived"
            else -> type
        } ?: return obj
        return JsonObject(obj + (TYPE to JsonPrimitive(name)))
    }

    private const val TYPE = "type"
}
