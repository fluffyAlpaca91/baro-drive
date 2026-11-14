package com.barodrive.core.sim

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.navi.NavVoice
import com.barodrive.core.sim.scenario.ParkingChoice
import com.barodrive.core.sim.vehicle.Vehicle
import com.barodrive.core.sim.vehicle.VehiclePatch

/**
 * 시나리오를 화면 없이 sim 시간으로 끝까지 돌리는 도우미 (프로토타입 tests/lib-sim.js `runScenarioHeadless`의 시뮬레이터 쪽).
 * 테스트와 엔진 확인(골든)이 쓴다. 앱 세션 연결과 같은 순서로 돈다:
 *
 * 1. [BaroSim.reset] → 신호 지원·주차 고르기 → 불러오기 → 재생.
 * 2. 틱마다: 그 시나리오 시각이 된 주입을 누르고 → 틱 → 스냅숏. 기어가 P를 벗어나는 틱에 세션이 시작되고 sim 시계를 0으로 맞춘다
 *    ([Options.sessionClock], 앱 `session.start()`와 같음 — 골든 스냅숏의 t가 이렇다).
 * 3. [SessionHook]이 있으면(엔진 세션) 세션 시작·틱마다 스냅숏·시나리오 입력·마무리를 넘긴다. 마무리는 도착(또는 시나리오 끝) 뒤 P에서
 *    표본 30개(3초) 이상이고 마지막 코치 말이 끝나고 한 틱 지났고 내비 음성 대기(+1초)가 끝났을 때(길어도 60개).
 *    훅이 없으면 시나리오가 끝나고 30틱 뒤에 멈춘다.
 *
 * ```
 * val run = ScenarioRunner.run("S2-B", DriverSkill.NORMAL)          // 기본: 끝까지
 * run.snapshots                                                       // 틱마다 스냅숏(틱 k = snapshots[k - 1])
 * run.sessionSnapshots                                                // 세션 시작 틱부터(엔진에 넘길 것)
 * ```
 */
object ScenarioRunner {
    /** 그 시나리오 시각(재생기 t)에 누를 주입 버튼. */
    data class Injection(val t: Double, val id: String)

    /** 멈출 때. */
    sealed interface Until {
        /** 회차 끝(훅이 있으면 마무리, 없으면 시나리오가 끝나고 30틱 뒤). */
        data object End : Until

        /** 도착하면. */
        data object Arrived : Until

        /** 장면을 지나면. */
        data class Mark(val name: String) : Until

        /** 틱 수(1부터 센 틱 [n]까지). */
        data class Ticks(val n: Int) : Until

        /** 조건(틱마다 그 틱 스냅숏으로). */
        data class Predicate(val test: (VehicleSnapshot) -> Boolean) : Until
    }

    data class Options(
        val skill: DriverSkill = DriverSkill.NORMAL,
        /** 못 읽는 신호(null = 모두 읽음). */
        val caps: SignalCaps? = null,
        /** 모드 6 주차 종류·방향. */
        val parking: ParkingChoice? = null,
        /** 운전자 흔들림 시드(기본: 시나리오 id). */
        val seed: String? = null,
        /** 불러오기 전에 넣을 차 신호(손 조작이 아님). */
        val signals: VehiclePatch? = null,
        val injections: List<Injection> = emptyList(),
        val until: Until = Until.End,
        val maxSimSec: Double = 3600.0,
        /** 기어가 P를 벗어나는 틱에 sim 시계를 0으로(앱 세션 시작). */
        val sessionClock: Boolean = true,
        /** 엔진 세션(없으면 시뮬레이터만). */
        val session: SessionHook? = null,
    )

    /** 장면 사건 기록(골든 `scenarioEvents`). [tick] = 그 사건이 난 틱(재생 전 0). */
    data class ScenarioEventRecord(val tick: Int, val t: Double, val playerT: Double, val event: SimEvent.Scenario)

    /** 실제로 누른 주입(골든 `injected`). [beforeTick] 틱을 돌리기 바로 전에 눌렀다. */
    data class Injected(val id: String, val beforeTick: Int, val playerT: Double, val t: Double, val result: String)

