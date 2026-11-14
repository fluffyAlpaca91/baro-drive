package com.barodrive.app.feature.predrive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.signal.SignalSource
import com.barodrive.app.demo.DemoNaviPicker
import com.barodrive.app.demo.DemoPanelRepository
import com.barodrive.app.demo.FixedDemoPanel
import com.barodrive.app.feature.coach.CoachLines
import com.barodrive.app.feature.weather.WeatherRules
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SendResult
import com.barodrive.app.session.api.SessionController
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.PassengerAnswer
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SignalCaps
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * PR-1 연습 시작 전 (프로토타입 predrive.js + demo.js 신호 확인 줄·조수석 질문, 설계 4.2 PR-1·3.2·7.8). 날씨 줄은 feature/weather의
 * PredriveWeatherCardRoute(자기 ViewModel)가 그 자리에 그린다.
 * 어떤 연습인지는 세션 관리자의 준비 세션에 있다(CR-1·FB-2·LC-1이 만든 뒤 온다). 들어오면 오늘 과제를 코치가 읽고(p.tasks),
 * 조수석이 앉아 있으면 L1, 사이드브레이크를 못 읽으면 PB1을 묻는다(SessionController.enterPredrive — 준비 세션마다 한 번).
 * 기어가 P를 벗어나면 세션이 시작되고 주행 덮개가 덮는다.
 */
@HiltViewModel
class PredriveViewModel @Inject constructor(
    private val session: SessionController,
    profiles: ProfileRepository,
    signals: SignalSource,
    variants: Set<@JvmSuppressWildcards PredriveVariant>,
    panel: DemoPanelRepository = FixedDemoPanel,
    private val naviPicker: DemoNaviPicker = DemoNaviPicker(),
) : ViewModel() {
    private val byMode: Map<Int, PredriveVariant> = variants.associateBy { it.mode }
    private val lastToast = MutableStateFlow<String?>(null)

    /** 시연 패널 동네·혼잡 시드(코스 카드 혼잡). */
    private val where = panel.state.map { it.town to it.congestionSeed }.distinctUntilChanged()

    val uiState: StateFlow<PredriveUiState> = combine(session.state, profiles.profile, signals.caps, lastToast, where) { s, p, caps, toast, w ->
        build(s, p, caps, toast, w)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PredriveUiState())

    init {
        viewModelScope.launch {
            val st = session.state.value
            val prep = st.prep ?: return@launch
            if (st.active) return@launch
            val v = byMode[prep.mode]
            session.enterPredrive(readTasks = v?.tasks != false, onEnter = v?.let { { it.onEnter(prep) } })
        }
    }

    private fun build(s: DrivingState, p: Profile, caps: SignalCaps, toast: String?, where: Pair<String, Int> = "home" to 1): PredriveUiState {
        val prep = s.prep ?: return PredriveUiState(loaded = true, noPrep = true, stage = p.progress.currentStage.coerceIn(1, 4))
        val v = byMode[prep.mode]
        val snap = s.snapshot
        val l1 = session.itemValue("L1")
        val fixed = if (v?.passenger == false) null else v?.passengerCard(prep, snap)
        val showPassenger = v?.passenger != false
        val passenger = PredriveRules.passenger(snap, prep, l1)
        val aboardSay = (passenger == PassengerStatus.ABOARD_PENDING || passenger == PassengerStatus.ABOARD_SAID)
        val l1Text = { CoachLines.render("l1", p.coach, emptyMap(), p.honorific).takeIf { it.isNotEmpty() } }
        val hasRoute = prep.courseId != null || prep.placeId != null || prep.navi != null
        val primary = v?.primary(prep)?.let { PredrivePrimary.Variant(it) }
            ?: when {
                s.sent -> PredrivePrimary.Sent(toast, hasRoute)
                hasRoute -> PredrivePrimary.Send
                else -> PredrivePrimary.Pick
            }
        return PredriveUiState(
            loaded = true,
            stage = p.progress.currentStage.coerceIn(1, 4),
            prep = prep,
            mode = prep.mode,
            subtitle = v?.subtitle(prep) ?: PredriveRules.subtitle(prep),
            coach = p.coach,
            profile = p,
            variantTop = v?.hasTopCard == true,
            showTasks = v?.tasks != false,
            tasks = s.today,
            belt = PredriveRules.belt(snap),
            handbrake = PredriveRules.handbrake(snap, prep, session.itemValue("PB1")),
            mirror = prep.checks.mirror,
            showPassenger = showPassenger,
            fixedPassenger = fixed,
            fixedQuote = if (fixed?.l1Quote == true && snap?.passenger == true && l1 == Delivery.NOW) l1Text() else null,
            passenger = passenger,
            l1Quote = if (aboardSay) l1Text() else null,
            l2 = PredriveRules.l2(snap, prep.mode),
            passengerAsk = showPassenger && fixed == null && !caps.passenger,
            passengerAnswer = prep.passengerAnswer,
            l1Now = l1 == Delivery.NOW,
            weatherOrder = if (prep.mode in WeatherRules.MODES) WeatherRules.orderFor(snap) else null,
            showCourse = v?.course != false,
            course = PredriveRules.course(prep, p, caps.trip, where.first, where.second),
            courseNote = v?.courseNote(prep),
            signals = PredriveRules.signals(prep.mode, caps),
            block = PredriveRules.startBlock(prep.mode, caps),
            primary = primary,
        )
    }

    /** [다시 듣기]: 코치가 오늘 과제를 다시 읽는다('p.tasks'). */
    fun readTasks() {
        viewModelScope.launch { session.readTasks() }
    }

    /** [풀었어요](PB1 확인, 결정-84). */
    fun confirmHandbrake() {
        viewModelScope.launch { session.confirmHandbrake() }
    }

    /** 사이드미러 체크(A5, 결정-48). */
    fun setMirror(on: Boolean) {
        viewModelScope.launch { session.setMirror(on) }
    }

    /** '조수석에 누가 탔나요?' 답(demo.js): [탔어요] → L1 회차에 한 번 · [혼자예요] · [다시 고르기](null). */
    fun answerPassenger(answer: PassengerAnswer?) {
        viewModelScope.launch {
            session.updatePrep { it.copy(passengerAnswer = answer) }
            if (answer == PassengerAnswer.ABOARD) session.passengerAboard()
        }
    }

    /**
     * [내비로 보내고 출발](session.send). [onDone]에 결과(보내지 못했으면 null). 진짜 차에서 좌표 없는 코스 등으로 보내지 않았으면
     * [SendResult.sent]가 거짓이고 글은 그 까닭이다 — 그때는 '보냄' 상태가 되지 않는다.
     */
    fun send(onDone: (SendResult?) -> Unit) {
        viewModelScope.launch {
            val r = session.send()
            if (r != null && r.sent) lastToast.value = r.toast
            onDone(r)
        }
    }

    /** [내비에서 목적지 고르기]: 시연 빌드는 시연 패널의 목적지 고르기를 연다(에뮬레이터 내비는 손으로 몰 수 없다). 열었으면 참. */
    fun pickInNavi(): Boolean = naviPicker.open()

    /** 변형 하단 버튼: 할 일을 한 뒤 [go]로 그 경로. */
    fun runVariant(primary: VariantPrimary, go: (Any) -> Unit) {
        val prep = session.state.value.prep ?: return
        viewModelScope.launch {
            primary.onClick?.invoke(prep)
            primary.route?.let(go)
        }
    }

    /** 그 모드 PR-1 변형(맨 위 카드를 그릴 때). 없으면 null. */
    fun variant(mode: Int): PredriveVariant? = byMode[mode]
}
