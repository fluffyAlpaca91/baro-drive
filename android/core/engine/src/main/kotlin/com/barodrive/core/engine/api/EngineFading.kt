package com.barodrive.core.engine.api

import com.barodrive.core.engine.fading.FadingCalc
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.FadingUpdate
import com.barodrive.core.model.ItemStat
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord

/**
 * 도움 줄여 가기 (설계 6.7, 6.1.1 `fading.update(profile, record)`, 프로토타입 fading.js 머리 주석, 결정-28).
 * 계산은 `com.barodrive.core.engine.fading.FadingCalc`.
 */
object EngineFading {
    /** 도움 줄여 가기 대상 항목(items.js `fade`: 과제와 이어진 B1·C2·C4·C5·F1, FB-1 순서). */
    val items: List<String> = listOf("B1", "C2", "C4", "C5", "F1")

    /**
     * 회차 끝 새 상태 (fading.js `update(profile, rec)`). 프로필은 고치지 않는다 — 결과의 `state`를
     * 프로필 `fading[mode]`에 넣는 것은 부르는 쪽([EngineReview.applyProgress]).
     */
    fun update(profile: Profile, record: SessionRecord): FadingUpdate = FadingCalc.update(profile, record)

    /**
     * 같은 계산, 아무것도 고치지 않음 (fading.js `next(prev, stats, { mode, overrides })`, 리뷰 미리 계산용).
     * 회차 성공률 = ok ÷ 판정, 판정 2번 미만은 세지 않음(연속도 끊지 않음). 80% 이상이면 streak + 1, 2회차 연속이면 faded
     * (그 모드 기본값이 O이고 사용자가 바꾸지 않은 항목만). 80% 아래면 streak 0, 내려가 있었으면 되돌림.
     */
    fun next(prev: Map<String, FadeState>, stats: Map<String, ItemStat>, mode: Int, overrides: Map<String, Delivery>): FadingUpdate =
        FadingCalc.next(prev, stats, mode, overrides)

    /** 항목별 성공·판정 수(fading.js `stats`: 기록 itemStats가 있으면 그것, 없으면 판정 기록에서). */
    fun stats(record: SessionRecord): Map<String, ItemStat> = FadingCalc.stats(record)

    /** 이 모드에서 O → S로 내릴 수 있나(fading.js `eligible`: fade 항목, 고정 아님, 사용자가 안 바꿈, 그 모드 기본값 O). */
    fun eligible(item: String, mode: Int, overrides: Map<String, Delivery>): Boolean = FadingCalc.eligible(item, mode, overrides)

    /**
     * RV-1 알림 줄(fading.js `note`): '출발·정지는 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.'
     * 셋 넘으면 '출발·정지 등 5가지는 …'. 이름 순서 출발 → 정지 → 깜빡이 셋. 내려간 항목이 없으면 null.
     */
    fun note(fadedNow: List<String>): String? = FadingCalc.note(fadedNow)
}
