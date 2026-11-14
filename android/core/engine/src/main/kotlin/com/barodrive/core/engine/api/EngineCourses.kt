package com.barodrive.core.engine.api

import com.barodrive.core.engine.SeededRandom
import com.barodrive.core.engine.config.ModeContent
import com.barodrive.core.model.Course
import com.barodrive.core.model.CourseRanking
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.Courses
import com.barodrive.core.model.LineRef
import com.barodrive.core.model.NaviPlace
import com.barodrive.core.model.RankedCourse
import kotlin.math.floor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 코스 추천 (설계 6.10.6, 6.1.1 `courses.rank`, 프로토타입 core.js `rankCourses` 머리 주석). CR-1이 쓴다.
 * 코스·장소 데이터는 모델 [Courses].
 */
object EngineCourses {
    /**
     * 추천 순서와 '덜 막혀요' (core.js `rankCourses(mode, { town, congestionSeed, myCourses, tripOk, coursesDone })`).
     * 1 그 모드의 팀 코스(지금 동네) + 내 코스(별을 누른 팀 코스는 내 코스 한 줄로) · 2 가까운 3곳(내 코스 먼저, 남은 자리를 팀 코스로,
     * 모드 8은 시험장 코스 전부) · 3 점수 = 예상 시간(분) + 혼잡 × 6, 가장 낮은 코스가 best·처음 선택(같으면 가까운 쪽) ·
     * 4 혼잡도를 못 받으면 가까운 곳이 처음 선택, 혼잡·점수·best·코치 한마디 없음(결정-81).
     * 보이는 순서는 코스 글자 순. 혼잡 = 기본값 + 시드 값(시드 1 이하·null은 기본값 그대로 = 목업 s11). 모드 5는 빈 결과.
     * 글자: 팀·own 코스는 데이터 글자, 장소에서 가져온 내 코스와 다른 모드 코스는 그 모드에서 안 쓴 다음 글자(D, E …).
     * 진짜 차 내비에서 가져온 내 코스([com.barodrive.core.model.MyCourse.navi], 프로토타입에 없음)는 좌표만 있고 거리·시간·혼잡을 모른다:
     * 내 코스 중 가까운 순 맨 뒤이고, 혼잡·점수가 없어 '덜 막혀요'가 되지 않는다(줄의 km·min은 0).
     */
    fun rank(mode: Int, opts: RankOptions = RankOptions()): CourseRanking {
        require(mode in 1..8) { "모드는 1~8이에요: $mode" }
        val town = opts.town
        val tripOk = opts.tripOk
        val done = opts.coursesDone.toSet()
        val base = CourseRanking(
            mode = mode, town = town, tripOk = tripOk, autonomous = mode == AUTONOMOUS, kind = ModeContent.courseKind(mode),
            note = ModeContent.courseNote(mode),
        )
        if (mode == AUTONOMOUS) return base
        val modeCourses = Courses.forMode(mode)
        val used = modeCourses.map { it.letter }.toMutableSet()
        var next = 'A'.code
        fun nextLetter(): String {
            while (next.toChar().toString() in used) next++
            return next.toChar().toString().also { used += it }
        }
        val mine = ArrayList<Row>()
        val starred = HashSet<String>()
        for (mc in opts.myCourses) {
            if (mc.mode != mode) continue
            val row: Row = when {
                mc.courseRef != null -> {
                    val c = Courses.course(mc.courseRef) ?: continue
                    if (c.town != town || c.id in starred) continue
                    starred += c.id
                    fromCourse(c).also { if (c.mode != mode) it.letter = nextLetter() }
                }
                mc.navi != null -> {
                    val n = mc.navi ?: continue
                    if ((mc.town ?: HOME) != town) continue
                    Row(
                        id = mc.id.ifEmpty { "mc-navi" }, courseId = null, placeId = null, letter = nextLetter(), name = mc.name.ifEmpty { n.name },
                        km = 0.0, min = 0, baseCongestion = 0, routeOption = modeCourses.firstOrNull()?.routeOption ?: "추천",
                        straightKm = UNKNOWN_KM, own = false, segments = null, checkedAt = null,
                    ).also { it.navi = n }
                }
                mc.placeId != null -> {
                    val p = Courses.place(mc.placeId) ?: continue
                    if ((mc.town ?: HOME) != town) continue
                    Row(
                        id = mc.id.ifEmpty { "mc-${p.id}" }, courseId = null, placeId = p.id, letter = nextLetter(), name = mc.name.ifEmpty { p.name }, km = p.km,
                        min = p.min, baseCongestion = p.congestion, routeOption = modeCourses.firstOrNull()?.routeOption ?: "추천",
                        straightKm = p.straightKm, own = false, segments = null, checkedAt = null,
                    )
                }
                else -> continue
            }
            row.mine = true
            row.myCourseId = mc.id.ifEmpty { null }
            row.source = mc.source
            if (mc.name.isNotEmpty()) row.name = mc.name
            mine += row
        }
        val near = compareBy<Row>({ it.straightKm }, { letterOrd(it.letter) })
        val team = modeCourses.filter { !it.own && it.town == town && it.id !in starred }.map(::fromCourse).sortedWith(near)
        mine.sortWith(near)
        val list = if (mode == LICENSE) {
            (team + mine).toMutableList()
        } else {
            val l = mine.take(NEAR_COUNT).toMutableList()
            for (c in team) {
                if (l.size >= NEAR_COUNT) break
                l += c
            }
            l
        }
        for (r in list) {
            r.congestion = if (tripOk && r.navi == null) congestionFor(r.baseCongestion, r.id, opts.congestionSeed) else null
            r.score = r.congestion?.let { r.min + it * CONGESTION_WEIGHT.toDouble() }
            r.ran = mode == LICENSE && (r.letter in done || r.courseId in done)
        }
        var best: Row? = null
        if (tripOk) {
            for (r in list) {
                val b = best
                val s = r.score ?: continue
                val bs = b?.score
                if (b == null || bs == null || s < bs || (s == bs && near.compare(r, b) < 0)) best = r
            }
        }
        best?.best = true
        val nearest = list.sortedWith(near).firstOrNull()
        val shown = list.sortedBy { letterOrd(it.letter) }
        return base.copy(
            list = shown.map { it.toRanked() },
            bestId = best?.id,
            selectedId = best?.id ?: nearest?.id,
            empty = shown.isEmpty(),
            line = when {
                shown.isEmpty() -> LineRef("p.course.none")
                best != null -> LineRef("p.course", JsonObject(mapOf("X" to JsonPrimitive(best.letter))))
                else -> null
            },
        )
    }

