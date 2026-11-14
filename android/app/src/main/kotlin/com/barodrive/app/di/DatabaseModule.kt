package com.barodrive.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.barodrive.app.data.local.BaroDatabase
import com.barodrive.app.data.local.SessionRecordDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** 기기 안 저장소: 회차 기록(Room), 앱 상태(DataStore `baro_app` — JSON), 앱 설정(DataStore `settings`). 모두 앱에 하나씩. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BaroDatabase =
        Room.databaseBuilder(context, BaroDatabase::class.java, "baro-drive.db")
            // 첫 출시 전(BaroDatabase version 2 KDoc): 버전이 다르면 마이그레이션 대신 표를 모두 지우고 새로 만든다 — 시험 기록만 사라진다.
            // 버전을 내릴 때(에뮬레이터 하나에 여러 브랜치 빌드를 번갈아 깔 때)도 이것 하나로 된다. 뒤에 fallbackToDestructiveMigrationOnDowngrade를
            // 부르면 올릴 때 마이그레이션을 다시 요구해서 옛 DB를 못 연다(에뮬레이터에서 확인) — 부르지 않는다. 출시 전에 마이그레이션으로 바꾼다.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun sessionRecordDao(database: BaroDatabase): SessionRecordDao = database.sessionRecordDao()

    /** 프로필·진행·설정 JSON 한 키(설계 8.1 `baro.app.v1`). */
    @Provides
    @Singleton
    @AppStateStore
    fun appStateDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(io + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile("baro_app") },
    )

    @Provides
    @Singleton
    fun settingsDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(io + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile("settings") },
    )
}
