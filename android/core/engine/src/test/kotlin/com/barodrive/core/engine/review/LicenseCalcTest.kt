package com.barodrive.core.engine.review

import com.barodrive.core.engine.api.EngineLicense
import com.barodrive.core.engine.api.LicenseChecks
import com.barodrive.core.engine.api.LicenseStartInput
import com.barodrive.core.engine.review.ReviewTestKit.itemsOf
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.LicenseInfo
import com.barodrive.core.model.LicenseResult
import com.barodrive.core.model.LicenseSceneCounts
import com.barodrive.core.model.LicenseState
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SeatState
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StartBlocker
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 운전면허 연습 모드 계산 (프로토타입 tests/unit/t8_license.test.js의 화면 없는 부분: 유효기간·만료·LC-1 시작 판단·장면 매핑·
 * 코스 완주·진행률·RV-5 값·금지 표현). 검출기·S8-C 시나리오 시험은 엔진 코어·시뮬레이터 몫이다.
 */
class LicenseCalcTest {
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    // ================================================================ 유효기간 (설계 4.2 LC-1·LC-2, 법 제81조)

    @Test
    fun expiryIsIssuePlusOneYearMinusOneDay() {
        val cases = listOf(
            "2026-04-01" to "2027-03-31", // 목업 s09
            "2024-02-29" to "2025-02-28", // 윤일 발급: 다음 해 2월 말일(민법 제160조 ③)
            "2023-03-01" to "2024-02-29", // 끝이 윤년 2월
            "2025-03-01" to "2026-02-28",
            "2026-01-01" to "2026-12-31",
            "2024-02-28" to "2025-02-27",
            "2026-12-31" to "2027-12-30",
        )
        for ((issue, exp) in cases) assertEquals(issue, exp, EngineLicense.expiry(issue))
        assertEquals("시각이 붙은 값도 받는다(시각은 무시)", "2027-03-31", EngineLicense.expiry("2026-04-01T15:00:00+09:00"))
        assertNull(EngineLicense.expiry(null))
        assertNull(EngineLicense.expiry("엉뚱한 값"))
    }

    @Test
    fun statusDdayAndExpiryBoundary() {
        val st = EngineLicense.status("2026-04-01", day(2026, 11, 10))
        assertEquals("목업 s09 'D-141'", listOf<Any?>(141, "D-141", LicenseState.OK, false, false), listOf(st.days, st.dday, st.state, st.expired, st.soon))
        assertEquals(listOf("2026. 4. 1.", "2027. 3. 31.", "2027-03-31"), listOf(st.issueText, st.expiryText, st.expiresOn))
        assertEquals("2026-04-01", st.issueDate)
        assertEquals(listOf<Any?>(31, LicenseState.OK), EngineLicense.status("2026-04-01", day(2027, 2, 28)).let { listOf(it.days, it.state) })
        assertEquals("D-30부터 주의 색", listOf<Any?>("D-30", LicenseState.SOON), EngineLicense.status("2026-04-01", day(2027, 3, 1)).let { listOf(it.dday, it.state) })
        val last = EngineLicense.status("2026-04-01", day(2027, 3, 31))
        assertEquals("마지막 날 당일", listOf<Any?>(0, "D-day", false, LicenseState.SOON), listOf(last.days, last.dday, last.expired, last.state))
        val after = EngineLicense.status("2026-04-01", day(2027, 4, 1))
        assertEquals(listOf<Any?>(-1, null, true, LicenseState.EXPIRED), listOf(after.days, after.dday, after.expired, after.state))
        val none = EngineLicense.status(null, day(2026, 11, 10))
        assertEquals("발급일 없음", listOf<Any?>(LicenseState.NONE, null, false), listOf(none.state, none.dday, none.expired))
        val unknownToday = EngineLicense.status("2026-04-01", null)
        assertEquals("오늘을 모르면 남은 날 없이", listOf<Any?>(LicenseState.OK, null, "2027-03-31"), listOf(unknownToday.state, unknownToday.days, unknownToday.expiresOn))
        assertTrue("발급일이 오늘보다 뒤", EngineLicense.status("2026-12-01", day(2026, 11, 10)).future)
        assertTrue("오늘은 고를 수 있다", EngineLicense.validIssueDate("2026-11-10", day(2026, 11, 10)))
        assertFalse("오늘보다 뒤는 안 된다(LC-2)", EngineLicense.validIssueDate("2026-11-11", day(2026, 11, 10)))
    }

    // ================================================================ LC-1 시작 판단

