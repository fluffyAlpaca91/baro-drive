package com.barodrive.app.feature.review

import com.barodrive.app.feature.autonomous.F2RowUi
import com.barodrive.app.feature.autonomous.ReviewSectionsUi
import com.barodrive.app.feature.autonomous.ReviewTipUi
import com.barodrive.app.feature.autonomous.f2Rows
import com.barodrive.app.feature.autonomous.reviewSections
import com.barodrive.app.feature.autonomous.tipsOf
import com.barodrive.app.feature.course.CourseRules
import com.barodrive.core.engine.review.ReviewText
import com.barodrive.core.engine.stage.StagePass
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Courses
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareStatus
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** '이 코스 내 코스로 저장' 줄. */
data class CourseLinkUi(val saved: Boolean, val name: String, val courseId: String?, val placeId: String?)

/** '가족에게 요약 보내기' 줄 — 보냈으면 '보냈어요 · 아버지', 못 보냈으면 '연결되면 보내요', 아니면 링크. */
data class ShareUi(val status: ShareStatus, val to: String)

/** [N단계 통과 확인]이 갈 곳(RV-2·RV-3). */
data class PassUi(val graduated: Boolean, val stage: Int)

/**
 * RV-1 화면 값 (프로토타입 review.js `BaroApp.review.model`, 설계 4.2 RV-1 1~12). 공통 구역(히어로·과제 달성·잘한 것·아쉬운 것·다음 과제·
 * K4·K6·도움 줄여 가기)은 리뷰 화면들이 같이 쓰는 [reviewSections](feature/autonomous의 리뷰 구역 묶음)로, 급제동·팁도 같은 묶음
 * ([f2Rows]·[tipsOf])으로 만든다. 여기에는 RV-1만의 값(부제·날짜·내 코스·공유·하단 갈 곳)을 더한다. 기록은 고치지 않는다.
 */
data class ReviewModel(
    val id: String,
    val record: SessionRecord,
    val mode: Int,
    val readOnly: Boolean,
    val focus: String?,
    val coach: Coach,
    /** 지난 날 회차를 기록에서 열면 그 날짜('11월 9일') — 제목이 '연습 돌아보기'가 된다(T11-16). */
    val pastDay: String?,
    /** 부제 '1단계 · 2회차 · 18분 · 4.2km · 빗길'(날짜가 있으면 앞에). */
    val sub: String,
    val sections: ReviewSectionsUi,
    val f2: List<F2RowUi>,
    val tips: List<ReviewTipUi>,
    val course: CourseLinkUi?,
    val share: ShareUi,
    val pass: PassUi?,
    /** [다시 연습하기] — 그 모드(5 → FB-2 · 6 → PK-1 · 8 → LC-1 · 나머지 CR-1). 읽기 전용이면 null. */
    val again: Int?,
) {
    val f2Pending: Int get() = f2.count { it.choice == null }

    val past: Boolean get() = pastDay != null
}

/** RV-1 계산 (review.js `model`·`courseInfo`·`saveCourse`·`passTarget`·`setF2Choice`·`pastDay`). 순수 함수. */
object ReviewModels {
    /** 내 코스로 저장할 수 있는 모드(review.js `COURSE_MODES`) — 5 자율·8 시험장 코스는 없음. */
    private val COURSE_MODES = setOf(1, 2, 3, 4, 6, 7)

    fun model(record: SessionRecord, profile: Profile, readOnly: Boolean, focus: String?, today: LocalDate): ReviewModel {
        val day = pastDay(record, readOnly, today)
        val sub = ReviewText.sub(record)
        return ReviewModel(
            id = record.id.orEmpty(),
            record = record,
            mode = record.mode,
            readOnly = readOnly,
            focus = focus,
            coach = profile.coach,
            pastDay = day,
            sub = if (day != null) "$day · $sub" else sub,
            sections = reviewSections(record, profile.coach, profile.honorific),
            f2 = f2Rows(record),
            tips = tipsOf(record),
            course = courseInfo(record, profile),
            share = ShareUi(record.share.status, record.share.to?.takeIf { it.isNotEmpty() } ?: profile.consent.familyName.ifEmpty { "가족" }),
            pass = if (readOnly) null else passTarget(record),
            again = if (readOnly) null else record.mode.takeIf { it in 1..8 },
        )
    }

