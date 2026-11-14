package com.barodrive.core.engine.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 골든 파일이 다 있고 읽히는지(엔진 쪽). 엔진 포트와 맞춰 보는 시험이 아니라 파일·읽기 도우미를 확인한다 —
 * 실제 비교는 엔진을 옮기는 쪽이 [GoldenFixtures.load]로 쓴다(README 9장).
 */
class GoldenFixturesSmokeTest {
    private val snapshotFields = listOf(
        "t", "gear", "speed", "brake", "steer", "blinker", "beltDriver", "passenger", "handbrake", "wiper",
        "lowBeam", "night", "outsideTemp", "tripDist", "nav",
    )
    private val navFields = listOf("status", "tbt", "limit", "cautions", "inSchoolZone", "congested", "voice", "heading", "events")

    @Test
    fun manifestCoversEveryModeAndTheAgreedRuns() {
        assertEquals(GoldenFixtures.MANIFEST_FORMAT, GoldenFixtures.manifest.string("format"))
        assertEquals(GoldenFixtures.FORMAT, GoldenFixtures.manifest.string("fileFormat"))
        val runs = GoldenFixtures.runs
        assertEquals("id가 겹침", runs.size, runs.map { it.id }.toSet().size)
        for (mode in 1..8) assertTrue("모드 $mode 보통", runs.any { it.mode == mode && it.skill == "normal" })
        for (mode in 1..2) for (skill in listOf("poor", "good")) assertTrue("모드 $mode $skill", runs.any { it.mode == mode && it.skill == skill })
        assertTrue("비", runs.any { it.weather == "rain" })
        assertTrue("눈·아주 나쁜 날", runs.any { it.weather == "snow" } && runs.any { it.severe != null })
        assertTrue("못 읽는 신호", runs.any { it.capsOff.isNotEmpty() })
        assertTrue("자율 급제동", runs.any { it.mode == 5 && "hazardBrake" in it.injections })
    }

    @Test
    fun everyFixtureLoadsAndDecodesConsistently() {
        for (run in GoldenFixtures.runs) {
            val g = GoldenFixtures.load(run.id)
            val id = run.id
            assertEquals(id, GoldenFixtures.FORMAT, g.json.string("format"))
            assertEquals(id, run.id, g.id)
            assertEquals(id, run.mode, g.mode)
            assertEquals(id, run.scenario, g.scenario)
            assertEquals(id, run.mode, g.cfg.int("mode"))
            assertEquals(id, run.mode, g.record.int("mode"))
            assertEquals(id, g.cfg.string("seed"), g.json.obj("seeds").string("engine"))

            // 스냅숏: step 수만큼, 설계 6.1.2 필드가 다 있고(못 읽는 신호는 null), 0부터 0.1초씩
            assertEquals(id, run.steps, g.steps)
            assertEquals(id, run.steps, g.snapshots.size)
            g.snapshots.forEachIndexed { i, s ->
                for (f in snapshotFields) assertTrue("$id #$i $f", f in s)
                val nav = s["nav"] as? JsonObject
                if (nav != null) for (f in navFields) assertTrue("$id #$i nav.$f", f in nav)
                val expectT = if (i == 0) 0.0 else GoldenStreams.round1(g.snapshots[i - 1].double("t") + 0.1)
                assertEquals("$id #$i t", expectT, s.double("t"), 1e-9)
            }
            for (k in run.capsOff.filter { it in listOf("speed", "steer", "handbrake") }) {
                assertTrue("$id 못 읽는 $k = null", g.snapshots.all { it.stringOrNull(k) == null })
            }

            // 출력: step마다 하나, 시각 = 스냅숏 시각
            assertEquals(id, run.steps, g.outputs.size)
            g.outputs.forEachIndexed { i, o -> assertEquals("$id out #$i t", g.snapshots[i].double("t"), o.double("t"), 1e-9) }

            // 부른 순서: step + input, finish는 마지막 step 뒤
            assertEquals(id, run.inputs, g.inputs.size)
            assertEquals(id, run.steps + run.inputs, g.calls.size)
            assertEquals(id, run.steps, g.json.obj("finish").int("afterSteps"))

            // 출력에서 모은 말 = 기록의 말한 것(같은 순서) · 막힌 말 = 기록의 막힌 것 앞부분
            val utterances = g.record.array("utterances").map { it.jsonObject }
            val spoken = utterances.filter { it.bool("spoken") }
            val unspoken = utterances.filter { !it.bool("spoken") }
            assertEquals(id, spoken.size, g.said.size)
            g.said.forEachIndexed { i, u ->
                assertEquals("$id said #$i", spoken[i].string("key"), u.string("key"))
                assertEquals("$id said #$i", spoken[i].string("text"), u.string("text"))
                assertEquals("$id said #$i", spoken[i].double("t"), u.double("t"), GoldenCompare.TIME_TOLERANCE)
            }
            assertTrue(id, g.blocked.size <= unspoken.size)
            g.blocked.forEachIndexed { i, b ->
                assertNull("$id blocked #$i", GoldenCompare.firstDiff(unspoken[i]["reason"], b["reason"]))
                assertEquals("$id blocked #$i", unspoken[i].string("key"), b.string("key"))
            }
            // 판정: 출력에서 모은 것(F2 뺌) = 기록 events(시작 전 pre·F2 뺌)의 앞부분
            val judged = g.calls.flatMap { c -> c.output.array("events").map { it.jsonObject } }.filter { it.stringOrNull("item") != "F2" }
            val recorded = g.record.array("events").map { it.jsonObject }
                .filter { it.stringOrNull("item") != "F2" && it["pre"]?.toString() != "true" }
            assertTrue(id, judged.size <= recorded.size)
            judged.forEachIndexed { i, e ->
                assertEquals("$id event #$i", recorded[i].stringOrNull("item"), e.stringOrNull("item"))
                assertEquals("$id event #$i", recorded[i].double("t"), e.double("t"), GoldenCompare.TIME_TOLERANCE)
            }

            // 리뷰·점수·도움 줄여 가기
            assertNotNull(id, g.review["tasks"])
            assertEquals(id, run.mode, g.fading.int("mode"))
            if (run.mode == 5) {
                assertNotNull("$id 점수", g.score)
                assertNull("$id 점수 = 기록의 점수", GoldenCompare.firstDiff(g.record["score"], g.score))
            } else {
                assertNull("$id 점수는 자율만", g.score)
            }
        }
    }

