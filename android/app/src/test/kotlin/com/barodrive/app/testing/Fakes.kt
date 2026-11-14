package com.barodrive.app.testing

import androidx.datastore.core.DataStore
import com.barodrive.app.data.local.SessionRecordDao
import com.barodrive.app.data.local.SessionRecordEntity
import com.barodrive.app.data.signal.SignalSource
import com.barodrive.app.data.voice.CoachVoice
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 테스트가 스냅숏을 직접 흘려 넣는 신호 출처. */
class FakeSignalSource(caps: SignalCaps = SignalCaps()) : SignalSource {
    private val flow = MutableSharedFlow<VehicleSnapshot>()
    override val snapshots: Flow<VehicleSnapshot> = flow
    override val caps: StateFlow<SignalCaps> = MutableStateFlow(caps)

    /** 누군가 이 출처를 모으기 시작할 때까지 기다린다(그 전에 보낸 값은 사라지므로). */
    suspend fun awaitCollector() {
        flow.subscriptionCount.first { it > 0 }
    }

    suspend fun emit(vararg snapshots: VehicleSnapshot) {
        snapshots.forEach { flow.emit(it) }
    }
}

/** 말한 것을 적어 두는 음성. */
class FakeCoachVoice : CoachVoice {
    override var muted: Boolean = false
    val spoken = mutableListOf<Utterance>()
    var stopCount = 0
        private set

    override fun speak(utterance: Utterance) {
        if (!muted) spoken += utterance
    }

    override fun stop() {
        stopCount++
    }
}

/** 메모리 안 회차 기록 표(Room `session_record` 흉내 — seq는 넣은 순서). [failNext]면 다음 쓰기 한 번이 실패한다. */
class FakeSessionRecordDao : SessionRecordDao {
    private var seq = 0L
    val rows = mutableListOf<SessionRecordEntity>()
    var failNext = false

    private fun maybeFail() {
        if (failNext) {
            failNext = false
            throw IllegalStateException("디스크 가득")
        }
    }

    override suspend fun all(): List<SessionRecordEntity> = rows.sortedByDescending { it.seq }

    override suspend fun insert(record: SessionRecordEntity): Long {
        maybeFail()
        require(rows.none { it.profileId == record.profileId && it.recordId == record.recordId }) { "UNIQUE" }
        val e = record.copy(seq = ++seq)
        rows += e
        return e.seq
    }

    override suspend fun update(
        profileId: String, recordId: String, mode: Int, stage: Int?, noInMode: Int?, kind: String?, startedAt: String?,
        simSec: Double, arrived: Boolean, qualified: Boolean, json: String,
    ): Int {
        maybeFail()
        val i = rows.indexOfFirst { it.profileId == profileId && it.recordId == recordId }
        if (i < 0) return 0
        rows[i] = rows[i].copy(
            mode = mode, stage = stage, noInMode = noInMode, kind = kind, startedAt = startedAt, simSec = simSec, arrived = arrived,
            qualified = qualified, json = json,
        )
        return 1
    }

    override suspend fun delete(profileId: String, recordIds: List<String>): Int {
        maybeFail()
        val n = rows.size
        rows.removeAll { it.profileId == profileId && it.recordId in recordIds }
        return n - rows.size
    }

    override suspend fun deleteProfile(profileId: String): Int {
        maybeFail()
        val n = rows.size
        rows.removeAll { it.profileId == profileId }
        return n - rows.size
    }

    override suspend fun deleteAll() {
        maybeFail()
        rows.clear()
    }

    override suspend fun replaceProfile(profileId: String, records: List<SessionRecordEntity>) {
        deleteProfile(profileId)
        records.asReversed().forEach { insert(it) }
    }
}

/** 메모리 안 DataStore(파일 없이 설정 저장소를 시험한다). */
class InMemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    override val data: Flow<T> = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
        transform(state.value).also { state.value = it }
    }
}
