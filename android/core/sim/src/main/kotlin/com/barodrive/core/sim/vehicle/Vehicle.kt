package com.barodrive.core.sim.vehicle

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SkipInfo
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.BaroSim
import com.barodrive.core.sim.PlayerState
import com.barodrive.core.sim.SimEvent
import com.barodrive.core.sim.util.JsMath
import kotlin.math.abs

/**
 * 차 상태 하나 (프로토타입 sim/vehicle.js `BaroSim.vehicle`, 설계 7.1·6.1.2·7.8). 시나리오·손 조작·주입이 모두 이것을 바꾸고,
 * 엔진과 화면은 [snapshot]만 본다.
 *
 * - [set]: 바뀐 키 목록. 기어가 바뀌면 상태를 다 고친 뒤 [SimEvent.Gear]. P가 되면 차는 바로 선다.
 *   [Source.PANEL](시연 패널 손 조작)이 재생 중에 기어·속도·핸들·브레이크를 바꾸면 재생을 멈추고 [SimEvent.Manual](설계 9).
 *   무엇이든 바뀌면 시계를 4초 더 돌린다(세워 둔 차라도 판정이 마저 돌게).
 * - 스냅숏: 못 읽는 신호(caps)는 null, 내비를 못 읽으면 nav null, 뒤로 가는 속도를 못 읽으면 speed는 크기만.
 *   더 넣은 값: model·maxSteer, '다음 장면'·'끝으로' 바로 다음 틱에만 skip + skipInfo.
 */
class Vehicle internal constructor(private val sim: BaroSim) {
    /** 지금 차 상태(움직이는 값). 바꿀 때는 [set]으로. */
    var state: CarState = CarState()
        private set

    private var pendingGear: SimEvent.Gear? = null
    private var pendingSkip: SkipInfo? = null
    private var curSkip: SkipInfo? = null

    /** 바뀐 곳(누가 바꿨나). */
    enum class Source(val code: String) { PANEL("panel"), SCENARIO("scenario"), DRIVER("driver"), INJECT("inject"), SIM("sim"), RESET("reset") }

    /** 지금 차종. */
    fun model(): CarModel = CarModel.of(state.model) ?: CarModel.AVANTE

    /** 값을 바꾼다 → 바뀐 키(vehicle.js `set`). 이상한 값(범위 밖은 자름, 모르는 차종)은 무시한다. */
    fun set(patch: VehiclePatch, source: Source = Source.SIM): List<String> {
        val v = state
        val changed = ArrayList<String>(4)
        val prevGear = v.gear
        patch.gear?.let { if (v.gear != it) { v.gear = it; changed += "gear" } }
        patch.speed?.let { n -> num(n, -40.0, 200.0)?.let { if (v.speed != it) { v.speed = it; changed += "speed" } } }
        patch.brake?.let { if (v.brake != it) { v.brake = it; changed += "brake" } }
        patch.steer?.let { n -> num(n, -35.0, 35.0)?.let { if (v.steer != it) { v.steer = it; changed += "steer" } } }
        patch.blinker?.let { if (v.blinker != it) { v.blinker = it; changed += "blinker" } }
        patch.beltDriver?.let { if (v.beltDriver != it) { v.beltDriver = it; changed += "beltDriver" } }
        patch.passenger?.let { if (v.passenger != it) { v.passenger = it; changed += "passenger" } }
        patch.handbrake?.let { if (v.handbrake != it) { v.handbrake = it; changed += "handbrake" } }
        patch.wiper?.let { if (v.wiper != it) { v.wiper = it; changed += "wiper" } }
        patch.lowBeam?.let { if (v.lowBeam != it) { v.lowBeam = it; changed += "lowBeam" } }
        patch.highBeam?.let { if (v.highBeam != it) { v.highBeam = it; changed += "highBeam" } }
        patch.night?.let { if (v.night != it) { v.night = it; changed += "night" } }
        patch.outsideTemp?.let { n -> num(n, -30.0, 50.0)?.let { if (v.outsideTemp != it) { v.outsideTemp = it; changed += "outsideTemp" } } }
        patch.tripDist?.let { n -> num(n, 0.0, 1e7)?.let { if (v.tripDist != it) { v.tripDist = it; changed += "tripDist" } } }
        patch.s?.let { n -> num(n, -1e7, 1e7)?.let { if (v.s != it) { v.s = it; changed += "s" } } }
        patch.x?.let { n -> num(n, -1e7, 1e7)?.let { if (v.x != it) { v.x = it; changed += "x" } } }
        patch.y?.let { n -> num(n, -1e7, 1e7)?.let { if (v.y != it) { v.y = it; changed += "y" } } }
        patch.heading?.let { n -> if (n.isFinite()) ((n % 360) + 360) % 360 else null }?.let { if (v.heading != it) { v.heading = it; changed += "heading" } }
        patch.model?.let { m -> if (CarModel.of(m) != null && v.model != m) { v.model = m; changed += "model" } }
        // 패널 손 조작으로 브레이크를 정하면 운전자 모델의 '브레이크 고정'도 같이 바꾼다(T11-3)
        if (source == Source.PANEL && patch.brake != null) {
            val on = patch.brake
            if (sim.driver.manual.brakeLock != on) sim.driver.manual.lock(on)
        }
        if (changed.isEmpty()) return changed
        if (v.gear == Gear.P && ("gear" in changed || "speed" in changed) && v.speed != 0.0) {
            v.speed = 0.0
            if ("speed" !in changed) changed += "speed"
        }
        sim.clock.keepAlive(4.0)
        if (source == Source.PANEL) manualTouch(changed)
        if ("gear" in changed && v.gear != prevGear) emitGear(prevGear, v.gear, source)
        return changed
    }

