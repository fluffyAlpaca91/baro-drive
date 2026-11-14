package com.barodrive.app.feature.feedback

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.AppState
import com.barodrive.core.model.Delivery.NOW
import com.barodrive.core.model.Delivery.OFF
import com.barodrive.core.model.Onboarding
import com.barodrive.core.model.Profile
import com.barodrive.core.model.StageProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** FB-1·FB-2 ViewModel: 저장소 가짜([InMemoryBaroStore])와 세션 가짜로 상태·동작·토스트를 본다. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun store(profile: Profile = Profile(onboarding = Onboarding(done = true), myPresets = BaroEngine.examplePresets())) =
        InMemoryBaroStore(AppState(profiles = mapOf("p1" to profile)))

    private fun TestScope.keepCollecting(block: suspend () -> Unit) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { block() }
    }

    // ---------------- FB-1

    @Test
    fun fb1StartsAtCurrentStageWhenNoModeIsPassed() = runTest {
        val s = store(Profile(progress = StageProgress(currentStage = 3, unlockedStage = 3)))
        val vm = FeedbackSettingsViewModel(SavedStateHandle(mapOf("mode" to 0)), s)
        keepCollecting { vm.uiState.collect {} }
        assertEquals(3, vm.uiState.value.mode)
        assertEquals("3 · 큰 도로 달리기", vm.uiState.value.title)
        vm.selectMode(5)
        assertEquals(5, vm.uiState.value.mode)
        assertTrue(vm.uiState.value.showMyPresets)
        assertEquals(FeedbackRules.MODE5_NOTE, vm.uiState.value.extraNote)
        vm.selectMode(9)
        assertEquals("1~8 밖은 무시", 5, vm.uiState.value.mode)
    }

    @Test
    fun fb1ResetClearsThatModeAndToasts() = runTest {
        val p = Profile(feedback = com.barodrive.core.model.FeedbackSettings(mapOf(2 to mapOf("C2" to OFF), 3 to mapOf("B1" to NOW))))
        val s = store(p)
        val vm = FeedbackSettingsViewModel(SavedStateHandle(mapOf("mode" to 2)), s)
        keepCollecting { vm.uiState.collect {} }
        vm.toasts.test {
            vm.reset()
            assertEquals("2 · 동네 골목 다니기를 기본값으로 되돌렸어요", awaitItem())
            vm.blocked(FeedbackSamples.c1, com.barodrive.core.model.Delivery.AFTER)
            assertEquals("이 항목은 달리는 중에만 쓸 수 있어요", awaitItem())
        }
        assertEquals(mapOf(3 to mapOf("B1" to NOW)), s.current().feedback.overrides)
    }

    @Test
    fun fb1ChoiceIsSavedRightAway() = runTest {
        assumeItemsPorted()
        val s = store()
        val vm = FeedbackSettingsViewModel(SavedStateHandle(mapOf("mode" to 1)), s)
        keepCollecting { vm.uiState.collect {} }
        vm.choose("C2", OFF)
        assertEquals(mapOf(1 to mapOf("C2" to OFF)), s.current().feedback.overrides)
        val c2 = vm.uiState.value.groups.flatMap { it.rows }.first { it.id == "C2" }
        assertEquals(OFF, c2.value)
    }

    // ---------------- FB-2

    private fun fb2(s: InMemoryBaroStore, session: FakeSessionController = FakeSessionController(), manage: Boolean = false) =
        MyPresetsViewModel(SavedStateHandle(mapOf("manage" to manage)), s, session, PresetDrafts())

    @Test
    fun fb2ShowsExamplesAndStartsFromTheLastPreset() = runTest {
        val s = store()
        val vm = fb2(s)
        keepCollecting { vm.uiState.collect {} }
        val st = vm.uiState.value
        assertEquals(listOf("출퇴근용", "비 오는 날"), st.chips.map { it.name })
        assertEquals(listOf(true, true), st.chips.map { it.example })
        assertEquals("ex-commute", st.chips.single { it.selected }.id)
        assertFalse(st.newSelected)
        assertEquals("출퇴근용", vm.nameText)
        assertTrue(st.canStart)
        assertEquals("안전 항목만 바로 피드백하고, 나머지는 주행 완료 후 피드백해요.", st.desc)
        vm.selectPreset("ex-rain")
        assertEquals("ex-rain", vm.uiState.value.chips.single { it.selected }.id)
        assertEquals("비 오는 날", vm.nameText)
        assertEquals("고른 칩은 lastPresetId로", "ex-rain", s.current().lastPresetId)
        vm.selectNew()
        assertTrue(vm.uiState.value.newSelected)
        assertEquals("", vm.nameText)
        assertEquals(PresetRules.NEW_DESC, vm.uiState.value.desc)
    }

    @Test
    fun fb2ManageModeHasNoStartButton() = runTest {
        val vm = fb2(store(), manage = true)
        keepCollecting { vm.uiState.collect {} }
        assertFalse(vm.uiState.value.canStart)
    }

    @Test
    fun fb2SaveNewThenOverwriteWithToasts() = runTest {
        val s = store()
        val vm = fb2(s)
        keepCollecting { vm.uiState.collect {} }
        vm.effects.test {
            vm.setName("   ")
            vm.save()
            assertEquals(MyPresetsEffect.Toast("설정 이름을 써 주세요"), awaitItem())
            vm.setName("주말 장보기")
            vm.save()
            assertEquals(MyPresetsEffect.Toast("'주말 장보기'로 저장했어요"), awaitItem())
            assertEquals("my-1", s.current().lastPresetId)
            assertEquals("my-1", vm.uiState.value.chips.single { it.selected }.id)
            vm.save()
            assertEquals("같은 이름이면 덮어씀", MyPresetsEffect.Toast("'주말 장보기'에 저장했어요"), awaitItem())
            vm.setName("새 길")
            vm.save()
            assertEquals(MyPresetsEffect.Toast("'새 길'로 저장했어요"), awaitItem())
            vm.setName("공원")
            vm.save()
            assertEquals("받침 있으면 '으로'", MyPresetsEffect.Toast("'공원'으로 저장했어요"), awaitItem())
        }
        assertEquals(listOf("ex-commute", "ex-rain", "my-1", "my-2", "my-3"), s.current().myPresets.map { it.id })
    }

    @Test
    fun fb2RenameAndDeleteThroughTheSheet() = runTest {
        val s = store()
        val vm = fb2(s)
        keepCollecting { vm.uiState.collect {} }
        vm.effects.test {
            vm.openEdit("ex-rain")
            assertEquals("ex-rain", vm.uiState.value.edit?.id)
            assertEquals("비 오는 날", vm.editField)
            assertEquals("연필을 누르면 그 설정을 고른다", "ex-rain", vm.uiState.value.chips.single { it.selected }.id)
            vm.setEditName("출퇴근용")
            vm.rename()
            assertEquals(MyPresetsEffect.Toast("같은 이름의 설정이 있어요"), awaitItem())
            assertEquals("안 되면 시트는 그대로", "ex-rain", vm.uiState.value.edit?.id)
            vm.setEditName("장마철")
            vm.rename()
            assertEquals(MyPresetsEffect.Toast("이름을 '장마철'로 바꿨어요"), awaitItem())
            assertNull(vm.uiState.value.edit)
            assertEquals("장마철", vm.nameText)
            vm.openEdit("ex-rain")
            vm.askDelete()
            assertTrue(vm.uiState.value.edit?.confirmDelete == true)
            vm.cancelDelete()
            assertFalse(vm.uiState.value.edit?.confirmDelete == true)
            vm.askDelete()
            vm.confirmDelete()
            assertEquals(MyPresetsEffect.Toast("'장마철' 설정을 지웠어요"), awaitItem())
        }
        assertNull(vm.uiState.value.edit)
        assertEquals(listOf("ex-commute"), s.current().myPresets.map { it.id })
        assertEquals("지운 설정 대신 남은 첫 설정", "ex-commute", vm.uiState.value.chips.single { it.selected }.id)
    }

    @Test
    fun fb2StartPreparesAutonomousSessionAndGoesToPredrive() = runTest {
        val s = store()
        val session = FakeSessionController()
        val vm = fb2(s, session)
        keepCollecting { vm.uiState.collect {} }
        vm.effects.test {
            vm.selectPreset("ex-rain")
            vm.start()
            assertEquals(MyPresetsEffect.GoPredrive, awaitItem())
        }
        val prep = session.state.value.prep
        assertEquals(5, prep?.mode)
        assertEquals("ex-rain", prep?.presetId)
        assertNull("저장한 그대로면 presetId만", prep?.presetItems)
        assertEquals(SessionPhase.READY, session.state.first().phase)
        assertEquals("ex-rain", s.current().lastPresetId)
    }

    @Test
    fun fb2CannotStartWhileDriving() = runTest {
        val session = FakeSessionController()
        session.setState { it.copy(phase = SessionPhase.ACTIVE) }
        val vm = fb2(store(), session)
        vm.effects.test {
            vm.start()
            assertEquals(MyPresetsEffect.Toast("주행이 끝난 뒤에 출발할 수 있어요"), awaitItem())
        }
    }
}
