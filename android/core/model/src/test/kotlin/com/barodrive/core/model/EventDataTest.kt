package com.barodrive.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDataTest {
    @Test
    fun builtAndDecodedDataAreEqualWhateverTheNumberForm() {
        val built = EventData(turnId = "t3", distance = 45.0, type = "TURN_LEFT", ic = false, dir = TurnDir.LEFT)
        val decoded = BaroJson.decodeFromString(
            EventData.serializer(),
            """{"turnId":"t3","distance":45,"type":"TURN_LEFT","ic":false,"dir":"L","extraNull":null}""",
        )

        assertEquals(built, decoded)
        assertEquals(built.hashCode(), decoded.hashCode())
        assertEquals("""{"turnId":"t3","distance":45,"type":"TURN_LEFT","ic":false,"dir":"L"}""", BaroJson.encodeToString(EventData.serializer(), built))
    }

    @Test
    fun typedGettersReadEachItemShape() {
        val f1 = EventData(v0 = 28.0, v1 = 0.0, dur = 2.6, maxDrop1s = 8.2, stopped = true, threshold = 7.5)
        val e1 = BaroJson.decodeFromString(EventData.serializer(), """{"zoneId":"z2","limit":30,"effLimit":24,"school":false,"peak":31.4,"overSec":3.1}""")
        val g1 = EventData(value = 0.94, windows = 30, base = 0.8, baseFirst = false, better = -0.17, insufficient = false)
        val hl3 = EventData(situation = HillSlope.DOWN, need = TurnDir.RIGHT, steer = 14.0, side = TurnDir.RIGHT)
        val j3 = EventData(kind = "rain", trigger = "wiper", wiperSec = 30.1, severe = Severe.ICE)

        assertEquals(8.2, f1.maxDrop1s)
        assertEquals(true, f1.stopped)
        assertEquals("z2", e1.zoneId)
        assertEquals(30.0, e1.limit)
        assertEquals(31.4, e1.peak)
        assertEquals(30, g1.windows)
        assertEquals(-0.17, g1.better)
        assertEquals(HillSlope.DOWN, hl3.situation)
        assertEquals(TurnDir.RIGHT, hl3.need)
        assertEquals("wiper", j3.trigger)
        assertEquals("wiper", j3.string("when"))
        assertEquals(Severe.ICE, j3.severe)
    }

    @Test
    fun wrongTypesAndMissingKeysReadAsNullWithoutThrowing() {
        val d = BaroJson.decodeFromString(EventData.serializer(), """{"distance":"far","value":"O","n":2.5,"stopped":"yes"}""")

        assertNull(d.distance)
        assertNull(d.turnId)
        assertNull("G1 값 자리에 글자", d.value)
        assertEquals("출발 전 사건의 value 글자", Delivery.NOW, d.delivery)
        assertNull("소수는 정수로 안 읽음", d.n)
        assertNull(d.stopped)
        assertTrue("distance" in d)
        assertFalse("turnId" in d)
    }

    @Test
    fun withAndPlusKeepCanonicalForm() {
        val base = EventData(turnId = "t5", type = "TURN_LEFT")

        val excluded = base + EventData(was = EventResult.LATE, excluded = "reroute")

        assertEquals("t5", excluded.turnId)
        assertEquals(EventResult.LATE, excluded.was)
        assertEquals(EventData(turnId = "t5"), base.with("type", null))
        assertEquals(12, base.with("custom", JsonPrimitive(12.0)).int("custom"))
        assertEquals(EventData.EMPTY, EventData())
        assertEquals(EventData.of(JsonObject(emptyMap())), EventData.EMPTY)
    }

    @Test
    fun extraKeysSurviveTheRoundTrip() {
        val d = EventData(kind = "tip", extra = JsonObject(mapOf("future" to JsonPrimitive("x"))))

        val back = BaroJson.decodeFromString(EventData.serializer(), BaroJson.encodeToString(EventData.serializer(), d))

        assertEquals(d, back)
        assertEquals("x", back.string("future"))
    }
}
