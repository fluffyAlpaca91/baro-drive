package com.barodrive.core.model

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecordTest {
    /** 설계 8.1 SessionRecord 예를 프로토타입 JSON 모양 그대로(손으로 씀). */
    private val example = """
        {
          "id": "sess-20261110-0912", "mode": 2, "stage": 2, "noInMode": 3, "courseId": "c2-B", "presetId": null,
          "startedAt": "2026-11-10T09:12:00+09:00", "simSec": 1080, "driveSec": 1004.2, "distanceM": 3200, "arrived": true,
          "weather": { "type": "rain", "severe": false, "cold": false },
          "caps": { "speed": true, "passenger": false },
          "items": { "A1": "O", "C1": "X", "G1": "S" },
          "events": [
            { "t": 0, "item": "L1", "result": "info", "data": { "spoken": true, "value": "O" }, "pre": true },
            { "t": 35.1, "item": "C2", "result": "ok", "data": { "turnId": "t1", "distance": 50, "type": "TURN_RIGHT", "ic": false, "dir": "R" } },
            { "t": 90.4, "item": "C4", "result": "fail", "data": { "turnId": null, "dir": "L", "interval": 1.2, "blinkOnT": 88.1, "steerStartT": 89.3 } },
            { "t": 120, "item": "C2", "result": "excluded", "data": { "turnId": "t5", "type": "TURN_LEFT", "was": "late", "excluded": "reroute" } }
          ],
          "utterances": [
            { "t": 0, "item": "L1", "key": "l1", "category": "info", "text": "조수석에 계신 분은 위험할 때만 말씀해 주세요.", "spoken": true, "reason": null },
            { "t": 35.1, "item": "C1", "key": "c1", "category": "pre", "text": "오른쪽 깜빡이 미리 켜 볼까?", "spoken": false, "reason": "gap" },
            { "t": 35.9, "item": "C2", "key": "c2.ok", "category": "praise", "text": "좋았어, 50미터 전에 켰어.", "spoken": true, "reason": null },
            { "t": 90.4, "item": "C4", "key": "c4.fix", "category": "fix", "text": "깜빡이 켜고 하나, 둘, 셋 뒤에 꺾어 보자.", "spoken": false, "reason": "S" }
          ],
          "tasks": [ { "id": "T-30", "success": 2, "total": 3 } ],
          "achievement": 0.83, "qualified": true, "fewEvents": false,
          "metrics": { "c2Avg": 52, "stopFailRate": 0.125, "g1": 0.77, "per10min": 4.2, "praise": 6, "correction": 2 },
          "nextTask": { "id": "T-30", "label": "30 구역 지키기", "short": "30 구역" },
          "f2": [ { "id": "f2-1", "v0": 28, "dur": 1.4, "choice": null } ],
          "score": null, "hill": null, "parking": null, "license": null,
          "fadedNow": ["B1"],
          "share": { "status": "none" },
          "notes": { "tasks": { "T-X": { "success": 1, "total": 1 } } },
          "unknownLater": 1
        }
    """.trimIndent()

    @Test
    fun decodesTheDesignExample() {
        val r = BaroJson.decodeFromString(SessionRecord.serializer(), example)

        assertEquals("sess-20261110-0912", r.id)
        assertEquals(3, r.noInMode)
        assertEquals("simSec → durationSec", 1080.0, r.durationSec, 0.0)
        assertEquals(3200.0, r.distanceM, 0.0)
        assertEquals("severe false는 null로", SessionWeather(type = WeatherType.RAIN), r.weather)
        assertEquals(false, r.caps.passenger)
        assertEquals("빠진 caps 칸은 읽힘으로", true, r.caps.gear)
        assertEquals(mapOf("A1" to Delivery.NOW, "C1" to Delivery.OFF, "G1" to Delivery.AFTER), r.items)
        assertEquals(true, r.events.first().pre)
        assertEquals(false, r.events.first().isJudged)
        assertEquals(Delivery.NOW, r.events.first().data.delivery)
        assertEquals("null 값 칸은 없는 칸으로", EventData(dir = TurnDir.LEFT, interval = 1.2, blinkOnT = 88.1, steerStartT = 89.3), r.events[2].data)
        assertEquals(EventResult.LATE, r.events[3].data.was)
        assertEquals("reroute", r.events[3].data.excluded)
        assertEquals(TaskResult(id = "T-30", success = 2, total = 3), r.tasks.single())
        assertEquals(2.0 / 3, r.tasks.single().rate!!, 1e-9)
        assertEquals(52.0, r.metrics.c2Avg)
        assertNull(r.metrics.k4)
        assertEquals(NextTask(id = "T-30", label = "30 구역 지키기", short = "30 구역"), r.nextTask)
        assertEquals(F2Check(id = "f2-1", v0 = 28.0, dur = 1.4), r.f2.single())
        assertEquals(1, r.uncheckedF2)
        assertEquals(listOf("B1"), r.fadedNow)
        assertTrue(r.notes?.containsKey("tasks") == true)
        assertNull("없으면 events에서 센다", r.itemStats)
    }

    @Test
    fun splitsUtterancesIntoSpokenAndBlocked() {
        val r = BaroJson.decodeFromString(SessionRecord.serializer(), example)

        assertEquals(listOf("l1", "c2.ok"), r.spoken.map { it.key })
        assertEquals(listOf(BlockReason.GAP, BlockReason.AFTER), r.blocked.map { it.reason })
        assertEquals(Category.PRE, r.blocked.first().utterance.category)
        assertEquals(35.1, r.blocked.first().utterance.t, 0.0)
    }

    @Test
    fun roundTripsAndKeepsPrototypeNames() {
        val r = BaroJson.decodeFromString(SessionRecord.serializer(), example)

        val text = BaroJson.encodeToString(SessionRecord.serializer(), r)
        val obj = BaroJson.parseToJsonElement(text).jsonObject

        assertEquals(r, BaroJson.decodeFromString(SessionRecord.serializer(), text))
        assertEquals("1080", obj.getValue("simSec").jsonPrimitive.content.removeSuffix(".0"))
        assertTrue("durationSec 이름은 JSON에 없음", "durationSec" !in obj)
        assertEquals("O", obj.getValue("items").jsonObject.getValue("A1").jsonPrimitive.content)
        assertEquals("gap", obj.getValue("utterances").jsonArray[1].jsonObject.getValue("reason").jsonPrimitive.content)
        assertEquals("C2", obj.getValue("events").jsonArray[1].jsonObject.getValue("item").jsonPrimitive.content)
    }

    @Test
    fun decodesVirtualParkingRecord() {
        val text = """
            { "kind": "virtualParking", "id": "sess-20261011-1105", "mode": 6, "stage": null, "noInMode": null, "courseId": null,
              "presetId": null, "startedAt": "2026-10-11T11:05:00+09:00", "simSec": 96, "distanceM": 0, "arrived": false,
              "weather": null, "caps": {}, "items": {}, "events": [], "utterances": [], "tasks": [], "achievement": null,
              "qualified": false, "fewEvents": true, "metrics": {}, "nextTask": null, "f2": [], "score": null, "hill": null,
              "license": null, "tips": [], "fadedNow": [], "share": { "status": "none" },
              "parking": { "virtual": true, "type": "parallel", "side": "R", "key": "parallel-R", "model": "avante", "result": "success",
                "sec": 96, "switches": 3, "wheelAngle": 2.1, "wheelStraight": true, "touches": 0, "restarts": 1 } }
        """.trimIndent()

        val r = BaroJson.decodeFromString(SessionRecord.serializer(), text)

        assertTrue(r.isVirtualParking)
        assertEquals(RecordKind.VIRTUAL_PARKING, r.kind)
        assertNull(r.noInMode)
        assertNull(r.weather)
        assertEquals(SignalCaps(), r.caps)
        assertEquals(SessionMetrics(), r.metrics)
        assertEquals(ParkingResult(virtual = true, type = ParkingType.PARALLEL, side = TurnDir.RIGHT, key = "parallel-R", model = "avante",
            result = "success", sec = 96.0, switches = 3, wheelAngle = 2.1, wheelStraight = true, touches = 0, restarts = 1), r.parking)
    }

    @Test
    fun decodesOlderRecordWithoutOptionalFields() {
        val text = """{"mode":1,"simSec":12.5,"arrived":false,"unknownLater":1}"""

        val decoded = BaroJson.decodeFromString(SessionRecord.serializer(), text)

        assertEquals(SessionRecord(mode = 1, durationSec = 12.5, arrived = false), decoded)
    }

    @Test
    fun withReviewMergesSummaryAndKeepsTheRest() {
        val r = SessionRecord(mode = 2, metrics = SessionMetrics(c2Avg = 45.0, per10min = 4.2), score = ScoreResult(total = 86))
        val s = ReviewSummary(
            tasks = listOf(TaskResult("T-30", "시속 30 넘지 않기", "30 구역", 2, 2)), achievement = 1.0, fewEvents = false, qualified = false,
            metrics = SessionMetrics(k4 = K4Compare(first = true)), good = listOf(ReviewLine("T-30", "30 구역 다 지켰어요")),
            itemStats = mapOf("E1" to ItemStat(2, 2)), fadedNow = listOf("B1"), fadedNote = "출발은 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.",
        )

        val merged = r.withReview(s)

        assertEquals(s.tasks, merged.tasks)
        assertEquals(SessionMetrics(c2Avg = 45.0, per10min = 4.2, k4 = K4Compare(first = true)), merged.metrics)
        assertEquals(86, merged.score?.total)
        assertEquals(mapOf("E1" to ItemStat(2, 2)), merged.itemStats)
        assertEquals(s.fadedNote, merged.fadedNote)
    }
}
