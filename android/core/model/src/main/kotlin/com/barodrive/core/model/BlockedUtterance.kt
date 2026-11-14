package com.barodrive.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 말하지 않기로 걸러진 말과 그 이유 (설계 6.5.2 '막힌 말', 프로토타입 세션 출력 `blocked[]`). 리포트와 시연 패널 기록에 쓴다.
 * JSON은 프로토타입처럼 평평하다: `{t, item, key, category, text, coach, reason}`.
 */
@Serializable(with = BlockedUtteranceSerializer::class)
data class BlockedUtterance(
    val utterance: Utterance,
    val reason: BlockReason,
)

/**
 * 막힌 이유 (프로토타입 core.js `REASONS`, 설계 6.5.2·구현계획 1.7). JSON에는 프로토타입 이름을 쓴다.
 * [label]은 copy.js `reasons`.
 */
@Serializable
enum class BlockReason(val label: String) {
    /** 간격: 앞 말이 끝나고 20초가 안 지남(말하는 중 포함). */
    @SerialName("gap")
    GAP("간격"),

    /** 상한: 최근 10분에 이미 6번 말함. */
    @SerialName("cap")
    CAP("상한"),

    /** 비율: 칭찬에 비해 교정이 많음. */
    @SerialName("ratio")
    RATIO("비율"),

    /** 침묵: 큰 조향·합류·급제동 직후. */
    @SerialName("silence")
    SILENCE("침묵"),

    /** 혼잡: 막히는 길에서는 안전·사전 안내만 말함. */
    @SerialName("jam")
    JAM("혼잡"),

    /** 내비 음성이 나오는 중. */
    @SerialName("navVoice")
    NAV_VOICE("내비 음성"),

    /** 소리 끔: 안전만 말함. */
    @SerialName("muted")
    MUTED("소리 끔"),

    /** 회전까지 50m 안으로 들어와 사전 안내가 늦음. */
    @SerialName("under50")
    UNDER_50("50m 안"),

    /** 기다리다 만료됨. */
    @SerialName("expired")
    EXPIRED("만료"),

    /** 전달 방식 S(완료 후 리포트). */
    @SerialName("S")
    AFTER("완료 후"),

    /** 전달 방식 X(안 함). */
    @SerialName("X")
    OFF("안 함"),
}

/** 프로토타입 막힌 말의 평평한 JSON 모양. */
@Serializable
@SerialName("com.barodrive.core.model.BlockedUtterance")
private class BlockedUtteranceJson(
    val t: Double = 0.0,
    val item: String? = null,
    val key: String = "",
    val category: Category = Category.INFO,
    val text: String = "",
    val coach: Coach? = null,
    val reason: BlockReason = BlockReason.EXPIRED,
)

/** [BlockedUtterance] ↔ 평평한 JSON. */
internal object BlockedUtteranceSerializer : KSerializer<BlockedUtterance> {
    override val descriptor: SerialDescriptor = BlockedUtteranceJson.serializer().descriptor

    override fun serialize(encoder: Encoder, value: BlockedUtterance) {
        val u = value.utterance
        encoder.encodeSerializableValue(
            BlockedUtteranceJson.serializer(),
            BlockedUtteranceJson(t = u.t, item = u.item, key = u.key, category = u.category, text = u.text, coach = u.coach, reason = value.reason),
        )
    }

    override fun deserialize(decoder: Decoder): BlockedUtterance {
        val j = decoder.decodeSerializableValue(BlockedUtteranceJson.serializer())
        return BlockedUtterance(Utterance(key = j.key, text = j.text, category = j.category, item = j.item, t = j.t, coach = j.coach), j.reason)
    }
}
