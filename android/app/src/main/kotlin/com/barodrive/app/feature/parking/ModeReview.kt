package com.barodrive.app.feature.parking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.data.repo.StoreRules
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroTextButton
import com.barodrive.app.ui.components.CoachAvatar
import com.barodrive.app.ui.components.HeroCard
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Coach
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.Courses
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.Mode
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareStatus
import com.barodrive.core.model.WeatherType
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlin.math.floor
import kotlin.math.roundToInt

// 모드 리뷰 화면(PK-4 주차 연습 마무리 · RV-6 언덕 주행 돌아보기)이 같이 쓰는 리뷰 틀 조각
// (프로토타입 review.js `BaroApp.review` — model·sections.appBar·hero·links·actions, frame의 '기록 없음', 결정사항 E T8-Q2).
// RV-1(ui-loop)의 리뷰 틀이 들어오면 그쪽 부품으로 바꿀 수 있다.

/** 이 회차 코스를 내 코스로 저장하는 줄 (mycourses.js `saveLink`): 코스 이름과 이미 저장됐는지. */
@Immutable
data class ReviewCourse(val name: String, val saved: Boolean)

/** 가족에게 요약 보내기 상태 (share.js `linkInfo`): 보낸 사람 이름 [to]. */
@Immutable
data class ReviewShare(val status: ShareStatus, val to: String)

/**
 * 리뷰 화면 값 (review.js `model`의 공통 부분): 회차 기록 하나와 그것을 그릴 프로필 조각.
 * @property course 내 코스 저장 줄(코스로 저장할 수 없는 회차면 null). [today] 읽기 전용 날짜 머리말을 정할 오늘.
 */
@Immutable
data class ModeReviewModel(
    val record: SessionRecord,
    val readOnly: Boolean,
    val coach: Coach = Coach.DAD,
    val honorific: Honorific = Honorific(),
    val course: ReviewCourse? = null,
    val share: ReviewShare = ReviewShare(ShareStatus.NONE, ""),
    val today: LocalDate? = null,
)

/** 리뷰 화면 값을 만드는 순수 함수 (review.js `model`·`subText`, mycourses.js `recordRef`·`saveRecord`). */
internal object ModeReviews {
    /** 리뷰 '이 코스 내 코스로 저장' 줄이 있는 모드(mycourses.js `SAVE_MODES`, 결정사항 E T9-7). */
    private val SAVE_MODES = setOf(1, 2, 3, 4, 6, 7)

    fun model(rec: SessionRecord, profile: Profile, readOnly: Boolean, today: LocalDate? = null): ModeReviewModel = ModeReviewModel(
        record = rec,
        readOnly = readOnly,
        coach = profile.coach,
        honorific = profile.honorific,
        course = courseOf(rec, profile),
        share = ReviewShare(rec.share.status, rec.share.to?.takeIf { it.isNotEmpty() } ?: profile.consent.familyName.ifEmpty { FAMILY }),
        today = today,
    )

    /** 내 코스 저장 줄 (mycourses.js `recordRef`·`hasRecord`). 세워 두고 연습·모드 5·8·코스 없는 회차는 null. */
    fun courseOf(rec: SessionRecord, profile: Profile): ReviewCourse? {
        if (rec.isVirtualParking || rec.mode !in SAVE_MODES) return null
        val course = Courses.course(rec.courseId)
        if (course != null) {
            return ReviewCourse(course.name, profile.myCourses.any { it.mode == rec.mode && it.courseRef == course.id })
        }
        val place = Courses.place(rec.placeId)?.takeIf { !it.here } ?: return null
        return ReviewCourse(place.name, profile.myCourses.any { it.mode == rec.mode && it.placeId == place.id })
    }

