package com.barodrive.app.feature.autonomous

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.feature.share.ShareCenter
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.ScoreCounts
import com.barodrive.core.model.AppState
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.ShareStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** RV-4 ViewModel: 기록을 그리고, 들어올 때 점수 대사를 한 번 말하고, 급제동 확인을 기록에 저장한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutonomousViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-11-11T06:00:00Z"), ZoneId.of("Asia/Seoul"))

    private val record = SessionRecord(
        id = "sess-a", mode = 5, placeId = "mart", arrived = true, distanceM = 12440.0, durationSec = 1000.0,
        f2 = listOf(F2Check(id = "f2-1", v0 = 56.0, dur = 4.3)),
        score = BaroEngine.score.fromCounts(ScoreCounts(late = 1, almost = 2, c4 = 1, bf = 1, g1Points = 4, f2 = 1)),
    )

    private fun TestScope.setup(readOnly: Boolean, rec: SessionRecord = record): Triple<AutonomousViewModel, InMemoryBaroStore, InMemoryVoiceLog> {
        val store = InMemoryBaroStore(AppState(profiles = mapOf("p1" to Profile(sessions = listOf(rec)))), clock)
        val voice = InMemoryVoiceLog()
        val session = FakeSessionController()
        val share = ShareCenter(store, store, session, clock, backgroundScope)
        val vm = AutonomousViewModel(SavedStateHandle(mapOf("recordId" to rec.id, "readOnly" to readOnly)), store, store, voice, share)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return Triple(vm, store, voice)
    }

    @Test
    fun drawsTheRecordAndSpeaksTheScoreLineOnce() = runTest {
        val (vm, _, voice) = setup(readOnly = false)
        val s = vm.uiState.value
        assertTrue(s.found)
        assertEquals("도착했어요", s.title)
        assertEquals(86, s.total)
        assertEquals("마트까지 잘 왔어. 깜빡이만 조금 일찍 켜 보자.", s.lineText)
        assertEquals(1, s.f2.size)
        assertEquals("가족에게 요약 보내기", s.share.text)
        assertEquals(listOf("rv.score.mid"), voice.entries.value.map { it.key })
    }

    @Test
    fun readOnlyStaysQuiet() = runTest {
        val (vm, _, voice) = setup(readOnly = true)
        assertTrue(vm.uiState.value.readOnly)
        assertTrue(voice.entries.value.isEmpty())
    }

    @Test
    fun f2ChoiceIsSavedToTheRecord() = runTest {
        val (vm, store, _) = setup(readOnly = true)
        vm.chooseF2("f2-1", F2Choice.AVOID)
        assertEquals(F2Choice.AVOID, store.get("sess-a")?.f2?.single()?.choice)
        assertEquals(F2Choice.AVOID, vm.uiState.value.f2.single().choice)
    }

    @Test
    fun missingRecord() = runTest {
        val store = InMemoryBaroStore(AppState(), clock)
        val vm = AutonomousViewModel(
            SavedStateHandle(mapOf("recordId" to "nope", "readOnly" to true)), store, store, InMemoryVoiceLog(),
            ShareCenter(store, store, FakeSessionController(), clock, backgroundScope),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.found)
    }

    @Test
    fun shareLinkOpensTheSheetOrRetriesQueued() = runTest {
        val (vm, _, _) = setup(readOnly = true)
        vm.effects.test {
            vm.shareClick()
            assertEquals(AutonomousEffect.OpenShare("sess-a"), awaitItem())
        }
        val queued = record.copy(share = ShareState(status = ShareStatus.QUEUED, to = "아버지"))
        val (vm2, store2, _) = setup(readOnly = true, rec = queued)
        store2.update { it.copy(shareQueue = listOf("sess-a")) }
        assertEquals("연결되면 보내요", vm2.uiState.value.share.text)
        vm2.effects.test {
            vm2.shareClick()
            // 가짜 세션은 기어를 모름 → 서 있는 것으로 보고 바로 보낸다
            assertEquals(AutonomousEffect.Toast("보내지 못한 요약 1건을 보냈어요"), awaitItem())
        }
        assertEquals(ShareStatus.SENT, store2.get("sess-a")?.share?.status)
        assertEquals("보냈어요 · 아버지", vm2.uiState.value.share.text)
    }
}
