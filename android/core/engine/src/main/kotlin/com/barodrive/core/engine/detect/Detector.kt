package com.barodrive.core.engine.detect

import com.barodrive.core.model.Delivery
import com.barodrive.core.model.HillHint
import com.barodrive.core.model.Hints
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.LicenseHint
import com.barodrive.core.model.ParkingHint
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WeatherBadge

/**
 * 검출기 정의 (프로토타입 `Baro.detector({ id, items, needs, modes, create })`, 구현계획 1.8.2, 설계 7.8).
 * 세션이 만들 때 [modes]에 그 모드가 없거나, [needs] 신호 중 하나라도 못 읽거나, [items]가 하나도 이 회차 항목에 없으면
 * 만들지 않는다(꺼진 이유 'mode' · 'caps:신호' · 'items'). [items]가 비면 24개 항목이 아닌 검출기(K8 답·E3 등 — 늘 켬).
 * [create]는 세션마다 한 번 불러 상태를 들고 있는 [DetectorInstance]를 만든다.
 */
class DetectorDef(
    val id: String,
    val items: List<String> = emptyList(),
    val needs: List<SignalKey> = emptyList(),
    /** 도는 모드. null이면 모든 모드. */
    val modes: List<Int>? = null,
    val create: (DetectorContext) -> DetectorInstance,
)

/** 검출기 한 세션 몫 (프로토타입 `create(ctx)`가 돌려주는 `{ step?, finish? }`). */
interface DetectorInstance {
    /** 스냅숏마다(추적기 사건을 다 나눈 뒤, 검출기 등록 순서로). */
    fun step(snap: VehicleSnapshot) = Unit

    /** 회차 끝(추적기가 열린 창을 닫은 뒤). 기록 지표를 채우거나 판정을 더할 수 있다. */
    fun finish(rec: RecordDraft) = Unit

    companion object {
        /** step·finish가 없는 검출기(사건 처리기만 둠). */
        val NONE: DetectorInstance = object : DetectorInstance {}
    }
}

/**
 * 회차 끝에 검출기가 보는 기록 (core.js finish의 `rec` — 검출기 finish(rec)가 읽고 고치는 부분).
 * [events]는 지금까지의 판정(출발 전 사건·F2 포함, 뺀 회전 바꾸기 전), [metrics]는 검출기가 채우는 K4 재료.
 */
class RecordDraft internal constructor(
    val mode: Int,
    val items: Map<String, Delivery>,
    private val eventSource: () -> List<JudgeEvent>,
) {
    val events: List<JudgeEvent> get() = eventSource()
    val metrics = MetricsDraft()
}

/** 검출기가 채우는 회차 지표 (설계 8.1 `metrics`, 결정사항 E T2a-6). */
class MetricsDraft internal constructor() {
    var c2Avg: Double? = null
    var c2N: Int? = null
    var stopTotal: Int? = null
    var stopFail: Int? = null
    var stopFailRate: Double? = null
    var g1: Double? = null
    var g1Windows: Int? = null
    var g1Better: Double? = null
}

/**
 * 화면 힌트 판 (core.js `S.hints`, 검출기 `ctx.hint(이름, 값)`). 한 번 넣은 값은 바꿀 때까지 매 출력에 실린다.
 */
class HintBoard internal constructor() {
    var hero: String? = null
    var badge: WeatherBadge? = null

    /** 내비 도착 전이어도 다음 P에서 회차를 끝낸다(주차 안내 끝·언덕 주차 P — 결정 T5b-Q1). */
    var arrived: Boolean = false
    var hill: HillHint? = null
    var parking: ParkingHint? = null
    var license: LicenseHint? = null

    internal fun snapshot(): Hints = Hints(hero = hero, badge = badge, arrived = arrived, hill = hill, parking = parking, license = license)
}

/** 만료된 교정을 카드로 바꿀 때의 카드 모양 (core.js say의 `card: { icon, title, detail }`). */
class CardSpec(val title: String = "", val detail: String = "", val icon: String? = null)
