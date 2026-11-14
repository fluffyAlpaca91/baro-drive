package com.barodrive.app.feature.history

import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.LicenseResult
import com.barodrive.core.model.NextTask
import com.barodrive.core.model.ParkingResult
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.ProgressResult
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.ReviewLine
import com.barodrive.core.model.ScoreResult
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.ShareStatus
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.WeatherType
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 연습 기록 줄 글·태그·고르기 (프로토타입 tests/unit/t9_settings.test.js '연습 기록' 묶음 — 같은 기록, 같은 기대값). */
class RecordTextTest {
    private val zone = ZoneId.of("Asia/Seoul")

    /** 2026-11-11 15:00. */
    private val now = ZonedDateTime.of(2026, 11, 11, 15, 0, 0, 0, zone)

    private fun iso(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toOffsetDateTime().toString()

    private fun stageRec() = SessionRecord(
        id = "sess-a", mode = 2, stage = 2, noInMode = 3, courseId = "c2-B", startedAt = iso(2026, 11, 11, 9, 12), durationSec = 1080.0,
        distanceM = 4200.0, arrived = true, weather = SessionWeather(type = WeatherType.RAIN),
        tasks = listOf(TaskResult("T-30", "30 구역 지키기", success = 2, total = 3), TaskResult("T-SIGNAL30", "깜빡이", success = 18, total = 21)),
        achievement = 0.833, qualified = true, nextTask = NextTask("T-30", "30 구역 지키기", "30 구역"),
        good = listOf(ReviewLine("T-SIGNAL30", "회전 30m 전 깜빡이 18/21번")),
        f2 = listOf(F2Check(id = "f2-1", v0 = 28.0, dur = 1.4)), progress = ProgressResult(stage = 2, passed = false),
    )

    private fun virtualRec() = SessionRecord(
        id = "sess-v", kind = RecordKind.VIRTUAL_PARKING, mode = 6, startedAt = iso(2026, 11, 10, 20, 5), durationSec = 42.0,
        parking = ParkingResult(virtual = true, type = ParkingType.PARALLEL, side = TurnDir.RIGHT, key = "parallel-R", result = "success", sec = 42.0),
    )

    @Test
    fun rowTextTitlePlaceWhenAndModeExtras() {
        val r = stageRec()
        assertEquals("2단계 3회차", RecordText.title(r))
        assertEquals("초등학교 뒤 골목", RecordText.place(r))
        assertEquals("오늘 09:12 · 18분 · 4.2km", RecordText.describe(r, now).sub)
        assertEquals("어제 23:59", RecordText.whenText(iso(2026, 11, 10, 23, 59), now))
        assertEquals("11월 1일 08:05", RecordText.whenText(iso(2026, 11, 1, 8, 5), now))
        assertEquals("2025. 12. 31. 08:05", RecordText.whenText(iso(2025, 12, 31, 8, 5), now))
        assertEquals("", RecordText.whenText(null, now))
        val auto = SessionRecord(
            id = "a", mode = 5, noInMode = 2, placeId = "mart", startedAt = iso(2026, 11, 11, 10, 0), durationSec = 1860.0, distanceM = 12400.0,
            score = ScoreResult(total = 86),
        )
        assertEquals("자율 2회차", RecordText.title(auto))
        assertEquals("마트까지", RecordText.place(auto))
        assertEquals("오늘 10:00 · 31분 · 12.4km · 86점", RecordText.describe(auto, now).sub)
        val v = virtualRec()
        assertEquals("세워 두고 연습", RecordText.title(v))
        assertEquals("평행 · 오른쪽", RecordText.place(v))
        assertEquals("어제 20:05 · 42초 · 성공", RecordText.describe(v, now).sub)
        val lic = SessionRecord(
            id = "l", mode = 8, noInMode = 1, courseId = "c8-C", durationSec = 900.0, distanceM = 5300.0, startedAt = iso(2026, 11, 11, 11, 0),
            license = LicenseResult(letter = "C", name = "시험장 주변 C 코스", complete = true),
        )
        assertEquals("면허 1회차", RecordText.title(lic))
        assertTrue(RecordText.describe(lic, now).sub.endsWith("C 코스 완주"))
        assertEquals("회차 번호가 없으면 모드 이름만", "언덕", RecordText.title(SessionRecord(mode = 7)))
    }

    @Test
    fun tagsPassedQualifiedF2AndShare() {
        assertEquals(listOf("통과 회차", "급제동 확인 1건"), RecordText.tags(stageRec()).map { it.text })
        val passed = stageRec().copy(progress = ProgressResult(stage = 2, passed = true), share = ShareState(ShareStatus.SENT, to = "아버지"))
        assertEquals(listOf("2단계 통과", "급제동 확인 1건", "보냈어요"), RecordText.tags(passed).map { it.text })
        val queued = stageRec().copy(qualified = false, f2 = listOf(F2Check(choice = F2Choice.AVOID)), share = ShareState(ShareStatus.QUEUED))
        assertEquals(listOf("share"), RecordText.tags(queued).map { it.id })
        assertEquals("보낼 예정", RecordText.tags(queued.copy(f2 = emptyList()))[0].text)
        assertEquals("단계 연습이 아니면 통과 회차 태그 없음", emptyList<RecordTag>(), RecordText.tags(SessionRecord(mode = 5, qualified = true)))
        assertEquals(2, RecordText.f2Open(stageRec().copy(f2 = listOf(F2Check(), F2Check(choice = F2Choice.MISTAKE), F2Check()))))
    }

    @Test
    fun targetOpensTheModeReviewReadOnlyExceptVirtualParking() {
        assertEquals(RecordTarget(2, "sess-a"), RecordText.target(stageRec()))
        assertEquals(RecordTarget(5, "sess-5"), RecordText.target(SessionRecord(id = "sess-5", mode = 5)))
        assertEquals(RecordTarget(6, "p"), RecordText.target(SessionRecord(id = "p", mode = 6)))
        assertNull("세워 두고 연습은 리뷰 화면이 없다", RecordText.target(virtualRec()))
        assertNull("id 없는 기록", RecordText.target(SessionRecord(mode = 2)))
    }

    @Test
    fun filtersAndLists() {
        val recs = listOf(stageRec(), virtualRec(), stageRec().copy(id = "sess-1", mode = 1, noInMode = 1))
        assertEquals(listOf("all", "stage", "parking"), RecordText.filters(recs) { false }.map { it.id })
        assertEquals(listOf("all", "stage", "free", "parking", "hill", "license"), RecordText.filters(recs).map { it.id })
        assertEquals(listOf("all", "stage", "parking", "license"), RecordText.filters(recs) { it.id == "license" }.map { it.id })
        assertEquals(listOf("sess-a", "sess-1"), RecordText.list(recs, "stage").map { it.id })
        assertEquals(listOf("sess-v"), RecordText.list(recs, "parking").map { it.id })
        assertEquals("저장된 순서(최신이 앞)", listOf("sess-a", "sess-v", "sess-1"), RecordText.list(recs, "all").map { it.id })
        assertEquals(3, RecordText.list(recs, "없는 묶음").size)
        assertEquals(emptyList<SessionRecord>(), RecordText.list(emptyList(), "all"))
    }

    @Test
    fun formatsKmAndMinutes() {
        assertEquals("12.4km", BaroFormat.km(12440.0))
        assertEquals("0.0km", BaroFormat.km(-5.0))
        assertEquals("45초", BaroFormat.min(45.0))
        assertEquals("17분", BaroFormat.min(1000.0))
        assertEquals("1시간 5분", BaroFormat.min(3900.0))
        assertEquals("2시간", BaroFormat.min(7200.0))
    }
}
