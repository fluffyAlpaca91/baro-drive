package com.barodrive.app.data.repo

import com.barodrive.core.model.SessionRecord
import kotlinx.coroutines.flow.Flow

/**
 * 회차 기록 저장소 — 지금 프로필의 기록 (프로토타입 app/store.js `records`, 설계 8.1 `sessions[]`). 이름은 프로토타입 그대로:
 * add = 저장, remove = 지우기. 기록은 최신이 앞이고 최대 [StoreRules.MAX_RECORDS]개다. 진행·기준값(progress, g1Baseline,
 * firstMetrics)은 기록을 지워도 프로필에 남는다(결정-90).
 * 패키지 고른 까닭은 [ProfileRepository] 참고.
 */
interface RecordRepository {
    /** 지금 프로필의 회차 기록, 최신이 앞. */
    val records: Flow<List<SessionRecord>>

    /** 기록 하나를 지켜본다(지우면 null). */
    fun observe(id: String): Flow<SessionRecord?>

    /** 기록 하나(records.get). 없으면 null. */
    suspend fun get(id: String): SessionRecord?

    /** 기록 목록(records.list({ mode })), 최신이 앞. [mode]가 null이면 전부. */
    suspend fun list(mode: Int? = null): List<SessionRecord>

    /**
     * 맨 앞에 넣는다(records.add). id가 없으면 'sess-YYYYMMDD-HHMM'(지역 시각), 같은 id가 있으면 '-2'·'-3' …을 붙인다.
     * 30개를 넘으면 오래된 것을 지우고 `shareQueue`에서도 뺀다. id를 붙인 기록을 돌려준다.
     */
    suspend fun add(record: SessionRecord): SessionRecord

    /** 고친다(records.update — RV 급제동 확인·공유 상태·면허 장면 등). id는 바꾸지 못한다. 없으면 false. */
    suspend fun update(id: String, transform: (SessionRecord) -> SessionRecord): Boolean

    /** 지운다(records.remove, ST-2 기록). `shareQueue`에서도 뺀다. 없으면 false. */
    suspend fun remove(id: String): Boolean

    /** 지금 프로필 기록을 모두 지운다(ST 'clearRecords'). 진행·기준값은 그대로. 지운 수. */
    suspend fun clear(): Int
}
