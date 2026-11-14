package com.barodrive.app.di

import com.barodrive.app.BuildConfig
import com.barodrive.app.data.navi.ActiveNaviPlaceSource
import com.barodrive.app.data.navi.NaviPlaceSource
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.data.store.DeviceBaroStore
import com.barodrive.app.data.signal.ActiveSignalSource
import com.barodrive.app.data.signal.SignalSource
import com.barodrive.app.data.signal.sim.DemoSimLink
import com.barodrive.app.data.signal.sim.SimSignalSource
import com.barodrive.app.data.voice.AndroidTtsCoachVoice
import com.barodrive.app.data.voice.AutoCoachVoice
import com.barodrive.app.data.voice.CoachVoice
import com.barodrive.app.data.voice.CoachVoiceLog
import com.barodrive.app.data.voice.PleosTtsCoachVoice
import com.barodrive.app.demo.DataStoreDemoPanelRepository
import com.barodrive.app.demo.DemoPanelRepository
import com.barodrive.app.session.DrivingSessionManager
import com.barodrive.app.session.FacadeSessionEngine
import com.barodrive.app.session.NoSimLink
import com.barodrive.app.session.SessionEngine
import com.barodrive.app.session.SimLink
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.session.api.VoiceLog
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * 데이터 층 인터페이스 ↔ 구현. 진짜 차 구현은 [CarModule]에 있다.
 * 앱 계약(화면이 주입받는 것): [SessionController] · [VoiceLog] · [ProfileRepository] · [RecordRepository].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    /** 한정자 없는 SignalSource = 설정에서 고른 지금 출처. */
    @Binds
    abstract fun signalSource(impl: ActiveSignalSource): SignalSource

    @Binds
    @SimSignal
    abstract fun simSignalSource(impl: SimSignalSource): SignalSource


    @Binds
    abstract fun voiceLog(impl: CoachVoiceLog): VoiceLog

    /** CR-2 장소: 시뮬레이터면 팀 장소, 진짜 차면 차 내비 즐겨찾기·최근 목적지. */
    @Binds
    abstract fun naviPlaceSource(impl: ActiveNaviPlaceSource): NaviPlaceSource

    @Binds
    abstract fun sessionController(impl: DrivingSessionManager): SessionController

    @Binds
    abstract fun sessionEngine(impl: FacadeSessionEngine): SessionEngine

    /** 시연 패널 설정(`baro.panel.v1`) — 네트워크·동네·혼잡 시드·신호 지원·가족 휴대폰 카드. */
    @Binds
    abstract fun demoPanelRepository(impl: DataStoreDemoPanelRepository): DemoPanelRepository

    /** 프로필과 기록은 한 저장소가 함께 맡는다(store.js처럼 기록이 프로필에 딸림). */
    @Binds
    abstract fun profileRepository(impl: DeviceBaroStore): ProfileRepository

    @Binds
    abstract fun recordRepository(impl: DeviceBaroStore): RecordRepository

    companion object {
        /** 시뮬레이터 연결: 시연 빌드는 시연 시뮬레이터([DemoSimLink]), 시연 도구를 뺀 빌드는 시나리오 없음([NoSimLink]). */
        @Provides
        fun simLink(demo: Provider<DemoSimLink>, none: NoSimLink): SimLink = if (BuildConfig.DEMO_TOOLS) demo.get() else none

        /** 코치 소리: Pleos TTS(한국어) → 안드로이드 TTS → 소리 없음, 처음 말할 때 저절로 고른다(쓰는 엔진은 로그에). */
        @Provides
        @Singleton
        fun coachVoice(
            pleos: PleosTtsCoachVoice,
            android: AndroidTtsCoachVoice,
            @ApplicationScope scope: CoroutineScope,
        ): CoachVoice = AutoCoachVoice(listOf(pleos, android), scope)
    }
}
