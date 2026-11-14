package com.barodrive.core.engine

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.FeedbackContext
import com.barodrive.core.engine.api.RenderOptions
import com.barodrive.core.engine.api.ResolveOptions
import com.barodrive.core.engine.config.ItemResolver
import com.barodrive.core.engine.content.LineRenderException
import com.barodrive.core.engine.content.LineRotation
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Courses
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Delivery.AFTER
import com.barodrive.core.model.Delivery.NOW
import com.barodrive.core.model.Delivery.OFF
import com.barodrive.core.model.DeliveryReason
import com.barodrive.core.model.EngineFlags
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.FeedbackPreset
import com.barodrive.core.model.FeedbackSettings
import com.barodrive.core.model.FirstMetrics
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.PlaceList
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.PrepWeather
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ResolvedDelivery
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.Severe
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.Weather
import com.barodrive.core.model.WeatherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프로토타입 t1c_engine.test.js — 데이터·전달 방식·buildConfig·대사·기준 (설계 6.3.2·6.6·6.8). */
class T1cDataTest {
    private val table = mapOf(
        "J3" to "OOOOOXOO", "A1" to "OOOOOXOO", "PB1" to "OOXXXXOO", "L1" to "OOOOOXOO", "B1" to "OOSSSXSO", "C1" to "OOXXXXXO",
        "C2" to "OOSSSXSO", "C4" to "OOSSSXSO", "C5" to "OOSSSXSO", "F1" to "OOSSSXSO", "F2" to "OOOOOXOO", "E1" to "OOOOOXOO",
        "E2" to "OOOOOXOO", "C3" to "OOOOOXOO", "C6" to "OOOOOXOO", "J1" to "OOOOOXOO", "G1" to "SSSSSXSS", "PK1" to "XXXXXOXX",
        "PK2" to "XXXXXOXX", "PK3" to "XXXXXOXX", "PB2" to "OOXXXOOO", "HL1" to "XXXXXXOX", "HL2" to "XXXXXXOX", "HL3" to "XXXXXXOX",
    )

    private fun rd(v: Delivery, r: DeliveryReason) = ResolvedDelivery(v, r)

    @Test
    fun items24AndModeDefaultsMatchTheTable() {
        assertEquals(24, BaroEngine.itemIds().size)
        assertEquals(table.keys.sorted(), BaroEngine.itemIds().sorted())
        for (m in 1..8) {
            val r = BaroEngine.resolveItems(m, null, ResolveOptions(noInMode = 1))
            for ((id, codes) in table) {
                assertEquals("$id 모드 $m 기본값", codes[m - 1].toString(), BaroEngine.defaultFor(id, m)?.code)
                assertEquals("$id 모드 $m 첫 회차 값", codes[m - 1].toString(), r[id]?.value?.code)
            }
        }
    }

    @Test
    fun allowedValuesFixedItemsAndNeeds() {
        assertEquals(listOf(NOW, OFF), BaroEngine.allowed("C1"))
        assertEquals(listOf(NOW, OFF), BaroEngine.allowed("L1"))
        assertEquals(listOf(NOW, OFF), BaroEngine.allowed("PK1"))
        assertEquals(listOf(NOW, OFF), BaroEngine.allowed("HL1"))
        assertEquals(listOf(AFTER, OFF), BaroEngine.allowed("G1"))
        for (id in listOf("J3", "A1", "PB1", "B1", "C2", "C4", "C5", "F1", "PK2", "PK3", "PB2", "HL2", "HL3")) {
            assertEquals(id, listOf(NOW, AFTER, OFF), BaroEngine.allowed(id))
            assertFalse(id, BaroEngine.isFixed(id))
        }
        for (id in listOf("F2", "E1", "E2", "C3", "C6", "J1")) {
            assertTrue(id, BaroEngine.isFixed(id))
            assertEquals(id, emptyList<Delivery>(), BaroEngine.allowed(id))
        }
        assertEquals("HL2는 안전 분류지만 고정은 아님", Category.SAFETY, BaroEngine.itemDef("HL2")?.cats?.first())
        assertEquals(listOf("B1", "E1", "E2", "F1", "F2", "HL2", "PK2").sorted(), BaroEngine.itemsOffByCaps(SignalCaps(speed = false)).sorted())
        assertEquals(listOf("C1", "C2", "C3", "C4", "C5", "C6"), BaroEngine.itemsOffByCaps(SignalCaps(blinker = false)).sorted())
        assertEquals(listOf("C1", "C2", "C3", "E1", "E2"), BaroEngine.itemsOffByCaps(SignalCaps(nav = false)).sorted())
        assertEquals(listOf("C4", "C5", "G1", "HL3", "PK3"), BaroEngine.itemsOffByCaps(SignalCaps(steer = false)).sorted())
        assertEquals(listOf("A1"), BaroEngine.itemsOffByCaps(SignalCaps(beltDriver = false)))
        assertEquals("대안이 있는 신호는 항목을 끄지 않음", emptyList<String>(),
            BaroEngine.itemsOffByCaps(SignalCaps(handbrake = false, passenger = false, wiper = false)))
        for (id in BaroEngine.itemIds()) {
            val d = BaroEngine.itemDef(id)
            assertTrue("$id 이름·짧은 이름·아이콘", d != null && d.name.isNotEmpty() && d.short.isNotEmpty() && !d.icon.isNullOrEmpty())
        }
    }

