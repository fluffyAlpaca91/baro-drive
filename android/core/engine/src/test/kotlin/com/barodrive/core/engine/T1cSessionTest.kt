package com.barodrive.core.engine

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.RankOptions
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.BrakeEndEvent
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HillResult
import com.barodrive.core.model.HillUpSummary
import com.barodrive.core.model.InputEvent
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.PrepWeather
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ReviewTip
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.StartEvent
import com.barodrive.core.model.TurnApproach
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.TurnPassed
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WeatherType
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프로토타입 t1c_engine.test.js — 회차 기록(6.9·8.1)·코스 추천(6.10.6)·회차를 넘는 값·건너뛰기·시간 틈·ctx. */
class T1cSessionTest {
    private fun stopAt(t0: Double) = ramp(30.0, 0.0, 5.0, t0) + hold(0.0, 2.0, t0 + 5, brake = true)

    @Test
    fun finishBuildsTheRecord() {
        val base = BaroEngine.buildConfig(
            2, Profile(sessionCount = mapOf(2 to 2)),
            PrepSession(mode = 2, courseId = "c2-B", weather = PrepWeather(snow = true),
                preEvents = listOf(JudgeEvent(item = "L1", result = EventResult.INFO, data = EventData(spoken = true)))),
            SignalCaps(trip = false),
        )
        val s = sess(base, listOf(script(listOf(
            Action(1.0) { c, _ -> c.say("t1c.praise", item = "C2", category = Category.PRAISE); c.emit("C2", EventResult.OK, EventData(distance = 55.0)) },
            Action(30.0) { c, _ -> c.say("t1c.fix", item = "C4", category = Category.FIX); c.emit("C4", EventResult.FAIL) },
            sayAction(60.0, "t1c.info", "A1", Category.INFO),
            Action(70.0) { c, _ -> c.emit("F2", EventResult.INFO, EventData(v0 = 28.0, dur = 1.4, maxDrop1s = 14.0)) },
            Action(71.0) { c, _ -> c.noteTip(ReviewTip(text = "눈길 회차예요.")) },
            Action(72.0) { c, _ -> c.noteTip(ReviewTip(text = "엔진브레이크를 써요.")) },
            Action(73.0) { c, _ -> c.noteHill(HillResult(up = HillUpSummary(attempts = 2))) },
            Action(74.0) { c, _ -> c.note("extra", JsonPrimitive(1)) },
            Action(75.0) { c, _ -> c.setWeather(cold = true) },
        ))))
        drive(s, tl(0.0, 120.0) { t, sn -> sn.copy(tripDist = Math.round(t * 8.3).toDouble()) })
        val rec = s.finish(arrived = true)
        assertEquals(2, rec.mode)
        assertEquals(2, rec.stage)
        assertEquals(3, rec.noInMode)
        assertEquals("c2-B", rec.courseId)
        assertTrue(rec.arrived)
        assertEquals(119.9, rec.durationSec, 1e-9)
        assertEquals(119.9, rec.driveSec, 1e-9)
        assertTrue("트립 거리를 못 읽으면 속도를 쌓음 ${rec.distanceM}", rec.distanceM > 900 && rec.distanceM < 1100)
        val w = checkNotNull(rec.weather)
        assertEquals(listOf<Any?>(WeatherType.SNOW, null, true), listOf(w.type, w.severe, w.cold))
        assertTrue(!rec.caps.trip)
        assertEquals(Delivery.OFF, rec.items["C1"])
        assertEquals("F2는 기록에서 빼고 정차 중 판정은 앞에",
            listOf(Triple("L1", EventResult.INFO, true), Triple("C2", EventResult.OK, false), Triple("C4", EventResult.FAIL, false)),
            rec.events.map { Triple(it.item, it.result, it.pre) })
        assertEquals(listOf(F2Check(id = "f2-1", t = 70.0, v0 = 28.0, dur = 1.4, maxDrop1s = 14.0)), rec.f2)
        assertEquals(listOf("눈길 회차예요.", "엔진브레이크를 써요."), rec.tips.map { it.text })
        assertEquals(2, rec.hill?.up?.attempts)
        assertEquals(JsonObject(mapOf("extra" to JsonPrimitive(1))), rec.notes)
        assertEquals(3, rec.utterances.size)
        assertTrue(rec.utterances.all { it.spoken })
        assertEquals("K6: 3 ÷ (119.9초 / 600)", 15.0, rec.metrics.per10min)
        assertEquals(1, rec.metrics.praise)
        assertEquals(1, rec.metrics.correction)
        assertEquals(3, rec.metrics.spoken)
        assertEquals(listOf<Double?>(null, null, null), listOf(rec.metrics.c2Avg, rec.metrics.stopFailRate, rec.metrics.g1))
        assertNull(rec.score)
        assertEquals(ShareState(), rec.share)
        assertEquals("저장할 수 있는 값만", rec, BaroJson.decodeFromString(SessionRecord.serializer(), BaroJson.encodeToString(SessionRecord.serializer(), rec)))
    }

