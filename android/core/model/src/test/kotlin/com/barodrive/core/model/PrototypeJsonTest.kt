package com.barodrive.core.model

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프로토타입이 실제로 낸 JSON(회차 기록·프로필·앱 상태·세션 설정·스냅숏·엔진 출력)이 모델로 그대로 읽히고,
 * 다시 써도 프로토타입 칸이 하나도 빠지지 않는지 본다. 일부러 버리는 칸만 아래 목록에 있다.
 */
class PrototypeJsonTest {
    /** 일부러 다르게 쓰는 칸: 경로 끝 → 까닭. */
    private val allowed = mapOf(
        // 코치 말의 speaker는 늘 'coach'라 두지 않는다(Utterance KDoc).
        ".speaker|missing" to "speaker",
        // 프로토타입 기록 날씨는 아주 나쁜 날이 없으면 false를 쓴다 — 모델은 null.
        ".weather.severe|missing" to "severe false",
        // cfg의 noInMode는 sessionNoInMode와 같은 값이라 한 칸만 둔다.
        "$.noInMode|missing" to "cfg noInMode",
        // 면허 결과 alone은 프로토타입이 false 또는 수를 쓴다 — 모델은 수.
        ".license.alone|value" to "alone false → 0",
    )

    private fun unexpected(lost: Set<String>): List<String> = lost.filterNot { l -> allowed.keys.any { l.endsWith(it) } }

    @Test
    fun lossCheckCatchesFieldsTheModelDoesNotKeep() {
        val tree = BaroJson.parseToJsonElement(
            """{"mode":2,"simSec":5,"newField":1,"events":[{"t":1,"item":"C2","result":"ok","data":{"distance":40}}],
               "metrics":{"c2Avg":45,"newMetric":2},"weather":{"type":"rain","severe":false}}""",
        )

        val lost = JsonFixtures.roundTripLoss(SessionRecord.serializer(), tree)

        assertEquals(listOf("$.newField|missing", "$.metrics.newMetric|missing"), unexpected(lost))
        assertEquals(setOf("$.newField|missing", "$.metrics.newMetric|missing", "$.weather.severe|missing"), lost)
    }

    @Test
    fun recordFromPrototypeSessionLosesNothing() {
        val tree = JsonFixtures.tree("proto-record-s2b.json")

        val lost = JsonFixtures.roundTripLoss(SessionRecord.serializer(), tree)

        assertEquals(emptyList<String>(), unexpected(lost))
    }

    @Test
    fun recordFromPrototypeReadsTheRightValues() {
        val r = BaroJson.decodeFromString(SessionRecord.serializer(), JsonFixtures.text("proto-record-s2b.json"))

        assertEquals("sess-20261110-0912", r.id)
        assertEquals(2, r.mode)
        assertEquals(2, r.stage)
        assertEquals(1, r.noInMode)
        assertEquals("c2-B", r.courseId)
        assertEquals("2026-11-10T09:12:00+09:00", r.startedAt)
        assertTrue(r.durationSec > 0)
        assertEquals(SessionWeather(type = WeatherType.CLEAR, severe = Severe.ICE, by = WeatherSource.SEVERE, at = 0.0, tipCards = 5), r.weather)
        // 판정 기록: 첫 줄은 A1 벨트, C2는 회전 id·거리·종류를 data로
        assertEquals(JudgeEvent(t = 0.0, item = "A1", result = EventResult.FAIL, data = EventData(gear = Gear.D)), r.events.first())
        val c2 = r.events.first { it.item == "C2" }
        assertEquals("t1", c2.data.turnId)
        assertEquals(50.0, c2.data.distance)
        assertEquals("TURN_RIGHT", c2.data.type)
        assertEquals(TurnDir.RIGHT, c2.data.dir)
        // 말 기록: 말한 것과 막힌 것이 한 목록, 나눠 보기
        assertTrue(r.utterances.any { !it.spoken && it.reason == BlockReason.GAP && it.key == "c1" })
        assertEquals(r.utterances.count { it.spoken }, r.spoken.size)
        assertEquals(r.utterances.count { !it.spoken }, r.blocked.size)
        assertEquals("어머, 벨트! 벨트부터 매자.", r.spoken.first().text)
        // 요약
        assertEquals(listOf("T-30", "T-SIGNAL30", "T-WAIT3", "T-STOP"), r.tasks.map { it.id })
        assertEquals(TaskResult("T-STOP", "멈출 때 부드럽게 멈추기", "멈출 때 브레이크", 10, 11), r.tasks.last())
        assertEquals(NextTaskReason.SAFETY, r.nextTask?.reason)
        assertEquals(ItemStat(ok = 5, total = 6), r.itemStats?.get("C2"))
        assertEquals(6, r.metrics.c2N)
        assertEquals(true, r.metrics.k4?.first)
        assertEquals(ProgressResult(mode = 2, stage = 2, qualifiedBefore = 0, qualifiedAfter = 0), r.progress)
        assertEquals(Delivery.NOW, r.items["C1"])
        assertEquals(ShareStatus.NONE, r.share.status)
    }

