package com.barodrive.app.feature.stage

import androidx.lifecycle.SavedStateHandle
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.ProgressResult
import com.barodrive.core.model.SessionRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 프로토타입 tests/unit/t3_stage.test.js의 화면 모델 부분(rv2Model·rv3Model — 지금 코치 대사, 들어올 때 한 번 말하기). */
@OptIn(ExperimentalCoroutinesApi::class)
class StageViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private suspend fun store(): InMemoryBaroStore {
        val s = InMemoryBaroStore()
        s.update { p ->
            p.copy(
                coach = Coach.MOM,
                honorific = Honorific(HonorificType.SON),
                progress = p.progress.copy(currentStage = 4, unlockedStage = 4, graduatedAt = "2026-11-09T10:00:00+09:00"),
                sessionCount = p.sessionCount + mapOf(2 to 2, 4 to 2),
            )
        }
        s.add(SessionRecord(id = "sess-p2", mode = 2, stage = 2, durationSec = 480.0, distanceM = 2950.0, progress = ProgressResult(stage = 2, passed = true, nextStage = 3)))
        s.add(SessionRecord(id = "sess-p4", mode = 4, stage = 4, durationSec = 560.0, distanceM = 11300.0, progress = ProgressResult(stage = 4, passed = true, graduated = true)))
        return s
    }

    @Test
    fun stagePassedShowsTheModelAndSpeaksOnce() = runTest(main.dispatcher.scheduler) {
        val s = store()
        val voice = InMemoryVoiceLog()
        val vm = StagePassedViewModel(SavedStateHandle(mapOf(StagePassedViewModel.ARG_RECORD to "sess-p2")), s, s, voice)
        runCurrent()
        val st = vm.uiState.value
        assertEquals(2, st.pass?.stage)
        assertEquals("2단계 통과!", st.pass?.title)
        assertEquals("rv.pass.2", st.pass?.lineKey)
        assertEquals(Coach.MOM, st.coach)
        assertEquals("지금 코치(엄마) 대사 — rv.pass.*에는 호칭 자리가 없음", "세상에, 2단계 통과야! 큰길도 천천히 가자, 응?", st.line)
        assertFalse(st.toGraduation)
        assertEquals(listOf("rv.pass.2"), voice.entries.value.map { it.key })
        assertEquals(3, st.pass?.courseMode)
    }

    @Test
    fun stageFourGoesToGraduationAndMissingRecordShowsEmpty() = runTest(main.dispatcher.scheduler) {
        val s = store()
        val vm = StagePassedViewModel(SavedStateHandle(mapOf(StagePassedViewModel.ARG_RECORD to "sess-p4")), s, s, InMemoryVoiceLog())
        runCurrent()
        assertTrue(vm.uiState.value.toGraduation)
        val none = StagePassedViewModel(SavedStateHandle(mapOf(StagePassedViewModel.ARG_RECORD to "sess-none")), s, s, InMemoryVoiceLog())
        runCurrent()
        assertNull(none.uiState.value.pass)
        assertTrue(none.uiState.value.loaded)
    }

    @Test
    fun graduationSummarySpeaksOnlyWhenOpenedFromTheSession() = runTest(main.dispatcher.scheduler) {
        val s = store()
        val voice = InMemoryVoiceLog()
        val vm = GraduationViewModel(SavedStateHandle(mapOf(StagePassedViewModel.ARG_RECORD to "sess-p4")), s, s, voice)
        runCurrent()
        val g = vm.uiState.value
        assertTrue(g.summary?.graduated == true)
        assertEquals(4, g.summary?.sessions)
        assertEquals("세상에, 다 끝냈어 우리 아들! 너무 대견해!", g.line)
        assertEquals("2026. 11. 9.", g.date)
        assertEquals(listOf("rv.grad"), voice.entries.value.map { it.key })
        val home = GraduationViewModel(SavedStateHandle(), s, s, voice)
        runCurrent()
        assertTrue(home.uiState.value.summary?.graduated == true)
        assertEquals("홈에서 열면 말하지 않음", 1, voice.entries.value.size)
    }

    @Test
    fun beforeGraduationOnlyTheSummary() = runTest(main.dispatcher.scheduler) {
        val s = InMemoryBaroStore()
        val vm = GraduationViewModel(SavedStateHandle(), s, s, InMemoryVoiceLog())
        runCurrent()
        assertFalse(vm.uiState.value.summary?.graduated == true)
        assertEquals("", vm.uiState.value.line)
        assertNull(vm.uiState.value.date)
        assertEquals("2026. 4. 1.", GraduationViewModel.dotDate("2026-04-01"))
        assertNull(GraduationViewModel.dotDate("x"))
    }
}