    /** core.js finalize처럼 세션 설정 칸(시작 시각·코스·장소·자율 설정·회차 번호·신호·항목·날씨)이 기록에 그대로 옮겨진다(앱 세션 연결이 읽음). */
    @Test
    fun finishCopiesTheConfigSideFields() {
        val cfg = BaroEngine.buildConfig(
            5, Profile(id = "p7", sessionCount = mapOf(5 to 3)),
            PrepSession(mode = 5, placeId = "mart", presetId = "ex-rain", startedAt = "2026-11-11T09:00:00+09:00", weather = PrepWeather(rain = true)),
            SignalCaps(handbrake = false),
        )
        val s = sess(cfg)
        drive(s, tl(0.0, 1.0))
        val rec = s.finish()
        assertEquals("2026-11-11T09:00:00+09:00", rec.startedAt)
        assertEquals(listOf<Any?>(5, null, 4, null, "mart", "ex-rain"), listOf(rec.mode, rec.stage, rec.noInMode, rec.courseId, rec.placeId, rec.presetId))
        assertEquals(cfg.caps, rec.caps)
        assertEquals(cfg.items, rec.items)
        assertEquals(WeatherType.RAIN, rec.weather?.type)
        assertTrue(!rec.arrived)
    }

    @Test
    fun f2NotesWinOverF2Events() {
        val s = sess(SessionConfig(mode = 1), listOf(script(listOf(
            Action(1.0) { c, _ -> c.noteF2(F2Check(v0 = 30.0, dur = 1.2)) },
            Action(2.0) { c, _ -> c.noteF2(F2Check(v0 = 25.0, dur = 1.5)) },
            Action(3.0) { c, _ -> c.emit("F2", EventResult.INFO, EventData(v0 = 1.0)) },
        ))))
        drive(s, tl(0.0, 4.0))
        val rec = s.finish()
        assertEquals(listOf(Triple("f2-1", 30.0, null), Triple("f2-2", 25.0, null)), rec.f2.map { Triple(it.id, it.v0, it.choice) })
        assertEquals(0, rec.events.size)
    }

    @Test
    fun detectorFinishCanAddJudgmentsAndMetrics() {
        val g1 = DetectorDef(id = "t1c.g1", items = listOf("G1")) { ctx ->
            object : DetectorInstance {
                override fun finish(rec: RecordDraft) {
                    ctx.emit("G1", EventResult.INFO, EventData(value = 0.77))
                    rec.metrics.g1 = 0.77
                }
            }
        }
        val s = sess(SessionConfig(mode = 5), listOf(g1))
        drive(s, tl(0.0, 2.0))
        val rec = s.finish(arrived = true)
        assertEquals(0.77, rec.metrics.g1)
        assertEquals(0.0, rec.metrics.per10min)
        assertEquals(listOf("G1"), rec.events.map { it.item })
        assertNotNull("모드 5는 점수를 붙임", rec.score)
        assertNull("점수는 모드 5만", sess(SessionConfig(mode = 1)).finish().score)
    }

