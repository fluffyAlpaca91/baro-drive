package com.barodrive.app.session

import com.barodrive.app.data.navi.NaviController
import com.barodrive.app.data.navi.NaviDestination
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.data.repo.StoreRules
import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.data.signal.ActiveSignalSource
import com.barodrive.app.data.signal.SignalFrame
import com.barodrive.app.data.voice.CoachVoice
import com.barodrive.app.data.voice.CoachVoiceLog
import com.barodrive.app.di.ApplicationScope
import com.barodrive.app.di.DefaultDispatcher
import com.barodrive.app.session.api.ActivePhase
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SendResult
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.Card
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.Gear
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.LicenseResult
import com.barodrive.core.model.Mode
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ProgressResult
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TodayTask
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.VehicleSnapshot
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 주행 세션 관리자 (프로토타입 app/session.js, 설계 3.1·3.2·7.8). 앱에 하나. 공개 API는 [SessionController].
 * 신호 → 엔진(파사드, [SessionEngine]) → 음성·자막 기록 → 화면 상태 → 진행 반영·기록 저장. 화면이 바뀌거나 Activity가 다시
 * 만들어져도 세션은 여기(앱 범위)서 이어진다. 프로세스가 죽으면 진행 중이던 회차는 기록 없이 사라진다(설계 3.2 '새로고침').
 *
 * - [prepare](PR-1) → READY. 기어가 P를 벗어나면 엔진 세션을 만들고 그 스냅숏을 세션 0초로 맞춘다(설계 3.2 '시계를 0으로').
 * - P가 되면 엔진이 그 P 표본을 본 뒤 정한다([decidePark]): 도착(내비 도착·hints.arrived·시나리오 끝)이면 마무리 — P 표본 30개
 *   (3 sim초) 이상이고 마지막 코치 말이 끝나고 한 틱, 내비 음성 기다림도 끝났을 때(길어도 60개) 저장 → [SessionEvent.Finished].
 *   아니면 DR-3([ActivePhase.STOPPED]). 시나리오가 P를 넣고 아직 재생 중이면 표본 20개(2 sim초)까지 기다려 본다.
 * - 기어를 못 읽으면 트립 거리로: 움직이면 출발, 10 sim초 안 움직이면 정차([GearlessTracker], 설계 7.8).
 * - 연습 중에 자료 출처가 바뀌면 그 세션은 버린다(기록하지 않음).
 * - [내비로 보내고 출발]: 시뮬레이터 자료 출처는 시연 시뮬레이터([SimLink])로, 진짜 차는 차 내비([NaviController], Pleos NaviHelper)로.
 *
 * 스레드: 신호 처리와 공개 함수는 모두 [mutex] 안에서 차례로 돈다. 상태는 [state]로만 내보낸다.
 */
