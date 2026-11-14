package com.barodrive.core.engine.detect.hill

import com.barodrive.core.engine.review.jsRound
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillDownHint
import com.barodrive.core.model.HillDownSummary
import com.barodrive.core.model.HillHint
import com.barodrive.core.model.HillParkHint
import com.barodrive.core.model.HillParkResult
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillResult
import com.barodrive.core.model.HillTaskCount
import com.barodrive.core.model.HillUpHint
import com.barodrive.core.model.HillUpSummary
import com.barodrive.core.model.HillWheelHint
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * 언덕 화면 힌트와 회차 결과 (프로토타입 detectors/hill.js `view`·`publish`·`parkResult`·`noteOf`, 결정사항 E T6-7).
 * HL-1·HL-2는 힌트를 그대로 그리고(판정 규칙은 엔진에만), 리뷰는 rec.hill.tasks를 과제 결과로 그대로 센다.
 */

/** 리뷰 아쉬운 것 글 — {n}은 리뷰가 실패 수로 바꾼다 (hill.js `noteOf`). */
private const val DOWN_HOLD = "내리막에서 브레이크 계속 밟음 {n}번"
private const val DOWN_SPLIT = "내리막에서 브레이크 나눠 밟기 놓침 {n}번"
private const val PARK_WHEEL = "언덕 주차 바퀴 방향 틀림 {n}번"
private const val PARK_HANDBRAKE = "언덕 주차 사이드브레이크 안 채움 {n}번"

/** 뒤로 가는 속도를 못 읽어 오르막 과제를 판정하지 않음 (T-HILLUP `off`). */
private const val OFF_REVERSE = "reverseSpeed"

/** 주차 한 번의 결과 (hill.js `parkResult`): 앞바퀴(핸들을 못 읽으면 null)·사이드브레이크·둘 다 맞으면 ok(둘 다 모르면 null). */
internal fun HillState.parkResult(p: ParkTry): HillParkResult {
    val j = p.judge
    val wheelOk = if (steerOff || j == null || !j.done) null else j.ok == true
    val hbState = p.hb?.let { it.state ?: if (it.open) null else HandbrakeState.UNKNOWN }
    val hb = when (hbState) {
        HandbrakeState.ON -> true
        HandbrakeState.OFF -> false
        else -> null
    }
    val judged = wheelOk != null || hb != null
    return HillParkResult(
        n = p.n, situation = p.situation, need = p.need, wheelOk = wheelOk, wheelSide = j?.side, wheelDeg = j?.deg, handbrake = hbState,
        ok = if (judged) wheelOk != false && hb != false else null,
    )
}

/** 화면 힌트 값, seq 빼고 (hill.js `view`). */
internal fun HillMachine.view(): HillHint {
    val snap = st.snap
    val d = st.downCur
    val p = st.parkCur
    val steer = snap?.steer
    val up = st.upCur
    val lastRun = st.downList.lastOrNull()
    val wheel = if (st.practice == HillPractice.PARK && steer != null) {
        HillWheelHint(
            side = sideOf(steer, ctx.th("steer.end")), deg = jsRound(abs(steer)),
            ok = if (p?.need != null) sideOk(steer, p.need, ctx.th("HL3.steer")) else null,
        )
    } else {
        null
    }
    val sit = p?.situation
    return HillHint(
        practice = st.practice, step = st.step, steps = st.practice.steps(), done = st.done, tap = isTap(),
        key = "hl1.${st.practice.id}.${st.step}",
        vars = if (st.practice == HillPractice.PARK && sit != null) JsonObject(mapOf("side" to JsonPrimitive(sideTextOf(sit)))) else null,
        cheer = st.practice == HillPractice.UP && st.step == 3 && !st.done,
        up = HillUpHint(
            attempts = st.upList.size + (if (up?.released == true) 1 else 0),
            rollbacks = st.upList.count { it.rolled } + (if (up?.rolled == true) 1 else 0),
            rolled = up?.rolled == true, rollOff = st.rollOff,
        ),
        down = HillDownHint(
            presses = d?.presses ?: 0, holds = d?.holds ?: 0, warn = d?.warn == true, braking = d?.brakeOn == true,
            bound = d?.zoneId != null, runs = st.downList.size, lastOk = lastRun?.ok,
        ),
        park = HillParkHint(
            situation = sit, need = p?.need, wheel = wheel, steerOff = st.steerOff, result = if (p?.judge != null) st.parkResult(p) else null,
        ),
        gearP = st.gear == Gear.P,
    )
}

/** 힌트를 바뀔 때만 새 값으로 낸다(seq가 오름) (hill.js `publish`). */
internal fun HillMachine.publish() {
    val v = view()
    if (v == st.hint) return
    st.hint = v
    ctx.hints.hill = v.copy(seq = ++st.hintSeq)
}

/**
 * 회차 결과 rec.hill (hill.js `noteOf`, 설계 8.1·6.4). 과제: T-HILLUP 시도마다 밀림 없음(뒤로 가는 속도를 못 읽으면 total 0) ·
 * T-HILLDOWN 내리막 구간마다 · T-HILLPARK 언덕 주차마다 바퀴 맞음 + 사이드브레이크 채움(못 읽는 쪽은 빼고, 둘 다 못 읽으면 안 셈).
 * 실패 까닭이 하나면 badText에 그 글, 섞이면 리뷰 기본 글.
 */
internal fun HillState.result(): HillResult {
    val ups = upList.toList()
    val runs = downList.toList()
    val parks = parkList.map { parkResult(it) }
    val judgedParks = parks.filter { it.ok != null }
    val up = if (rollOff) HillTaskCount(success = 0, total = 0, off = OFF_REVERSE) else HillTaskCount(success = ups.count { !it.rolled }, total = ups.size)
    val badDown = runs.filter { !it.ok }
    val downText = when {
        badDown.isNotEmpty() && badDown.all { it.holds > 0 } -> DOWN_HOLD
        badDown.isNotEmpty() && badDown.all { it.holds == 0 } -> DOWN_SPLIT
        else -> null
    }
    val badPark = judgedParks.filter { it.ok != true }
    val parkText = when {
        badPark.isNotEmpty() && badPark.all { it.wheelOk == false && it.handbrake != HandbrakeState.OFF } -> PARK_WHEEL
        badPark.isNotEmpty() && badPark.all { it.handbrake == HandbrakeState.OFF && it.wheelOk != false } -> PARK_HANDBRAKE
        else -> null
    }
    return HillResult(
        tasks = linkedMapOf(
            "T-HILLUP" to up,
            "T-HILLDOWN" to HillTaskCount(success = runs.count { it.ok }, total = runs.size, badText = downText),
            "T-HILLPARK" to HillTaskCount(success = judgedParks.count { it.ok == true }, total = judgedParks.size, badText = parkText),
        ),
        up = HillUpSummary(attempts = ups.size, rollbacks = ups.count { it.rolled }, rollOff = rollOff, list = ups),
        down = HillDownSummary(runs = runs.size, presses = runs.sumOf { it.presses }, holds = runs.sumOf { it.holds }, list = runs),
        park = parks.lastOrNull(), parks = parks, practice = practice, step = step, done = done,
    )
}
