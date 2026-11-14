package com.barodrive.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 기기 안 DB (프로토타입 localStorage `baro.app.v1`의 회차 기록 자리, 설계 8.1). 프로필·진행·설정은 DataStore(JSON)에 있다.
 * 칸을 바꾸면 version을 올린다. 바뀐 구조는 app/schemas/에 기록되니 같이 커밋한다.
 *
 * version 2: 개발 중에 표 구조가 version 1인 채로 바뀌어, 옛 구조로 깔린 앱(에뮬레이터)이 DB를 열지 못했다. 그래서 올렸다.
 * 첫 출시 전이라 마이그레이션은 쓰지 않는다 — 버전이 다르면 표를 모두 지우고 새로 만든다(di/DatabaseModule). 출시한 뒤로는
 * 기록이 지워지지 않게 마이그레이션을 쓴다.
 */
@Database(entities = [SessionRecordEntity::class], version = 2, exportSchema = true)
abstract class BaroDatabase : RoomDatabase() {
    abstract fun sessionRecordDao(): SessionRecordDao
}
