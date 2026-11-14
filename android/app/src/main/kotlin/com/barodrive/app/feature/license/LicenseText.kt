package com.barodrive.app.feature.license

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.review.LicenseCalc
import com.barodrive.core.engine.review.ReviewText
import com.barodrive.core.model.Courses
import com.barodrive.core.model.LicenseProgress
import com.barodrive.core.model.LicenseReview
import com.barodrive.core.model.LicenseState
import com.barodrive.core.model.LicenseStatus
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord

/** PR-1 면허 변형 동승자 카드 (license.js `predrivePassenger`). [ok]가 거짓이면 주의 글. */
data class LicensePassengerCard(val label: String, val sub: String, val ok: Boolean)

/**
 * 운전면허 연습 모드 화면 글의 화면 없는 계산 (프로토타입 screens/license.js — LC-2 미리보기, RV-5 부제·그 회차 진행, PR-1 면허 변형,
 * 설계 4.2 LC-1·LC-2·RV-5·PR-1, 5.9). 유효기간·시작 판단·코스 진행·장면은 엔진(license.*)이 계산한다.
 */
object LicenseText {
    /** LC-2 미리보기 '2027. 3. 31.까지 · D-141'(지났으면 '… · 유효기간이 지났어요', 발급일이 없으면 ''). */
    fun previewText(st: LicenseStatus): String {
        if (st.state == LicenseState.NONE) return ""
        return "${st.expiryText.orEmpty()}까지 · " + if (st.expired) "유효기간이 지났어요" else st.dday.orEmpty()
    }

    /**
     * RV-5가 그릴 면허 값 (license.js `reviewModel`): 엔진 review + 그 회차 때의 진행(기록의 coursesDoneAfter가 있으면 그것 —
     * 기록에서 다시 열어도 '4개 코스 중 n개'가 그 회차 기준).
     */
    fun review(rec: SessionRecord, profile: Profile?): LicenseReview {
        val v = BaroEngine.license.review(rec, profile)
        val after = rec.license?.coursesDoneAfter ?: return v
        val done = LicenseCalc.coursesAfter(after, null)
        return v.copy(coursesDone = done, progress = BaroEngine.license.progress(done))
    }

    /** RV-5 앱 바 부제: RV-1 부제('면허 · 1회차 · 11분 · 5.3km')에 코스 글자를 넣는다('면허 · C 코스 · 1회차 · …'). */
    fun reviewSub(rec: SessionRecord): String {
        val c = BaroEngine.license.courseResult(rec)
        val rest = ReviewText.sub(rec).replace(Regex("^면허( · )?"), "")
        val head = if (c.test && !c.letter.isNullOrEmpty()) "면허 · ${c.letter} 코스" else "면허"
        return if (rest.isNotEmpty()) "$head · $rest" else head
    }

    /** 진행 막대 값 0~1. */
    fun progressFraction(p: LicenseProgress): Float = if (p.total > 0) p.done.toFloat() / p.total else 0f

    // ---------------- PR-1 면허 변형 (license.js `Baro.predriveVariant(8)`, 결정사항 E T8-Q1) — PR-1(ui-loop)이 부른다

    private fun segCount(courseId: String?): Int = Courses.course(courseId)?.segments?.takeIf { it > 1 } ?: 1

    /** PR-1 부제 '운전면허 연습 · C 코스 · 3구간' · 장소에서 가져온 코스면 그 이름 · 코스 없이면 'n회차'. */
    fun predriveSubtitle(prep: PrepSession?): String {
        val parts = arrayListOf("운전면허 연습")
        val c = Courses.course(prep?.courseId)
        val pl = if (c == null) Courses.place(prep?.placeId) else null
        when {
            c != null -> {
                parts += if (c.letter.isNotEmpty()) "${c.letter} 코스" else c.name
                val n = segCount(c.id)
                if (n > 1) parts += "${n}구간"
            }
            pl != null -> parts += pl.name
            (prep?.noInMode ?: 0) > 0 -> parts += "${prep?.noInMode}회차"
        }
        return parts.joinToString(" · ")
    }

    /** PR-1 코스 카드 아래 '3구간으로 나눠 안내해요'(구간이 하나면 null). */
    fun predriveCourseNote(prep: PrepSession?): String? {
        val n = segCount(prep?.courseId)
        return if (n > 1) "${n}구간으로 나눠 안내해요" else null
    }

    /**
     * PR-1 동승자 카드: LC-1 시작 체크를 거친 준비 세션이면([checked]) '동승자 확인 끝'으로 고정, 그사이 조수석이 비면 주의 글
     * (차는 막지 않음, 결정-57). [seat] = 조수석 착석(못 읽으면 null). 시작 체크를 안 거쳤으면 null(기본 카드).
     */
    fun predrivePassenger(checked: Boolean, seat: Boolean?): LicensePassengerCard? {
        if (!checked) return null
        return when (seat) {
            false -> LicensePassengerCard("조수석이 비어 있어요", "동승자가 타야 연습할 수 있어요", ok = false)
            null -> LicensePassengerCard("동승자 확인 끝", "조수석 신호를 못 읽어요 · 시작 체크에서 확인했어요", ok = false)
            true -> LicensePassengerCard("동승자 확인 끝", "시작 체크에서 확인했어요", ok = true)
        }
    }
}
