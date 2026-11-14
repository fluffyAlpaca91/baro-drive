package com.barodrive.core.sim.driver

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.TurnDir
import com.barodrive.core.sim.BaroSim
import com.barodrive.core.sim.PlayerState
import com.barodrive.core.sim.SimEvent
import com.barodrive.core.sim.route.Piece
import com.barodrive.core.sim.route.Route
import com.barodrive.core.sim.route.RouteTurn
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.DriveKind
import com.barodrive.core.sim.scenario.TurnPlan
import com.barodrive.core.sim.util.JsMath
import com.barodrive.core.sim.util.SeedRandom
import com.barodrive.core.sim.vehicle.CarState
import com.barodrive.core.sim.vehicle.Vehicle
import com.barodrive.core.sim.vehicle.VehiclePatch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 운전자 모델 (프로토타입 sim/driver.js `BaroSim.driver`, 설계 7.3·7.11). 틱 단계 'driver'(20).
 * 재생 중(재생기 PLAYING)이면 시나리오 운전, 아니면 손 조작(시연 패널)을 따른다.
 *
 * - 속도: 목표 = min(구간 제한속도, 순항(다리 cruise > 시나리오 cruise)) · 어린이보호구역 30 · 정체 25 · 회전 15(유턴 10, 큰 반지름 √(2r)·3.6)
 *   · 가속 4.7 · 감속 5.4(브레이크) · 작은 감속은 엔진 브레이크. 앞의 회전·정지·제한속도를 일정 감속으로 미리 맞춘다.
 *   출발은 처음 1초 남짓 gain km/h/s, 정지해 기다리는 동안 브레이크를 밟는다.
 * - 핸들: 회전 구간에서 δ = atan(축거 / 반지름)까지 돌리고 끝나면 푼다. steerAfter면 깜빡이 켠 뒤 그 초에 7°로 먼저 꺾는다.
 *   직진에는 흔들림 잡음(0.3·0.5·0.7Hz 합, 10초 창 표준편차 = wobble).
 * - 깜빡이: 회전마다 blinkAt m 앞에서 켜고, 10° 넘게 꺾었다 3° 안으로 풀리면 끔(keepOn이면 15초 뒤).
 */
class Driver internal constructor(private val sim: BaroSim) {
    /** 단계: IDLE(P) · DEPART(출발 전 확인) · RUN · PARKED. */
    enum class Phase { IDLE, DEPART, RUN, PARKED }

    /** 시나리오 운전 계획(`configure`가 정함). */
    data class Plan(val cruise: Double? = null, val turns: Map<Int, TurnPlan> = emptyMap(), val wobble: Double? = null, val drive: DriveKind = DriveKind.ROUTE)

    /** 회전 하나에 대한 운전 계획(planFor 결과). */
    data class TurnDrive(
        val blink: Boolean,
        val blinkAt: Double,
        val wrong: Boolean,
        val keepOn: Boolean,
        val miss: Boolean,
        val steerAfter: Double?,
        val early: Double?,
        val fromInject: Boolean,
        val auto: Boolean,
    )

    private class Stopping(val dec: Double, val hold: Double, val kind: String, val stop: Any? = null, val then: String? = null, val startT: Double? = null)
    private class Constraint(val s: Double, val v: Double, val dec: Double, val kind: String, val stop: Any? = null)
    private class Holding(val until: Double, val since: Double)
    private class PendingStop(var s: Double, val dec: Double, val hold: Double)
    private class EndStop(var s: Double, val dec: Double, val handbrake: Boolean?)
    private class Launching(val gain: Double, val untilV: Double)
    private class Over(val speed: Double, val dur: Double, var endT: Double?)
    private class Wait(val speed: Double, val until: Double)
    private class Roll(val speed: Double, val until: Double)
    private class Pulse(val n: Int, var i: Int, var on: Boolean, var until: Double)
    private class Pedal(val until: Double?)
    private class SteerPulse(val angle: Double, val start: Double, val end: Double)
    private class WobbleM(val std: Double, var left: Double)
    private class EvSteer(val angle: Double, val until: Double?)
    private class CancelWatch(val side: Blinker, var peak: Double = 0.0, var keepOnUntil: Double? = null, var keepOn: Boolean = false)
    private class TurnRt {
        var blinkOnT: Double? = null
        var steerOnT: Double? = null
        var done = false
        var correctAt: Double? = null
        var blinked = false
        var inArc = false
        var steerTarget: Double? = null
        var full: Double? = null
    }

    /** 시연 패널 손 조작(가속·브레이크 누르기, 브레이크 고정, 목표 속도, 기어). */
    inner class Manual internal constructor() {
        /** 손 운전 목표 속도(km/h 크기). */
        var target: Double = 0.0
            internal set
        var throttle: Boolean = false
            internal set
        var brakeHeld: Boolean = false
            internal set
        var brakeLock: Boolean = false
            internal set

        /** 목표 속도(패널 슬라이더). */
        fun setTarget(v: Double) {
            target = JsMath.clamp(abs(if (v.isNaN()) 0.0 else v), 0.0, 180.0)
            manualSetT = time
            sim.clock.paused = false
        }

        /** 가속('throttle')·브레이크('brake') 페달을 누름·뗌. */
        fun press(name: String, on: Boolean) {
            if (name == "throttle") throttle = on else if (name == "brake") brakeHeld = on
            sim.clock.paused = false
            sim.clock.keepAlive(3.0)
            takeOver(name)
        }

        /** 브레이크 고정. */
        fun lock(on: Boolean) {
            brakeLock = on
            sim.clock.paused = false
            sim.clock.keepAlive(3.0)
            if (on) takeOver("brake")
        }

        /** 패널 기어 버튼: 달리는 중 P·R(앞으로 갈 때)·D(후진 중)면 먼저 세우고 바꾼다. → 'now' | 'later'. */
        fun shift(g: Gear): String {
            val v = veh()
            val moving = abs(v.speed) > 0.5
            val reverse = (g == Gear.R && v.speed > 0.5) || (g == Gear.D && v.speed < -0.5)
            takeOver("gear")
            if (moving && (g == Gear.P || reverse)) {
                shiftAfter = g
                target = 0.0
                sim.clock.paused = false
                sim.clock.keepAlive(3.0)
                return "later"
            }
            shiftAfter = null // 바로 바꾼 기어가 이긴다: 그 전에 미뤄 둔 기어(멈춘 뒤 R 등)는 취소(키보드 PgUp·PgDn으로 오갈 때)
            sim.vehicle.set(VehiclePatch(gear = g), Vehicle.Source.PANEL)
            return "now"
        }

        internal fun clear() {
            target = 0.0; throttle = false; brakeHeld = false; brakeLock = false
        }
    }