@Singleton
class DrivingSessionManager @Inject constructor(
    private val signals: ActiveSignalSource,
    private val engine: SessionEngine,
    private val tts: CoachVoice,
    private val voice: CoachVoiceLog,
    private val profiles: ProfileRepository,
    private val records: RecordRepository,
    private val settings: SettingsRepository,
    private val sim: SimLink,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
    private val navi: NaviController,
) : SessionController {
    private val _state = MutableStateFlow(DrivingState())
    override val state: StateFlow<DrivingState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    override val events: Flow<SessionEvent> = _events.asSharedFlow()

    /** 지금 프로필 — [itemValue]처럼 바로 답해야 하는 곳에서만 읽는다(나머지는 [ProfileRepository.current]). */
    private val profile: StateFlow<Profile> = profiles.profile.stateIn(scope, SharingStarted.Eagerly, Profile())

    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    private val gearless = GearlessTracker()

    // ---- 아래는 mutex 안에서만 쓴다 (session.js `st`). prep·active는 itemValue가 밖에서 읽어 @Volatile ----
    private var source: DataSource? = null

    @Volatile
    private var prep: PrepSession? = null

    @Volatile
    private var active: EngineSession? = null

    /** 회차를 만들지 못함 — 기어가 P로 돌아올 때까지 다시 해 보지 않는다(프로토타입은 기어가 바뀔 때만 시작한다). 출발 문이 밖에서 읽어 @Volatile. */
    @Volatile
    private var startFailed = false
    private var t0 = 0.0
    private var phase = SessionPhase.IDLE
    private var activePhase: ActivePhase? = null
    private var arrived = false
    private var cards: List<Card> = emptyList()
    private var muted = false
    private var lastOutput: StepOutput? = null
    private var lastSay: Utterance? = null
    private var mode: Int? = null
    private var noInMode: Int? = null
    private var today: List<TodayTask> = emptyList()
    private var pSamples = 0
    private var sent = false
    private var lastRecordId: String? = null
    private var dr3Continue = false
    private var speakEnd = Double.NEGATIVE_INFINITY
    private var navUntil = Double.NEGATIVE_INFINITY
    private var lastSnap: VehicleSnapshot? = null
    private var elapsed = 0.0

    /** 준비 세션마다 한 번인 정차 중 말(prep.lines의 entered·pb1Asked — 모델 PrepLines에 없는 칸). */
    private var entered = false
    private var pb1Asked = false

    /** 이 준비 세션에서 시나리오가 재생됐나·끝났나(session.js `st.scn`). */
    private var scnPlayed = false
    private var scnEnded = false

    /** 신호 수집을 시작한다. 앱 시작 때 한 번(BaroApplication). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // 출발 문: 기어가 P를 벗어나는 틱에 회차가 시작되면 시뮬레이터가 그 자리에서 시계를 0으로(session.js `start`). 틱 안에서 불려 잠그지 않는다.
        sim.onDeparture { active == null && prep != null && !startFailed }
        scope.launch(dispatcher) {
            signals.frames.collect { frame -> mutex.withLock { onFrame(frame) } }
        }
        scope.launch(dispatcher) {
            settings.voiceOn.collect { on -> tts.muted = !on }
        }
        scope.launch(dispatcher) {
            sim.scenario.collect { s -> mutex.withLock { onScenario(s) } }
        }
        scope.launch(dispatcher) {
            sim.navVoice.collect { v -> mutex.withLock { voice.navi(v.text, v.t?.let { it - t0 } ?: elapsed, v.dur, v.key) } }
        }
    }

    // ================================================================ 준비 세션 (PR-1)

    override suspend fun prepare(prep: PrepSession): PrepSession? = mutex.withLock {
        if (active != null) {
            warn("회차가 진행 중이라 준비 세션을 바꾸지 않아요")
            return this.prep
        }
        val current = profiles.current()
        val m = modeNum(prep.mode)
        val p = prep.copy(mode = m, noInMode = prep.noInMode?.takeIf { it > 0 } ?: current.nextNoInMode(m))
        this.prep = p
        phase = SessionPhase.READY
        mode = p.mode
        noInMode = p.noInMode
        today = computeToday(current, p.mode)
        sent = false
        lastRecordId = null
        entered = false
        pb1Asked = false
        scnPlayed = false
        scnEnded = false
        publish()
        p
    }

    override suspend fun updatePrep(transform: (PrepSession) -> PrepSession): PrepSession? = mutex.withLock {
        val p = prep ?: return null
        if (active != null) return null
        val next = transform(p)
        prep = next
        mode = next.mode
        noInMode = next.noInMode
        publish()
        next
    }

    override suspend fun drop(): Boolean = mutex.withLock {
        if (active != null || prep == null) return false
        prep = null
        phase = SessionPhase.IDLE
        mode = null
        noInMode = null
        today = emptyList()
        sent = false
        publish()
        true
    }

    override suspend fun enterPredrive(readTasks: Boolean, onEnter: (() -> Unit)?): Boolean = mutex.withLock {
        if (prep == null || active != null) return false
        if (readTasks && prep?.lines?.tasksRead == false) readTasksLocked()
        if (onEnter != null && !entered) { // 변형의 정차 중 말(언덕 p.hill 등) — 준비 세션마다 한 번
            entered = true
            runCatching(onEnter).onFailure { warn("PR-1 변형 onEnter 오류", it) }
        }
        val snap = lastSnap
        if (snap?.passenger == true) passengerAboardLocked()
        if (snap != null && snap.handbrake == null) askHandbrakeLocked()
        publish()
        true
    }

    override suspend fun readTasks(): Boolean = mutex.withLock { readTasksLocked().also { publish() } }

    override suspend fun passengerAboard(): Boolean = mutex.withLock { passengerAboardLocked().also { publish() } }

    override suspend fun askHandbrake(): Boolean = mutex.withLock { askHandbrakeLocked().also { publish() } }

    override suspend fun confirmHandbrake(): Boolean = mutex.withLock {
        val p = prep ?: return false
        if (active != null || p.checks.pbConfirmed) return false
        prep = p.copy(checks = p.checks.copy(pbConfirmed = true), preEvents = p.preEvents + preEvent("PB1", EventData(confirmed = true)))
        publish()
        true
    }

    override suspend fun setMirror(on: Boolean): Boolean = mutex.withLock {
        val p = prep ?: return false
        if (active != null) return false
        prep = p.copy(checks = p.checks.copy(mirror = on))
        publish()
        on
    }

    override suspend fun send(): SendResult? = mutex.withLock {
        val p = prep ?: return null
        if ((source ?: settings.dataSource.first()) == DataSource.CAR) return sendToCarNavi(p)
        // 모드 6(실제 주차 안내)은 코스 없이도 그 종류·방향의 주차 시나리오를 보낸다(parking-guide.js [주차 안내 시작]).
        if (active != null || (p.courseId == null && p.placeId == null && p.mode != Mode.PARKING.no)) return null
        val summary = try {
            sim.send(p)
        } catch (e: RuntimeException) {
            warn("내비로 보내지 못했어요", e)
            null
        } ?: return null
        sent = true
        scnPlayed = false
        scnEnded = false
        publish()
        SendResult(toast = SendToast.text(p, summary.name, summary.waypoints))
    }

    /**
     * 진짜 차의 [내비로 보내고 출발]: 내비에서 가져온 장소(좌표)면 차 내비에 안내를 부탁한다(`requestRoute`). 좌표가 없는 팀 코스·팀 장소면
     * 보내지 않고 그렇다고 알린다 — 내비에서 목적지를 넣고 기어를 D로 바꾸면 회차는 그대로 시작된다. 코스·장소가 없으면 null.
     */
    private fun sendToCarNavi(p: PrepSession): SendResult? {
        if (active != null) return null
        val dest = p.navi
        if (dest == null) {
            return if (p.courseId != null || p.placeId != null) SendResult(SendToast.NO_COORDS, sent = false) else null
        }
        val ok = try {
            navi.requestRoute(NaviDestination.of(dest))
        } catch (e: RuntimeException) {
            warn("내비로 보내지 못했어요", e)
            false
        }
        if (!ok) return SendResult(SendToast.NAVI_FAILED, sent = false)
        sent = true
        publish()
        return SendResult(toast = SendToast.text(p))
    }

    /** 오늘 과제 읽기('p.tasks'). */
    private fun readTasksLocked(): Boolean {
        val p = prep ?: return false
        if (active != null || today.isEmpty()) return false
        prep = p.copy(lines = p.lines.copy(tasksRead = true))
        voice.sayLine("p.tasks", mapOf("nWord" to engine.numWord(today.size), "list" to today.joinToString(", ") { it.label }))
        return true
    }

    /** 조수석에 사람이 앉음(정차 중) → L1 회차에 한 번(설계 6.3.1). O면 말하고, 아니면 막힌 말로 기록만. */
    private fun passengerAboardLocked(): Boolean {
        val p = prep ?: return false
        if (active != null || p.lines.l1Said) return false
        val v = prepItemValue("L1", p)
        if (v == Delivery.NOW) voice.sayLine("l1", item = "L1") else voice.blockedLine("l1", "L1", v.blockReason())
        prep = p.copy(
            lines = p.lines.copy(l1Said = true),
            preEvents = p.preEvents + preEvent("L1", EventData(spoken = v == Delivery.NOW, delivery = v)),
        )
        return true
    }

    /** 사이드브레이크를 못 읽으면 PR-1에 들어올 때 한 번 묻는다(설계 6.3.1 PB1). X면 줄을 숨기므로 묻지 않는다. */
    private fun askHandbrakeLocked(): Boolean {
        val p = prep ?: return false
        if (active != null || pb1Asked) return false
        val v = prepItemValue("PB1", p)
        if (v == Delivery.OFF) return false
        pb1Asked = true
        if (v == Delivery.NOW) voice.sayLine("pb1.ask", item = "PB1") else voice.blockedLine("pb1.ask", "PB1", v.blockReason())
        prep = p.copy(preEvents = p.preEvents + preEvent("PB1", EventData(asked = true, spoken = v == Delivery.NOW, delivery = v)))
        return true
    }

    // ================================================================ 회차 중 입력 (DR-1·DR-3·PK-3 …)

    override suspend fun input(event: SessionInput): StepOutput? = mutex.withLock { inputLocked(event) }

    private fun inputLocked(event: SessionInput): StepOutput? {
        val ses = active ?: return null
        val out = try {
            ses.input(event)
        } catch (e: RuntimeException) {
            warn("입력을 넘기지 못했어요", e)
            return null
        }
        deliver(out)
        publish()
        return out
    }

    override suspend fun toggleMute(on: Boolean?): Boolean? = mutex.withLock {
        if (active == null) return null
        inputLocked(if (on ?: !muted) SessionInput.Mute else SessionInput.Unmute)
        muted
    }

    override suspend fun continueHere(): Boolean = mutex.withLock {
        if (active == null || activePhase != ActivePhase.STOPPED) return false
        dr3Continue = true
        publish()
        true
    }

    /**
     * DR-3 [여기서 마치기](session.js `endHere`): P 마무리(PB2 등 P 때 말)를 아직 못 끝냈으면 차가 서 있는 P 표본을 0.1초씩 이어 붙여
     * 엔진을 바로 돌린 뒤(프로토타입의 빨리 감기) 도착 안 함으로 저장한다.
     */
    override suspend fun endHere(): String? = mutex.withLock {
        val ses = active ?: return null
        val snap = lastSnap
        if (snap != null && snap.gear == Gear.P && !pDone(snap.t - t0)) {
            sim.pause()
            activePhase = ActivePhase.FINISHING
            var t = snap.t
            var n = 0
            while (active === ses && n < SessionController.P_MAX + 10) {
                t += TICK
                step(ses, snap.copy(t = t, speed = snap.speed?.let { 0.0 }, nav = snap.nav?.copy(events = emptyList(), voice = false)))
                n++
            }
        }
        if (active === ses) finish(arrived = false) else lastRecordId
    }

    override suspend fun cancel(): Boolean = mutex.withLock {
        val p = prep
        if (active == null && p == null) return false
        val event = SessionEvent.Cancelled(mode = mode ?: p?.mode ?: 1, noInMode = noInMode ?: p?.noInMode, active = active != null)
        active = null
        if (sim.scenario.value == ScenarioState.PLAYING) sim.pause()
        resetState(lastRecordId)
        _events.emit(event)
        true
    }

    override fun itemValue(item: String): Delivery {
        active?.let { ses -> return runCatching { ses.deliver(item) }.getOrNull() ?: Delivery.OFF }
        val p = prep ?: return Delivery.NOW
        return prepItemValue(item, p)
    }

    override suspend fun todayTasks(mode: Int): List<TodayTask> = computeToday(profiles.current(), modeNum(mode))

    // ================================================================ 신호 (session.js `onTick`)

    private suspend fun onFrame(frame: SignalFrame) {
        if (frame.source != source) {
            if (active != null) discard()
            source = frame.source
            gearless.reset()
        }
        val prev = lastSnap
        val snap = frame.snapshot
        lastSnap = snap
        // 이 틱 안에서 시뮬레이터에 난 일(프로토타입은 틱 안에서 바로 받았다): 시나리오 상태는 먼저, 시나리오 입력은 엔진 걸음 바로 전에.
        val tick = sim.takeTick(snap)
        tick.scenario.forEach(::onScenario)
        val dt = prev?.let { snap.t - it.t }?.takeIf { it > 0 } ?: TICK
        voice.advance(dt)
        if (snap.gear == null) gearlessTick(snap, dt) else gearlessOff()
        if (snap.gear == Gear.P) startFailed = false
        // 기어가 P를 벗어남: 준비 세션이 있으면 회차 시작(설계 3.2). 없으면 DR-0(화면 몫).
        if (active == null && prep != null && !startFailed && snap.gear != null && snap.gear != Gear.P) begin(snap)
        val ses = active
        if (ses == null) {
            prepTick(snap)
        } else {
            tick.inputs.forEach { inputLocked(it) }
            if (active === ses) step(ses, snap)
        }
        publish()
    }

    /** 기어를 다시 읽음: 기어 없이 재던 정차와 그 시계 붙잡기를 푼다(session.js `gearlessOff`). */
    private fun gearlessOff() {
        if (gearless.on) sim.hold(SimLink.HOLD_GEARLESS, false)
        gearless.reset()
    }

    /** 준비 세션 중 정차: 조수석에 앉는 순간 L1(설계 4.2 PR-1). */
    private fun prepTick(snap: VehicleSnapshot) {
        val p = prep ?: return
        if (active != null) return
        if (snap.gear != null && snap.gear != Gear.P) return
        if (snap.passenger == true && !p.lines.l1Said) passengerAboardLocked()
    }

    /** 기어를 못 읽을 때 출발·정차(session.js `gearlessTick`·`gearlessDrive`·`gearlessPark`). */
    private suspend fun gearlessTick(snap: VehicleSnapshot, dt: Double) {
        when (gearless.tick(snap, dt)) {
            GearlessTracker.Change.DROVE_OFF -> {
                sim.hold(SimLink.HOLD_GEARLESS, true) // 10초 정차를 잴 수 있게 시계를 붙잡는다
                if (active == null && prep != null) begin(snap) else if (active != null) toDriving()
            }
            GearlessTracker.Change.STOPPED -> {
                sim.hold(SimLink.HOLD_GEARLESS, false)
                // 10초 멈춤이 이미 '정차' — 도착 뒤면 바로 저장(리뷰는 정차 때만), 아니면 DR-3.
                if (active != null) if (endsAtPark()) finish(arrived) else toStopped()
            }
            null -> Unit
        }
    }

    /** 회차 시작(session.js `start`): buildConfig → createSession → 이 스냅숏이 세션 0초(엔진에는 부른 쪽이 넣는다). */
    private suspend fun begin(snap: VehicleSnapshot) {
        var p = prep ?: return
        if (p.startedAt == null) p = p.copy(startedAt = StoreRules.isoLocal(ZonedDateTime.now(clock)))
        // 사이드미러 체크(A5 체크리스트, 결정-48)는 출발 때 값 하나로 남긴다.
        if (p.preEvents.none { it.item == "A5" }) p = p.copy(preEvents = p.preEvents + preEvent("A5", EventData(checked = p.checks.mirror)))
        prep = p
        val ses = try {
            engine.createSession(engine.buildConfig(p.mode, profiles.current(), p, signals.caps.value))
        } catch (e: RuntimeException) {
            warn("회차를 시작하지 못했어요", e)
            startFailed = true
            return
        }
        active = ses
        t0 = snap.t
        elapsed = 0.0
        mode = ses.config.mode
        noInMode = ses.config.sessionNoInMode
        arrived = false
        cards = emptyList()
        muted = false
        lastOutput = null
        lastSay = null
        pSamples = 0
        phase = SessionPhase.ACTIVE
        activePhase = ActivePhase.DRIVING
        dr3Continue = false
        lastRecordId = null
        speakEnd = Double.NEGATIVE_INFINITY
        navUntil = Double.NEGATIVE_INFINITY
        voice.simT = 0.0
        voice.dropStationary() // 출발하면 정차 중 말은 거둔다(voice.js 'sim:gear')
        _events.emit(
            SessionEvent.Started(
                mode = ses.config.mode, noInMode = ses.config.sessionNoInMode, stage = ses.config.stage, courseId = p.courseId,
                placeId = p.placeId, presetId = p.presetId, startedAt = p.startedAt,
            ),
        )
    }

    /** 회차 중 스냅숏 하나(session.js `onTick`의 엔진 부분과 P 처리). */
    private suspend fun step(ses: EngineSession, snap: VehicleSnapshot) {
        val rebased = snap.copy(t = snap.t - t0)
        val gear = snap.gear
        if (gear == Gear.P && activePhase == ActivePhase.DRIVING) {
            activePhase = ActivePhase.PARK_WAIT // 다음 틱(엔진이 이 P 표본을 본 뒤)에 정한다(session.js `parkNow`)
            sim.hold(SimLink.HOLD_FINISH, true) // 서 있어도 P 표본을 모을 수 있게 시계를 붙잡는다
        } else if (gear != null && gear != Gear.P && activePhase != ActivePhase.DRIVING) {
            toDriving()
        }
        val out = try {
            ses.step(rebased)
        } catch (e: RuntimeException) {
            warn("엔진 step 오류", e)
            return
        }
        elapsed = rebased.t
        voice.simT = rebased.t
        if (snap.nav?.events?.contains(NavEvent.Arrived) == true) arrived = true
        deliver(out)
        if (active !== ses) return
        if (snap.nav?.voice == true) navUntil = rebased.t + NAV_AFTER // 엔진 규칙 4: 내비 음성 + 1초 기다림
        if (gear == Gear.P) {
            pSamples++
            if (activePhase == ActivePhase.PARK_WAIT) decidePark()
            if (activePhase == ActivePhase.FINISHING && pDone(rebased.t)) finish(arrived)
            return
        }
        pSamples = 0
    }

    /** D로(session.js `onDriveGear`): 중간 정차·마무리 중이었으면 주행으로. DR-3에서 오면 멈춘 시나리오를 이어서. */
    private fun toDriving() {
        val fromStop = activePhase == ActivePhase.STOPPED
        activePhase = ActivePhase.DRIVING
        dr3Continue = false
        sim.hold(SimLink.HOLD_FINISH, false)
        if (fromStop && sim.scenario.value == ScenarioState.PAUSED) sim.resume()
    }

    /** 엔진 출력 넘기기(session.js `deliver`): 말은 음성으로, 막힌 말은 기록만. */
    private fun deliver(out: StepOutput) {
        for (u in out.say) {
            voice.say(u)
            lastSay = u
            val end = u.t + (u.dur ?: 0.0)
            if (end > speakEnd) speakEnd = end // 엔진 규칙 3 '말하는 중'이 풀리는 때
        }
        if (out.hints.arrived) arrived = true // 검출기의 '도착'(주차 안내 끝 등)도 내비 도착과 같이
        out.blocked.forEach { voice.blocked(it) }
        cards = out.cards
        muted = out.muted
        lastOutput = out
    }

    /** 도착으로 끝나는 P인가(session.js `endsAtPark`): 도착 · 시나리오가 끝남. */
    private fun endsAtPark(): Boolean = arrived || scnEnded

    /** P 표본마다(PARK_WAIT): 끝이면 마무리, 시나리오가 P를 넣고 아직 재생 중이면 잠깐 더, 아니면 DR-3(session.js `decidePark`). */
    private fun decidePark() {
        when {
            endsAtPark() -> activePhase = ActivePhase.FINISHING
            sim.scenario.value == ScenarioState.PLAYING && pSamples < PARK_WAIT -> Unit
            else -> toStopped()
        }
    }

    private fun toStopped() {
        activePhase = ActivePhase.STOPPED
        dr3Continue = false
        sim.hold(SimLink.HOLD_FINISH, false)
    }

    /** 마무리를 끝내도 되나(session.js `pDone`): P 표본 30개 이상이고 밀린 P 때 말이 없음 · 아니어도 60개면 끝. */
    private fun pDone(t: Double): Boolean {
        if (pSamples >= SessionController.P_MAX) return true
        if (pSamples < SessionController.P_MIN) return false
        return t >= speakEnd + TICK - EPS && t >= navUntil - EPS
    }

    /** 시나리오 상태('sim:scenario', session.js `onScenario`). */
    private fun onScenario(s: ScenarioState) {
        if (s == ScenarioState.LOADED) {
            scnPlayed = false
            scnEnded = false
            return
        }
        if (prep == null && active == null) return
        if (s == ScenarioState.PLAYING) scnPlayed = true
        else if (s == ScenarioState.ENDED && scnPlayed) scnEnded = true
    }

    /** 회차 끝(session.js `finish`): 엔진 finish → 진행 반영 → 저장 → 알림. 저장한 기록 id. */
    private suspend fun finish(arrived: Boolean): String? {
        val ses = active ?: return null
        val m = mode ?: ses.config.mode
        active = null // 다시 들어오지 않게 먼저 비운다
        val rec = try {
            ses.finish(arrived)
        } catch (e: RuntimeException) {
            warn("회차를 마치지 못했어요", e)
            null
        }
        var saved: SessionRecord? = null
        var passed: SessionEvent.StagePassed? = null
        if (rec != null) {
            var toSave = rec
            try {
                // 진행 반영(review.js applyProgress) — 바뀐 프로필과 기록을 둘 다 저장한다. 모드 8은 완주 코스도(license.js applyCourseDone).
                val now = StoreRules.isoLocal(ZonedDateTime.now(clock))
                var result: ProgressResult? = null
                profiles.update { current ->
                    val applied = engine.applyProgress(current, rec, now)
                    val (p, r) = withCourseDone(applied.profile, applied.record)
                    toSave = r
                    result = applied.result
                    p
                }
                val r = result
                val stage = r?.stage
                if (r != null && r.passed && stage != null) passed = SessionEvent.StagePassed(stage, r.graduated, r.nextStage, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("진행을 반영하지 못했어요", e)
            }
            saved = try {
                records.add(toSave)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("회차를 저장하지 못했어요", e)
                null
            }
        }
        val id = saved?.id
        resetState(id)
        _events.emit(SessionEvent.Finished(recordId = id, mode = m, arrived = arrived))
        passed?.let { _events.emit(it.copy(recordId = id)) }
        return id
    }

    /**
     * 모드 8 회차 끝 완주 코스 반영(license.js `onSessionEnd`): `profile.license.coursesDone`과 기록 `license.coursesDoneAfter`를
     * applyCourseDone의 `after`로. 다른 모드·계산 실패면 그대로.
     */
    private fun withCourseDone(profile: Profile, record: SessionRecord): Pair<Profile, SessionRecord> {
        if (record.mode != Mode.LICENSE.no) return profile to record
        val done = try {
            engine.applyCourseDone(profile, record)
        } catch (e: RuntimeException) {
            warn("회차 끝 코스 진행을 반영하지 못했어요", e)
            return profile to record
        }
        return profile.copy(license = profile.license.copy(coursesDone = done.after)) to
            record.copy(license = (record.license ?: LicenseResult()).copy(coursesDoneAfter = done.after))
    }

    /** 연습 중 출처가 바뀜: 기록 없이 버린다. */
    private fun discard() {
        active = null
        tts.stop()
        resetState(lastRecordId)
    }

    private fun resetState(record: String?) {
        prep = null
        phase = SessionPhase.IDLE
        activePhase = null
        mode = null
        noInMode = null
        arrived = false
        cards = emptyList()
        muted = false // 소리 끄기는 회차가 끝나면 켜짐(결정-49)
        lastOutput = null
        lastSay = null
        pSamples = 0
        sent = false
        today = emptyList()
        dr3Continue = false
        lastRecordId = record
        speakEnd = Double.NEGATIVE_INFINITY
        navUntil = Double.NEGATIVE_INFINITY
        entered = false
        pb1Asked = false
        scnPlayed = false
        scnEnded = false
        elapsed = 0.0
        sim.hold(SimLink.HOLD_FINISH, false)
        publish()
    }

    // ================================================================ 도구

    /** 오늘 과제(session.js `todayTasks`): 못 읽는 신호 때문에 항목이 모두 꺼지는 과제는 뺀다(설계 7.8, T11-14). */
    private fun computeToday(profile: Profile, mode: Int): List<TodayTask> {
        val list = try {
            engine.todayTasks(profile, mode)
        } catch (e: RuntimeException) {
            warn("오늘 과제를 계산하지 못했어요", e)
            return emptyList()
        }
        val off = engine.itemsOffByCaps(signals.caps.value)
        if (off.isEmpty()) return list
        return list.filter { t ->
            val its = engine.taskItems(t.id)
            its.isEmpty() || its.any { it !in off }
        }
    }

    /** 준비 세션의 전달 방식(session.js `itemValue(item, prep)`): 결과에 없으면 X, 계산이 안 되면 O. */
    private fun prepItemValue(item: String, p: PrepSession): Delivery = try {
        engine.resolveItem(item, p, profile.value) ?: Delivery.OFF
    } catch (e: RuntimeException) {
        Delivery.NOW
    }

    private fun preEvent(item: String, data: EventData) = JudgeEvent(item = item, data = data)

    /** 전달 방식 → 막힌 이유('S' 완료 후 · 'X' 안 함). */
    private fun Delivery.blockReason(): BlockReason = if (this == Delivery.AFTER) BlockReason.AFTER else BlockReason.OFF

    /** 시연 패널 상태 줄(session.js `label`). */
    private fun label(): String {
        val m = mode ?: return ""
        val base = "${Mode.ofOrNull(m)?.short ?: "${m}번 모드"} ${noInMode ?: 1}회차"
        return when {
            active == null -> "$base 준비"
            activePhase == ActivePhase.STOPPED -> "$base 중간 정차"
            activePhase == ActivePhase.FINISHING -> "$base 마무리"
            else -> base
        }
    }

    private fun publish() {
        val snap = lastSnap
        _state.value = DrivingState(
            phase = phase,
            activePhase = if (phase == SessionPhase.ACTIVE) activePhase else null,
            snapshot = snap,
            gear = snap?.gear,
            speed = snap?.speed,
            blinker = snap?.blinker,
            prep = prep,
            mode = mode,
            noInMode = noInMode,
            today = today,
            output = lastOutput,
            lastSay = lastSay,
            cards = cards,
            muted = muted,
            arrived = arrived,
            elapsedSec = elapsed,
            dr3Continue = dr3Continue,
            sent = sent,
            gearless = gearless.on,
            lastRecordId = lastRecordId,
            label = label(),
        )
    }

    private fun warn(message: String, e: Throwable? = null) {
        // 단위 테스트(JVM)에는 android.util.Log가 없다 — 로그를 못 남겨도 세션은 이어 간다.
        runCatching { android.util.Log.w(TAG, message, e) }
    }

    private companion object {
        const val TAG = "DrivingSession"

        /** 시나리오가 P를 넣고도 재생 중이면 이만큼(표본) 끝나기를 기다린 뒤 DR-3(session.js `PARK_WAIT`). */
        const val PARK_WAIT = 20

        /** 내비 음성이 끝나고 1초 더 기다리는 엔진 규칙 4(설계 6.5.2, session.js `NAV_AFTER`). */
        const val NAV_AFTER = 1.0

        /** 신호 한 틱(10Hz). */
        const val TICK = 0.1
        const val EPS = 1e-6

        /** session.js `modeNum`: 1~8이 아니면 1. */
        fun modeNum(m: Int): Int = if (m in 1..8) m else 1
    }
}
