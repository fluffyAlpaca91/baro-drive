package com.barodrive.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 팀 코스 (설계 8.2, 프로토타입 data/courses.js `courses[]`). 값은 목업 s11 그대로, 지명은 가상의 동네(결정-62).
 * 목록은 [Courses.all](리소스 `courses.json`).
 */
@Serializable
data class Course(
    /** 'c2-B'. */
    val id: String,
    val mode: Int,
    /** 코스 글자 A~D. 모드 8은 진행 글자(`profile.license.coursesDone`)와 같다. */
    val letter: String,
    val name: String,
    /** 흉내 낸 `requestTrip` 거리 km. */
    val km: Double,
    /** 흉내 낸 예상 시간, 분. */
    val min: Int,
    /** 기본 혼잡 0 원활 · 1 보통 · 2 막힘(시연 패널 '혼잡도 다시 받기' 시드로 바뀜). */
    val congestion: Int,
    /** 경로 옵션 '편안한 길'·'추천'·'고속도로 우선'. */
    val routeOption: String,
    /** 동네 id('home'·'moved'). */
    val town: String = "home",
    /** 지금 위치(우리 동네)에서 직선거리 km — '가까운 3곳'을 고르는 데만 쓴다. */
    val straightKm: Double,
    /** 줄거리. */
    val story: String = "",
    /** 내 코스로만 쓰는 코스(c1-C·c2-A) — 프로필 `myCourses`에 courseRef로 있을 때만 나온다. */
    val own: Boolean = false,
    /** 모드 8: 구간 수. */
    val segments: Int? = null,
    /** 모드 8: 확인일 'YYYY-MM-DD'. */
    val checkedAt: String? = null,
    /** 모드 8: 고속도로 없음. */
    val noHighway: Boolean = false,
) {
    /** 혼잡 글자('원활'·'보통'·'막힘'). */
    val congestionLabel: String get() = CONGESTION_LABELS.getOrElse(congestion) { CONGESTION_LABELS[0] }

    companion object {
        /** 혼잡 0·1·2 글자(copy.js `congestion`). */
        val CONGESTION_LABELS: List<String> = listOf("원활", "보통", "막힘")
    }
}

/**
 * 내비 장소 (설계 7.5·8.2, 프로토타입 data/courses.js `places[]`). 즐겨찾기 = 집·회사·마트·어린이집·운전면허시험장,
 * 최근 목적지 = 시민체육관·동네 병원·역 앞 카페. 목록은 [Courses.places](리소스 `places.json`).
 */
@Serializable
data class Place(
    /** 'mart'. */
    val id: String,
    val name: String,
    val list: PlaceList,
    val km: Double,
    val min: Int,
    val congestion: Int = 0,
    val addr: String = "",
    /** 집에서 직선거리 km. */
    val straightKm: Double,
    /** 지금 위치(집). 코스로 만들 수 없다. */
    val here: Boolean = false,
    /** 언덕 위. */
    val hill: Boolean = false,
)

/**
 * 진짜 차 내비의 장소 (NaviHelper 즐겨찾기 `BookmarkInfo` · 최근 목적지 `RecentDestinationInfo`, API-레퍼런스 4.5, 설계 8.2 '좌표는 차 안에만').
 * 팀 장소([Place] — 프로토타입의 가상 장소, 좌표 없음)와 달리 좌표가 있어 내비에 그대로 안내를 부탁할 수 있다(`requestRoute`).
 * 프로토타입에는 없는 것(진짜 차 몫)이라 이것을 담는 칸은 비어 있으면 JSON에 쓰지 않는다 — 프로토타입 JSON과 같게.
 */
@Serializable
data class NaviPlace(
    val name: String,
    /** 위도 °. */
    val lat: Double,
    /** 경도 °. */
    val lng: Double,
    val address: String = "",
    /** 내비가 준 POI id(42dot 등) — 안내를 부탁할 때 그대로 돌려준다. 없으면 빈 글자. */
    val poiId: String = "",
    val poiSubId: String = "",
    val poiProvider: String = "",
) {
    /** 같은 장소인지 가리는 열쇠: POI id가 있으면 그것, 없으면 좌표. */
    val key: String get() = poiId.ifEmpty { "$lat,$lng" }
}