    @Test
    fun safety3SwitchOffRuns21Items() {
        assertEquals(21, BaroEngine.itemIds(extraSafety = false).size)
        val r = ItemResolver.resolve(1, null, ResolveOptions(), extraSafety = false)
        for (id in listOf("C3", "C6", "J1")) assertNull(id, r[id])
        val cfg = BaroEngine.buildConfig(1, null, flags = EngineFlags(extraSafety = false))
        assertFalse("C3" in cfg.items)
        assertFalse(cfg.flags.extraSafety)
        assertEquals("없는 항목은 안 함", OFF, sess(cfg).deliver("C6"))
        assertEquals(24, BaroEngine.itemIds().size)
    }

    @Test
    fun tasksPerModeAndLabels() {
        fun ids(m: Int) = BaroEngine.modeTasks(m).map { it.id }
        assertEquals(listOf("T-START", "T-STOP", "T-SIGNAL1"), ids(1))
        assertEquals(listOf("T-30", "T-SIGNAL30", "T-WAIT3", "T-STOP"), ids(2))
        assertEquals(listOf("T-LIMIT", "T-SIGNALPRE", "T-STOPSIG"), ids(3))
        assertEquals(listOf("T-IC", "T-LIMIT", "T-NOBRAKE"), ids(4))
        assertEquals(emptyList<String>(), ids(5))
        assertEquals(emptyList<String>(), ids(6))
        assertEquals(listOf("T-HILLUP", "T-HILLDOWN", "T-HILLPARK"), ids(7))
        assertEquals(listOf("T-LIMIT", "T-SIGNAL30", "T-WAIT3", "T-STOPSIG"), ids(8))
        assertEquals("2단계 표시 문구", "멈출 때 부드럽게 멈추기", BaroEngine.modeTasks(2).first { it.id == "T-STOP" }.label)
        assertEquals("브레이크 부드럽게 밟아서 멈추기", BaroEngine.modeTasks(1).first { it.id == "T-STOP" }.label)
        assertEquals("30 구역", BaroEngine.taskDef("T-30")?.short)
        assertEquals("30 구역 지키기", BaroEngine.taskDef("T-30")?.nextLabel)
        assertEquals("어린이보호구역 30 지키기", BaroEngine.taskDef("T-SCHOOL")?.nextLabel)
        assertTrue(BaroEngine.taskDef("T-SCHOOL")?.safetyOnly == true)
        for (m in 1..8) for (t in BaroEngine.modeTasks(m)) assertTrue(t.id, t.label.isNotEmpty() && t.short.isNotEmpty() && t.items.isNotEmpty())
    }