    @Test
    fun startCheckBlockersInOrder() {
        val all = LicenseChecks(companion = true, sign = true, purpose = true)
        val today = day(2026, 11, 10)
        fun check(passenger: Boolean?, checks: LicenseChecks, issue: String? = "2026-04-01") =
            EngineLicense.startCheck(LicenseStartInput(passenger = passenger, checks = checks, issueDate = issue, today = today))
        val ok = check(true, all)
        assertEquals(listOf<Any?>(true, null, "시험장 주변 코스 고르기", null, 3), listOf(ok.ok, ok.blocker, ok.label, ok.hint, ok.checked))
        assertTrue("발급일 없이도 시작(설계 4.2 LC-1)", check(true, all, issue = null).ok)
        val empty = check(false, all)
        assertEquals(listOf<Any?>(false, SeatState.OFF, StartBlocker.PASSENGER, "동승자가 타야 시작할 수 있어요"), listOf(empty.ok, empty.seat, empty.blocker, empty.label))
        assertEquals("조수석에 동승자가 타야 시작할 수 있어요", empty.hint)
        val two = check(true, LicenseChecks(companion = true, sign = true))
        assertEquals("목업 글", listOf<Any?>(StartBlocker.CHECKS, "모두 체크하면 시작할 수 있어요", "세 가지를 모두 체크해 주세요", 2), listOf(two.blocker, two.label, two.hint, two.checked))
        assertEquals("동승자가 타고 세 가지를 모두 체크해 주세요", check(false, LicenseChecks(), issue = null).hint)
        val blind = check(null, all, issue = null)
        assertEquals("좌석을 못 읽으면 모드 8은 시작 안 함(설계 7.8)", listOf<Any?>(SeatState.UNKNOWN, StartBlocker.SEAT, false), listOf(blind.seat, blind.blocker, blind.ok))
        val exp = check(true, all, issue = "2025-09-01")
        assertEquals("만료가 먼저", listOf<Any?>(StartBlocker.EXPIRED, true, false), listOf(exp.blocker, exp.expired, exp.ok))
        assertEquals("유효기간이 지나면 도로에서 연습할 수 없어요", exp.hint)
    }

    // ================================================================ 장면 매핑 (설계 6.12.3, 4.2 RV-5, 결정-56)

    private fun ev(item: String, result: EventResult, data: EventData = EventData.EMPTY, pre: Boolean = false) =
        JudgeEvent(t = 1.0, item = item, result = result, data = data, pre = pre)

    private val fail = EventResult.FAIL
    private val ok = EventResult.OK

    private fun sceneRec(items: Map<String, Delivery> = itemsOf(8)) = SessionRecord(
        mode = 8, courseId = "c8-C", items = items,
        events = listOf(
            ev("C5", fail, EventData(turnId = "t1")), ev("C2", fail, EventData(turnId = "t1")), // 같은 회전 → 1번
            ev("C2", fail, EventData(turnId = "t2")), // C5 판정 없는 회전 → 깜빡이 없이
            ev("C2", EventResult.LATE, EventData(turnId = "t3", distance = 20.0)),
            ev("C5", ok, EventData(turnId = "t4", before = false)), ev("C2", fail, EventData(turnId = "t4")), // 지나서 켬 → 늦음
            ev("C2", EventResult.ALMOST, EventData(turnId = "t5", distance = 35.0)), ev("C2", ok, EventData(turnId = "t6", distance = 50.0)),
            ev("F1", fail, EventData(v0 = 30.0)), ev("F1", fail, EventData(v0 = 40.0)), ev("F1", ok),
            ev("E2", fail, EventData(zoneId = "z3")), ev("E1", fail, EventData(zoneId = "z3", school = true, limit = 30.0)), // 같은 구역 → 1번
            ev("E1", fail, EventData(zoneId = "z7", school = true, limit = 30.0)), // E2 없는 어린이보호구역
            ev("E1", fail, EventData(zoneId = "z9", school = false, limit = 50.0)), ev("E1", ok, EventData(zoneId = "z1", limit = 50.0)),
            ev("B1", fail, EventData(maxGain1s = 13.0)), ev("B1", EventResult.EXCLUDED, EventData(reason = "F2")),
            ev("C5", fail, EventData(turnId = "t8"), pre = true), // 사전 판정은 뺌
            ev("C5", EventResult.EXCLUDED, EventData(turnId = "t9", was = EventResult.FAIL, excluded = "reroute")), // 재탐색으로 빠진 회전
            ev("M2", fail, EventData(alone = true)),
        ),
        f2 = listOf(F2Check(id = "f2-1", choice = F2Choice.MISTAKE), F2Check(id = "f2-2", choice = F2Choice.AVOID), F2Check(id = "f2-3")),
    )

