package com.barodrive.core.engine.detect

import com.barodrive.core.engine.SeededRandom
import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.session.CardRequest
import com.barodrive.core.engine.session.SayRequest
import com.barodrive.core.engine.session.SessionCore
import com.barodrive.core.engine.session.WeatherNote
import com.barodrive.core.engine.track.TrackerView
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.HillResult
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.LicenseResult
import com.barodrive.core.model.ParkingResult
import com.barodrive.core.model.ReviewTip
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.Severe
import com.barodrive.core.model.TrackerEvent
import com.barodrive.core.model.WeatherSource
import com.barodrive.core.model.WeatherType
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonElement

/**
 * 검출기 ctx (구현계획 1.8.2, 프로토타입 core.js `makeCtx`). 검출기는 세션을 이것으로만 만진다.
 * 말([say])·카드([card])는 전달 방식과 상관없이 부른다 — S·X는 세션이 막고 '막힌 말'로 기록한다.
 */
class DetectorContext internal constructor(private val core: SessionCore, internal val handle: DetectorHandle) {
    /** 검출기 id('basic.C2'). */
    val id: String get() = handle.id

    /** 지금 세션 시각(sim초). 첫 스냅숏 전이면 0. */
    val t: Double get() = core.now()

    val mode: Int get() = core.cfg.mode

    val cfg: SessionConfig get() = core.cfg

    /** 추적기 현재 값. */
    val track: TrackerView get() = core.tracker.view

    /** 이 검출기 몫 시드 난수(시드 = cfg.seed + ':' + 검출기 id). */
    val rng: SeededRandom by lazy { SeededRandom("${core.cfg.seed}:${handle.id}") }

    /** 화면 힌트(한 번 넣으면 바꿀 때까지 매 출력에). */
    val hints: HintBoard get() = core.hints

    /** 항목의 이 회차 전달 방식. 24개 항목이 아니면(K8 등) 늘 O, 이 회차에 없는 항목은 X. */
    fun deliver(item: String?): Delivery = core.deliver(item)

    /** 날씨를 반영한 기준값(core.js `ctx.th(key, arg)`). [ic]면 IC 기준('C2.ok' 110 …). */
    fun th(key: String, ic: Boolean = false): Double = Thresholds.weathered(key, core.weather.snapshot(), ic)

    /** 날씨 배율을 적용한 제한속도(core.js `ctx.limit`). */
    fun limit(raw: Double?): Int? = Thresholds.effLimit(raw, core.weather.snapshot())

    /** 지금 회차 날씨(사본). */
    fun weather(): SessionWeather = core.weather.snapshot()

    /** 날씨를 합친다: 비·눈이 되면 맑음으로 안 돌아감, severe·cold는 켜기만(core.js `setWeather`). */
    fun setWeather(type: WeatherType? = null, severe: Severe? = null, cold: Boolean = false) = core.weather.merge(type, severe, cold)

    /** 추적기 사건 [type]을 받는다. 돌려준 함수를 부르면 그만 받는다. */
    fun <E : TrackerEvent> on(type: KClass<E>, fn: (E) -> Unit): () -> Unit {
        val cls = type.java
        val wrapper: (TrackerEvent) -> Unit = { e -> if (cls.isInstance(e)) fn(cls.cast(e)) }
        val list = handle.handlers.getOrPut(cls) { ArrayList() }
        list += wrapper
        return { list.remove(wrapper) }
    }

    /** [on]의 줄임: `ctx.on<StartEvent> { … }`. */
    inline fun <reified E : TrackerEvent> on(noinline fn: (E) -> Unit): () -> Unit = on(E::class, fn)

    /** 판정 기록(설계 6.9). 이번 출력 `events`와 회차 기록 `events`에 들어간다. */
    fun emit(item: String?, result: EventResult = EventResult.INFO, data: EventData = EventData.EMPTY): JudgeEvent =
        core.emit(item, result, data)

