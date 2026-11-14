package com.barodrive.core.sim.golden

import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.BaroSim
import com.barodrive.core.sim.ScenarioInfo
import com.barodrive.core.sim.ScenarioRun
import com.barodrive.core.sim.ScenarioRunner
import com.barodrive.core.sim.SimTarget
import com.barodrive.core.sim.driver.skillOf
import com.barodrive.core.sim.scenario.ParkingChoice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 시뮬레이터 포트 = 프로토타입(골든 파일, prototype/tools/golden/README.md 8·9.2장).
 * 회차마다 같은 시나리오·실력·못 읽는 신호·주차 고르기·주입으로 돌려 틱마다 스냅숏(설계 6.1.2 필드 전부)이 같은지(수는 1e-6),
 * 불러온 직후 스냅숏·세션 시작 틱·내비 음성·재탐색·경유지·장면 틱·주입 틱·끝 상태가 같은지 본다.
 */
class SimGoldenTest {
    @Test
    fun s1bNormal() = assertGolden("m1-S1-B-normal")

    @Test
    fun s2bNormal() = assertGolden("m2-S2-B-normal")

    @Test
    fun everyFixtureMatchesThePrototype() {
        val failures = GoldenFixtures.runs.mapNotNull { r -> runCatching { assertGolden(r.id) }.exceptionOrNull()?.let { "${r.id}: ${it.message}" } }
        if (failures.isNotEmpty()) fail("골든과 다른 회차 ${failures.size}/${GoldenFixtures.runs.size}:\n" + failures.joinToString("\n"))
    }

    private fun assertGolden(id: String) {
        val g = GoldenFixtures.load(id)
        val caps = capsOf(g.caps)
        val parking = g.parking?.let { ParkingChoice.norm(it.string("type"), it.string("side")) }
        val skill = skillOf(g.skill)

        // 불러온 직후(재생 전) 스냅숏
        val fresh = BaroSim().apply {
            reset()
            setCaps(caps)
            parking?.let { chooseParking(it.type, it.side) }
        }
        val info = fresh.load(SimTarget.Scenario(g.scenario), skill)
        GoldenCompare.firstDiff(g.initial.obj("snapshot"), json(fresh.snapshot()))?.let { fail("$id 처음 스냅숏 $it") }
        compareInitial(id, g.initial, info, fresh)

        val run = ScenarioRunner.run(
            SimTarget.Scenario(g.scenario),
            ScenarioRunner.Options(
                skill = skill, caps = caps, parking = parking,
                injections = g.json.array("injections").map { ScenarioRunner.Injection(it.jsonObject.double("t"), it.jsonObject.string("id")) },
                until = ScenarioRunner.Until.Ticks(g.ticks),
            ),
        )
        assertTrue("$id 오류 ${run.errors}", run.errors.isEmpty())
        assertEquals("$id 틱 수", g.ticks, run.ticks)
        for (tick in 1..g.ticks) {
            GoldenCompare.firstDiff(g.snapshotAt(tick), json(run.snapshotAt(tick)))?.let { fail("$id 틱 $tick: $it") }
        }
        assertEquals("$id 세션 시작 틱", g.sessionStartTick, run.sessionStartTick)
        assertEquals("$id 주입", g.injections.map { it.id to it.beforeTick }, run.injected.map { it.id to it.beforeTick })
        assertEquals("$id 주입 글", g.injections.map { it.result }, run.injected.map { it.result })
        compareNav(id, g, run)
        val sceneTicks = g.scenarioEvents.map { listOf(it.int("tick"), it.string("state"), it.stringOrNull("mark")) }
        assertEquals("$id 장면", sceneTicks, run.scenarioEvents.map { listOf(it.tick, it.event.state.code, it.event.mark) })
        assertEquals("$id 끝 재생기", g.end.string("playerState"), run.state.code)
        assertEquals("$id 끝 시나리오 시각", g.end.double("playerT"), run.playerT, 1e-9)
        assertEquals("$id 끝 sim 시계", g.end.double("simT"), run.simT, 1e-9)
    }

