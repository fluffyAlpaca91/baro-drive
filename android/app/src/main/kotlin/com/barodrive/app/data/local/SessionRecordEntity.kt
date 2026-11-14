package com.barodrive.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 회차 기록 한 줄 (설계 8.1 `profiles[id].sessions[]`, 프로토타입 store.js `records`). 찾을 때 쓰는 칸 몇 개 + 회차 기록 전체(JSON).
 * 기록 모양(SessionRecord)이 바뀌어도 대개 표는 그대로 둘 수 있다. [seq]가 넣은 순서다 — 큰 것이 최신(목록은 최신이 앞).
 */
@Entity(
    tableName = "session_record",
    indices = [
        Index(value = ["profileId", "recordId"], unique = true),
        Index(value = ["profileId", "mode"]),
    ],
)
data class SessionRecordEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    /** 기록 주인 프로필 id('p1' …). */
    val profileId: String,
    /** 기록 id('sess-20261110-0912', 프로필 안에서 하나). */
    val recordId: String,
    val mode: Int,
    val stage: Int?,
    val noInMode: Int?,
    /** 'virtualParking'(세워 두고 연습) 또는 null. */
    val kind: String?,
    /** 시작한 벽시계 시각(ISO 지역 시각). */
    val startedAt: String?,
    /** 세션 sim초(기록 `simSec`). */
    val simSec: Double,
    val arrived: Boolean,
    val qualified: Boolean,
    /** SessionRecord 전체(BaroJson). */
    val json: String,
)
