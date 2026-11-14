package com.barodrive.core.sim.route

import com.barodrive.core.model.CautionType
import com.barodrive.core.model.TurnDir

/**
 * 경로 다리 하나 (프로토타입 sim/route.js 머리 주석, 설계 7.4). 거북이 그리기처럼 이어 붙여 경로를 만든다.
 *
 * 값이 null인 칸은 JS의 `undefined`다 — 앞 다리 값을 이어 쓴다([nav]·[Go.limit]·[Go.road]).
 */
sealed interface Leg {
    /** 내비 안내 구간인가. null이면 앞 다리를 따른다(처음은 true). */
    val nav: Boolean?

    /**
     * 직진 [len] m. [limit] 제한속도(null = 앞 값 · 안내 구간이고 앞 값도 없으면 50), [noLimit] = JS `limit: null`(제한 없음).
     * [school]·[jams]는 다리 시작에서 잰 [a, b] m 구간들, [cautions]는 주의 구간. [free] = 내비 안내가 없어도 제한속도를 알림.
     * [cruise] 이 다리 순항 km/h, [grade] 경사 %(기록용), [key] 시나리오 파일이 거리를 재는 이름표(경로에는 쓰지 않음).
     */
    data class Go(
        val len: Double,
        val limit: Int? = null,
        val noLimit: Boolean = false,
        val road: String? = null,
        val school: List<Span> = emptyList(),
        val cautions: List<CautionSpec> = emptyList(),
        val jams: List<Span> = emptyList(),
        override val nav: Boolean? = null,
        val free: Boolean = false,
        val cruise: Double? = null,
        val grade: Double = 0.0,
        val key: String? = null,
    ) : Leg

    /**
     * 회전 [angle]°(기본 90) [dir] 쪽, 반지름 [r](null = 각도별 기본). [tbt]로 TBT 종류를 직접 주고(IC 등),
     * [noTbt]면 각도와 상관없이 TBT를 내지 않는다(JS `tbt: null`). [road] = 회전 뒤 길 이름(IC 이름).
     */
    data class Turn(
        val dir: TurnDir = TurnDir.RIGHT,
        val angle: Double = 90.0,
        val r: Double? = null,
        val tbt: String? = null,
        val noTbt: Boolean = false,
        val road: String? = null,
        override val nav: Boolean? = null,
    ) : Leg

    /** 경유지(번호는 구간마다 1부터). */
    data class Waypoint(val name: String? = null, override val nav: Boolean? = null) : Leg
}

/** 다리 안 [a, b] m 구간(어린이보호구역·정체). */
data class Span(val a: Double, val b: Double)

/** 다리 안 주의 구간: 다리 시작에서 [at] m, 길이 [len] m. */
data class CautionSpec(val at: Double = 0.0, val type: CautionType = CautionType.SLIPPERY_ROAD_ALERT, val len: Double = 0.0)

/**
 * 경로 정의 (route.js `normalize(def)` 결과 모양): 구간마다 다리 목록. 구간이 여럿이면 면허 코스처럼 구간 끝마다
 * 내비가 다음 구간을 다시 찾는다(설계 6.12.3). [segmentNames]는 구간 이름(없으면 null).
 */
data class RouteDef(
    val segments: List<List<Leg>>,
    val routeOption: String? = null,
    val name: String? = null,
    val segmentNames: List<String?> = emptyList(),
    /** 일반 경로 생성기가 만든 경로. */
    val generated: Boolean = false,
) {
    /** 다리가 하나라도 있나(route.js normalize가 null이 아닌가). */
    val usable: Boolean get() = segments.isNotEmpty() && segments.any { it.isNotEmpty() }

    /** 구간이 하나면 그 다리 목록, 여럿이면 null(템플릿 `opts.legs`). */
    val singleLegs: List<Leg>? get() = segments.singleOrNull()

    companion object {
        /** 구간 하나짜리(`{ legs }`). */
        fun legs(legs: List<Leg>, routeOption: String? = null, name: String? = null, generated: Boolean = false) =
            RouteDef(listOf(legs), routeOption, name, emptyList(), generated)
    }
}
