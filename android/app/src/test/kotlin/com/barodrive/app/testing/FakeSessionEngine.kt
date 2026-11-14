package com.barodrive.app.testing

import com.barodrive.app.session.EngineSession
import com.barodrive.app.session.SessionEngine
import com.barodrive.core.model.Coach
import com.barodrive.core.model.CourseDoneUpdate
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ProgressApplied
import com.barodrive.core.model.ProgressResult
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TodayTask
import com.barodrive.core.model.VehicleSnapshot

/**
 * 상태 기계 테스트용 엔진. 스냅숏마다 [script]가 출력을 정하고, [finish]는 받은 표본으로 간단한 기록을 만든다.
 * [passes]면 진행 반영이 '지금 단계 통과'를 낸다. 대사는 'key|vars' 모양으로 그린다.
 */
class FakeSessionEngine(
    var script: (VehicleSnapshot) -> StepOutput = { StepOutput(t = it.t) },
    var items: Map<String, Delivery> = emptyMap(),
    var today: List<TodayTask> = emptyList(),
    var passes: Boolean = false,
) : SessionEngine {
    val sessions = mutableListOf<Session>()

    override fun buildConfig(mode: Int, profile: Profile, prep: PrepSession, caps: SignalCaps): SessionConfig = SessionConfig(
        mode = mode, sessionNoInMode = prep.noInMode ?: profile.nextNoInMode(mode), items = items, caps = caps,
        preEvents = prep.preEvents, startedAt = prep.startedAt, courseId = prep.courseId, placeId = prep.placeId,
    )

    override fun createSession(cfg: SessionConfig): EngineSession = Session(cfg, script).also { sessions += it }

    override fun applyProgress(profile: Profile, record: SessionRecord, now: String?): ProgressApplied {
        val count = profile.sessionCount + (record.mode to (profile.sessionCount[record.mode] ?: 0) + 1)
        val result = ProgressResult(mode = record.mode, stage = record.stage, passed = passes && record.stage != null, nextStage = record.stage?.plus(1))
        return ProgressApplied(profile.copy(sessionCount = count), record.copy(progress = result), result)
    }

    /** 도착한 모드 8 회차면 [courseLetter]를 완주한 것으로. */
    var courseLetter = "B"

    override fun applyCourseDone(profile: Profile, record: SessionRecord): CourseDoneUpdate {
        val before = profile.license.coursesDone
        val after = if (record.arrived) (before + courseLetter).distinct().sorted() else before
        return CourseDoneUpdate(before = before, after = after, added = (after - before.toSet()).firstOrNull())
    }

    override fun todayTasks(profile: Profile, mode: Int): List<TodayTask> = today

    override fun itemsOffByCaps(caps: SignalCaps): List<String> = emptyList()

    override fun taskItems(taskId: String): List<String> = emptyList()

    override fun resolveItem(item: String, prep: PrepSession, profile: Profile): Delivery? = items[item]

    override fun render(key: String, coach: Coach, vars: Map<String, Any?>, honorific: Honorific?, peek: Boolean): String =
        if (vars.isEmpty()) key else "$key|" + vars.entries.joinToString(",") { "${it.key}=${it.value}" }

    override fun numWord(n: Int): String = listOf("영", "한", "두", "세").getOrElse(n) { n.toString() }

    class Session(override val config: SessionConfig, private val script: (VehicleSnapshot) -> StepOutput) : EngineSession {
        val steps = mutableListOf<VehicleSnapshot>()
        val inputs = mutableListOf<SessionInput>()
        var finishedWith: Boolean? = null

        override fun step(snapshot: VehicleSnapshot): StepOutput {
            steps += snapshot
            return script(snapshot)
        }

        override fun input(event: SessionInput): StepOutput {
            inputs += event
            return StepOutput(muted = event == SessionInput.Mute)
        }

        override fun finish(arrived: Boolean): SessionRecord {
            finishedWith = arrived
            val first = steps.firstOrNull()?.t ?: 0.0
            val last = steps.lastOrNull()?.t ?: 0.0
            return SessionRecord(
                mode = config.mode, stage = config.stage, noInMode = config.sessionNoInMode, startedAt = config.startedAt,
                durationSec = last - first, arrived = arrived, events = config.preEvents.map { it.copy(pre = true) },
            )
        }
    }
}