    @Test
    fun coursesPlacesAndTowns() {
        assertEquals(19, Courses.all.size)
        val c = { id: String -> checkNotNull(Courses.course(id)) }
        assertEquals(listOf(2.6, 7.0, 0.0), listOf(c("c1-B").km, c("c1-B").min.toDouble(), c("c1-B").congestion.toDouble()))
        assertEquals(listOf(2.8, 14.0, 2.0), listOf(c("c2-C").km, c("c2-C").min.toDouble(), c("c2-C").congestion.toDouble()))
        assertEquals("고속도로 우선", c("c4-A").routeOption)
        for (k in listOf("A", "B", "C", "D")) {
            val x = c("c8-$k")
            assertEquals("2026-11-08", x.checkedAt)
            assertEquals(3, x.segments)
            assertEquals(k, x.letter)
        }
        assertEquals(listOf("c1-C", "c2-A"), Courses.all.filter { it.own }.map { it.id })
        assertEquals(12.4, checkNotNull(Courses.place("mart")).km, 0.0)
        assertEquals(5, Courses.places.count { it.list == PlaceList.BOOKMARK })
        assertEquals(listOf("home", "moved"), Courses.towns.map { it.id })
        assertEquals(listOf("c1-C" to 1, "c2-A" to 2), BaroEngine.exampleMyCourses().map { it.courseRef to it.mode })
    }

    @Test
    fun userOverrideWinsAndInvalidOrFixedChangesAreDropped() {
        val profile = Profile(feedback = FeedbackSettings(mapOf(1 to mapOf("C2" to OFF, "C1" to AFTER, "G1" to NOW, "E1" to OFF, "F2" to AFTER))))
        val r = BaroEngine.resolveItems(1, profile, ResolveOptions(noInMode = 1))
        assertEquals(rd(OFF, DeliveryReason.USER), r["C2"])
        assertEquals("C1의 S는 허용 값 밖", rd(NOW, DeliveryReason.DEFAULT), r["C1"])
        assertEquals("G1의 O는 허용 값 밖", rd(AFTER, DeliveryReason.DEFAULT), r["G1"])
        assertEquals("안전 항목은 고정", rd(NOW, DeliveryReason.FIXED), r["E1"])
        assertEquals(rd(NOW, DeliveryReason.FIXED), r["F2"])
        assertEquals("다른 모드에는 닿지 않음", NOW, BaroEngine.resolveItems(2, profile)["C2"]?.value)
    }

    @Test
    fun c1First2InModes2And8() {
        for (m in listOf(2, 8)) {
            assertEquals(rd(NOW, DeliveryReason.FIRST2), BaroEngine.resolveItems(m, null, ResolveOptions(noInMode = 1))["C1"])
            assertEquals(rd(NOW, DeliveryReason.FIRST2), BaroEngine.resolveItems(m, null, ResolveOptions(noInMode = 2))["C1"])
            assertEquals(rd(OFF, DeliveryReason.FIRST2), BaroEngine.resolveItems(m, null, ResolveOptions(noInMode = 3))["C1"])
        }
        assertEquals(rd(NOW, DeliveryReason.DEFAULT), BaroEngine.resolveItems(1, null, ResolveOptions(noInMode = 7))["C1"])
        assertEquals(OFF, BaroEngine.resolveItems(3, null, ResolveOptions(noInMode = 1))["C1"]?.value)
        assertEquals(OFF, BaroEngine.resolveItems(2, Profile(sessionCount = mapOf(2 to 2)))["C1"]?.value)
        assertEquals(NOW, BaroEngine.resolveItems(2, Profile(sessionCount = mapOf(2 to 1)))["C1"]?.value)
    }

    @Test
    fun fadingUserFirstAndC2PullsC1() {
        val fading = mapOf(
            1 to mapOf("B1" to FadeState(2, true), "C2" to FadeState(2, true), "A1" to FadeState(faded = true), "E1" to FadeState(faded = true)),
            3 to mapOf("B1" to FadeState(faded = true)),
        )
        val r = BaroEngine.resolveItems(1, Profile(fading = fading), ResolveOptions(noInMode = 4))
        assertEquals(rd(AFTER, DeliveryReason.FADING), r["B1"])
        assertEquals(rd(AFTER, DeliveryReason.FADING), r["C2"])
        assertEquals("C2가 내려가면 C1도 끔(6.7)", rd(OFF, DeliveryReason.FADING), r["C1"])
        assertEquals("A1은 안 내려감", NOW, r["A1"]?.value)
        assertEquals("안전은 안 내려감", NOW, r["E1"]?.value)
        assertEquals("기본값이 S면 그대로", rd(AFTER, DeliveryReason.DEFAULT), BaroEngine.resolveItems(3, Profile(fading = fading))["B1"])
        val withUser = BaroEngine.resolveItems(1, Profile(fading = fading, feedback = FeedbackSettings(mapOf(1 to mapOf("B1" to NOW, "C1" to NOW)))))
        assertEquals(rd(NOW, DeliveryReason.USER), withUser["B1"])
        assertEquals(rd(NOW, DeliveryReason.USER), withUser["C1"])
        val userC2 = BaroEngine.resolveItems(1, Profile(fading = fading, feedback = FeedbackSettings(mapOf(1 to mapOf("C2" to NOW)))))
        assertEquals("C2를 사용자가 정했으면 C1은 그대로", rd(NOW, DeliveryReason.DEFAULT), userC2["C1"])
    }

