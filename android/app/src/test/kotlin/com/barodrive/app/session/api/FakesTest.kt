package com.barodrive.app.session.api

import app.cash.turbine.test
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.TodayTask
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면 미리보기·테스트용 가짜가 계약대로 도는지. */
class FakesTest {
    @Test
    fun fakeControllerPreparesAndAnswersButtons() = runTest {
        val c = FakeSessionController(today = listOf(TodayTask(id = "T-STOP", label = "부드럽게 서기")), items = mapOf("PB1" to Delivery.OFF))
        val p = c.prepare(PrepSession(mode = 9, placeId = "pl-1"))
        assertEquals(1, p?.mode)
        assertEquals(SessionPhase.READY, c.state.value.phase)
        assertTrue(c.readTasks())
        assertTrue(c.state.value.prep?.lines?.tasksRead == true)
        assertTrue(c.passengerAboard())
        assertFalse("준비 세션마다 한 번", c.passengerAboard())
        assertFalse("PB1이 X면 묻지 않음", c.askHandbrake())
        assertEquals(listOf("p.tasks", "l1"), c.linesSaid)
        assertTrue(c.confirmHandbrake())
        assertTrue(c.setMirror(true))
        assertEquals("내비에 pl-1를 넣었어요", c.send()?.toast)
        assertTrue(c.state.value.sent)
        assertNull("회차가 없으면 입력 없음", c.input(SessionInput.DriverAsk))

        c.setState { it.copy(phase = SessionPhase.ACTIVE, activePhase = ActivePhase.STOPPED, mode = 1, noInMode = 1) }
        assertEquals(true, c.toggleMute())
        assertEquals(listOf<SessionInput>(SessionInput.Mute), c.inputs)
        assertTrue(c.continueHere())
        c.events.test {
            val id = c.endHere()
            assertEquals(SessionEvent.Finished(id, 1, false), awaitItem())
        }
        assertEquals(SessionPhase.IDLE, c.state.value.phase)
    }
}