    val manual = Manual()

    /** 지금 실력. */
    var skill: DriverSkill = DriverSkill.NORMAL

    var plan: Plan = Plan()
        private set
    private var phases = doubleArrayOf(0.0, 1.3, 2.6)

    /** 운전자 자신의 시각(초, 계속 늘어남). */
    var time: Double = 0.0
        private set

    var phase: Phase = Phase.IDLE
        private set
    private var gearT = -1.0
    private var stopping: Stopping? = null
    private var slowing: Constraint? = null
    private var holding: Holding? = null
    private var pendingStops: List<PendingStop> = emptyList()
    private var endStop: EndStop? = null
    private var launching: Launching? = null
    private var armedGain: Double? = null
    private var overM: Over? = null
    private var waitM: Wait? = null
    private var rollM: Roll? = null
    private var pulseM: Pulse? = null
    private var holdBrakeM: Double? = null
    private var pedal: Pedal? = null
    private var steerM: SteerPulse? = null
    private var wobbleM: WobbleM? = null
    private var blinkOffAt: Double? = null
    private var shiftAfter: Gear? = null
    private var parkHandbrake: Boolean? = null
    private var turnRt = HashMap<String, TurnRt>()
    private var injPlans = HashMap<String, TurnPlan>()
    private var lastTurnEndT = -99.0
    private var lastStopEndT = -99.0
    private var lastLaunchT = -99.0
    private var blendStart = -99.0
    private var cancelWatch: CancelWatch? = null
    private var lastAuto = false
    private var autoHandbrakeAt = -1.0
    private var autoBeltAt = -1.0
    private var evSteer: EvSteer? = null
    private var unwindRate = 15.0
    private var manualSetT = -99.0
    private var overEnded = -99.0
    private var lastCap = Double.POSITIVE_INFINITY

    private fun veh(): CarState = sim.vehicle.state
    private fun path(): Route? = sim.navi.path
    private val spec: SkillSpec get() = SkillSpec.of(skill)

    private fun takeOver(key: String) {
        val p = sim.player
        if (p.state == PlayerState.PLAYING) {
            p.pause(manual = true)
            sim.emit(SimEvent.Manual(key))
        }
    }

    /** 패널이 재생을 손으로 넘겨받음(속도 슬라이더 등). */
    fun takeOverBy(key: String) = takeOver(key)

    /* ---------- 회전 ---------- */

    /** 회전 속도: 유턴 10 · 반지름 30m 넘으면 √(2r)·3.6(20~90) · 그 밖 15. */
    fun turnSpeed(tr: RouteTurn): Double {
        if (tr.uturn || tr.angle >= 150) return V_UTURN
        if (tr.r >= 30) return JsMath.clamp(sqrt(2 * tr.r) * 3.6, 20.0, 90.0)
        return V_TURN
    }

    /** 회전 중 앞바퀴각 δ = atan(축거 / 반지름)(차종 최대각까지), 왼쪽 음수. */
    fun steerAngle(tr: RouteTurn): Double {
        val m = sim.vehicle.model()
        val d = JsMath.atan(m.wheelbase / tr.r) / Math.PI * 180
        return (if (tr.dir == TurnDir.LEFT) -1 else 1) * min(m.maxSteer, d)
    }

    private fun prevTurnEnd(tr: RouteTurn): Double {
        val p = path() ?: return 0.0
        var end = 0.0
        for (x in p.turns) {
            if (x === tr) break
            if (x.sEnd <= tr.sStart) end = x.sEnd
        }
        return end
    }

    private fun defaultBlinkAt(tr: RouteTurn): Double = if (tr.ic) 140.0 else if (!tr.nav) 15.0 else 50.0

    /** 그 회전을 어떻게 도는지(시나리오 turns + 주입 계획 + 실력). */
    fun planFor(tr: RouteTurn): TurnDrive {
        val sk = spec
        val isAuto = plan.drive != DriveKind.EVENTS
        var cfg = TurnPlan()
        val n = tr.n
        if (n != null && !tr.injected) plan.turns[n]?.let { cfg = it }
        if (tr.rerouted) cfg = TurnPlan(blinkAt = cfg.blinkAt, steerAfter = cfg.steerAfter) // 다시 찾은 회전은 그냥 잘 돈다
        val inj = injPlans[tr.id]
        if (inj != null) cfg = cfg.mergedWith(inj)
        var blink = !(cfg.blink == false || cfg.blinkAt == 0.0 || cfg.noBlink == true)
        var blinkAt = cfg.blinkAt ?: defaultBlinkAt(tr)
        var wrong = cfg.wrong == true
        var keepOn = cfg.keepOn == true
        var steerAfter = cfg.steerAfter
        var early: Double? = null
        if (inj == null || inj.skillApplies != false) {
            if (skill == DriverSkill.POOR) {
                blinkAt *= sk.blinkMul
                if (steerAfter != null) steerAfter = max(0.5, steerAfter + sk.steerShift)
                else early = -sk.steerShift // 자연스러운 때보다 1.5초 먼저 꺾기 시작
            } else if (skill == DriverSkill.GOOD) {
                blinkAt = max(blinkAt, if (tr.nav) sk.blinkMin ?: blinkAt else blinkAt)
                if (steerAfter != null) steerAfter = max(steerAfter, sk.steerMin ?: steerAfter)
                if (inj == null) {
                    blink = true; wrong = false; keepOn = false
                }
            }
        }
        val avail = tr.sStart - prevTurnEnd(tr) - 3
        if (avail > 5 && blinkAt > avail && inj == null) blinkAt = avail
        return TurnDrive(blink, blinkAt, wrong, keepOn, cfg.miss == true && !tr.rerouted, steerAfter, early, inj != null, isAuto)
    }