/** 내비 장소 목록 (프로토타입 'bookmark' 즐겨찾기 · 'recent' 최근 목적지). */
@Serializable
enum class PlaceList(val label: String) {
    @SerialName("bookmark")
    BOOKMARK("즐겨찾기"),

    @SerialName("recent")
    RECENT("최근 목적지"),
}

/** 동네 (시연 패널 '동네', 설계 8.2). 목록은 [Courses.towns](리소스 `towns.json`). */
@Serializable
data class Town(
    /** 'home' 우리 동네 · 'moved' 이사 간 동네. */
    val id: String,
    val name: String,
    /** 팀 코스가 있는 동네. */
    val hasCourses: Boolean,
)

/** 코스 출처 (추천 줄 `source`·내 코스 `source`): 팀 코스 · 연습한 길(별) · 즐겨찾기 · 최근 목적지. [label]은 mycourses.js. */
@Serializable
enum class CourseSource(val label: String) {
    @SerialName("team")
    TEAM("팀 코스"),

    @SerialName("saved")
    SAVED("연습한 길"),

    @SerialName("bookmark")
    BOOKMARK("즐겨찾기"),

    @SerialName("recent")
    RECENT("최근 목적지"),
}

/**
 * CR-1 코스 추천 결과 (설계 6.10.6, 6.1.1 `courses.rank`, 프로토타입 core.js `rankCourses`가 돌려주는 값).
 * 보이는 순서는 코스 글자 순. 가장 낮은 점수(예상 시간 + 혼잡 × 6)가 '덜 막혀요'([bestId])이고 처음 선택.
 */
@Serializable
data class CourseRanking(
    val mode: Int,
    val town: String = "home",
    /** 혼잡도를 받았나(못 받으면 가까운 순, 결정-81). */
    val tripOk: Boolean = true,
    /** 자율 모드(코스 추천 없음). */
    val autonomous: Boolean = false,
    /** 코스 종류 줄(copy.js `courseKinds[mode].kind`). */
    val kind: String = "",
    /** 안내 줄(copy.js `courseKinds[mode].note`). */
    val note: String = "",
    val list: List<RankedCourse> = emptyList(),
    val bestId: String? = null,
    val selectedId: String? = null,
    val empty: Boolean = true,
    /** 코치 한마디: { key: 'p.course', vars: { X } } · 코스가 없으면 'p.course.none' · 혼잡도를 못 받으면 null. */
    val line: LineRef? = null,
)

/** 추천 줄 하나 (rankCourses 항목). 팀 코스·내 코스·장소 코스가 같은 모양이다. */
@Serializable
data class RankedCourse(
    /** 줄 id(팀 코스 id 또는 내 코스 id). */
    val id: String,
    val courseId: String? = null,
    val placeId: String? = null,
    val letter: String = "",
    val name: String = "",
    val km: Double = 0.0,
    val min: Int = 0,
    /** 혼잡 0~2. 혼잡도를 못 받으면 null. */
    val congestion: Int? = null,
    val congestionLabel: String? = null,
    val routeOption: String = "",
    val straightKm: Double = 0.0,
    /** 순위 점수 = 분 + 혼잡 × 6. 혼잡도를 못 받으면 null. */
    val score: Double? = null,
    /** 내 코스 줄. */
    val mine: Boolean = false,
    val myCourseId: String? = null,
    val source: CourseSource = CourseSource.TEAM,
    val own: Boolean = false,
    /** 모드 8: 이미 완주한 코스. */
    val ran: Boolean = false,
    /** '덜 막혀요'. */
    val best: Boolean = false,
    val segments: Int? = null,
    val checkedAt: String? = null,
    /** 진짜 차 내비에서 가져온 내 코스의 장소(좌표). 거리·시간·혼잡은 모른다(0 · null). */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val navi: NaviPlace? = null,
)

/** 대사 고르기: 대사 키와 변수 (엔진 파사드 `lines.render`에 넘긴다). */
@Serializable
data class LineRef(
    val key: String,
    val vars: JsonObject = JsonObject(emptyMap()),
)