    /**
     * 말 후보(설계 6.5.1, core.js `ctx.say`). 분류를 빼면 항목 기본 분류. 만료: [expiresIn]초 또는 분류별 기본(안전 10초 — [alive]가 있으면
     * 120초, 사전 안내 30·60초, 칭찬 8초, 교정 15초, 안내 30초). [alive]가 거짓이 되면 버린다(이유 [expireReason] 또는 마지막 막은 규칙).
     * 돌려주는 값: 후보 id, 막혔으면 null.
     */
    fun say(
        key: String,
        item: String? = null,
        vars: Map<String, Any?> = emptyMap(),
        category: Category? = null,
        pairWith: String? = null,
        expiresIn: Double? = null,
        alive: (() -> Boolean)? = null,
        expireReason: BlockReason? = null,
        cardable: Boolean = false,
        card: CardSpec? = null,
        answer: Boolean = false,
        guide: Boolean = false,
        priority: Int? = null,
    ): String? = core.say(
        id,
        SayRequest(key, item, vars, category, pairWith, expiresIn, alive, expireReason, cardable, card, answer, guide, priority),
    )

    /**
     * 신호 대기 카드(설계 6.5.3, core.js `ctx.card`). [key]가 있으면 코치 문장을 그려 [text]로 둔다(맨 앞 카드면 그 문장을 말함 —
     * 요령 카드 빼고, [speak]가 거짓이면 말하지 않음). 돌려주는 값: 카드 id, 막혔으면 null.
     */
    fun card(
        title: String = "",
        item: String? = null,
        key: String? = null,
        vars: Map<String, Any?> = emptyMap(),
        text: String? = null,
        icon: String? = null,
        detail: String = "",
        category: CardCategory = CardCategory.FIX,
        turnId: String? = null,
        speak: Boolean = true,
    ): String? = core.card(id, CardRequest(item, key, vars, text, icon, title, detail, category, turnId, speak))

    /** [sec]초 동안 말하지 않는다(규칙 5, core.js `ctx.silence`). */
    fun silence(sec: Double, reason: String) {
        if (sec.isFinite() && sec > 0) core.addSilence(core.now() + sec, reason)
    }

    /** 위험 회피 급제동 한 줄 → `rec.f2`(core.js `ctx.note('f2', row)`). id는 회차 끝에 붙인다. */
    fun noteF2(row: F2Check) = core.notes.addF2(row)

    /** 리뷰 팁 줄 하나를 덧붙인다(core.js `ctx.note('tips', 값)`). */
    fun noteTip(tip: ReviewTip) = core.notes.addTip(tip)

    /** 리뷰 팁 줄을 바꾼다(core.js `ctx.note('tips', [값…])`). */
    fun noteTips(list: List<ReviewTip>) = core.notes.replaceTips(list)

    /** 회차 날씨 기록에 더할 값(core.js `ctx.note('weather', { by, at, tipCards })`). */
    fun noteWeather(by: WeatherSource?, at: Double?, tipCards: Int) {
        core.notes.weather = WeatherNote(by, at, tipCards)
    }

    /** 언덕 결과 → `rec.hill`. */
    fun noteHill(result: HillResult) {
        core.notes.hill = result
    }

    /** 주차 결과 → `rec.parking`. */
    fun noteParking(result: ParkingResult) {
        core.notes.parking = result
    }

    /** 면허 결과 → `rec.license`. */
    fun noteLicense(result: LicenseResult) {
        core.notes.license = result
    }

    /** 그 밖 메모 → `rec.notes[key]`. */
    fun note(key: String, value: JsonElement) {
        core.notes.rest[key] = value
    }

    /** 말하지 않는 문장(요령·리뷰 줄)을 이 회차 코치로 그린다(순번과 상관없이 [index]번째). 없으면 ''(weather.js `lineOf`). */
    fun line(key: String, index: Int = 0): String = core.lineOrEmpty(key, index)

    /** 같은 묶음 검출기끼리 나누는 세션 값(core.js `SHARED` WeakMap). 처음 부를 때 [create]로 만든다. */
    fun <T : Any> shared(key: KClass<T>, create: () -> T): T = key.java.cast(core.shared.getOrPut(key) { create() })
}

/** 세션 안 검출기 하나: id·사건 처리기·만든 것 (core.js `det`). */
internal class DetectorHandle(val id: String) {
    val handlers = HashMap<Class<*>, MutableList<(TrackerEvent) -> Unit>>()
    var inst: DetectorInstance = DetectorInstance.NONE
}