    private fun rtFor(tr: RouteTurn): TurnRt = turnRt.getOrPut(tr.id) { TurnRt() }

    /* ---------- 깜빡이 ---------- */

    private fun setBlinker(side: Blinker, why: Vehicle.Source = Vehicle.Source.DRIVER) {
        val v = veh()
        if (v.blinker != side) sim.vehicle.set(VehiclePatch(blinker = side), why)
        cancelWatch = if (side == Blinker.OFF) null else CancelWatch(side)
    }

    private fun blinkerTick(v: CarState) {
        // 차의 자동 해제: 같은 방향으로 10° 넘게 꺾었다가 3° 안으로 풀리면 끔
        val off = blinkOffAt
        if (off != null && time >= off) {
            blinkOffAt = null
            setBlinker(Blinker.OFF)
        }
        if (v.blinker == Blinker.OFF) {
            cancelWatch = null
            return
        }
        var cw = cancelWatch
        if (cw == null || cw.side != v.blinker) {
            cw = CancelWatch(v.blinker)
            cancelWatch = cw
        }
        val dirSteer = if (v.blinker == Blinker.LEFT) -v.steer else v.steer
        if (dirSteer > cw.peak) cw.peak = dirSteer
        val until = cw.keepOnUntil
        if (until != null) {
            if (time >= until) setBlinker(Blinker.OFF)
            return
        }
        if (cw.peak >= 10 && abs(v.steer) < 3) {
            if (cw.keepOn) cw.keepOnUntil = time + KEEP_ON_SEC else setBlinker(Blinker.OFF)
        }
    }

    /* ---------- 흔들림 ---------- */

    private fun noise(t: Double): Double {
        var x = 0.0
        for (i in 0 until 3) x += AMP[i] * JsMath.sin(2 * Math.PI * FREQ[i] * t + phases[i])
        return x
    }

    private fun wobbleStd(): Double = wobbleM?.std ?: plan.wobble ?: spec.wobble

    /* ---------- 사건 구간·순항(압축용) ---------- */

    /** 사건 구간인가(압축 ×3, 설계 7.2). */
    fun eventWindow(): Boolean {
        val v = veh()
        val h = holding
        if (h != null && pedal == null && h.until - time > 5 && time - h.since > 5) return false // 긴 신호 대기 가운데는 ×6
        if (stopping != null || holding != null || launching != null || rollM != null || pulseM != null || overM != null ||
            steerM != null || pedal != null || waitM != null || holdBrakeM != null
        ) return true
        if (phase == Phase.DEPART || phase == Phase.IDLE) return true
        if (time - lastTurnEndT < 5 || time - lastStopEndT < 5 || time - lastLaunchT < 5) return true
        if (abs(v.speed) < 0.5) return true
        val p = path() ?: return false
        val s = v.s
        for (st in pendingStops) {
            val d = st.s - s
            if (d > -1 && d < max(40.0, abs(v.speed) / 3.6 * 5 + 20)) return true
        }
        for (tr in p.turns) {
            if (tr.sEnd < s) continue
            if (tr.sStart - s <= 150) return true
            break
        }
        for (z in p.zones) if (abs(z.s0 - s) < 50 && z.s0 > 0) return true
        for (z in p.schools) if (abs(z.s0 - s) < 50 || abs(z.s1 - s) < 30) return true
        return false
    }

    /** 순항 중인가(압축 ×10). */
    fun steady(): Boolean {
        val v = veh()
        val p = path()
        if (p == null || abs(v.speed) < 10) return false
        val s = v.s
        val base = autoBase(v)
        if (abs(abs(v.speed) - base) > 1) return false
        for (tr in p.turns) {
            if (tr.sEnd < s) continue
            if (tr.sStart - s < 300) return false
            break
        }
        if (pendingStops.any { it.s - s > -1 && it.s - s < 250 }) return false
        val e = endStop
        if (e != null && e.s - s < 300) return false
        for (z in p.zones) if (z.s0 > s && z.s0 - s < 200) return false
        if (sim.navi.jamNow()) return false
        return true
    }

    /* ---------- 목표 속도 ---------- */

    private fun pieceTarget(p: Piece): Double {
        var v = p.cruise ?: plan.cruise ?: 40.0
        if (p.limit != null) v = min(v, p.limit.toDouble())
        return v
    }

    private fun autoBase(v: CarState): Double {
        val p = path() ?: return 0.0
        val pc = p.pieceAt(v.s)
        var x = pieceTarget(pc)
        if (sim.navi.schoolNow()) x = min(x, 30.0)
        if (sim.navi.jamNow()) x = min(x, JAM_SPEED)
        val tr = pc.turn
        if (pc.kind == Piece.Kind.ARC && tr != null) x = min(x, turnSpeed(tr))
        overM?.let { x = abs(it.speed) }
        waitM?.let { x = it.speed }
        return max(0.0, x)
    }

    private fun constraints(v: CarState): List<Constraint> {
        val p = path()
        val out = ArrayList<Constraint>()
        if (p == null || p.loop) return out
        val s = v.s
        val h = 450.0
        for (tr in p.turns) {
            if (tr.sEnd < s) continue
            if (tr.sStart - s > h) break
            if (s < tr.sStart) out += Constraint(tr.sStart, turnSpeed(tr), DEC, "turn")
        }
        for (st in pendingStops) {
            if (st.s < s - 2 || st.s - s > h) continue
            out += Constraint(max(st.s, s), 0.0, st.dec, "stop", st)
        }
        val e = endStop
        if (e != null && e.s - s < h) out += Constraint(max(e.s, s), 0.0, e.dec, "end", e)
        if (overM == null && waitM == null) {
            val cur = autoBase(v)
            for (pc in p.pieces) {
                if (pc.s1 <= s) continue
                if (pc.s0 - s > h) break
                if (pc.s0 <= s) continue
                val t = pieceTarget(pc)
                if (t < cur - 0.01) out += Constraint(pc.s0, t, DEC, "zone")
            }
            for (z in p.schools) if (z.s0 > s && z.s0 - s < h) out += Constraint(z.s0, 30.0, DEC, "school")
        }
        if (e == null) out += Constraint(p.length - 1, 0.0, DEC, "pathEnd")
        return out
    }