    @Test
    fun priorityUserOverFadingOverFirst2OverDefault() {
        val faded = mapOf(2 to mapOf("C2" to FadeState(faded = true)))
        assertEquals("도움 줄여 가기 > 처음 2회차", rd(OFF, DeliveryReason.FADING), BaroEngine.resolveItems(2, Profile(fading = faded), ResolveOptions(noInMode = 1))["C1"])
        assertEquals(rd(NOW, DeliveryReason.USER),
            BaroEngine.resolveItems(2, Profile(fading = faded, feedback = FeedbackSettings(mapOf(2 to mapOf("C1" to NOW)))), ResolveOptions(noInMode = 5))["C1"])
        assertEquals(rd(OFF, DeliveryReason.USER),
            BaroEngine.resolveItems(2, Profile(feedback = FeedbackSettings(mapOf(2 to mapOf("C1" to OFF)))), ResolveOptions(noInMode = 1))["C1"])
        assertEquals(rd(OFF, DeliveryReason.FIRST2), BaroEngine.feedback.resolve(2, "C1", FeedbackContext(sessionNoInMode = 3)))
        assertEquals(rd(OFF, DeliveryReason.FADING),
            BaroEngine.feedback.resolve(2, "C1", FeedbackContext(sessionNoInMode = 1, fading = mapOf("C2" to FadeState(faded = true)))))
        assertEquals(rd(OFF, DeliveryReason.USER),
            BaroEngine.feedback.resolve(1, "B1", FeedbackContext(overrides = mapOf("B1" to OFF), fading = mapOf("B1" to FadeState(faded = true)))))
        assertNull("모르는 항목", BaroEngine.feedback.resolve(1, "ZZ"))
    }

    @Test
    fun autonomousPresetAndExamplePresets() {
        val profile = Profile(
            myPresets = listOf(FeedbackPreset("p-1", "내 설정", items = mapOf("B1" to NOW, "C2" to OFF, "E1" to OFF, "C1" to AFTER, "G1" to AFTER))),
            feedback = FeedbackSettings(mapOf(5 to mapOf("A1" to OFF, "B1" to OFF))),
        )
        val r = BaroEngine.resolveItems(5, profile, ResolveOptions(presetId = "p-1"))
        assertEquals(rd(NOW, DeliveryReason.PRESET), r["B1"])
        assertEquals(rd(OFF, DeliveryReason.PRESET), r["C2"])
        assertEquals("안전 항목은 내 설정에서도 바로", rd(NOW, DeliveryReason.FIXED), r["E1"])
        assertEquals("허용 값 밖은 버림", rd(OFF, DeliveryReason.DEFAULT), r["C1"])
        assertEquals("설정에 없는 항목은 FB-1 변경", rd(OFF, DeliveryReason.USER), r["A1"])
        val unsaved = BaroEngine.resolveItems(5, profile, ResolveOptions(presetId = "p-1", presetItems = mapOf("B1" to AFTER)))
        assertEquals("저장하지 않은 '새로 만들기' 값이 먼저", AFTER, unsaved["B1"]?.value)
        assertEquals("자율 설정은 모드 5에만", NOW, BaroEngine.resolveItems(1, profile, ResolveOptions(presetId = "p-1"))["C2"]?.value)
        val (commute, rain) = BaroEngine.examplePresets()
        assertEquals("출퇴근용", commute.name)
        assertEquals("비 오는 날", rain.name)
        assertEquals(listOf("A1", "B1", "C1", "C2", "C4", "C5", "F1", "G1", "J3", "L1", "PB1", "PB2"), commute.items.keys.sorted())
        assertEquals("C1은 완료 후가 없어 안 함", OFF, commute.items["C1"])
        assertEquals(OFF, commute.items["L1"])
        assertEquals(AFTER, commute.items["B1"])
        for ((id, v) in commute.items) assertTrue("출퇴근용 $id", v in BaroEngine.allowed(id))
        assertEquals(listOf(NOW, NOW, NOW, AFTER), listOf("B1", "F1", "J3", "C2").map { rain.items[it] })
        assertNotSame("부를 때마다 새 값", commute.items, BaroEngine.examplePresets()[0].items)
    }