    /** 지난 날 회차(읽기 전용으로 기록에서 열고 오늘이 아님) → 'M월 D일'(review.js `pastDay`). */
    fun pastDay(record: SessionRecord, readOnly: Boolean, today: LocalDate): String? {
        if (!readOnly) return null
        val at = record.startedAt ?: return null
        val d = try {
            OffsetDateTime.parse(at).toLocalDate()
        } catch (_: DateTimeParseException) {
            return null
        }
        return if (d == today) null else "${d.monthValue}월 ${d.dayOfMonth}일"
    }

    /** 이 회차 코스(review.js `courseInfo`): 팀 코스 또는 장소(지금 위치 아님). 내 코스로 저장할 수 없는 모드면 null. */
    fun courseInfo(record: SessionRecord, p: Profile): CourseLinkUi? {
        val m = record.mode
        if (m !in COURSE_MODES) return null
        record.courseId?.let { id ->
            val c = Courses.course(id) ?: return null
            return CourseLinkUi(saved = p.myCourses.any { it.mode == m && it.courseRef == c.id }, name = c.name, courseId = c.id, placeId = null)
        }
        record.placeId?.let { id ->
            val pl = Courses.place(id) ?: return null
            if (pl.here) return null
            return CourseLinkUi(saved = p.myCourses.any { it.mode == m && it.placeId == pl.id }, name = pl.name, courseId = null, placeId = pl.id)
        }
        return null
    }

    /**
     * 이 회차 코스를 내 코스로(review.js `saveCourse`): 팀 코스는 CR-1 별과 같은 줄, 장소는 장소 줄(동네 'home'). 이미 있으면 그대로.
     * 바뀐 프로필과 저장했나.
     */
    fun saveCourse(p: Profile, record: SessionRecord, savedAt: String, town: String = "home"): Pair<Profile, Boolean> {
        val info = courseInfo(record, p) ?: return p to false
        if (info.saved) return p to false
        val m = record.mode
        val list = p.myCourses
        val add = if (info.courseId != null) {
            MyCourse(
                id = CourseRules.uniqueId(list, "mc-${info.courseId}"), name = info.name, mode = m, source = CourseSource.SAVED,
                courseRef = info.courseId, savedAt = savedAt,
            )
        } else {
            MyCourse(
                id = CourseRules.uniqueId(list, "mc-${info.placeId}"), name = info.name, mode = m, source = CourseSource.SAVED,
                placeId = info.placeId, town = town, savedAt = savedAt,
            )
        }
        return p.copy(myCourses = list + add) to true
    }

    /** [N단계 통과 확인](review.js `passTarget` → stage.js `screenFor`): 이번 회차로 지금 단계를 통과했을 때만. */
    fun passTarget(record: SessionRecord): PassUi? {
        val screen = StagePass.screenFor(record) ?: return null
        val stage = record.progress?.stage ?: record.stage ?: record.mode
        return PassUi(graduated = screen == "RV-3", stage = stage)
    }

    /** 급제동 본인 확인 저장(review.js `setF2Choice`, 결정-54): 고른 값만. 바뀐 기록(그 건이 없으면 null). */
    fun withF2Choice(record: SessionRecord, f2Id: String, choice: F2Choice?): SessionRecord? {
        val i = record.f2.indexOfFirst { it.id == f2Id }.takeIf { it >= 0 }
            ?: f2Id.removePrefix("f2-").toIntOrNull()?.minus(1)?.takeIf { it in record.f2.indices }
            ?: return null
        return record.copy(f2 = record.f2.mapIndexed { k, f -> if (k == i) f.copy(choice = choice) else f })
    }
}
