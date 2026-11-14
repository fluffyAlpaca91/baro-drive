package com.barodrive.app.feature.course

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.StoreRules
import com.barodrive.app.data.signal.SignalSource
import com.barodrive.app.demo.DemoPanelRepository
import com.barodrive.app.demo.FixedDemoPanel
import com.barodrive.app.feature.coach.CoachLines
import com.barodrive.app.feature.coach.plainVars
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.core.model.Category
import com.barodrive.core.model.ParkingSetup
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.TurnDir
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * CR-1 연습 코스 추천 (프로토타입 course.js, 설계 4.2 CR-1·6.10.6, 결정-63·64·81·85·87).
 * 들어올 때와 모드를 바꿀 때 0.8초 '혼잡도 받는 중' 뒤 목록을 보이고, 코치 한마디(p.course)를 정차 중 말로 한 번 한다.
 * 혼잡도를 못 받으면(신호 지원 '코스 혼잡도' 끔) 가까운 순이고 '덜 막혀요'·한마디가 없다(결정-81).
 * [X 코스로 연습하기]는 준비 세션을 만들고(session.prepare) PR-1로.
 *
 * 시연 패널 '동네'·'혼잡 시드'([DemoPanelRepository])로 받고, 바뀌면 다시 받는다(course.js panel:change).
 */