    @Test
    fun buildConfigTurnsProfileAndPrepIntoTheSessionConfig() {
        val profile = Profile(
            id = "p1", coach = Coach.MOM, honorific = Honorific(HonorificType.DAUGHTER), sessionCount = mapOf(1 to 3, 2 to 2), g1Baseline = 1.1,
            firstMetrics = FirstMetrics(c2Avg = 38.0, stopFailRate = 0.375, g1 = 1.1), fading = mapOf(2 to mapOf("B1" to FadeState(2, true))),
            feedback = FeedbackSettings(mapOf(2 to mapOf("G1" to OFF))),
            sessions = listOf(
                SessionRecord(id = "s3", mode = 2, noInMode = 2, achievement = 0.83, qualified = true, metrics = SessionMetrics(g1 = 0.9)),
                SessionRecord(id = "s2", mode = 1, noInMode = 3, achievement = 0.9),
                SessionRecord(id = "s1", mode = 2, noInMode = 1, achievement = 0.5),
            ),
        )
        val prep = PrepSession(mode = 2, courseId = "c2-B", weather = PrepWeather(snow = true), preEvents = listOf(JudgeEvent(item = "L1", result = EventResult.INFO)))
        val cfg = BaroEngine.buildConfig(2, profile, prep, SignalCaps(speed = false))
        assertEquals(2, cfg.mode)
        assertEquals(2, cfg.stage)
        assertEquals(3, cfg.noInMode)
        assertEquals("3회차라 C1 안 함", OFF, cfg.items["C1"])
        assertEquals(DeliveryReason.FIRST2, cfg.itemReasons["C1"])
        assertEquals(AFTER, cfg.items["B1"])
        assertEquals(DeliveryReason.FADING, cfg.itemReasons["B1"])
        assertEquals(OFF, cfg.items["G1"])
        assertEquals(24, cfg.items.size)
        assertFalse(cfg.caps.speed)
        assertTrue(cfg.caps.blinker)
        assertEquals(Weather(snow = true, rain = false, severe = null), cfg.weather)
        assertEquals(Coach.MOM, cfg.coach)
        assertEquals(HonorificType.DAUGHTER, cfg.honorific.type)
        assertEquals(1.1, cfg.history.baselineG1)
        assertEquals(38.0, cfg.history.firstSessionMetrics?.c2Avg)
        assertEquals("그 모드 회차만, 최신이 앞", listOf("s3", "s1"), cfg.history.recentSessionsInMode.map { it.id })
        assertEquals("c2-B", cfg.courseId)
        assertEquals("초등학교 뒤 골목", cfg.dest)
        assertEquals(1, cfg.preEvents.size)
        assertEquals("마트", BaroEngine.buildConfig(5, profile, PrepSession(mode = 5, placeId = "mart")).dest)
        assertNull(BaroEngine.buildConfig(5, profile, PrepSession(mode = 5, placeId = "mart")).stage)
        assertEquals("빈 프로필도 됨", 1, BaroEngine.buildConfig(1, null, null).noInMode)
    }

