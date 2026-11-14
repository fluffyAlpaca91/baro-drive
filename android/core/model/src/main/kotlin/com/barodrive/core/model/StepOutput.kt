package com.barodrive.core.model

import kotlinx.serialization.Serializable

/**
 * 엔진 한 걸음의 출력 (설계 6.1.1 `{say, cards, hints, blocked}`, 프로토타입 core.js `session.step`·`input`이 돌려주는 값).
 * 화면과 음성은 이것만 그린다 — 판정이나 말 고르기를 화면 쪽에서 하지 않는다(설계 6.1).
 */
@Serializable
data class StepOutput(
    /** 지금 소리 낼 말. */
    val say: List<Utterance> = emptyList(),
    /** 지금 보이는 신호 대기 카드 전부(이번에 새로 생긴 것만이 아님). 최대 2장. */
    val cards: List<Card> = emptyList(),
    val hints: Hints = Hints(),
    /** 이번 걸음에서 막힌 말. */
    val blocked: List<BlockedUtterance> = emptyList(),
    /** 이 출력의 시각, 세션 초. */
    val t: Double = 0.0,
    /** 이번 걸음의 판정(시연 패널 기록). */
    val events: List<JudgeEvent> = emptyList(),
    /** 신호 대기 중(DR-1 ↔ DR-2). */
    val waiting: Boolean = false,
    /** 창 안의 다음 내비 회전 — 주행 화면 깜빡이 히어로(설계 4.6.1). 없으면 null. */
    val turn: TurnApproach? = null,
    /** 소리 끔(안전만 말함). */
    val muted: Boolean = false,
) {
    companion object {
        val EMPTY = StepOutput()
    }
}

/**
 * 화면 힌트 (설계 6.1.1 `hints`, 프로토타입 검출기 `ctx.hint(이름, 값)`). 검출기가 채우고 화면은 그대로 그린다.
 */
@Serializable
data class Hints(
    /** DR-1 기본 히어로 덮어쓰기(지금 검출기는 쓰지 않음). */
    val hero: String? = null,
    /** DR-1 날씨 배지. 맑으면 null(J3가 S·X여도 보임 — 결정사항 E T7-4). */
    val badge: WeatherBadge? = null,
    /** 내비 도착 전이어도 다음 P에서 회차를 끝낸다(주차 안내 끝·언덕 주차 P — 결정 T5b-Q1). */
    val arrived: Boolean = false,
    /** 언덕 화면(HL-1·HL-2) 값. 모드 7만. */
    val hill: HillHint? = null,
    /** 실제 주차 안내 화면(PK-3) 값. 모드 6만. */
    val parking: ParkingHint? = null,
    /** 면허 모드 구간 진행(DR-1 머리줄). 모드 8만. */
    val license: LicenseHint? = null,
)