    /** 지금 브레이크를 밟아야 할 제약 하나(가장 센 감속)와, 더 빨라져도 되는 한계(제동 포락선 · 회전 바로 앞이면 회전 속도). */
    private fun urgent(v: CarState, m: Double, dt: Double): Constraint? {
        val vms = m / 3.6
        var best: Constraint? = null
        var cap = Double.POSITIVE_INFINITY
        for (c in constraints(v)) {
            val d = max(0.0, c.s - v.s)
            val env = 3.6 * sqrt(max(0.0, (c.v / 3.6) * (c.v / 3.6) + 2 * (c.dec / 3.6) * d))
            cap = min(cap, if (c.kind == "turn" && d < 8) c.v else env)
            if (m <= c.v + 1e-6) continue
            val need = (vms * vms - (c.v / 3.6) * (c.v / 3.6)) / (2 * (c.dec / 3.6))
            if (d <= need + vms * dt * 0.5) {
                val b = best
                if (b == null || c.dec > b.dec || (c.dec == b.dec && c.v < b.v)) best = c
            }
        }
        lastCap = cap
        return best
    }

    /* ---------- 출발 ---------- */

    /** 실력으로 바꾼 출발 세기(km/h/s). */
    fun adjGain(g: Double?): Double {
        val sk = spec
        var x = g ?: ACC
        if (skill == DriverSkill.POOR) x += sk.gainAdd else if (skill == DriverSkill.GOOD) x = min(x, sk.gainMax ?: x)
        return x
    }

    /** 실력으로 바꾼 정지 세기(km/h/s). 11 넘는 급제동은 실력과 상관없이 그대로. */
    fun adjDrop(d: Double?): Double {
        val sk = spec
        var x = d ?: DEC
        if (skill == DriverSkill.POOR) x = if (x >= 11) x else min(10.9, x + sk.dropAdd)
        else if (skill == DriverSkill.GOOD && x < 11) x = min(x, sk.dropMax ?: x)
        return x
    }

    private fun startLaunch(): Launching? {
        val g = armedGain ?: adjGain(null)
        armedGain = null
        lastLaunchT = time
        launching = if (g > ACC + 0.01) Launching(g, max(5 + 1.05 * g + 0.5, 8.0)) else null
        return launching
    }

    /* ---------- 속도 한 틱 ---------- */

    private fun speedTick(dt: Double, v: CarState, auto: Boolean) {
        val g = v.gear
        if (g == Gear.P) {
            v.speed = 0.0
            v.brake = !auto && (manual.brakeHeld || manual.brakeLock)
            stopping = null; launching = null; rollM = null
            return
        }
        val dirS = if (g == Gear.R) -1 else 1
        var m = max(0.0, v.speed * dirS) // 진행 방향 속도 크기
        var brake = false

        rollM?.let { r ->
            if (time < r.until) {
                v.speed = r.speed
                v.brake = false
                return
            }
            rollM = null
            v.speed = 0.0
            holding = Holding(time + 1.5, time)
            m = 0.0
        }
        stopping?.let { st ->
            m = max(0.0, m - st.dec * dt)
            if (m <= 1e-6) {
                m = 0.0
                stopping = null
                lastStopEndT = time
                finishStop(st)
            }
            v.speed = JsMath.orZero(m * dirS)
            v.brake = true
            return
        }
        holding?.let { h ->
            if (time < h.until || pedal != null) {
                v.speed = 0.0
                v.brake = true
                return
            }
            holding = null
            lastStopEndT = time
            if (auto) startLaunch()
        }
        if (pedal != null || (!auto && (manual.brakeHeld || manual.brakeLock)) || shiftAfter != null) {
            val pd = pedal
            if (pd?.until != null && time >= pd.until) {
                pedal = null
                if (auto && m < 0.5 && phase == Phase.RUN) startLaunch()
            } else {
                m = max(0.0, m - DEC * dt)
                v.speed = JsMath.orZero(m * dirS)
                v.brake = true
                val sa = shiftAfter
                if (m == 0.0 && sa != null) {
                    shiftAfter = null
                    sim.vehicle.set(VehiclePatch(gear = sa), Vehicle.Source.DRIVER)
                }
                if (!auto) manual.target = m
                return
            }
        }
        if (auto && plan.drive != DriveKind.EVENTS && phase == Phase.DEPART) {
            if (!canDepart(v)) {
                v.speed = 0.0
                v.brake = true
                return
            }
            phase = Phase.RUN
            startLaunch()
        }

        var base = when {
            auto && plan.drive != DriveKind.EVENTS -> autoBase(v)
            auto -> if (g == Gear.R) CREEP_R else if (g == Gear.N) 0.0 else CREEP_D
            else -> if (g == Gear.N) max(0.0, m - 1.0 * dt) else manual.target
        }
        val ov = overM
        if (!auto && ov != null) base = abs(ov.speed)
        if (auto && plan.drive == DriveKind.EVENTS && ov != null) base = abs(ov.speed)
        if (auto && plan.drive == DriveKind.EVENTS && g == Gear.R && ov != null && ov.speed > 0) base = ov.speed

        // 앞의 회전·정지·제한속도에 맞춰 미리 줄이기(경로 운전). 한 번 밟기 시작하면 그 속도·그 자리까지 같은 감속으로
        slowing?.let { sl -> if (!auto || m <= sl.v + 1e-6 || v.s > sl.s + 40) slowing = null }
        lastCap = Double.POSITIVE_INFINITY
        if (auto && plan.drive != DriveKind.EVENTS) {
            var c = urgent(v, m, dt)
            val sl = slowing
            if (sl != null && (c == null || (c.kind != "stop" && c.kind != "end" && c.kind != "pathEnd" && c.dec <= sl.dec && c.v >= sl.v))) c = sl
            if (c != null) {
                if (c.kind == "stop" || c.kind == "end" || c.kind == "pathEnd") {
                    val stopRef = c.stop
                    stopping = Stopping(c.dec, (stopRef as? PendingStop)?.hold ?: 0.0, c.kind, stopRef)
                    if (stopRef is PendingStop && c.kind == "stop") pendingStops = pendingStops.filter { it !== stopRef }
                    m = max(0.0, m - c.dec * dt)
                    v.speed = JsMath.orZero(m * dirS)
                    v.brake = true
                    launching = null
                    if (m <= 1e-6) {
                        val st = stopping
                        stopping = null
                        v.speed = 0.0
                        lastStopEndT = time
                        finishStop(st)
                    }
                    return
                }
                if (c !== slowing) slowing = Constraint(c.s, c.v, c.dec, c.kind)
                m = max(c.v, m - c.dec * dt)
                v.speed = JsMath.orZero(m * dirS)
                v.brake = true
                launching = null
                if (m <= c.v + 1e-6) slowing = null
                return
            }
        }
        // 내리막 나눠 밟기 · 계속 밟기
        pulseM?.let { p ->
            if (time >= p.until) {
                if (p.on) {
                    p.on = false
                    p.until = time + 2.0
                } else if (p.i >= p.n) {
                    pulseM = null
                } else {
                    p.on = true
                    p.i++
                    p.until = time + 1.2
                }
            }
            if (pulseM != null) {
                if (p.on) {
                    m = max(5.0, m - 4 * dt)
                    brake = true
                } else {
                    m = min(base + 6, m + 2 * dt)
                }
                v.speed = JsMath.orZero(m * dirS)
                v.brake = brake
                return
            }
        }
        holdBrakeM?.let { until ->
            if (time < until) {
                m = max(8.0, m - 0.8 * dt)
                v.speed = JsMath.orZero(m * dirS)
                v.brake = true
                return
            }
            holdBrakeM = null
        }
        if (!auto && manual.throttle && g != Gear.N) {
            val cap = if (g == Gear.R) 20.0 else 180.0
            m = min(cap, m + ACC * dt)
            manual.target = m
            v.speed = JsMath.orZero(m * dirS)
            v.brake = false
            return
        }
        launching?.let { l ->
            m += l.gain * dt
            if (m >= l.untilV) launching = null
            v.speed = JsMath.orZero(m * dirS)
            v.brake = false
            return
        }
        if (m < base) {
            if (m < lastCap) m = minOf(base, lastCap, m + ACC * dt)
            if (!auto && m < 0.01 && base > 0) lastLaunchT = time
        } else if (m > base) {
            val gap = m - base
            if (gap > 3 && !overEndCoast() && g != Gear.N) {
                m = max(base, m - DEC * dt)
                brake = true
            } else {
                m = max(base, m - COAST * dt)
            }
        }
        v.speed = JsMath.orZero(m * dirS)
        v.brake = brake || (m == 0.0 && g != Gear.N && auto)
    }

