package com.barodrive.app.feature.home

import app.cash.turbine.test
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.Coach
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.LicenseInfo
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StageState
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 프로토타입 tests/unit/t2b_screens.test.js의 홈 부분(진행 문장·단계 카드·알림 줄·면허 유효기간) + 홈 화면 값. */
class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val today = LocalDate.of(2026, 11, 10)

    private fun fresh(): Profile = Profile.blank("p1")

    private fun Profile.withStages(current: Int, unlocked: Int, vararg passed: Int, qualified: Map<Int, Int> = emptyMap()): Profile {
        val stages = (1..4).associateWith { n -> StageState(qualified = qualified[n] ?: 0, passedAt = if (n in passed) "2026-11-0$n" else null) }
        return copy(progress = progress.copy(currentStage = current, unlockedStage = unlocked, stages = stages))
    }

    @Test
    fun progressSentenceStageCardsAndGraduation() {
        var s = HomeRules.summary(fresh(), today)
        assertEquals(1, s.stage)
        assertEquals(0, s.k)
        assertEquals(ProgressTail.NEXT_STAGE, s.tail)
        assertEquals(listOf(StageCardState.NOW, StageCardState.LOCKED, StageCardState.LOCKED, StageCardState.LOCKED), s.stages)
        assertFalse(s.graduated)

        s = HomeRules.summary(fresh().withStages(1, 1, qualified = mapOf(1 to 1)), today)
        assertEquals(1, s.k)
        assertEquals(ProgressTail.ONE_MORE, s.tail)

        // 2단계부터 시작(1단계는 건너뜀) — 결정-89
        assertEquals(
            listOf(StageCardState.OPEN, StageCardState.NOW, StageCardState.LOCKED, StageCardState.LOCKED),
            HomeRules.summary(fresh().withStages(2, 2), today).stages,
        )
        assertEquals(
            listOf(StageCardState.OPEN, StageCardState.PASSED, StageCardState.NOW, StageCardState.LOCKED),
            HomeRules.summary(fresh().withStages(3, 3, 2), today).stages,
        )
        assertEquals(ProgressTail.ALL_STAGES, HomeRules.summary(fresh().withStages(4, 4, 2), today).tail)

        val grad = fresh().withStages(4, 4, 1, 2, 3, 4).let { it.copy(progress = it.progress.copy(graduatedAt = "2026-11-09T10:00:00+09:00")) }
        s = HomeRules.summary(grad, today)
        assertTrue(s.graduated)
        assertEquals(2, s.k)
        assertEquals(ProgressTail.NONE, s.tail)
        assertEquals(List(4) { StageCardState.PASSED }, s.stages)
    }

    @Test
    fun alertRowsCountUncheckedHardBrakesAndUnsentSummaries() {
        val p = fresh()
        assertEquals(0, HomeRules.summary(p, today).f2Total)
        assertEquals(0, HomeRules.summary(p, today).share)
        val q = p.copy(
            sessions = listOf(
                SessionRecord(id = "sess-3", mode = 5, f2 = listOf(F2Check(id = "f2-1"), F2Check(id = "f2-2", choice = F2Choice.MISTAKE))),
                SessionRecord(id = "sess-2", mode = 2, f2 = listOf(F2Check(id = "f2-1", choice = F2Choice.AVOID))),
                SessionRecord(id = "sess-1", mode = 2, f2 = listOf(F2Check(id = "f2-1"), F2Check(id = "f2-2"))),
                SessionRecord(id = "sess-0", mode = 6),
            ),
            shareQueue = listOf("sess-3", "sess-1"),
        )
        val s = HomeRules.summary(q, today)
        assertEquals(3, s.f2Total)
        assertEquals("최신 회차가 먼저", listOf(F2Ref("sess-3", 5, 1), F2Ref("sess-1", 2, 2)), s.f2Records)
        assertEquals(2, s.share)
    }

    @Test
    fun licenseExpiryIsIssuePlusOneYearMinusOneDay() {
        assertEquals("2027-03-31", HomeRules.licenseExpiry("2026-04-01"))
        assertEquals("윤년", "2025-02-28", HomeRules.licenseExpiry("2024-02-29"))
        assertEquals(null, HomeRules.licenseExpiry(null))
        assertFalse("만료일 당일은 아직 유효", HomeRules.licenseExpired("2026-04-01", LocalDate.of(2027, 3, 31)))
        assertTrue(HomeRules.licenseExpired("2026-04-01", LocalDate.of(2027, 4, 1)))
        assertFalse("발급일이 없으면 만료 아님", HomeRules.licenseExpired(null, today))
        val s = HomeRules.summary(fresh().copy(license = LicenseInfo(issueDate = "2025-09-01")), today)
        assertEquals("2025-09-01", s.licenseIssueDate)
        assertEquals("2026-08-31", s.licenseExpiresOn)
        assertTrue(s.licenseExpired)
    }

    @Test
    fun viewModelFollowsTheStoredProfile() = runTest {
        val store = InMemoryBaroStore()
        val clock = Clock.fixed(ZonedDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"))
        val vm = HomeViewModel(store, clock)
        vm.uiState.test {
            var s = awaitItem()
            if (!s.loaded) s = awaitItem()
            assertEquals(Coach.DAD, s.coach)
            assertEquals(1, s.summary.stage)
            store.update { it.copy(coach = Coach.MOM, license = LicenseInfo(issueDate = "2025-09-01")) }
            val next = awaitItem()
            assertEquals(Coach.MOM, next.coach)
            assertTrue(next.summary.licenseExpired)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
