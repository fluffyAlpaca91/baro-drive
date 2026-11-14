package com.barodrive.app.testing

import androidx.datastore.preferences.core.emptyPreferences
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.data.signal.ActiveSignalSource
import com.barodrive.app.data.voice.CoachVoiceLog
import com.barodrive.app.session.DrivingSessionManager
import com.barodrive.core.model.AppState
import com.barodrive.core.model.Gear
import com.barodrive.core.model.VehicleSnapshot
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * 세션 관리자를 가짜 엔진·출처·음성 + 메모리 저장소로 묶는다. 앱 범위는 테스트의 backgroundScope라 테스트가 끝나면 함께 멈춘다.
 * DEMO_TOOLS가 켜진(기본) 빌드를 가정한다 — 꺼지면 자료 출처가 늘 진짜 차다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionFixture(
    scope: TestScope,
    val engine: FakeSessionEngine = FakeSessionEngine(),
    initial: AppState? = null,
) {
    val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
    val clock: Clock = Clock.fixed(Instant.ofEpochMilli(STARTED_AT_MS), ZoneId.of("Asia/Seoul"))
    val settings = SettingsRepository(InMemoryDataStore(emptyPreferences()))
    val sim = FakeSignalSource()
    val car = FakeSignalSource()
    val signals = ActiveSignalSource(settings, car, sim, scope.backgroundScope)
    val tts = FakeCoachVoice()
    val store = InMemoryBaroStore(initial, clock)
    val voice = CoachVoiceLog(tts, engine, store, scope.backgroundScope)
    val simLink = FakeSimLink()
    val navi = FakeNaviController()
    val manager = DrivingSessionManager(
        signals = signals,
        engine = engine,
        tts = tts,
        voice = voice,
        profiles = store,
        records = store,
        settings = settings,
        sim = simLink,
        clock = clock,
        scope = scope.backgroundScope,
        dispatcher = dispatcher,
        navi = navi,
    )

    companion object {
        /** 2025-11-12 07:26:40 +09:00. */
        const val STARTED_AT_MS = 1_762_900_000_000L

        /** P에서 기다림 → D로 바꾸고 2초 서 있다가 → 5km/h/s로 30까지 → 순항. 0.1초 간격, [startT]부터. */
        fun driveOff(startT: Double = 0.0): List<VehicleSnapshot> = buildList {
            var i = 0
            fun at(gear: Gear, speed: Double) = add(VehicleSnapshot(t = startT + i++ / 10.0, gear = gear, speed = speed))
            repeat(5) { at(Gear.P, 0.0) }
            repeat(5) { at(Gear.D, 0.0) }
            for (k in 1..60) at(Gear.D, k * 0.5)
            repeat(20) { at(Gear.D, 30.0) }
        }

        /** [startT]부터 0.1초 간격 P 표본 [n]개. */
        fun parked(startT: Double, n: Int): List<VehicleSnapshot> = List(n) { VehicleSnapshot(t = startT + it / 10.0, gear = Gear.P, speed = 0.0) }
    }
}
