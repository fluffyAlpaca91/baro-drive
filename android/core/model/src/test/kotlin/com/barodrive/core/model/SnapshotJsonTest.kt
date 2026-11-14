package com.barodrive.core.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotJsonTest {
    @Test
    fun navEventsReadInEveryPrototypeForm() {
        val text = """
            { "t": 12.3, "gear": "D", "speed": 28, "blinker": "RIGHT",
              "nav": { "status": "ROUTE_DRIVE", "limit": 30,
                       "events": [ "reroute", { "type": "destinationArrived" }, { "type": "waypoint", "index": 2, "segment": 1, "kind": "segment" } ] },
              "skip": true, "skipInfo": { "from": { "s": 120, "t": 30.5 }, "to": { "s": 860, "t": 95 }, "label": "3단계" } }
        """.trimIndent()

        val s = BaroJson.decodeFromString(VehicleSnapshot.serializer(), text)

        assertEquals(Gear.D, s.gear)
        assertEquals(Blinker.RIGHT, s.blinker)
        assertEquals(TurnDir.RIGHT, s.blinker?.dir)
        assertEquals(30, s.nav?.limit)
        assertEquals(
            listOf(NavEvent.Reroute(), NavEvent.Arrived, NavEvent.WaypointArrived(index = 2, segment = 1, kind = "segment")),
            s.nav?.events,
        )
        assertTrue(s.skip)
        assertEquals("3단계", s.skipInfo?.label)
        assertEquals(860.0, s.skipInfo?.to?.s)
    }

    @Test
    fun snapshotRoundTripsAndWritesPrototypeEventObjects() {
        val s = VehicleSnapshot(
            t = 1.0, gear = Gear.R, speed = -3.2, nav = NavSnapshot(
                tbt = listOf(TbtItem(id = "t1", type = "TURN_LEFT", distance = 120.0, dir = TurnDir.LEFT, blinker = Blinker.LEFT)),
                cautions = listOf(CautionItem("ca1", CautionType.SLIPPERY_ROAD_ALERT, 280.0)),
                events = listOf(NavEvent.Reroute("t1"), NavEvent.Arrived), segment = NavSegment(2, 3),
            ),
        )

        val text = BaroJson.encodeToString(VehicleSnapshot.serializer(), s)
        val nav = BaroJson.parseToJsonElement(text).jsonObject.getValue("nav").jsonObject

        assertEquals(s, BaroJson.decodeFromString(VehicleSnapshot.serializer(), text))
        assertTrue(text.contains("\"type\":\"reroute\""))
        assertTrue(text.contains("\"dir\":\"L\""))
        assertEquals("SLIPPERY_ROAD_ALERT", nav.getValue("cautions").toString().substringAfter("\"type\":\"").substringBefore('"'))
    }

    @Test
    fun sessionInputsUsePrototypeTypeNames() {
        val inputs: List<SessionInput> = listOf(
            SessionInput.PbConfirmed, SessionInput.DriverAsk, SessionInput.PkNext(step = 3), SessionInput.HillPress,
            SessionInput.HillSituation(HillSlope.UP), SessionInput.PickHillPractice(HillPractice.PARK), SessionInput.HillStep(2),
            SessionInput.Mute, SessionInput.Unmute,
        )
        val serializer = ListSerializer(SessionInput.serializer())

        val text = BaroJson.encodeToString(serializer, inputs)

        assertEquals(inputs, BaroJson.decodeFromString(serializer, text))
        val types = BaroJson.parseToJsonElement(text).let { BaroJson.decodeFromJsonElement(ListSerializer(kotlinx.serialization.json.JsonObject.serializer()), it) }
            .map { it.getValue("type").jsonPrimitive.content }
        assertEquals(listOf("pbConfirmed", "driverAsk", "pkNext", "hillPress", "hillSituation", "hillPractice", "hillStep", "mute", "unmute"), types)
        assertEquals(SessionInput.PkNext(step = 4), BaroJson.decodeFromString(SessionInput.serializer(), """{"type":"pkNext","step":4}"""))
        assertEquals(
            SessionInput.HillSituation(HillSlope.DOWN),
            BaroJson.decodeFromString(SessionInput.serializer(), """{"type":"hillSituation","value":"down"}"""),
        )
    }

    @Test
    fun blockedUtteranceIsFlatLikeThePrototype() {
        val b = BlockedUtterance(Utterance(key = "c1", text = "오른쪽 깜빡이 미리 켜 볼까?", category = Category.PRE, item = "C1", t = 35.1, coach = Coach.DAD), BlockReason.NAV_VOICE)

        val obj = BaroJson.parseToJsonElement(BaroJson.encodeToString(BlockedUtterance.serializer(), b)).jsonObject

        assertEquals(setOf("t", "item", "key", "category", "text", "coach", "reason"), obj.keys)
        assertEquals("navVoice", obj.getValue("reason").jsonPrimitive.content)
        assertEquals("pre", obj.getValue("category").jsonPrimitive.content)
        assertEquals(b, BaroJson.decodeFromJsonElement(BlockedUtterance.serializer(), obj))
    }

    @Test
    fun stepOutputDefaultsAreEmpty() {
        assertEquals(StepOutput.EMPTY, BaroJson.decodeFromString(StepOutput.serializer(), "{}"))
        val out = BaroJson.decodeFromString(StepOutput.serializer(), """{"hints":{"badge":"severe","arrived":true,"license":{"segment":2,"count":3,"text":"구간 2/3"}}}""")
        assertEquals(WeatherBadge.SEVERE, out.hints.badge)
        assertEquals("아주 나쁜 날 · 제한속도 50% 낮춤", out.hints.badge?.label)
        assertEquals(true, out.hints.arrived)
        assertEquals("구간 2/3", out.hints.license?.text)
    }

    @Test
    fun capsReadAndToggleBySignalKey() {
        val caps = SignalCaps().with(SignalKey.SPEED, false).with(SignalKey.TRIP, false)

        assertEquals(listOf(SignalKey.SPEED, SignalKey.TRIP), caps.off)
        assertEquals(false, caps[SignalKey.SPEED])
        assertEquals(true, caps[SignalKey.REVERSE_SPEED])
        assertEquals(SignalKey.entries.map { it.key }, BaroJson.encodeToJsonElement(SignalCaps.serializer(), SignalCaps()).jsonObject.keys.toList())
        assertEquals("차속", SignalKey.fromKey("speed")?.title)
    }

    @Test
    fun severeWeatherReadsLeniently() {
        fun weather(severe: String) = BaroJson.decodeFromString(SessionWeather.serializer(), """{"type":"snow","severe":$severe}""")

        assertEquals(null, weather("false").severe)
        assertEquals(null, weather("null").severe)
        assertEquals(Severe.FOG, weather("\"fog\"").severe)
        assertEquals(Severe.OTHER, weather("true").severe)
        assertEquals(Severe.OTHER, weather("\"hail\"").severe)
        assertEquals(SessionWeather(type = WeatherType.SNOW), Weather(snow = true, rain = true).initial)
        assertEquals("fog", BaroJson.encodeToJsonElement(SessionWeather.serializer(), weather("\"fog\"")).jsonObject.getValue("severe").jsonPrimitive.content)
    }
}
