package com.barodrive.core.engine.api

import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Coach
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.LicenseState
import com.barodrive.core.model.LineRef
import com.barodrive.core.model.Mode
import com.barodrive.core.model.ParkingSetup
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.PrepWeather
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ScoreResult
import com.barodrive.core.model.SeatState
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.Severe
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WeatherType
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaroEngineApiTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "픽스처가 없어요: $name" }.use { it.readBytes().decodeToString() }

    private val record = SessionRecord(mode = 2, stage = 2, noInMode = 1, courseId = "c2-B")
    private val profile = Profile()

    // ================================================================ 실제로 옮긴 함수

    @Test
    fun buildConfigMatchesPrototypeBuildConfig() {
        // 프로토타입: buildConfig(2, 이 프로필, { courseId: 'c2-B', weather: { snow: true, severe: 'fog' }, preEvents: [L1] }, { caps: { passenger: false } })
        val p = BaroJson.decodeFromString(Profile.serializer(), fixture("proto-profile-s2b.json"))
        val expected = BaroJson.decodeFromString(SessionConfig.serializer(), fixture("proto-config-s2b.json"))
        val prep = PrepSession(
            mode = 2, courseId = "c2-B", weather = PrepWeather(snow = true, severe = Severe.FOG),
            preEvents = listOf(JudgeEvent(item = "L1", data = EventData(spoken = true, delivery = Delivery.NOW))),
        )

        val cfg = BaroEngine.buildConfig(2, p, prep, SignalCaps(passenger = false))

        assertEquals(expected, cfg)
        assertEquals("baro:p1:m2:n2:c2-B", cfg.seed)
        assertEquals(2, cfg.sessionNoInMode)
        assertEquals(1, cfg.history.recentSessionsInMode.size)
        assertEquals(0.94, cfg.history.baselineG1)
    }

    @Test
    fun buildConfigWithoutProfileOrPrepUsesDefaults() {
        val cfg = BaroEngine.buildConfig(1, null)

        assertEquals("baro:p:m1:n1:", cfg.seed)
        assertEquals(1, cfg.stage)
        assertEquals(1, cfg.sessionNoInMode)
        assertEquals(Coach.DAD, cfg.coach)
        assertNull(cfg.profileId)
        assertNull(cfg.dest)
        assertEquals(Gear.P, cfg.startGear)
        assertEquals("모드 밖은 1~8로 자른다", 8, BaroEngine.buildConfig(12, null).mode)
    }

    @Test
    fun buildConfigTakesPlaceNameAsDestinationAndCarriesPrep() {
        val p = Profile(id = "p2", sessionCount = Mode.numbers.associateWith { if (it == 6) 4 else 0 })
        val prep = PrepSession(mode = 6, courseId = "c6-A", placeId = "gym", parking = ParkingSetup(ParkingType.BACK, TurnDir.LEFT), startedAt = "2026-11-11T10:00:00+09:00")

        val cfg = BaroEngine.buildConfig(6, p, prep)

        assertEquals("시민체육관", cfg.dest)
        assertEquals(5, cfg.sessionNoInMode)
        assertNull(cfg.stage)
        assertEquals("baro:p2:m6:n5:c6-A", cfg.seed)
        assertEquals("back-L", cfg.parking?.key)
        assertEquals("2026-11-11T10:00:00+09:00", cfg.startedAt)
    }

    @Test
    fun thresholdsFollowWeather() {
        val clear = SessionWeather()
        val rain = SessionWeather(type = WeatherType.RAIN)
        val severe = SessionWeather(severe = Severe.ICE)

        assertEquals(11.0, BaroEngine.threshold("B1.gain", clear))
        assertEquals(8.0, BaroEngine.threshold("B1.gain", rain))
        assertEquals(8.0, BaroEngine.threshold("B1.gain", severe))
        assertEquals(7.5, BaroEngine.threshold("F1.drop", clear))
        assertEquals(6.0, BaroEngine.threshold("F1.drop", SessionWeather(type = WeatherType.SNOW)))
        assertEquals(0.5, BaroEngine.threshold("E.factor", SessionWeather(type = WeatherType.RAIN, severe = Severe.FOG)))
        assertEquals(40, BaroEngine.effLimit(50.0, rain))
        assertEquals(24, BaroEngine.effLimit(30.0, rain))
        assertEquals(25, BaroEngine.effLimit(50.0, severe))
        assertEquals(15, BaroEngine.effLimit(30.0, severe))
        assertEquals(24.0, BaroEngine.threshold("E2.limit", rain))
        assertEquals(40.0, BaroEngine.threshold("E.limit", rain, limit = 50.0))
        assertEquals(110.0, BaroEngine.threshold("C2.ok", ic = true))
        assertEquals(40.0, BaroEngine.threshold("C2.ok"))
        assertEquals(20.0, BaroEngine.threshold("gap"))
        assertNull(BaroEngine.threshold("no.such.key"))
        assertNull(BaroEngine.effLimit(null, clear))
    }

    @Test
    fun honorificAndSpeakDurationFollowTheRules() {
        assertEquals("", BaroEngine.honorificText(Honorific(HonorificType.DAUGHTER), Coach.SIB))
        assertEquals("우리 딸", BaroEngine.honorificText(Honorific(HonorificType.DAUGHTER), Coach.MOM))
        assertEquals("우리 아들", BaroEngine.honorificText(Honorific(HonorificType.SON), Coach.DAD))
        assertEquals("지은아지은아지은", BaroEngine.honorificText(Honorific(HonorificType.CUSTOM, "  지은아지은아지은아 "), Coach.MOM))
        assertEquals("", BaroEngine.honorificText(null, Coach.MOM))
        assertEquals(0.85, BaroEngine.speakDur("가나다"), 1e-9)
    }

    @Test
    fun exampleMyCoursesAreTheOwnCourses() {
        val list = BaroEngine.exampleMyCourses()

        assertEquals(listOf("mc-c1-C", "mc-c2-A"), list.map { it.id })
        assertTrue(list.all { it.example && it.source == CourseSource.SAVED && it.courseRef == it.id.removePrefix("mc-") })
    }

    @Test
    fun validIssueDateRejectsFutureAndGarbage() {
        val today = LocalDate.of(2026, 11, 11)

        assertTrue(BaroEngine.license.validIssueDate("2026-04-01", today))
        assertTrue(BaroEngine.license.validIssueDate("2026-11-11", today))
        assertFalse(BaroEngine.license.validIssueDate("2026-11-12", today))
        assertFalse(BaroEngine.license.validIssueDate("어제", today))
        assertFalse(BaroEngine.license.validIssueDate(null, today))
    }

    @Test
    fun scoreLineAndLostTextFollowTotals() {
        assertEquals("rv.score.hi", BaroEngine.score.line(ScoreResult(total = 90)).key)
        assertEquals("rv.score.mid", BaroEngine.score.line(ScoreResult(total = 86)).key)
        assertEquals("rv.score.lo", BaroEngine.score.line(ScoreResult(total = 69), dest = "마트").key)
        assertEquals("14점 깎였어요", BaroEngine.score.lostText(ScoreResult(total = 86, lost = 14)))
        assertEquals("한 점도 안 깎였어요", BaroEngine.score.lostText(ScoreResult()))
    }

    // ================================================================ STUB도 모양이 맞는 값을 돌려준다

    @Test
    fun sessionStubRunsAndFinishes() {
        val session = BaroEngine.createSession(BaroEngine.buildConfig(1, null))

        val out = session.step(VehicleSnapshot(t = 0.0, gear = Gear.D, speed = 0.0))
        val rec = session.finish(arrived = false)

        assertNotNull(out)
        assertEquals(1, rec.mode)
    }

    @Test
    fun itemFunctionsReturnWithoutThrowing() {
        // STUB이든 옮긴 뒤든 참인 것만 본다(엔진 작업자가 이 테스트를 고치지 않아도 되게).
        assertNotNull(BaroEngine.itemIds())
        BaroEngine.itemDef("C2")
        BaroEngine.defaultFor("C2", 2)
        assertNull("모르는 항목", BaroEngine.defaultFor("ZZ", 2))
        assertNotNull(BaroEngine.allowed("C2"))
        assertTrue("고정 항목은 고를 값이 없음", BaroEngine.allowed("E1").isEmpty())
        BaroEngine.isFixed("E1")
        assertNotNull(BaroEngine.resolveItems(2, profile))
        assertNotNull(BaroEngine.itemsOffByCaps(SignalCaps(speed = false)))
        assertEquals(listOf("ex-commute", "ex-rain"), BaroEngine.examplePresets().map { it.id })
        assertNotNull(BaroEngine.modeTasks(1))
        assertTrue("자율 모드는 과제 없음", BaroEngine.modeTasks(5).isEmpty())
        BaroEngine.taskDef("T-START")
        BaroEngine.feedback.resolve(2, "C1", FeedbackContext(sessionNoInMode = 3))
        assertNull("모르는 항목", BaroEngine.feedback.resolve(2, "ZZ"))
    }

    @Test
    fun linesAndCourseStubsReturnUsableValues() {
        assertTrue(BaroEngine.lines.render("c2.ok", Coach.MOM, mapOf("N" to 50), Honorific(HonorificType.DAUGHTER)).isNotEmpty())
        assertTrue(BaroEngine.lines.render(LineRef("p.course", JsonObject(mapOf("X" to JsonPrimitive("B"))))).isNotEmpty())
        BaroEngine.lines.resetRotation()

        val stage2 = BaroEngine.courses.rank(2)
        assertEquals(listOf("B", "C"), stage2.list.map { it.letter })
        assertEquals("c2-B", stage2.selectedId)
        assertTrue(BaroEngine.courses.rank(5).autonomous)
        val license = BaroEngine.courses.rank(8, RankOptions(coursesDone = listOf("A", "B")))
        assertEquals(listOf(true, true, false, false), license.list.map { it.ran })
        assertTrue(BaroEngine.courses.rank(2, RankOptions(town = "moved")).empty)
    }

    @Test
    fun reviewScoreFadingStubsReturnValidValues() {
        assertNotNull(BaroEngine.review.compute(record, profile))
        assertNotNull(BaroEngine.review.summarize(record, null))
        val applied = BaroEngine.review.applyProgress(profile, record, now = "2026-11-11T10:00:00+09:00")
        assertEquals(true, applied.record.progress?.applied)
        assertEquals(2, applied.result.mode)
        assertNotNull(BaroEngine.review.todayTasks(profile, 2))
        assertNotNull(BaroEngine.review.countTasks(record, 2))
        assertNotNull(BaroEngine.review.itemStats(record))
        assertNotNull(BaroEngine.review.k4(record.metrics, BaroEngine.buildConfig(2, null).history))
        BaroEngine.review.nextTask(record, emptyList(), 2)

        val score = BaroEngine.score.compute(record, profile)
        assertEquals(100, score.total)
        assertNotNull(BaroEngine.score.compute(record, BaroEngine.buildConfig(5, null)))
        assertNotNull(BaroEngine.score.fromCounts(ScoreCounts(late = 1, g1Points = 6)))
        assertTrue(BaroEngine.score.g1Points(1.2, 0.8) in 0..10)
        assertNotNull(BaroEngine.score.table(score))
        assertNotNull(BaroEngine.score.footnotes(score))
        BaroEngine.score.excludedText(score)
        BaroEngine.score.previous(listOf(record), null)

        assertEquals(listOf("B1", "C2", "C4", "C5", "F1"), BaroEngine.fading.items)
        assertEquals(2, BaroEngine.fading.update(profile, record).mode)
        assertNotNull(BaroEngine.fading.next(emptyMap(), emptyMap(), 2, emptyMap()))
        assertNotNull(BaroEngine.fading.stats(record))
        BaroEngine.fading.eligible("B1", 2, emptyMap())
        assertFalse("고정 항목은 내려가지 않음", BaroEngine.fading.eligible("E1", 2, emptyMap()))
        BaroEngine.fading.note(listOf("B1"))
    }

    @Test
    fun licenseStubsReturnValidValues() {
        val today = LocalDate.of(2026, 11, 11)

        assertEquals(LicenseState.NONE, BaroEngine.license.status(null, today).state)
        assertEquals(LicenseState.OK, BaroEngine.license.status("2026-04-01", today).state)
        val check = BaroEngine.license.startCheck(LicenseStartInput(passenger = null, checks = LicenseChecks(companion = true, sign = true)))
        assertEquals(SeatState.UNKNOWN, check.seat)
        assertEquals(2, check.checked)
        assertFalse(check.allChecked)
        assertEquals("c2-B", BaroEngine.license.courseResult(record).courseId)
        assertNotNull(BaroEngine.license.progress(listOf("A")))
        assertEquals(listOf("A"), BaroEngine.license.applyCourseDone(profile.copy(license = profile.license.copy(coursesDone = listOf("A"))), record).after)
        assertNotNull(BaroEngine.license.scenes(record))
        assertNotNull(BaroEngine.license.review(record, profile))
        BaroEngine.license.expiry("2026-04-01")
    }
}
