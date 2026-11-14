package com.barodrive.app.feature.review

import androidx.lifecycle.SavedStateHandle
import com.barodrive.app.R
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.feature.autonomous.F2RowUi
import com.barodrive.app.feature.autonomous.ReviewTipUi
import com.barodrive.app.feature.course.CourseRecommend
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ProgressResult
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.ShareStatus
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 프로토타입 tests/unit/t2d_review.test.js의 model·급제동·내 코스·하단 갈 곳 부분(설계 5.3 10번 예 PASS). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val today = LocalDate.of(2026, 11, 10)

    /** t2d_review.test.js PASS — 프로토타입 기록 JSON 그대로. */
    private val pass: SessionRecord = BaroJson.decodeFromString(
        SessionRecord.serializer(),
        """
        {"id":"sess-20261110-0912","mode":2,"stage":2,"noInMode":3,"courseId":"c2-B","placeId":null,"presetId":null,
         "startedAt":"2026-11-10T09:12:00+09:00","simSec":1080,"driveSec":1060,"distanceM":4200,"arrived":true,
         "weather":{"type":"rain","severe":false,"cold":false},"events":[],"utterances":[],
         "tasks":[{"id":"T-30","label":"시속 30 넘지 않기","short":"30 구역","success":2,"total":3},
           {"id":"T-SIGNAL30","label":"꺾기 30m 전에 깜빡이 켜기","short":"깜빡이","success":5,"total":6},
           {"id":"T-WAIT3","label":"깜빡이 켜자마자 꺾지 않기","short":"깜빡이 켜고 셋 세기","success":6,"total":7},
           {"id":"T-STOP","label":"멈출 때 부드럽게 멈추기","short":"멈출 때 브레이크","success":7,"total":8}],
         "achievement":0.833,"qualified":true,"fewEvents":false,
         "nextTask":{"id":"T-30","label":"30 구역 지키기","short":"30 구역","reason":"safety"},
         "good":[{"id":"T-STOP","text":"멈출 때 부드럽게 멈추기 7/8번"},{"id":"G1","text":"직진할 때 핸들 첫날보다 30% 안정"}],
         "bad":[{"id":"E1-30","text":"30 구역 초과 1번"},{"id":"C4","text":"깜빡이 켜고 바로 꺾음 1번"}],
         "metrics":{"per10min":4.2,"praise":6,"correction":2,
           "k4":{"first":false,"rows":[{"id":"c2Avg","label":"깜빡이 켠 거리 평균","text":"38m → 52m","better":true},
             {"id":"stop","label":"급정지 비율","text":"3/8 → 1/8","better":true},{"id":"g1","label":"직진 핸들 흔들림","text":"첫날보다 30% 안정","better":true}]}},
         "f2":[{"id":"f2-1","t":412.3,"v0":28,"v1":0,"dur":1.4,"maxDrop1s":11.6,"stopped":true,"choice":null}],
         "tips":[{"text":"빗길 회차예요. 빗길에선 브레이크를 일찍, 나눠서 밟아요."}],
         "fadedNow":["B1"],"fadedNote":"출발은 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.",
         "progress":{"applied":true,"mode":2,"stage":2,"qualified":true,"qualifiedBefore":1,"qualifiedAfter":2,"need":2,"alreadyPassed":false,
           "pastStage":false,"passed":true,"graduated":false,"nextStage":3},
         "share":{"status":"none"}}
        """.trimIndent(),
    )

    private val profile = Profile.blank("p1")

    @Test
    fun modelOfTheDesignExample() {
        val m = ReviewModels.model(pass, profile, readOnly = false, focus = null, today = today)
        assertEquals(listOf("2", "false"), listOf(m.mode.toString(), m.readOnly.toString()))
        assertFalse(m.past)
        assertEquals("2단계 · 3회차 · 18분 · 4.2km · 빗길", m.sub)
        val sec = m.sections
        assertEquals("rv.end.good", sec.heroKey)
        assertEquals("오늘 고생 많았어. 다음엔 30 구역 하나만 신경 써 보자.", sec.heroText)
        assertEquals("이번 회차 과제 83% (20/24) · 통과 회차 2/2", sec.achievement?.text)
        assertEquals(listOf("멈출 때 부드럽게 멈추기" to "7/8번", "직진할 때 핸들" to "첫날보다 30% 안정"), sec.good.map { it.label to it.value })
        assertEquals(listOf("30 구역 초과" to "1번", "깜빡이 켜고 바로 꺾음" to "1번"), sec.bad.map { it.label to it.value })
        assertFalse(sec.badEmpty)
        assertEquals("30 구역 지키기", sec.next)
        assertEquals(listOf(F2RowUi("f2-1", "28km/h에서 1.4초 만에 정지", null)), m.f2)
        assertEquals(1, m.f2Pending)
        assertEquals(listOf("38m → 52m", "3/8 → 1/8", "첫날보다 30% 안정"), sec.k4Rows.map { it.value })
        assertEquals("코치가 10분에 4.2번 말했어요 · 칭찬 6 : 교정 2", sec.k6)
        assertEquals(listOf(ReviewTipUi("빗길 회차예요. 빗길에선 브레이크를 일찍, 나눠서 밟아요.", R.drawable.ic_rain)), m.tips)
        assertEquals("출발은 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.", sec.fade)
        assertEquals(CourseLinkUi(false, "초등학교 뒤 골목", "c2-B", null), m.course)
        assertEquals(ShareUi(ShareStatus.NONE, "가족"), m.share)
        assertEquals(PassUi(graduated = false, stage = 2), m.pass)
        assertEquals(2, m.again)
    }

    @Test
    fun readOnlySummaryOnlyAndPastDay() {
        val ro = ReviewModels.model(pass, profile, readOnly = true, focus = Review.FOCUS_F2, today = today)
        assertEquals(listOf<Any?>(true, "f2", null, null), listOf(ro.readOnly, ro.focus, ro.pass, ro.again))
        assertFalse("오늘 회차는 '오늘 연습 돌아보기'", ro.past)
        val later = ReviewModels.model(pass, profile, readOnly = true, focus = null, today = today.plusDays(2))
        assertEquals("11월 10일", later.pastDay)
        assertEquals("11월 10일 · 2단계 · 3회차 · 18분 · 4.2km · 빗길", later.sub)
        val x = ReviewModels.model(
            SessionRecord(id = "sess-x", mode = 1, noInMode = 1, f2 = listOf(F2Check(id = "f2-1", v0 = 28.0, dur = 1.4))),
            profile, readOnly = true, focus = null, today = today,
        )
        assertEquals("rv.end.none", x.sections.heroKey)
        assertNull(x.sections.achievement)
        assertNull(x.sections.next)
        assertNull(x.sections.k4First)
        assertNull(x.sections.k6)
        assertTrue(x.sections.good.isEmpty() && x.sections.bad.isEmpty())
        assertFalse(x.sections.badEmpty)
        assertNull(x.sections.fade)
        assertNull(x.course)
        assertEquals("28km/h에서 1.4초 만에 정지", x.f2[0].text)
        val sent = ReviewModels.model(pass.copy(share = ShareState(status = ShareStatus.SENT, to = "아버지")), profile, false, null, today)
        assertEquals(ShareUi(ShareStatus.SENT, "아버지"), sent.share)
    }

    @Test
    fun hardBrakeChoiceIsStoredOnTheRecordOnly() {
        val rec = pass.copy(f2 = pass.f2 + F2Check(id = "f2-2", v0 = 35.0, v1 = 0.0, dur = 1.6, stopped = true))
        val a = requireNotNull(ReviewModels.withF2Choice(rec, "f2-1", F2Choice.AVOID))
        val b = requireNotNull(ReviewModels.withF2Choice(a, "f2-2", F2Choice.MISTAKE))
        assertEquals(listOf(F2Choice.AVOID, F2Choice.MISTAKE), b.f2.map { it.choice })
        assertEquals("진행은 그대로", rec.progress, b.progress)
        assertNull("없는 건", ReviewModels.withF2Choice(rec, "f2-9", F2Choice.AVOID))
        assertEquals(0, ReviewModels.model(b, profile, false, null, today).f2Pending)
    }

    @Test
    fun saveCourseTeamAndPlace() {
        val (p1, ok) = ReviewModels.saveCourse(profile, pass, "2026-11-10T10:00:00+09:00")
        assertTrue(ok)
        assertEquals(listOf(listOf<Any?>(2, CourseSource.SAVED, "c2-B", "초등학교 뒤 골목")), p1.myCourses.map { listOf(it.mode, it.source, it.courseRef, it.name) })
        assertTrue(ReviewModels.model(pass, p1, false, null, today).course?.saved == true)
        assertFalse("두 번 저장하지 않음", ReviewModels.saveCourse(p1, pass, "x").second)
        val gym = SessionRecord(id = "sess-gym", mode = 1, noInMode = 1, placeId = "gym")
        assertEquals(CourseLinkUi(false, "시민체육관", null, "gym"), ReviewModels.courseInfo(gym, profile))
        val (p2, ok2) = ReviewModels.saveCourse(profile, gym, "x")
        assertTrue(ok2)
        assertEquals(listOf<Any?>(1, CourseSource.SAVED, "home", "시민체육관"), p2.myCourses.single().let { listOf(it.mode, it.source, it.town, it.name) })
        for (r in listOf(SessionRecord(id = "f", mode = 5, placeId = "mart"), SessionRecord(id = "l", mode = 8, courseId = "c8-C"), SessionRecord(id = "h", mode = 2, placeId = "home"))) {
            assertNull(r.id, ReviewModels.courseInfo(r, profile))
        }
    }

    @Test
    fun passTargetOnlyWhenThisSessionPassed() {
        assertEquals(PassUi(graduated = false, stage = 2), ReviewModels.passTarget(pass))
        val grad = pass.copy(mode = 4, stage = 4, progress = ProgressResult(stage = 4, passed = true, graduated = true))
        assertEquals(PassUi(graduated = true, stage = 4), ReviewModels.passTarget(grad))
        assertNull(ReviewModels.passTarget(pass.copy(progress = ProgressResult(stage = 2, passed = false, qualifiedAfter = 1))))
        assertEquals(CourseRecommend(3), againRouteFor(3))
    }

    @Test
    fun viewModelSpeaksOnceAndSavesTheChoice() = runTest(main.dispatcher.scheduler) {
        val store = InMemoryBaroStore()
        store.add(pass)
        val voice = InMemoryVoiceLog()
        val clock = Clock.fixed(ZonedDateTime.of(2026, 11, 10, 10, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"))
        val vm = ReviewViewModel(SavedStateHandle(mapOf(ReviewViewModel.ARG_RECORD to pass.id)), store, store, voice, clock)
        runCurrent()
        assertEquals(listOf("rv.end.good"), voice.entries.value.map { it.key })
        assertEquals(pass.id, vm.uiState.value.model?.id)
        vm.chooseF2("f2-1", F2Choice.MISTAKE)
        runCurrent()
        assertEquals(F2Choice.MISTAKE, store.get(pass.id.orEmpty())?.f2?.first()?.choice)
        assertEquals(0, vm.uiState.value.model?.f2Pending)
        val ro = ReviewViewModel(SavedStateHandle(mapOf(ReviewViewModel.ARG_RECORD to pass.id, ReviewViewModel.ARG_READ_ONLY to true)), store, store, voice, clock)
        runCurrent()
        assertEquals("기록에서 다시 열면 말하지 않는다", 1, voice.entries.value.size)
        assertTrue(ro.uiState.value.model?.readOnly == true)
    }
}