    @Test
    fun finishTwiceGivesTheSameRecordAndLeftoverCandidatesAreBlocked() {
        val s = sess(SessionConfig(mode = 1), listOf(script(listOf(sayAction(1.0, "t1c.info", "A1", Category.INFO), sayAction(2.0, "t1c.info2", "PB1", Category.INFO)))))
        drive(s, tl(0.0, 3.0))
        val a = s.finish(arrived = false)
        assertSame(a, s.finish(arrived = true))
        assertTrue(!a.arrived)
        assertEquals(listOf(Triple("t1c.info", true, null), Triple("t1c.info2", false, BlockReason.GAP)), a.utterances.map { Triple(it.key, it.spoken, it.reason) })
        val o = s.step(snap(t = 5.0, gear = Gear.D, speed = 30.0))
        assertEquals(0 to 0, o.say.size to o.events.size)
        assertTrue(s.finished)
        assertSame(a, s.record)
    }

    @Test
    fun sameConfigAndSnapshotsGiveTheSameOutput() {
        fun run(): String {
            val d = DetectorDef(id = "t1c.rand") { ctx ->
                object : DetectorInstance {
                    override fun step(snap: VehicleSnapshot) {
                        if (abs(snap.t - 5) < 1e-9) ctx.note("r", JsonPrimitive(ctx.rng.next() + ctx.rng.next()))
                        if (abs(snap.t - 2) < 1e-9) ctx.say("t1c.rot", item = "A1")
                    }
                }
            }
            val s = sess(SessionConfig(mode = 1, seed = "same"), listOf(d, Recorder().def))
            val out = drive(s, tl(0.0, 15.0) { t, sn ->
                val dd = 100 - Math.round(t * 10).toDouble()
                sn.copy(speed = 36.0, blinker = if (dd < 50) Blinker.RIGHT else Blinker.OFF, steer = if (t > 10 && t < 13) 15.0 else 0.0,
                    nav = nav(tbt = if (dd > 0) listOf(tbt("t1", "TURN_RIGHT", dd, TurnDir.RIGHT)) else emptyList()))
            })
            return listOf(out.say, out.blocked, out.events, s.finish(arrived = true)).toString()
        }
        assertEquals(run(), run())
    }

    @Test
    fun rankCoursesMatchesTheMockup() {
        fun best(m: Int): String = BaroEngine.courses.rank(m).let { r -> r.list.first { it.id == r.bestId }.letter }
        assertEquals(listOf("B", "B", "A", "B", "C"), listOf(1, 2, 3, 7, 8).map(::best))
        assertEquals("A", best(4))
        assertEquals("A", best(6))
        val r1m = BaroEngine.courses.rank(1, RankOptions(myCourses = BaroEngine.exampleMyCourses()))
        assertEquals(listOf(listOf<Any?>("A", 14.0, false, false), listOf<Any?>("B", 7.0, false, true), listOf<Any?>("C", 27.0, true, false)),
            r1m.list.map { listOf(it.letter, it.score, it.mine, it.best) })
        assertEquals("c1-B", r1m.selectedId)
        assertEquals("p.course", r1m.line?.key)
        assertEquals(JsonObject(mapOf("X" to JsonPrimitive("B"))), r1m.line?.vars)
        assertEquals("원활", r1m.list[1].congestionLabel)
        assertEquals("편안한 길", r1m.list[0].routeOption)
        assertEquals("가까운 넓은 주차장 · 편안한 길", r1m.kind)
        val r2 = BaroEngine.courses.rank(2, RankOptions(myCourses = BaroEngine.exampleMyCourses()))
        assertEquals(listOf("A", "B", "C"), r2.list.map { it.letter })
        assertTrue(r2.list[0].mine)
        assertEquals("내 코스(c2-A)는 프로필에 있을 때만", 2, BaroEngine.courses.rank(2).list.size)
        assertEquals("c2-B", BaroEngine.courses.rank(2).bestId)
    }