@HiltViewModel
class CourseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val session: SessionController,
    signals: SignalSource,
    private val voice: VoiceLog,
    private val selection: CourseSelection,
    private val clock: Clock,
    private val panel: DemoPanelRepository = FixedDemoPanel,
) : ViewModel() {
    private val requestedMode: Int? = savedStateHandle.get<Int>(ARG_MODE)
    private val parking: ParkingSetup? = parkingOf(savedStateHandle.get<String>(ARG_PARKING_TYPE), savedStateHandle.get<String>(ARG_PARKING_SIDE))

    private val entry = MutableStateFlow<Int?>(null)
    private val mode = MutableStateFlow<Int?>(null)
    private val loading = MutableStateFlow(true)
    private val pickTick = MutableStateFlow(0)
    private val tripOk: StateFlow<Boolean> = signals.caps.map { it.trip }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, signals.caps.value.trip)
    private var fetchJob: Job? = null

    /** 시연 패널 '동네'·'혼잡 시드'. */
    private val where: StateFlow<Pair<String, Int>> = panel.state.map { it.town to it.congestionSeed }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, panel.state.value.let { it.town to it.congestionSeed })

    val uiState: StateFlow<CourseUiState> = combine(profiles.profile, mode, loading, combine(tripOk, where) { t, w -> t to w }, pickTick) { p, m, isLoading, tw, _ ->
        if (m == null) CourseUiState() else build(p, m, isLoading, tw.first)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CourseUiState())

    init {
        viewModelScope.launch {
            val p = profiles.current()
            val m = CourseRules.entryMode(requestedMode, p)
            entry.value = m
            mode.value = m
            fetch(speak = true)
        }
        // 신호 지원 '코스 혼잡도'·동네·혼잡 시드가 바뀌면 다시 받는다 — 고른 코스도 비우고 '덜 막혀요'부터(course.js panel:change).
        viewModelScope.launch {
            combine(tripOk, where) { t, w -> t to w }.distinctUntilChanged().drop(1).collect {
                selection.clear()
                fetch(speak = true)
            }
        }
    }

    private fun build(p: Profile, m: Int, isLoading: Boolean, trip: Boolean): CourseUiState {
        val base = CourseUiState(
            loaded = true, mode = m, entryMode = entry.value ?: m, modes = CourseRules.visibleModes(entry.value),
            locked = CourseRules.lockedModes(p), coach = p.coach, tripOk = trip,
        )
        if (m == AUTONOMOUS) return base.copy(body = CourseBodyState.AUTONOMOUS)
        if (isLoading) return base.copy(body = CourseBodyState.LOADING)
        val res = rank(m, p, trip)
        if (res.empty || res.list.isEmpty()) return base.copy(body = CourseBodyState.EMPTY)
        val line = res.line?.takeIf { res.bestId != null }?.let { CoachLines.render(it, p.coach, p.honorific) }?.takeIf { it.isNotEmpty() }
        val today = LocalDate.now(clock)
        return base.copy(
            body = CourseBodyState.LIST,
            coachLine = line,
            cards = CourseUiState.cards(res, selection.get(m)),
            licenseDone = res.list.count { it.ran },
            licenseTotal = res.list.count { it.checkedAt != null }.takeIf { it > 0 } ?: res.list.size,
            expired = m == LICENSE && CourseRules.licenseExpired(p, today),
        )
    }

    /** 추천 순위(core.js `rankCourses`) — 시연 패널 동네·혼잡 시드로. */
    private fun rank(m: Int, p: Profile, trip: Boolean) = CourseRules.rank(m, p, town = where.value.first, congestionSeed = where.value.second, tripOk = trip)

    /** 0.8초 '혼잡도 받는 중' 뒤 목록(course.js `fetch`). 자율은 받지 않는다. */
    private fun fetch(speak: Boolean) {
        fetchJob?.cancel()
        if (mode.value == AUTONOMOUS) {
            loading.value = false
            return
        }
        loading.value = true
        fetchJob = viewModelScope.launch {
            delay(LOAD_MS)
            loading.value = false
            if (speak) say()
        }
    }

    /** 목록이 나오면 코치 한마디(p.course | p.course.none)를 정차 중 말로 한 번(course.js `say`). */
    private suspend fun say() {
        val m = mode.value ?: return
        val res = rank(m, profiles.current(), tripOk.value)
        val line = res.line ?: return
        voice.sayLine(line.key, line.plainVars(), category = Category.INFO)
    }

    /** 모드 칩. 6·8로 들어온 게 아니면 그 시작 화면으로 보내야 하므로 부르는 쪽이 [CourseRules.ENTRY_MODES]를 먼저 본다. */
    fun selectMode(m: Int) {
        if (m == mode.value || m in uiState.value.locked) return
        mode.value = m
        fetch(speak = true)
    }

    /** 코스 카드를 고름(모드마다 기억). */
    fun pick(id: String) {
        val m = mode.value ?: return
        selection.set(m, id)
        pickTick.value++
    }

    /** 별: 내 코스 저장/빼기. [onDone]에 저장했나(참)·뺐나(거짓). */
    fun toggleStar(id: String, onDone: (Boolean) -> Unit) {
        val m = mode.value ?: return
        viewModelScope.launch {
            val item = rank(m, profiles.current(), tripOk.value).list.firstOrNull { it.id == id } ?: return@launch
            var added = false
            profiles.update { p ->
                val (next, a) = CourseRules.toggleStar(p, item, m, StoreRules.isoLocal(ZonedDateTime.now(clock)))
                added = a
                next
            }
            onDone(added)
        }
    }

    /** [X 코스로 연습하기]: 고른 코스로 준비 세션을 만들고 [onReady](PR-1로). */
    fun start(onReady: () -> Unit) = startWith(withCourse = true, onReady = onReady)

    /** [코스 없이 연습하기](결정-85). */
    fun startWithoutCourse(onReady: () -> Unit) = startWith(withCourse = false, onReady = onReady)

    private fun startWith(withCourse: Boolean, onReady: () -> Unit) {
        val m = mode.value ?: return
        viewModelScope.launch {
            val p = profiles.current()
            val item = if (withCourse) {
                val res = rank(m, p, tripOk.value)
                val sel = uiState.value.selected?.id
                res.list.firstOrNull { it.id == sel } ?: return@launch
            } else {
                null
            }
            val extra: ((PrepSession) -> PrepSession)? = if (m == entry.value && parking != null) ({ it.copy(parking = parking) }) else null
            session.prepare(CourseRules.prepFor(m, item, p, extra))
            onReady()
        }
    }

    companion object {
        /** '혼잡도 받는 중'(course.js `LOAD_MS`). */
        const val LOAD_MS = 800L
        const val ARG_MODE = "mode"
        const val ARG_PARKING_TYPE = "parkingType"
        const val ARG_PARKING_SIDE = "parkingSide"
        private const val AUTONOMOUS = 5
        private const val LICENSE = 8

        /** 경로의 주차 종류·방향 글자('parallel'·'R') → 준비 세션 값. 없으면 null. */
        fun parkingOf(type: String?, side: String?): ParkingSetup? {
            val t = ParkingType.entries.firstOrNull { it.code == type } ?: return null
            return ParkingSetup(type = t, side = TurnDir.fromCode(side) ?: TurnDir.RIGHT)
        }
    }
}
