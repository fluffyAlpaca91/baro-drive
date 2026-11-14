package com.barodrive.core.engine.review

import com.barodrive.core.engine.api.EngineFading
import com.barodrive.core.engine.api.EngineLicense
import com.barodrive.core.engine.api.EngineReview
import com.barodrive.core.engine.api.EngineScore
import com.barodrive.core.engine.golden.EngineGolden
import com.barodrive.core.engine.golden.GoldenCompare
import com.barodrive.core.engine.golden.GoldenFixtures
import com.barodrive.core.engine.score.ScoreText
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.FadingUpdate
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ReviewSummary
import com.barodrive.core.model.ScoreResult
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionRecord
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 골든 정답지와 맞춰 보기 (prototype/tools/golden/README.md 7.5·9.1): 회차마다 프로토타입이 끝낸 기록(`record`)과 프로필을
 * 그대로 넣고 `review.compute`·`score.compute`·`fading.update` 결과가 같은지 본다(엔진 세션 없이). 엔진 세션이 회차 끝에 합친
 * 요약(`summarize(rec, cfg)`)과 모드 5 점수(`score.compute(rec, cfg)`)도 기록의 값과 같아야 한다.
 */
class ReviewGoldenTest {
    private class Run(val g: EngineGolden) {
        val id: String get() = g.id
        val profile: Profile = BaroJson.decodeFromJsonElement(Profile.serializer(), g.profile)
        val record: SessionRecord = BaroJson.decodeFromJsonElement(SessionRecord.serializer(), g.record)
        val cfg: SessionConfig = BaroJson.decodeFromJsonElement(SessionConfig.serializer(), g.cfg)
    }

    private companion object {
        val runs: List<Run> by lazy { GoldenFixtures.runs.map { Run(GoldenFixtures.load(it.id)) } }

        /** 기록에 합쳐진 리뷰 요약 칸(review.js `summarize`가 돌려주는 키). */
        val SUMMARY_KEYS = listOf("tasks", "achievement", "fewEvents", "qualified", "nextTask", "good", "bad", "itemStats", "fadedNow", "fadedNote")
    }

    private fun json(s: ReviewSummary): JsonElement = BaroJson.encodeToJsonElement(ReviewSummary.serializer(), s)

    private fun json(s: ScoreResult): JsonElement = BaroJson.encodeToJsonElement(ScoreResult.serializer(), s)

    private fun json(s: FadingUpdate): JsonElement = BaroJson.encodeToJsonElement(FadingUpdate.serializer(), s)

    private fun assertSame(what: String, expected: JsonElement?, actual: JsonElement?) {
        val diff = GoldenCompare.firstDiff(expected, actual)
        assertNull("$what: $diff", diff)
    }

    @Test
    fun everyFixtureIsChecked() {
        assertEquals(GoldenFixtures.runs.size, runs.size)
        assertTrue("골든 회차 18개", runs.size >= 18)
        // 비교가 빈 비교가 아님: 일부러 바꾼 값은 다르다고 나와야 한다
        val r = runs.first { it.id == "m1-S1-B-normal" }
        val s = EngineReview.compute(r.record, r.profile)
        assertNotNull(GoldenCompare.firstDiff(r.g.review, json(s.copy(achievement = 0.5))))
        assertNotNull(GoldenCompare.firstDiff(r.g.review, json(s.copy(bad = s.bad.drop(1)))))
        assertNotNull(GoldenCompare.firstDiff(r.g.fading, json(EngineFading.update(r.profile, r.record).copy(fadedNow = listOf("B1")))))
        val sc = runs.first { it.id == "m5-S5-M-normal" }
        assertNotNull(GoldenCompare.firstDiff(sc.g.score, json(EngineScore.compute(sc.record, sc.profile).copy(top = "G1"))))
    }

    @Test
    fun reviewComputeMatchesThePrototype() {
        for (r in runs) assertSame("${r.id} review.compute", r.g.review, json(EngineReview.compute(r.record, r.profile)))
    }