    fun run(id: String, skill: DriverSkill = DriverSkill.NORMAL, seed: String? = null, sim: BaroSim = BaroSim()): ScenarioRun =
        run(SimTarget.Scenario(id), Options(skill = skill, seed = seed), sim)

    fun run(target: SimTarget, opts: Options = Options(), sim: BaroSim = BaroSim()): ScenarioRun {
        sim.reset()
        opts.caps?.let { sim.setCaps(it) }
        opts.parking?.let { sim.chooseParking(it.type, it.side) }
        opts.signals?.let { sim.vehicle.set(it, Vehicle.Source.SIM) }
        val hook = opts.session
        val baseTicks = sim.clock.ticks
        fun tickNow(): Int = (sim.clock.ticks - baseTicks).toInt()

        val voices = ArrayList<NavVoice>()
        val reroutes = ArrayList<String>()
        val waypoints = ArrayList<SimEvent.Waypoint>()
        val scenarioEvents = ArrayList<ScenarioEventRecord>()
        val inputs = ArrayList<Pair<Int, SessionInput>>()
        var arrived = false
        var navArrived = false
        var sessionStartTick: Int? = null
        var sessionOn = false
        var finished = false
        var finishTick: Int? = null
        var speakEnd = Double.NEGATIVE_INFINITY
        var navUntil = Double.NEGATIVE_INFINITY

        fun collect(r: HookStep?) {
            if (r == null) return
            r.speechEnd?.let { if (it > speakEnd) speakEnd = it }
            if (r.arrived) arrived = true
        }

        val off = sim.addListener { e ->
            when (e) {
                is SimEvent.NavVoice -> voices += e.voice
                is SimEvent.Arrived -> {
                    arrived = true
                    navArrived = true
                }
                is SimEvent.Reroute -> reroutes += e.turnId
                is SimEvent.Waypoint -> waypoints += e
                is SimEvent.Scenario -> scenarioEvents += ScenarioEventRecord(tickNow(), sim.clock.simT, sim.player.t, e)
                is SimEvent.Gear -> if (sessionStartTick == null && e.from == Gear.P && e.to != Gear.P) {
                    sessionStartTick = tickNow()
                    sessionOn = true
                    if (opts.sessionClock) sim.resetClock(0.0)
                    hook?.start(sim)
                }
                else -> Unit
            }
        }
        sim.inputSink = { ev ->
            inputs += Pair(tickNow(), ev)
            if (sessionOn && !finished && hook != null) collect(hook.input(ev))
        }
        val info = sim.load(target, opts.skill, seed = opts.seed)
        val snapshots = ArrayList<VehicleSnapshot>()
        val injected = ArrayList<Injected>()
        val injQ = opts.injections.sortedBy { it.t }.toMutableList()
        val maxTicks = Math.round(opts.maxSimSec * 10).toInt()
        val until = opts.until
        var pTicks = 0
        var tailTicks = 0
        var i = 0

        fun finish() {
            if (!sessionOn || finished) return
            finished = true
            finishTick = i
            hook?.finish(arrived)
        }

        try {
            sim.play()
            while (i < maxTicks) {
                while (injQ.isNotEmpty() && sim.player.t >= injQ[0].t - 1e-9) {
                    val q = injQ.removeAt(0)
                    val playerT = sim.player.t
                    val t = sim.clock.simT
                    val result = sim.injectionRunner.run(q.id)
                    injected += Injected(q.id, tickNow() + 1, playerT, t, result)
                }
                sim.clock.tick(ff = true)
                i++
                val snap = sim.snapshot()
                snapshots += snap
                if (sessionOn && !finished) {
                    if (hook != null) collect(hook.step(snap))
                    if (snap.nav?.voice == true) navUntil = snap.t + 1
                }
                val v = sim.vehicle.state
                if (sessionOn && !finished && hook != null) {
                    if (v.gear == Gear.P) {
                        pTicks++ // P 표본은 P가 된 때부터 센다(앱과 같음)
                        val t = sim.clock.simT
                        if ((arrived || sim.player.state == PlayerState.ENDED) &&
                            (pTicks >= 60 || (pTicks >= 30 && t >= speakEnd + 0.1 - 1e-6 && t >= navUntil - 1e-6))
                        ) finish()
                    } else {
                        pTicks = 0
                    }
                }
                if (sim.player.state == PlayerState.ENDED || sim.player.state == PlayerState.IDLE) tailTicks++
                when (until) {
                    is Until.Ticks -> if (i >= until.n) break
                    Until.Arrived -> if (arrived) break
                    is Until.Mark -> if (sim.marks().any { it.name == until.name && it.passed }) break
                    is Until.Predicate -> if (until.test(snap)) break
                    Until.End -> {
                        if (if (hook != null) finished else tailTicks >= 30) break
                        if (hook != null && !sessionOn && tailTicks >= 30) break
                    }
                }
            }
            if (sessionOn && !finished && hook != null) finish()
        } finally {
            off()
            sim.inputSink = null
        }
        return ScenarioRun(
            info = info, snapshots = snapshots, ticks = i, sessionStartTick = sessionStartTick, finishTick = finishTick,
            voices = voices, reroutes = reroutes, waypoints = waypoints, arrived = arrived, navArrived = navArrived,
            scenarioEvents = scenarioEvents, injected = injected, inputs = inputs,
            state = sim.player.state, playerT = sim.player.t, simT = sim.clock.simT, errors = sim.errors, sim = sim,
        )
    }
}

