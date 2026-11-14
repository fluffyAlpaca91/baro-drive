package com.barodrive.core.engine.review

import com.barodrive.core.model.LicenseStartCheck
import com.barodrive.core.model.LicenseState
import com.barodrive.core.model.LicenseStatus
import com.barodrive.core.model.SeatState
import com.barodrive.core.model.StartBlocker
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * 연습면허 유효기간과 LC-1 시작 판단 (프로토타입 js/engine/detectors/license.js `expiry`·`status`·`startCheck`, 설계 4.2 LC-1·LC-2,
 * 7.8, 법 제81조·시행규칙 제55조). 오늘 날짜는 넘겨받는다(엔진은 시계를 쓰지 않음, 결정-80).
 */
object LicenseStart {
    /** 남은 날이 이 이하면 주의 색(soon). */
    const val SOON_DAYS: Int = 30

    // ================================================================ 유효기간 (설계 4.2 LC-1·LC-2, 법 제81조)

    private val YMD = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    /**
     * 날짜 글자 → 그날 (util.js `toDate` + license.js `dayOf`). 'YYYY-MM-DD'는 JS `new Date(y, m − 1, d)`처럼 넘치는 날을 다음 달로 넘긴다.
     * ISO 시각 글자는 적힌 날짜. 못 읽으면 null.
     */
    fun dayOf(value: String?): LocalDate? {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return null
        YMD.find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return jsDate(y.toInt(), mo.toInt() - 1, d.toInt())
        }
        return runCatching { OffsetDateTime.parse(s).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(s) }.getOrNull()
    }

    /** JS `new Date(y, monthIndex, d)`의 날짜(달·날이 넘치거나 0이면 앞뒤 달로). */
    private fun jsDate(y: Int, monthIndex: Int, d: Int): LocalDate = LocalDate.of(y, 1, 1).plusMonths(monthIndex.toLong()).plusDays(d - 1L)

    /** util.js `fmtDate(d)` 기본 모양 '2026. 4. 1.'. */
    fun dotText(d: LocalDate): String = "${d.year}. ${d.monthValue}. ${d.dayOfMonth}."

    /**
     * 유효기간 마지막 날 'YYYY-MM-DD' (license.js `expiry`): 발급일 + 1년 − 1일(설계 4.2 LC-2). 2월 29일 발급이면 다음 해 2월 28일
     * (JS 날짜 넘김 그대로). 발급일을 못 읽으면 null.
     */
    fun expiry(issueDate: String?): String? = dayOf(issueDate)?.let { expiryOf(it).toString() }

    private fun expiryOf(issue: LocalDate): LocalDate = jsDate(issue.year + 1, issue.monthValue - 1, issue.dayOfMonth - 1)

    /**
     * 유효기간 상태 (license.js `status(issueDate, today)`): 남은 날(마지막 날 당일 0) · 'D-141'·'D-day' · 지나면 expired
     * (마지막 날 다음 날부터) · 30일 이하 soon. 발급일이 없으면 state NONE, 오늘을 모르면 남은 날 없이 OK.
     */
    fun status(issueDate: String?, today: LocalDate?): LicenseStatus {
        val issue = dayOf(issueDate) ?: return LicenseStatus(state = LicenseState.NONE)
        val exp = expiryOf(issue)
        if (today == null) {
            return LicenseStatus(
                issueDate = issue.toString(), expiresOn = exp.toString(), state = LicenseState.OK,
                issueText = dotText(issue), expiryText = dotText(exp),
            )
        }
        val days = ChronoUnit.DAYS.between(today, exp).toInt()
        val expired = days < 0
        val soon = !expired && days <= SOON_DAYS
        return LicenseStatus(
            issueDate = issue.toString(),
            expiresOn = exp.toString(),
            days = days,
            dday = if (expired) null else if (days == 0) "D-day" else "D-$days",
            expired = expired,
            soon = soon,
            future = issue.isAfter(today),
            state = if (expired) LicenseState.EXPIRED else if (soon) LicenseState.SOON else LicenseState.OK,
            issueText = dotText(issue),
            expiryText = dotText(exp),
        )
    }

    // ================================================================ LC-1 시작 판단 (설계 4.2 LC-1, 시행규칙 제55조, 7.8)

    /**
     * LC-1 시작 판단 (license.js `startCheck`): 막는 까닭 하나(유효기간 지남 > 좌석 못 읽음 > 체크 모자람 > 조수석 빔),
     * 하단 버튼 글·비활성 버튼 토스트. 발급일이 없어도 체크·시작은 된다.
     */
    fun startCheck(passenger: Boolean?, checks: List<Boolean>, issueDate: String?, today: LocalDate?): LicenseStartCheck {
        val checked = checks.count { it }
        val allChecked = checked == checks.size
        val seat = when (passenger) {
            true -> SeatState.ON
            false -> SeatState.OFF
            null -> SeatState.UNKNOWN
        }
        val st = status(issueDate, today)
        val blocker = when {
            st.expired -> StartBlocker.EXPIRED
            seat == SeatState.UNKNOWN -> StartBlocker.SEAT
            !allChecked -> StartBlocker.CHECKS
            seat == SeatState.OFF -> StartBlocker.PASSENGER
            else -> null
        }
        val label = when (blocker) {
            StartBlocker.EXPIRED -> "유효기간이 지나 시작할 수 없어요"
            StartBlocker.SEAT -> "이 차에서는 시작할 수 없어요"
            StartBlocker.CHECKS -> "모두 체크하면 시작할 수 있어요"
            StartBlocker.PASSENGER -> "동승자가 타야 시작할 수 있어요"
            null -> "시험장 주변 코스 고르기"
        }
        val hint = when (blocker) {
            StartBlocker.EXPIRED -> "유효기간이 지나면 도로에서 연습할 수 없어요"
            StartBlocker.SEAT -> "조수석 신호를 못 읽어 운전면허 연습 모드를 시작할 수 없어요"
            StartBlocker.CHECKS -> if (seat == SeatState.OFF) "동승자가 타고 세 가지를 모두 체크해 주세요" else "세 가지를 모두 체크해 주세요"
            StartBlocker.PASSENGER -> "조수석에 동승자가 타야 시작할 수 있어요"
            null -> null
        }
        return LicenseStartCheck(
            ok = blocker == null, seat = seat, checked = checked, allChecked = allChecked, expired = st.expired, status = st,
            blocker = blocker, label = label, hint = hint,
        )
    }
}