    /** 불러온 직후 재생기 정보 · 내비 상태(DR-1 값) · 운전자 계획(정지 자리·끝 자리). */
    private fun compareInitial(id: String, initial: JsonObject, info: ScenarioInfo, sim: BaroSim) {
        val gi = initial.obj("info")
        val mine = buildJsonObject {
            put("id", info.id); put("title", info.title); put("mode", info.mode); put("courseId", info.courseId); put("placeId", info.placeId)
            put("skill", info.skill?.name?.lowercase()); put("state", info.state.code); put("t", info.t); put("kind", info.kind)
            put("generated", info.generated); put("routeKey", info.routeKey); put("marks", JsonArray(info.marks.map { JsonPrimitive(it) }))
            put("mark", info.mark); put("lengthM", info.lengthM)
        }
        GoldenCompare.firstDiff(gi, mine)?.let { fail("$id 처음 정보 $it") }
        val n = sim.navState()
        val gn = initial.obj("nav")
        val nav = buildJsonObject {
            put("guiding", n.guiding); put("arrived", n.arrived); put("searching", n.searching); put("remainM", n.remainM)
            put("remainSec", n.remainSec); put("totalRemainM", n.totalRemainM); put("destName", n.destName); put("routeOption", n.routeOption)
            put("courseId", n.courseId); put("placeId", n.placeId); put("key", n.key); put("voiceText", n.voiceText)
            put("limitSource", n.limitSource); put("loop", n.loop)
            put("waypoints", JsonArray(n.waypoints.map { w -> buildJsonObject { put("index", w.index); put("name", w.name); put("remainM", w.remainM); put("passed", w.passed) } }))
        }
        GoldenCompare.firstDiff(JsonObject(gn.filterKeys { it in nav.keys }), nav)?.let { fail("$id 처음 내비 상태 $it") }
        val gd = initial.obj("driver")
        val plan = sim.driver.plan
        assertEquals("$id 운전자 순항", gd.obj("plan").doubleOrNull("cruise"), plan.cruise)
        assertEquals("$id 운전자 흔들림", gd.obj("plan").doubleOrNull("wobble"), plan.wobble)
        assertEquals("$id 운전 방식", gd.obj("plan").string("drive"), plan.drive.name.lowercase())
        assertEquals("$id 회전 계획 번호", gd.obj("plan").obj("turns").keys.map { it.toInt() }.sorted(), plan.turns.keys.sorted())
        val di = sim.driver.info()
        assertEquals("$id 정지 자리", gd.obj("info").array("pendingStops").map { it.jsonPrimitive.content.toLong() }, di.pendingStops)
        assertEquals("$id 끝 자리", gd.obj("info").doubleOrNull("endStop")?.toLong(), di.endStop)
    }

    private fun compareNav(id: String, g: SimGolden, run: ScenarioRun) {
        val voices = g.nav.array("voices").map { v -> v.jsonObject.let { listOf(it.string("text"), it.double("t"), it.double("dur"), it.stringOrNull("key")) } }
        assertEquals("$id 내비 음성", voices, run.voices.map { listOf(it.text, it.t, it.dur, it.key) })
        assertEquals("$id 재탐색", g.nav.array("reroutes").map { it.jsonObject.string("turnId") }, run.reroutes)
        val wps = g.nav.array("waypoints").map { w -> w.jsonObject.let { listOf(it.int("index"), it.int("segment"), it.string("kind"), it.stringOrNull("name")) } }
        assertEquals("$id 경유지", wps, run.waypoints.map { listOf(it.index, it.segment, it.kind, it.name) })
        assertEquals("$id 도착", g.nav.bool("arrived"), run.navArrived)
    }

    companion object {
        /** 골든 caps(못 읽는 키만 false) → [SignalCaps]. */
        fun capsOf(o: JsonObject?): SignalCaps {
            var c = SignalCaps()
            if (o == null) return c
            for ((k, v) in o) {
                val key = SignalKey.fromKey(k) ?: continue
                if ((v as? JsonPrimitive)?.booleanOrNull == false) c = c.with(key, false)
            }
            return c
        }

        /** 스냅숏 → 프로토타입 JSON 모양(skip이 없으면 빼고). */
        fun json(s: VehicleSnapshot): JsonElement {
            val o = BaroJson.encodeToJsonElement(VehicleSnapshot.serializer(), s).jsonObject
            return if (s.skip) o else JsonObject(o - "skip")
        }
    }
}