    @Test
    fun summarizeWithTheSessionConfigMatchesTheRecord() {
        for (r in runs) {
            val s = json(EngineReview.summarize(r.record, r.cfg)) as JsonObject
            for (k in SUMMARY_KEYS) assertSame("${r.id} summarize.$k", r.g.record[k], s[k])
            assertSame("${r.id} summarize.metrics.k4", (r.g.record["metrics"] as JsonObject)["k4"], (s["metrics"] as JsonObject)["k4"])
        }
    }

    @Test
    fun scoreMatchesForAutonomousRuns() {
        val autonomous = runs.filter { it.g.mode == 5 }
        assertTrue("자율 회차가 골든에 있음", autonomous.size >= 3)
        for (r in autonomous) {
            val expected = r.g.score
            assertNotNull(r.id, expected)
            assertSame("${r.id} score.compute(rec, profile)", expected, json(EngineScore.compute(r.record, r.profile)))
            assertSame("${r.id} score.compute(rec, cfg) = 기록의 점수", r.g.record["score"], json(EngineScore.compute(r.record, r.cfg)))
        }
    }

    @Test
    fun fadingUpdateMatchesThePrototype() {
        for (r in runs) assertSame("${r.id} fading.update", r.g.fading, json(EngineFading.update(r.profile, r.record)))
    }

    @Test
    fun decodedRecordKeepsTheSummaryFields() {
        // 기록 → 모델 → JSON이 리뷰·점수 칸을 잃지 않는지(위 비교가 디코딩 차이로 우연히 맞지 않게)
        for (r in runs) {
            val back = BaroJson.encodeToJsonElement(SessionRecord.serializer(), r.record) as JsonObject
            for (k in SUMMARY_KEYS + "score") assertSame("${r.id} record.$k 왕복", r.g.record[k], back[k])
        }
    }

    @Test
    fun scoreTextsForTheMockupRun() {
        val r = runs.first { it.id == "m5-S5-M-normal" }
        val sc = EngineScore.compute(r.record, r.profile)
        assertEquals(86, sc.total)
        assertEquals(
            listOf(
                listOf("회전 전 깜빡이 '늦음'", "1번", "−3"), listOf("회전 전 깜빡이 '거의'", "2번", "−2"), listOf("깜빡이 켜자마자 꺾음", "1번", "−2"),
                listOf("급출발·급정지", "1번", "−3"), listOf("직진할 때 핸들 흔들림", "기준보다 큼", "−4"), listOf("제한속도 초과 · 깜빡이 없이 회전", "0번", "0"),
            ),
            EngineScore.table(sc).map { listOf(it.label, it.count, it.points) },
        )
        val l = EngineScore.line(sc, r.cfg.dest)
        assertEquals("마트까지 잘 왔어. 깜빡이만 조금 일찍 켜 보자.", com.barodrive.core.engine.content.Lines.render(l.key, r.cfg.coach, ScoreText.vars(l), peek = true))
    }

    @Test
    fun licenseRunScenesMatchTheDetectorNote() {
        val r = runs.first { it.g.mode == 8 }
        val lic = requireNotNull(r.record.license)
        assertEquals("검출기 메모의 장면 수 = 기록 판정으로 센 수", lic.scenes, LicenseCalc.countScenes(r.record.events, r.record.items))
        val c = EngineLicense.courseResult(r.record)
        assertEquals(listOf<Any?>(lic.complete, lic.reached, lic.segmentsDone, lic.letter, lic.segments), listOf(c.complete, c.reached, c.segmentsDone, c.letter, c.count))
        assertEquals(listOf("C"), LicenseCalc.coursesAfter(emptyList(), r.record))
        val rv = EngineLicense.review(r.record, r.profile)
        assertEquals("시험장 주변 C 코스를 끝까지 달렸어요", rv.course.text)
        assertEquals("4개 코스 중 1개", rv.progress.text)
        assertEquals(lic.alone, rv.alone)
    }
}
