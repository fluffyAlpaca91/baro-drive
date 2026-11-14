package com.barodrive.app.data.repo

import com.barodrive.core.model.AppState
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import java.time.Clock
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 메모리 안 앱 상태 저장소 — [ProfileRepository]와 [RecordRepository]를 함께 (프로토타입 app/store.js를 파일 없이).
 * 미리보기·화면 테스트용 가짜이자 기기 저장 구현이 붙기 전 기본 구현이다. 규칙은 [StoreRules] 그대로다.
 * 기록은 프로필의 `sessions`에 둔다(프로토타입과 같은 모양).
 *
 * @param initial 시작 상태(데모 상태·미리보기 값). 기본은 [StoreRules.defaults].
 * @param clock 새 기록 id('sess-YYYYMMDD-HHMM')와 새 프로필 시각.
 */
class InMemoryBaroStore(
    initial: AppState? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ProfileRepository, RecordRepository {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(StoreRules.normalize(initial ?: StoreRules.defaults(now())) ?: StoreRules.defaults(now()))

    /** 지금 상태(테스트에서 바로 읽기). */
    val snapshot: StateFlow<AppState> = _state.asStateFlow()

    override val state: Flow<AppState> = _state

    override val profile: Flow<Profile> = _state.map { it.activeProfile }.distinctUntilChanged()

    override val records: Flow<List<SessionRecord>> = profile.map { it.sessions }.distinctUntilChanged()

    override fun observe(id: String): Flow<SessionRecord?> = records.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    override suspend fun current(): Profile = _state.value.activeProfile

    override suspend fun update(transform: (Profile) -> Profile): Profile = mutate { st ->
        val before = st.activeProfile
        // 기록은 RecordRepository로만 바꾼다 — 돌려준 프로필의 sessions는 버린다.
        val after = transform(before).copy(id = before.id, sessions = before.sessions)
        st.withActive(after) to after
    }

    override suspend fun updateState(transform: (AppState) -> AppState): AppState = mutate { st ->
        val next = StoreRules.normalize(transform(st), now()) ?: st
        next to next
    }

    override suspend fun setActiveProfile(id: String): Profile = mutate { st ->
        require(id.isNotEmpty()) { "store.setProfile: 프로필 id를 넘겨요" }
        val p = st.profiles[id] ?: Profile.blank(id, createdAt = StoreRules.isoLocal(now()))
        st.copy(activeProfileId = id, profiles = st.profiles + (id to p)) to p
    }

    override suspend fun reset(): AppState = mutate {
        val d = StoreRules.defaults(now())
        d to d
    }

    override suspend fun replace(state: AppState): AppState = mutate {
        val next = requireNotNull(StoreRules.normalize(state, now())) { "store.replace: v1 모양의 상태가 아니에요" }
        next to next
    }

    override suspend fun get(id: String): SessionRecord? = _state.value.activeProfile.sessions.firstOrNull { it.id == id }

    override suspend fun list(mode: Int?): List<SessionRecord> =
        _state.value.activeProfile.sessions.filter { mode == null || it.mode == mode }

    override suspend fun add(record: SessionRecord): SessionRecord = mutate { st ->
        val (p, saved) = StoreRules.addRecord(st.activeProfile, record, now())
        st.withActive(p) to saved
    }

    override suspend fun update(id: String, transform: (SessionRecord) -> SessionRecord): Boolean = mutate { st ->
        val p = StoreRules.updateRecord(st.activeProfile, id, transform)
        if (p == null) st to false else st.withActive(p) to true
    }

    override suspend fun remove(id: String): Boolean = mutate { st ->
        val p = StoreRules.removeRecord(st.activeProfile, id)
        if (p == null) st to false else st.withActive(p) to true
    }

    override suspend fun clear(): Int = mutate { st ->
        val n = st.activeProfile.sessions.size
        st.withActive(StoreRules.clearRecords(st.activeProfile)) to n
    }

    private suspend fun <R> mutate(block: (AppState) -> Pair<AppState, R>): R = mutex.withLock {
        val (next, result) = block(_state.value)
        _state.value = next
        result
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    private fun AppState.withActive(p: Profile): AppState = copy(profiles = profiles + (activeProfileId to p))
}