    private fun overEndCoast(): Boolean = time - overEnded < 8

    private fun finishStop(st: Stopping?) {
        val hold = st?.hold ?: 0.0
        if (st != null && st.kind == "park") {
            doPark(null)
            return
        }
        if (st != null && (st.kind == "end" || st.kind == "pathEnd")) {
            if (plan.drive == DriveKind.EVENTS && st.kind == "pathEnd") return
            doPark(st.stop as? EndStop)
            return
        }
        if (st != null && st.then == "park") {
            doPark(null)
            return
        }
        holding = Holding(time + max(0.0, hold), time)
        // 손 운전: 주입으로 세운 차는 서 있는다(그사이 패널에서 속도를 새로 정했으면 그 값을 따름)
        if (!lastAuto && !(st?.startT != null && manualSetT > st.startT)) manual.target = 0.0
    }

    private fun doPark(stop: EndStop?) {
        holding = null
        stopping = null
        pendingStops = emptyList()
        phase = Phase.PARKED
        val hb = stop?.handbrake ?: parkHandbrake ?: (skill == DriverSkill.GOOD)
        sim.vehicle.set(VehiclePatch(speed = 0.0, brake = false, gear = Gear.P), Vehicle.Source.DRIVER)
        if (hb) sim.clock.later(1.5) { sim.vehicle.set(VehiclePatch(handbrake = true), Vehicle.Source.DRIVER) }
        parkHandbrake = null
        sim.player.onParked()
    }

    private fun canDepart(v: CarState): Boolean {
        val p = sim.player
        if (v.handbrake) {
            if (autoHandbrakeAt < 0) autoHandbrakeAt = time + 3
            if (time >= autoHandbrakeAt && !p.pendingSignal(Act.HANDBRAKE)) {
                sim.vehicle.set(VehiclePatch(handbrake = false), Vehicle.Source.DRIVER)
                autoHandbrakeAt = -1.0
                p.noteAction()
            }
            return false
        }
        if (!v.beltDriver) {
            if (autoBeltAt < 0) autoBeltAt = time + 5
            if (time >= autoBeltAt && !p.pendingSignal(Act.BELT)) {
                sim.vehicle.set(VehiclePatch(beltDriver = true), Vehicle.Source.DRIVER)
                autoBeltAt = -1.0
                p.noteAction()
            }
            if (p.pendingSignal(Act.BELT)) return false
            if (!v.beltDriver) return false
        }
        return p.canDepart()
    }

    /* ---------- 핸들·깜빡이(경로 운전) ---------- */

    private class Active(val tr: RouteTurn, val d: Double)

    private fun activeTurn(p: Route?, s: Double): Active? {
        if (p == null) return null
        for (tr in p.turns) {
            if (p.loop) {
                val d = sim.navi.distTo(tr, s)
                if (d > -tr.len - 1 && d < 600) return Active(tr, d)
                continue
            }
            if (tr.sEnd + 0.01 < s) continue
            return Active(tr, tr.sStart - s)
        }
        return null
    }

