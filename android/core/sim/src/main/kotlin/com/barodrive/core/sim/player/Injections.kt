package com.barodrive.core.sim.player

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.CautionType
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.TurnDir
import com.barodrive.core.sim.BaroSim
import com.barodrive.core.sim.InjectionInfo
import com.barodrive.core.sim.navi.TurnRef
import com.barodrive.core.sim.navi.Upcoming
import com.barodrive.core.sim.scenario.TurnPlan
import com.barodrive.core.sim.util.JsMath
import com.barodrive.core.sim.vehicle.Vehicle
import com.barodrive.core.sim.vehicle.VehiclePatch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 시연 패널 '코칭 이벤트 넣기' 버튼 (프로토타입 sim/player.js `INJ`, 설계 7.10 표에서 '운전자: 방금 어땠어?'(K8, 엔진 입력)를 뺀 전부).
 * [run]은 차·내비·운전자를 바꾸고 패널에 보일 짧은 글을 돌려준다. 손 운전(경로 없음)·재생 중 모두 오류 없이 돈다.
 */
class Injections internal constructor(private val sim: BaroSim) {
    private class Def(val info: InjectionInfo, val run: () -> String)

    /** 마지막으로 끼운 회전 { 회전 id · 운전자 계획 id }. */
    private var lastInjected: Pair<String, String>? = null

    private fun v() = sim.vehicle.state
    private fun moving() = abs(v().speed) > 0.5
    private fun setI(patch: VehiclePatch) {
        sim.vehicle.set(patch, Vehicle.Source.INJECT)
    }

    private fun nextTurnWithin(maxM: Double): Upcoming? {
        val n = sim.navi.nextTurn()
        return if (n != null && n.remain > 5 && n.remain <= maxM) n else null
    }

    private fun planId(turn: TurnRef): String = turn.pathTurn?.id ?: turn.id

    private fun ensureTurn(dist: Double): Upcoming? {
        val near = nextTurnWithin(260.0)
        if (near != null && near.remain >= 60) return near
        val r = sim.navi.injectTurn(distance = dist, dir = TurnDir.RIGHT) ?: return null
        lastInjected = Pair(r.turn.id, planId(r.turn))
        return Upcoming(r.turn, sim.navi.distTo(r.turn.turn))
    }

    private fun injectedAhead(): Upcoming? {
        val li = lastInjected ?: return null
        return sim.navi.upcoming().firstOrNull { it.ref.id == li.first }
    }

    private fun blinkAt(m: Double): String {
        // 이미 그 거리 안으로 들어온 회전(또는 지난 회전)에는 걸지 않는다 — "회전 -12m 앞"처럼 보이지 않게(T11-2)
        val far = { x: Upcoming? -> if (x != null && x.remain > m + 10) x else null }
        var e = far(injectedAhead()) ?: far(nextTurnWithin(400.0))
        if (e == null) {
            val r = sim.navi.injectTurn(distance = max(100.0, m + 45), dir = TurnDir.RIGHT) ?: return "회전을 넣을 자리가 없어요"
            lastInjected = Pair(r.turn.id, planId(r.turn))
            e = Upcoming(r.turn, sim.navi.distTo(r.turn.turn))
        }
        sim.driver.turnPlan(planId(e.ref), TurnPlan(blink = true, blinkAt = m, skillApplies = false))
        return "회전 ${JsMath.str(JsMath.round(e.remain))}m 앞 · ${JsMath.str(m)}m에서 깜빡이"
    }

