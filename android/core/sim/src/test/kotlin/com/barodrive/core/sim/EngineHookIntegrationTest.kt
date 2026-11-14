package com.barodrive.core.sim

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.scenario.ParkingChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시뮬레이터 + 엔진 통합(테스트에만 있는 의존 — main 코드의 sim은 엔진을 모른다).
 * [ScenarioRunner]에 엔진 세션을 [SessionHook]으로 이어 앱 세션 연결처럼 끝까지 돌린다(기어가 P를 벗어나면 세션 시작·시계 0,
 * 도착 뒤 P에서 마무리). 엔진 규칙은 엔진 쪽이 바뀌는 중이라 대사·판정은 보지 않고, 끝까지 돌고 기록이 나오는지만 본다.
 */
class EngineHookIntegrationTest {
    /** 엔진 파사드 세션을 잇는 훅. */
    private class EngineHook : SessionHook {
        private var stepFn: ((VehicleSnapshot) -> StepOutput)? = null
        private var inputFn: ((SessionInput) -> StepOutput)? = null
        private var finishFn: ((Boolean) -> SessionRecord)? = null
        var steps = 0
        var inputs = 0
        var firstT: Double? = null
        var record: SessionRecord? = null

        override fun start(sim: BaroSim) {
            val mode = sim.info().mode ?: 1
            val session = BaroEngine.createSession(BaroEngine.buildConfig(mode, null, caps = sim.caps))
            stepFn = session::step
            inputFn = session::input
            finishFn = session::finish
        }

        override fun step(snapshot: VehicleSnapshot): HookStep {
            if (firstT == null) firstT = snapshot.t
            steps++
            return report(requireNotNull(stepFn)(snapshot))
        }

        override fun input(event: SessionInput): HookStep {
            inputs++
            return report(requireNotNull(inputFn)(event))
        }

        override fun finish(arrived: Boolean) {
            record = requireNotNull(finishFn)(arrived)
        }

        private fun report(o: StepOutput) = HookStep(
            speechEnd = o.say.maxOfOrNull { it.t + (it.dur ?: 0.0) },
            arrived = o.hints.arrived,
        )
    }

    private fun runWithEngine(target: SimTarget, parking: ParkingChoice? = null): Pair<ScenarioRun, EngineHook> {
        val hook = EngineHook()
        val run = ScenarioRunner.run(target, ScenarioRunner.Options(skill = DriverSkill.NORMAL, parking = parking, session = hook))
        return run to hook
    }

    @Test
    fun s1bRunsThroughTheEngineToAFinishedRecord() {
        val (run, hook) = runWithEngine(SimTarget.Scenario("S1-B"))
        assertTrue(run.errors.toString(), run.errors.isEmpty())
        assertTrue("도착", run.arrived)
        assertEquals("세션 시작 틱에 sim 시계 0", 0.0, requireNotNull(hook.firstT), 0.0)
        assertEquals("세션 시작 틱부터 끝 틱까지 step", run.sessionSnapshots.size, hook.steps)
        assertNotNull("P에서 마무리", run.finishTick)
        val rec = requireNotNull(hook.record)
        assertTrue("기록 시간 ${rec.durationSec}", rec.durationSec > 0)
    }

    @Test
    fun scenarioInputsReachTheEngineSession() {
        // S6-P: 시나리오의 [다음 단계](pkNext) · S9-R: 운전자 질문(driverAsk)이 엔진 세션 input으로 간다
        val (park, ph) = runWithEngine(SimTarget.Scenario("S6-P"), ParkingChoice("parallel", "R"))
        assertTrue(park.errors.isEmpty())
        assertEquals(1, ph.inputs)
        assertTrue(requireNotNull(ph.record).durationSec > 0)
        val (rules, rh) = runWithEngine(SimTarget.Scenario("S9-R"))
        assertTrue(rules.errors.isEmpty())
        assertEquals(listOf<SessionInput>(SessionInput.DriverAsk), rules.inputs.map { it.second })
        assertEquals(1, rh.inputs)
        assertTrue(requireNotNull(rh.record).durationSec > 0)
    }
}
