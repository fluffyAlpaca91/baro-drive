package com.barodrive.app.data.voice

import com.barodrive.core.model.Utterance

/**
 * 코치 음성 (프로토타입 app/voice.js '음성 자막'의 자리 — 앱에서는 진짜 소리). 말할지 말지는 엔진의 전달 관리자가
 * 이미 정했다. 여기서는 소리만 낸다. 소리가 안 나도(엔진 없음) 화면 말풍선은 그대로 보인다.
 */
interface CoachVoice {
    /** 켜면 아무 말도 하지 않고, 하던 말도 멈춘다(설정 '소리'). */
    var muted: Boolean

    fun speak(utterance: Utterance)

    /** 하던 말과 기다리던 말을 모두 멈춘다. */
    fun stop()
}