    @Test
    fun scenesMapJudgementsToTestCriteria() {
        val rows = EngineLicense.scenes(sceneRec())
        assertEquals(
            listOf("noSignal" to 2, "lateSignal" to 2, "hardBrake" to 3, "speed" to 1, "school" to 2, "hardStart" to 1),
            rows.map { it.id to it.n },
        )
        assertEquals(
            "횟수만(결정-56)",
            listOf("방향지시등 없이 회전 2번", "회전 신호가 늦음 2번", "급브레이크 3번", "제한속도 초과 1번", "어린이보호구역 속도 초과 2번", "급출발 1번"),
            rows.map { it.text },
        )
        for (r in rows) assertFalse("점수·감점 표현 없음: ${r.text}", Regex("점|감점|불합격|합격").containsMatchIn(r.text))
        // '안 하기'(X)로 둔 항목은 넣지 않는다 · C5가 X면 C2 안 켬이 그 회전을 센다
        val items = itemsOf(8) + mapOf("B1" to Delivery.OFF, "C5" to Delivery.OFF)
        assertEquals(
            listOf("noSignal" to 3, "lateSignal" to 1, "hardBrake" to 3, "speed" to 1, "school" to 2),
            EngineLicense.scenes(sceneRec(items)).map { it.id to it.n },
        )
        // F2 '내 실수'는 F1이 X여도 넣는다(고정 규칙 · 본인 확인)
        assertEquals(1, EngineLicense.scenes(sceneRec(items + ("F1" to Delivery.OFF))).first { it.id == "hardBrake" }.n)
        // 없으면 빈 목록(화면은 '다시 볼 장면이 없어요')
        assertEquals(
            emptyList<Any>(),
            EngineLicense.scenes(SessionRecord(mode = 8, events = listOf(ev("C2", ok, EventData(turnId = "t1")), ev("E1", ok)))),
        )
        assertEquals("다시 볼 장면이 없어요", LicenseCalc.Text.SCENE_NONE)
        assertEquals("시험 기준으로 다시 볼 장면", LicenseCalc.Text.SCENE_TITLE)
        // 판정 기록이 없는 회차(미리 만든 상태): rec.license.scenes 개수 + F2 내 실수
        val summary = SessionRecord(
            mode = 8, license = LicenseResult(scenes = LicenseSceneCounts(lateSignal = 1, hardBrake = 2)), f2 = listOf(F2Check(choice = F2Choice.MISTAKE)),
        )
        assertEquals(listOf("lateSignal" to 1, "hardBrake" to 3), EngineLicense.scenes(summary).map { it.id to it.n })
    }

    @Test
    fun countScenesSkipsDroppedTurns() {
        val r = sceneRec()
        val all = LicenseCalc.countScenes(r.events, r.items)
        assertEquals(LicenseSceneCounts(noSignal = 2, lateSignal = 2, hardBrake = 2, speed = 1, school = 2, hardStart = 1), all)
        val dropped = LicenseCalc.countScenes(r.events, r.items, dropped = setOf("t2", "t4"))
        assertEquals("빠진 회전(t2 안 켬 · t4 늦음)은 세지 않는다", listOf(1, 1), listOf(dropped.noSignal, dropped.lateSignal))
    }

    // ================================================================ 코스 완주·진행률 (설계 6.12.3, 4.2 RV-5)

    private val done = SessionRecord(
        mode = 8, courseId = "c8-C", arrived = true,
        license = LicenseResult(courseId = "c8-C", letter = "C", name = "시험장 주변 C 코스", segments = 3, reached = 3, segmentsDone = 3, complete = true),
    )
    private val part = SessionRecord(
        mode = 8, courseId = "c8-C", arrived = false,
        license = LicenseResult(courseId = "c8-C", letter = "C", name = "시험장 주변 C 코스", segments = 3, reached = 2, segmentsDone = 1, complete = false),
    )