    /**
     * 이 회차 코스를 내 코스에 더한 목록 (mycourses.js `saveRecord`). 저장할 수 없거나 이미 있으면 null.
     * @param town 장소 코스의 동네(시연 패널 동네, 모르면 'home'). [savedAt] 저장 시각(ISO).
     */
    fun saveCourse(list: List<MyCourse>, rec: SessionRecord, town: String, savedAt: String): List<MyCourse>? {
        if (rec.isVirtualParking || rec.mode !in SAVE_MODES) return null
        val course = Courses.course(rec.courseId)
        if (course != null) {
            if (list.any { it.mode == rec.mode && it.courseRef == course.id }) return null
            val id = StoreRules.uniqueId(list.map { it.id }, "mc-${course.id}")
            return list + MyCourse(id = id, name = course.name, mode = rec.mode, source = CourseSource.SAVED, courseRef = course.id, savedAt = savedAt)
        }
        val place = Courses.place(rec.placeId)?.takeIf { !it.here } ?: return null
        if (list.any { it.mode == rec.mode && it.placeId == place.id }) return null
        val id = StoreRules.uniqueId(list.map { it.id }, "mc-${place.id}")
        return list + MyCourse(id = id, name = place.name, mode = rec.mode, source = CourseSource.SAVED, placeId = place.id, town = town, savedAt = savedAt)
    }

    /** 저장 시각 글자(store.js `isoLocal`). */
    fun now(clock: Clock): String = StoreRules.isoLocal(ZonedDateTime.now(clock))

    /** 오늘(지역 날짜). */
    fun today(clock: Clock): LocalDate = LocalDate.now(clock)

    /**
     * 앱 바 부제 '언덕 · 1회차 · 6분 · 2.2km · 빗길' (review.js `subText`). 기록에서 지난 날 회차를 열면 앞에 '11월 10일'(review.js `pastDay`).
     */
    fun subText(m: ModeReviewModel, res: TextRes): String {
        val rec = m.record
        val parts = mutableListOf<String>()
        Mode.ofOrNull(rec.mode)?.short?.let { parts += it }
        rec.noInMode?.takeIf { it > 0 }?.let { parts += res.text(R.string.pk_rv_no, it) }
        if (rec.durationSec >= 1) parts += fmtMin(rec.durationSec, res)
        if (rec.distanceM >= 50) parts += res.text(R.string.pk_rv_km, String.format(java.util.Locale.ROOT, "%.1f", rec.distanceM.coerceAtLeast(0.0) / 1000))
        roadWord(rec, res)?.let { parts += it }
        val sub = parts.joinToString(res.text(R.string.pk_rv_sep))
        val day = pastDay(m, res) ?: return sub
        return day + res.text(R.string.pk_rv_sep) + sub
    }

    /** 초 → '45초' · '7분' · '1시간 5분' (util.js `fmtMin`). */
    fun fmtMin(sec: Double, res: TextRes): String {
        val s = jsRound(sec.coerceAtLeast(0.0))
        if (s < 60) return res.text(R.string.pk_rv_sec, s)
        val m = floor(s / 60.0 + 0.5).roundToInt()
        if (m < 60) return res.text(R.string.pk_rv_min, m)
        val h = m / 60
        val mm = m % 60
        return if (mm != 0) res.text(R.string.pk_rv_hour_min, h, mm) else res.text(R.string.pk_rv_hour, h)
    }

    /** 날씨 길 글(copy.js `weather.road`): 빗길 · 눈길 · 아주 나쁜 날. 맑으면 null. */
    private fun roadWord(rec: SessionRecord, res: TextRes): String? {
        val w = rec.weather ?: return null
        return when {
            w.type == WeatherType.RAIN -> res.text(R.string.pk_rv_road_rain)
            w.type == WeatherType.SNOW -> res.text(R.string.pk_rv_road_snow)
            w.severe != null -> res.text(R.string.pk_rv_road_severe)
            else -> null
        }
    }

    /** 읽기 전용으로 연 지난 날 회차의 '11월 10일'(오늘이면 null, review.js `pastDay`). */
    private fun pastDay(m: ModeReviewModel, res: TextRes): String? {
        if (!m.readOnly) return null
        val today = m.today ?: return null
        val started = m.record.startedAt ?: return null
        val day = try {
            OffsetDateTime.parse(started).toLocalDate()
        } catch (e: DateTimeParseException) {
            return null
        }
        if (day == today) return null
        return res.text(R.string.pk_rv_day, day.monthValue, day.dayOfMonth)
    }

    private const val FAMILY = "가족"
}

/** 정차 중 말을 기록 하나에 키마다 한 번만 (review.js `speakOnce` — 같은 기록을 다시 그리거나 화면을 다시 만들어도 한 번). */
internal object ModeSpoken {
    private val said = HashSet<String>()

    /** 처음이면 true를 돌려주고 표시한다. */
    @Synchronized
    fun first(recordId: String?, key: String): Boolean = said.add("${recordId.orEmpty()}|$key")
}

