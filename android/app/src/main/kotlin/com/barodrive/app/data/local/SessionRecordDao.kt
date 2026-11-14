package com.barodrive.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** 회차 기록 표 접근. 앱 코드는 이것 대신 저장소(`data.repo.RecordRepository`)를 쓴다. */
@Dao
interface SessionRecordDao {
    /** 모든 프로필의 기록, 최신이 앞. */
    @Query("SELECT * FROM session_record ORDER BY seq DESC")
    suspend fun all(): List<SessionRecordEntity>

    /** 새 기록(맨 앞이 된다). */
    @Insert
    suspend fun insert(record: SessionRecordEntity): Long

    /** 기록 하나의 내용을 바꾼다(순서 [SessionRecordEntity.seq]는 그대로). 바뀐 줄 수. */
    @Query(
        "UPDATE session_record SET mode = :mode, stage = :stage, noInMode = :noInMode, kind = :kind, startedAt = :startedAt, " +
            "simSec = :simSec, arrived = :arrived, qualified = :qualified, json = :json WHERE profileId = :profileId AND recordId = :recordId",
    )
    suspend fun update(
        profileId: String, recordId: String, mode: Int, stage: Int?, noInMode: Int?, kind: String?, startedAt: String?,
        simSec: Double, arrived: Boolean, qualified: Boolean, json: String,
    ): Int

    @Query("DELETE FROM session_record WHERE profileId = :profileId AND recordId IN (:recordIds)")
    suspend fun delete(profileId: String, recordIds: List<String>): Int

    @Query("DELETE FROM session_record WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String): Int

    @Query("DELETE FROM session_record")
    suspend fun deleteAll()

    /** 한 프로필의 기록을 통째로 바꾼다([records]는 최신이 앞). */
    @Transaction
    suspend fun replaceProfile(profileId: String, records: List<SessionRecordEntity>) {
        deleteProfile(profileId)
        records.asReversed().forEach { insert(it) }
    }
}
