package com.barodrive.app.session

import com.barodrive.core.engine.CoachSession
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.ResolveOptions
import com.barodrive.core.model.Coach
import com.barodrive.core.model.CourseDoneUpdate
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ProgressApplied
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TodayTask
import com.barodrive.core.model.VehicleSnapshot
import javax.inject.Inject

/**
 * 세션 관리자가 쓰는 엔진 기능 (session.js가 부르는 `BaroEngine.*`만). 앱은 엔진을 파사드([BaroEngine])로만 부른다 —
 * [FacadeSessionEngine]이 그 구현이고, 상태 기계 테스트는 가짜 엔진을 넣는다.
 */
interface SessionEngine {
    /** core.js `buildConfig(mode, profile, prep, panelState)`. */
    fun buildConfig(mode: Int, profile: Profile, prep: PrepSession, caps: SignalCaps): SessionConfig

    /** core.js `createSession(cfg)`. */
    fun createSession(cfg: SessionConfig): EngineSession

    /** review.js `applyProgress(profile, rec, { now })`. */
    fun applyProgress(profile: Profile, record: SessionRecord, now: String?): ProgressApplied

    /** license.js `applyCourseDone(profile, rec)` — 모드 8 회차 끝 완주 코스. 프로필은 고치지 않는다(결과 `after`를 부르는 쪽이 넣음). */
    fun applyCourseDone(profile: Profile, record: SessionRecord): CourseDoneUpdate

    /** review.js `todayTasks(profile, mode)`. */
    fun todayTasks(profile: Profile, mode: Int): List<TodayTask>

    /** core.js `itemsOffByCaps(caps)`. */
    fun itemsOffByCaps(caps: SignalCaps): List<String>

    /** 과제의 항목(data/tasks.js `items`). 모르는 과제면 []. */
    fun taskItems(taskId: String): List<String>

    /** 준비 세션의 전달 방식(core.js `resolveItems(mode, profile, { presetId, presetItems, noInMode })[item].value`). 없으면 null. */
    fun resolveItem(item: String, prep: PrepSession, profile: Profile): Delivery?

    /** core.js `renderLine(key, coach, vars, honorific, { peek })`. 키·문장이 없으면 예외. */
    fun render(key: String, coach: Coach, vars: Map<String, Any?>, honorific: Honorific?, peek: Boolean = false): String

    /** 개수 말(copy.js `numWord`: 1 → '한' …). */
    fun numWord(n: Int): String
}

/** 엔진 세션 하나 (core.js 세션: `step`·`input`·`finish`·`deliver`·`cfg`). */
interface EngineSession {
    val config: SessionConfig

    fun step(snapshot: VehicleSnapshot): StepOutput

    fun input(event: SessionInput): StepOutput

    fun finish(arrived: Boolean): SessionRecord

    /** 이번 회차의 그 항목 전달 방식(core.js `ses.deliver(item)`). */
    fun deliver(item: String): Delivery? = config.items[item]
}

/** 파사드로 부르는 진짜 엔진. 상태가 없다. */
class FacadeSessionEngine @Inject constructor() : SessionEngine {
    override fun buildConfig(mode: Int, profile: Profile, prep: PrepSession, caps: SignalCaps): SessionConfig =
        BaroEngine.buildConfig(mode, profile, prep, caps)

    override fun createSession(cfg: SessionConfig): EngineSession = FacadeSession(BaroEngine.createSession(cfg))

    override fun applyProgress(profile: Profile, record: SessionRecord, now: String?): ProgressApplied =
        BaroEngine.review.applyProgress(profile, record, now)

    override fun applyCourseDone(profile: Profile, record: SessionRecord): CourseDoneUpdate =
        BaroEngine.license.applyCourseDone(profile, record)

    override fun todayTasks(profile: Profile, mode: Int): List<TodayTask> = BaroEngine.review.todayTasks(profile, mode)

    override fun itemsOffByCaps(caps: SignalCaps): List<String> = BaroEngine.itemsOffByCaps(caps)

    override fun taskItems(taskId: String): List<String> = BaroEngine.taskDef(taskId)?.items.orEmpty()

    override fun resolveItem(item: String, prep: PrepSession, profile: Profile): Delivery? =
        BaroEngine.resolveItems(prep.mode, profile, ResolveOptions(presetId = prep.presetId, presetItems = prep.presetItems, noInMode = prep.noInMode))[item]?.value

    override fun render(key: String, coach: Coach, vars: Map<String, Any?>, honorific: Honorific?, peek: Boolean): String =
        BaroEngine.lines.render(key, coach, vars, honorific, com.barodrive.core.engine.api.RenderOptions(peek = peek))

    override fun numWord(n: Int): String = com.barodrive.core.engine.content.Words.numWord(n)

    private class FacadeSession(private val session: CoachSession) : EngineSession {
        override val config: SessionConfig get() = session.config

        override fun step(snapshot: VehicleSnapshot): StepOutput = session.step(snapshot)

        override fun input(event: SessionInput): StepOutput = session.input(event)

        override fun finish(arrived: Boolean): SessionRecord = session.finish(arrived)
    }
}
