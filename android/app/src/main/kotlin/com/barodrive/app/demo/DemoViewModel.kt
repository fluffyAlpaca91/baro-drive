package com.barodrive.app.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.data.signal.sim.SimController
import com.barodrive.app.data.signal.sim.SimView
import com.barodrive.app.demo.preset.DemoPresets
import com.barodrive.app.feature.share.ShareCenter
import com.barodrive.app.feature.weather.WeatherRules
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.session.api.Speaker
import com.barodrive.app.session.api.VoiceEntry
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.app.session.api.fmtT
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Courses
import com.barodrive.core.model.PlaceList
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.SignalKey
import com.barodrive.core.sim.InjectionInfo
import com.barodrive.core.sim.PlayerState
import com.barodrive.core.sim.SimTarget
import com.barodrive.core.sim.player.Player
import com.barodrive.core.sim.vehicle.CarModel
import com.barodrive.core.sim.vehicle.VehiclePatch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 시연 패널 (설계 9장, 프로토타입 shell/panel.js·panel-sim.js·panel-demo.js·panel-voice.js와 screens/share.js의 '가족 휴대폰 미리보기',
 * screens/demo.js의 시연 도구). 차를 대신하는 조작판이다 — 시연 빌드에만 있다.
 *
 * - 시뮬레이터 조작은 [SimController]로, 배속·자동 재생·운전 실력·신호 지원·주간/야간·차종은 패널 설정([DemoPanelRepository])에 적으면
 *   시뮬레이터가 따라간다(panel-sim.js `syncFromPanel`).
 * - 네트워크는 패널 설정에 남기고 공유([ShareCenter.setOnline])에 넣는다. 가족 휴대폰 카드는 [ShareCenter.familyCards].
 * - 화면이 시계를 붙잡는 일(프로토타입 parking-virtual.js PK-2 · weather.js 와이퍼 30초)은 지금 화면([onScreen])을 보고 여기서 한다.
 */