    @Test
    fun streamDecodingFollowsTheReadmeRules() {
        val encoded = Json.parseToJsonElement(
            """[{"t":0.8,"a":1,"nav":{"x":1,"y":[1]}},{},{"a":2,"nav":{"y":[2]}},{"t":5,"${'$'}del":["a"],"nav":null},{"nav":{"x":3}}]""",
        ).jsonArray
        val s = GoldenStreams.decodeSnapshots(encoded)
        assertEquals(listOf(0.8, 0.9, 1.0, 5.0, 5.1), s.map { it.double("t") })
        assertEquals("셈한 t도 JS처럼(정수면 소수점 없이)", "1", s[2]["t"].toString())
        assertEquals(1, s[1].int("a"))
        assertEquals(2, s[2].int("a"))
        assertEquals("nav는 한 단계 더 합침", """{"x":1,"y":[2]}""", s[2].obj("nav").toString())
        assertTrue("\$del로 지움", "a" !in s[3])
        assertTrue("nav null", s[3]["nav"] is JsonNull)
        assertEquals("앞 nav가 null이면 통째로", """{"x":3}""", s[4].obj("nav").toString())

        val outputs = GoldenStreams.decodeOutputs(
            Json.parseToJsonElement("""[{"i":0,"say":[1],"cards":[],"turn":null},{"i":2,"blocked":[2],"turn":{"id":"t1"}}]""").jsonArray,
            s.take(3),
        )
        assertEquals("""{"t":0.8,"say":[1],"blocked":[],"cards":[],"events":[],"turn":null}""", outputs[0].toString())
        assertEquals("목록은 그 걸음 것만, 상태는 이어 씀", """{"t":0.9,"say":[],"blocked":[],"cards":[],"events":[],"turn":null}""", outputs[1].toString())
        assertEquals("""{"t":1,"say":[],"blocked":[2],"cards":[],"events":[],"turn":{"id":"t1"}}""", outputs[2].toString())
    }
}
