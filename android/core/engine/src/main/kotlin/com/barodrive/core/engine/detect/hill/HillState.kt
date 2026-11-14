package com.barodrive.core.engine.detect.hill

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.content.HillGuide
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillDownRun
import com.barodrive.core.model.HillHint
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.HillUpAttempt
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot

/*
 * 언덕 검출기 셋(HL1·HL2·HL3)이 나눠 쓰는 회차 상태 (프로토타입 detectors/hill.js `shared(ctx)`와 그 안 값들, 설계 6.12.2, 표 A-8).
 * 단계 넘김 규칙은 [HillMachine](hill.js 단계 함수들), 화면 힌트·회차 결과는 [HillView].
 */

/** 연습마다 단계 수 (hill.js `STEPS`). */
internal fun HillPractice.steps(): Int = when (this) {
    HillPractice.UP -> 4
    HillPractice.DOWN -> 3
    HillPractice.PARK -> 3
}

/** 연습 id 글자('up'·'down'·'park') — 대사 키 `hl1.<연습>.<n>`. */
internal val HillPractice.id: String get() = name.lowercase()

/** 상황 하나: id·맞는 앞바퀴 방향·`{side}` 글 (hill.js `situationOf`). 언덕 데이터가 없으면 FALLBACK_SIDE. */
internal class Situation(val id: HillSlope, val side: TurnDir, val sideText: String?)

internal fun situationOf(id: HillSlope): Situation {
    val d = HillGuide.situation(id)
    val side = TurnDir.fromCode(d?.side) ?: if (id == HillSlope.UP) TurnDir.LEFT else TurnDir.RIGHT
    return Situation(id, side, d?.sideText?.takeIf { it.isNotEmpty() })
}

/** hl1.park.2의 `{side}` 글 (hill.js `sideTextOf`). */
internal fun sideTextOf(id: HillSlope): String {
    val s = situationOf(id)
    return s.sideText ?: if (s.side == TurnDir.LEFT) "왼쪽" else "오른쪽"
}

/** 앞바퀴 쪽: [th]보다 작으면 똑바로(null) (hill.js `sideOf`). */
internal fun sideOf(steer: Double?, th: Double): TurnDir? = when {
    steer == null || kotlin.math.abs(steer) < th -> null
    steer < 0 -> TurnDir.LEFT
    else -> TurnDir.RIGHT
}

/** 앞바퀴가 [need] 쪽으로 [deg]° 이상인가 (hill.js `sideOk`). */
internal fun sideOk(steer: Double?, need: TurnDir?, deg: Double): Boolean {
    if (steer == null || need == null) return false
    return if (need == TurnDir.RIGHT) steer >= deg - EPS else steer <= -deg + EPS
}

/** 오르막 출발 시도 하나 (hill.js `up.cur`). */
internal class UpTry(val n: Int, val t0: Double) {
    /** 브레이크를 뗌(4단계에 감) — 뗀 시도만 센다. */
    var released = false
    var rolled = false
    var rollT: Double? = null

    /** 4단계 문장을 말함(차가 앞으로 나갈 때). */
    var line4 = false

    /** 3km/h 넘게 달리기 시작한 때(3초면 시도 끝). */
    var fastSince: Double? = null
}

/** 내리막 구간 하나 (hill.js `down.cur`). */
internal class DownTry(val n: Int, val t0: Double, var zoneId: String?) {
    /** 내비 내리막 주의 구간으로 시작함. */
    var viaNav = zoneId != null
    var inside = false

    /** 2단계(나눠 밟기)를 거침 — 거친 구간만 판정. */
    var step2 = false
    var presses = 0
    var holds = 0
    var brakeOn = false

    /** 이번 밟음을 나눠 밟기로 셈. */
    var counted = false

    /** 움직이는 동안 이번 밟음이 이어진 초. */
    var runSec = 0.0

    /** 이번 밟음을 '계속 밟음'으로 셈. */
    var flagged = false

    /** '계속 밟지 말고 나눠서 밟아요' 줄을 보임. */
    var warn = false
    var judged: HillDownRun? = null
}

/** 언덕 주차 한 번 (hill.js `park.cur`). */
internal class ParkTry(val n: Int, val t0: Double, var situation: HillSlope?, var need: TurnDir?) {
    /** P 때 판정(HL3가 같은 스냅숏에서 채움). */
    var judge: ParkJudge? = null

    /** 사이드브레이크 3초 창(PB2와 같은 창). */
    var hb: HandbrakeWindow? = null
}

/** P 순간 앞바퀴 판정 (hill.js `P.judge`). */
internal class ParkJudge(val t: Double, val steer: Double?) {
    var done = false
    var ok: Boolean? = null
    var side: TurnDir? = null
    var deg: Double? = null
}

/** 사이드브레이크 창 (hill.js `P.hb`). */
internal class HandbrakeWindow(val t0: Double) {
    var open = true
    var state: HandbrakeState? = null
}

/** 회차 상태 (hill.js `shared(ctx)`의 값). */
internal class HillState(cfg: SessionConfig) {
    var practice = HillPractice.UP
    var step = 1
    var done = false

    /** 차가 처음 움직임(단계 문장을 말하기 시작함). */
    var started = false

    /** P에 들어갔다 나옴 — 다시 움직이면 지금 단계 문장을 다시 말한다. */
    var sayAgain = false
    var gear: Gear = cfg.startGear
    var lastT: Double? = null
    var snap: VehicleSnapshot? = null

    /** 오르막 1단계: 이 단계에서 움직였나(처음 서 있던 것·같은 정지는 세지 않음). */
    var moved = false

    var upN = 0
    val upList = ArrayList<HillUpAttempt>()
    var upCur: UpTry? = null

    var downN = 0
    val downList = ArrayList<HillDownRun>()
    var downCur: DownTry? = null

    var parkN = 0
    val parkList = ArrayList<ParkTry>()
    var parkCur: ParkTry? = null

    val seenZones = HashSet<String>()

    /** HL2를 못 읽음(차속·뒤로 가는 속도, 설계 7.8). */
    val rollOff = !cfg.caps.speed || !cfg.caps.reverseSpeed
    val steerOff = !cfg.caps.steer

    /** 마지막으로 낸 힌트(seq 빼고) — 바뀔 때만 새 값을 낸다. */
    var hint: HillHint? = null
    var hintSeq = 0
}

/** 세션의 언덕 상태(처음 부른 검출기가 만든다). */
internal fun DetectorContext.hill(): HillState = shared(HillState::class) { HillState(cfg) }

/** 차속(못 읽으면 추적기가 트립 거리로 어림한 값, 설계 7.8) (hill.js `speedOf`). */
internal fun DetectorContext.speedOf(snap: VehicleSnapshot?): Double? = snap?.speed ?: track.v
