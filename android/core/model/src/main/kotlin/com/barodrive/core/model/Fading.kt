package com.barodrive.core.model

import kotlinx.serialization.Serializable

/**
 * 도움 줄여 가기 상태 한 칸 (설계 6.7 `fading[mode][item] = {streak, faded}`, 프로토타입 fading.js).
 * 과제와 이어진 B1·C2·C4·C5·F1만 쓴다. 회차 성공률 80% 이상(판정 2번 이상)이면 [streak] + 1, 연속 2회차면 [faded](O → S).
 */
@Serializable
data class FadeState(
    /** 연속으로 80% 이상인 회차 수. */
    val streak: Int = 0,
    /** 내려가 있음(다음 회차부터 그 모드에서 O → S, C2가 내려가면 C1도 X). */
    val faded: Boolean = false,
)

/** 엔진 파사드 `fading.update(profile, record)` 결과 — 그 모드의 새 상태(프로필에 넣는 것은 부르는 쪽). */
@Serializable
data class FadingUpdate(
    val mode: Int,
    /** 새 `fading[mode]`. */
    val state: Map<String, FadeState> = emptyMap(),
    /** 이번 회차로 내려간 항목. */
    val fadedNow: List<String> = emptyList(),
    /** 이번 회차로 되돌린 항목(80% 아래, 결정-28). */
    val restoredNow: List<String> = emptyList(),
)
