package com.barodrive.app.feature.hill

import app.cash.turbine.test
import com.barodrive.app.R
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.session.api.ActivePhase
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HillHint
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.Hints
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** HL-1·HL-2 버튼 (hill.js `practiceChips`·단계 칸·상황 칩·[했어요]·`hillHead` 소리 끄기·[그만하기]). */
class HillViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun state(gear: Gear, speed: Double, practice: HillPractice = HillPractice.UP) = DrivingState(
        phase = SessionPhase.ACTIVE,
        activePhase = ActivePhase.DRIVING,
        mode = 7,
        noInMode = 1,
        snapshot = VehicleSnapshot(t = 5.0, gear = gear, speed = speed),
        gear = gear,
        output = StepOutput(t = 5.0, hints = Hints(hill = HillHint(practice = practice, step = 1, steps = 4))),
    )

    @Test
    fun chipsAndStepsGoToTheEngineOnlyWhenStopped() = runTest {
        val c = FakeSessionController(state(Gear.D, 15.0))
        val vm = HillDriveViewModel(c, InMemoryBaroStore())
        vm.effects.test {
            vm.pickPractice(HillPractice.DOWN)
            assertEquals(HillEffect.Toast(R.string.hl_still_hint), awaitItem())
            vm.pickStep(2)
            assertEquals(HillEffect.Toast(R.string.hl_still_hint), awaitItem())
        }
        assertTrue(c.inputs.isEmpty())
        c.setState { state(Gear.D, 0.0) }
        vm.pickPractice(HillPractice.DOWN)
        vm.pickStep(2)
        vm.press()
        assertEquals(listOf(SessionInput.PickHillPractice(HillPractice.DOWN), SessionInput.HillStep(2), SessionInput.HillPress), c.inputs)
    }

    @Test
    fun situationChipsAreRefusedInPark() = runTest {
        val c = FakeSessionController(state(Gear.P, 0.0, HillPractice.PARK))
        val vm = HillParkViewModel(c, InMemoryBaroStore())
        vm.effects.test {
            vm.pickSituation(HillSlope.DOWN)
            assertEquals(HillEffect.Toast(R.string.hl_still_hint), awaitItem())
        }
        c.setState { state(Gear.D, 0.0, HillPractice.PARK) }
        vm.pickSituation(HillSlope.DOWN)
        assertEquals(listOf<SessionInput>(SessionInput.HillSituation(HillSlope.DOWN)), c.inputs)
    }

    @Test
    fun muteTogglesWithToasts() = runTest {
        val c = FakeSessionController(state(Gear.D, 10.0))
        val vm = HillDriveViewModel(c, InMemoryBaroStore())
        vm.effects.test {
            vm.toggleMute()
            assertEquals(HillEffect.Toast(R.string.hl_mute_on_toast, R.drawable.ic_mute), awaitItem())
            vm.toggleMute()
            assertEquals(HillEffect.Toast(R.string.hl_mute_off_toast, R.drawable.ic_speaker), awaitItem())
        }
        assertEquals(listOf(SessionInput.Mute, SessionInput.Unmute), c.inputs)
    }

    @Test
    fun stopOnlyWhenStoppedThenHomeOrClose() = runTest {
        val c = FakeSessionController(state(Gear.D, 10.0))
        val vm = HillDriveViewModel(c, InMemoryBaroStore())
        vm.effects.test {
            vm.stop()
            assertEquals(HillEffect.Toast(R.string.hl_stop_hint), awaitItem())
            assertEquals(SessionPhase.ACTIVE, c.state.value.phase)

            c.setState { state(Gear.D, 0.0) }
            vm.stop()
            assertEquals(HillEffect.Close, awaitItem())
            assertEquals("기록 없이 끝냄", SessionPhase.IDLE, c.state.value.phase)

            c.setState { state(Gear.P, 0.0) }
            vm.stop()
            assertEquals(HillEffect.Home, awaitItem())
        }
    }

    @Test
    fun uiStateFollowsTheSessionAndTheCoach() = runTest {
        val c = FakeSessionController(state(Gear.D, 0.0, HillPractice.PARK))
        val vm = HillDriveViewModel(c, InMemoryBaroStore())
        vm.uiState.test {
            var s = awaitItem()
            while (!s.toPark) s = awaitItem()
            assertTrue(s.canTap)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
