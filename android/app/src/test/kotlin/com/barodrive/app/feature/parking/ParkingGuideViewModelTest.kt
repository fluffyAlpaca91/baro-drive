package com.barodrive.app.feature.parking

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.barodrive.app.session.api.ActivePhase
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Hints
import com.barodrive.core.model.ParkingHint
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** PK-3 버튼 (parking-guide.js `press`·`stop`, e2e t5b-guide·t5b-stop). */
class ParkingGuideViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun hint(json: String) = BaroJson.decodeFromString(ParkingHint.serializer(), json)

    private fun live(json: String, gear: Gear) = DrivingState(
        phase = SessionPhase.ACTIVE,
        activePhase = ActivePhase.DRIVING,
        mode = 6,
        noInMode = 1,
        snapshot = VehicleSnapshot(t = 3.0, gear = gear, speed = 0.0),
        gear = gear,
        output = StepOutput(hints = Hints(parking = hint(json))),
    )

    private fun vm(c: FakeSessionController) =
        ParkingGuideViewModel(SavedStateHandle(mapOf("type" to "back", "side" to "L")), c)

    @Test
    fun nextSendsTheShownStepOnlyDuringTheSession() = runTest {
        val c = FakeSessionController(DrivingState(prep = PrepSession(mode = 6), phase = SessionPhase.READY, mode = 6))
        val viewModel = vm(c)
        viewModel.next()
        assertTrue("회차 전에는 엔진에 넣지 않는다", c.inputs.isEmpty())

        c.setState { live(ParkingGuideMapperTest.GOLDEN_STEP1, Gear.D) }
        viewModel.next()
        assertEquals(listOf<SessionInput>(SessionInput.PkNext(1)), c.inputs)

        c.setState { live(ParkingGuideMapperTest.GOLDEN_DONE, Gear.R) }
        viewModel.next()
        assertEquals("다 마치면 누르지 않는다", 1, c.inputs.size)
    }

    @Test
    fun stopWhenParkedCancelsAndGoesToTheSelectScreen() = runTest {
        val c = FakeSessionController(live(ParkingGuideMapperTest.GOLDEN_STEP1, Gear.P))
        val viewModel = vm(c)
        c.events.test {
            viewModel.effects.test {
                viewModel.stop()
                assertEquals(ParkingGuideEffect.ToSelect("parallel", "R"), awaitItem())
            }
            assertEquals(SessionEvent.Cancelled(mode = 6, noInMode = 1, active = true), awaitItem())
        }
        assertEquals(SessionPhase.IDLE, c.state.value.phase)
    }

    @Test
    fun stopWhileMovingCancelsAndClosesTheScreen() = runTest {
        val c = FakeSessionController(live(ParkingGuideMapperTest.GOLDEN_STEP1, Gear.D))
        val viewModel = vm(c)
        viewModel.effects.test {
            viewModel.stop()
            assertEquals(ParkingGuideEffect.Close, awaitItem())
        }
        assertEquals(SessionPhase.IDLE, c.state.value.phase)
    }

    @Test
    fun stopAfterTheLastStepInParkOnlyToasts() = runTest {
        val c = FakeSessionController(live(ParkingGuideMapperTest.GOLDEN_DONE, Gear.P))
        val viewModel = vm(c)
        viewModel.effects.test {
            viewModel.stop()
            assertEquals(ParkingGuideEffect.Toast(GuideBlock.DONE_PARKED.text), awaitItem())
        }
        assertEquals("끝낸 회차를 버리지 않는다", SessionPhase.ACTIVE, c.state.value.phase)
    }

    @Test
    fun routeArgumentsAreUsedBeforeAnySession() = runTest {
        val viewModel = vm(FakeSessionController())
        viewModel.uiState.test {
            val ui = awaitItem()
            assertEquals(listOf("back", "L", "7"), listOf(ui.type, ui.side, ui.total.toString()))
        }
    }
}