    @Test
    fun courseCompletionAndProgress() {
        assertEquals("시험장 코스 4개(결정-63)", listOf("A", "B", "C", "D"), LicenseCalc.testCourses().map { it.letter })
        assertEquals("시험장 주변 C 코스를 끝까지 달렸어요", EngineLicense.courseResult(done).text)
        assertEquals("설계 4.2 RV-5", "2구간까지 달렸어요(완주 아님)", EngineLicense.courseResult(part).text)
        assertEquals(listOf("A", "B", "C"), LicenseCalc.coursesAfter(listOf("B", "A"), done))
        assertEquals("완주 아님은 넣지 않는다", listOf("B"), LicenseCalc.coursesAfter(listOf("B"), part))
        assertEquals("글자로 맞추고 모르는 값은 버림", listOf("A", "D"), LicenseCalc.coursesAfter(listOf("c8-D", "X", "A", "A"), null))
        val place = SessionRecord(mode = 8, placeId = "clinic", arrived = true, license = LicenseResult(complete = true, segments = 1, reached = 1))
        assertEquals("시험장 코스가 아니면 진행률에 안 넣음", listOf("A"), LicenseCalc.coursesAfter(listOf("A"), place))
        assertEquals("메모가 없으면 도착으로", true, EngineLicense.courseResult(SessionRecord(mode = 8, courseId = "c8-B", arrived = true)).complete)
        val noNote = EngineLicense.courseResult(SessionRecord(mode = 8, courseId = "c8-B", arrived = false))
        assertEquals("메모가 없으면 코스 구간 수", listOf<Any?>(3, 1, 0, "1구간까지 달렸어요(완주 아님)"), listOf(noNote.count, noNote.reached, noNote.segmentsDone, noNote.text))
        assertEquals("한 구간 코스", "목적지까지 가지 않았어요(완주 아님)", EngineLicense.courseResult(SessionRecord(mode = 8, placeId = "clinic")).text)

        var p = Profile()
        val r1 = EngineLicense.applyCourseDone(p, done)
        p = p.copy(license = p.license.copy(coursesDone = r1.after))
        assertEquals(listOf<Any?>(listOf("C"), "C", "4개 코스 중 1개"), listOf(p.license.coursesDone, r1.added, r1.progress.text))
        val r2 = EngineLicense.applyCourseDone(p, done)
        assertEquals("같은 회차를 두 번 반영해도 같음", listOf<Any?>(listOf("C"), null), listOf(r2.after, r2.added))
        p = p.copy(license = LicenseInfo(coursesDone = listOf("A", "B")))
        val r3 = EngineLicense.applyCourseDone(p, done)
        assertEquals(listOf("A✓", "B✓", "C✓", "D"), EngineLicense.progress(r3.after).letters.map { it.letter + if (it.done) "✓" else "" })
        val rv = EngineLicense.review(done, Profile(license = LicenseInfo(coursesDone = listOf("A"))))
        assertEquals("RV-5 값 묶음", listOf<Any?>("4개 코스 중 2개", true, listOf("A", "C")), listOf(rv.progress.text, rv.course.complete, rv.coursesDone))
        assertEquals(listOf<Any?>(0, null), listOf(rv.alone, rv.aloneText))
        val alone = EngineLicense.review(done.copy(license = done.license?.copy(alone = 2)), null)
        assertEquals(listOf<Any?>(2, "조수석이 빈 채로 달린 때가 있었어요 · 동승자 없이 연습하면 안 돼요"), listOf(alone.alone, alone.aloneText))
        assertEquals("구간 2/3", LicenseCalc.segmentText(2, 3))
        assertEquals("구간 1/1", LicenseCalc.segmentText(null, null))
        assertEquals("구간 3/3", LicenseCalc.segmentText(7, 3))
    }

    // ================================================================ 문구

    @Test
    fun textsHaveNoForbiddenWords() {
        val all = LicenseChecks(companion = true, sign = true, purpose = true)
        val cases = listOf(
            LicenseStartInput(true, all), LicenseStartInput(false, all), LicenseStartInput(true, LicenseChecks()), LicenseStartInput(false, LicenseChecks()),
            LicenseStartInput(null, all), LicenseStartInput(true, all, issueDate = "2024-01-01"),
        )
        val blockers = HashSet<StartBlocker?>()
        val texts = ArrayList<String?>()
        texts += listOf(
            LicenseCalc.Text.SCENE_TITLE, LicenseCalc.Text.SCENE_SUB, LicenseCalc.Text.SCENE_NONE, LicenseCalc.Text.ALONE,
            LicenseCalc.Text.CARD_TITLE, LicenseCalc.Text.CARD_DETAIL,
        )
        texts += LicenseCalc.SCENES.map { it.second }
        for (c in cases) {
            val r = EngineLicense.startCheck(c.copy(today = day(2026, 11, 10)))
            blockers += r.blocker
            texts += listOf(r.label, r.hint)
        }
        assertEquals("모든 버튼 글을 훑음", setOf(StartBlocker.CHECKS, StartBlocker.EXPIRED, StartBlocker.PASSENGER, StartBlocker.SEAT, null), blockers)
        val forbidden = Regex("교습|강사|수료증|면허 보증|시험 코스 재현|준비 중|감점")
        for (t in texts.filterNotNull()) assertFalse("금지 표현: $t", forbidden.containsMatchIn(t))
    }
}
