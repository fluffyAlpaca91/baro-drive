package com.barodrive.app.data.store

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.barodrive.app.data.repo.setCoach
import com.barodrive.app.data.repo.setHonorific
import com.barodrive.app.data.repo.updateProgress
import com.barodrive.app.data.repo.updateShareQueue
import com.barodrive.app.testing.FakeSessionRecordDao
import com.barodrive.app.testing.InMemoryDataStore
import com.barodrive.core.model.AppState
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Coach
import com.barodrive.core.model.FirstMetrics
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.StageState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** t1a_store.test.js를 기기 저장 구현으로: 같은 DataStore·표로 다시 열기 = 새로고침. */
class DeviceBaroStoreTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:12:00Z"), ZoneId.of("Asia/Seoul"))
    private val key = stringPreferencesKey("baro.app.v1")

    /** 같은 저장 공간을 쓰는 저장소를 새로 연다(새로고침). */
    private class Disk(initial: Preferences = emptyPreferences()) {
        val prefs = InMemoryDataStore(initial)
        val dao = FakeSessionRecordDao()
    }

    private fun open(disk: Disk) = DeviceBaroStore(disk.prefs, disk.dao, clock)

    private fun rec(id: String?, mode: Int) = SessionRecord(id = id, mode = mode, stage = if (mode <= 4) mode else null, noInMode = 1, durationSec = 600.0, arrived = true)

    private suspend fun savedJson(disk: Disk) = disk.prefs.data.first()[key]

    @Test
    fun defaultsAndReadingDoesNotWrite() = runTest {
        val disk = Disk()
        val s = open(disk)
        val st = s.state.first()
        assertEquals(1, st.version)
        assertEquals("p1", st.activeProfileId)
        assertNull(st.ui.lastScreen)
        val p = s.current()
        assertEquals("운전자 1", p.name)
        assertEquals("2026-11-10T09:12:00+09:00", p.createdAt)
        assertEquals(StageState(), p.progress.stages[2])
        assertEquals(listOf(1, 2, 3, 4), p.progress.stages.keys.toList())
        assertNull("읽기만 해서는 저장하지 않는다", savedJson(disk))
    }

    @Test
    fun updatePersistsAndReopenStartsFromSavedState() = runTest {
        val disk = Disk()
        val s = open(disk)
        s.setCoach(Coach.MOM)
        s.setHonorific(Honorific(type = HonorificType.DAUGHTER))
        s.updateProgress { it.copy(currentStage = 2) }
        val saved = Json.parseToJsonElement(savedJson(disk) ?: "").jsonObject
        val p1 = saved.getValue("profiles").jsonObject.getValue("p1").jsonObject
        assertEquals("mom", p1.getValue("coach").jsonPrimitive.content)
        assertEquals("daughter", p1.getValue("honorific").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue("기록은 JSON에 넣지 않는다(표에 있다)", p1.getValue("sessions").jsonArray.isEmpty())

        val again = open(disk)
        assertEquals(Coach.MOM, again.current().coach)
        assertEquals(2, again.current().progress.currentStage)
    }

    @Test
    fun resetRemovesStateAndRecords() = runTest {
        val disk = Disk()
        val s = open(disk)
        s.setCoach(Coach.SIB)
        s.add(rec("a", 1))
        s.reset()
        assertNull(savedJson(disk))
        assertTrue(disk.dao.rows.isEmpty())
        assertEquals(Coach.DAD, s.current().coach)
        assertEquals(Coach.DAD, open(disk).current().coach)
    }

    @Test
    fun recordsCapAtThirtyKeepProgressAndShareQueue() = runTest {
        val disk = Disk()
        val s = open(disk)
        s.update {
            it.copy(
                progress = it.progress.copy(currentStage = 2, stages = it.progress.stages + (1 to StageState(2, "2026-11-01T10:00:00+09:00"))),
                g1Baseline = 1.1, firstMetrics = FirstMetrics(c2Avg = 38.0, stopFailRate = 0.375, g1 = 1.1),
            )
        }
        s.updateShareQueue { listOf("r-0", "r-5") }
        for (i in 0 until 31) s.add(rec("r-$i", if (i % 2 == 1) 2 else 1))
        val list = s.list()
        assertEquals(30, list.size)
        assertEquals("r-30", list.first().id)
        assertEquals("r-1", list.last().id)
        assertNull("가장 오래된 것을 지움", s.get("r-0"))
        val p = s.current()
        assertEquals(2, p.progress.currentStage)
        assertEquals(2, p.progress.stages[1]?.qualified)
        assertEquals(1.1, p.g1Baseline)
        assertEquals(listOf("r-5"), p.shareQueue)
        assertEquals(15, s.list(mode = 2).size)
        assertEquals(30, disk.dao.rows.size)

        assertTrue(s.update("r-5") { it.copy(share = ShareState()) })
        assertFalse(s.update("없음") { it })
        assertTrue(s.remove("r-5"))
        assertFalse(s.remove("r-5"))
        assertEquals(emptyList<String>(), s.current().shareQueue)
        assertEquals(29, disk.dao.rows.size)
        val again = open(disk)
        assertEquals(29, again.list().size)
        assertEquals("r-30", again.list().first().id)
        assertEquals(emptyList<String>(), again.current().shareQueue)
    }

    @Test
    fun addSuffixesDuplicatesAndStampsMissingIds() = runTest {
        val s = open(Disk())
        assertEquals("sess-20261110-0912", s.add(rec("sess-20261110-0912", 2)).id)
        assertEquals("sess-20261110-0912-2", s.add(rec("sess-20261110-0912", 2)).id)
        assertEquals("sess-20261110-0912-3", s.add(rec("sess-20261110-0912", 2)).id)
        val d = s.add(SessionRecord(mode = 5))
        assertTrue(d.id.orEmpty().matches(Regex("^sess-\\d{8}-\\d{4}(-\\d+)?$")))
    }

    @Test
    fun profilesHaveTheirOwnRecordsAcrossReopen() = runTest {
        val disk = Disk()
        val s = open(disk)
        s.update { it.copy(coach = Coach.MOM, onboarding = it.onboarding.copy(done = true)) }
        s.add(rec("p1-a", 1))
        val p2 = s.setActiveProfile("p2")
        assertEquals("운전자 2", p2.name)
        assertEquals(Coach.DAD, s.current().coach)
        assertTrue(s.list().isEmpty())
        s.add(rec("p2-a", 2))
        s.setActiveProfile("p1")
        assertEquals(Coach.MOM, s.current().coach)
        assertEquals(listOf("p1-a"), s.list().map { it.id })

        val again = open(disk)
        val st = again.state.first()
        assertEquals("p1", st.activeProfileId)
        assertEquals(setOf("p1", "p2"), st.profiles.keys)
        assertEquals(listOf("p2-a"), st.profiles["p2"]?.sessions?.map { it.id })
        assertTrue(runCatching { again.setActiveProfile("") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun brokenOrOtherVersionStartsFreshAndOldJsonGetsFilledAndMoved() = runTest {
        assertEquals("운전자 1", open(Disk(mutablePreferencesOf(key to "{깨진 JSON"))).current().name)
        assertEquals(1, open(Disk(mutablePreferencesOf(key to """{"version":2,"profiles":{}}"""))).state.first().version)

        val old = """{"version":1,"activeProfileId":"p1","profiles":{"p1":{"id":"p1","name":"지은","coach":"sib",
            |"progress":{"currentStage":3},"sessions":[{"id":"x","mode":3,"stage":3,"simSec":600,"arrived":true}]}}}""".trimMargin()
        val disk = Disk(mutablePreferencesOf(key to old))
        val s = open(disk)
        val p = s.current()
        assertEquals("지은", p.name)
        assertEquals(Coach.SIB, p.coach)
        assertEquals(3, p.progress.currentStage)
        assertEquals("빠진 키는 기본값", 1, p.progress.unlockedStage)
        assertEquals(StageState(), p.progress.stages[4])
        assertEquals(listOf("x"), p.sessions.map { it.id })
        assertEquals("JSON 안 기록은 표로 옮김", listOf("x"), disk.dao.rows.map { it.recordId })
        assertEquals(listOf("x"), open(disk).list().map { it.id })
    }

    @Test
    fun replaceCopiesStateWithRecordsAndRejectsOtherVersions() = runTest {
        val disk = Disk()
        val s = open(disk)
        val p1 = Profile.blank("p1", "운전자 1").let { it.copy(progress = it.progress.copy(currentStage = 4), sessions = listOf(rec("b", 4), rec("a", 3))) }
        s.replace(AppState(profiles = mapOf("p1" to p1, "p2" to Profile.blank("p2"))))
        assertEquals(4, s.current().progress.currentStage)
        assertEquals(listOf("b", "a"), s.list().map { it.id })
        assertEquals(listOf("b", "a"), open(disk).list().map { it.id })
        assertTrue(runCatching { s.replace(AppState(version = 2)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun updateStateRewritesRecordsOfChangedProfiles() = runTest {
        val disk = Disk()
        val s = open(disk)
        s.add(rec("a", 1))
        s.updateState { st -> st.copy(profiles = st.profiles + ("p1" to Profile.blank("p1", "운전자 1"))) } // ST '처음부터'
        assertTrue(s.list().isEmpty())
        assertTrue(disk.dao.rows.isEmpty())
        val counted = Json.parseToJsonElement(savedJson(disk) ?: "").jsonObject.getValue("version").jsonPrimitive.int
        assertEquals(1, counted)
    }

    @Test
    fun diskFailureKeepsTheInMemoryState() = runTest {
        val disk = Disk()
        val s = open(disk)
        disk.dao.failNext = true
        val saved = s.add(rec("a", 1))
        assertEquals("a", saved.id)
        assertEquals(listOf("a"), s.list().map { it.id })
        assertTrue(disk.dao.rows.isEmpty())
    }

    @Test
    fun recordJsonRoundTripsThroughTheTable() = runTest {
        val disk = Disk()
        val s = open(disk)
        val r = rec(null, 6).copy(kind = com.barodrive.core.model.RecordKind.VIRTUAL_PARKING, noInMode = null, durationSec = 42.5)
        val saved = s.add(r)
        val row = disk.dao.rows.single()
        assertEquals("virtualParking", row.kind)
        assertEquals(6, row.mode)
        assertEquals(42.5, row.simSec, 0.0)
        assertEquals(saved, BaroJson.decodeFromString(SessionRecord.serializer(), row.json))
        assertEquals(saved, open(disk).get(saved.id ?: ""))
    }
}
