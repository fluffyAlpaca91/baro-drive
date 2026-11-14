package com.barodrive.app.demo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.barodrive.app.di.ApplicationScope
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.DemoPanelState
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * 시연 패널 설정 (설계 8.1 `baro.panel.v1`·9장, 프로토타입 shell/panel.js `state`·`set`·`reset`). 앱 저장(`baro.app.v1`)과 따로 둔다 —
 * [처음 실행으로 되돌리기]는 이것을 지우지 않고, [패널 설정 초기화]만 처음 값으로 돌린다(가족 휴대폰 카드도 지움).
 *
 * 앱 화면이 읽는 값(프로토타입 `BaroShell.panel.state`): 동네·혼잡 시드·신호 지원 trip(코스 추천 CR-1) · 다른 앱이 앞에.
 * 네트워크는 여기에 남기고 공유(ui-more `ShareCenter.setOnline`)에 넣는다 — 가족 휴대폰 카드도 `ShareCenter`가 들고 있다.
 * 시연 도구를 뺀 빌드에서는 처음 값 그대로다.
 */
interface DemoPanelRepository {
    /** 지금 패널 값. 처음 읽기 전에는 처음 값. */
    val state: StateFlow<PanelSettings>

    /** 고쳐 저장하고 고친 값을 돌려준다(panel.js `set`). */
    suspend fun update(transform: (PanelSettings) -> PanelSettings): PanelSettings

    /** [패널 설정 초기화](panel.js `reset`): 처음 값으로. */
    suspend fun reset(): PanelSettings
}

/**
 * 패널 설정 전체. [demo] = 시뮬레이터·앱이 쓰는 값(:core:model [DemoPanelState]), 나머지는 패널 화면 값
 * (목업 원안 보기 · 말한 내용 거르기 · 마지막에 적용한 데모 상태 · 묶음 펼침).
 *
 * 화면이 읽는 값: [town]·[congestionSeed](코스 추천 CR-1 · PR-1 코스 카드 · 내 코스 ST-3 · 내비에서 불러오기 CR-2),
 * [mockupHero](주행 화면 DR-1 회전 히어로를 목업 s04 모양으로, 결정-92), [tripOk](신호 지원 '코스 혼잡도').
 */
@Serializable
data class PanelSettings(
    val demo: DemoPanelState = DemoPanelState(),
    /** '보기' [목업 원안 보기](panel.js `mockupHero`): DR-1 회전 히어로를 목업 s04 모양으로. */
    val mockupHero: Boolean = false,
    val logFilter: LogFilter = LogFilter(),
    /** 마지막에 [적용]한 데모 상태 id(새로고침 뒤에도 그것을 고른 채로, T11-10). */
    val demoPreset: String? = null,
    /** 묶음 id → 펼침(없으면 그 묶음 처음 값). */
    val open: Map<String, Boolean> = emptyMap(),
) {
    /** 패널 '동네'('home' 우리 동네 · 'moved' 이사 간 동네). */
    val town: String get() = demo.town

    /** 패널 혼잡 시드(1 이하 = 코스 기본값 그대로 = 목업 혼잡). */
    val congestionSeed: Int get() = demo.congestionSeed

    /** 코스 혼잡도를 받나(신호 지원 '코스 혼잡도'). */
    val tripOk: Boolean get() = demo.caps.trip
}

/** '말한 내용' 거르기(panel.js `logFilter`): 코치 말 · 내비 말 · 막힌 말. */
@Serializable
data class LogFilter(val coach: Boolean = true, val navi: Boolean = true, val blocked: Boolean = true)

/**
 * 기기 설정 파일(DataStore `settings`)의 한 키(`baro.panel.v1`)에 JSON으로 둔다. 앱 시작 때 한 번 읽고, 고칠 때마다 메모리를 먼저 바꾼 뒤 쓴다.
 * 읽을 수 없으면 처음 값으로 시작한다(panel.js `load`).
 */
@Singleton
class DataStoreDemoPanelRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope scope: CoroutineScope,
) : DemoPanelRepository {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(PanelSettings())
    override val state: StateFlow<PanelSettings> = _state.asStateFlow()
    private var loaded = false

    init {
        scope.launch { mutex.withLock { loadLocked() } }
    }

    override suspend fun update(transform: (PanelSettings) -> PanelSettings): PanelSettings = mutex.withLock {
        loadLocked()
        val next = transform(_state.value)
        if (next != _state.value) {
            _state.value = next
            write(next)
        }
        next
    }

    override suspend fun reset(): PanelSettings = mutex.withLock {
        loaded = true
        val d = PanelSettings()
        _state.value = d
        runCatching { dataStore.edit { it.remove(KEY) } }
        d
    }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        val text = try {
            dataStore.data.first()[KEY]
        } catch (e: IOException) {
            null
        }
        val saved = text?.let {
            try {
                BaroJson.decodeFromString(PanelSettings.serializer(), it)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (saved != null) _state.value = saved
    }

    private suspend fun write(s: PanelSettings) {
        val text = BaroJson.encodeToString(PanelSettings.serializer(), s)
        try {
            dataStore.edit { it[KEY] = text }
        } catch (e: IOException) {
            // 쓰지 못해도 메모리 값은 그대로 쓴다(panel.js `save`: 경고만).
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("baro.panel.v1")
    }
}

/** 처음 값 그대로인 패널(시험·미리보기용 — 바꾸지 않는다). 화면 ViewModel 생성자의 기본값으로 쓴다. */
object FixedDemoPanel : DemoPanelRepository {
    override val state: StateFlow<PanelSettings> = MutableStateFlow(PanelSettings())

    override suspend fun update(transform: (PanelSettings) -> PanelSettings): PanelSettings = state.value

    override suspend fun reset(): PanelSettings = state.value
}