/**
 * 엔진 세션을 [ScenarioRunner]에 잇는 손잡이(시뮬레이터는 엔진을 모른다 — 앱·테스트가 엔진으로 채운다).
 * [step]·[input]은 엔진 출력에서 마지막 말이 끝나는 sim초(말 t + dur 중 가장 늦은 것)와 '도착' 힌트를 돌려준다(마무리 때 셈).
 */
interface SessionHook {
    /** 세션 시작(기어가 P를 벗어난 틱, sim 시계를 0으로 맞춘 뒤). */
    fun start(sim: BaroSim)

    fun step(snapshot: VehicleSnapshot): HookStep

    fun input(event: SessionInput): HookStep

    fun finish(arrived: Boolean)
}

/** [SessionHook] 걸음 결과: 말이 끝나는 sim초(없으면 null) · 검출기가 도착으로 봄(hints.arrived). */
data class HookStep(val speechEnd: Double? = null, val arrived: Boolean = false)

/** [ScenarioRunner.run] 결과. 틱 k(1부터)의 스냅숏 = [snapshots][k − 1]. */
class ScenarioRun(
    val info: ScenarioInfo,
    val snapshots: List<VehicleSnapshot>,
    val ticks: Int,
    /** 기어가 P를 벗어나 세션이 시작된 틱(없으면 null). */
    val sessionStartTick: Int?,
    /** 훅의 finish를 부른 틱. */
    val finishTick: Int?,
    val voices: List<NavVoice>,
    val reroutes: List<String>,
    val waypoints: List<SimEvent.Waypoint>,
    /** 도착(내비 도착 또는 훅의 도착 힌트). */
    val arrived: Boolean,
    /** 내비가 도착을 알렸나. */
    val navArrived: Boolean,
    val scenarioEvents: List<ScenarioRunner.ScenarioEventRecord>,
    val injected: List<ScenarioRunner.Injected>,
    /** 시나리오가 낸 입력(틱 번호, 입력). */
    val inputs: List<Pair<Int, SessionInput>>,
    val state: PlayerState,
    val playerT: Double,
    val simT: Double,
    /** 틱 단계에서 난 오류(없어야 정상). */
    val errors: List<String>,
    /** 돌린 시뮬레이터(끝난 상태 그대로 — 장면·정의를 더 볼 때). */
    val sim: BaroSim,
) {
    /** 틱 번호(1부터)의 스냅숏. */
    fun snapshotAt(tick: Int): VehicleSnapshot = snapshots[tick - 1]

    /** 세션 시작 틱부터 끝까지(엔진에 넘길 스냅숏). */
    val sessionSnapshots: List<VehicleSnapshot>
        get() = sessionStartTick?.let { snapshots.subList(it - 1, finishTick ?: snapshots.size) } ?: emptyList()
}
