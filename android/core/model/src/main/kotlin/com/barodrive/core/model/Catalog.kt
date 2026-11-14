package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 코칭 항목 정의 (설계 6.3.1·6.3.2, 프로토타입 data/items.js `BaroData.items[id]` 24개). 데이터는 엔진 콘텐츠(JSON)에 있고,
 * 엔진 파사드(`itemIds`·`itemDef`·`defaultFor`·`allowed`·`isFixed`)로 읽는다. FB-1·FB-2 화면은 이름·아이콘·잠금 글을 여기서 쓴다.
 * 칸 이름은 프로토타입과 같아 items.js를 JSON으로 내보내면 그대로 읽힌다.
 */
@Serializable
data class ItemDef(
    /** 'C2'. */
    val id: String,
    /** FB-1 줄 순서(목업 s10, 안전3은 E2 뒤). */
    val order: Int = 0,
    /** FB-1 줄 이름. */
    val name: String = "",
    /** 주행 화면 히어로 짧은 이름. */
    val short: String = "",
    /** 심볼 id. */
    val icon: String? = null,
    val group: ItemGroup = ItemGroup.DRIVE,
    /** 말 분류 후보(첫째가 기본). 빈 목록 = 말하지 않는 항목(F2·G1). */
    val cats: List<Category> = emptyList(),
    /** 모드 1~8 기본 전달 방식 글자 8개(예: 'OOSSSXSO', 설계 6.3.2 표·표 A-4). */
    val defaults: String = "XXXXXXXX",
    /** 사용자가 고를 수 있는 값(결정-20). 고정 항목은 []. */
    val allowed: List<Delivery> = emptyList(),
    /** 고정(안전 항목·F2): 사용자 변경·도움 줄여 가기가 닿지 않는다. */
    val fixed: Boolean = false,
    /** 안전3(C3·C6·J1) — 스위치가 꺼지면 항목에서 빠진다(설계 12). */
    val extra: Boolean = false,
    /** 이 신호 중 하나라도 못 읽으면 항목이 꺼진다(설계 7.8). */
    val needs: List<SignalKey> = emptyList(),
    /** 쓰지만 못 읽어도 대안이 있는 신호. */
    val uses: List<SignalKey> = emptyList(),
    /** 도움 줄여 가기 대상(B1·C2·C4·C5·F1). */
    val fade: Boolean = false,
    /** '처음 2회차만 O'인 모드(C1: [2, 8]). */
    val first2: List<Int>? = null,
    /** 조작 중에 바로 말해야 하는 단계 안내(간격·상한·비율·큰 조향 침묵을 받지 않음). */
    val guide: Boolean = false,
    /** 교정을 신호 대기 카드로 보여 주는 항목. */
    val card: Boolean = false,
    /** 자율 FB-2 목록에 있는 항목. */
    val fb2: Boolean = false,
    /** 고를 수 없는 칸을 눌렀을 때 토스트 글. */
    val lock: ItemLock? = null,
    /** FB-1 줄 아래 작은 글(키: wx·first2On·first2Off·rule·needs — 조건은 화면이 판단). */
    val note: Map<String, String>? = null,
) {
    /** 모드 1~8의 기본 전달 방식. 모드 밖이면 null. */
    fun defaultFor(mode: Int): Delivery? = defaults.getOrNull(mode - 1)?.let { Delivery.fromCode(it.toString()) }
}

/** 항목 묶음 (items.js `group`): 안전 항목(늘 바로) · 늘 지키는 규칙(F2) · 주행 · 주차 · 언덕. */
@Serializable
enum class ItemGroup {
    @SerialName("safety")
    SAFETY,

    @SerialName("rule")
    RULE,

    @SerialName("drive")
    DRIVE,

    @SerialName("parking")
    PARKING,

    @SerialName("hill")
    HILL,
}

/** 잠금 토스트 글 (items.js `lock`): 그 칸([after] = S 칸, [now] = O 칸) · 고정 항목 전체([all]) · 그 모드에서 X인 고정 항목([off]). */
@Serializable
data class ItemLock(
    val all: String? = null,
    val off: String? = null,
    @SerialName("S")
    val after: String? = null,
    @SerialName("O")
    val now: String? = null,
)

/**
 * 과제 정의 (설계 6.4, 프로토타입 data/tasks.js `BaroData.tasks[id]`). 엔진 파사드 `modeTasks(mode)`가 모드 문구를 반영해 돌려준다.
 */
@Serializable
data class TaskDef(
    /** 'T-START'. */
    val id: String,
    /** 과제 문구(PR-1 오늘 과제, RV-1 잘한 것, RV-2 '해 볼 것'). modeTasks는 [labelByMode]를 반영한 값을 준다. */
    val label: String = "",
    /** 짧은 말(대사 `{task…}` 자리). */
    val short: String = "",
    /** 세는 단위. */
    val unit: String = "",
    /** 성공 조건(설계 6.4 표 글 그대로). */
    val rule: String = "",
    /** 이어진 항목(도움 줄여 가기·리뷰). */
    val items: List<String> = emptyList(),
    /** 그 모드에서만 다른 문구(2단계 T-STOP). */
    val labelByMode: Map<Int, String>? = null,
    /** 안전 위반 때 '다음 과제 하나' 문구('30 구역 지키기'). */
    val nextLabel: String? = null,
    /** 모드 과제 목록에는 없고 '다음 과제' 1순위로만 쓰는 과제(T-SCHOOL). */
    val safetyOnly: Boolean = false,
)