// ---------------------------------------------------------------- 구역 그리기 (review.js sections)

/** 2 코치 히어로 (목업 `.hero`, review.js `sections.hero`): 아바타 112 + 강한 말투 30/38. 글이 없으면 그리지 않는다. */
@Composable
internal fun ReviewHero(coach: Coach, text: String) {
    if (text.isEmpty()) return
    HeroCard(text = text, leading = { CoachAvatar(coach.face(), size = Dimens.avatarXL) })
}

/**
 * 11 텍스트 버튼 줄 (review.js `sections.links`): '이 코스 내 코스로 저장 ›'(저장돼 있으면 별 + '내 코스에 있어요') ·
 * '가족에게 요약 보내기 ›'(보냈으면 '보냈어요 · 아버지', 줄 세웠으면 '연결되면 보내요'). [onShare]가 null이면(요약 보내기 시트가 없으면)
 * 공유 줄은 상태 글만 보인다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewLinks(
    course: ReviewCourse?,
    share: ReviewShare,
    onSaveCourse: () -> Unit,
    onShare: (() -> Unit)?,
    onQueuedShare: () -> Unit,
) {
    val shareText = when (share.status) {
        ShareStatus.SENT -> stringResource(R.string.pk_rv_share_sent, share.to)
        ShareStatus.QUEUED -> stringResource(R.string.pk_rv_share_queued)
        ShareStatus.NONE -> null
    }
    val hasShare = onShare != null || shareText != null
    if (course == null && !hasShare) return
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap),
        verticalArrangement = Arrangement.Center,
    ) {
        if (course != null) {
            if (course.saved) {
                LinkState(R.drawable.ic_star_f, stringResource(R.string.pk_rv_course_saved))
            } else {
                BaroTextButton(stringResource(R.string.pk_rv_course_save), onClick = onSaveCourse)
            }
        }
        when {
            onShare != null -> BaroTextButton(
                text = shareText ?: stringResource(R.string.pk_rv_share),
                onClick = if (share.status == ShareStatus.QUEUED) onQueuedShare else onShare,
                leadingIcon = R.drawable.ic_share,
            )
            shareText != null -> LinkState(R.drawable.ic_send, shareText)
        }
    }
}

/** 상태 글 줄 (review.css `.rv-link-st`): 민트 아이콘(28) + 26/34 굵은 보조 글, 높이 64. */
@Composable
private fun LinkState(icon: Int, text: String) {
    val colors = BaroTheme.colors
    Row(
        Modifier
            .heightIn(min = Dimens.minTouch)
            .padding(horizontal = Dimens.space1),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroIcon(icon, contentDescription = null, size = 28.dp, tint = colors.brandText)
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = colors.t2)
    }
}

/**
 * 12 하단 버튼 (review.js `sections.actions`): [홈으로] + [primaryLabel](민트) — 읽기 전용(또는 기록 없음)은 [닫기] 하나.
 * BaroScaffold의 하단 줄 안에서 부른다.
 */
@Composable
internal fun RowScope.ReviewActions(
    readOnly: Boolean,
    primaryLabel: String?,
    onHome: () -> Unit,
    onPrimary: () -> Unit,
    onClose: () -> Unit,
) {
    if (readOnly) {
        BaroButton(stringResource(R.string.pk_rv_close), onClick = onClose, modifier = Modifier.weight(1f))
        return
    }
    BaroButton(stringResource(R.string.pk_rv_home), onClick = onHome, modifier = Modifier.weight(1f))
    if (primaryLabel != null) {
        BaroButton(primaryLabel, onClick = onPrimary, modifier = Modifier.weight(1f), kind = BaroButtonKind.Primary)
    }
}

/** 기록을 찾을 수 없을 때 (review.js `missing`, `.empty-st.rv-missing`): 기록 아이콘 · '이 회차 기록을 찾을 수 없어요' · 30회차 안내. */
@Composable
internal fun ReviewMissing(modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.space4, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
    ) {
        BaroIcon(R.drawable.ic_history, contentDescription = null, size = Dimens.iconL, tint = colors.t3)
        Text(
            stringResource(R.string.pk_rv_missing_title),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Text(stringResource(R.string.pk_rv_missing_body), style = MaterialTheme.typography.labelLarge, color = colors.t3, textAlign = TextAlign.Center)
    }
}
