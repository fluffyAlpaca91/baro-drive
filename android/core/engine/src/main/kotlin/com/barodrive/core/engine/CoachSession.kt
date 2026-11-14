package com.barodrive.core.engine

import com.barodrive.core.engine.config.ItemResolver
import com.barodrive.core.engine.api.ResolveOptions
import com.barodrive.core.engine.content.LineBook
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.session.DetectorOff
import com.barodrive.core.engine.session.SessionCore
import com.barodrive.core.model.Card
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Hints
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.UtteranceLog
import com.barodrive.core.model.VehicleSnapshot

/**
 * 회차 하나의 엔진 세션 (설계 6.1, 프로토타입 core.js `createSession`이 돌려주는 세션 — '세션' 머리 주석).
 * 흐름: 스냅숏 → 공용 추적기(6.2) → 검출기 → 말 후보·카드 → 전달 관리자(6.5) → [StepOutput]. 끝나면 회차 기록(6.9, 8.1).
 *
 * 스레드 안전하지 않다 — 한 곳(세션 관리자)에서 차례로 부른다. [finish] 뒤의 [step]·[input]은 아무것도 하지 않는다.
 * 세션 설정은 빠진 항목을 그 모드 기본값으로 채워 둔다(core.js `normCfg`).
 */
class CoachSession internal constructor(config: SessionConfig, detectors: List<DetectorDef>, lines: LineBook) {
    private val core = SessionCore(config.normalized(), detectors, lines)

    /** 이 세션 설정(빠진 항목을 채운 것). */
    val config: SessionConfig get() = core.cfg

    /** 스냅숏 하나(설계 6.1.2)를 판정하고 지금 할 말·보일 카드를 낸다. */
    fun step(snapshot: VehicleSnapshot): StepOutput = core.step(snapshot)

    /** 화면에서 온 사건(설계 6.1.1 `session.input`): 소리 끄기·켜기는 세션이, 나머지는 검출기가 받는다. */
    fun input(event: SessionInput): StepOutput = core.input(event)

    /**
     * 회차를 끝내고 기록을 만든다(설계 6.9, 8.1). [arrived]를 주지 않으면 내비 도착 사건이 있었는지를 쓴다.
     * 리뷰 요약(review.summarize)을 합치고 자율 모드면 점수를 붙인다. 두 번 불러도 같은 기록이다.
     */
    fun finish(arrived: Boolean? = null): SessionRecord = core.finish(arrived)

    /** 항목의 이 회차 전달 방식(24개 항목이 아니면 O, 이 회차에 없는 항목은 X). */
    fun deliver(item: String?): Delivery = core.deliver(item)

    /** 이 세션 코치·호칭·순번으로 대사를 그린다(core.js `session.renderLine`). [peek]이면 순번을 넘기지 않는다. */
    fun renderLine(key: String, vars: Map<String, Any?> = emptyMap(), peek: Boolean = false): String = core.lines.render(key, vars, peek)

    /** 켜진 검출기 id와 꺼진 검출기·이유(core.js `info()`). */
    fun info(): SessionInfo = SessionInfo(core.dets.map { it.id }, core.off.toList())

    /** 마지막 스냅숏 시각. 첫 스냅숏 전이면 null. */
    val t: Double? get() = core.t
    val muted: Boolean get() = core.muted

    /** 내비 도착 사건이 있었나. */
    val arrived: Boolean get() = core.arrivedNav

    /** 지금 보이는 신호 대기 카드. */
    val cards: List<Card> get() = core.cards.visibleView()
    val hints: Hints get() = core.hints.snapshot()
    val weather: SessionWeather get() = core.weather.snapshot()

    /** 지금까지의 판정(F2·뺀 회전 그대로 — 기록 events와 다를 수 있음). */
    val events: List<JudgeEvent> get() = core.events.toList()

    /** 지금까지의 말 기록(말한 것·막힌 것). */
    val utterances: List<UtteranceLog> get() = core.utterances.toList()
    val waiting: Boolean get() = core.tracker.view.waiting
    val finished: Boolean get() = core.finished
    val record: SessionRecord? get() = core.record

    /** 검출기·대사 오류(프로토타입은 console.error). 비어 있어야 정상이다. */
    val errors: List<String> get() = core.errors.toList()
}

/** 켜진 검출기와 꺼진 검출기 (core.js `session.info()`). */
data class SessionInfo(val detectors: List<String>, val off: List<DetectorOff>)

/** 빠진 항목을 그 모드 기본값(이 회차 번호 기준)으로 채운다(core.js `normCfg`). 넘긴 값이 앞선다. */
internal fun SessionConfig.normalized(): SessionConfig {
    val base = ItemResolver.resolve(mode, null, ResolveOptions(noInMode = sessionNoInMode), flags.extraSafety).mapValues { it.value.value }
    return copy(items = base + items)
}