    private val defs: List<Def> = listOf(
        Def(InjectionInfo("beltStart", "벨트 풀고 출발", "출발 전", "A1")) {
            setI(VehiclePatch(beltDriver = false))
            when {
                moving() -> "달리는 중이라 벨트만 풀었어요"
                v().gear == Gear.P -> {
                    sim.clock.later(0.5) { setI(VehiclePatch(gear = Gear.D)) }
                    "벨트를 풀고 기어 D로 바꿔요"
                }
                else -> "벨트를 풀었어요 · P에서 D로 바꿀 때 판정해요"
            }
        },
        Def(InjectionInfo("handbrakeStart", "사이드브레이크 채운 채 출발", "출발 전", "PB1")) {
            if (moving()) return@Def "멈춘 뒤에 눌러요"
            setI(VehiclePatch(handbrake = true))
            if (v().gear == Gear.P) sim.clock.later(0.5) { setI(VehiclePatch(gear = Gear.D)) }
            sim.clock.later(3.5) { setI(VehiclePatch(handbrake = false)) }
            "사이드브레이크를 채운 채 D · 3초 뒤 풀어요"
        },
        Def(InjectionInfo("passenger", "조수석 타기 / 내리기", "출발 전", "L1")) {
            setI(VehiclePatch(passenger = !v().passenger))
            if (v().passenger) "조수석에 탔어요" else "조수석에서 내렸어요"
        },
        Def(InjectionInfo("hardLaunch", "급출발", "출발·정지", "B1")) {
            val poor = sim.driver.skill == DriverSkill.POOR
            when {
                v().gear == Gear.P -> {
                    setI(VehiclePatch(gear = Gear.D))
                    sim.clock.later(0.6) { sim.driver.launchNext(13.0 - (if (sim.driver.skill == DriverSkill.POOR) 4 else 0)) }
                    "기어 D · 1초에 13km/h로 출발해요"
                }
                moving() -> {
                    sim.driver.launchNext(13.0)
                    "다음 출발 때 급출발해요"
                }
                else -> if (sim.driver.launchNext(if (poor) 9.0 else 13.0) == "now") "1초에 13km/h로 출발해요" else "다음 출발 때 급출발해요"
            }
        },
        Def(InjectionInfo("hardStop", "급정지", "출발·정지", "F1")) {
            if (abs(v().speed) < 10) return@Def "10km/h 넘게 달릴 때 눌러요"
            sim.driver.stopNow(drop = 9.0, hold = 4.0, raw = true)
            "1초에 9km/h씩 줄여 멈춰요"
        },
        Def(InjectionInfo("hazardBrake", "위험 회피 급제동", "출발·정지", "F2")) {
            if (abs(v().speed) < 10) return@Def "10km/h 넘게 달릴 때 눌러요"
            sim.driver.stopNow(drop = 14.0, hold = 3.0, raw = true)
            "1초에 14km/h씩 줄여 멈춰요"
        },
        Def(InjectionInfo("turnAhead", "회전 다가옴(깜빡이 끔)", "깜빡이", "C1")) {
            val r = sim.navi.injectTurn(distance = 100.0, dir = TurnDir.RIGHT) ?: return@Def "회전을 넣을 자리가 없어요"
            lastInjected = Pair(r.turn.id, planId(r.turn))
            sim.driver.turnPlan(planId(r.turn), TurnPlan(blink = false, skillApplies = false))
            if (v().blinker != Blinker.OFF) sim.driver.blinkNow(Blinker.OFF)
            val d = JsMath.round(sim.navi.distTo(r.turn.turn))
            (if (r.bound) "다가오는 회전" else "100m 앞 우회전") + "(" + JsMath.str(d) + "m) · 깜빡이 끔"
        },
        Def(InjectionInfo("blink55", "깜빡이 55m에서 켬", "깜빡이", "C2")) { blinkAt(55.0) },
        Def(InjectionInfo("blink25", "깜빡이 25m에서 켬", "깜빡이", "C2")) { blinkAt(25.0) },
        Def(InjectionInfo("quickSteer", "켜자마자 꺾기", "깜빡이", "C4")) {
            val n = sim.navi.nextTurn()
            val side = if (n != null && n.ref.dir == TurnDir.LEFT) Blinker.LEFT else Blinker.RIGHT
            sim.driver.blinkNow(side)
            sim.driver.steerPulse(if (side == Blinker.LEFT) -15.0 else 15.0, 3.0, 1.0)
            "깜빡이 켜고 1초 뒤 핸들 15° 3초"
        },
        Def(InjectionInfo("steerNoBlink", "깜빡이 없이 회전", "깜빡이", "C5")) {
            if (v().blinker != Blinker.OFF) sim.driver.blinkNow(Blinker.OFF)
            sim.driver.steerPulse(15.0, 3.0, 0.0)
            "깜빡이 없이 핸들 15° 3초"
        },
        Def(InjectionInfo("wrongBlink", "반대 깜빡이", "깜빡이", "C3")) {
            // 다가오는 회전(260m 안이면 그 회전, 없으면 110m 앞에 끼움)에 '반대쪽 깜빡이 → 3초 뒤 바로잡기'를 건다(T11-2)
            val e = ensureTurn(110.0) ?: return@Def "회전을 넣을 자리가 없어요"
            val at = max(20.0, min(140.0, JsMath.round(e.remain) - 15))
            sim.driver.turnPlan(planId(e.ref), TurnPlan(blink = true, wrong = true, blinkAt = at, skillApplies = false))
            "회전 ${JsMath.str(JsMath.round(e.remain))}m 앞 · ${JsMath.str(at)}m에서 반대쪽 깜빡이, 3초 뒤 바로잡아요"
        },
        Def(InjectionInfo("blinkStays", "깜빡이 켜 둔 채 직진", "깜빡이", "C6")) {
            val n = nextTurnWithin(250.0)
            if (n != null) {
                sim.driver.turnPlan(planId(n.ref), TurnPlan(keepOn = true, skillApplies = false))
                // 운전자가 이미 그 회전 깜빡이를 켰으면 계획은 다시 돌지 않는다 → 지금 켜진 깜빡이를 켜 둔다(T11-2)
                if (v().blinker != Blinker.OFF) sim.driver.keepBlinkerOn()
                return@Def "다음 회전 뒤 깜빡이를 15초 켜 둬요"
            }
            sim.driver.blinkNow(Blinker.RIGHT, dur = 13.0, keepOn = true)
            "오른쪽 깜빡이를 켠 채 13초 직진"
        },
        Def(InjectionInfo("speed30", "30 구역 과속", "속도", "E1")) {
            sim.navi.overrideLimit(30, meters = 260.0)
            sim.driver.forceOver(38.0, 6.0)
            if (v().gear == Gear.P) setI(VehiclePatch(gear = Gear.D))
            "제한 30 · 38km/h 6초"
        },
        Def(InjectionInfo("schoolSpeed", "어린이보호구역 과속", "속도", "E2")) {
            sim.driver.forceOver(35.0, 8.0)
            sim.navi.overrideSchool(true, meters = 240.0, startIn = max(8.0, abs(v().speed) / 3.6 * 1.2))
            if (v().gear == Gear.P) setI(VehiclePatch(gear = Gear.D))
            "어린이보호구역 진입 · 35km/h"
        },
        Def(InjectionInfo("wobble", "핸들 크게 흔들기", "핸들", "G1")) {
            sim.driver.wobble(2.0, 20.0)
            "다음 직진 20초 흔들림 2.0°"
        },
        Def(InjectionInfo("rain", "비 오기 시작", "날씨·길", "J3")) {
            setI(VehiclePatch(wiper = true))
            "와이퍼 켬 · 30초 넘게 켜져 있으면 비 오는 날 안내"
        },
        Def(InjectionInfo("slippery", "미끄러운 길", "날씨·길", "J3")) {
            sim.navi.overrideCaution(CautionType.SLIPPERY_ROAD_ALERT, ahead = 300.0, len = 120.0)
            "300m 앞 미끄러운 길 주의 구간"
        },
        Def(InjectionInfo("downhill", "내리막 주의", "날씨·길", "HL1")) {
            sim.navi.overrideCaution(CautionType.DOWNHILL_CAUTION_AREA, ahead = 300.0, len = 200.0)
            "300m 앞 내리막 주의 구간"
        },
        Def(InjectionInfo("jam", "정체 구간", "날씨·길", null)) {
            sim.navi.overrideJam(true, meters = 400.0)
            "지금부터 400m 혼잡"
        },
        Def(InjectionInfo("naviVoice", "내비 음성", "내비", null)) {
            val n = sim.navi.nextTurn()
            val text = if (n != null) sim.navi.turnLine(n.ref, max(100.0, JsMath.round(n.remain / 10) * 10)) else "전방에 과속 방지턱이 있습니다"
            sim.navi.voice(text, key = "nav.inject")
            "내비 음성 2.5초"
        },
        Def(InjectionInfo("reroute", "재탐색(회전 놓침)", "내비", null)) {
            val tr = sim.navi.missTurn()
            if (tr != null) "다음 회전(" + JsMath.str(JsMath.round(tr.sStart - v().s)) + "m)을 지나쳐요"
            else "놓칠 회전이 없어요(안내 중 다가오는 회전이 있어야 해요)"
        },
        Def(InjectionInfo("rollback", "뒤로 밀림", "언덕·야간", "HL2")) {
            if (v().gear != Gear.D) return@Def "기어 D에서 멈춘 채로 눌러요"
            if (moving()) return@Def "멈춘 뒤에 눌러요"
            sim.driver.rollback(2.0, 1.0)
            "기어 D에서 −2km/h 1초"
        },
        Def(InjectionInfo("nightNoLamp", "밤인데 전조등 끔", "언덕·야간", "J1")) {
            setI(VehiclePatch(night = true, lowBeam = false))
            "야간 · 하향등 끔"
        },
        Def(InjectionInfo("arrive", "목적지 도착", "내비", null)) {
            val ok = sim.navi.arrive(forced = true)
            sim.driver.park()
            if (ok) "도착 → 멈추고 P" else "안내 중인 경로가 없어 멈추고 P만 해요"
        },
    )

    /** 버튼 목록(설계 7.10 순서). */
    val list: List<InjectionInfo> = defs.map { it.info }

    /** 버튼 [id]를 누른다 → 패널에 보일 글. 없는 id면 [IllegalArgumentException]. */
    fun run(id: String): String {
        val d = defs.firstOrNull { it.info.id == id } ?: throw IllegalArgumentException("주입 없음: $id")
        return d.run()
    }

    internal fun reset() {
        lastInjected = null
    }
}
