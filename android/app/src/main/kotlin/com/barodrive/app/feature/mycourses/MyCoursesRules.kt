package com.barodrive.app.feature.mycourses

import com.barodrive.app.feature.course.CourseRules
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.Courses
import com.barodrive.core.model.Mode
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.NaviPlace
import com.barodrive.core.model.Place
import com.barodrive.core.model.PlaceList
import com.barodrive.core.model.Profile

/** 내 코스 줄 하나 (mycourses.js `groups().items[]`). */
data class MyCourseItem(
    val course: MyCourse,
    val name: String,
    /** '연습한 길' · '즐겨찾기' · '최근 목적지'. */
    val sourceLabel: String,
    val km: Double?,
    /** 지금 동네가 아니면 그 동네 이름. */
    val townName: String?,
)

/** 모드별 묶음 ('2단계 · 동네 골목 다니기'). */
data class MyCourseGroup(val mode: Int, val title: String, val items: List<MyCourseItem>)

/** 가져오기·모드 바꾸기가 안 된 까닭 (mycourses.js `error`). */
enum class MyCourseError { PLACE, HERE, MODE, DUP, MISSING, SAME }

/**
 * 내 코스 계산 (프로토타입 mycourses.js `BaroApp.myCourses` — groups·addPlace·setMode·remove·errorText, 설계 4.2 ST-3·CR-2, 8.1).
 * 순수 함수 — 바뀐 프로필을 돌려준다. 진짜 차 내비 장소(좌표째, [addNaviPlace])는 프로토타입에 없는 것으로, 팀 장소와 같은 규칙을 따른다.
 */
object MyCoursesRules {
    /** 가져오기·모드 바꾸기 모드(설계 4.2 CR-2 '1~8 중 코스가 있는 모드' — 5 자율은 코스 없음). */
    val IMPORT_MODES: List<Int> = listOf(1, 2, 3, 4, 6, 7, 8)

    private val TOWN_FALLBACK = mapOf("home" to "우리 동네", "moved" to "이사 간 동네")

    /** 출처 이름. */
    fun sourceLabel(mc: MyCourse): String = when (mc.source) {
        CourseSource.BOOKMARK -> CourseSource.BOOKMARK.label
        CourseSource.RECENT -> CourseSource.RECENT.label
        else -> CourseSource.SAVED.label
    }

    fun modeLabel(m: Int): String = Mode.ofOrNull(m)?.label ?: m.toString()

    fun modeShort(m: Int): String = Mode.ofOrNull(m)?.short ?: m.toString()

    fun townName(id: String?): String = Courses.town(id)?.name ?: TOWN_FALLBACK[id] ?: id.orEmpty()

    /** 내비 장소 목록(즐겨찾기 · 최근 목적지, mycourses.js `places`). */
    fun places(recent: Boolean): List<Place> = Courses.places.filter { (it.list == PlaceList.RECENT) == recent }

    private fun kmOf(mc: MyCourse): Double? = when {
        mc.placeId != null -> Courses.place(mc.placeId)?.km?.takeIf { it > 0 }
        mc.courseRef != null -> Courses.course(mc.courseRef)?.km?.takeIf { it > 0 }
        else -> null
    }

    private fun townOf(mc: MyCourse): String =
        if (mc.placeId != null || mc.navi != null) mc.town ?: "home" else Courses.course(mc.courseRef)?.town ?: "home"

    /** 모드별 묶음(mycourses.js `groups`). [town]이 아닌 동네의 줄에는 동네 이름. */
    fun groups(p: Profile, town: String = "home"): List<MyCourseGroup> = (1..8).mapNotNull { m ->
        val items = p.myCourses.filter { it.mode == m }.map { mc ->
            val name = mc.name.ifEmpty { Courses.course(mc.courseRef)?.name ?: Courses.place(mc.placeId)?.name ?: "내 코스" }
            val tw = townOf(mc)
            MyCourseItem(mc, name, sourceLabel(mc), kmOf(mc), if (tw != town) townName(tw) else null)
        }
        if (items.isEmpty()) null else MyCourseGroup(m, modeLabel(m), items)
    }

    private fun same(a: MyCourse, b: MyCourse): Boolean = when {
        a.courseRef != null || b.courseRef != null -> a.courseRef != null && a.courseRef == b.courseRef
        a.navi != null || b.navi != null -> a.navi?.key != null && a.navi?.key == b.navi?.key && (a.town ?: "home") == (b.town ?: "home")
        a.placeId != null || b.placeId != null -> a.placeId != null && a.placeId == b.placeId && (a.town ?: "home") == (b.town ?: "home")
        else -> false
    }

