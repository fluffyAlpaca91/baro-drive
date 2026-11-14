package com.barodrive.app.feature.share

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.AppState
import com.barodrive.core.model.Consent
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.Gear
import com.barodrive.core.model.NextTask
import com.barodrive.core.model.ParkingResult
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ProgressResult
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.ReviewLine
import com.barodrive.core.model.ScoreResult
import com.barodrive.core.model.Severe
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.ShareStatus
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.WeatherType
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 가족에게 요약 보내기 (프로토타입 tests/unit/t9_settings.test.js '공유' 묶음 + RV-7 시트). */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 11, 11, 15, 0, 0, 0, zone)
    private val clock = Clock.fixed(now.toInstant(), zone)

    private fun iso(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toOffsetDateTime().toString()

    private fun stageRec(id: String = "sess-a") = SessionRecord(
        id = id, mode = 2, stage = 2, noInMode = 3, courseId = "c2-B", startedAt = iso(2026, 11, 11, 9, 12), durationSec = 1080.0,
        distanceM = 4200.0, arrived = true, weather = SessionWeather(type = WeatherType.RAIN),
        tasks = listOf(TaskResult("T-30", "30 구역 지키기", success = 2, total = 3), TaskResult("T-SIGNAL30", "깜빡이", success = 18, total = 21)),
        achievement = 0.833, qualified = true, nextTask = NextTask("T-30", "30 구역 지키기", "30 구역"),
        good = listOf(ReviewLine("T-SIGNAL30", "회전 30m 전 깜빡이 18/21번")),
        f2 = listOf(F2Check(id = "f2-1", v0 = 28.0, dur = 1.4)), progress = ProgressResult(stage = 2, passed = false),
    )

    private val father = Profile(consent = Consent(record = true, family = true, familyName = "아버지"))

    @Test
    fun summaryHasNoLocation() {
        val s = ShareRules.summary(stageRec(), father, now)
        assertEquals("2단계 3회차 · 초등학교 뒤 골목", s.title)
        assertEquals("11월 11일 09:12", s.whenText)
        assertEquals("아버지", s.to)
        assertEquals(
            listOf(
                "시간·거리" to "18분 · 4.2km", "과제" to "83% 해냈어요 (20/24)", "잘한 것" to "회전 30m 전 깜빡이 18/21번",
                "다음 과제" to "30 구역 지키기", "급제동" to "1번 · 아직 확인 전", "날씨" to "빗길",
            ),
            s.lines.map { it.label to it.text },
        )
        assertFalse("위치 없음", s.toString().contains("placeId"))
        val f2 = ShareRules.summary(
            stageRec().copy(
                f2 = listOf(F2Check(choice = F2Choice.AVOID), F2Check(choice = F2Choice.MISTAKE), F2Check()),
                weather = SessionWeather(type = WeatherType.CLEAR, severe = Severe.ICE),
            ),
            father, now,
        )
        assertEquals("3번 · 위험 피하기 1번 · 내 실수 1번 · 확인 전 1번", f2.lines.first { it.id == "f2" }.text)
        assertEquals("아주 나쁜 날", f2.lines.first { it.id == "weather" }.text)
        assertEquals(
            "1번 · 본인 확인: 위험 피하기",
            ShareRules.summary(stageRec().copy(f2 = listOf(F2Check(choice = F2Choice.AVOID))), father, now).lines.first { it.id == "f2" }.text,
        )
        val auto = SessionRecord(id = "a", mode = 5, noInMode = 2, placeId = "mart", durationSec = 1860.0, distanceM = 12400.0, score = ScoreResult(total = 86))
        assertEquals(listOf("31분 · 12.4km", "마트까지", "86점"), ShareRules.summary(auto, father, now).lines.map { it.text })
        val v = SessionRecord(
            id = "sess-v", kind = RecordKind.VIRTUAL_PARKING, mode = 6, durationSec = 42.0,
            parking = ParkingResult(virtual = true, type = ParkingType.PARALLEL, side = TurnDir.RIGHT, result = "success", sec = 42.0),
        )
        assertEquals(listOf("42초", "세워 두고 연습 · 평행 · 오른쪽 · 성공"), ShareRules.summary(v, father, now).lines.map { it.text })
        val passed = stageRec().copy(progress = ProgressResult(stage = 2, passed = true))
        assertEquals("2단계를 통과했어요", ShareRules.summary(passed, father, now).lines.first { it.id == "pass" }.text)
    }

    @Test
    fun queueSentCardAndCancel() {
        var p = Profile(consent = Consent(familyName = "아버지"))
        var r = stageRec()
        ShareRules.markQueued(p, r, "아버지", now).let { (np, nr) -> p = np; r = nr }
        assertEquals(ShareState(ShareStatus.QUEUED, to = "아버지", queuedAt = "2026-11-11T15:00:00+09:00"), r.share)
        assertEquals(listOf("sess-a"), p.shareQueue)
        assertEquals("두 번 넣지 않음", listOf("sess-a"), ShareRules.markQueued(p, r, "아버지", now).first.shareQueue)
        assertEquals(ShareLinkInfo(ShareStatus.QUEUED, "연결되면 보내요"), ShareRules.linkInfo(r, p))
        ShareRules.markSent(p, r, "아버지", now).let { (np, nr) -> p = np; r = nr }
        assertEquals(ShareStatus.SENT, r.share.status)
        assertEquals(emptyList<String>(), p.shareQueue)
        assertEquals(ShareLinkInfo(ShareStatus.SENT, "보냈어요 · 아버지"), ShareRules.linkInfo(r, p))
        assertEquals(ShareLinkInfo(ShareStatus.NONE, "가족에게 요약 보내기"), ShareRules.linkInfo(stageRec("n"), p))
        val c = ShareRules.card(r, p, "아버지", now)
        assertEquals("sess-a" to "아버지", c.recordId to c.to)
        assertEquals("운전자 1", c.from)
        assertEquals("2단계 3회차 · 초등학교 뒤 골목", c.title)
        assertTrue(c.lines.size >= 5)
        val (list, n) = ShareRules.cancelQueued(listOf(r, stageRec("sess-b").copy(share = ShareState(ShareStatus.QUEUED))))
        assertEquals(1, n)
        assertEquals(ShareStatus.NONE, list[1].share.status)
    }

    private fun TestScope.center(profile: Profile, session: FakeSessionController = FakeSessionController()): Pair<ShareCenter, InMemoryBaroStore> {
        val store = InMemoryBaroStore(AppState(profiles = mapOf("p1" to profile)), clock)
        return ShareCenter(store, store, session, clock, backgroundScope) to store
    }

    @Test
    fun sendQueuesWhenOfflineAndSendsAfterAMomentWhenOnline() = runTest {
        val (share, store) = center(father.copy(sessions = listOf(stageRec(), stageRec("sess-b"))))
        share.setOnline(false)
        assertEquals(ShareSendResult.Queued, share.send("sess-a"))
        assertEquals(listOf("sess-a"), store.current().shareQueue)
        assertEquals(ShareStatus.QUEUED, store.get("sess-a")?.share?.status)
        assertNull(share.send("없음"))
        // 연결된 채로 보내면 잠깐 뒤 보냄 + 가족 카드(보낼 예정 줄은 연결이 '바뀔' 때만 보낸다)
        val (online, store2) = center(father.copy(sessions = listOf(stageRec("sess-b"))))
        assertEquals(ShareSendResult.Sent("아버지"), online.send("sess-b"))
        assertEquals(ShareStatus.SENT, store2.get("sess-b")?.share?.status)
        assertEquals("아버지", store2.get("sess-b")?.share?.to)
        assertEquals(listOf("sess-b"), online.familyCards.value.map { it.recordId })
    }

    @Test
    fun queuedSummariesGoWhenConnectionReturnsWhileParkedOrAtNextP() = runTest {
        val queued = { id: String -> stageRec(id).copy(share = ShareState(ShareStatus.QUEUED, to = "아버지")) }
        val session = FakeSessionController()
        session.setState { it.copy(gear = Gear.P, speed = 0.0) }
        val (share, store) = center(father.copy(sessions = listOf(queued("sess-a"), queued("sess-b")), shareQueue = listOf("sess-a", "sess-b")), session)
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { share.notices.toList(notices) }
        runCurrent()
        assertEquals("연결된 채로는 보내지 않음", 2, store.current().shareQueue.size)
        share.setOnline(false)
        share.setOnline(true)
        runCurrent()
        assertEquals("끊김 → 연결: 정차 중이라 바로 보냄", emptyList<String>(), store.current().shareQueue)
        assertEquals(listOf("보내지 못한 요약 2건을 보냈어요"), notices)
        assertEquals(listOf("sess-a", "sess-b"), share.familyCards.value.map { it.recordId })
        // 주행 중에 연결 → 다음 P에서
        store.update { it.copy(shareQueue = listOf("sess-a")) }
        share.setOnline(false)
        session.setState { it.copy(gear = Gear.D, speed = 30.0) }
        runCurrent()
        share.setOnline(true)
        runCurrent()
        assertEquals("주행 중에는 보내지 않음", listOf("sess-a"), store.current().shareQueue)
        assertEquals(0, share.flush())
        session.setState { it.copy(gear = Gear.P, speed = 0.0) }
        runCurrent()
        assertEquals("다음 정차(P)에서 보냄", emptyList<String>(), store.current().shareQueue)
        assertEquals("보내지 못한 요약 1건을 보냈어요", notices.last())
    }

    @Test
    fun sheetAsksConsentFirstThenSends() = runTest {
        val p = Profile(consent = Consent(record = true, family = false, familyName = "아버지"), sessions = listOf(stageRec()))
        val (share, store) = center(p)
        val vm = ShareSheetViewModel(SavedStateHandle(mapOf("recordId" to "sess-a")), store, store, share, clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(ShareStep.CONSENT, vm.uiState.value.step)
        vm.agree()
        assertTrue(store.current().consent.family)
        assertEquals(ShareStep.PREVIEW, vm.uiState.value.step)
        assertEquals("아버지", vm.uiState.value.summary?.to)
        assertFalse(vm.uiState.value.wasSent)
        vm.effects.test {
            vm.send()
            assertEquals(ShareEffect.Toast("아버지에게 보냈어요"), awaitItem())
            assertEquals(ShareEffect.Close, awaitItem())
        }
        assertEquals(ShareStatus.SENT, store.get("sess-a")?.share?.status)
    }

    @Test
    fun sheetQueuesWhenOffline() = runTest {
        val (share, store) = center(father.copy(sessions = listOf(stageRec())))
        share.setOnline(false)
        val vm = ShareSheetViewModel(SavedStateHandle(mapOf("recordId" to "sess-a")), store, store, share, clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertFalse(vm.uiState.value.online)
        vm.effects.test {
            vm.send()
            assertEquals(ShareEffect.Toast("연결되면 보낼게요"), awaitItem())
            assertEquals(ShareEffect.Close, awaitItem())
        }
        assertEquals(listOf("sess-a"), store.current().shareQueue)
    }
}