    /** 혼잡 = 코스 기본값 + 시드 값(설계 6.10.6). 시드 1 이하는 기본값 그대로 = 목업 s11 (core.js `congestionFor`). */
    private fun congestionFor(base: Int, id: String, seed: Int?): Int {
        if (seed == null || seed <= 1) return base
        val r = SeededRandom("cong:$seed:$id")
        val d = SEED_STEPS[floor(r.next() * SEED_STEPS.size).toInt()]
        return (base + d).coerceIn(0, 2)
    }

    private fun letterOrd(s: String): Int = s.firstOrNull()?.code ?: NO_LETTER

    private fun fromCourse(c: Course) = Row(
        id = c.id, courseId = c.id, placeId = null, letter = c.letter, name = c.name, km = c.km, min = c.min, baseCongestion = c.congestion,
        routeOption = c.routeOption, straightKm = c.straightKm, own = c.own, segments = c.segments, checkedAt = c.checkedAt,
    )

    /** 추천 줄을 만드는 중의 값 (core.js rankCourses의 항목 객체). */
    private class Row(
        val id: String,
        val courseId: String?,
        val placeId: String?,
        var letter: String,
        var name: String,
        val km: Double,
        val min: Int,
        val baseCongestion: Int,
        val routeOption: String,
        val straightKm: Double,
        val own: Boolean,
        val segments: Int?,
        val checkedAt: String?,
    ) {
        var mine = false
        var myCourseId: String? = null
        var source: CourseSource = CourseSource.TEAM
        var congestion: Int? = null
        var score: Double? = null
        var ran = false
        var best = false
        var navi: NaviPlace? = null

        fun toRanked() = RankedCourse(
            id = id, courseId = courseId, placeId = placeId, letter = letter, name = name, km = km, min = min, congestion = congestion,
            congestionLabel = congestion?.let { Course.CONGESTION_LABELS.getOrNull(it) }, routeOption = routeOption, straightKm = straightKm,
            score = score, mine = mine, myCourseId = myCourseId, source = source, own = own, ran = ran, best = best, segments = segments,
            checkedAt = checkedAt, navi = navi,
        )
    }

    private const val AUTONOMOUS = 5
    private const val LICENSE = 8
    private const val HOME = "home"
    private const val NEAR_COUNT = 3
    private const val CONGESTION_WEIGHT = 6
    private const val NO_LETTER = 999

    /** 직선거리를 모르는 줄(진짜 차 내비 장소) — 가까운 순 맨 뒤. */
    private const val UNKNOWN_KM = Double.MAX_VALUE
    private val SEED_STEPS = listOf(-1, 0, 0, 1)
}
