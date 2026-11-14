package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 코칭 항목의 전달 방식 (설계 0.2·6.3.2, 프로토타입 'O'·'S'·'X'). JSON에는 프로토타입 글자를 그대로 쓴다.
 * 화면 글은 copy.js `delivery`.
 */
@Serializable
enum class Delivery(
    val code: String,
    /** FB-1 칸 이름(목업 s10). */
    val label: String,
    /** 짧은 이름. */
    val shortLabel: String,
) {
    /** O = 바로 말로. 달리는 중에 말한다. */
    @SerialName("O")
    NOW("O", "바로 피드백", "바로"),

    /** S = 완료 후 리포트로. 말하지 않고 리뷰에만 남긴다. */
    @SerialName("S")
    AFTER("S", "주행 완료 후 피드백", "완료 후"),

    /** X = 안 함. 말도 리포트도 없이 기록만 한다(통과 계산·점수에는 들어간다). */
    @SerialName("X")
    OFF("X", "안 하기", "안 함"),
    ;

    companion object {
        /** 'O'·'S'·'X'를 값으로. 모르는 글자면 null. */
        fun fromCode(code: String?): Delivery? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 이 회차 전달 방식이 그렇게 정해진 까닭 (프로토타입 core.js `resolveItems`의 reason, 설계 6.3.2 아래 줄·6.7).
 * 우선순위: 사용자 변경 > 도움 줄여 가기 > C1 처음 2회차 > 기본값. 고정 항목은 늘 [FIXED].
 */
@Serializable
enum class DeliveryReason {
    /** 고정 항목(안전·F2) — 기본값 그대로. */
    @SerialName("fixed")
    FIXED,

    /** 자율 모드에서 고른 내 음성 피드백 설정(FB-2). */
    @SerialName("preset")
    PRESET,

    /** 사용자가 FB-1에서 바꿈. */
    @SerialName("user")
    USER,

    /** 도움 줄여 가기로 O → S(C2가 내려가면 C1은 X). */
    @SerialName("fading")
    FADING,

    /** C1 '처음 2회차만 O'(모드 2·8). */
    @SerialName("first2")
    FIRST2,

    /** 모드 기본값(설계 6.3.2 표). */
    @SerialName("default")
    DEFAULT,
}

/** 항목 하나의 이 회차 전달 방식과 까닭 (core.js `resolveItems`·`feedback.resolve`의 `{ value, reason }`). */
@Serializable
data class ResolvedDelivery(
    val value: Delivery,
    val reason: DeliveryReason = DeliveryReason.DEFAULT,
)
