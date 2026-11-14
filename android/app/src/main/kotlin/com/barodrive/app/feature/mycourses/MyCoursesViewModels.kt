package com.barodrive.app.feature.mycourses

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.navi.NaviPlaceSource
import com.barodrive.app.data.navi.NaviPlaces
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.StoreRules
import com.barodrive.app.demo.DemoPanelRepository
import com.barodrive.app.demo.FixedDemoPanel
import com.barodrive.app.feature.course.CourseRules
import com.barodrive.core.model.PlaceList
import com.barodrive.core.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 시연 패널 '동네'를 못 읽을 때의 동네(우리 동네). */
internal const val TOWN_NOW = "home"

/** ST-3 내 코스 화면 값. */
data class MyCoursesUiState(val loaded: Boolean = false, val groups: List<MyCourseGroup> = emptyList())

/**
 * ST-3 내 코스 (프로토타입 mycourses.js ST-3, 설계 4.2 ST-3·8.1): 모드별 묶음, 줄마다 [모드 바꾸기](시트) [지우기](확인 대화상자).
 * 비어 있으면 빈 상태(새 프로필에 예시 내 코스는 넣지 않는다 — 결정 T1c-Q1).
 */
@HiltViewModel
class MyCoursesViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    panel: DemoPanelRepository = FixedDemoPanel,
) : ViewModel() {
    /** 지금 동네(시연 패널 '동네') — 다른 동네의 줄에 동네 이름을 붙인다. */
    val uiState: StateFlow<MyCoursesUiState> = combine(profiles.profile, panel.state.map { it.town }.distinctUntilChanged()) { p, town ->
        MyCoursesUiState(loaded = true, groups = MyCoursesRules.groups(p, town))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MyCoursesUiState())

    /** [바꾸기]. [onDone]에 까닭(성공이면 null, 같은 모드면 SAME). */
    fun setMode(id: String, mode: Int, onDone: (MyCourseError?) -> Unit) {
        viewModelScope.launch {
            var err: MyCourseError? = null
            profiles.update { p -> MyCoursesRules.setMode(p, id, mode).also { err = it.second }.first }
            onDone(err)
        }
    }

    /** [지우기] 확인. [onDone]에 지웠나. */
    fun remove(id: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            var ok = false
            profiles.update { p -> MyCoursesRules.remove(p, id).also { ok = it.second }.first }
            onDone(ok)
        }
    }
}

/**
 * CR-2 장소 한 줄: 팀 장소(시뮬레이터 — 프로토타입 `places`) 또는 차 내비 장소(진짜 차). [id]는 고르고 저장할 때 쓰는 열쇠
 * (팀 장소 id · 내비 장소 [com.barodrive.core.model.NaviPlace.key]).
 */
data class PlaceRowUi(
    val id: String,
    val name: String,
    val addr: String = "",
    /** 거리 km(팀 장소만 — 차 내비 장소는 모른다). */
    val km: Double? = null,
    /** 지금 있는 곳(팀 장소 '집') — 코스로 만들 수 없다. */
    val here: Boolean = false,
    val savedModes: List<Int> = emptyList(),
    val open: Boolean = false,
)

/** CR-2 내비에서 불러오기 화면 값. */
data class NaviImportUiState(
    val loaded: Boolean = false,
    val recent: Boolean = false,
    val rows: List<PlaceRowUi> = emptyList(),
    /** 가져오기 모드 칩에서 고른 모드. */
    val mode: Int = 1,
    /** 진짜 차인데 차 내비에서 장소를 받지 못함(내비에 붙지 않음 · 답 없음). */
    val naviFailed: Boolean = false,
)

/**
 * CR-2 내비에서 불러오기 (프로토타입 mycourses.js CR-2, 설계 4.2 CR-2 — 시트 대신 정차 화면, 결정사항 E T9-1).
 * 탭 2개(즐겨찾기 · 최근 목적지). 줄마다 [가져오기] → '어느 연습에 쓸까요?' 모드 칩 + [저장]. '집'(지금 위치)은 코스가 될 수 없다.
 * 장소는 [NaviPlaceSource]가 정한다: 시뮬레이터면 팀 장소(프로토타입 그대로), 진짜 차면 차 내비(NaviHelper)의 즐겨찾기·최근 목적지를
 * 탭마다 받아(`getBookmarkInfo`·`getRecentDestinationInfo`) 좌표째 내 코스로 저장한다.
 */