    private fun routeTurnTick(v: CarState, auto: Boolean) {
        val p = path() ?: return
        val s = v.s
        val m = abs(v.speed)
        // 끝난 회전(풀기·깜빡이 해제는 blinkerTick이)
        for (tr in p.turns) {
            val rt = turnRt[tr.id] ?: continue
            if (rt.done) continue
            val d = if (p.loop) sim.navi.distTo(tr, s) else tr.sStart - s
            if (rt.inArc && (d < -tr.len || d > 1)) {
                rt.done = true
                rt.inArc = false
                lastTurnEndT = time
                blendStart = time
                unwindRate = max(15.0, abs(rt.full?.takeIf { it != 0.0 } ?: 15.0))
            }
        }
        val at = activeTurn(p, s) ?: return
        val tr = at.tr
        val d = at.d
        val pl = planFor(tr)
        if (pl.miss) return
        val rt = rtFor(tr)
        if (rt.done) return
        val useBlink = auto || pl.fromInject
        // 깜빡이 켜기 — 이번 틱에 차가 움직인 뒤 스냅숏에 보이는 남은 거리가 [blinkAt, blinkAt + 한 틱)에 들도록 한 틱 앞서 본다
        val stepM = m / 3.6 * 0.1
        if (useBlink && !rt.blinked && pl.blink && d - 2 * stepM < pl.blinkAt && d > -tr.len) {
            rt.blinked = true
            rt.blinkOnT = time
            val right = tr.blinker
            val wrongSide = if (right == Blinker.LEFT) Blinker.RIGHT else Blinker.LEFT
            setBlinker(if (pl.wrong) wrongSide else right)
            cancelWatch?.keepOn = pl.keepOn
            if (pl.wrong) rt.correctAt = time + 3
            // 꺾기 시작(5° 넘음)이 깜빡이 + steerAfter초에 오도록 0.3초 앞서 돌리기 시작
            if (pl.steerAfter != null) rt.steerOnT = time + max(0.0, pl.steerAfter - 0.3)
        }
        val ca = rt.correctAt
        if (ca != null && time >= ca) {
            rt.correctAt = null
            setBlinker(tr.blinker)
            cancelWatch?.keepOn = pl.keepOn
        }
        if (d <= 0 && d > -tr.len) rt.inArc = true
        if (!auto) return
        // 핸들(경로 운전에서만 — 손 조작은 패널 슬라이더)
        val full = steerAngle(tr)
        var target: Double? = null
        // 회전의 마지막 1초에는 핸들을 풀기 시작한다(회전이 끝나면 거의 똑바로 — 바로 뒤 직진 창이 깨끗하게)
        if (rt.inArc) {
            target = if ((tr.sEnd - s) <= max(1.2, m / 3.6 * 0.7)) 0.0 else full
        } else if (d > 0) {
            var early = false
            val so = rt.steerOnT
            if (so != null && time >= so) early = true
            val pe = pl.early
            if (pe != null && m > 0.5 && d / (m / 3.6) <= pe) early = true
            if (early) target = sign(full) * EARLY_STEER
        }
        rt.steerTarget = target
        rt.full = full
    }

    private fun steerTick(dt: Double, v: CarState, auto: Boolean) {
        val sm = steerM
        if (sm != null && time >= sm.start) {
            val tgt = if (time < sm.end) sm.angle else 0.0
            val rate = max(15.0, abs(sm.angle)) * dt
            v.steer = approach(v.steer, tgt, rate)
            if (time >= sm.end && abs(v.steer) < 0.01) {
                steerM = null
                blendStart = time
            }
            return
        }
        if (!auto) return // 손 조작: 슬라이더 값 그대로
        if (plan.drive == DriveKind.EVENTS) {
            val es = evSteer
            if (es != null) {
                val tgt = if (es.until != null && time >= es.until) 0.0 else es.angle
                v.steer = approach(v.steer, tgt, max(15.0, abs(es.angle)) * dt)
                if (es.until != null && time >= es.until && abs(v.steer) < 0.01) evSteer = null
            }
            return
        }
        val p = path()
        val at = activeTurn(p, v.s)
        var target: Double? = null
        var full = 15.0
        if (at != null) {
            val rt = turnRt[at.tr.id]
            if (rt != null && !rt.done) {
                target = rt.steerTarget
                full = abs(rt.full?.takeIf { it != 0.0 } ?: 15.0)
            }
        }
        evSteer?.let { es ->
            target = es.angle
            if (es.until != null && time >= es.until) evSteer = null
        }
        val tg = target
        if (tg != null) {
            v.steer = approach(v.steer, tg, max(10.0, full) * dt)
            if (tg == 0.0 && at != null && at.d <= 0) blendStart = time // 푸는 중엔 흔들림을 섞지 않음
            return
        }
        // 직진: 회전을 푼 뒤 1초에 걸쳐 0으로, 그다음 흔들림(1.5초에 걸쳐 섞음)
        val m = abs(v.speed)
        val blend = JsMath.clamp((time - blendStart - 1.0) / 1.5, 0.0, 1.0)
        val factor = if (m >= 15) 1.0 else m / 15
        val w = wobbleStd() * noise(time) * factor * blend
        if (abs(v.steer - w) > 2.5 && blend < 1) v.steer = approach(v.steer, w, unwindRate * dt) else v.steer = w
        val wm = wobbleM
        if (wm != null && m > 15) {
            wm.left -= dt
            if (wm.left <= 0) wobbleM = null
        }
    }

    private fun approach(x: Double, target: Double, step: Double): Double {
        if (abs(target - x) <= step) return target
        return x + sign(target - x) * step
    }

    private fun sign(x: Double): Int = if (x < 0) -1 else 1

    /* ---------- 틱 ---------- */

    /** 틱 단계 'driver'(20). */
    internal fun tick(dt: Double) {
        time += dt
        val v = veh()
        val auto = sim.player.state == PlayerState.PLAYING
        if (auto != lastAuto) {
            lastAuto = auto
            if (auto) {
                // 재생 시작·이어 하기: 손 조작 값을 비우고 지금 상태에서 시나리오 운전
                manual.throttle = false; manual.brakeHeld = false; manual.brakeLock = false; shiftAfter = null
                if (v.gear != Gear.P && (phase == Phase.IDLE || phase == Phase.PARKED)) phase = Phase.RUN
                if (v.gear != Gear.P && abs(v.speed) < 0.5 && phase == Phase.RUN && holding == null) startLaunch()
            } else {
                manual.target = abs(v.speed)
            }
        }
        // 기어로 단계 바꾸기
        if (v.gear == Gear.P) {
            if (phase != Phase.PARKED) phase = Phase.IDLE
        } else if (phase == Phase.IDLE || phase == Phase.PARKED) {
            phase = if (auto && plan.drive != DriveKind.EVENTS) Phase.DEPART else Phase.RUN
            gearT = time
            autoHandbrakeAt = -1.0
            autoBeltAt = -1.0
        }
        overM?.let { o ->
            val end = o.endT
            if (end != null && time >= end) {
                overM = null
                overEnded = time
            }
        }
        overM?.let { o -> if (o.endT == null && abs(abs(v.speed) - abs(o.speed)) < 1) o.endT = time + o.dur }
        waitM?.let { if (time >= it.until) waitM = null }
        if (pendingStops.isNotEmpty()) pendingStops = pendingStops.filter { it.s >= v.s - 2 }
        routeTurnTick(v, auto)
        speedTick(dt, v, auto)
        steerTick(dt, v, auto)
        blinkerTick(v)
    }

