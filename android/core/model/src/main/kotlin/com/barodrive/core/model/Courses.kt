package com.barodrive.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * 팀 코스·내비 장소·동네 목록 (설계 8.2, 프로토타입 data/courses.js를 그대로 옮긴 리소스 `courses.json`·`places.json`·`towns.json`).
 * 시뮬레이터(경로)와 앱(CR-1·CR-2·ST-3·PR-1)이 함께 쓴다. 추천 순서는 엔진 파사드 `courses.rank`가 계산한다.
 * 처음 쓸 때 한 번 읽는다. 리소스가 없거나 모양이 틀리면 [IllegalStateException] — 빌드 잘못이라 바로 드러나게.
 */
object Courses {
    /** 팀 코스 19개(own 코스 c1-C·c2-A 포함), 데이터 순서(모드 → 글자). */
    val all: List<Course> by lazy { load("courses.json", ListSerializer(Course.serializer())) }

    /** 내비 장소 8개. */
    val places: List<Place> by lazy { load("places.json", ListSerializer(Place.serializer())) }

    /** 동네 2개('home'·'moved'). */
    val towns: List<Town> by lazy { load("towns.json", ListSerializer(Town.serializer())) }

    /** id로 코스(core.js `courseById`). 없으면 null. */
    fun course(id: String?): Course? = id?.let { k -> all.firstOrNull { it.id == k } }

    /** id로 장소(core.js `placeById`). 없으면 null. */
    fun place(id: String?): Place? = id?.let { k -> places.firstOrNull { it.id == k } }

    /** id로 동네. 없으면 null. */
    fun town(id: String?): Town? = id?.let { k -> towns.firstOrNull { it.id == k } }

    /** 그 모드의 코스(own 포함), 데이터 순서. */
    fun forMode(mode: Int): List<Course> = all.filter { it.mode == mode }

    /** 모드 8 시험장 주변 코스 A~D(결정-63). */
    val testCourses: List<Course> get() = forMode(Mode.LICENSE.no)

    /** 지금 위치(집) 장소. */
    val here: Place? get() = places.firstOrNull { it.here }

    private fun <T> load(name: String, serializer: KSerializer<T>): T {
        val text = Courses::class.java.getResourceAsStream(name)?.use { it.readBytes().decodeToString() }
            ?: error("코스 리소스가 없어요: $name")
        return BaroJson.decodeFromString(serializer, text)
    }
}