    private fun num(n: Double, lo: Double, hi: Double): Double? = if (n.isFinite()) minOf(hi, maxOf(lo, n)) else null

    private fun emitGear(from: Gear, to: Gear, source: Source) {
        if (sim.clock.isSilent()) {
            val g = pendingGear
            pendingGear = if (g == null) SimEvent.Gear(from, to, source.code) else g.copy(to = to)
            return
        }
        sim.emit(SimEvent.Gear(from, to, source.code))
    }

    /** 시연 패널 손 조작: 재생 중이면 멈추고(시계는 손 조작으로 계속) 운전자 모델이 손 조작 값을 따르게 한다. */
    private fun manualTouch(changed: List<String>) {
        val key = changed.firstOrNull { it in DRIVE_KEYS }
        if (key != null) sim.clock.paused = false
        sim.driver.onManualSet(changed, state)
        val p = sim.player
        if (key == null || p.state != PlayerState.PLAYING) return
        p.pause(manual = true)
        sim.emit(SimEvent.Manual(key))
    }

    /** 건너뛰기가 끝난 뒤 한 번에 기어 사건을 보낸다(player.skip). */
    internal fun flushGear(): SimEvent.Gear? {
        val g = pendingGear
        pendingGear = null
        if (g != null && g.from != g.to) sim.emit(g)
        return g
    }

    /** 처음 값으로. 기어가 바뀌면 [SimEvent.Gear](source 'reset'). */
    internal fun reset() {
        val prevGear = state.gear
        state = CarState()
        pendingGear = null
        if (prevGear != state.gear) sim.emit(SimEvent.Gear(prevGear, state.gear, Source.RESET.code))
    }

    /** 다음 틱 스냅숏에 skip을 한 번 싣는다(건너뛰기). */
    internal fun markSkip(info: SkipInfo?) {
        pendingSkip = info ?: SkipInfo()
    }

    internal fun clearSkip() {
        pendingSkip = null
        curSkip = null
    }

    /** 틱 단계 'vehicle.pre'(0): 건너뛰기 표시 넘기기. */
    internal fun preTick() {
        curSkip = pendingSkip
        pendingSkip = null
    }

    /** 틱 단계 'vehicle.move'(30): 경로 따라 움직이기. */
    internal fun move(dt: Double) {
        val v = state
        if (v.gear == Gear.P && v.speed != 0.0) v.speed = 0.0
        val ds = v.speed / 3.6 * dt
        if (ds != 0.0) {
            v.tripDist += abs(ds)
            val r = sim.navi.advance(v.s, ds)
            v.s = r.s
            v.x = r.x
            v.y = r.y
            v.heading = ((r.h % 360) + 360) % 360
            if (r.blocked) v.speed = 0.0 // 길 끝 · 길 처음(후진)
        }
    }

