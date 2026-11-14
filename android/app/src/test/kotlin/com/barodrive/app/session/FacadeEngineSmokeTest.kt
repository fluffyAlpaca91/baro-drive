package com.barodrive.app.session

import app.cash.turbine.test
import androidx.datastore.preferences.core.emptyPreferences
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.data.signal.ActiveSignalSource
import com.barodrive.app.data.store.DeviceBaroStore
import com.barodrive.app.data.voice.CoachVoiceLog
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.testing.FakeCoachVoice
import com.barodrive.app.testing.FakeNaviController
import com.barodrive.app.testing.FakeSessionRecordDao
import com.barodrive.app.testing.FakeSignalSource
import com.barodrive.app.testing.FakeSimLink
import com.barodrive.app.testing.InMemoryDataStore
import com.barodrive.app.testing.SessionFixture
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.PrepSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 진짜 묶음으로 한 회차: 엔진 파사드(FacadeSessionEngine) + 기기 저장(DeviceBaroStore, 가짜 디스크).
 * 말·판정 내용은 보지 않고(골든 시험 몫) 시작·끝·저장·진행 반영 자리만 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FacadeEngineSmokeTest {
    @Test
    fun oneRunThroughTheFacadeIsSavedOnDisk() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-10T00:12:00Z"), ZoneId.of("Asia/Seoul"))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(InMemoryDataStore(emptyPreferences()))
        val sim = FakeSignalSource()
        val signals = ActiveSignalSource(settings, FakeSignalSource(), sim, backgroundScope)
        val dao = FakeSessionRecordDao()
        val store = DeviceBaroStore(InMemoryDataStore(emptyPreferences()), dao, clock)
        val engine = FacadeSessionEngine()
        val tts = FakeCoachVoice()
        val voice = CoachVoiceLog(tts, engine, store, backgroundScope)
        val manager = DrivingSessionManager(
            signals, engine, tts, voice, store, store, settings, FakeSimLink(), clock, backgroundScope, dispatcher, FakeNaviController(),
        )
        manager.start()
        sim.awaitCollector()
        val prep = manager.prepare(PrepSession(mode = 1, courseId = "c1-B"))
        assertEquals(1, prep?.noInMode)

        manager.events.test {
            sim.emit(*SessionFixture.driveOff().toTypedArray())
            val started = awaitItem() as SessionEvent.Started
            assertEquals("c1-B", started.courseId)
            sim.emit(SessionFixture.driveOff().last().copy(t = 9.0, speed = 0.0, nav = NavSnapshot(events = listOf(NavEvent.Arrived))))
            sim.emit(*SessionFixture.parked(9.1, 40).toTypedArray())
            val done = awaitItem() as SessionEvent.Finished
            assertTrue(done.arrived)
            assertEquals("sess-20261110-0912", done.recordId)
            cancelAndIgnoreRemainingEvents()
        }
        val rec = store.get("sess-20261110-0912")
        assertEquals(1, rec?.mode)
        assertEquals(1, dao.rows.size)
    }
}
