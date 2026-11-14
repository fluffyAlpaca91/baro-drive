package com.barodrive.core.engine.track

import com.barodrive.core.model.Gear
import com.barodrive.core.model.TurnApproach
import com.barodrive.core.model.TurnDir

/**
 * 추적기 현재 값 (core.js 추적기 `view` = 검출기 `ctx.track`). 스냅숏마다 추적기가 고친다 — 검출기는 읽기만 한다.
 * 검출기 step이 불릴 때는 이번 스냅숏 값이다(추적기가 먼저 돈다).
 */
class TrackerView internal constructor() {
    var t: Double = 0.0
        internal set

    /** 속도 km/h(차속을 못 읽으면 트립 거리 변화로 어림, 설계 7.8). 모르면 null. */
    var v: Double? = null
        internal set

    var gear: Gear? = null
        internal set

    /** 바로 전 기어(기어가 바뀌었을 때만 바뀜). */
    var prevGear: Gear? = null
        internal set

    /** 정지(abs(speed) < 0.5). */
    var stopped: Boolean = false
        internal set

    /** 정지한 지 몇 초. */
    var stopDur: Double = 0.0
        internal set

    /** 신호 대기 중(기어 D·N이고 정지 1초 이상). */
    var waiting: Boolean = false
        internal set

    var waitDur: Double = 0.0
        internal set

    /** 움직이는 중(정지가 아님으로 확인됨). 시간 틈 뒤 첫 표본 전에는 false. */
    var moving: Boolean = false
        internal set

    var steer: Double? = null
        internal set

    var brake: Boolean? = null
        internal set

    /** 제동 구간 안. */
    var braking: Boolean = false
        internal set

    /** 지금 깜빡이 쪽(꺼져 있으면 null). */
    var blinkDir: TurnDir? = null
        internal set

    /** 창(150m · IC 300m) 안의 다음 내비 회전 — 주행 화면 깜빡이 히어로. 없으면 null. */
    var turn: TurnApproach? = null
        internal set
}

/** 추적기가 세션에 부탁하는 일 (core.js 추적기가 부르는 `S.addSilence`·`S.turnMissed`·`S.turnSkipped`·`S.arrivedNav`). */
internal interface TrackerHost {
    /** [until] sim초까지 말하지 않는다(규칙 5 — 급제동 'F2' · 합류 'merge'). */
    fun addSilence(until: Double, reason: String)

    /** 재탐색으로 놓친 회전. */
    fun turnMissed(id: String)

    /** 건너뛰기로 지나친 회전. */
    fun turnSkipped(id: String)

    /** 내비 도착 사건. */
    fun navArrived()
}
