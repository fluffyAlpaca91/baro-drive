package com.barodrive.app.feature.course

import com.barodrive.app.feature.home.HomeRules
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.RankOptions
import com.barodrive.core.model.CourseRanking
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.RankedCourse
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 코스 추천 계산 (프로토타입 course.js `BaroApp.courseRec` — inputs·rank·toggleStar·prepFor·visibleModes·lockedModes·licenseExpired,
 * 설계 4.2 CR-1·6.10.6, 구현계획 1.5 준비 세션). 순수 함수. 순서·'덜 막혀요'·처음 선택은 엔진([BaroEngine.courses])이 정한다.
 */
object CourseRules {
    /** 들어오는 화면이 따로 있는 모드(course.js `ENTRY`): 6 주차 → PK-1(종류 고르기) · 8 면허 → LC-1(시작 체크). */
    val ENTRY_MODES: Set<Int> = setOf(6, 8)

    /** 코스 추천 입력(course.js `inputs`): 시연 패널 동네·혼잡 시드, 내 코스, 혼잡도를 받았나, 면허 모드 완주 코스. */
    fun inputs(profile: Profile, town: String = "home", congestionSeed: Int? = null, tripOk: Boolean = true): RankOptions = RankOptions(
        town = town,
        congestionSeed = congestionSeed,
        myCourses = profile.myCourses,
        tripOk = tripOk,
        coursesDone = profile.license.coursesDone,
    )

    /** 추천 결과(course.js `rank`). */
    fun rank(mode: Int, profile: Profile, town: String = "home", congestionSeed: Int? = null, tripOk: Boolean = true): CourseRanking =
        BaroEngine.courses.rank(mode, inputs(profile, town, congestionSeed, tripOk))

    /** 지금 단계 1~4. */
    fun currentStage(p: Profile): Int = p.progress.currentStage.coerceIn(1, 4)

    /** 잠긴 단계 모드(course.js `lockedModes`): 2~4 중 열린 단계(지금 단계 포함)보다 위. 값 = 통과해야 할 단계(n − 1). */
    fun lockedModes(p: Profile): Map<Int, Int> {
        val open = maxOf(p.progress.unlockedStage.coerceIn(1, 4), currentStage(p))
        return (2..4).filter { it > open }.associateWith { it - 1 }
    }

    /** 모드 칩(course.js `visibleModes`): 1~4는 늘, 5~8은 들어갈 화면이 있을 때(앱에는 모두 있다) + 들어온 모드. */
    fun visibleModes(entryMode: Int?): List<Int> = ((1..8).toList() + listOfNotNull(entryMode)).distinct().sorted()

    /** 들어온 모드(course.js render): 1~8이 아니면 지금 단계, 잠긴 단계면 지금 단계. */
    fun entryMode(requested: Int?, p: Profile): Int {
        val m = requested?.takeIf { it in 1..8 } ?: currentStage(p)
        return if (m in lockedModes(p)) currentStage(p) else m
    }

    /** 모드 8: 연습면허 유효기간이 지났나(course.js `licenseExpired`). */
    fun licenseExpired(p: Profile, today: LocalDate): Boolean = HomeRules.licenseExpired(p.license.issueDate, today)

    /** 같은 id가 있으면 '-2', '-3' …(course.js `uniqueId`). */
    fun uniqueId(list: List<MyCourse>, base: String): String {
        if (list.none { it.id == base }) return base
        var k = 2
        while (list.any { it.id == "$base-$k" }) k++
        return "$base-$k"
    }

    /**
     * 별(course.js `toggleStar`): 내 코스면 빼고(그 줄 또는 같은 코스·모드 줄), 아니면 팀 코스를 내 코스로 저장(설계 8.1 + savedAt).
     * 바뀐 프로필과 저장했나(참)·뺐나(거짓). 팀 코스가 아닌 줄을 저장하려 하면 그대로 + 거짓.
     */
    fun toggleStar(p: Profile, item: RankedCourse, mode: Int, savedAt: String): Pair<Profile, Boolean> {
        val list = p.myCourses
        if (item.mine) {
            val kept = list.filterNot { mc ->
                (item.myCourseId != null && mc.id == item.myCourseId) || (item.courseId != null && mc.courseRef == item.courseId && mc.mode == mode)
            }
            return p.copy(myCourses = kept) to false
        }
        val courseId = item.courseId ?: return p to false
        val add = MyCourse(
            id = uniqueId(list, "mc-$courseId"), name = item.name, mode = mode, source = CourseSource.SAVED, courseRef = courseId, savedAt = savedAt,
        )
        return p.copy(myCourses = list + add) to true
    }

    /**
     * 준비 세션(구현계획 1.5, course.js `prepFor`): 모드 · 팀 코스 id(또는 장소에서 가져온 내 코스면 장소) · 회차 번호 = 그 모드 끝낸 회차 + 1
     * · 날씨·점검·정차 중 말은 처음 값. [extra]는 들어온 화면이 넘긴 값(주차 종류 등)을 덮어쓴다 — 모드는 덮어쓰지 않는다.
     * 진짜 차 내비에서 가져온 내 코스면 그 장소(좌표)를 준비 세션 `navi`로 넘긴다 — [내비로 보내고 출발]이 차 내비에 그대로 부탁한다.
     */
    fun prepFor(mode: Int, item: RankedCourse?, p: Profile, extra: ((PrepSession) -> PrepSession)? = null): PrepSession {
        val base = PrepSession(
            mode = mode,
            courseId = item?.courseId,
            placeId = if (item != null && item.courseId == null) item.placeId else null,
            navi = item?.navi,
            presetId = null,
            noInMode = (p.sessionCount[mode] ?: 0) + 1,
        )
        return extra?.invoke(base)?.copy(mode = mode) ?: base
    }
}

/**
 * 모드마다 고른 코스(course.js 파일 안 `sel` — PR-1 [코스 바꾸기]로 돌아와도 그대로). 앱이 사는 동안만.
 * 동네·혼잡 시드·혼잡도 받기가 바뀌면 비운다(다시 '덜 막혀요'부터).
 */
@Singleton
class CourseSelection @Inject constructor() {
    private val picked = HashMap<Int, String>()

    @Synchronized
    fun get(mode: Int): String? = picked[mode]

    @Synchronized
    fun set(mode: Int, id: String) {
        picked[mode] = id
    }

    @Synchronized
    fun clear() = picked.clear()
}