    @Test
    fun starredTeamCourseBecomesMyCourseRowAndMyCoursesComeFirst() {
        val r = BaroEngine.courses.rank(1, RankOptions(myCourses = listOf(MyCourse(id = "mc-9", name = "시민체육관 주차장", mode = 1, courseRef = "c1-B"))))
        assertEquals(listOf("A" to false, "B" to true), r.list.map { it.letter to it.mine })
        assertEquals("mc-9", r.list[1].myCourseId)
        val r2 = BaroEngine.courses.rank(2, RankOptions(myCourses = BaroEngine.exampleMyCourses() +
            MyCourse(id = "mc-c", name = "동네 병원", mode = 2, source = CourseSource.BOOKMARK, placeId = "clinic")))
        assertEquals(listOf(Triple("A", "우리 동네 한 바퀴", true), Triple("B", "초등학교 뒤 골목", false), Triple("D", "동네 병원", true)),
            r2.list.map { Triple(it.letter, it.name, it.mine) })
        assertEquals("clinic", r2.list[2].placeId)
        assertNull(r2.list[2].courseId)
        assertEquals(CourseSource.BOOKMARK, r2.list[2].source)
        val other = BaroEngine.courses.rank(6, RankOptions(myCourses = listOf(MyCourse(id = "mc-x", mode = 6, courseRef = "c1-B", name = "체육관"))))
        assertEquals("다른 모드 코스를 가져오면 새 글자", other.list.size, other.list.map { it.letter }.toSet().size)
    }

    @Test
    fun movedTownHasNoTeamCourses() {
        val r = BaroEngine.courses.rank(1, RankOptions(town = "moved", myCourses = BaroEngine.exampleMyCourses()))
        assertTrue(r.empty)
        assertEquals("p.course.none", r.line?.key)
        assertNull(r.selectedId)
        val r2 = BaroEngine.courses.rank(2, RankOptions(town = "moved",
            myCourses = listOf(MyCourse(id = "mc-1", mode = 2, name = "동네 병원", source = CourseSource.RECENT, placeId = "clinic", town = "moved"))))
        assertEquals(listOf("동네 병원"), r2.list.map { it.name })
        assertEquals("mc-1", r2.bestId)
        assertEquals(JsonObject(mapOf("X" to JsonPrimitive(r2.list[0].letter))), r2.line?.vars)
        assertTrue(BaroEngine.courses.rank(8, RankOptions(town = "moved")).empty)
    }

    @Test
    fun withoutCongestionNearestFirstAndSeedsAreDeterministic() {
        val r = BaroEngine.courses.rank(1, RankOptions(tripOk = false, myCourses = BaroEngine.exampleMyCourses()))
        assertNull(r.bestId)
        assertEquals("가장 가까운 곳", "c1-A", r.selectedId)
        assertNull(r.line)
        assertTrue(r.list.all { it.congestion == null && it.score == null && !it.best })
        assertEquals("시드 1 = 목업 값", listOf<Int?>(0, 2, 1), BaroEngine.courses.rank(3, RankOptions(congestionSeed = 1)).list.map { it.congestion })
        val seen = HashSet<String>()
        for (seed in 2 until 12) {
            val a = BaroEngine.courses.rank(3, RankOptions(congestionSeed = seed))
            assertEquals("같은 시드면 같음", a, BaroEngine.courses.rank(3, RankOptions(congestionSeed = seed)))
            a.list.forEach { assertTrue((it.congestion ?: -1) in 0..2) }
            seen += a.list.joinToString("") { it.congestion.toString() }
        }
        assertTrue("다시 받으면 바뀜", seen.size > 1)
    }

    @Test
    fun licenseModeShowsAllTestCoursesAndAutonomousHasNone() {
        val r = BaroEngine.courses.rank(8, RankOptions(coursesDone = listOf("A", "B")))
        assertEquals(listOf("A" to true, "B" to true, "C" to false, "D" to false), r.list.map { it.letter to it.ran })
        assertTrue(r.list[2].best)
        assertEquals("2026-11-08", r.list[0].checkedAt)
        assertEquals(3, r.list[0].segments)
        val r5 = BaroEngine.courses.rank(5)
        assertTrue(r5.autonomous)
        assertEquals(0, r5.list.size)
        assertNull(r5.line)
    }

