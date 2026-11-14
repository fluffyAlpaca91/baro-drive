package com.barodrive.core.engine.detect.parking

import com.barodrive.core.engine.content.ParkingChoice
import com.barodrive.core.engine.content.ParkingGuide
import com.barodrive.core.engine.content.ParkingSignal
import com.barodrive.core.engine.content.ParkingStep
import com.barodrive.core.engine.content.ParkingVisual as GuideVisual
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.engine.round1
import com.barodrive.core.model.Gear
import com.barodrive.core.model.ParkingHandbrake
import com.barodrive.core.model.ParkingHint
import com.barodrive.core.model.ParkingResult
import com.barodrive.core.model.ParkingStepLog
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.ParkingVisual
import com.barodrive.core.model.ParkingWheel
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 단계를 신호로 넘김 (parking.js `via` 'signal'). */
internal const val VIA_SIGNAL = "signal"

/** 단계를 화면 누름으로 넘김 — 비상등 단계, 또는 그 신호를 못 읽음(설계 7.8) (parking.js `via` 'press'). */
internal const val VIA_PRESS = "press"

/**
 * 주차 검출기 셋(PK1·PK2·PK3)이 나눠 쓰는 회차 상태 (프로토타입 detectors/parking.js `shared(ctx)`).
 * 종류·방향은 cfg.parking → cfg.preEvents의 PK1 setup 사건 → 평행 · 오른쪽 차례로 정한다(결정사항 E T5b-Q3·T5b-3).
 * 회차 결과([result])는 parking.js `s.result`처럼 상태에서 바로 만든다.
 */
internal class ParkingState(cfg: SessionConfig) {
    val choice: ParkingChoice = setupOf(cfg)
    val steps: List<ParkingStep> = ParkingGuide.steps(choice.type, choice.side)

    /** 단계마다 넘기는 방법: 비상등 단계·못 읽는 신호는 화면 누름. */
    val via: List<String> = steps.map { st ->
        val blind = ParkingGuide.signals.getValue(st.signal).caps.any { k -> SignalKey.entries.firstOrNull { it.key == k }?.let { !cfg.caps[it] } == true }
        if (st.signal == ParkingSignal.PRESS || blind) VIA_PRESS else VIA_SIGNAL
    }

    /** 지금 단계 index(0부터). */
    var cur = 0
    var started = false
    var startT: Double? = null
    var stepT: Double? = null

    /** 이 단계에 들어온 뒤 차가 움직였나('멈춤' 신호). */
    var moved = false

    /** '똑바로' 단계: |핸들| ≤ 3°가 시작된 때. */
    var straightSince: Double? = null
    var done = false
    var doneT: Double? = null
    var pressed = 0
    var pk2 = 0
    var pk2LastT: Double? = null

    /** 마지막으로 P에 들어간 때(사이드브레이크 PB2 판정을 고를 때). */
    var lastPT: Double? = null
    var prevGear: Gear = cfg.startGear
    var snap: VehicleSnapshot? = null
    var wheelJudged = false

    /** PK1이 켜져 회차 결과를 메모함(parking.js PK1 create의 `ctx.note('parking', …)` — PK1이 없으면 rec.parking도 없음). */
    var noted = false

    // ---- 회차 결과(rec.parking) 중 상태에서 바로 나오지 않는 칸 ----
    var sec = 0.0
    val logs = ArrayList<ParkingStepLog>()
    var wheel: ParkingWheel? = null
    var handbrake: ParkingHandbrake? = null
    var model: String? = null

    /** 지금 단계 번호(1부터). */
    val stepNo: Int get() = steps[cur].n

    /** 시작 뒤 sim초(round1). 시작 전이면 0 (parking.js `since`). */
    fun since(t: Double): Double = startT?.let { round1(t - it) } ?: 0.0

    /** 화면 힌트 hints.parking (parking.js `view`) — 단계·결과가 바뀔 때마다 새 값. */
    fun view(): ParkingHint {
        val st = steps[cur]
        val v = via[cur]
        return ParkingHint(
            type = typeOf(choice.type), side = TurnDir.fromCode(choice.side) ?: TurnDir.RIGHT, key = choice.key, total = steps.size,
            step = st.n, index = cur, last = cur == steps.size - 1, done = done, line = st.key,
            vars = JsonObject(st.vars.mapValues { JsonPrimitive(it.value) }), text = st.text, signal = st.signal.id,
            dir = TurnDir.fromCode(st.dir), via = v,
            signalText = if (v == VIA_PRESS && st.signal != ParkingSignal.PRESS) ParkingGuide.pressText else st.signalText,
            visual = st.visual.toModel(), tip = st.tip, pk2 = pk2, startedAt = startT, stepAt = stepT, doneAt = doneT,
        )
    }

    /** 회차 결과 rec.parking (parking.js `s.result`, 결정사항 E T5b-5). */
    fun result(): ParkingResult = ParkingResult(
        type = typeOf(choice.type), side = TurnDir.fromCode(choice.side) ?: TurnDir.RIGHT, key = choice.key, model = model, sec = sec,
        total = steps.size, reached = if (done) steps.size else stepNo, done = done, pressed = pressed, steps = logs.toList(), pk2 = pk2,
        wheel = wheel, handbrake = handbrake,
    )

    /** PK1이 켜진 회차면 지금 결과를 rec.parking으로 메모한다(PK1·PK3 finish가 부름 — 마지막 것이 남는다). */
    fun note(ctx: DetectorContext) {
        if (noted) ctx.noteParking(result())
    }

    private companion object {
        fun setupOf(cfg: SessionConfig): ParkingChoice {
            cfg.parking?.let { return ParkingGuide.norm(it.type.code, it.side.code) }
            for (e in cfg.preEvents.asReversed()) {
                if (e.item == "PK1" && e.data.setup == true) return ParkingGuide.norm(e.data.string("type"), e.data.string("side"))
            }
            return ParkingGuide.norm(null, null)
        }

        fun typeOf(code: String): ParkingType = ParkingType.entries.firstOrNull { it.code == code } ?: ParkingType.PARALLEL
    }
}

/** 단계 그림(내용 표) → 힌트 값. */
private fun GuideVisual.toModel(): ParkingVisual = ParkingVisual(
    kind = kind.name.lowercase(), rot = rot?.toDouble(), gear = gear?.let { g -> Gear.entries.firstOrNull { it.name == g } }, label = label,
)

/** 세션의 주차 상태(처음 부른 검출기가 만든다). */
internal fun DetectorContext.parking(): ParkingState = shared(ParkingState::class) { ParkingState(cfg) }
