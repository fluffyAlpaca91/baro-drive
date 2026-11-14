package com.barodrive.app.feature.parking

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.barodrive.app.R
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Coach
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.ParkingHandbrake
import com.barodrive.core.model.ParkingResult
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.Profile
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.ShareStatus
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.WeatherType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PK-4 글과 리뷰 틀 조각 (프로토타입 parking-guide.js `headline`·`rowsOf`·`subOf`, review.js `subText`·mycourses.js `saveRecord`,
 * e2e t5b-guide·t5b-left·t5b-stop의 PK-4 글). 실제 안내 기록은 골든 m6-S6-P-normal의 `record.parking` 그대로다.
 */
class ParkingDoneTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val res = XmlTextRes

    private fun golden(): SessionRecord = BaroJson.decodeFromString(SessionRecord.serializer(), GOLDEN_M6)

    private fun m(rec: SessionRecord, coach: Coach = Coach.DAD, readOnly: Boolean = false) =
        ModeReviews.model(rec, Profile(coach = coach), readOnly, LocalDate.of(2026, 11, 11))

    @Test
    fun goldenParkingRunReadsLikeThePrototype() {
        val rec = golden()
        val model = m(rec)
        assertEquals("평행 · 오른쪽 칸 · 1회차", ParkingDoneText.sub(rec, res))
        assertEquals("평행 · 오른쪽 칸 주차를 마쳤어요", ParkingDoneText.headline(rec, res))
        assertEquals("pk.done", ParkingDoneText.heroKey(rec))
        assertEquals("주차 잘 마쳤어. 고생했어.", ModeLines.render("pk.done", Coach.DAD))
        val rows = ParkingDoneText.rows(model, res)
        assertEquals(listOf("time", "steps", "slow", "wheel", "handbrake"), rows.map { it.id })
        assertEquals(
            listOf("걸린 시간" to "1분 24초", "단계" to "8 / 8", "'천천히' 안내" to "1번", "바퀴" to "꺾여 있었어요", "사이드브레이크" to "안 채웠어요"),
            rows.map { it.label to it.value },
        )
        assertEquals(listOf(false, true, false, false, false), rows.map { it.good })
        assertEquals(listOf(false, false, false, true, true), rows.map { it.warn })
        assertEquals("바퀴를 똑바로 두면 다음 출발이 편해.", rows[3].line)
        assertEquals("사이드브레이크 채우자.", rows[4].line)
        assertEquals("다른 주차", res.text(R.string.pk4_other))
        assertEquals("홈으로", res.text(R.string.pk_rv_home))
    }

    @Test
    fun stoppedHalfwayAndOtherCoaches() {
        // t5b-stop: 3단계에서 P → [여기서 마치기] → '3단계까지 했어요', pk.done 없음
        val rec = golden().let { it.copy(parking = it.parking!!.copy(done = false, reached = 3, pk2 = 0, wheel = null, handbrake = null)) }
        assertEquals("평행 · 오른쪽 칸 주차를 3단계까지 했어요", ParkingDoneText.headline(rec, res))
        assertNull(ParkingDoneText.heroKey(rec))
        val rows = ParkingDoneText.rows(m(rec), res)
        assertEquals(listOf("3 / 8", "없었어요", "확인하지 못했어요", "확인하지 못했어요"), rows.drop(1).map { it.value })
        assertEquals(listOf(false, true, false, false), rows.drop(1).map { it.good })
        // 엄마 코치 문장, 차속을 못 읽은 회차, 사이드브레이크를 물어본 회차
        val mom = golden().let {
            it.copy(caps = SignalCaps(speed = false), parking = it.parking!!.copy(handbrake = ParkingHandbrake(result = EventResult.INFO, on = null)))
        }
        val momRows = ParkingDoneText.rows(m(mom, Coach.MOM), res)
        assertEquals("확인하지 못했어요", momRows.first { it.id == "slow" }.value)
        assertEquals("물어봤어요", momRows.first { it.id == "handbrake" }.value)
        assertEquals("바퀴 똑바로! 다음에 출발하기 편해!", momRows.first { it.id == "wheel" }.line)
        // 판정 없이 사이드브레이크를 채운 것만 읽음
        val on = golden().let { it.copy(parking = it.parking!!.copy(handbrake = ParkingHandbrake(result = null, on = true))) }
        assertEquals("채웠어요", ParkingDoneText.rows(m(on), res).last().value)
        assertTrue(ParkingDoneText.rows(m(on), res).last().good)
    }

    @Test
    fun virtualParkingRecordsShowTheirOwnRows() {
        val rec = SessionRecord(
            kind = RecordKind.VIRTUAL_PARKING,
            mode = 6,
            parking = ParkingResult(type = ParkingType.FRONT, side = TurnDir.LEFT, key = "front-L", virtual = true, result = "success", sec = 45.0, switches = 0, wheelStraight = true),
        )
        assertEquals("전방 · 왼쪽 칸 · 세워 두고 연습", ParkingDoneText.headline(rec, res))
        assertEquals("전방 · 왼쪽 칸 · 세워 두고 연습", ParkingDoneText.sub(rec, res))
        assertNull("세워 두고 연습은 히어로·말 없음", ParkingDoneText.heroKey(rec))
        assertTrue(ParkingDoneText.done(rec))
        assertEquals(
            listOf("결과" to "주차 완료", "걸린 시간" to "45초", "앞뒤로 바꾼 횟수" to "0번", "바퀴" to "똑바로"),
            ParkingDoneText.rows(m(rec), res).map { it.label to it.value },
        )
        val stopped = rec.copy(parking = rec.parking!!.copy(result = "stopped", sec = 120.0, switches = 3))
        assertEquals(listOf("그만했어요", "2분", "3번"), ParkingDoneText.rows(m(stopped), res).map { it.value })
        assertNull("세워 두고 연습은 내 코스 줄이 없다", m(rec).course)
    }

    @Test
    fun courseLinkAndSubtitleFollowTheReviewKit() {
        val rec = golden().copy(courseId = "c6-A")
        val course = ModeReviews.courseOf(rec, Profile())
        assertEquals(ReviewCourse("시민체육관 주차장", saved = false), course)
        val list = ModeReviews.saveCourse(emptyList(), rec, "home", "2026-11-11T09:30:00+09:00")
        assertEquals(listOf("mc-c6-A"), list?.map { it.id })
        assertEquals("c6-A", list?.single()?.courseRef)
        assertNull("이미 있으면 저장하지 않음", ModeReviews.saveCourse(list.orEmpty(), rec, "home", "x"))
        val taken = listOf(MyCourse(id = "mc-c6-A", mode = 1, courseRef = "c6-A"))
        assertEquals("mc-c6-A-2", ModeReviews.saveCourse(taken, rec, "home", "x")?.last()?.id)
        assertTrue(ModeReviews.courseOf(rec, Profile(myCourses = list.orEmpty()))!!.saved)
        // 장소 회차 · 지금 위치(집)는 코스로 만들 수 없음
        assertEquals("시민체육관", ModeReviews.courseOf(rec.copy(courseId = null, placeId = "gym"), Profile())?.name)
        assertNull(ModeReviews.courseOf(rec.copy(courseId = null, placeId = "home"), Profile()))
        // 언덕 회차 부제(골든 m7 S7-B: 352.4초 · 2239m) · 비 · 지난 날을 읽기 전용으로
        val hill = SessionRecord(mode = 7, noInMode = 1, courseId = "c7-B", durationSec = 352.4, distanceM = 2239.0, startedAt = "2026-11-11T09:00:00+09:00")
        assertEquals("언덕 · 1회차 · 6분 · 2.2km", ModeReviews.subText(m(hill), res))
        val rain = hill.copy(weather = SessionWeather(type = WeatherType.RAIN), startedAt = "2026-11-09T18:20:00+09:00")
        assertEquals("11월 9일 · 언덕 · 1회차 · 6분 · 2.2km · 빗길", ModeReviews.subText(m(rain, readOnly = true), res))
        assertEquals("오늘 회차는 날짜를 붙이지 않음", "언덕 · 1회차 · 6분 · 2.2km", ModeReviews.subText(m(hill, readOnly = true), res))
        assertEquals("45초", ModeReviews.fmtMin(44.6, res))
        assertEquals("1시간 5분", ModeReviews.fmtMin(3900.0, res))
        assertEquals("2시간", ModeReviews.fmtMin(7200.0, res))
    }

    @Test
    fun shareStateUsesTheFamilyName() {
        val rec = golden().copy(share = ShareState(status = ShareStatus.SENT, to = "아버지"))
        assertEquals(ReviewShare(ShareStatus.SENT, "아버지"), m(rec).share)
        assertEquals("보냈어요 · 아버지", res.text(R.string.pk_rv_share_sent, "아버지"))
        assertEquals(ReviewShare(ShareStatus.NONE, "가족"), m(golden()).share)
    }

    @Test
    fun viewModelSpeaksPkDoneOnceAndSavesTheCourse() = runTest {
        val store = InMemoryBaroStore()
        val saved = store.add(golden().copy(id = "sess-test-pk4-speak", courseId = "c6-A"))
        val id = requireNotNull(saved.id)
        val voice = InMemoryVoiceLog()
        val clock = Clock.fixed(Instant.parse("2026-11-11T00:30:00Z"), ZoneId.of("Asia/Seoul"))
        val vm = ParkingDoneViewModel(SavedStateHandle(mapOf("recordId" to id, "readOnly" to false)), store, store, voice, clock)
        vm.uiState.test {
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertEquals(id, s.model?.record?.id)
            assertEquals(listOf("pk.done"), voice.entries.value.map { it.key })
            vm.saveCourse()
            var after = awaitItem()
            while (after.model?.course?.saved != true) after = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        vm.effects.test { assertEquals(ReviewEffect.Toast(R.string.pk_rv_course_saved_toast), awaitItem()) }
        assertEquals("2026-11-11T09:30:00+09:00", store.current().myCourses.single().savedAt)
        // 같은 기록을 다시 열어도(화면을 다시 만들어도) 다시 말하지 않음 · 읽기 전용은 말하지 않음
        val again = ParkingDoneViewModel(SavedStateHandle(mapOf("recordId" to id)), store, store, voice, clock)
        again.uiState.test {
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, voice.entries.value.size)
        assertFalse(again.readOnly)
    }

    @Test
    fun missingRecordShowsTheEmptyState() = runTest {
        val store = InMemoryBaroStore()
        val vm = ParkingDoneViewModel(SavedStateHandle(mapOf("recordId" to "nope", "readOnly" to true)), store, store, InMemoryVoiceLog(), Clock.systemUTC())
        vm.uiState.test {
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertNull(s.model)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(vm.readOnly)
    }

    companion object {
        /** 골든 m6-S6-P-normal 회차 기록에서 PK-4가 읽는 칸. */
        const val GOLDEN_M6 = """{"mode":6,"noInMode":1,"courseId":null,"placeId":null,"simSec":89.4,"distanceM":49,"tasks":[],"nextTask":null,
            "parking":{"type":"parallel","side":"R","key":"parallel-R","total":8,"reached":8,"done":true,"sec":83.5,"pressed":1,
              "steps":[{"n":1,"signal":"press","by":"press","t":8.4},{"n":2,"signal":"stop","by":"signal","t":20.3},
                {"n":3,"signal":"gearR","by":"signal","t":27.5},{"n":4,"signal":"steerFull","by":"signal","t":33.4},
                {"n":5,"signal":"steerStraight","by":"signal","t":45.9},{"n":6,"signal":"steerFull","by":"signal","t":63.4},
                {"n":7,"signal":"stop","by":"signal","t":71.1},{"n":8,"signal":"gearP","by":"signal","t":83.5}],
              "pk2":1,"wheel":{"steer":-8,"straight":false},"handbrake":{"result":"fail","on":false},"model":"avante"},
            "tips":[],"f2":[],"share":{"status":"none"},"kind":null,"startedAt":"2026-11-11T09:00:00+09:00"}"""
    }
}
