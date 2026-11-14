package com.barodrive.core.engine.api

import com.barodrive.core.engine.config.ItemResolver
import com.barodrive.core.model.ResolvedDelivery

/**
 * 전달 방식 결정 (설계 6.1.1 `feedback.resolve`, 6.3.2·6.7, 프로토타입 core.js `feedbackResolve`·`resolveOne`·`couple`).
 * 화면(FB-1·FB-2·PR-1)이 항목 하나의 이번 회차 값과 까닭을 볼 때 쓴다. 한 회차 전체는 [BaroEngine.resolveItems].
 */
object EngineFeedback {
    /**
     * 항목 하나의 이 회차 전달 방식과 까닭 (core.js `feedback.resolve(mode, item, { overrides, fading, sessionNoInMode, preset })`).
     * 우선순위: 고정 → 자율 설정(모드 5) → 사용자 변경 → 도움 줄여 가기(그 회차 값이 O인 fade 항목만 S) → C1 처음 2회차 → 기본값.
     * C1은 C2와 묶어 본다(C2가 fading이면 C1도 X). 회차 번호를 빼면 1회차로 본다. 모르는 항목이면 null, 모드가 1~8이 아니면 예외.
     */
    fun resolve(mode: Int, item: String, ctx: FeedbackContext = FeedbackContext()): ResolvedDelivery? = ItemResolver.feedback(mode, item, ctx)
}