    @Test
    fun fadingAndFirst2CarryAcrossSessions() {
        var profile = Profile(id = "p1", sessionCount = mapOf(2 to 0))
        class Run(val cfg: SessionConfig, val out: Drive, val rec: SessionRecord)
        fun run(): Run {
            val cfg = BaroEngine.buildConfig(2, profile, PrepSession(mode = 2, courseId = "c2-B"))
            val s = sess(cfg, listOf(script(listOf(
                sayAction(1.0, "t1c.pre", "C1", Category.PRE),
                Action(30.0) { c, _ -> c.card(item = "B1", key = "t1c.fix", title = "출발이 셌어요", category = CardCategory.FIX) },
            ))))
            val out = drive(s, tl(0.0, 31.0) + stopAt(31.0))
            val rec = s.finish(arrived = true)
            profile = profile.copy(sessionCount = mapOf(2 to (profile.sessionCount[2] ?: 0) + 1))
            return Run(cfg, out, rec)
        }
        var r = run()
        assertTrue(said(r.out, "t1c.pre"))
        assertEquals(listOf("출발이 셌어요"), r.out.cards.map { it.title })
        r = run()
        assertEquals(2, r.cfg.noInMode)
        assertTrue(said(r.out, "t1c.pre"))
        profile = profile.copy(fading = mapOf(2 to mapOf("B1" to FadeState(2, true))))
        r = run()
        assertEquals(3, r.cfg.noInMode)
        assertTrue("3회차부터 C1 안 함", blocked(r.out, "t1c.pre", BlockReason.OFF))
        assertTrue("내려간 B1은 리포트로만", blocked(r.out, "t1c.fix", BlockReason.AFTER))
        assertEquals(0, r.out.cards.size)
        assertEquals(listOf(Delivery.OFF, Delivery.AFTER), listOf(r.rec.items["C1"], r.rec.items["B1"]))
        profile = profile.copy(fading = mapOf(2 to mapOf("B1" to FadeState(2, false))))
        r = run()
        assertEquals(listOf("출발이 셌어요"), r.out.cards.map { it.title })
    }

    @Test
    fun skippedTurnsAreNotJudgedAndSkippedTimeIsNotDriving() {
        val rec = Recorder()
        val judge = DetectorDef(id = "t1c.judge2", items = listOf("C2")) { ctx ->
            ctx.on<TurnApproach> { a -> if (a.blinkOk && a.blinkOnDist == a.distance) ctx.emit("C2", EventResult.OK, EventData(turnId = a.id)) }
            ctx.on<TurnPassed> { p -> if (!p.skipped && !p.missed) ctx.emit("C2", EventResult.FAIL, EventData(turnId = p.id)) }
            DetectorInstance.NONE
        }
        val s = sess(SessionConfig(mode = 1), listOf(rec.def, judge))
        drive(s, tl(0.0, 6.0) { t, sn ->
            val d = 100 - Math.round(t * 10).toDouble()
            sn.copy(speed = 36.0, blinker = if (d <= 60) Blinker.RIGHT else Blinker.OFF, nav = nav(tbt = if (d > 0) listOf(tbt("t1", "TURN_RIGHT", d, TurnDir.RIGHT)) else emptyList()))
        })
        drive(s, listOf(snap(t = 40.0, gear = Gear.D, speed = 36.0, skip = true, nav = NAV0.copy(tbt = listOf(tbt("t2", "TURN_LEFT", 400.0, TurnDir.LEFT))))))
        drive(s, tl(40.0, 45.0) { t, sn -> sn.copy(speed = 36.0, nav = NAV0.copy(tbt = listOf(tbt("t2", "TURN_LEFT", 400 - Math.round((t - 40) * 10).toDouble(), TurnDir.LEFT)))) })
        assertEquals(listOf("t1" to true), rec.of<TurnPassed>("turnPassed").map { it.id to it.skipped })
        val out = s.finish(arrived = true)
        assertEquals(listOf(Triple("C2", EventResult.EXCLUDED, "skipped")), out.events.map { Triple(it.item, it.result, it.data.excluded) })
        assertEquals("0.1~6초(5.9초) + 건너뛴 뒤 5초. 34초 틈은 뺌", 10.9, out.driveSec, 1e-9)
    }

