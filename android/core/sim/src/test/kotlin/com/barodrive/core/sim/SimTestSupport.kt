package com.barodrive.core.sim

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.EventValue
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioEvent
import com.barodrive.core.sim.scenario.StartState
import com.barodrive.core.sim.scenario.TurnPlan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 시뮬레이터 단위 테스트 도우미 (프로토타입 tests/lib-sim.js TEST1 · t1b_sim.test.js 머리 도우미). */
object SimTestSupport {
    fun go(len: Number, limit: Int? = null, road: String? = null) = Leg.Go(len = len.toDouble(), limit = limit, road = road)
    fun turn(dir: TurnDir = TurnDir.RIGHT, angle: Number = 90, r: Number? = null, tbt: String? = null, road: String? = null) =
        Leg.Turn(dir = dir, angle = angle.toDouble(), r = r?.toDouble(), tbt = tbt, road = road)

    val L = TurnDir.LEFT
    val R = TurnDir.RIGHT

    fun gearD(t: Double = 0.5) = ScenarioEvent(t = t, act = Act.GEAR, v = EventValue.of(Gear.D))
    fun endPark() = ScenarioEvent(end = true, act = Act.PARK)
    fun ev(t: Double? = null, at: Double? = null, act: Act, v: Any? = null, drop: Double? = null, hold: Double? = null, gain: Double? = null, speed: Double? = null, dur: Double? = null) =
        ScenarioEvent(t = t, at = at, act = act, v = EventValue.of(v), drop = drop, hold = hold, gain = gain, speed = speed, dur = dur)

    /** 시험용 작은 시나리오(직진 → 우회전 → 30 구역 → 정지 → 도착, lib-sim.js TEST1). */
    val TEST1_ROUTE = RouteDef.legs(
        listOf(go(420, 50, "시험로"), turn(R, 90, 10), go(320, 30, "어린이공원길"), go(260, 50, "도착길")),
        routeOption = "편안한 길", name = "시험 코스",
    )
    val TEST1 = ScenarioDef(
        id = "TEST-1", title = "시험 · 직진 → 우회전 → 30 구역 → 정지 → 도착", mode = 1, courseId = "TEST-1", cruise = 45.0,
        start = StartState(gear = Gear.P, speed = 0.0),
        turns = mapOf(1 to TurnPlan(blinkAt = 55.0)),
        events = listOf(
            gearD(),
            ScenarioEvent(mark = "회전 앞", at = 280.0),
            ScenarioEvent(mark = "30 구역", at = 450.0),
            ev(at = 640.0, act = Act.STOP, drop = 6.0, hold = 5.0),
            ScenarioEvent(mark = "도착 직전", at = 900.0),
            endPark(),
        ),
    )

    /** TEST-1을 등록한 시뮬레이터. */
    fun sim(): BaroSim = BaroSim().apply {
        registry.registerRoute("TEST-1", TEST1_ROUTE)
        registry.registerScenario(TEST1)
    }

    /** 직진 시나리오(회전 없음) — 흔들림·출발·정지 시험용. */
    fun straight(id: String, cruise: Double = 30.0, wobble: Double? = null, events: List<ScenarioEvent>? = null) = ScenarioDef(
        id = id, mode = 2, cruise = cruise, wobble = wobble,
        route = RouteDef.legs(listOf(go(1500, 50, "곧은길"))),
        events = events ?: listOf(gearD(), endPark()),
    )

    /** 경로가 있는 시험 시나리오. */
    fun inline(id: String, legs: List<Leg>, mode: Int = 1, cruise: Double = 30.0, turns: Map<Int, TurnPlan> = emptyMap(), events: List<ScenarioEvent>? = null) =
        ScenarioDef(id = id, mode = mode, cruise = cruise, route = RouteDef.legs(legs), turns = turns, events = events ?: listOf(gearD(), endPark()))

    /** 헤드리스로 돌린다(엔진 없이 — lib-sim `{ engine: false }`처럼 세션 시계를 0으로 돌리지 않음). */
    fun run(
        def: ScenarioDef,
        skill: DriverSkill = DriverSkill.NORMAL,
        until: ScenarioRunner.Until = ScenarioRunner.Until.End,
        injections: List<ScenarioRunner.Injection> = emptyList(),
        sim: BaroSim = sim(),
    ): ScenarioRun = ScenarioRunner.run(
        SimTarget.Inline(def), ScenarioRunner.Options(skill = skill, until = until, injections = injections, sessionClock = false), sim,
    )

    fun runId(id: String, skill: DriverSkill = DriverSkill.NORMAL, until: ScenarioRunner.Until = ScenarioRunner.Until.End): ScenarioRun =
        ScenarioRunner.run(SimTarget.Scenario(id), ScenarioRunner.Options(skill = skill, until = until, sessionClock = false), sim())

    /** n sim초까지(틱 수). */
    fun seconds(sec: Int) = ScenarioRunner.Until.Ticks(sec * 10)

    val VehicleSnapshot.v: Double get() = requireNotNull(speed)
    val VehicleSnapshot.st: Double get() = requireNotNull(steer)
    val VehicleSnapshot.trip: Double get() = requireNotNull(tripDist)
    val VehicleSnapshot.n: com.barodrive.core.model.NavSnapshot get() = requireNotNull(nav)

    /** 1초 창(10틱) 안 속도 감소 최댓값. */
    fun maxDrop(tr: List<VehicleSnapshot>, from: Int, to: Int): Double {
        var m = 0.0
        for (i in max(10, from)..min(tr.size - 1, to)) m = max(m, tr[i - 10].v - tr[i].v)
        return m
    }

    fun std(xs: List<Double>): Double {
        val mean = xs.sum() / xs.size
        return sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
    }
}
