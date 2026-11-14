package com.barodrive.core.sim.golden

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 골든 파일이 다 있고 읽히는지(시뮬레이터 쪽). 시뮬레이터 포트와 맞춰 보는 시험이 아니라 파일·읽기 도우미를 확인한다 —
 * 실제 비교는 시뮬레이터를 옮기는 쪽이 [GoldenFixtures.load]로 쓴다(README 9장).
 */
class GoldenFixturesSmokeTest {
    private val snapshotFields = listOf(
        "t", "gear", "speed", "brake", "steer", "blinker", "beltDriver", "passenger", "handbrake", "wiper",
        "lowBeam", "night", "outsideTemp", "tripDist", "nav",
    )
    private val navFields = listOf("status", "tbt", "limit", "cautions", "inSchoolZone", "congested", "voice", "heading", "events")

    @Test
    fun manifestCoversEveryMode() {
        assertEquals(GoldenFixtures.MANIFEST_FORMAT, GoldenFixtures.manifest.string("format"))
        assertEquals(GoldenFixtures.FORMAT, GoldenFixtures.manifest.string("fileFormat"))
        val runs = GoldenFixtures.runs
        assertEquals("id가 겹침", runs.size, runs.map { it.id }.toSet().size)
        for (mode in 1..8) assertTrue("모드 $mode 보통", runs.any { it.mode == mode && it.skill == "normal" })
        assertTrue("시드 확인 값", GoldenFixtures.manifest.array("rngCheck").isNotEmpty())
    }

    @Test
    fun everyFixtureLoadsAndHasOneSnapshotPerTick() {
        for (run in GoldenFixtures.runs) {
            val g = GoldenFixtures.load(run.id)
            val id = run.id
            assertEquals(id, GoldenFixtures.FORMAT, g.json.string("format"))
            assertEquals(id, run.id, g.id)
            assertEquals(id, run.mode, g.mode)
            assertEquals(id, run.scenario, g.scenario)
            assertEquals(id, run.skill, g.skill)
            assertEquals(id, "driver:${run.scenario}:${run.skill}", g.driverSeed)
            assertEquals(id, run.injections, g.injections.map { it.id })

            // 처음 상태: 불러온 직후 스냅숏(t 0)과 차 상태
            assertEquals(id, 0.0, g.initial.obj("snapshot").double("t"), 0.0)
            assertTrue(id, "s" in g.initial.obj("vehicle") && "model" in g.initial.obj("vehicle"))
            assertEquals(id, run.scenario, g.initial.obj("info").string("id"))

            // 틱마다 하나: 세션 전에는 t = 틱/10, 세션 시작 틱에 0, 그 뒤 0.1초씩
            assertEquals(id, run.ticks, g.ticks)
            assertEquals(id, g.ticks, g.snapshots.size)
            assertEquals(id, g.ticks, g.finishTick)
            assertTrue(id, g.sessionStartTick in 1..g.ticks)
            assertEquals("$id 세션 부분 = 엔진 step 수", run.steps, g.sessionSnapshots.size)
            for (tick in 1..g.ticks) {
                val s = g.snapshotAt(tick)
                for (f in snapshotFields) assertTrue("$id 틱 $tick $f", f in s)
                val nav = s["nav"] as? JsonObject
                if (nav != null) for (f in navFields) assertTrue("$id 틱 $tick nav.$f", f in nav)
                val expectT = when {
                    tick < g.sessionStartTick -> tick / 10.0
                    tick == g.sessionStartTick -> 0.0
                    else -> GoldenStreams.round1(g.snapshotAt(tick - 1).double("t") + 0.1)
                }
                assertEquals("$id 틱 $tick t", expectT, s.double("t"), 1e-9)
            }
            assertTrue("$id 세션은 기어가 P를 벗어난 틱에", g.snapshotAt(g.sessionStartTick).string("gear") != "P")

            // 못 읽는 신호는 null
            for (k in run.capsOff.filter { it in listOf("speed", "steer", "handbrake") }) {
                assertTrue("$id 못 읽는 $k = null", g.snapshots.all { it.stringOrNull(k) == null })
            }
            // 주입은 세션 안 틱에
            for (inj in g.injections) assertTrue("$id ${inj.id}", inj.beforeTick in g.sessionStartTick..g.ticks)
        }
    }
}
