package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 코치가 하는 말 하나 (설계 6.1.1 `say`의 발화, 프로토타입 core.js 세션 출력 `say[]`).
 * 프로토타입 JSON의 `speaker`('coach' 고정)는 두지 않는다. 막힌 말은 [BlockedUtterance], 회차 기록의 말은 [UtteranceLog].
 */
@Serializable
data class Utterance(
    /** 대사 키(프로토타입 lines 키, 예: 'c2.ok'). */
    val key: String = "",
    /** 소리 내고 화면에 보일 문장(호칭·변수를 넣은 뒤). */
    val text: String = "",
    val category: Category = Category.INFO,
    /** 코칭 항목 id(A1, C2 …). 항목과 상관없는 말이면 null. */
    val item: String? = null,
    /** 말한(또는 막힌) 시각, 세션 초. */
    val t: Double = 0.0,
    /** 말하는 데 걸리는 시간, 초(설계 2.6 `speakDur` = 글자 수 × 0.15 + 0.4, 소수 둘째 자리). 모르면 null. */
    val dur: Double? = null,
    /** 짝 회전 id(C1 → C2 간격 예외, 설계 6.5.2 규칙 7). */
    val pairWith: String? = null,
    /** 말한 코치. 음성(TTS)이 목소리를 고를 때 쓴다. */
    val coach: Coach? = null,
)

/**
 * 말 분류와 우선순위 (작을수록 먼저, 프로토타입 core.js `PRIORITY`·`CATEGORIES`, 설계 6.5.1). JSON에는 프로토타입 이름을 쓴다.
 * [label]은 copy.js `categories`(시연 패널 기록 칩).
 */
@Serializable
enum class Category(val priority: Int, val label: String) {
    /** 안전. 간격·상한에 막히지 않는다(결정-26). */
    @SerialName("safety")
    SAFETY(1, "안전"),

    /** 사전 안내(C1). */
    @SerialName("pre")
    PRE(2, "사전 안내"),

    /** 칭찬. */
    @SerialName("praise")
    PRAISE(3, "칭찬"),

    /** 교정. */
    @SerialName("fix")
    FIX(4, "교정"),

    /** 안내(A1, PB1, J3, L1, 단계 안내 …). */
    @SerialName("info")
    INFO(2, "안내"),
}

/**
 * 회차 기록의 말 기록 한 줄 (설계 6.9 `{t, item, key, category, text, spoken, reason}`, 프로토타입 `rec.utterances[]`).
 * 말한 것과 막힌 것을 시간 순으로 한 목록에 둔다. 시연 패널 기록과 K6가 이것을 쓴다.
 */
@Serializable
data class UtteranceLog(
    val t: Double = 0.0,
    val item: String? = null,
    val key: String = "",
    val category: Category = Category.INFO,
    val text: String = "",
    /** true = 실제로 말함, false = 막힘([reason]). */
    val spoken: Boolean = false,
    /** 막힌 이유. 말한 것이면 null. */
    val reason: BlockReason? = null,
) {
    /** 발화 모양으로. */
    fun toUtterance(): Utterance = Utterance(key = key, text = text, category = category, item = item, t = t)

    companion object {
        /** 말한 발화 → 기록 줄. */
        fun spoken(u: Utterance): UtteranceLog =
            UtteranceLog(t = u.t, item = u.item, key = u.key, category = u.category, text = u.text, spoken = true)

        /** 막힌 말 → 기록 줄. */
        fun blocked(b: BlockedUtterance): UtteranceLog = UtteranceLog(
            t = b.utterance.t, item = b.utterance.item, key = b.utterance.key, category = b.utterance.category,
            text = b.utterance.text, spoken = false, reason = b.reason,
        )
    }
}
