package com.barodrive.app.feature.course

import com.barodrive.core.model.Coach
import com.barodrive.core.model.CourseRanking

/** CR-1 목록 자리 상태 (course.js `body.dataset.state`): 혼잡도 받는 중 · 목록 · 근처 코스 없음 · 자율(추천 없음). */
enum class CourseBodyState { LOADING, LIST, EMPTY, AUTONOMOUS }

/** 코스 카드 하나 (course.js `courseCard`). */
data class CourseCardUi(
    val id: String,
    val letter: String,
    val name: String,
    val km: Double,
    val min: Int,
    /** 혼잡 0~2(혼잡도를 못 받으면 null). */
    val congestion: Int?,
    val congestionLabel: String?,
    /** '덜 막혀요'. */
    val best: Boolean,
    /** 내 코스 줄(별 켜짐). */
    val mine: Boolean,
    /** 모드 8: 완주한 코스('달렸어요'). */
    val ran: Boolean,
    /** 모드 8: 데이터 확인일·구간 수. */
    val checkedAt: String?,
    val segments: Int?,
    val selected: Boolean,
    /** 진짜 차 내비에서 가져온 내 코스 — 거리·시간을 몰라 그 자리에 [address]를 쓴다. */
    val navi: Boolean = false,
    val address: String = "",
)

/** CR-1 연습 코스 추천 화면 값 (course.js 화면 상태 `st` + 그린 값). */
data class CourseUiState(
    val loaded: Boolean = false,
    /** 지금 고른 모드 1~8. */
    val mode: Int = 1,
    /** 들어온 모드(그 모드로 들어온 게 아니면 6·8 칩은 시작 화면으로 보낸다). */
    val entryMode: Int = 1,
    val modes: List<Int> = (1..8).toList(),
    /** 잠긴 단계 모드 → 통과해야 할 단계. */
    val locked: Map<Int, Int> = emptyMap(),
    val body: CourseBodyState = CourseBodyState.LOADING,
    val coach: Coach = Coach.DAD,
    /** 코치 한마디(p.course — '지금은 B 코스가 덜 막혀.'). 혼잡도를 못 받았거나 '덜 막혀요'가 없으면 null. */
    val coachLine: String? = null,
    /** 혼잡도를 받았나(못 받으면 가까운 순 안내 줄, 결정-81). */
    val tripOk: Boolean = true,
    val cards: List<CourseCardUi> = emptyList(),
    /** 모드 8 진행: 완주한 코스 수 / 코스 수. */
    val licenseDone: Int = 0,
    val licenseTotal: Int = 0,
    /** 모드 8: 연습면허 유효기간이 지남 → [X 코스로 연습하기] 막힘. */
    val expired: Boolean = false,
) {
    val selected: CourseCardUi? get() = cards.firstOrNull { it.selected }

    /** [코스 없이 연습하기](빈 상태, 결정-85) — 주차·언덕은 코스 없이 하지 않는다. */
    val canStartWithoutCourse: Boolean get() = body == CourseBodyState.EMPTY && mode != 6 && mode != 7

    companion object {
        /** 추천 결과 → 카드(course.js `list`). [selectedId]가 목록에 없으면 엔진의 처음 선택. */
        fun cards(res: CourseRanking, selectedId: String?): List<CourseCardUi> {
            val sel = selectedId?.takeIf { id -> res.list.any { it.id == id } } ?: res.selectedId
            return res.list.map { c ->
                CourseCardUi(
                    id = c.id, letter = c.letter, name = c.name, km = c.km, min = c.min, congestion = c.congestion,
                    congestionLabel = c.congestionLabel, best = c.best, mine = c.mine, ran = c.ran, checkedAt = c.checkedAt,
                    segments = c.segments, selected = c.id == sel, navi = c.navi != null, address = c.navi?.address.orEmpty(),
                )
            }
        }
    }
}