    @Test
    fun linesRotateEachSessionAndPeekDoesNotAdvance() {
        val rot = LineRotation()
        fun r(peek: Boolean = false, index: Int? = null) = TEST_LINES.render("t1c.rot", Coach.DAD, emptyMap(), null, rot, peek, index)
        assertEquals("첫째 문장.", r(peek = true))
        assertEquals("첫째 문장.", r())
        assertEquals("둘째 문장.", r())
        assertEquals("첫째 문장.", r())
        assertEquals("index로 고르면 순번을 안 넘김", "둘째 문장.", r(index = 1))
        assertEquals("둘째 문장.", r())
        val a = sess(SessionConfig(mode = 1, coach = Coach.SIB))
        val b = sess(SessionConfig(mode = 1, coach = Coach.SIB))
        assertEquals("하나.", a.renderLine("t1c.rot"))
        assertEquals("둘.", a.renderLine("t1c.rot"))
        assertEquals("세션마다 따로 돈다", "하나.", b.renderLine("t1c.rot"))
        assertEquals("하나.", a.renderLine("t1c.rot", peek = true))
        // 파사드: 키 → 순번 맵(core.js rotation 객체)
        val map = HashMap<String, Int>()
        val first = BaroEngine.lines.render("c2.ok", Coach.DAD, mapOf("N" to 57), opts = RenderOptions(rotation = map))
        val second = BaroEngine.lines.render("c2.ok", Coach.DAD, mapOf("N" to 57), opts = RenderOptions(rotation = map, peek = true))
        assertEquals("좋았어, 50미터 전에 켰어.", first)
        assertEquals("좋았어, 타이밍 딱 맞았어.", second)
        assertEquals(1, map["c2.ok"])
    }

    @Test
    fun honorificAndVariablesFollowTheRules() {
        fun r(key: String, coach: Coach, h: Honorific?, vars: Map<String, Any?> = emptyMap()) =
            TEST_LINES.render(key, coach, vars, h, LineRotation())
        val d = Honorific(HonorificType.DAUGHTER)
        val none = Honorific(HonorificType.NONE)
        assertEquals("어머 잘했다 우리 딸! 딱 좋았어!", r("t1c.hon", Coach.MOM, d))
        assertEquals("어머 잘했다! 딱 좋았어!", r("t1c.hon", Coach.MOM, none))
        assertEquals("어머 잘했다! 딱 좋았어!", r("t1c.hon", Coach.MOM, null))
        assertEquals("어머 잘했다 지은아! 딱 좋았어!", r("t1c.hon", Coach.MOM, Honorific(HonorificType.CUSTOM, "  지은아  ")))
        assertEquals("어머 잘했다 가나다라마바사아! 딱 좋았어!", r("t1c.hon", Coach.MOM, Honorific(HonorificType.CUSTOM, "가나다라마바사아자차")))
        assertEquals("우리 딸, 오늘 고생 많았어.", r("t1c.hon", Coach.DAD, d))
        assertEquals("오늘 고생 많았어.", r("t1c.hon", Coach.DAD, none))
        assertEquals("오늘 끝! 우리 딸, 고생했어.", r("t1c.hon2", Coach.MOM, d))
        assertEquals("오늘 끝! 고생했어.", r("t1c.hon2", Coach.MOM, none))
        assertEquals("그래, 우리 딸!", r("t1c.hon2", Coach.DAD, d))
        assertEquals("그래!", r("t1c.hon2", Coach.DAD, none))
        assertEquals("", BaroEngine.honorificText(d, Coach.SIB))
        assertEquals("우리 딸", BaroEngine.honorificText(d, Coach.MOM))
        assertEquals("50미터 전에 켰어.", r("t1c.vars", Coach.DAD, null, mapOf("N" to 57)))
        assertEquals("30미터 전. 오른쪽으로 가는 길이야.", r("t1c.vars", Coach.SIB, null, mapOf("N" to 39.9, "dir" to "R")))
        assertEquals("120미터 전. 왼쪽으로 가는 길이야.", r("t1c.vars", Coach.SIB, null, mapOf("N" to 120, "dir" to "LEFT")))
        assertEquals("비 오는 날은 40까지야!", r("t1c.vars", Coach.MOM, null, mapOf("wx" to "rain", "L" to 40)))
        assertEquals("오늘 같은 날은 25까지야!", r("t1c.vars", Coach.MOM, null, mapOf("wx" to "severe", "L" to 25)))
        assertThrows(LineRenderException::class.java) { r("t1c.vars", Coach.DAD, null) }
        assertThrows(LineRenderException::class.java) { r("t1c.none", Coach.DAD, null) }
        val rot = LineRotation()
        assertThrows(LineRenderException::class.java) { TEST_LINES.render("t1c.vars", Coach.DAD, emptyMap(), null, rot) }
        assertEquals("오류가 나면 순번을 넘기지 않는다", 0, rot.count("t1c.vars"))
    }