    @Test
    fun profileAndAppStatesFromPrototypeLoseNothing() {
        val profile = JsonFixtures.roundTripLoss(Profile.serializer(), JsonFixtures.tree("proto-profile-s2b.json"))
        assertEquals(emptyList<String>(), unexpected(profile))

        for (id in listOf("graduated", "license-2of4", "needs-check")) {
            val lost = JsonFixtures.roundTripLoss(AppState.serializer(), JsonFixtures.tree("proto-appstate-$id.json"))
            assertEquals("데모 상태 $id", emptyList<String>(), unexpected(lost))
        }
    }

    @Test
    fun graduatedDemoStateCarriesEveryModeResult() {
        val st = BaroJson.decodeFromString(AppState.serializer(), JsonFixtures.text("proto-appstate-graduated.json"))
        val p = st.activeProfile

        assertEquals("p1", st.activeProfileId)
        assertEquals("아버지", p.consent.familyName)
        assertEquals(4, p.progress.currentStage)
        assertTrue(p.progress.graduatedAt != null)
        assertEquals(listOf(1, 2, 3, 4).map { true }, (1..4).map { p.progress.stages[it]?.passedAt != null })
        assertEquals(2, p.sessionCount[5])
        assertEquals(true, p.parking.done["parallel-R"])
        assertEquals(listOf("ex-commute", "ex-rain"), p.myPresets.map { it.id })
        assertEquals(Delivery.AFTER, p.myPresets.first().items["B1"])
        assertTrue(p.myCourses.any { it.courseRef == "c2-A" && it.source == CourseSource.SAVED })
        // 세워 두고 연습 · 실제 주차 · 언덕 · 자율 점수
        val virtual = p.sessions.filter { it.isVirtualParking }
        assertEquals(2, virtual.size)
        assertEquals(null, virtual.first().noInMode)
        assertEquals(ParkingType.BACK, virtual.first().parking?.type)
        val park = p.sessions.first { it.mode == 6 && !it.isVirtualParking }.parking
        assertEquals(ParkingHandbrake(result = EventResult.FAIL, on = false), park?.handbrake)
        val hill = p.sessions.first { it.mode == 7 }.hill
        assertEquals(HillTaskCount(success = 1, total = 2), hill?.tasks?.get("T-HILLUP"))
        assertEquals(HandbrakeState.ON, hill?.park?.handbrake)
        val autos = p.sessions.filter { it.mode == 5 }
        assertEquals(2, autos.size)
        val sc = autos.first().score
        assertTrue(sc != null && sc.total in 0..100 && sc.rows.size == 7)
        assertEquals(10, sc?.rows?.first { it.id == "G1" }?.max)
        assertEquals(1, autos.first().f2.size)
        assertEquals(F2Choice.AVOID, autos.first().f2.first().choice)
    }

