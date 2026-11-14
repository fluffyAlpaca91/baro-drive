package com.barodrive.core.engine.golden

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StepOutput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 골든 비교 — 18회차 모두(단계 연습 모드 1~4·자율 모드 5·날씨·못 읽는 신호·실제 주차 안내 모드 6·언덕 모드 7·운전면허 연습 모드 8)를
 * 엔진 포트로 다시 돌려 걸음마다 출력(말·막힌 말·카드·힌트·판정)과 회차 기록 전체가 프로토타입과 같은지 본다(README 9.1).
 * `-Dgolden.only=<id>`로 회차 하나만 돌릴 수 있다.
 */
class GoldenEngineTest {
    private val runs: List<String> = GoldenFixtures.runs.map { it.id }

    @Test
    fun everyGoldenRunMatchesThePrototype() {
        val only = System.getProperty("golden.only")
        val results = runs.filter { only == null || it == only }.map(GoldenReplay::run)
        val report = results.joinToString("\n") { r ->
            val state = if (r.diff == null && r.errors.isEmpty()) "OK  " else "DIFF"
            "$state ${r.id} (${r.steps} calls) ${r.diff.orEmpty()} ${if (r.errors.isEmpty()) "" else "errors=${r.errors.take(3)}"}"
        }
        println(report)
        assertTrue(report, results.all { it.diff == null && it.errors.isEmpty() })
    }

    @Test
    fun comparisonCatchesDifferences() {
        val g = GoldenFixtures.load("m1-S1-B-normal")
        val withSay = g.calls.first { it.output.array("say").isNotEmpty() }
        assertNotNull("말이 빠지면 다름", GoldenReplay.outputDiff(withSay.output, StepOutput(t = withSay.output.double("t"))))
        val withCard = g.calls.first { it.output.array("cards").isNotEmpty() }
        val emptyCards = JsonObject(withCard.output + ("cards" to JsonArray(emptyList())))
        val actual = BaroJson.decodeFromJsonElement(StepOutput.serializer(), GoldenReplay.normalize(emptyCards))
        assertNotNull("카드가 빠지면 다름", GoldenReplay.outputDiff(withCard.output, actual))
        val rec = BaroJson.decodeFromJsonElement(SessionRecord.serializer(), g.record)
        assertNull("골든 기록을 읽어 다시 쓰면 같음", GoldenReplay.recordDiff(g.record, rec))
        assertNotNull("말 기록이 하나 빠지면 다름", GoldenReplay.recordDiff(g.record, rec.copy(utterances = rec.utterances.drop(1))))
        assertNotNull("판정이 하나 빠지면 다름", GoldenReplay.recordDiff(g.record, rec.copy(events = rec.events.drop(1))))
        assertNotNull("거리가 다르면 다름", GoldenReplay.recordDiff(g.record, rec.copy(distanceM = rec.distanceM + 1)))
    }

    /** buildConfig(모드, 프로필, 준비 세션, 패널 caps)가 골든 cfg와 같다 — 항목 값(처음 2회차·도움 줄여 가기·사용자 변경·자율 설정)까지. */
    @Test
    fun buildConfigReproducesEveryGoldenConfig() {
        val diffs = GoldenFixtures.runs.mapNotNull { run ->
            val g = GoldenFixtures.load(run.id)
            val profile = BaroJson.decodeFromJsonElement(Profile.serializer(), g.profile)
            val prep = BaroJson.decodeFromJsonElement(PrepSession.serializer(), g.buildConfig.obj("prep"))
            val caps = BaroJson.decodeFromJsonElement(SignalCaps.serializer(), g.buildConfig.obj("panelState").obj("caps"))
            val cfg = BaroEngine.buildConfig(g.buildConfig.int("mode"), profile, prep, caps)
            val actual = BaroJson.encodeToJsonElement(SessionConfig.serializer(), cfg)
            val expected = JsonObject(g.cfg - "noInMode")
            GoldenCompare.firstDiff(GoldenReplay.normalize(expected), GoldenReplay.normalize(actual))?.let { "${run.id}: $it" }
        }
        assertTrue(diffs.joinToString("\n"), diffs.isEmpty())
    }
}