    /** 설계 6.1.2 스냅숏(새 값). */
    fun snapshot(): VehicleSnapshot {
        val v = state
        val caps = sim.caps
        val m = model()
        var speed = JsMath.round2(v.speed)
        if (!caps.reverseSpeed) speed = abs(speed)
        val skip = curSkip
        return VehicleSnapshot(
            t = sim.clock.simT,
            gear = if (caps.gear) v.gear else null,
            speed = if (caps.speed) speed else null,
            brake = v.brake,
            steer = if (caps.steer) JsMath.round2(v.steer) else null,
            blinker = if (caps.blinker) v.blinker else null,
            beltDriver = if (caps.beltDriver) v.beltDriver else null,
            passenger = if (caps.passenger) v.passenger else null,
            handbrake = if (caps.handbrake) v.handbrake else null,
            wiper = if (caps.wiper) v.wiper else null,
            lowBeam = v.lowBeam,
            night = v.night,
            outsideTemp = if (caps.outsideTemp) v.outsideTemp else null,
            tripDist = JsMath.round1(v.tripDist),
            nav = if (caps.nav) sim.navi.navFields() else null,
            model = m.id,
            maxSteer = m.maxSteer,
            skip = skip != null,
            skipInfo = skip,
        )
    }

    private companion object {
        /** 이 키를 재생 중에 손으로 바꾸면 시나리오가 멈춘다(설계 9: 기어·속도·핸들 + 페달). */
        val DRIVE_KEYS = setOf("gear", "speed", "steer", "brake")
    }
}

/**
 * 차 상태 값 (vehicle.js `state`·`DEFAULTS`). 야간이 패널 기본이라 하향등도 켠 채로 시작한다(아니면 모든 시나리오에서 J1이 울린다).
 * [s] = 지금 경로 위 거리 m, [x]·[y] = m(동쪽·남쪽 +), [heading] = °(북 0, 시계 방향).
 */
class CarState(
    var gear: Gear = Gear.P,
    /** km/h, 후진·뒤로 밀림은 음수. */
    var speed: Double = 0.0,
    var brake: Boolean = false,
    /** 앞바퀴각 °, 왼쪽 음수. */
    var steer: Double = 0.0,
    var blinker: Blinker = Blinker.OFF,
    var beltDriver: Boolean = true,
    var passenger: Boolean = false,
    var handbrake: Boolean = false,
    var wiper: Boolean = false,
    var lowBeam: Boolean = true,
    var highBeam: Boolean = false,
    var night: Boolean = true,
    var outsideTemp: Double = 15.0,
    var tripDist: Double = 0.0,
    var s: Double = 0.0,
    var x: Double = 0.0,
    var y: Double = 0.0,
    var heading: Double = 0.0,
    var model: String = CarModel.AVANTE.id,
) {
    fun copy(): CarState = CarState(
        gear, speed, brake, steer, blinker, beltDriver, passenger, handbrake, wiper, lowBeam, highBeam, night,
        outsideTemp, tripDist, s, x, y, heading, model,
    )
}

/** [Vehicle.set]에 넘기는 값. null인 칸은 그대로 둔다. */
data class VehiclePatch(
    val gear: Gear? = null,
    val speed: Double? = null,
    val brake: Boolean? = null,
    val steer: Double? = null,
    val blinker: Blinker? = null,
    val beltDriver: Boolean? = null,
    val passenger: Boolean? = null,
    val handbrake: Boolean? = null,
    val wiper: Boolean? = null,
    val lowBeam: Boolean? = null,
    val highBeam: Boolean? = null,
    val night: Boolean? = null,
    val outsideTemp: Double? = null,
    val tripDist: Double? = null,
    val s: Double? = null,
    val x: Double? = null,
    val y: Double? = null,
    val heading: Double? = null,
    val model: String? = null,
)