@HiltViewModel
class NaviImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val clock: Clock,
    private val panel: DemoPanelRepository = FixedDemoPanel,
    private val naviPlaces: NaviPlaceSource = NaviPlaceSource.Simulator,
) : ViewModel() {
    /** 지금 동네(시연 패널 '동네') — 가져온 장소 코스는 이 동네 것으로 저장한다. */
    private val town: String get() = panel.state.value.town

    private val recent = MutableStateFlow(savedStateHandle.get<String>(ARG_TAB) == NaviImport.TAB_RECENT)
    private val open = MutableStateFlow<String?>(null)
    private val mode = MutableStateFlow<Int?>(savedStateHandle.get<Int>(ARG_MODE)?.takeIf { it in MyCoursesRules.IMPORT_MODES })

    /** 탭(최근 목적지인가)마다 받은 장소. 아직 받는 중인 탭은 없다. */
    private val loaded = MutableStateFlow<Map<Boolean, NaviPlaces>>(emptyMap())

    private val where = combine(panel.state.map { it.town }.distinctUntilChanged(), loaded) { tw, l -> tw to l }

    val uiState: StateFlow<NaviImportUiState> = combine(profiles.profile, recent, open, mode, where) { p, r, o, m, (tw, l) ->
        val picked = m ?: CourseRules.currentStage(p)
        when (val got = l[r]) {
            null -> NaviImportUiState(loaded = false, recent = r, mode = picked)
            NaviPlaces.Unavailable -> NaviImportUiState(loaded = true, recent = r, mode = picked, naviFailed = true)
            else -> NaviImportUiState(loaded = true, recent = r, rows = rows(got, r, p, tw, o), mode = picked)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NaviImportUiState())

    init {
        load(recent.value)
    }

    /** 줄: 팀 장소(시뮬레이터) 또는 차 내비 장소(진짜 차, 거리는 모름). */
    private fun rows(got: NaviPlaces, recentTab: Boolean, p: Profile, tw: String, openId: String?): List<PlaceRowUi> = when (got) {
        is NaviPlaces.Car -> got.places.map { pl ->
            PlaceRowUi(pl.key, pl.name, addr = pl.address, savedModes = MyCoursesRules.savedNaviModes(p, pl.key, tw), open = pl.key == openId)
        }
        else -> MyCoursesRules.places(recentTab).map { pl ->
            PlaceRowUi(
                pl.id, pl.name, addr = pl.addr, km = pl.km.takeIf { it > 0 }, here = pl.here,
                savedModes = MyCoursesRules.savedModes(p, pl.id, tw), open = pl.id == openId,
            )
        }
    }

    /** 그 탭의 장소를 받는다(한 번 — 다시 들어오면 새로 받는다). */
    private fun load(recentTab: Boolean) {
        if (recentTab in loaded.value) return
        viewModelScope.launch {
            val got = naviPlaces.load(if (recentTab) PlaceList.RECENT else PlaceList.BOOKMARK)
            loaded.update { it + (recentTab to got) }
        }
    }

    fun selectTab(recentTab: Boolean) {
        if (recent.value == recentTab) return
        recent.value = recentTab
        open.value = null
        load(recentTab)
    }

    fun toggle(placeId: String) {
        open.value = if (open.value == placeId) null else placeId
    }

    fun pickMode(m: Int) {
        mode.value = m
    }

    /** [저장]: 고른 모드의 내 코스로([placeId] = 줄 [PlaceRowUi.id]). [onDone]에 (모드, 까닭 — 성공이면 null). */
    fun save(placeId: String, onDone: (Int, MyCourseError?) -> Unit) {
        val m = uiState.value.mode
        val recentTab = recent.value
        val got = loaded.value[recentTab]
        viewModelScope.launch {
            var err: MyCourseError? = null
            val savedAt = StoreRules.isoLocal(ZonedDateTime.now(clock))
            profiles.update { p ->
                val (next, _, e) = if (got is NaviPlaces.Car) {
                    val place = got.places.firstOrNull { it.key == placeId }
                    if (place == null) {
                        Triple(p, null, MyCourseError.PLACE)
                    } else {
                        MyCoursesRules.addNaviPlace(p, place, if (recentTab) PlaceList.RECENT else PlaceList.BOOKMARK, m, town, savedAt)
                    }
                } else {
                    MyCoursesRules.addPlace(p, placeId, m, town, savedAt)
                }
                err = e
                next
            }
            if (err == null) open.value = null
            onDone(m, err)
        }
    }

    companion object {
        const val ARG_MODE = "mode"
        const val ARG_TAB = "tab"
    }
}
