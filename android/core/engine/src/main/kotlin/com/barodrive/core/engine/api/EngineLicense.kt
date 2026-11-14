package com.barodrive.core.engine.api

import com.barodrive.core.engine.review.LicenseCalc
import com.barodrive.core.engine.review.LicenseStart
import com.barodrive.core.model.CourseDoneUpdate
import com.barodrive.core.model.LicenseCourseResult
import com.barodrive.core.model.LicenseProgress
import com.barodrive.core.model.LicenseReview
import com.barodrive.core.model.LicenseScene
import com.barodrive.core.model.LicenseStartCheck
import com.barodrive.core.model.LicenseStatus
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 운전면허 연습 모드 계산 (설계 6.12.3, 4.2 LC-1·LC-2·RV-5, 프로토타입 detectors/license.js `BaroEngine.license` 머리 주석).
 * 화면 없는 순수 계산이다. 오늘 날짜는 앱이 넘긴다(엔진은 시계를 쓰지 않음, 결정-80).
 * 계산은 `review.LicenseStart`(유효기간·LC-1)와 `review.LicenseCalc`(코스 진행·장면·RV-5).
 */
object EngineLicense {
    /** 유효기간 마지막 날 'YYYY-MM-DD' (license.js `expiry`: 발급일 + 1년 − 1일, 그 달에 그날이 없으면 말일). 발급일이 없으면 null. */
    fun expiry(issueDate: String?): String? = LicenseStart.expiry(issueDate)

    /**
     * 유효기간 상태 (license.js `status(issueDate, today)`): 남은 날(마지막 날 당일 0) · 'D-141'·'D-day' ·
     * 지나면 expired(마지막 날 다음 날부터) · 30일 이하 soon. 발급일이 없으면 state NONE.
     */
    fun status(issueDate: String?, today: LocalDate?): LicenseStatus = LicenseStart.status(issueDate, today)

    /** 고를 수 있는 발급일인가 (license.js `validIssueDate`: 'YYYY-MM-DD'이고 오늘보다 뒤가 아님, 설계 4.2 LC-2). */
    fun validIssueDate(value: String?, today: LocalDate): Boolean {
        val d = try {
            value?.let(LocalDate::parse)
        } catch (_: DateTimeParseException) {
            null
        }
        return d != null && !d.isAfter(today)
    }

    /**
     * LC-1 시작 판단 (license.js `startCheck`): 막는 까닭 하나(유효기간 지남 > 좌석 못 읽음 > 체크 모자람 > 조수석 빔),
     * 하단 버튼 글·토스트. 발급일이 없어도 시작은 된다.
     */
    fun startCheck(input: LicenseStartInput): LicenseStartCheck {
        val c = input.checks
        return LicenseStart.startCheck(input.passenger, listOf(c.companion, c.sign, c.purpose), input.issueDate, input.today)
    }

    /** RV-5 코스 진행 카드 첫 줄 (license.js `courseResult`: 완주 '…을 끝까지 달렸어요' · 'N구간까지 달렸어요(완주 아님)'). */
    fun courseResult(record: SessionRecord): LicenseCourseResult = LicenseCalc.courseResult(record)

    /** 시험장 코스 진행 (license.js `progress(done)`: '4개 코스 중 2개', 글자 A~D마다 완주 여부). */
    fun progress(done: List<String>): LicenseProgress = LicenseCalc.progress(done)

    /**
     * 회차 끝 완주 반영 (license.js `applyCourseDone(profile, rec)`): 시험장 코스를 완주했으면 그 글자를 더한 A~D 순 목록.
     * 프로필은 고치지 않는다 — 결과 `after`를 `profile.license.coursesDone`에 넣는 것은 부르는 쪽. 같은 기록을 두 번 넣어도 같다.
     */
    fun applyCourseDone(profile: Profile, record: SessionRecord): CourseDoneUpdate = LicenseCalc.applyCourseDone(profile, record)

    /**
     * 시험 기준으로 다시 볼 장면 (license.js `scenes(rec)`, 설계 6.12.3 매핑·결정사항 E T8-4): 방향지시등 없이 회전 ·
     * 회전 신호가 늦음 · 급브레이크(F1 실패 + F2 '내 실수') · 제한속도 초과 · 어린이보호구역 속도 초과 · 급출발. 횟수 0인 장면은 뺀다.
     */
    fun scenes(record: SessionRecord): List<LicenseScene> = LicenseCalc.scenes(record)

    /** RV-5가 그릴 값 묶음 (license.js `review(rec, profile)`). 그 회차 때의 진행(`license.coursesDoneAfter`)을 쓰는 것은 화면 몫. */
    fun review(record: SessionRecord, profile: Profile?): LicenseReview = LicenseCalc.review(record, profile)
}
