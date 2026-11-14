package com.barodrive.core.sim

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.driver.Driver
import com.barodrive.core.sim.navi.NavState
import com.barodrive.core.sim.navi.Navi
import com.barodrive.core.sim.parking.ParkingPractice
import com.barodrive.core.sim.parking.VirtualParking
import com.barodrive.core.sim.player.Injections
import com.barodrive.core.sim.player.Player
import com.barodrive.core.sim.scenario.ParkingChoice
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioRegistry
import com.barodrive.core.sim.vehicle.Vehicle
import com.barodrive.core.sim.vehicle.VehiclePatch

/**
 * 시뮬레이터 파사드 — 앱 시연 패널·SimSignalSource와 테스트가 부르는 하나의 손잡이 (프로토타입 `BaroSim` 전역: sim/clock·vehicle·route·
 * navi·driver·generic·player·parking.js, 설계 7장). 순수 Kotlin, 벽시계·시드 없는 난수 없음, 스레드 안전하지 않음(부르는 쪽이 잠근다).
 *
 * ## 불러오기·재생 (설계 7.2·7.6)
 * - [load] `(SimTarget.Scenario("S2-B") | Course("c2-C") | Place("mart") | Inline(def), skill, autoplay, autoplayDelay, seed)` → [ScenarioInfo].
 *   찾는 순서: 시나리오 id → 코스 기본 시나리오 → 모드 틀 → 기본 틀(일반 경로 생성기) · 장소 시나리오 → 기본 틀.
 *   [loadScenario]·[loadCourse]·[loadPlace]는 줄임. [list] = 등록된 시나리오(패널 목록).
 * - [play] · [pause] · [toggle] · [unload] · [setSpeed] (1·2·4) · [setRealtime] (실제 속도) · [setSkill] · [skip]
 *   (`Player.Skip.NEXT` 다음 장면 · `END` 끝으로).
 * - [info] · [progress] (경로 위치·장면 막대) · [marks] · [navState] (DR-1 남은 거리·시간·목적지·음성 글).
 *
 * ## 시간 (설계 7.2) — sim 시계는 0.1초 틱으로만 나아간다
 * - [advance] `(realSec)` → 그동안 돈 틱마다 스냅숏 목록: 실제 시간 고리(앱이 프레임마다 실제 경과를 넘김). 재생 중이면 압축 3·6·10 × 배속
 *   (실제 속도면 ×1), 손 조작이면 ×1, 아무 일 없으면(P·0km/h) 시계가 선다. 엔진에는 이 목록을 차례로 `session.step`한다.
 * - [tick] → 틱 하나 뒤 스냅숏 · [step] `(simSec)` → 그 sim초만큼의 스냅숏들(헤드리스·테스트).
 * - [snapshot] = 지금 스냅숏(설계 6.1.2). [resetClock] = 세션 시작 때 시계를 0으로(앱 세션이 [SimEvent.Gear] P→D를 받은 그 자리에서 부른다).
 * - `clock.hold(name, on)` = 세션 마무리(P 표본 3초)처럼 서 있는 동안에도 시계를 ×1로 돌려 둠(앱 session.js `clockHold`).
 *
 * ## 손 조작 · 신호 지원 · 주입 (설계 7.8·7.10·9)
 * - [set] `(VehiclePatch)` = 패널 손 조작(재생 중 기어·속도·핸들·브레이크를 바꾸면 재생을 멈춤) · [setEnvironment] `(night, model)` = 패널 주간/야간·차종.
 * - 손 운전: [pressPedal] `("throttle"|"brake", on)` · [lockBrake] · [setTargetSpeed] · [shift].
 * - [caps] / [setCaps] = 못 읽는 신호(스냅숏에서 null). [injections] = 버튼 목록, [inject] `(id)` → 패널 글.
 * - 내비 패널(회전 넣기·제한속도·어린이보호구역·정체·안내 끝): [navi]의 `injectTurn`·`overrideLimit`·`overrideSchool`·`overrideJam`·`clear`.
 *
 * ## 엔진 쪽 연결
 * - [inputSink] = 시나리오의 질문(ask)·[다음 단계](press)·언덕 상황 → `session.input`. [addListener] = 기어·장면·내비 음성·도착 사건.
 *
 * ## 실제 주차(설계 7.9)
 * - [chooseParking] `(type, side)` = 모드 6 S6-P가 따를 종류·방향(PK-1) · [parkingPractice] `(type, side)` = PK-2 연습 하나
 *   (지금 차종, `step(dt, ParkInput)`) · [parking] = 가상 주차 모델 전체([VirtualParking] 장면·정해 둔 조작 순서).
 *
 * 헤드리스로 시나리오를 끝까지 돌리려면 [ScenarioRunner].
 */