    /** 패널 손 조작으로 속도·핸들이 바뀌면 그 값을 따른다. */
    internal fun onManualSet(changed: List<String>, v: CarState) {
        if ("speed" in changed) manual.target = abs(v.speed)
        if ("gear" in changed && v.gear == Gear.P) {
            manual.target = 0.0
            manual.throttle = false
        }
    }

    internal fun reset() {
        time = 0.0
        skill = DriverSkill.NORMAL
        plan = Plan()
        phases = doubleArrayOf(0.0, 1.3, 2.6)
        phase = Phase.IDLE
        gearT = -1.0
        clearManeuvers()
        lastTurnEndT = -99.0; lastStopEndT = -99.0; lastLaunchT = -99.0; blendStart = -99.0; overEnded = -99.0
        cancelWatch = null
        lastAuto = false
        autoHandbrakeAt = -1.0
        autoBeltAt = -1.0
        manual.clear()
    }

    private fun clearManeuvers() {
        stopping = null; slowing = null; holding = null; pendingStops = emptyList(); endStop = null; launching = null; armedGain = null
        overM = null; waitM = null; rollM = null; pulseM = null; holdBrakeM = null; pedal = null; steerM = null; wobbleM = null
        blinkOffAt = null; shiftAfter = null; parkHandbrake = null; evSteer = null
        turnRt = HashMap()
        injPlans = HashMap()
    }

    /**
     * 시나리오 운전 계획을 정한다(driver.js `configure`). 흔들림 위상은 시드 'driver:<[seed]>:<실력>'.
     * 사건으로만 움직이면(EVENTS) 브레이크를 밟은 채로 시작한다.
     */
    fun configure(skill: DriverSkill? = null, cruise: Double? = null, turns: Map<Int, TurnPlan> = emptyMap(), wobble: Double? = null, seed: String? = null, drive: DriveKind = DriveKind.ROUTE): Plan {
        if (skill != null) this.skill = skill
        plan = Plan(cruise, turns.toMap(), wobble, drive)
        time = 0.0
        val r = SeedRandom.of("driver:" + (seed?.takeIf { it.isNotEmpty() } ?: "x") + ":" + this.skill.code)
        phases = doubleArrayOf(r() * Math.PI * 2, r() * Math.PI * 2, r() * Math.PI * 2)
        phase = Phase.IDLE
        clearManeuvers()
        lastTurnEndT = -99.0; lastStopEndT = -99.0; lastLaunchT = -99.0; blendStart = time - 5; overEnded = -99.0
        autoHandbrakeAt = -1.0
        autoBeltAt = -1.0
        if (plan.drive == DriveKind.EVENTS) pedal = Pedal(null)
        return plan
    }

    /** 손 조작·끼어든 동작이 진행 중인가(시계를 돌려야 하나). */
    fun busy(): Boolean =
        manual.throttle || manual.brakeHeld || shiftAfter != null || stopping != null || holding != null || launching != null ||
            rollM != null || pulseM != null || holdBrakeM != null || steerM != null || overM != null || blinkOffAt != null ||
            abs(veh().speed) > 0.01

    /* ---------- 동작(시나리오 사건·주입이 부름) ---------- */

    /** 지금 세운다(감속 [drop] km/h/s, [raw]면 실력 보정 없이). 서 있으면 [hold]초 서 있음. → 'braking' | 'stopped'. */
    fun stopNow(drop: Double? = null, hold: Double? = null, then: String? = null, raw: Boolean = false): String {
        val v = veh()
        val dec = if (raw) (drop?.takeIf { it != 0.0 && !it.isNaN() } ?: DEC) else adjDrop(drop)
        if (abs(v.speed) < 0.5) {
            holding = Holding(time + (hold ?: 0.0), time)
            if (then == "park") doPark(null)
            return "stopped"
        }
        stopping = Stopping(dec, hold ?: 3.0, if (then == "park") "park" else "now", null, then, time)
        launching = null
        return "braking"
    }

    /** 경로 거리 [s]에 정지(신호 대기)를 더한다. */
    fun addStop(s: Double, drop: Double? = null, hold: Double? = null, raw: Boolean = false) {
        val st = PendingStop(s, if (raw) (drop?.takeIf { it != 0.0 } ?: DEC) else adjDrop(drop), hold ?: 4.0)
        pendingStops = (pendingStops + st).sortedBy { it.s }
    }

    fun clearStops() {
        pendingStops = emptyList()
    }

    /** 경로 끝 정지(세우고 P). [handbrake] = P 뒤 사이드브레이크(null = 실력 기본: 능숙만). null 값이면 끝 정지 없음. */
    fun setEnd(s: Double?, handbrake: Boolean? = null) {
        endStop = s?.let { EndStop(it, DEC, handbrake) }
        if (s != null && handbrake != null) parkHandbrake = handbrake
    }

    /** 경로가 늘면 뒤 정지들도 민다. */
    internal fun shiftStops(fromS: Double, delta: Double) {
        for (st in pendingStops) if (st.s >= fromS) st.s += delta
        endStop?.let { if (it.s >= fromS) it.s += delta }
    }

    /** 다음 출발을 [gain] km/h/s로(서 있으면 지금). → 'now' | 'armed'. */
    fun launchNext(gain: Double? = null): String {
        val v = veh()
        val g = adjGain(gain)
        if (abs(v.speed) < 0.5 && v.gear != Gear.P && (holding != null || phase == Phase.RUN || phase == Phase.DEPART || !lastAuto)) {
            holding = null
            if (phase == Phase.DEPART) phase = Phase.RUN
            armedGain = null
            lastLaunchT = time
            val l = Launching(g, max(5 + 1.05 * g + 0.5, 8.0))
            launching = l
            if (!lastAuto) manual.target = max(manual.target, l.untilV)
            return "now"
        }
        armedGain = g
        return "armed"
    }