@HiltViewModel
class DemoViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val sim: SimController,
    private val panel: DemoPanelRepository,
    private val session: SessionController,
    private val voice: VoiceLog,
    private val profiles: ProfileRepository,
    private val share: ShareCenter,
    private val actions: DemoActions,
    private val naviPicker: DemoNaviPicker,
) : ViewModel() {
    private val screen = MutableStateFlow<ScreenId?>(null)
    private val pickerOpen = MutableStateFlow(false)

    /** PR-1 [내비에서 목적지 고르기] → 패널을 열고 목적지 고르기를 보인다. */
    val pickerRequests: Flow<Unit> = naviPicker.requests.map { pickerOpen.value = true }
    private val notice = MutableStateFlow<String?>(null)
    private var noticeJob: Job? = null

    /** 시나리오 고르기에서 고른 값(불러오기 전에도). */
    private val selected = MutableStateFlow<String?>(null)

    private val _effects = Channel<DemoGo>(Channel.BUFFERED)

    /** 데모 도구가 바꾼 뒤 갈 화면. */
    val effects: Flow<DemoGo> = _effects.receiveAsFlow()

    /** 고르기 줄: 앱이 고른 코스(또는 내비 경로) · 불러온 것 · 등록된 시나리오 — 바뀔 때만 다시 만든다(panel-sim.js `buildOptions`). */
    private val scenarioOptions = combine(session.state, sim.view) { st, v ->
        Triple(prepTarget(st, v)?.second, v.scenario?.let { it.id to it.title }, v.scenario != null)
    }.distinctUntilChanged().map { (prepLabel, cur, _) -> options(prepLabel, cur) }

    private val scenarioUi = combine(sim.view, scenarioOptions, panel.state.map { it.demo.skill }.distinctUntilChanged(), selected) { v, opts, skill, sel ->
        scenarioUi(v, opts, skill, sel)
    }.distinctUntilChanged()
    private val carUi = combine(sim.view, panel.state) { v, p -> carUi(v, p) }.distinctUntilChanged()
    private val naviUi = sim.view.map { naviUi(it) }.distinctUntilChanged()
    private val toolsUi = combine(panel.state, profiles.state) { p, st ->
        ToolsSectionUi(
            presets = DemoPresets.list, preset = p.demoPreset, network = p.demo.network, queued = st.activeProfile.shareQueue.size,
            town = p.demo.town, towns = Courses.towns, congestionSeed = p.demo.congestionSeed, tripOff = !p.demo.caps.trip,
            profileId = st.activeProfileId, otherApp = p.demo.otherApp,
        )
    }.distinctUntilChanged()
    private val capsUi = panel.state.map { capsUi(it.demo.caps) }.distinctUntilChanged()
    private val voiceUi = combine(voice.entries, panel.state.map { it.logFilter }.distinctUntilChanged()) { e, f -> voiceUi(e, f) }
        .distinctUntilChanged()
    private val statusLine = combine(sim.view, session.state, panel.state) { v, st, p -> status(v, st, p.demo.caps) }.distinctUntilChanged()
    private val sources = combine(settings.dataSource, settings.voiceOn, panel.state.map { it.open to it.demo.otherApp }.distinctUntilChanged()) { s, on, o ->
        Triple(s, on, o)
    }
    private val extras = combine(panel.state.map { it.mockupHero }.distinctUntilChanged(), pickerOpen) { hero, picker -> hero to picker }

    val uiState: StateFlow<DemoUiState> = combine(
        combine(statusLine, notice, sources) { s, n, src -> Triple(s, n, src) },
        combine(scenarioUi, carUi, naviUi) { a, b, c -> Triple(a, b, c) },
        combine(toolsUi, capsUi, voiceUi) { a, b, c -> Triple(a, b, c) },
        share.familyCards,
        extras,
    ) { top, simUi, rest, family, ex ->
        DemoUiState(
            status = top.first, notice = top.second, dataSource = top.third.first, voiceOn = top.third.second,
            open = top.third.third.first, otherApp = top.third.third.second,
            scenario = simUi.first, car = simUi.second, navi = simUi.third, injections = injectGroups,
            tools = rest.first, caps = rest.second, voice = rest.third, family = family,
            mockupHero = ex.first, pickerOpen = ex.second, places = placeGroups,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DemoUiState())

    /** 목적지 목록(navi.js `placeGroups`): 즐겨찾기 → 최근 목적지. */
    private val placeGroups: List<PlaceGroupUi> by lazy {
        PlaceList.entries.mapNotNull { l -> Courses.places.filter { it.list == l }.takeIf { it.isNotEmpty() }?.let { PlaceGroupUi(l.label, it) } }
    }

    /** 패널 주간/야간 — 앱 테마가 따른다(frame.js `applyTheme`, 야간이 처음 값). */
    val night: StateFlow<Boolean> = panel.state.map { it.demo.night }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, panel.state.value.demo.night)

    /** 다른 앱(음악)이 앞에 — 패널이 닫혀 있어도 덮개를 그리려고 따로. */
    val otherApp: StateFlow<Boolean> = panel.state.map { it.demo.otherApp }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** 주입 버튼 묶음(시뮬레이터 주입 + '운전자: 방금 어땠어?'(K8), 그룹은 처음 나온 차례). */
    private val injectGroups: List<InjectGroupUi> by lazy {
        val all = sim.injections() + InjectionInfo(DemoTables.DRIVER_ASK_ID, DemoTables.DRIVER_ASK_LABEL, DemoTables.DRIVER_ASK_GROUP, DemoTables.DRIVER_ASK_ITEM)
        all.groupBy { it.group }.map { (g, list) -> InjectGroupUi(g, list) }
    }

    init {
        viewModelScope.launch { sim.notices.collect { showNotice(it) } }
        // 깜빡이를 못 읽는데 모드 2·8 회차가 시작됨(경로를 먼저 보낸 뒤 신호를 끈 경우) → 기록 없이 끝냄(demo.js 'app:sessionStart', 설계 7.8)
        viewModelScope.launch {
            session.events.collect { e ->
                if (e !is SessionEvent.Started) return@collect
                val block = DemoTables.startBlock(e.mode, sim.caps.value) ?: return@collect
                session.cancel()
                sim.pauseScenario()
                showNotice("$block · 회차를 기록 없이 끝냈어요")
            }
        }
        // 네트워크: 패널 값 → 공유(share.js — 끊김 → 연결이면 보낼 예정 요약을 보낸다)
        viewModelScope.launch { panel.state.map { it.demo.network }.distinctUntilChanged().collect { share.setOnline(it) } }
        // 화면이 시계를 붙잡는 일: PK-2가 열려 있는 동안(parking-virtual.js) · PR-1에서 와이퍼 30초를 재는 동안(weather.js)
        viewModelScope.launch {
            combine(screen, session.state, sim.view) { s, st, v -> (s == ScreenId.PK_2) to wiperHold(s, st, v) }
                .distinctUntilChanged()
                .collect { (pk2, wiper) ->
                    sim.hold(HOLD_PK2, pk2, unpause = true)
                    sim.hold(HOLD_WIPER, wiper)
                }
        }
    }

    override fun onCleared() {
        sim.hold(HOLD_PK2, false)
        sim.hold(HOLD_WIPER, false)
    }

    // ================================================================ 지금 화면 · 안내 줄

    /** 바로운전 창의 지금 화면(패널 '지금 화면'과 시계 붙잡기). */
    fun onScreen(id: ScreenId?) {
        screen.value = id
    }

    private fun showNotice(text: String?) {
        if (text.isNullOrEmpty()) return
        noticeJob?.cancel()
        notice.value = text
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            notice.value = null
        }
    }

    fun toggleSection(section: DemoTables.Section) = launchPanel { p ->
        p.copy(open = p.open + (section.id to !(p.open[section.id] ?: section.defaultOpen)))
    }

    fun setDataSource(source: DataSource) {
        viewModelScope.launch { settings.setDataSource(source) }
    }

    fun setVoiceOn(on: Boolean) {
        viewModelScope.launch { settings.setVoiceOn(on) }
    }

    // ================================================================ 시나리오 (panel-sim.js renderScenario)

    /** 고른 줄을 불러온다(재생은 ▶ — panel-sim.js `loadSelected`). */
    fun selectScenario(key: String) {
        selected.value = key
        val target = targetOf(key) ?: return
        val info = sim.load(target) ?: return
        showNotice("불러왔어요: ${info.id}" + if (info.generated) " (일반 경로로 만든 시나리오)" else "")
    }

    /** ▶/⏸: 불러온 것이 없으면 고른 줄(없으면 안내). 코칭 없이 달리게 되면 알려 준다(T11-13). */
    fun togglePlay() {
        val idle = sim.view.value.player == PlayerState.IDLE
        val fallback = if (idle) targetOf(selected.value ?: defaultKey()) else null
        if (idle && fallback == null) {
            showNotice("시나리오를 먼저 골라요")
            return
        }
        when (sim.toggle(fallback)) {
            "playing" -> {
                val ready = session.state.value.phase != SessionPhase.IDLE
                showNotice(if (ready) "재생해요" else "재생해요 · 앱에서 연습을 시작하지 않아 코칭 없이 달려요(연습: 홈 → [이어서 연습하기] → [내비로 보내고 출발])")
            }
            null -> if (idle) showNotice("시나리오를 먼저 골라요")
        }
    }

    fun setSkill(skill: DriverSkill) = launchPanel { it.copy(demo = it.demo.copy(skill = skill)) }

    /** 배속 ×1·×2·×4(압축 × 배속, 볼거리 위주로 빨리). '실제'는 끈다. */
    fun setSpeed(x: Int) = launchPanel { it.copy(demo = it.demo.copy(speed = x.toDouble(), realtime = false)) }

    /** 배속 '실제'(처음 값): 재생 중에도 실제 시간 그대로. 고른 ×N은 그대로 두지만 쓰지 않는다. */
    fun setRealtime() = launchPanel { it.copy(demo = it.demo.copy(realtime = true)) }

    fun setAutoplay(on: Boolean) = launchPanel { it.copy(demo = it.demo.copy(autoplay = on)) }

    fun skipNext() {
        val r = sim.skip(Player.Skip.NEXT)
        showNotice(if (r != null) "다음 장면: ${r.mark}" else "다음 장면이 없어요")
    }

    fun skipEnd() {
        val r = sim.skip(Player.Skip.END)
        showNotice(if (r != null) "도착 직전으로 옮겼어요" else "이미 끝 근처예요")
    }

    /** 고른 줄 → 불러올 대상(panel-sim.js `targetOf`): 'prep' = 앱이 고른 코스·목적지(없으면 내비 경로) · 'cur' = 불러온 것. */
    private fun targetOf(key: String?): SimTarget? = when (key) {
        null, "" -> null
        PREP -> prepTarget()?.first
        CUR -> sim.view.value.scenario?.let { SimTarget.Scenario(it.id) }
        else -> SimTarget.Scenario(key)
    }

    private fun defaultKey(): String? = if (prepTarget() != null) PREP else null

    /** 앱이 고른 코스·목적지 → (대상, 줄 글)(panel-sim.js `prepTarget`). */
    private fun prepTarget(st: DrivingState = session.state.value, v: SimView = sim.view.value): Pair<SimTarget, String>? {
        val prep = st.prep
        prep?.courseId?.let { return SimTarget.Course(it) to "앱이 고른 코스 ($it)" }
        prep?.placeId?.let { return SimTarget.Place(it) to "앱이 고른 목적지 ($it)" }
        v.nav.courseId?.let { return SimTarget.Course(it) to "내비 경로: ${v.nav.destName.orEmpty()}" }
        v.nav.placeId?.let { return SimTarget.Place(it) to "내비 경로: ${v.nav.destName.orEmpty()}" }
        return null
    }

    // ================================================================ 차량 (panel-sim.js renderCar)

    /** 기어 버튼. 달리는 중 P·반대 방향이면 시뮬레이터가 먼저 세우고 안내 줄을 낸다([SimController.shift]). */
    fun shift(gear: Gear) {
        sim.shift(gear)
    }

    fun setBlinker(b: Blinker) = sim.setBlinker(b)

    /** 비상등 토글(시연 전용): 키보드 W와 같은 호출([SimController.setHazard]) — 깜빡이와 따로이고 재생을 멈추지 않는다. */
    fun setHazard(on: Boolean) = sim.setHazard(on)

    /** 브레이크·가속 누르고 있기('brake'·'throttle'). */
    fun pedal(name: String, on: Boolean) = sim.pressPedal(name, on)

    fun lockBrake(on: Boolean) = sim.lockBrake(on)

    fun setTargetSpeed(kmh: Float) = sim.setTargetSpeed(kmh.toDouble())

    fun setSteer(deg: Float) = sim.set(VehiclePatch(steer = deg.toDouble()))

    fun setBelt(on: Boolean) = sim.set(VehiclePatch(beltDriver = on))

    fun setPassenger(on: Boolean) = sim.set(VehiclePatch(passenger = on))

    fun setHandbrake(on: Boolean) = sim.set(VehiclePatch(handbrake = on))

    fun setWiper(on: Boolean) = sim.set(VehiclePatch(wiper = on))

    fun setLowBeam(on: Boolean) = sim.set(VehiclePatch(lowBeam = on))

    fun setTemp(c: Float) = sim.set(VehiclePatch(outsideTemp = c.roundToInt().toDouble()))

    /** 주간/야간(오토 라이트처럼 하향등도 같이 — 시뮬레이터가 패널 값을 따라간다). */
    fun setNight(night: Boolean) = launchPanel { it.copy(demo = it.demo.copy(night = night)) }

    fun setModel(id: String) = launchPanel { it.copy(demo = it.demo.copy(model = id)) }

    // ================================================================ 내비 (panel-sim.js renderNavi)

    fun injectTurn(type: String, distance: Int) = showNotice(sim.injectTurn(type, DemoTables.tbtLabel(type), distance.toDouble()))

    fun setLimit(v: Int?) {
        sim.overrideLimit(v)
        showNotice(if (v == null) "제한속도를 경로 값으로" else "제한속도 $v (앞으로 400m)")
    }

    fun setSchool(on: Boolean) = sim.overrideSchool(on)

    fun setJam(on: Boolean) = sim.overrideJam(on)

    fun clearRoute() {
        sim.clearRoute()
        showNotice("안내를 끝냈어요 · 동네 한 바퀴")
    }

    /** 내비 '목적지 고르기' 열기·닫기. */
    fun setPickerOpen(open: Boolean) {
        pickerOpen.value = open
    }

    /** 목적지를 고름(navi-ui.js `pick`): 연습 준비가 있으면 그 장소로 보내고 자동 재생, 없으면 안내만. */
    fun pickDestination(placeId: String) {
        pickerOpen.value = false
        runAction { actions.pickDestination(placeId) }
    }

    /** '보기' [목업 원안 보기](결정-92). */
    fun setMockupHero(on: Boolean) = launchPanel { it.copy(mockupHero = on) }

    // ================================================================ 코칭 이벤트 넣기 (panel-sim.js renderInject · demo.js driverAsk)

    fun inject(id: String) {
        if (id == DemoTables.DRIVER_ASK_ID) {
            viewModelScope.launch { showNotice(actions.driverAsk()) }
            return
        }
        showNotice(sim.inject(id))
    }

    // ================================================================ 데모 도구 (panel.js · panel-demo.js · share.js 네트워크 · course.js 동네)

    fun applyPreset(id: String) = runAction { actions.applyPreset(id) }

    fun resetApp() = runAction { actions.resetApp() }

    /** [패널 설정 초기화](panel.js `reset`): 배속·신호 지원·네트워크·프로필 값 … 처음으로, 가족 휴대폰 카드도 지움. */
    fun resetPanel() {
        viewModelScope.launch {
            panel.reset()
            share.clearFamily()
            showNotice("패널 설정을 처음 값으로 되돌렸어요")
        }
    }

    fun setNetwork(on: Boolean) = launchPanel { it.copy(demo = it.demo.copy(network = on)) }

    fun setTown(id: String) = launchPanel { it.copy(demo = it.demo.copy(town = id)) }

    /** [혼잡도 다시 받기]: 시드 + 1(99를 넘으면 2). */
    fun reloadCongestion() {
        viewModelScope.launch {
            val p = panel.update { s ->
                val seed = s.demo.congestionSeed + 1
                s.copy(demo = s.demo.copy(congestionSeed = if (seed > 99) 2 else seed))
            }
            showNotice("코스 혼잡도를 다시 받았어요(시드 ${p.demo.congestionSeed})")
        }
    }

    fun setProfile(id: String) = runAction { actions.switchProfile(id) ?: DemoResult() }

    fun setOtherApp(on: Boolean) = launchPanel { it.copy(demo = it.demo.copy(otherApp = on)) }

    fun addExampleCourses() {
        viewModelScope.launch {
            val n = actions.addExampleCourses()
            showNotice(if (n > 0) "예시 내 코스 ${n}개를 넣었어요(강변 둔치 주차장 · 우리 동네 한 바퀴)" else "예시 내 코스가 이미 있어요")
        }
    }

    fun startRules() = runAction { actions.startRules() }

    // ================================================================ 신호 지원 (panel-demo.js caps)

    fun setCap(key: SignalKey, readable: Boolean) = launchPanel { it.copy(demo = it.demo.copy(caps = it.demo.caps.with(key, readable))) }

    fun allCaps() {
        launchPanel { it.copy(demo = it.demo.copy(caps = SignalCaps())) }
        showNotice("신호를 모두 읽는 차로 되돌렸어요")
    }

    // ================================================================ 말한 내용 (panel-voice.js)

    fun setLogFilter(f: LogFilter) = launchPanel { it.copy(logFilter = f) }

    fun clearLog() {
        voice.clear()
        showNotice("말한 내용 기록을 지웠어요")
    }

    /** 보이는 줄을 글로(panel-voice.js `copyText`). 없으면 ''. */
    fun logText(): String {
        val f = panel.state.value.logFilter
        return voice.entries.value.filter { visible(it, f) }.joinToString("\n") { lineOf(it) }
    }

    fun copied(lines: Int) = showNotice(if (lines > 0) "${lines}줄을 복사했어요" else "복사할 줄이 없어요")

    // ================================================================ 가족 휴대폰 미리보기 (share.js family)

    fun clearFamily() = share.clearFamily()

    // ================================================================ 만들기

    private fun launchPanel(transform: (PanelSettings) -> PanelSettings) {
        viewModelScope.launch { panel.update(transform) }
    }

    private fun runAction(block: suspend () -> DemoResult) {
        viewModelScope.launch {
            val r = block()
            showNotice(r.notice)
            r.go?.let { _effects.send(it) }
        }
    }

    private fun options(prepLabel: String?, cur: Pair<String, String>?): List<ScenarioOption> {
        val list = sim.scenarios()
        val out = ArrayList<ScenarioOption>()
        if (prepLabel != null) out += ScenarioOption(PREP, prepLabel, "")
        if (cur != null && list.none { it.id == cur.first }) out += ScenarioOption(CUR, "${cur.first} · ${cur.second} (불러옴)", "")
        for (x in list) {
            val title = x.title.replace(Regex("^[^·]*·\\s*"), "")
            out += ScenarioOption(x.id, "${x.id} · $title" + if (x.isDefault) " ★" else "", DemoTables.modeName(x.mode))
        }
        return out
    }

    private fun scenarioUi(v: SimView, options: List<ScenarioOption>, skill: DriverSkill, sel: String?): ScenarioSectionUi {
        val cur = v.scenario
        val listed = cur != null && options.any { it.key == cur.id }
        val selectedKey = when {
            cur != null -> if (listed) cur.id else CUR
            sel != null && options.any { it.key == sel } -> sel
            options.firstOrNull()?.key == PREP -> PREP
            else -> null
        }
        val stateText = when (v.player) {
            PlayerState.LOADED -> "불러옴"
            PlayerState.PLAYING -> "재생 중"
            PlayerState.PAUSED -> "멈춤"
            PlayerState.ENDED -> "끝남"
            PlayerState.IDLE -> "멈춤"
        }
        val info = if (cur != null) "${cur.id} · ${DemoTables.MODE_NAME[cur.mode] ?: ""} · ${cur.skill.label} · $stateText"
        else "불러온 시나리오 없음 · 손 조작"
        val km = if (v.progressTotal > 0) " · " + km(min(v.progressS, v.progressTotal)) + " / " + km(v.progressTotal) + "km" else ""
        val marks = if (v.marks.isNotEmpty()) " · 장면 " + v.marks.joinToString(" → ") { (if (it.passed) "✓" else "") + it.name } else ""
        return ScenarioSectionUi(
            options = options, selected = selectedKey, info = info, skill = skill, playing = v.player == PlayerState.PLAYING,
            ended = v.player == PlayerState.ENDED, speed = v.speed, realtime = v.realtime, autoplay = v.autoplay, progress = v.progressRatio,
            marks = v.marks,
            progressText = "sim " + DemoTables.fmtSim(v.simT) + km + marks,
        )
    }

    private fun km(m: Double): String = String.format(java.util.Locale.ROOT, "%.1f", m / 1000)

    private fun carUi(v: SimView, p: PanelSettings): CarSectionUi {
        val c = v.car
        val read = "${c.gear.name} · ${c.speed.roundToInt()}km/h · 핸들 ${c.steer.roundToInt()}° · 브레이크 ${if (c.brake) "밟음" else "뗌"}" +
            " · 이번 주행 " + String.format(java.util.Locale.ROOT, "%.2f", c.tripDist / 1000) + "km"
        return CarSectionUi(
            car = c, manualTarget = v.manualTarget, brakeLock = v.brakeLock, hazard = v.hazard, night = p.demo.night, model = p.demo.model,
            models = CarModel.ALL.values.map { it.id to it.name }, readLine = read,
        )
    }

    private fun naviUi(v: SimView): NaviSectionUi {
        val n = v.nav
        val t0 = n.tbt.getOrNull(0)
        val t1 = n.tbt.getOrNull(1)
        val limit = n.limit?.let { " · 제한 $it" } ?: ""
        val text = if (!n.guiding && !n.arrived && t0 == null) {
            "안내 없음$limit"
        } else {
            val turns = if (t0 != null) {
                "${DemoTables.tbtLabel(t0.type)} ${t0.distance.roundToInt()}m (${t0.id})" +
                    (t1?.let { " → ${DemoTables.tbtLabel(it.type)} ${it.distance.roundToInt()}m" } ?: "")
            } else {
                "회전 없음"
            }
            turns + limit + (if (n.inSchoolZone) " · 어린이보호구역" else "") + (if (n.congested) " · 정체" else "") +
                (if ((n.segmentCount ?: 0) > 1) " · 구간 ${n.segmentIndex}/${n.segmentCount}" else "") + (if (n.arrived) " · 도착" else "")
        }
        return NaviSectionUi(now = text, school = n.overrideSchool, jam = n.overrideJam, limit = n.overrideLimit)
    }

    private fun capsUi(caps: SignalCaps): CapsSectionUi {
        val rows = DemoTables.CAP_ROWS.map { r -> CapRowUi(r, caps[r.key], capItems.getValue(r.key)) }
        val off = rows.filter { !it.readable }
        val summary = if (off.isEmpty()) "모든 신호를 읽어요" else "못 읽는 신호 ${off.size}개: " + off.joinToString("·") { it.row.name }
        return CapsSectionUi(caps, rows, summary)
    }

    /** 신호마다 꺼지는 항목(BaroEngine.itemsOffByCaps — 설계 7.8). */
    private val capItems: Map<SignalKey, List<String>> by lazy {
        SignalKey.entries.associateWith { k -> runCatching { BaroEngine.itemsOffByCaps(SignalCaps().with(k, false)) }.getOrDefault(emptyList()) }
    }

    private fun voiceUi(entries: List<VoiceEntry>, f: LogFilter): VoiceSectionUi {
        var coach = 0
        var navi = 0
        var blocked = 0
        for (e in entries) when {
            e.blocked -> blocked++
            e.speaker == Speaker.NAVI -> navi++
            else -> coach++
        }
        val visible = entries.filter { visible(it, f) }
        return VoiceSectionUi(
            rows = visible.takeLast(VoiceSectionUi.VOICE_ROWS), coach = coach, navi = navi, blocked = blocked, shown = visible.size, filter = f,
        )
    }

    private fun visible(e: VoiceEntry, f: LogFilter): Boolean = when {
        e.blocked -> f.blocked
        e.speaker == Speaker.NAVI -> f.navi
        else -> f.coach
    }

    /** 복사 줄(panel-voice.js `lineOf`): '[mm:ss] 막힘(이유) · 코치(이름) · 분류 · 항목 · 키 — 문장'. */
    private fun lineOf(e: VoiceEntry): String {
        val bits = ArrayList<String>()
        bits += if (e.speaker == Speaker.NAVI) "내비" else "코치(${e.coach?.title.orEmpty()})"
        e.category?.let { bits += it.label }
        e.item?.let { bits += it }
        e.key?.let { bits += it }
        val head = "[" + fmtT(e.t) + "] " + (if (e.blocked) "막힘(${e.reason?.label ?: "이유 없음"}) · " else "") + bits.joinToString(" · ")
        return head + " — " + e.text
    }

    /** 맨 위 상태 줄: 기어(10) · 속도(20) · 세션(50) · 못 읽는 신호(70) · sim 시각(90). */
    private fun status(v: SimView, st: DrivingState, caps: SignalCaps): String {
        val parts = ArrayList<String>()
        parts += v.car.gear.name
        parts += "${abs(v.car.speed).roundToInt()}km/h"
        if (st.label.isNotEmpty()) parts += st.label
        val off = caps.off.size
        if (off > 0) parts += "못 읽는 신호 $off"
        parts += "sim " + DemoTables.fmtSim(v.simT)
        return parts.joinToString(" · ")
    }

    /** PR-1에서 세워 둔 차의 와이퍼 30초를 재는 동안(weather.js `syncHold`): 준비 세션 · 날씨 줄이 있는 모드 · 와이퍼 켬 · 30초 전. */
    private fun wiperHold(s: ScreenId?, st: DrivingState, v: SimView): Boolean {
        val prep = st.prep ?: return false
        if (s != ScreenId.PR_1 || st.phase == SessionPhase.ACTIVE || prep.mode !in WeatherRules.MODES) return false
        val limit = BaroEngine.threshold("J3.wiperSec") ?: WIPER_SEC
        return v.car.wiper && prep.weather.wiperSec < limit - 1e-6
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val NOTICE_MS = 4_000L
        const val PREP = "prep"
        const val CUR = "cur"
        const val HOLD_PK2 = "PK-2"
        const val HOLD_WIPER = "weather-wiper"
        const val WIPER_SEC = 30.0
    }
}