class BaroSim(
    /** 시나리오·경로·틀 목록(기본: 프로토타입 데이터 전부). 시험용 시나리오를 더 등록할 수 있다. */
    val registry: ScenarioRegistry = ScenarioRegistry.builtIn(),
) {
    private val listeners = ArrayList<SimListener>()
    private val errorLog = ArrayList<String>()

    val clock: SimClock = SimClock(this)
    val vehicle: Vehicle = Vehicle(this)
    val navi: Navi = Navi(this)
    val driver: Driver = Driver(this)
    val player: Player = Player(this)
    internal val injectionRunner: Injections = Injections(this)

    /** 못 읽는 신호(설계 7.8). false인 신호는 스냅숏에서 null. 'trip'은 코스 혼잡도라 스냅숏과 상관없다. */
    var caps: SignalCaps = SignalCaps()
        private set

    /** 모드 6 실제 주차 종류·방향(PK-1에서 고른 것). S6-P와 모드 6 틀이 읽는다. */
    var parkingChoice: ParkingChoice = ParkingChoice()
        private set

    /** 가상 주차 모델(설계 7.9, PK-2). */
    val parking: VirtualParking get() = VirtualParking

    init {
        navi.reset()
    }

    /* ---------- 사건 ---------- */

    /** 사건 받기를 더한다 → 그만 받기 함수. */
    fun addListener(listener: SimListener): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    internal fun emit(e: SimEvent) {
        if (e is SimEvent.Route) player.onRoute(e.summary)
        for (l in listeners.toList()) l.onEvent(e)
    }

    /** 틱 단계에서 난 오류(프로토타입은 console.error로 알리고 계속 돈다). */
    val errors: List<String> get() = errorLog.toList()

    internal fun report(where: String, e: Throwable) {
        errorLog += "[sim] $where 오류: ${e::class.simpleName}: ${e.message}"
    }

    /* ---------- 신호 지원 · 주차 고르기 ---------- */

    fun setCaps(caps: SignalCaps) {
        this.caps = caps
    }

    /** 주차 종류('parallel'·'back'·'front')·방향('L'·'R')을 고른다(모르는 값이면 평행 · 오른쪽). */
    fun chooseParking(type: String?, side: String?): ParkingChoice {
        parkingChoice = ParkingChoice.norm(type, side)
        return parkingChoice
    }

    /* ---------- 불러오기 · 재생 ---------- */

    fun load(target: SimTarget, skill: DriverSkill? = null, autoplay: Boolean = false, autoplayDelay: Double = 1.0, seed: String? = null): ScenarioInfo =
        player.load(target, skill, autoplay, autoplayDelay, seed)

    fun loadScenario(id: String, skill: DriverSkill? = null): ScenarioInfo = load(SimTarget.Scenario(id), skill)
    fun loadCourse(courseId: String, skill: DriverSkill? = null): ScenarioInfo = load(SimTarget.Course(courseId), skill)
    fun loadPlace(placeId: String, skill: DriverSkill? = null): ScenarioInfo = load(SimTarget.Place(placeId), skill)
    fun loadInline(def: ScenarioDef, skill: DriverSkill? = null): ScenarioInfo = load(SimTarget.Inline(def), skill)

    fun play(): Boolean = player.play()
    fun pause(): Boolean = player.pause()
    fun toggle(): String? = player.toggle()
    fun unload(): Boolean = player.unload()

    /** 배속 ×1·×2·×4(그 밖은 1). */
    fun setSpeed(x: Int): Int = clock.setSpeed(x)

    /** 실제 속도(패널 배속 '실제'): 참이면 재생 중에도 실제 시간 그대로(압축·배속 없이 ×1). 처음 값 거짓. → 지금 값. */
    fun setRealtime(on: Boolean): Boolean = clock.setRealtime(on)

    fun setSkill(skill: DriverSkill): String = player.setSkill(skill)

    fun skip(kind: Player.Skip): ScenarioInfo? = player.skip(kind)

    fun info(): ScenarioInfo = player.info()
    fun progress(): SimProgress = player.progress()
    fun marks(): List<SceneMark> = player.marks()
    fun list(): List<ScenarioSummary> = player.list()
    fun navState(): NavState = navi.state()

    /** 시나리오의 질문·[다음 단계]·언덕 상황을 받을 곳(엔진 `session.input`). */
    var inputSink: ((SessionInput) -> Unit)?
        get() = player.inputSink
        set(value) {
            player.inputSink = value
        }

    /* ---------- 시간 ---------- */

    /** 틱 하나(0.1 sim초) → 그 뒤 스냅숏. */
    fun tick(ff: Boolean = false): VehicleSnapshot {
        clock.tick(ff)
        return snapshot()
    }

    /** [simSec] sim초만큼(틱 수 = round(simSec / 0.1)) → 틱마다 스냅숏. */
    fun step(simSec: Double): List<VehicleSnapshot> {
        val n = Math.max(0L, Math.round(simSec / SimClock.DT)).toInt()
        return List(n) { tick() }
    }

    /** 실제 경과 [realSec]초 → 그동안 돈 틱마다 스냅숏(재생 압축·배속 또는 실제 속도 ×1, 손 조작 ×1, 아무 일 없으면 빈 목록). */
    fun advance(realSec: Double): List<VehicleSnapshot> {
        val out = ArrayList<VehicleSnapshot>()
        clock.advance(realSec) { out += snapshot() }
        return out
    }

    /** 지금 스냅숏(설계 6.1.2). */
    fun snapshot(): VehicleSnapshot = vehicle.snapshot()

    /** sim 시계를 [t]초로(세션 시작 = 0). */
    fun resetClock(t: Double = 0.0) = clock.reset(t)

    /* ---------- 손 조작 ---------- */

    /** 시연 패널 손 조작(설계 9). 재생 중 기어·속도·핸들·브레이크를 바꾸면 재생을 멈춘다. → 바뀐 키. */
    fun set(patch: VehiclePatch): List<String> = vehicle.set(patch, Vehicle.Source.PANEL)

    /** 패널 주간/야간·차종(재생을 멈추지 않음). */
    fun setEnvironment(night: Boolean? = null, model: String? = null): List<String> =
        vehicle.set(VehiclePatch(night = night, model = model), Vehicle.Source.SIM)

    /** 가속('throttle')·브레이크('brake') 페달을 누름·뗌. 재생 중이면 손 조작으로 넘어간다. */
    fun pressPedal(name: String, on: Boolean) = driver.manual.press(name, on)

    /** 브레이크 고정. */
    fun lockBrake(on: Boolean) = driver.manual.lock(on)

    /** 손 운전 목표 속도(km/h, 패널 슬라이더). 재생 중이면 손 조작으로 넘어간다. */
    fun setTargetSpeed(kmh: Double) {
        driver.takeOverBy("speed")
        driver.manual.setTarget(kmh)
    }

    /** 패널 기어 버튼(달리는 중 P·반대 방향이면 먼저 세움) → 'now' | 'later'. */
    fun shift(gear: Gear): String = driver.manual.shift(gear)

    /* ---------- 주입 ---------- */

    /** 주입 버튼 목록(설계 7.10). */
    val injections: List<InjectionInfo> get() = injectionRunner.list

    /** 주입 버튼 [id]를 누른다 → 패널에 보일 글. */
    fun inject(id: String): String {
        clock.paused = false
        return injectionRunner.run(id)
    }

    /** 가상 주차 연습 하나(PK-2). */
    fun parkingPractice(type: String, side: String, model: String? = null): ParkingPractice =
        VirtualParking.create(type, side, model ?: vehicle.state.model)

    /* ---------- 처음으로 ---------- */

    /** 시계·차·내비·운전자·재생기·신호 지원을 처음으로(주차 고르기와 사건 받기는 그대로). */
    fun reset() {
        player.reset()
        clock.resetAll()
        caps = SignalCaps()
        driver.reset()
        vehicle.reset()
        navi.reset()
        vehicle.clearSkip()
        injectionRunner.reset()
    }
}