    /** [speed] km/h로 [dur]초(그 속도에 닿은 뒤부터 셈) — 과속·후진 속도. */
    fun over(speed: Double?, dur: Double?) {
        overM = Over(speed?.takeIf { !it.isNaN() } ?: 0.0, dur?.takeIf { it != 0.0 && !it.isNaN() } ?: 6.0, null)
    }

    fun forceOver(speed: Double?, dur: Double?) = over(speed, dur)

    /** 지금 속도로 [sec]초 기다림(줄이지 않음). */
    fun waitFor(sec: Double?) {
        waitM = Wait(abs(veh().speed), time + (sec ?: 0.0))
    }

    /** 뒤로 밀림(기어 D에서 −[speed] km/h [dur]초). 달리는 중이면 안 함. */
    fun rollback(speed: Double?, dur: Double?): Boolean {
        if (abs(veh().speed) > 0.5) return false
        rollM = Roll(-abs(speed?.takeIf { it != 0.0 } ?: 2.0), time + (dur?.takeIf { it != 0.0 } ?: 1.0))
        holding = null
        return true
    }

    /** 내리막 나눠 밟기 [n]번. */
    fun brakePulse(n: Int?) {
        pulseM = Pulse(max(1, n?.takeIf { it != 0 } ?: 4), 1, true, time + 1.2)
    }

    /** 내리막 계속 밟기 [sec]초. */
    fun holdBrake(sec: Double?) {
        holdBrakeM = time + (sec?.takeIf { it != 0.0 } ?: 9.0)
    }

    /** 브레이크 페달(사건으로만 움직이는 시나리오): 밟음([dur]초 또는 뗄 때까지) · 뗌. */
    fun brakePedal(on: Boolean, dur: Double? = null) {
        if (!on) {
            pedal = null
            return
        }
        pedal = Pedal(dur?.let { time + it })
        launching = null
    }

    /** 핸들을 [angle]°로 [delay]초 뒤부터 [dur]초 꺾었다 푼다(주입 C4·C5). */
    fun steerPulse(angle: Double?, dur: Double?, delay: Double?) {
        val dl = delay ?: 0.0
        val du = dur?.takeIf { it != 0.0 } ?: 3.0
        steerM = SteerPulse(JsMath.clamp(angle?.takeIf { it != 0.0 } ?: 15.0, -35.0, 35.0), time + dl, time + dl + du)
        sim.clock.keepAlive(dl + du + 3)
    }

    /** 핸들을 [angle]°로 잡고 있음([dur]초 뒤 풂, null이면 계속) — 사건으로만 움직이는 시나리오. */
    fun steerHold(angle: Double?, dur: Double?) {
        evSteer = EvSteer(JsMath.clamp(angle ?: 0.0, -35.0, 35.0), dur?.let { time + it })
    }

    /** 깜빡이를 지금 켬·끔([dur]초 뒤 끔, [keepOn]이면 핸들을 풀어도 켜 둠). */
    fun blinkNow(side: Blinker, dur: Double? = null, keepOn: Boolean = false, source: Vehicle.Source = Vehicle.Source.DRIVER) {
        setBlinker(side, source)
        blinkOffAt = if (dur != null && dur != 0.0) time + dur else null
        if (keepOn) cancelWatch?.keepOn = true
        if (dur != null && dur != 0.0) sim.clock.keepAlive(dur + 2)
    }

    /** 지금 켜진 깜빡이를 회전 뒤에도 켜 둠(C6 주입). */
    fun keepBlinkerOn() {
        cancelWatch?.keepOn = true
    }

    /** 다음 직진 [sec]초 흔들림 [std]°. */
    fun wobble(std: Double?, sec: Double?) {
        wobbleM = WobbleM(std?.takeIf { it != 0.0 } ?: 2.0, sec?.takeIf { it != 0.0 } ?: 20.0)
    }

    /** 세우고 P([handbrake] = 사이드브레이크, null이면 실력 기본). → 'parked' | 'braking'. */
    fun park(handbrake: Boolean? = null): String {
        parkHandbrake = handbrake
        val v = veh()
        if (abs(v.speed) < 0.5) {
            doPark(null)
            return "parked"
        }
        stopping = Stopping(DEC, 0.0, "park")
        launching = null
        return "braking"
    }

    /** 회전 id에 주입 계획을 더한다(null이면 지움). */
    fun turnPlan(id: String, p: TurnPlan?): TurnPlan? {
        if (p == null) injPlans.remove(id) else injPlans[id] = (injPlans[id] ?: TurnPlan()).mergedWith(p)
        val rt = turnRt[id]
        if (rt != null && p != null && (p.blinkAt != null || p.blink == true) && !rt.done) rt.blinked = false
        return injPlans[id]
    }

    /** 멈춘 뒤 바꿀 기어. */
    fun shiftAfterStop(g: Gear?) {
        shiftAfter = g
    }

    /** 패널·테스트용 요약. */
    data class Info(
        val phase: Phase,
        val skill: DriverSkill,
        val time: Double,
        val auto: Boolean,
        val stopping: Boolean,
        val holding: Boolean,
        val launching: Boolean,
        val over: Double?,
        val pendingStops: List<Long>,
        val endStop: Long?,
    )

    fun info(): Info = Info(
        phase, skill, JsMath.round1(time), lastAuto, stopping != null, holding != null, launching != null, overM?.speed,
        pendingStops.map { JsMath.round(it.s).toLong() }, endStop?.let { JsMath.round(it.s).toLong() },
    )

    private companion object {
        const val ACC = 4.7
        const val DEC = 5.4
        const val COAST = 1.5
        const val EARLY_STEER = 7.0
        const val V_TURN = 15.0
        const val V_UTURN = 10.0
        const val JAM_SPEED = 25.0
        const val CREEP_D = 5.0
        const val CREEP_R = 4.0
        const val KEEP_ON_SEC = 15.0
        val FREQ = doubleArrayOf(0.3, 0.5, 0.7)
        val AMP: DoubleArray = run {
            val a = doubleArrayOf(0.55, 1.0, 0.45)
            val k = sqrt(a.fold(0.0) { x, y -> x + y * y } / 2)
            DoubleArray(3) { a[it] / k }
        }
    }
}
