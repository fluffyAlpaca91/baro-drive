package com.barodrive.core.sim.player

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Courses
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.SessionInput
import com.barodrive.core.sim.BaroSim
import com.barodrive.core.sim.MarkProgress
import com.barodrive.core.sim.PlayerState
import com.barodrive.core.sim.RunResult
import com.barodrive.core.sim.SceneMark
import com.barodrive.core.sim.ScenarioInfo
import com.barodrive.core.sim.ScenarioSummary
import com.barodrive.core.sim.SimEvent
import com.barodrive.core.sim.SimProgress
import com.barodrive.core.sim.SimTarget
import com.barodrive.core.sim.generic.GenericRoutes
import com.barodrive.core.sim.navi.RouteSummary
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.Cond
import com.barodrive.core.sim.scenario.DriveKind
import com.barodrive.core.sim.scenario.EventValue
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioEvent
import com.barodrive.core.sim.scenario.TemplateCourse
import com.barodrive.core.sim.scenario.TemplateOptions
import com.barodrive.core.sim.scenario.TurnPlan
import com.barodrive.core.sim.util.JsMath
import com.barodrive.core.sim.vehicle.Vehicle
import com.barodrive.core.sim.vehicle.VehiclePatch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 시나리오 재생기 (프로토타입 sim/player.js `BaroSim.player`, 설계 7.2·7.6). 틱 단계 'player'(10): 시간(t)·거리(at) 사건을 쏘고 장면(mark)을 지난다.
 *
 * - [load]: 시나리오 id → 그 코스의 기본 시나리오 → 그 모드 틀 → 기본 틀(일반 경로 생성기). 장소는 그 장소 시나리오 → 기본 틀.
 *   경로는 시나리오 안 다리 → 코스·장소 경로. 차를 경로 처음에 두고 start 값을 넣고, 실력 variants를 합친다(events는 덧붙임).
 * - [t] = 시나리오 시각(재생한 sim초, 세션 시작과 상관없이 재생 시작부터).
 * - [skip]: 다음 장면 · 끝(길 끝 60m 앞)으로 조용히 돌려 순간 이동(그 사이 틱 사건 없음 → 판정 없음), 다음 틱 스냅숏에 skip.
 * - [compression]: 3(사건 구간) · 6(그 밖) · 10(순항).
 * - 시나리오의 ask·press·hillSituation·input은 [inputSink](엔진 세션)로 간다.
 */
class Player internal constructor(private val sim: BaroSim) {
    /** 불러온 시나리오(variants를 합친 정의 [def]). */
    class Current internal constructor(
        val id: String,
        val title: String,
        val mode: Int?,
        val courseId: String?,
        val placeId: String?,
        var skill: DriverSkill,
        val kind: String,
        val generated: Boolean,
        val routeKey: String?,
        val def: ScenarioDef,
        val target: SimTarget,
        val drive: DriveKind,
    )

    private class TEvent(val t: Double, val ev: ScenarioEvent, val endPark: Boolean = false, val isEnd: Boolean = false)
    private class SEvent(var s: Double, val ev: ScenarioEvent)
    private class Mark(val name: String, var s: Double?, val t: Double?, var passed: Boolean = false)
    private class Initial(val beltDriver: Boolean? = null, val handbrake: Boolean? = null, val passenger: Boolean? = null)

    var state: PlayerState = PlayerState.IDLE
        private set
    var current: Current? = null
        private set

    /** 시나리오 시각(재생한 sim초). */
    var t: Double = 0.0
        private set

    private var tEvents = ArrayList<TEvent>()
    private var sEvents = ArrayList<SEvent>()
    private var marks = ArrayList<Mark>()
    private var lastMark: String? = null
    private var started = false
    private var initial = Initial()
    private var gearT: Double? = null
    private var lastActionT = -99.0
    private var lastEventT = 0.0
    private var loading = false
    private var cancelSoon: (() -> Unit)? = null

    /** 시나리오의 ask·press·hillSituation·input을 받을 곳(엔진 세션). null이면 버린다. */
    var inputSink: ((SessionInput) -> Unit)? = null

    /** 패널 '자동 재생'(경로가 정해지면 1초 뒤 재생 — 실제 시간 고리가 있을 때). */
    var autoplay: Boolean = true

    /** 불러온 시나리오 정의(variants 합침). */
    val def: ScenarioDef? get() = current?.def

