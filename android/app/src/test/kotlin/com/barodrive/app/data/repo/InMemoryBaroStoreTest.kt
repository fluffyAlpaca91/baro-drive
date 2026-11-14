package com.barodrive.app.data.repo

import app.cash.turbine.test
import com.barodrive.core.model.AppState
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 저장 규칙 (store.js — t1a_store): 기본값, 기록 id·중복, 30개 상한과 shareQueue, 지우기, 프로필 바꾸기. */
class InMemoryBaroStoreTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:12:00Z"), ZoneId.of("Asia/Seoul"))

    @Test
    fun defaultsAreTheOnboardingState() = runTest {
        val s = InMemoryBaroStore(clock = clock)
        val p = s.current()
        assertEquals("p1", p.id)
        assertEquals("운전자 1", p.name)
        assertEquals("2026-11-10T09:12:00+09:00", p.createdAt)
        assertFalse(p.onboarding.done)
        assertEquals(Coach.DAD, p.coach)
        assertEquals((1..8).associateWith { 0 }, p.sessionCount)
    }

    @Test
    fun addPutsNewestFirstWithStampIdAndSuffixes() = runTest {
        val s = InMemoryBaroStore(clock = clock)
        val a = s.add(SessionRecord(mode = 1))
        val b = s.add(SessionRecord(mode = 2))
        val c = s.add(SessionRecord(id = "sess-20261110-0912", mode = 1))
        assertEquals("sess-20261110-0912", a.id)
        assertEquals("sess-20261110-0912-2", b.id)
        assertEquals("sess-20261110-0912-3", c.id)
        assertEquals(listOf(c.id, b.id, a.id), s.list().map { it.id })
        assertEquals(listOf(c.id, a.id), s.list(mode = 1).map { it.id })
        assertEquals(b, s.get(b.id ?: ""))
    }

    @Test
    fun moreThanThirtyDropsOldestAndItsShareQueueEntry() = runTest {
        val s = InMemoryBaroStore(clock = clock)
        val first = s.add(SessionRecord(id = "r0", mode = 1))
        s.updateShareQueue { listOf("r0", "r5") }
        repeat(30) { s.add(SessionRecord(id = "r${it + 1}", mode = 1)) }
        assertEquals(30, s.list().size)
        assertNull(s.get(first.id ?: ""))
        assertEquals(listOf("r5"), s.current().shareQueue)
    }

    @Test
    fun updateRemoveAndClearKeepProgress() = runTest {
        val s = InMemoryBaroStore(clock = clock)
        s.add(SessionRecord(id = "a", mode = 1))
        s.add(SessionRecord(id = "b", mode = 1))
        s.update { it.copy(g1Baseline = 1.5, shareQueue = listOf("a")) }
        assertTrue(s.update("a") { it.copy(id = "zzz", arrived = true) })
        assertEquals(true, s.get("a")?.arrived)
        assertFalse(s.update("nope") { it })
        assertTrue(s.remove("a"))
        assertEquals(emptyList<String>(), s.current().shareQueue)
        assertFalse(s.remove("a"))
        assertEquals(1, s.clear())
        assertTrue(s.list().isEmpty())
        assertEquals(1.5, s.current().g1Baseline)
    }

    @Test
    fun profileUpdateIgnoresSessionsAndOpsWork() = runTest {
        val s = InMemoryBaroStore(clock = clock)
        s.add(SessionRecord(id = "a", mode = 1))
        s.update { it.copy(sessions = emptyList(), name = "민지") }
        assertEquals("민지", s.current().name)
        assertEquals(1, s.current().sessions.size)
        s.setCoach(Coach.MOM)
        s.setOverride(1, "C1", Delivery.AFTER)
        s.setOverride(1, "C2", Delivery.OFF)
        s.setOverride(1, "C1", null)
        assertEquals(Coach.MOM, s.current().coach)
        assertEquals(mapOf(1 to mapOf("C2" to Delivery.OFF)), s.current().feedback.overrides)
        s.resetOverrides(1)
        assertTrue(s.current().feedback.overrides.isEmpty())
        s.updatePresets { listOf(com.barodrive.core.model.FeedbackPreset(id = "my-1")) }
        s.selectPreset("my-1")
        assertEquals("my-1", s.current().lastPresetId)
        s.selectPreset("none")
        assertNull(s.current().lastPresetId)
    }

    @Test
    fun setActiveProfileCreatesBlankAndRecordsArePerProfile() = runTest {
        val s = InMemoryBaroStore(clock = clock)
        s.add(SessionRecord(id = "a", mode = 1))
        s.records.test {
            assertEquals(1, awaitItem().size)
            val p2 = s.setActiveProfile("p2")
            assertEquals("운전자 2", p2.name)
            assertEquals(0, awaitItem().size)
            s.setActiveProfile("p1")
            assertEquals(1, awaitItem().size)
        }
    }

    @Test
    fun replaceNormalizesAndRejectsOtherVersions() = runTest {
        val s = InMemoryBaroStore(clock = clock)
        val next = s.replace(AppState(activeProfileId = "p3", profiles = mapOf("p1" to Profile())))
        assertEquals(setOf("p1", "p3"), next.profiles.keys)
        assertEquals("운전자 3", s.current().name)
        val failed = runCatching { s.replace(AppState(version = 2)) }
        assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
        assertEquals("운전자 1", s.reset().activeProfile.name)
    }
}