    @Test
    fun timeGapsDropTheMeasuringWindows() {
        val rec = Recorder()
        val s = sess(SessionConfig(mode = 1), listOf(rec.def))
        drive(s, hold(0.0, 2.0, brake = true))
        drive(s, listOf(snap(t = 10.0, gear = Gear.D, speed = 40.0)) + hold(40.0, 7.0, 10.0))
        assertEquals("서 있다가 틈 뒤 40km/h → 출발로 재지 않음", 0, rec.of<StartEvent>("start").size)
        drive(s, listOf(snap(t = 30.0, gear = Gear.D, speed = 0.0, brake = true)) + hold(0.0, 3.0, 30.0, brake = true))
        assertEquals("틈 사이에 줄어든 속도를 급제동으로 보지 않음", 0, rec.of<BrakeEndEvent>("brakeEnd").count { it.maxDrop1s >= 11 })
    }

    @Test
    fun ctxHintsTrackInputsAndOutputTurn() {
        var captured: DetectorContext? = null
        val inputs = ArrayList<SessionInput>()
        val d = DetectorDef(id = "t1c.ctx") { c ->
            captured = c
            c.on<InputEvent> { inputs += it.input }
            DetectorInstance.NONE
        }
        val s = sess(SessionConfig(mode = 1), listOf(d))
        val ctx = checkNotNull(captured)
        val o1 = s.step(snap(t = 0.1, gear = Gear.D, speed = 0.0, brake = true))
        ctx.hints.badge = com.barodrive.core.model.WeatherBadge.RAIN
        ctx.hints.hero = "i-belt"
        val o2 = s.step(snap(t = 0.2, gear = Gear.D, speed = 0.0, brake = true))
        assertNull(o1.hints.badge)
        assertEquals(com.barodrive.core.model.WeatherBadge.RAIN, o2.hints.badge)
        assertEquals("i-belt", s.hints.hero)
        assertTrue(ctx.track.stopped)
        assertEquals(Gear.D, ctx.track.gear)
        assertEquals(0.1, ctx.track.stopDur, 1e-9)
        assertEquals(0.2, ctx.t, 1e-9)
        assertEquals(1, ctx.mode)
        assertTrue(ctx.rng.next() in 0.0..1.0)
        assertTrue(ctx.rng.int(1, 3) in 1..3)
        s.input(SessionInput.PbConfirmed)
        s.input(SessionInput.DriverAsk)
        assertEquals(listOf<SessionInput>(SessionInput.PbConfirmed, SessionInput.DriverAsk), inputs)
        assertEquals("항목이 아닌 말", Delivery.NOW, s.deliver("K8"))
        assertEquals(Delivery.NOW, s.deliver(null))
        assertEquals("모드 1의 주차 안내", Delivery.OFF, s.deliver("PK1"))
        val s2 = sess(SessionConfig(mode = 1))
        val far = s2.step(snap(t = 0.1, gear = Gear.D, speed = 36.0, nav = NAV0.copy(tbt = listOf(tbt("k", "TURN_LEFT", 180.0, TurnDir.LEFT)))))
        assertNull("150m 밖", far.turn)
        val near = s2.step(snap(t = 0.2, gear = Gear.D, speed = 36.0, blinker = Blinker.RIGHT, nav = NAV0.copy(tbt = listOf(tbt("k", "TURN_LEFT", 73.0, TurnDir.LEFT)))))
        val tt = checkNotNull(near.turn)
        assertEquals(listOf<Any?>("k", TurnDir.LEFT, 73.0, false, true), listOf(tt.id, tt.dir, tt.distance, tt.blinkOk, tt.blinkWrong))
    }

    @Test
    fun seededRandomMatchesTheManifestRngCheck() {
        val check = com.barodrive.core.engine.golden.GoldenFixtures.manifest["rngCheck"] as kotlinx.serialization.json.JsonArray
        assertTrue(check.isNotEmpty())
        for (e in check) {
            val o = e as JsonObject
            val sp = o["seed"] as JsonPrimitive
            val seed = sp.content
            val r = if (sp.isString) SeededRandom(seed) else SeededRandom(seed.toLong())
            if (sp.isString) assertEquals(seed, (o["hash"] as JsonPrimitive).content.toLong(), SeededRandom.hash(seed))
            val first5 = (o["first5"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content.toDouble() }
            assertEquals(seed, first5, List(5) { r.next() })
        }
    }
}
