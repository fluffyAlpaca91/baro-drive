package com.barodrive.app.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.barodrive.app.data.local.SessionRecordDao
import com.barodrive.app.data.local.SessionRecordEntity
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.data.repo.StoreRules
import com.barodrive.app.di.AppStateStore
import com.barodrive.core.model.AppState
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Profile
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.SessionRecord
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/**
 * 기기 저장 앱 상태 (프로토타입 app/store.js의 localStorage `baro.app.v1` 자리, 설계 8.1).
 * - 프로필·진행·설정·ui 기억 = DataStore 한 키에 JSON(`sessions`는 뺀 [AppState]).
 * - 회차 기록 = Room `session_record`(찾는 칸 + 기록 JSON). 프로필마다 최신이 앞, 최대 30개.
 * 처음 읽을 때 둘을 합쳐 메모리에 두고, 고칠 때마다 메모리를 먼저 바꾼 뒤 바뀐 쪽만 기기에 쓴다. 규칙은 [StoreRules]
 * (메모리 구현 [com.barodrive.app.data.repo.InMemoryBaroStore]와 같다). 읽을 수 없는 파일·기록은 버리고 처음 상태로 시작한다.
 */
@Singleton
class DeviceBaroStore @Inject constructor(
    @AppStateStore private val dataStore: DataStore<Preferences>,
    private val dao: SessionRecordDao,
    private val clock: Clock,
) : ProfileRepository, RecordRepository {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AppState?>(null)

    override val state: Flow<AppState> = flow {
        if (_state.value == null) mutex.withLock { loadLocked() }
        emitAll(_state.filterNotNull())
    }

    override val profile: Flow<Profile> = state.map { it.activeProfile }.distinctUntilChanged()

    override val records: Flow<List<SessionRecord>> = profile.map { it.sessions }.distinctUntilChanged()

    override fun observe(id: String): Flow<SessionRecord?> = records.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    override suspend fun current(): Profile = read().activeProfile

    override suspend fun update(transform: (Profile) -> Profile): Profile = mutate { st ->
        val before = st.activeProfile
        val after = transform(before).copy(id = before.id, sessions = before.sessions)
        val next = st.withActive(after)
        writeProfiles(next)
        next to after
    }

    override suspend fun updateState(transform: (AppState) -> AppState): AppState = mutate { st ->
        val next = withIds(StoreRules.normalize(transform(st), now()) ?: st)
        // 프로필 처음으로·지우기처럼 기록까지 바뀐 프로필은 그 기록을 통째로 다시 쓴다.
        for (id in st.profiles.keys + next.profiles.keys) {
            val was = st.profiles[id]?.sessions.orEmpty()
            val after = next.profiles[id]?.sessions.orEmpty()
            if (was != after) persist("기록 다시 쓰기") { dao.replaceProfile(id, after.map { entity(id, it) }) }
        }
        writeProfiles(next)
        next to next
    }

    override suspend fun setActiveProfile(id: String): Profile = mutate { st ->
        require(id.isNotEmpty()) { "store.setProfile: 프로필 id를 넘겨요" }
        val p = st.profiles[id] ?: Profile.blank(id, createdAt = StoreRules.isoLocal(now()))
        val next = st.copy(activeProfileId = id, profiles = st.profiles + (id to p))
        writeProfiles(next)
        next to p
    }

    override suspend fun reset(): AppState = mutex.withLock {
        persist("상태 지우기") { dataStore.edit { it.remove(KEY) } }
        persist("기록 지우기") { dao.deleteAll() }
        val d = StoreRules.defaults(now())
        _state.value = d
        d
    }

    override suspend fun replace(state: AppState): AppState = mutex.withLock {
        val next = withIds(requireNotNull(StoreRules.normalize(state, now())) { "store.replace: v1 모양의 상태가 아니에요" })
        persist("기록 바꾸기") {
            dao.deleteAll()
            next.profiles.forEach { (id, p) -> dao.replaceProfile(id, p.sessions.map { entity(id, it) }) }
        }
        writeProfiles(next)
        _state.value = next
        next
    }

    override suspend fun get(id: String): SessionRecord? = read().activeProfile.sessions.firstOrNull { it.id == id }

    override suspend fun list(mode: Int?): List<SessionRecord> = read().activeProfile.sessions.filter { mode == null || it.mode == mode }

    override suspend fun add(record: SessionRecord): SessionRecord = mutate { st ->
        val pid = st.activeProfileId
        val before = st.activeProfile
        val (p, saved) = StoreRules.addRecord(before, record, now())
        val gone = before.sessions.mapNotNull { it.id }.toSet() - p.sessions.mapNotNull { it.id }.toSet()
        persist("기록 저장") {
            dao.insert(entity(pid, saved))
            if (gone.isNotEmpty()) dao.delete(pid, gone.toList())
        }
        val next = st.withActive(p)
        if (p.shareQueue != before.shareQueue) writeProfiles(next)
        next to saved
    }

    override suspend fun update(id: String, transform: (SessionRecord) -> SessionRecord): Boolean = mutate { st ->
        val p = StoreRules.updateRecord(st.activeProfile, id, transform) ?: return@mutate st to false
        val rec = p.sessions.first { it.id == id }
        persist("기록 고치기") {
            val e = entity(st.activeProfileId, rec)
            dao.update(e.profileId, id, e.mode, e.stage, e.noInMode, e.kind, e.startedAt, e.simSec, e.arrived, e.qualified, e.json)
        }
        st.withActive(p) to true
    }

    override suspend fun remove(id: String): Boolean = mutate { st ->
        val p = StoreRules.removeRecord(st.activeProfile, id) ?: return@mutate st to false
        persist("기록 지우기") { dao.delete(st.activeProfileId, listOf(id)) }
        val next = st.withActive(p)
        writeProfiles(next)
        next to true
    }

    override suspend fun clear(): Int = mutate { st ->
        val before = st.activeProfile
        persist("기록 모두 지우기") { dao.deleteProfile(st.activeProfileId) }
        val next = st.withActive(StoreRules.clearRecords(before))
        writeProfiles(next)
        next to before.sessions.size
    }

    // ================================================================ 읽기·쓰기

    private suspend fun read(): AppState = _state.value ?: mutex.withLock { loadLocked() }

    private suspend fun <R> mutate(block: suspend (AppState) -> Pair<AppState, R>): R = mutex.withLock {
        val (next, result) = block(loadLocked())
        _state.value = next
        result
    }

    /** mutex 안에서 부른다. 이미 읽었으면 그 값. */
    private suspend fun loadLocked(): AppState {
        _state.value?.let { return it }
        val prefs = try {
            dataStore.data.first()
        } catch (e: java.io.IOException) {
            warn("${StoreRules.KEY}를 읽지 못해 처음 상태로 시작해요", e)
            emptyPreferences()
        }
        val saved = prefs[KEY]?.let { text ->
            try {
                StoreRules.normalize(BaroJson.decodeFromString(AppState.serializer(), text), now())
                    .also { if (it == null) warn("${StoreRules.KEY} 모양이 v1이 아니어서 처음 상태로 시작해요") }
            } catch (e: SerializationException) {
                warn("${StoreRules.KEY}를 읽지 못해 처음 상태로 시작해요", e)
                null
            } catch (e: IllegalArgumentException) {
                warn("${StoreRules.KEY}를 읽지 못해 처음 상태로 시작해요", e)
                null
            }
        }
        val base = saved ?: StoreRules.defaults(now())
        val rows = try {
            dao.all()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("회차 기록을 읽지 못했어요", e)
            emptyList()
        }
        val byProfile = rows.groupBy { it.profileId }
        // 기록은 표에서 읽는다. 표에 없고 JSON에만 있으면(프로토타입 모양 파일) 표로 옮긴다.
        val moved = mutableListOf<String>()
        val st = withIds(
            base.copy(
                profiles = base.profiles.mapValues { (id, p) ->
                    val rowsOf = byProfile[id]
                    when {
                        rowsOf != null -> p.copy(sessions = rowsOf.mapNotNull(::decode))
                        p.sessions.isNotEmpty() -> p.also { moved += id }
                        else -> p
                    }
                },
            ),
        )
        moved.forEach { id -> persist("기록 옮기기") { dao.replaceProfile(id, st.profiles.getValue(id).sessions.map { entity(id, it) }) } }
        if (moved.isNotEmpty()) writeProfiles(st)
        _state.value = st
        return st
    }

    private suspend fun writeProfiles(st: AppState) {
        val stripped = st.copy(profiles = st.profiles.mapValues { it.value.copy(sessions = emptyList()) })
        val text = BaroJson.encodeToString(AppState.serializer(), stripped)
        persist("상태 저장") { dataStore.edit { it[KEY] = text } }
    }

    /** 기기에 쓰다 실패해도 메모리 상태는 그대로 둔다(store.js `write`: 경고만). */
    private suspend fun persist(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("$what 실패", e)
        }
    }

    private fun decode(row: SessionRecordEntity): SessionRecord? = try {
        BaroJson.decodeFromString(SessionRecord.serializer(), row.json).copy(id = row.recordId)
    } catch (e: SerializationException) {
        warn("읽을 수 없는 기록 ${row.recordId}를 건너뛰어요", e)
        null
    } catch (e: IllegalArgumentException) {
        warn("읽을 수 없는 기록 ${row.recordId}를 건너뛰어요", e)
        null
    }

    /** id 없는 기록(손으로 만든 데모 상태 등)에 id를 붙인다 — 표에는 id가 있어야 한다. */
    private fun withIds(st: AppState): AppState {
        if (st.profiles.values.all { p -> p.sessions.all { !it.id.isNullOrEmpty() } }) return st
        return st.copy(
            profiles = st.profiles.mapValues { (_, p) ->
                val taken = p.sessions.mapNotNull { it.id }.toMutableSet()
                p.copy(
                    sessions = p.sessions.map { r ->
                        if (!r.id.isNullOrEmpty()) r else r.copy(id = StoreRules.uniqueId(taken, StoreRules.recordIdBase(now())).also { taken += it })
                    },
                )
            },
        )
    }

    private fun entity(profileId: String, r: SessionRecord) = SessionRecordEntity(
        profileId = profileId,
        recordId = requireNotNull(r.id) { "기록 id가 없어요" },
        mode = r.mode,
        stage = r.stage,
        noInMode = r.noInMode,
        kind = if (r.kind == RecordKind.VIRTUAL_PARKING) "virtualParking" else null,
        startedAt = r.startedAt,
        simSec = r.durationSec,
        arrived = r.arrived,
        qualified = r.qualified,
        json = BaroJson.encodeToString(SessionRecord.serializer(), r),
    )

    private fun AppState.withActive(p: Profile): AppState = copy(profiles = profiles + (activeProfileId to p))

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    private fun warn(message: String, e: Throwable? = null) {
        runCatching { android.util.Log.w(TAG, message, e) } // JVM 단위 테스트에는 Log가 없다
    }

    private companion object {
        const val TAG = "BaroStore"
        val KEY = stringPreferencesKey(StoreRules.KEY)
    }
}
