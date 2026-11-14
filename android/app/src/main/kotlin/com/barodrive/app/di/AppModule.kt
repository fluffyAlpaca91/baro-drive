package com.barodrive.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 앱 전체 기반: 앱 범위 코루틴, 디스패처, 벽시계. 테스트는 이것들을 갈아 끼워 시간을 다룬다. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** SupervisorJob: 한 일이 실패해도 다른 수집은 계속된다. */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    /** 벽시계. 기록의 시작 시각에만 쓴다 — 판정 시간은 늘 스냅숏 `t`다. */
    @Provides
    @Singleton
    fun wallClock(): Clock = Clock.systemDefaultZone()
}
