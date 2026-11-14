package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 신호 대기 카드 (설계 6.5.3, DR-2 화면, 프로토타입 core.js `cardView`). 신호 대기가 시작되면 보이고,
 * 차가 움직이면 닫혀 다시 보이지 않는다. JSON 칸 이름은 프로토타입과 같다.
 */
@Serializable
data class Card(
    /** 대사 키(코치 문장이 있는 카드). 날씨 요령 카드처럼 글만 있는 카드는 null. */
    val key: String? = null,
    val category: CardCategory = CardCategory.FIX,
    /** 제목(36/44). */
    val title: String = "",
    /** 수치 한 줄(예: "28km/h에서 3초 만에 정지"). */
    val detail: String? = null,
    /** 코칭 항목 id. */
    val item: String? = null,
    /** 만든 시각, 세션 초. */
    val createdAt: Double = 0.0,
    /** 카드 id(프로토타입 'card-1' …). */
    val id: String? = null,
    /** 심볼 id(프로토타입 index.html, 예: 'i-brake'). 앱은 이 이름으로 아이콘을 고른다. */
    val icon: String? = null,
    /** 코치 문장(대사를 그린 글). 맨 앞 카드면 이 문장을 말한다. 없으면 null. */
    val text: String? = null,
    /** 보이기 시작한 시각, 세션 초. 아직 줄에 있으면 null. */
    val shownAt: Double? = null,
)

/** 카드 분류 (프로토타입 core.js `CARD_CATS`). [TIP]은 날씨 요령처럼 말하지 않는 카드. [label]은 copy.js `categories`. */
@Serializable
enum class CardCategory(val label: String) {
    @SerialName("safety")
    SAFETY("안전"),

    @SerialName("fix")
    FIX("교정"),

    @SerialName("praise")
    PRAISE("칭찬"),

    @SerialName("tip")
    TIP("요령"),
}