    @Test
    fun licenseDemoStateReadsCourseProgress() {
        val p = BaroJson.decodeFromString(AppState.serializer(), JsonFixtures.text("proto-appstate-license-2of4.json")).activeProfile

        assertEquals(listOf("A", "B"), p.license.coursesDone)
        assertEquals("2026-04-01", p.license.issueDate)
        val lic = p.sessions.first().license
        assertEquals(LicenseResult::class, lic?.let { it::class })
        assertEquals("c8-B", lic?.courseId)
        assertEquals(0, lic?.alone)
        assertEquals(1, lic?.scenes?.lateSignal)
        assertEquals(listOf("A", "B"), lic?.coursesDoneAfter)
    }

    @Test
    fun needsCheckDemoStateHasUncheckedBrakeAndQueuedShare() {
        val p = BaroJson.decodeFromString(AppState.serializer(), JsonFixtures.text("proto-appstate-needs-check.json")).activeProfile

        val last = p.sessions.first()
        assertEquals(1, last.uncheckedF2)
        assertEquals(ShareState(status = ShareStatus.QUEUED, to = "아버지"), last.share)
        assertEquals(listOf(last.id), p.shareQueue)
    }

    @Test
    fun configFromPrototypeBuildConfigLosesNothing() {
        val tree = JsonFixtures.tree("proto-config-s2b.json")

        assertEquals(emptyList<String>(), unexpected(JsonFixtures.roundTripLoss(SessionConfig.serializer(), tree)))
        val cfg = BaroJson.decodeFromJsonElement(SessionConfig.serializer(), tree)
        assertEquals(2, cfg.mode)
        assertEquals(cfg.sessionNoInMode, cfg.noInMode)
        assertEquals(Weather(snow = true, severe = Severe.FOG), cfg.weather)
        assertEquals(false, cfg.caps.passenger)
        assertEquals(DeliveryReason.FIRST2, cfg.itemReasons["C1"])
        assertEquals("초등학교 뒤 골목", cfg.dest)
        assertEquals(1, cfg.history.recentSessionsInMode.size)
        assertEquals(JudgeEvent(item = "L1", data = EventData(spoken = true, delivery = Delivery.NOW)), cfg.preEvents.single())
        assertEquals(Gear.P, cfg.startGear)
    }

    @Test
    fun snapshotsFromPrototypeSimulatorLoseNothing() {
        val tree = JsonFixtures.tree("proto-snapshots-s2b.json")
        val serializer = ListSerializer(VehicleSnapshot.serializer())

        assertEquals(emptyList<String>(), unexpected(JsonFixtures.roundTripLoss(serializer, tree)))
        val snaps = BaroJson.decodeFromJsonElement(serializer, tree)
        val events = snaps.flatMap { it.nav?.events.orEmpty() }
        assertEquals(
            listOf(NavEvent.WaypointArrived(index = 1, segment = 1), NavEvent.Reroute("t5"), NavEvent.WaypointArrived(index = 2, segment = 1), NavEvent.Arrived),
            events,
        )
        val tbt = snaps.flatMap { it.nav?.tbt.orEmpty() }.first()
        assertTrue(tbt.id.isNotEmpty() && tbt.dir != null && tbt.blinker != null)
        assertEquals("avante", snaps.first().model)
        assertEquals(34.0, snaps.first().maxSteer)
    }

    @Test
    fun stepOutputsFromPrototypeEngineLoseNothing() {
        val tree = JsonFixtures.tree("proto-outputs-s2b.json")
        val serializer = ListSerializer(StepOutput.serializer())

        assertEquals(emptyList<String>(), unexpected(JsonFixtures.roundTripLoss(serializer, tree)))
        val outs = BaroJson.decodeFromJsonElement(serializer, tree)
        val first = outs.first()
        assertEquals(Utterance(key = "a1", text = "출발 전에 벨트부터 매자.", category = Category.INFO, item = "A1", t = 0.0, dur = 2.5, coach = Coach.DAD), first.say.single())
        assertTrue(outs.any { it.blocked.isNotEmpty() })
        assertTrue(outs.any { it.cards.isNotEmpty() && it.cards.first().id != null })
        assertTrue(outs.any { it.turn != null })
    }
}