    /** 내비 장소를 내 코스로(mycourses.js `addPlace`). 성공이면 (바뀐 프로필, 새 줄, null), 아니면 (그대로, null, 까닭). */
    fun addPlace(p: Profile, placeId: String, mode: Int, town: String, savedAt: String): Triple<Profile, MyCourse?, MyCourseError?> {
        val pl = Courses.place(placeId) ?: return Triple(p, null, MyCourseError.PLACE)
        if (pl.here) return Triple(p, null, MyCourseError.HERE)
        if (mode !in IMPORT_MODES) return Triple(p, null, MyCourseError.MODE)
        val want = MyCourse(id = "", placeId = pl.id, town = town)
        if (p.myCourses.any { it.mode == mode && same(it, want) }) return Triple(p, null, MyCourseError.DUP)
        val course = MyCourse(
            id = CourseRules.uniqueId(p.myCourses, "mc-${pl.id}"), name = pl.name, mode = mode,
            source = if (pl.list == PlaceList.RECENT) CourseSource.RECENT else CourseSource.BOOKMARK,
            placeId = pl.id, town = town, savedAt = savedAt,
        )
        return Triple(p.copy(myCourses = p.myCourses + course), course, null)
    }

    /**
     * 진짜 차 내비 장소를 내 코스로(좌표째 — [addPlace]와 같은 규칙: 가져올 수 있는 모드 · 같은 동네·모드에 같은 장소면 DUP).
     * 출처는 [list](즐겨찾기 · 최근 목적지). 성공이면 (바뀐 프로필, 새 줄, null), 아니면 (그대로, null, 까닭).
     */
    fun addNaviPlace(p: Profile, place: NaviPlace, list: PlaceList, mode: Int, town: String, savedAt: String): Triple<Profile, MyCourse?, MyCourseError?> {
        if (mode !in IMPORT_MODES) return Triple(p, null, MyCourseError.MODE)
        val want = MyCourse(id = "", navi = place, town = town)
        if (p.myCourses.any { it.mode == mode && same(it, want) }) return Triple(p, null, MyCourseError.DUP)
        val course = MyCourse(
            id = CourseRules.uniqueId(p.myCourses, "mc-navi"), name = place.name, mode = mode,
            source = if (list == PlaceList.RECENT) CourseSource.RECENT else CourseSource.BOOKMARK,
            town = town, savedAt = savedAt, navi = place,
        )
        return Triple(p.copy(myCourses = p.myCourses + course), course, null)
    }

    /** 모드 바꾸기(mycourses.js `setMode`). */
    fun setMode(p: Profile, id: String, mode: Int): Pair<Profile, MyCourseError?> {
        val mc = p.myCourses.firstOrNull { it.id == id } ?: return p to MyCourseError.MISSING
        if (mode !in IMPORT_MODES) return p to MyCourseError.MODE
        if (mc.mode == mode) return p to MyCourseError.SAME
        if (p.myCourses.any { it !== mc && it.mode == mode && same(it, mc) }) return p to MyCourseError.DUP
        return p.copy(myCourses = p.myCourses.map { if (it.id == id) it.copy(mode = mode) else it }) to null
    }

    /** 지우기(mycourses.js `remove`). */
    fun remove(p: Profile, id: String): Pair<Profile, Boolean> {
        if (p.myCourses.none { it.id == id }) return p to false
        return p.copy(myCourses = p.myCourses.filterNot { it.id == id }) to true
    }

    /** 안 된 까닭 글(mycourses.js `errorText`). */
    fun errorText(err: MyCourseError?, mode: Int?): String = when (err) {
        MyCourseError.DUP -> if (mode != null) "${modeShort(mode)} 내 코스에 이미 있어요" else "이미 있는 내 코스예요"
        MyCourseError.HERE -> "지금 있는 곳은 코스로 만들 수 없어요"
        MyCourseError.MODE -> "이 모드는 코스를 쓰지 않아요"
        else -> "코스를 저장하지 못했어요"
    }

    /** 그 장소를 지금 동네에서 이미 가져온 모드들(CR-2 '내 코스 · 1단계·2단계' 태그). */
    fun savedModes(p: Profile, placeId: String, town: String): List<Int> =
        p.myCourses.filter { it.placeId == placeId && (it.town ?: "home") == town }.map { it.mode }.sorted()

    /** 그 진짜 차 내비 장소([NaviPlace.key])를 지금 동네에서 이미 가져온 모드들. */
    fun savedNaviModes(p: Profile, key: String, town: String): List<Int> =
        p.myCourses.filter { it.navi?.key == key && (it.town ?: "home") == town }.map { it.mode }.sorted()
}
