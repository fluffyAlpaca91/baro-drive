package com.barodrive.app.data.signal.sim

import com.barodrive.app.demo.DemoPanelRepository
import com.barodrive.app.demo.PanelSettings
import com.barodrive.app.di.ApplicationScope
import com.barodrive.app.session.NavVoice
import com.barodrive.app.session.RouteSummary
import com.barodrive.app.session.ScenarioState
import com.barodrive.app.session.SimTick
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.BaroSim
import com.barodrive.core.sim.InjectionInfo
import com.barodrive.core.sim.PlayerState
import com.barodrive.core.sim.ScenarioInfo
import com.barodrive.core.sim.ScenarioSummary
import com.barodrive.core.sim.SimEvent
import com.barodrive.core.sim.SimTarget
import com.barodrive.core.sim.player.Player
import com.barodrive.core.sim.vehicle.Vehicle
import com.barodrive.core.sim.vehicle.VehiclePatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 시연 시뮬레이터 하나([BaroSim])를 신호 출처([SimSignalSource], 배경 스레드)·세션 연결([DemoSimLink])·시연 패널(메인 스레드)이 함께 쓰게
 * 하는 손잡이 (프로토타입 전역 `BaroSim` + shell/panel-sim.js의 '패널 ↔ 시뮬레이터'). BaroSim은 스레드 안전하지 않고 사건 받기는
 * [advance] 안에서 불리므로 모든 접근을 [lock] 안에서 한다.
 *
 * 잠금 안에서는 세션(뮤텍스)을 기다리지 않는다 — 세션 쪽 일(시나리오 상태·시나리오 입력)은 그 틱 스냅숏에 붙여 두었다가 세션이 그 스냅숏을
 * 엔진에 넣기 바로 전에 [takeTick]으로 꺼내 간다. 그래서 세션은 프로토타입처럼 '틱 안에서 바로' 받은 것과 같은 차례로 본다.
 * 출발(기어가 P를 벗어남) 때 시계를 0으로 맞추는 것만 틱 안에서 한다([departureGate], 설계 3.2).
 *
 * 패널 설정([DemoPanelRepository])의 배속(실제 속도 포함)·자동 재생·운전 실력·신호 지원·주간/야간·차종은 바뀔 때마다 시뮬레이터에 넣는다
 * (panel-sim.js `syncFromPanel`). 패널 화면은 [view]·[notices]를 본다. 시연 키보드(demo/keyboard)도 패널과 같은 조작 함수를 부른다.
 */