    private fun emitScenario(extra: (SimEvent.Scenario) -> SimEvent.Scenario = { it }) {
        if (sim.clock.isSilent()) return
        sim.emit(extra(SimEvent.Scenario(state, current?.id, lastMark)))
    }

    /* ---------- 찾기 ---------- */

    /** 찾은 시나리오와 종류('scenario'·'template'·'generated'·'inline'). */
    class Found(val def: ScenarioDef, val kind: String)

    private fun courseMode(courseId: String): Int? {
        val c = Courses.course(courseId)
        GenericRoutes.modeOf(c)?.let { return it }
        val x = Regex("^c(\\d)-", RegexOption.IGNORE_CASE).find(courseId) ?: return null
        return x.groupValues[1].toInt()
    }

    /** 대상 → 시나리오(player.js `find`). 없으면 [IllegalArgumentException]('시나리오 없음'). */
    fun find(target: SimTarget, skill: DriverSkill): Found {
        val reg = sim.registry
        val parking = sim.parkingChoice
        when (target) {
            is SimTarget.Inline -> return Found(target.def, "inline")
            is SimTarget.Scenario -> {
                val id = target.id
                reg.scenario(id, parking)?.let { return Found(it, "scenario") }
                if (id.startsWith("GEN:")) {
                    val x = id.substring(4)
                    return find(if (Courses.place(x) != null && Courses.course(x) == null) SimTarget.Place(x) else SimTarget.Course(x), skill)
                }
                if (Courses.course(id) != null || reg.route(id) != null) return find(SimTarget.Course(id), skill)
                if (Courses.place(id) != null) return find(SimTarget.Place(id), skill)
                throw IllegalArgumentException("시나리오 없음: $id")
            }
            is SimTarget.Course -> {
                val id = target.courseId
                reg.forCourse(id, parking).firstOrNull { it.isDefault }?.let { return Found(it, "scenario") }
                val course = Courses.course(id)
                val mode = courseMode(id)
                val tpl = reg.template(mode)
                if (tpl != null) {
                    val routeDef = runCatching { sim.navi.resolve(id, courseId = id).def }.getOrNull()
                    val segs = routeDef?.segments
                    val made = tpl.make(
                        TemplateCourse(id, course?.name, course?.mode ?: mode),
                        TemplateOptions(
                            skill = skill, parking = parking, courseId = id, routeDef = routeDef, legs = segs?.singleOrNull(), segments = segs,
                            generic = { cid, m -> GenericRoutes.scenario(sim.navi, cid, null, m) },
                        ),
                    )
                    if (made != null) {
                        val def = made.copy(
                            id = made.id.ifEmpty { "TPL:$id" },
                            mode = made.mode ?: mode,
                        )
                        return Found(def, "template")
                    }
                }
                return Found(GenericRoutes.scenario(sim.navi, id, null), "generated")
            }
            is SimTarget.Place -> {
                val id = target.placeId
                val list = reg.forPlace(id, parking)
                (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.let { return Found(it, "scenario") }
                return Found(GenericRoutes.scenario(sim.navi, null, id), "generated")
            }
        }
    }

    private fun merge(def: ScenarioDef, skill: DriverSkill): ScenarioDef {
        val v = def.variants[skill] ?: return def
        val turns = LinkedHashMap(def.turns)
        for ((k, p) in v.turns) turns[k] = (turns[k] ?: TurnPlan()).mergedWith(p)
        return def.copy(
            turns = turns,
            events = def.events + v.events,
            cruise = v.cruise ?: def.cruise,
            wobble = v.wobble ?: def.wobble,
        )
    }

    /* ---------- 조건 ---------- */

    private fun passIf(ev: ScenarioEvent): Boolean {
        val sk = sim.driver.skill
        if (ev.skill != null && ev.skill != sk) return false
        if (ev.skills != null && sk !in ev.skills) return false
        val c = ev.cond ?: return true
        val v = sim.vehicle.state
        return when (c) {
            Cond.BELT_WAS_OFF -> initial.beltDriver == false
            Cond.BELT_WAS_ON -> initial.beltDriver != false
            Cond.BELT_OFF -> !v.beltDriver
            Cond.HANDBRAKE_WAS_ON -> initial.handbrake == true
            Cond.HANDBRAKE_WAS_OFF -> initial.handbrake != true
            Cond.HANDBRAKE_ON -> v.handbrake
            Cond.PASSENGER_WAS_ON -> initial.passenger == true
            Cond.PASSENGER_WAS_OFF -> initial.passenger != true
            Cond.PASSENGER_ON -> v.passenger
            Cond.NIGHT -> v.night
            Cond.DAY -> !v.night
        }
    }

    /** 능숙이면 끄는 '안전 위반' 사건(설계 7.3). 회전 쪽 위반(wrong·keepOn·blink false)은 운전자 모델이 끈다. */
    private fun isViolation(ev: ScenarioEvent): Boolean = when (ev.act) {
        Act.OVER, Act.SPEED -> true
        Act.LAMP, Act.BELT -> ev.v == EventValue.Bool(false)
        else -> false
    }

    /* ---------- 사건 ---------- */

    private fun setV(patch: VehiclePatch) {
        sim.vehicle.set(patch, Vehicle.Source.SCENARIO)
    }

    private fun pressDefault(): String = if (current?.mode == 7) "hillPress" else "pkNext"

    /** 시나리오 입력 → 엔진 세션([inputSink]). */
    fun input(ev: SessionInput) {
        inputSink?.invoke(ev)
    }

    /** 사건 글자('driverAsk' 등) → 입력. 모르면 null. */
    private fun inputOf(type: String?): SessionInput? = when (type) {
        "driverAsk" -> SessionInput.DriverAsk
        "pkNext" -> SessionInput.PkNext()
        "hillPress" -> SessionInput.HillPress
        "pbConfirmed" -> SessionInput.PbConfirmed
        "mute" -> SessionInput.Mute
        "unmute" -> SessionInput.Unmute
        else -> null
    }

    private fun slopeOf(v: String?): HillSlope? = when (v) {
        "down" -> HillSlope.DOWN
        "up" -> HillSlope.UP
        "none" -> HillSlope.NONE
        else -> null
    }

    internal fun noteAction() {
        lastActionT = t
    }

    private fun on(ev: ScenarioEvent): Boolean = ev.v != EventValue.Bool(false)
    private fun num(ev: ScenarioEvent): Double? = (ev.v as? EventValue.Num)?.value
    private fun str(ev: ScenarioEvent): String? = (ev.v as? EventValue.Str)?.value

    private fun fire(te: TEvent?, ev: ScenarioEvent) {
        if (te?.isEnd == true) {
            if (te.endPark) sim.driver.park(ev.handbrake) else end()
            return
        }
        if (!passIf(ev)) return
        lastEventT = t
        if (sim.driver.skill == DriverSkill.GOOD && isViolation(ev) && !ev.always) return
        val d = sim.driver
        val n = sim.navi
        when (ev.act) {
            null -> return
            Act.GEAR -> {
                val from = sim.vehicle.state.gear
                val g = str(ev)?.let { s -> Gear.entries.firstOrNull { it.name == s.trim().uppercase() } }
                setV(VehiclePatch(gear = g))
                if (from == Gear.P && sim.vehicle.state.gear != Gear.P) gearT = t
            }
            Act.BELT -> {
                setV(VehiclePatch(beltDriver = on(ev)))
                noteAction()
            }
            Act.PASSENGER -> setV(VehiclePatch(passenger = on(ev)))
            Act.SEAT_EMPTY -> setV(VehiclePatch(passenger = false))
            Act.HANDBRAKE -> {
                setV(VehiclePatch(handbrake = on(ev)))
                noteAction()
            }
            Act.WIPER -> setV(VehiclePatch(wiper = on(ev)))
            Act.LAMP -> setV(VehiclePatch(lowBeam = on(ev)))
            Act.HIGH_BEAM -> setV(VehiclePatch(highBeam = on(ev)))
            Act.NIGHT -> setV(VehiclePatch(night = on(ev)))
            Act.TEMP -> setV(VehiclePatch(outsideTemp = num(ev)))
            Act.BLINKER -> d.blinkNow(blinkerOf(str(ev) ?: "OFF"), ev.dur, ev.keepOn == true)
            Act.STEER ->
                if (d.plan.drive == DriveKind.EVENTS) d.steerHold(num(ev), ev.dur)
                else d.steerPulse(num(ev), ev.dur ?: 3.0, 0.0)
            Act.BRAKE -> if (ev.v == EventValue.Bool(false)) d.brakePedal(false) else d.brakePedal(true, ev.dur ?: ev.sec)
            Act.STOP -> d.stopNow(ev.drop, ev.hold ?: 4.0)
            Act.LAUNCH -> d.launchNext(ev.gain)
            Act.OVER, Act.SPEED -> d.over(ev.speed ?: num(ev), ev.dur)
            Act.PARK -> d.park(ev.handbrake)
            Act.WAIT -> d.waitFor(ev.sec ?: ev.dur)
            Act.ASK -> input(SessionInput.DriverAsk)
            Act.ROLLBACK -> d.rollback(ev.speed?.let { abs(it) } ?: 2.0, ev.dur ?: 1.0)
            Act.BRAKE_PULSE -> d.brakePulse(ev.n)
            Act.HOLD -> d.holdBrake(ev.sec ?: ev.dur)
            Act.HILL_SITUATION -> slopeOf(str(ev) ?: ev.type)?.let { input(SessionInput.HillSituation(it)) }
            Act.PRESS -> inputOf(ev.type ?: str(ev) ?: pressDefault())?.let { input(it) }
            Act.INPUT -> (ev.v as? EventValue.Input)?.let { input(it.event) }
            Act.NAV_VOICE -> n.voice(ev.text ?: str(ev))
            Act.REROUTE -> n.missTurn(ev.turn)
            Act.ARRIVE -> n.arrive(forced = true)
            Act.LIMIT -> n.overrideLimit(num(ev)?.toInt(), meters = ev.meters)
            Act.SCHOOL -> n.overrideSchool(on(ev), meters = ev.meters, startIn = ev.startIn)
            Act.JAM -> n.overrideJam(on(ev), meters = ev.meters)
            Act.CAUTION -> n.overrideCaution(cautionOf(ev.type ?: str(ev)), ahead = ev.ahead, len = ev.len)
            Act.WOBBLE -> d.wobble(num(ev) ?: ev.std, ev.sec ?: ev.dur)
        }
    }

    private fun blinkerOf(s: String): Blinker = when (s.trim().uppercase()) {
        "L", "LEFT" -> Blinker.LEFT
        "OFF" -> Blinker.OFF
        else -> Blinker.RIGHT
    }

    private fun cautionOf(s: String?): com.barodrive.core.model.CautionType =
        com.barodrive.core.model.CautionType.entries.firstOrNull { it.name == s } ?: com.barodrive.core.model.CautionType.SLIPPERY_ROAD_ALERT

    /** 틱 단계 'player'(10). */
    internal fun tick(dt: Double) {
        if (state != PlayerState.PLAYING) return
        if (!started) {
            started = true
            val v = sim.vehicle.state
            initial = Initial(v.beltDriver, v.handbrake, v.passenger)
            if (v.gear != Gear.P && gearT == null) gearT = 0.0
        }
        t = JsMath.round1(t + dt)
        while (tEvents.isNotEmpty() && tEvents[0].t <= t + 1e-9) {
            val x = tEvents.removeAt(0)
            fire(x, x.ev)
        }
        val s = sim.vehicle.state.s
        while (sEvents.isNotEmpty() && sEvents[0].s <= s + 1e-6) fire(null, sEvents.removeAt(0).ev)
        for (m in marks) {
            if (m.passed) continue
            val ms = m.s
            val mt = m.t
            if ((ms != null && s >= ms - 1e-6) || (mt != null && t >= mt - 1e-9)) {
                m.passed = true
                lastMark = m.name
                emitScenario()
            }
        }
        // 경로 없는(사건으로만 움직이는) 시나리오: 사건을 다 쏘고 P에서 1초가 지나면 끝
        val cur = current
        if (cur != null && cur.drive == DriveKind.EVENTS && tEvents.isEmpty() && sEvents.isEmpty() && sim.vehicle.state.gear == Gear.P && t > lastEventT + 1) end()
        if (t >= MAX_T) end("timeout")
    }

    private fun end(reason: String? = null) {
        if (current == null || state == PlayerState.ENDED) return
        state = PlayerState.ENDED
        sim.clock.keepAlive(5.0)
        emitScenario { it.copy(reason = reason) }
    }

    /* ---------- 불러오기 ---------- */

    private class RouteTarget(val inline: RouteDef?, val key: String, val courseId: String?, val placeId: String?)

    private fun routeTargetOf(def: ScenarioDef): RouteTarget? {
        val r = def.route
        if (r != null && r.usable) return RouteTarget(r, "scenario:${def.id}", def.courseId, def.placeId)
        if (def.courseId != null) return RouteTarget(null, "course:${def.courseId}", def.courseId, def.placeId)
        if (def.placeId != null) return RouteTarget(null, "place:${def.placeId}", null, def.placeId)
        return null
    }

    /**
     * 불러온다(player.js `load`). [skill]이 없으면 지금 시나리오 실력(없으면 보통). [seed]를 주면 운전자 흔들림 시드가
     * 'driver:<seed>:<실력>'(기본은 시나리오 id). [autoplay]면 실제 시간 [autoplayDelay]초 뒤 재생(0이면 바로).
     */
    fun load(target: SimTarget, skill: DriverSkill? = null, autoplay: Boolean = false, autoplayDelay: Double = 1.0, seed: String? = null): ScenarioInfo {
        val sk = skill ?: current?.skill ?: DriverSkill.NORMAL
        val found = find(target, sk)
        val raw = found.def
        cancelSoon?.invoke()
        cancelSoon = null
        sim.driver.skill = sk
        val def = merge(raw, sk)
        val rt = routeTargetOf(def)
        loading = true
        try {
            val n = sim.navi
            if (rt != null) {
                val b = n.base
                val same = b != null && b.key == rt.key && n.route != null && !n.arrived && rt.inline == null
                when {
                    same -> n.restart()
                    rt.inline != null -> n.setRoute(rt.inline, key = rt.key, courseId = rt.courseId, placeId = rt.placeId, name = def.destName ?: def.title)
                    rt.courseId != null -> n.setRoute(rt.courseId, placeId = rt.placeId)
                    else -> n.setDestination(requireNotNull(rt.placeId))
                }
            } else {
                n.clear(quiet = true)
            }
        } finally {
            loading = false
        }
        // 놓칠 회전·내비 음성 시점
        if (rt != null) {
            val misses = LinkedHashMap<Int, Double?>()
            val voices = LinkedHashMap<Int, List<Double>>()
            for ((k, c) in def.turns) {
                if (c.miss == true && !(sk == DriverSkill.GOOD && c.missSkill == DriverSkill.POOR)) misses[k] = null
                c.navVoiceAt?.let { voices[k] = it }
            }
            sim.navi.setMisses(misses)
            sim.navi.setVoicePlan(voices)
        }
        val path = sim.navi.path
        // 차·운전자
        val st = def.start
        sim.vehicle.set(
            VehiclePatch(
                gear = st?.gear ?: Gear.P, speed = st?.speed ?: 0.0, beltDriver = st?.beltDriver, passenger = st?.passenger,
                handbrake = st?.handbrake, wiper = st?.wiper, lowBeam = st?.lowBeam, night = st?.night, outsideTemp = st?.outsideTemp,
                blinker = st?.blinker, steer = st?.steer,
            ),
            Vehicle.Source.SCENARIO,
        )
        sim.vehicle.set(
            VehiclePatch(tripDist = 0.0, brake = false, steer = st?.steer?.takeIf { it != 0.0 } ?: 0.0, blinker = st?.blinker ?: Blinker.OFF),
            Vehicle.Source.SIM,
        )
        val drive = def.drive ?: (if (rt != null) DriveKind.ROUTE else DriveKind.EVENTS)
        sim.driver.configure(skill = sk, cruise = def.cruise, turns = def.turns, wobble = def.wobble, seed = seed ?: def.id, drive = drive)
        // 사건
        tEvents = ArrayList()
        sEvents = ArrayList()
        marks = ArrayList()
        lastMark = null
        var endEv: ScenarioEvent? = null
        for (ev in def.events) {
            if (ev.mark != null) {
                marks += Mark(ev.mark, if (ev.at != null && path != null) path.mapS(ev.at) else null, ev.t)
                if (ev.act == null) continue
            }
            if (ev.end) {
                endEv = ev
                if (ev.t != null) tEvents += TEvent(ev.t, ev, endPark = ev.act == Act.PARK, isEnd = true)
                continue
            }
            if (ev.at != null && path != null && !path.loop) {
                val sAt = path.mapS(ev.at)
                if (ev.act == Act.STOP) {
                    sim.driver.addStop(sAt, ev.drop, ev.hold ?: 4.0)
                    continue
                }
                sEvents += SEvent(if (ev.act == Act.LAUNCH) sAt - 30 else sAt, ev)
            } else if (ev.t != null) {
                tEvents += TEvent(ev.t, ev)
            } else if (ev.at != null) {
                tEvents += TEvent(0.0, ev)
            }
        }
        tEvents.sortBy { it.t }
        sEvents.sortBy { it.s }
        // 장면은 같은 종류(거리 at끼리, 시간 t끼리)일 때만 정렬한다. 섞이면 시나리오에 적은 순서(일어나는 차례)를 그대로 둔다(T11-8)
        if (marks.all { it.s != null }) marks.sortBy { it.s }
        else if (marks.all { it.s == null }) marks.sortBy { it.t ?: 0.0 }
        if (rt != null && path != null && !path.loop) sim.driver.setEnd(path.length - 1, endEv?.handbrake) else sim.driver.setEnd(null)
        current = Current(
            id = def.id, title = def.title ?: def.id, mode = def.mode, courseId = def.courseId, placeId = def.placeId, skill = sk,
            kind = found.kind, generated = found.kind == "generated", routeKey = rt?.key, def = def, target = target, drive = drive,
        )
        state = PlayerState.LOADED
        t = 0.0
        started = false
        initial = Initial()
        gearT = if (sim.vehicle.state.gear != Gear.P) 0.0 else null
        lastActionT = -99.0
        lastEventT = 0.0
        sim.clock.reset(0.0)
        sim.clock.paused = false
        sim.vehicle.clearSkip()
        emitScenario()
        if (autoplay) playSoon(autoplayDelay)
        return info()
    }

    fun info(): ScenarioInfo {
        val cur = current ?: return ScenarioInfo(null, null, null, null, null, null, state, t, null, false, null, emptyList(), null, null)
        val p = sim.navi.path
        return ScenarioInfo(
            id = cur.id, title = cur.title, mode = cur.mode, courseId = cur.courseId, placeId = cur.placeId, skill = cur.skill,
            state = state, t = t, kind = cur.kind, generated = cur.generated, routeKey = cur.routeKey,
            marks = marks.map { it.name }, mark = lastMark,
            lengthM = if (p != null && !p.loop) JsMath.round(p.length).toLong() else null,
        )
    }

    /** 재생(불러온 채·멈춘 채에서). 끝났거나 없으면 false. */
    fun play(): Boolean {
        if (current == null || state == PlayerState.ENDED || state == PlayerState.IDLE) return false
        cancelSoon?.invoke()
        cancelSoon = null
        state = PlayerState.PLAYING
        sim.clock.paused = false
        emitScenario()
        return true
    }

    /** 일시정지. [manual]이면 손 조작으로 멈춤(시계는 손 조작으로 계속). */
    fun pause(manual: Boolean = false): Boolean {
        if (state != PlayerState.PLAYING) return false
        state = PlayerState.PAUSED
        if (!manual) sim.clock.paused = true
        emitScenario { it.copy(manual = manual) }
        return true
    }

    /** ▶/⏸. 끝났으면 다시 불러와 재생. → 'paused' · 'playing' · null. */
    fun toggle(): String? {
        if (state == PlayerState.PLAYING) return if (pause()) "paused" else null
        val cur = current
        if (state == PlayerState.ENDED && cur != null) {
            load(cur.target, cur.skill)
            return if (play()) "playing" else null
        }
        return if (play()) "playing" else null
    }

    /** 시나리오를 내린다. */
    fun unload(reason: String? = null): Boolean {
        val cur = current
        if (cur == null) {
            state = PlayerState.IDLE
            return false
        }
        state = PlayerState.IDLE
        current = null
        tEvents = ArrayList(); sEvents = ArrayList(); marks = ArrayList(); lastMark = null
        sim.driver.configure(skill = sim.driver.skill)
        sim.driver.setEnd(null)
        if (!sim.clock.isSilent()) sim.emit(SimEvent.Scenario(PlayerState.ENDED, cur.id, null, unloaded = true, reason = reason))
        return true
    }

    /** 실제 시간 [sec]초 뒤 재생(0이면 바로). 실제 시간 고리([BaroSim.advance])가 돌 때만. */
    fun playSoon(sec: Double): Boolean {
        cancelSoon?.invoke()
        cancelSoon = null
        if (sec <= 0) return play()
        cancelSoon = sim.clock.realLater(sec) {
            cancelSoon = null
            if (state == PlayerState.LOADED || state == PlayerState.PAUSED) play()
        }
        return true
    }

    /** 재생 중 실력 바꾸기. 불러온 채 아직 재생 전이면 그 실력으로 다시 불러온다 → 'reloaded' · 'live'. */
    fun setSkill(skill: DriverSkill): String {
        val cur = current
        if (cur != null && state == PlayerState.LOADED && skill != cur.skill) {
            load(cur.target, skill)
            return "reloaded"
        }
        sim.driver.skill = skill
        cur?.skill = skill
        return "live"
    }

    /* ---------- 건너뛰기 ---------- */

    private fun endTargetS(): Double {
        val p = sim.navi.path
        if (p == null || p.loop) return Double.POSITIVE_INFINITY
        return max(0.0, p.length - 60)
    }

    /** 건너뛰기 종류: 다음 장면 · 끝(도착 직전). */
    enum class Skip { NEXT, END }

    /**
     * '다음 장면'·'끝으로'(player.js `skip`): 그 사이를 화면·엔진 없이 조용히 돌리고(틱 사건 없음), 다음 틱 스냅숏에
     * skip + skipInfo { from, to, label }을 싣는다. 지나친 도착·경유지·재탐색 사건은 다음 틱에 그대로 전한다. → 정보 | null(갈 곳 없음).
     */
    fun skip(kind: Skip): ScenarioInfo? {
        if (current == null || state == PlayerState.IDLE || state == PlayerState.ENDED) return null
        val v = sim.vehicle.state
        val from = com.barodrive.core.model.SkipPoint(JsMath.round(v.s), t)
        val pred: () -> Boolean
        val label: String
        if (kind == Skip.END) {
            val target = endTargetS()
            if (!(v.s < target)) return null
            pred = { sim.vehicle.state.s >= target || state == PlayerState.ENDED }
            label = "끝으로"
        } else {
            val m = marks.firstOrNull { !it.passed } ?: return null
            pred = { m.passed || state == PlayerState.ENDED }
            label = m.name
        }
        val was = state
        state = PlayerState.PLAYING
        if (!started) tick(0.0)
        sim.clock.silent {
            var n = 0
            while (!pred() && n < MAX_T * 10) {
                sim.clock.tick(ff = true)
                n++
            }
        }
        if (state == PlayerState.PLAYING && was != PlayerState.PLAYING) {
            state = PlayerState.PAUSED
            sim.clock.paused = true
        }
        sim.vehicle.markSkip(com.barodrive.core.model.SkipInfo(from, com.barodrive.core.model.SkipPoint(JsMath.round(sim.vehicle.state.s), t), label))
        sim.vehicle.flushGear()
        sim.navi.flushSilent()
        emitScenario { it.copy(skipped = label) }
        return info()
    }

    /* ---------- 동기 빨리 감기 ---------- */

    /**
     * 조건이 참이 될 때까지 틱을 돈다(player.js `runUntil`, 틱 사건에 ff). 불러온 채·멈춘 채면 재생부터.
     * [stopAtEnd]면 시나리오가 끝나도 멈춘다('mark:' 조건). 함수 조건은 시나리오가 끝나도 계속 돈다(세션이 P 뒤를 마저 보게).
     */
    fun runUntil(maxSimSec: Double = MAX_T, stopAtEnd: Boolean = false, check: () -> Boolean): RunResult {
        val limit = max(1, JsMath.round(maxSimSec * 10).toInt())
        if (state == PlayerState.LOADED || state == PlayerState.PAUSED) play()
        var i = 0
        var reason = "max"
        if (check()) {
            reason = "cond"
        } else {
            while (i < limit) {
                sim.clock.tick(ff = true)
                i++
                if (check()) {
                    reason = "cond"
                    break
                }
                if (stopAtEnd && state == PlayerState.ENDED) {
                    reason = "end"
                    break
                }
            }
        }
        return RunResult(reason, i, sim.clock.simT, t, state, lastMark)
    }

    /** 시나리오가 끝날 때까지. */
    fun runUntilEnd(maxSimSec: Double = MAX_T): RunResult = runUntil(maxSimSec) { state == PlayerState.ENDED || state == PlayerState.IDLE }

    /** 장면 [name]을 지날 때까지(없는 장면이면 [IllegalArgumentException]). */
    fun runUntilMark(name: String, maxSimSec: Double = MAX_T): RunResult {
        require(current == null || marks.any { it.name == name }) { "runUntil: 장면 \"$name\"이 이 시나리오에 없어요" }
        return runUntil(maxSimSec, stopAtEnd = true) { marks.any { it.name == name && it.passed } }
    }

    /** 지금부터 [sec] sim초. */
    fun runFor(sec: Double, maxSimSec: Double = MAX_T): RunResult {
        val goal = JsMath.round(sec * 10).toLong()
        val from = sim.clock.ticks
        return runUntil(maxSimSec) { sim.clock.ticks - from >= goal }
    }

    /* ---------- 압축 ---------- */

    /** 실제 시간 압축 3(사건 구간) · 6(그 밖) · 10(순항)(설계 7.2). */
    fun compression(): Double {
        if (state != PlayerState.PLAYING) return 6.0
        if (t < 3) return 3.0
        val d = sim.driver
        if (d.eventWindow()) return 3.0
        if (d.steady()) return 10.0
        return 6.0
    }

    fun progress(): SimProgress {
        val p = sim.navi.path
        val s = sim.vehicle.state.s
        val total = if (p != null && !p.loop) p.length else 0.0
        return SimProgress(
            s = s, total = total, ratio = if (total > 0) max(0.0, min(1.0, s / total)) else 0.0, t = t, state = state,
            marks = marks.map { m ->
                val ms = m.s
                MarkProgress(m.name, if (total > 0 && ms != null) max(0.0, min(1.0, ms / total)) else null, m.t, m.passed)
            },
        )
    }

    /** 등록된 시나리오 요약(모드 → id 순). */
    fun list(): List<ScenarioSummary> = sim.registry.all(sim.parkingChoice)
        .map { ScenarioSummary(it.id, it.title ?: it.id, it.mode, it.courseId, it.placeId, it.isDefault) }
        .sortedWith(compareBy<ScenarioSummary> { it.mode ?: 99 }.thenBy { it.id })

    /** 경로가 바뀌어(주입한 회전) 뒤쪽 거리가 늘면 그 뒤 사건도 민다. */
    internal fun onPathEdit(s: Double, delta: Double) {
        if (!(delta > 0)) return
        for (x in sEvents) if (x.s >= s) x.s += delta
        for (m in marks) {
            val ms = m.s
            if (ms != null && ms >= s) m.s = ms + delta
        }
        sim.driver.shiftStops(s, delta)
        val p = sim.navi.path
        if (p != null && !p.loop && sim.driver.info().endStop != null) sim.driver.setEnd(p.length - 1)
    }

    /** 다른 데서 내비 경로를 바꾸면(목적지 고르기 등) 불러온 시나리오를 내린다. */
    internal fun onRoute(r: RouteSummary?) {
        val cur = current
        if (loading || cur == null || state == PlayerState.IDLE) return
        if (r == null || r.key != cur.routeKey) unload("route")
    }

    internal fun onParked() {
        if (state == PlayerState.PLAYING) end()
    }

    /** 출발해도 되나(기어 넣고 2초 · 신호 조작 뒤 1초 · 곧 올 벨트·사이드브레이크 사건이 없음). */
    internal fun canDepart(): Boolean {
        val g = gearT ?: 0.0
        if (t < g + 2) return false
        if (t < lastActionT + 1) return false
        return !tEvents.any { (it.ev.act == Act.BELT || it.ev.act == Act.HANDBRAKE) && it.t < g + 20 && passIf(it.ev) }
    }

    /** 20초 안에 그 신호 사건이 올 예정인가. */
    internal fun pendingSignal(act: Act): Boolean = tEvents.any { it.ev.act == act && it.t < t + 20 && passIf(it.ev) }

    /** 장면들(복사). */
    fun marks(): List<SceneMark> = marks.map { SceneMark(it.name, it.s, it.t, it.passed) }

    internal fun reset() {
        cancelSoon?.invoke()
        cancelSoon = null
        state = PlayerState.IDLE
        current = null
        t = 0.0
        tEvents = ArrayList(); sEvents = ArrayList(); marks = ArrayList(); lastMark = null
        started = false
        initial = Initial()
        gearT = null
        lastActionT = -99.0
        inputSink = null
        autoplay = true
    }

    companion object {
        /** 시나리오 최대 길이(sim초). */
        const val MAX_T = 3600.0
    }
}