    @Test
    fun speakDurationIsCharsTimes015Plus04() {
        assertEquals(4 * 0.15 + 0.4, BaroEngine.speakDur("좋았어."), 1e-9)
        assertEquals(16 * 0.15 + 0.4, BaroEngine.speakDur("좋았어, 50미터 전에 켰어."), 1e-9)
        assertEquals(0.4, BaroEngine.speakDur(""), 1e-9)
    }

    @Test
    fun thresholdsFollowWeather() {
        val clear = SessionWeather()
        val rain = SessionWeather(type = WeatherType.RAIN)
        val snow = SessionWeather(type = WeatherType.SNOW)
        val severe = SessionWeather(severe = Severe.ICE)
        assertEquals(11.0, BaroEngine.threshold("B1.gain", clear))
        assertEquals(8.0, BaroEngine.threshold("B1.gain", rain))
        assertEquals(8.0, BaroEngine.threshold("B1.gain", severe))
        assertEquals(7.5, BaroEngine.threshold("F1.drop", clear))
        assertEquals(6.0, BaroEngine.threshold("F1.drop", snow))
        assertEquals(11.0, BaroEngine.threshold("F2.drop", rain))
        assertEquals(1.0, BaroEngine.threshold("E.factor", clear))
        assertEquals(0.8, BaroEngine.threshold("E.factor", rain))
        assertEquals(0.5, BaroEngine.threshold("E.factor", rain.copy(severe = Severe.FOG)))
        assertEquals(40, BaroEngine.effLimit(50.0, rain))
        assertEquals(24, BaroEngine.effLimit(30.0, snow))
        assertEquals(25, BaroEngine.effLimit(50.0, severe))
        assertEquals(15, BaroEngine.effLimit(30.0, severe))
        assertEquals(60, BaroEngine.effLimit(60.0, clear))
        assertNull(BaroEngine.effLimit(null, clear))
        assertEquals(40.0, BaroEngine.threshold("E.limit", rain, limit = 50.0))
        assertEquals(24.0, BaroEngine.threshold("E2.limit", rain))
        assertEquals(40.0, BaroEngine.threshold("C2.ok", clear))
        assertEquals(110.0, BaroEngine.threshold("C2.ok", clear, ic = true))
        assertEquals(100.0, BaroEngine.threshold("C2.almost", clear, ic = true))
        assertEquals(300.0, BaroEngine.threshold("turn.near", clear, ic = true))
        assertEquals(3.0, BaroEngine.threshold("C4.sec"))
        assertNull("기준 없음(프로토타입은 예외)", BaroEngine.threshold("없는.기준"))
    }

    @Test
    fun ctxThFollowsSessionWeatherWhichNeverGoesBackToClear() {
        var captured: DetectorContext? = null
        val d = DetectorDef(id = "t1c.wx") { c -> captured = c; DetectorInstance.NONE }
        sess(SessionConfig(mode = 2, weather = Weather(snow = true)), listOf(d))
        var ctx = checkNotNull(captured)
        assertEquals(6.0, ctx.th("F1.drop"), 0.0)
        assertEquals(WeatherType.SNOW, ctx.weather().type)
        sess(SessionConfig(mode = 2, weather = Weather(severe = Severe.ICE)), listOf(d))
        ctx = checkNotNull(captured)
        assertEquals(0.5, ctx.th("E.factor"), 0.0)
        assertEquals(25, ctx.limit(50.0))
        sess(SessionConfig(mode = 2), listOf(d))
        ctx = checkNotNull(captured)
        assertEquals(11.0, ctx.th("B1.gain"), 0.0)
        ctx.setWeather(type = WeatherType.RAIN)
        assertEquals(8.0, ctx.th("B1.gain"), 0.0)
        assertEquals(40, ctx.limit(50.0))
        ctx.setWeather(type = WeatherType.CLEAR)
        assertEquals("맑음으로 되돌리지 않음", WeatherType.RAIN, ctx.weather().type)
        ctx.setWeather(cold = true)
        assertTrue(ctx.weather().cold)
    }
}
