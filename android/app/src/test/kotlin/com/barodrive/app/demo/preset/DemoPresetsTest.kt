package com.barodrive.app.demo.preset

import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.RankOptions
import com.barodrive.core.engine.api.RenderOptions
import com.barodrive.core.engine.api.ResolveOptions
import com.barodrive.core.model.AppState
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.DeliveryReason
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.FirstMetrics
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ReviewTip
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.ShareStatus
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.WeatherType
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 데모 상태 11개 (프로토타입 tests/unit/t10_presets.test.js, 설계 8.3) — 모양(설계 8.1·store 기본값) · 늘 같은 결과 · 상태마다 홈/리뷰가 읽는 값.
 * 회차 기록은 요약만 있는 기록이 review.compute → applyProgress를 거쳐 실제 회차와 같은 진행·통과·도움 줄여 가기를 만든다.
 */
class DemoPresetsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 11)
    private val todayStart = today.atTime(10, 0).atZone(zone).toInstant()

    private fun build(id: String, day: LocalDate = today): AppState? = DemoPresets.build(id, day, zone)
    private fun prof(id: String, day: LocalDate = today): Profile = requireNotNull(build(id, day)).profiles.getValue("p1")
    private fun stageK(p: Profile) = (1..4).map { n -> p.progress.stages.getValue(n).let { "${it.qualified}${if (it.passedAt != null) "P" else ""}" } }
    private fun byMode(p: Profile, m: Int) = p.sessions.filter { it.mode == m && !it.isVirtualParking }
    private fun time(r: SessionRecord) = OffsetDateTime.parse(requireNotNull(r.startedAt)).toInstant()

    @Test
    fun elevenStatesInDesignOrderWithNamesDescriptionsAndPanelValues() {
        val list = DemoPresets.list
        assertEquals(IDS, list.map { it.id })
        assertEquals(
            listOf(
                "처음 실행", "1단계 1회차 마침", "1단계 통과 직전", "2단계 3회차", "4단계 통과 직전", "졸업 후",
                "자율 기록 3회", "운전면허 연습 2/4", "운전면허 유효기간 지남", "확인할 것 있음", "코스 없는 동네",
            ),
            list.map { it.title },
        )
        for (d in list) {
            assertTrue(d.id, Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(d.id))
            assertTrue(d.id, d.desc.isNotEmpty())
            assertEquals("p1", d.panel.profileId)
        }
        assertFalse(DemoPresets.info("needs-check")!!.panel.network)
        assertEquals("moved", DemoPresets.info("moved-town")!!.panel.town)
        assertTrue(DemoPresets.info("stage2-session3")!!.panel.network)
    }

    @Test
    fun shapeMatchesStoreDefaultsAndRecordsAreSummariesThatReplaceReads() = runTest {
        for (id in IDS) {
            val st = build(id)
            if (id == "first-run") {
                assertNull(st)
                continue
            }
            requireNotNull(st)
            assertEquals(1, st.version)
            assertEquals("p1", st.activeProfileId)
            assertEquals(listOf("p1"), st.profiles.keys.toList())
            val p = st.profiles.getValue("p1")
            assertTrue(id, p.onboarding.done)
            assertTrue(id, p.consent.record)
            assertTrue(id, p.sessions.size in 1..30)
            assertEquals("$id JSON 그대로", st, BaroJson.decodeFromString(AppState.serializer(), BaroJson.encodeToString(AppState.serializer(), st)))
            val ids = p.sessions.map { it.id }
            assertEquals("$id 회차 id 겹침 없음", ids.size, ids.toSet().size)
            val times = p.sessions.map(::time)
            assertEquals("$id 최신이 앞", times.sortedDescending(), times)
            assertTrue("$id 모두 오늘 전", times.all { it < todayStart })
            for (r in p.sessions) {
                assertTrue(r.id, Regex("^sess-\\d{8}-\\d{4}$").matches(requireNotNull(r.id)))
                if (r.isVirtualParking) continue
                assertTrue("$id ${r.id} applyProgress를 거침", r.progress?.applied == true)
                assertTrue("이 회차에 쓴 전달 방식", r.items.size >= 20)
            }
            val store = InMemoryBaroStore()
            store.replace(st)
            assertEquals("$id replace", p.sessions.size, store.current().sessions.size)
            assertEquals(p.progress, store.current().progress)
        }
    }

    @Test
    fun sameTodaySameResultAndOnlyDatesFollowANewToday() {
        for (id in IDS) assertEquals(id, build(id), build(id))
        val later = LocalDate.of(2027, 5, 3)
        val a = prof("stage2-session3")
        val b = prof("stage2-session3", later)
        assertEquals(stageK(a), stageK(b))
        assertTrue(b.sessions.all { time(it) < later.atTime(9, 0).atZone(zone).toInstant() })
        assertNotEquals(a.sessions[0].startedAt, b.sessions[0].startedAt)
    }

    @Test
    fun stage1SessionOneAndAlmostPassed() {
        val one = prof("stage1-session1")
        assertEquals(1, one.progress.currentStage)
        assertEquals(listOf("0", "0", "0", "0"), stageK(one))
        assertEquals(0.75, requireNotNull(one.sessions[0].achievement), 1e-9)
        assertFalse(one.sessions[0].qualified)
        assertEquals(1, one.sessionCount[1])
        assertEquals("K4 기준 = 설계 8.1 예", FirstMetrics(c2Avg = 38.0, g1 = 1.1, stopTotal = 8, stopFail = 3, stopFailRate = 0.375), one.firstMetrics)
        val two = prof("stage1-almost")
        assertEquals(listOf("1", "0", "0", "0"), stageK(two))
        assertEquals(1, two.sessions[0].progress?.qualifiedAfter)
        assertEquals("예시 내 코스(결정 T1c-Q1)", listOf("c1-C", "c2-A"), two.myCourses.mapNotNull { it.courseRef }.sorted())
        // 다음 회차가 통과 회차면 1단계 통과(설계 8.3 '능숙으로 한 번 더 하면 통과')
        val rec0 = SessionRecord(
            mode = 1, startedAt = "2026-11-11T10:00:00+09:00",
            tasks = listOf(TaskResult("T-START", success = 5, total = 5), TaskResult("T-STOP", success = 6, total = 6), TaskResult("T-SIGNAL1", success = 4, total = 4)),
        )
        val rec = rec0.withReview(BaroEngine.review.compute(rec0, two))
        val res = BaroEngine.review.applyProgress(two, rec)
        assertTrue(res.result.passed)
        assertEquals(2, res.profile.progress.currentStage)
    }

    @Test
    fun stage2ThirdSessionSkipsC1AndHasB1FadedToReport() {
        val p = prof("stage2-session3")
        assertEquals(2, p.progress.currentStage)
        assertEquals(listOf("2P", "1", "0", "0"), stageK(p))
        assertEquals(3, p.sessionCount[1])
        assertEquals(2, p.sessionCount[2])
        assertEquals(FadeState(streak = 2, faded = true), p.fading[2]?.get("B1"))
        assertEquals("내려간 것은 B1만", listOf("B1"), p.fading[2].orEmpty().filter { it.value.faded }.keys.toList())
        val items = BaroEngine.resolveItems(2, p, ResolveOptions(noInMode = 3))
        assertEquals(Delivery.OFF to DeliveryReason.FIRST2, items.getValue("C1").let { it.value to it.reason })
        assertEquals(Delivery.AFTER to DeliveryReason.FADING, items.getValue("B1").let { it.value to it.reason })
        assertEquals(Delivery.NOW, items.getValue("C2").value)
        assertEquals(3, BaroEngine.buildConfig(2, p, PrepSession(mode = 2, courseId = "c2-B")).sessionNoInMode)
        assertEquals("출발은 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.", p.sessions[0].fadedNote)
        val todays = BaroEngine.review.todayTasks(p, 2)
        assertEquals("오늘 과제 1번 = 지난 회차 다음 과제", p.sessions[0].nextTask?.id, todays[0].id)
    }

    @Test
    fun stage4AlmostAndGraduatedWithAutonomousParkingHillAndWeatherRuns() {
        val a = prof("stage4-almost")
        assertEquals(4, a.progress.currentStage)
        assertEquals(listOf("2P", "2P", "2P", "1"), stageK(a))
        assertNull(a.progress.graduatedAt)
        val g = prof("graduated")
        assertEquals(listOf("2P", "2P", "2P", "2P"), stageK(g))
        assertNotNull("졸업", g.progress.graduatedAt)
        assertEquals("합계 12회", 12, (1..4).sumOf { g.sessionCount[it] ?: 0 })
        assertEquals(2, g.sessionCount[5])
        assertEquals(listOf(86, 82), byMode(g, 5).map { it.score?.total })
        assertEquals("자율 회차 급제동은 확인된 상태", F2Choice.AVOID, g.sessions[0].f2[0].choice)
        val pk = requireNotNull(byMode(g, 6)[0].parking)
        assertEquals(8, pk.total)
        assertEquals(pk.total, pk.reached)
        assertTrue(pk.done)
        assertEquals(1, pk.pressed)
        assertEquals(EventResult.FAIL, pk.handbrake?.result)
        assertEquals("가상 주차는 회차 수를 늘리지 않음", 1, g.sessionCount[6])
        assertEquals(2, g.sessions.count { it.isVirtualParking })
        assertEquals(mapOf("parallel-R" to true, "back-R" to true), g.parking.done)
        val hl = byMode(g, 7)[0]
        assertEquals(listOf("T-HILLUP", "T-HILLDOWN", "T-HILLPARK"), hl.tasks.map { it.id })
        val hill = requireNotNull(hl.hill)
        assertEquals(listOf(2, 1, 4), listOf(hill.up.attempts, hill.up.rollbacks, hill.down.presses))
        assertEquals(HillSlope.DOWN, hill.park?.situation)
        // 날씨 회차(RV-3 '빗길 2회 · 눈길 1회') — 팁 줄은 j3.review 문장
        val wx = g.sessions.filter { it.mode <= 4 && it.weather?.type != WeatherType.CLEAR }
        assertEquals(listOf(WeatherType.RAIN, WeatherType.RAIN, WeatherType.SNOW), wx.mapNotNull { it.weather?.type }.sorted())
        for (r in wx) {
            val type = requireNotNull(r.weather).type
            val key = "j3.review." + if (type == WeatherType.RAIN) "rain" else "snow"
            val text = BaroEngine.lines.render(key, g.coach, emptyMap(), g.honorific, RenderOptions(index = 0, peek = true))
            assertEquals(listOf(ReviewTip(text = text, icon = if (type == WeatherType.RAIN) "i-rain" else "i-snow")), r.tips)
        }
    }

    @Test
    fun autonomousThreeRunsScoresAndMyPresets() {
        val p = prof("autonomous-3")
        assertEquals(2, p.progress.currentStage)
        assertEquals(listOf(86, 82, 78), byMode(p, 5).map { it.score?.total })
        assertEquals(listOf("출퇴근용", "비 오는 날", "주말 장보기"), p.myPresets.map { it.name })
        assertEquals(listOf(true, true, false), p.myPresets.map { it.example })
        assertEquals("my-weekend", p.lastPresetId)
        val latest = byMode(p, 5)[0]
        assertEquals(
            "목업 s07 86점 예",
            listOf("C2.late" to 3, "C2.almost" to 2, "C4" to 2, "BF" to 3, "G1" to 4),
            requireNotNull(latest.score).deductions.map { it.id to it.points },
        )
        assertEquals(82, BaroEngine.score.previous(p.sessions, latest.id)?.total)
        val rainy = byMode(p, 5)[1]
        assertEquals("ex-rain", rainy.presetId)
        assertEquals(WeatherType.RAIN, rainy.weather?.type)
        for (pr in p.myPresets) for ((id, v) in pr.items) assertTrue("${pr.name} $id", v in BaroEngine.allowed(id))
    }

    @Test
    fun licenseTwoOfFourAndExpiredCountBackFromToday() {
        val p = prof("license-2of4")
        assertEquals("2026-04-01", p.license.issueDate)
        assertEquals(listOf("A", "B"), p.license.coursesDone)
        assertEquals(2, p.sessionCount[8])
        val st = BaroEngine.license.status(p.license.issueDate, today)
        assertEquals("D-140" to false, st.dday to st.expired)
        assertEquals("4개 코스 중 2개", BaroEngine.license.progress(p.license.coursesDone).text)
        val recs = byMode(p, 8)
        assertEquals(listOf("B", "A"), recs.map { it.license?.letter })
        assertEquals(listOf(listOf("A", "B"), listOf("A")), recs.map { it.license?.coursesDoneAfter })
        assertTrue(recs.all { it.license?.complete == true && it.tasks.size == 4 })
        val later = LocalDate.of(2027, 3, 30)
        assertEquals("오늘이 바뀌어도 같은 D-day", "D-140", BaroEngine.license.status(prof("license-2of4", later).license.issueDate, later).dday)
        val x = prof("license-expired")
        assertEquals("오늘 기준 1년 1개월 전", "2025-10-11", x.license.issueDate)
        assertTrue(BaroEngine.license.status(x.license.issueDate, today).expired)
    }

    @Test
    fun needsCheckHasOneUncheckedHardBrakeAndOneQueuedSummaryAndMovedTownHasNoTeamCourses() {
        val p = prof("needs-check")
        assertEquals(2, p.progress.currentStage)
        val last = p.sessions[0]
        assertEquals(2, last.mode)
        assertEquals(1, last.f2.size)
        assertNull(last.f2[0].choice)
        assertEquals(listOf(last.id), p.shareQueue)
        assertEquals(ShareState(status = ShareStatus.QUEUED, to = "아버지"), last.share)
        assertTrue(p.consent.family)
        assertEquals(1, p.sessions.sumOf { r -> r.f2.count { it.choice == null } })
        val m = prof("moved-town")
        assertEquals(2, m.progress.currentStage)
        assertEquals(listOf("0", "1", "0", "0"), stageK(m))
        val r = BaroEngine.courses.rank(2, RankOptions(town = "moved", myCourses = m.myCourses))
        assertEquals("이사 간 동네에는 팀 코스 없음", 0, r.list.count { !it.mine })
    }

    private companion object {
        val IDS = listOf(
            "first-run", "stage1-session1", "stage1-almost", "stage2-session3", "stage4-almost", "graduated",
            "autonomous-3", "license-2of4", "license-expired", "needs-check", "moved-town",
        )
    }
}