@Singleton
class SimController @Inject constructor(
    private val panel: DemoPanelRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val sim = BaroSim()

    // ---- 아래는 lock 안에서만 ----
    /** 아직 스냅숏에 붙지 않은 일(틱 밖 패널 조작·세션 보내기, 그리고 지금 도는 틱 안). */
    private val pendingScenario = ArrayList<ScenarioState>()
    private val pendingInputs = ArrayList<SessionInput>()

    /** 일이 붙은 스냅숏(세션이 꺼내 갈 때까지). 대부분 틱은 붙은 일이 없어 짧다. */
    private val attached = ArrayDeque<Pair<VehicleSnapshot, SimTick>>()

    /** 마지막으로 줄 세운 시나리오 상태(같은 상태가 장면마다 거듭 와도 한 번만). */
    private var lastQueued: ScenarioState = ScenarioState.IDLE
    private var applied: PanelSettings? = null
    private var lastNight: Boolean? = null

    /** 비상등(시연 전용, [setHazard]) — 시뮬레이터 차 상태 밖. */
    private var hazard = false

    private val _view = MutableStateFlow(SimView())

    /** 패널이 볼 시뮬레이터 모습. 실제 시간 고리 한 번(100ms)과 패널 조작마다 바뀐다. */
    val view: StateFlow<SimView> = _view.asStateFlow()

    private val _caps = MutableStateFlow(SignalCaps())

    /** 신호 지원(설계 7.8) — 못 읽는 신호는 스냅숏에서 null. 세션은 회차를 시작할 때 이 값을 엔진 설정에 넣는다. */
    val caps: StateFlow<SignalCaps> = _caps.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 패널 안내 줄(panel.js `notice`) — "직접 조작해서 재생을 멈췄어요" 등. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _navVoices = MutableSharedFlow<NavVoice>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 내비 음성('sim:navVoice') — 세션 연결이 자막·기록으로. */
    val navVoices: SharedFlow<NavVoice> = _navVoices.asSharedFlow()

    private val _sessionScenario = MutableStateFlow(ScenarioState.IDLE)

    /** 세션이 본 시나리오 상태: 세션이 꺼내 간 스냅숏 시점으로 맞춘다([takeTick]) — 엔진에 넣는 스냅숏과 어긋나지 않게. */
    val sessionScenario: StateFlow<ScenarioState> = _sessionScenario.asStateFlow()

    /** 출발 문(세션이 건다): 기어가 P를 벗어나는 틱 안에서 참이면 sim 시계를 0으로. 틱 안에서 불려 잠그지 않는 함수여야 한다. */
    @Volatile
    var departureGate: (() -> Boolean)? = null

    init {
        sim.addListener(::onSimEvent)
        synchronized(lock) {
            armInputSinkLocked()
            lastNight = sim.vehicle.state.night
            publishLocked()
        }
        scope.launch { panel.state.collect { applyPanel(it) } }
    }

    /** 시나리오 입력(ask·[다음 단계]·언덕 상황)은 틱 안(잠금 안)에서 온다 — 그 틱 스냅숏에 붙인다. 재생기를 처음으로 돌리면 다시 건다. */
    private fun armInputSinkLocked() {
        sim.inputSink = { e -> pendingInputs += e }
    }

    // ================================================================ 시간 (설계 7.2)

    /**
     * 지금 차 상태 스냅숏 하나(시계를 돌리지 않음, 프로토타입 `BaroSim.snapshot()`). 세워 둔 차는 시계가 서서 틱이 없으므로
     * [SimSignalSource]가 모으기 시작할 때 이것을 먼저 낸다 — 앱을 막 켠 PR-1 점검 줄(벨트·사이드브레이크·조수석·와이퍼)이 처음부터 읽히게.
     */
    fun current(): VehicleSnapshot = synchronized(lock) { sim.snapshot() }

    private val _resets = MutableStateFlow(0)

    /**
     * [resetAll]을 부른 횟수. 처음으로 돌린 차(데모 상태 적용·처음 실행으로 되돌리기)는 서 있어 틱이 없으므로 [SimSignalSource]가
     * 이것이 바뀌면 지금 상태를 한 번 낸다 — 안 그러면 세션이 지난 회차 끝 상태(사이드브레이크 채움 등)를 그대로 들고 있다.
     */
    val resets: StateFlow<Int> = _resets.asStateFlow()

    /**
     * 실제 경과 [realSec]초 → 그동안 돈 틱마다 스냅숏(재생 중이면 압축 3·6·10 × 배속 — 실제 속도면 ×1, 손 조작이면 ×1, 아무 일 없으면
     * 빈 목록). [SimSignalSource]만 부른다.
     */
    fun advance(realSec: Double): List<VehicleSnapshot> = synchronized(lock) {
        val out = ArrayList<VehicleSnapshot>()
        sim.clock.advance(realSec) {
            val snap = sim.snapshot()
            out += snap
            attachLocked(snap)
        }
        syncNightLocked()
        publishLocked()
        out
    }

    /** 이 틱까지 쌓인 일을 그 스냅숏에 붙인다(붙일 것이 있을 때만). */
    private fun attachLocked(snap: VehicleSnapshot) {
        if (pendingScenario.isEmpty() && pendingInputs.isEmpty()) return
        attached.addLast(snap to SimTick(pendingScenario.toList(), pendingInputs.toList()))
        pendingScenario.clear()
        pendingInputs.clear()
        while (attached.size > MAX_ATTACHED) attached.removeFirst()
    }

    /**
     * 세션이 [snapshot]을 엔진에 넣기 전에 그 틱(과 앞의 틱 밖)에서 난 일을 꺼낸다. 세션이 보지 못한 앞 스냅숏(출처를 바꾼 사이)의
     * 시나리오 상태도 차례대로 함께 넘기고, 그 입력은 버린다(지난 회차 것).
     */
    fun takeTick(snapshot: VehicleSnapshot): SimTick = synchronized(lock) {
        val i = attached.indexOfFirst { it.first === snapshot }
        if (i < 0) return SimTick.EMPTY
        val scenario = ArrayList<ScenarioState>()
        var inputs: List<SessionInput> = emptyList()
        repeat(i + 1) { k ->
            val tick = attached.removeFirst().second
            scenario += tick.scenario
            if (k == i) inputs = tick.inputs
        }
        scenario.lastOrNull()?.let { _sessionScenario.value = it }
        SimTick(scenario, inputs)
    }

    // ================================================================ 사건 (틱 안·잠금 안)

    private fun onSimEvent(e: SimEvent) {
        when (e) {
            is SimEvent.Gear -> onGear(e)
            is SimEvent.Scenario -> {
                val s = if (e.unloaded) ScenarioState.IDLE else e.state.toScenarioState()
                if (s != lastQueued) {
                    pendingScenario += s
                    lastQueued = s
                }
            }
            is SimEvent.NavVoice -> _navVoices.tryEmit(NavVoice(e.voice.text, e.voice.t, e.voice.dur, e.voice.key))
            is SimEvent.Manual -> notice("직접 조작해서 재생을 멈췄어요")
            else -> Unit
        }
    }

    private fun onGear(e: SimEvent.Gear) {
        if (e.from != Gear.P || e.to == Gear.P) return
        // 회차가 이 틱에 시작되면 sim 시계 0(그 틱 스냅숏 t = 0, session.js `start`)
        if (runCatching { departureGate?.invoke() }.getOrNull() == true) sim.resetClock(0.0)
        // 패널에서 D로 바꿨는데 재생할 시나리오가 없으면 차는 서 있다 — 무엇을 하면 되는지 알려 준다(T11-13)
        if (e.source == Vehicle.Source.PANEL.code) {
            val st = sim.player.state
            val hasScenario = sim.player.current != null && (st == PlayerState.PLAYING || st == PlayerState.LOADED || st == PlayerState.PAUSED)
            if (!hasScenario) notice("재생할 시나리오가 없어 차가 서 있어요 · 페달(가속)로 직접 달리거나, P로 돌아가 앱의 [내비로 보내고 출발]을 눌러요")
        }
    }

    // ================================================================ 세션 연결 (DemoSimLink)

    /**
     * PR-1 [내비로 보내고 출발](session.js `send` + `loadScenario`): 코스면 `navi.setRoute`, 장소면 `navi.setDestination` →
     * 그 시나리오를 패널 운전 실력으로 불러 자동 재생(패널 '자동 재생'이면 1초 뒤). 경로 요약.
     */
    fun send(courseId: String?, placeId: String?): RouteSummary? = synchronized(lock) {
        val sum = when {
            courseId != null -> sim.navi.setRoute(courseId)
            placeId != null -> sim.navi.setDestination(placeId)
            else -> return null
        }
        val target = if (courseId != null) SimTarget.Course(courseId) else SimTarget.Place(requireNotNull(placeId))
        loadLocked(target, autoplay = sim.player.autoplay)
        publishLocked()
        RouteSummary(name = sum?.name, waypoints = sum?.waypoints ?: 0)
    }

    /**
     * PR-1 주차 변형 [주차 안내 시작](parking-guide.js): 주차 종류·방향을 고르고(모르는 값이면 평행 · 오른쪽) S6-P를 패널 운전 실력으로 불러
     * 자동 재생. 내비 경로는 넣지 않는다. 요약 이름 = 시나리오 이름.
     */
    fun startParking(type: String?, side: String?): RouteSummary = synchronized(lock) {
        sim.chooseParking(type, side)
        val info = loadLocked(SimTarget.Scenario(PARKING_SCENARIO), autoplay = sim.player.autoplay)
        publishLocked()
        RouteSummary(name = info.title, waypoints = 0)
    }

    /** 재생 멈춤(세션 cancel·DR-3 [여기서 마치기]). */
    fun pauseScenario() {
        mutate { sim.pause() }
    }

    /** 멈춘 재생 이어서(DR-3 → D). */
    fun resumeScenario() {
        mutate { if (sim.player.state == PlayerState.PAUSED) sim.play() }
    }

    /**
     * 시계 붙잡기(session.js `clockHold`): 차가 서 있어도 sim 시계를 ×1로 돌린다. [unpause]면 패널 ⏸로 멈춘 시계도 다시 돌린다
     * (화면이 붙잡을 때 — parking-virtual.js PK-2).
     */
    fun hold(name: String, on: Boolean, unpause: Boolean = false) {
        mutate {
            sim.clock.hold(name, on)
            if (on && unpause) sim.clock.paused = false
        }
    }

    // ================================================================ 시나리오 (패널 묶음 '시나리오')

    /** 등록된 시나리오(모드 → id 순, 패널 고르기 목록). */
    fun scenarios(): List<ScenarioSummary> = synchronized(lock) { sim.list() }

    /** 코칭 이벤트 넣기 버튼(설계 7.10, 시뮬레이터 몫 — '운전자: 방금 어땠어?'(K8)는 세션 입력이라 패널이 따로 둔다). */
    fun injections(): List<InjectionInfo> = synchronized(lock) { sim.injections }

    /** 지금 내비 경로의 코스·장소·이름(패널 고르기 '내비 경로: …'). 안내가 없으면 null. */
    fun naviBase(): NaviBase? = synchronized(lock) {
        sim.navi.base?.let { b -> NaviBase(b.courseId, b.placeId, b.name) }
    }

    /** 패널에서 고른 시나리오를 패널 운전 실력으로 불러온다(재생은 하지 않음). 못 불러오면 null과 안내 줄. */
    fun load(target: SimTarget): ScenarioInfo? = synchronized(lock) {
        val info = try {
            loadLocked(target, autoplay = false)
        } catch (e: RuntimeException) {
            notice("불러오지 못했어요: ${e.message}")
            null
        }
        publishLocked()
        info
    }

    private fun loadLocked(target: SimTarget, autoplay: Boolean): ScenarioInfo {
        val skill = applied?.demo?.skill ?: DriverSkill.NORMAL
        return sim.load(target, skill, autoplay = autoplay, autoplayDelay = AUTOPLAY_DELAY)
    }

    /**
     * ▶/⏸(player.js `toggle`): 불러온 것이 없으면 [fallback]을 먼저 불러온다. → 'playing' · 'paused' · null(시나리오 없음).
     */
    fun toggle(fallback: SimTarget?): String? = synchronized(lock) {
        if (sim.player.state == PlayerState.IDLE) {
            if (fallback == null) return null
            try {
                loadLocked(fallback, autoplay = false)
            } catch (e: RuntimeException) {
                notice("불러오지 못했어요: ${e.message}")
                return null
            }
        }
        sim.toggle().also { publishLocked() }
    }

    /** [다음 장면]·[끝으로](player.js `skip`) → 건너뛴 뒤 정보 | null(갈 곳 없음). */
    fun skip(kind: Player.Skip): ScenarioInfo? = synchronized(lock) { sim.skip(kind).also { publishLocked() } }

    fun setSpeed(x: Int) = mutate { sim.setSpeed(x) }

    /** 실제 속도(배속 '실제'): 재생 중에도 실제 시간 그대로. 패널은 설정으로 넣는다([applyPanelLocked]). */
    fun setRealtime(on: Boolean) = mutate { sim.setRealtime(on) }

    fun setAutoplay(on: Boolean) = mutate { sim.player.autoplay = on }

    /** 운전 실력(player.js `setSkill`) → 'reloaded'(불러온 채 재생 전이면 다시 불러옴) · 'live'. */
    fun setSkill(skill: DriverSkill): String = synchronized(lock) { sim.setSkill(skill).also { publishLocked() } }

    /** 규칙 확인(S9-R)처럼 시나리오를 불러 자동 재생(1초 뒤). */
    fun loadAndAutoplay(id: String): ScenarioInfo = synchronized(lock) {
        loadLocked(SimTarget.Scenario(id), autoplay = sim.player.autoplay).also { publishLocked() }
    }

    // ================================================================ 차량 (패널 묶음 '차량', 손 조작)

    /**
     * 기어 버튼·키보드 P R N D(달리는 중 P·반대 방향이면 먼저 세움) → 'now' | 'later'. 'later'면 안내 줄
     * "멈춘 뒤 D로 바꿔요(브레이크)"(panel-sim.js 기어 버튼).
     */
    fun shift(gear: Gear): String = synchronized(lock) {
        sim.shift(gear).also {
            publishLocked()
            if (it == "later") notice("멈춘 뒤 ${gear.name}로 바꿔요(브레이크)")
        }
    }

    /** 패널 손 조작(재생 중 기어·속도·핸들·브레이크를 바꾸면 재생을 멈춤). */
    fun set(patch: VehiclePatch) = mutate { sim.set(patch) }

    fun setBlinker(b: Blinker) = set(VehiclePatch(blinker = b))

    /**
     * 비상등 켜기·끄기(2026-11-12 drive-spec-2, 시연 전용): 패널 '비상등' 토글과 키보드 W가 같이 부른다. 앱 층(여기)에만 둔다 —
     * 시뮬레이터 차 상태·스냅숏·엔진·골든에는 넣지 않는다(골든 정답지가 차 상태 전체를 적어서, 넣으면 18회차가 모두 바뀐다).
     * 코칭은 보지 않고(앱은 진짜 차에서도 비상등을 읽지 않음), 깜빡이(`blinker`)와 따로이며, 손 운전이 아니라 재생을 멈추지 않는다.
     * 차를 처음으로 돌리면([resetAll]) 꺼진다. 패널 설정에 저장하지 않는다.
     */
    fun setHazard(on: Boolean) = mutate { hazard = on }

    /** 가속('throttle')·브레이크('brake') 페달을 누름·뗌. */
    fun pressPedal(name: String, on: Boolean) = mutate { sim.pressPedal(name, on) }

    fun lockBrake(on: Boolean) = mutate { sim.lockBrake(on) }

    /** 손 운전 목표 속도(속도 막대). */
    fun setTargetSpeed(kmh: Double) = mutate { sim.setTargetSpeed(kmh) }

    // ================================================================ 내비 (패널 묶음 '내비')

    /** 이 종류·거리의 회전을 끼워 넣는다 → 패널 글. */
    fun injectTurn(type: String, label: String, distance: Double): String = synchronized(lock) {
        val dir = if (type.contains("LEFT") || type.endsWith("_L") || type == "TURN_UTURN") TurnDir.LEFT else TurnDir.RIGHT
        val r = sim.navi.injectTurn(distance = distance, dir = dir, type = type, force = true)
        publishLocked()
        if (r == null) "회전을 넣을 자리가 없어요"
        else "내비에 $label ${sim.navi.distTo(r.turn.pathTurn ?: r.turn.turn).roundToInt()}m 앞을 넣었어요"
    }

    /** 제한속도 덮어쓰기(앞으로 400m) · null = 경로 값으로. */
    fun overrideLimit(v: Int?) = mutate { if (v == null) sim.navi.overrideLimit(null) else sim.navi.overrideLimit(v, meters = 400.0) }

    fun overrideSchool(on: Boolean) = mutate { sim.navi.overrideSchool(on, meters = 300.0) }

    fun overrideJam(on: Boolean) = mutate { sim.navi.overrideJam(on, meters = 400.0) }

    /** 안내 끝내기(동네 한 바퀴). */
    fun clearRoute() = mutate { sim.navi.clear() }

    /**
     * 내비 창 '목적지 고르기'(navi-ui.js `pick`): 재생 중이면 멈추고 그 장소로 안내(다른 경로면 불러온 시나리오는 내린다). 경로 요약.
     * 연습 준비가 있으면 세션 쪽 [send]로 간다(그 장소 시나리오까지 — [com.barodrive.app.demo.DemoActions.pickDestination]).
     */
    fun setDestination(placeId: String): RouteSummary? = synchronized(lock) {
        sim.pause()
        val sum = sim.navi.setDestination(placeId)
        publishLocked()
        sum?.let { RouteSummary(name = it.name, waypoints = it.waypoints) }
    }

    // ================================================================ 주입 (설계 7.10)

    /** 주입 버튼 [id] → 패널 글. 없는 id면 안내 글. */
    fun inject(id: String): String = synchronized(lock) {
        val msg = try {
            sim.inject(id)
        } catch (e: IllegalArgumentException) {
            "주입 없음: $id"
        }
        publishLocked()
        msg
    }

    // ================================================================ 처음으로 (데모 상태 적용·처음 실행으로 되돌리기)

    /**
     * 프로토타입의 새로고침처럼 시뮬레이터를 처음으로(시계·차·내비·운전자·재생기) 돌리고 패널 설정을 다시 넣는다. 깜빡이가 꺼지는
     * 차 처음으로와 함께 비상등도 끈다. 세션 쪽에 넘기지 않은 일도 버린다.
     */
    fun resetAll(): Unit = synchronized(lock) {
        sim.reset()
        hazard = false
        armInputSinkLocked()
        pendingScenario.clear()
        pendingInputs.clear()
        attached.clear()
        lastQueued = ScenarioState.IDLE
        _sessionScenario.value = ScenarioState.IDLE
        val p = applied
        applied = null
        applyPanelLocked(p ?: PanelSettings(), all = true)
        lastNight = sim.vehicle.state.night
        publishLocked()
        _resets.value += 1
    }

    // ================================================================ 패널 설정 → 시뮬레이터 (panel-sim.js `syncFromPanel`)

    private fun applyPanel(p: PanelSettings): Unit = synchronized(lock) {
        applyPanelLocked(p, all = applied == null)
        publishLocked()
    }

    private fun applyPanelLocked(p: PanelSettings, all: Boolean) {
        val prev = applied?.demo
        val d = p.demo
        applied = p
        if (all || prev?.speed != d.speed || prev.realtime != d.realtime) {
            sim.setRealtime(d.realtime)
            // '실제'는 배속을 곱하는 다른 곳(끝 뒤 마무리 꼬리)에서도 ×1처럼(drive-spec A)
            sim.setSpeed(if (d.realtime) 1 else d.speed.roundToInt())
        }
        if (all || prev?.autoplay != d.autoplay) sim.player.autoplay = d.autoplay
        if (all || prev?.caps != d.caps) {
            sim.setCaps(d.caps)
            _caps.value = d.caps
        }
        if (all || prev?.night != d.night) {
            // 주간/야간은 오토 라이트처럼 하향등도 같이(panel-sim.js 주간/야간 버튼·부팅, 결정사항 E T1b-7)
            sim.vehicle.set(VehiclePatch(night = d.night, lowBeam = d.night), Vehicle.Source.SIM)
            lastNight = sim.vehicle.state.night
        }
        if (all || prev?.model != d.model) sim.setEnvironment(model = d.model)
        if (!all && prev?.skill != d.skill) {
            val r = sim.setSkill(d.skill)
            if (r == "reloaded") notice("운전 실력을 바꿔 다시 불러왔어요")
            else if (sim.player.state == PlayerState.PLAYING) notice("운전 실력은 다음 장면부터(서툰 전용 사건은 다시 불러올 때) 바뀌어요")
        }
    }

    /** 차 night가 패널 밖에서 바뀌면(주입 '밤인데 전조등 끔') 패널 주간/야간도 맞춘다(panel-sim.js `onFrame`). 하향등은 그대로. */
    private fun syncNightLocked() {
        val night = sim.vehicle.state.night
        if (lastNight == night) return
        lastNight = night
        val p = applied ?: return
        if (p.demo.night == night) return
        applied = p.copy(demo = p.demo.copy(night = night))
        scope.launch { panel.update { it.copy(demo = it.demo.copy(night = night)) } }
    }

    // ================================================================ 도구

    private inline fun <T> mutate(block: () -> T): T = synchronized(lock) { block().also { publishLocked() } }

    private fun notice(text: String) {
        _notices.tryEmit(text)
    }

    private fun publishLocked() {
        val v = sim.vehicle.state
        val cur = sim.player.current
        val pr = sim.progress()
        val ns = sim.navState()
        val ov = sim.navi.overrides
        val t = sim.clock.simT
        val segment = ns.nav.segment
        _view.value = SimView(
            scenario = cur?.let { LoadedScenario(it.id, it.title, it.mode, it.skill, it.kind, it.generated, it.courseId, it.placeId) },
            player = sim.player.state,
            simT = t,
            progressS = pr.s,
            progressTotal = pr.total,
            progressRatio = pr.ratio,
            marks = pr.marks,
            car = CarView(
                gear = v.gear, speed = v.speed, steer = v.steer, brake = v.brake, blinker = v.blinker, beltDriver = v.beltDriver,
                passenger = v.passenger, handbrake = v.handbrake, wiper = v.wiper, lowBeam = v.lowBeam, night = v.night,
                outsideTemp = v.outsideTemp, tripDist = v.tripDist, model = v.model,
            ),
            manualTarget = sim.driver.manual.target,
            brakeLock = sim.driver.manual.brakeLock,
            hazard = hazard,
            nav = NavView(
                guiding = ns.guiding, arrived = ns.arrived, tbt = ns.nav.tbt, limit = ns.nav.limit, inSchoolZone = ns.nav.inSchoolZone,
                congested = ns.nav.congested, segmentIndex = segment?.index, segmentCount = segment?.count, destName = ns.destName,
                courseId = ns.courseId, placeId = ns.placeId, overrideSchool = ov.school != null, overrideJam = ov.jam != null,
                overrideLimit = ov.limit,
            ),
            speed = sim.clock.speed,
            realtime = sim.clock.realtime,
            autoplay = sim.player.autoplay,
        )
    }

    companion object {
        /** 배속(설계 7.2). */
        val SPEED_FACTORS = listOf(1, 2, 4)

        /** 실제 주차 안내 시나리오(주차 종류·방향은 [BaroSim.chooseParking]). */
        const val PARKING_SCENARIO = "S6-P"

        /** 자동 재생: 경로가 정해지고 실제 1초 뒤(결정-68). */
        const val AUTOPLAY_DELAY = 1.0

        /** 세션이 꺼내 가지 않은 스냅숏 꼬리표를 이만큼만 둔다. */
        private const val MAX_ATTACHED = 256

        fun PlayerState.toScenarioState(): ScenarioState = when (this) {
            PlayerState.IDLE -> ScenarioState.IDLE
            PlayerState.LOADED -> ScenarioState.LOADED
            PlayerState.PLAYING -> ScenarioState.PLAYING
            PlayerState.PAUSED -> ScenarioState.PAUSED
            PlayerState.ENDED -> ScenarioState.ENDED
        }
    }
}

/** 지금 내비 경로의 출처(`navi.base`): 코스 · 장소 · 이름. */
data class NaviBase(val courseId: String?, val placeId: String?, val name: String?)
