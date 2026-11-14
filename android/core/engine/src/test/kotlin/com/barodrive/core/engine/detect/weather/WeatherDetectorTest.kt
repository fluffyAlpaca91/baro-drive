package com.barodrive.core.engine.detect.weather

import com.barodrive.core.engine.CoachEngine
import com.barodrive.core.engine.CoachSession
import com.barodrive.core.engine.Drive
import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.blocked
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.det
import com.barodrive.core.engine.drive
import com.barodrive.core.engine.nav
import com.barodrive.core.engine.of
import com.barodrive.core.engine.r1
import com.barodrive.core.engine.review.round2
import com.barodrive.core.engine.said
import com.barodrive.core.engine.snap
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.CautionItem
import com.barodrive.core.model.CautionType
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Gear
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.ReviewTip
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.Severe
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.Weather
import com.barodrive.core.model.WeatherBadge
import com.barodrive.core.model.WeatherSource
import com.barodrive.core.model.WeatherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프로토타입 t7_weather.test.js의 단위 시험(설계 5.6·6.2·6.3.1 J3·6.5.3·6.8, 결정-17·82). 시나리오 시험은 골든 비교로 본다. */
class WeatherDetectorTest {
    /** t7 `sess`: 등록 순서대로(basic → weather) — 기준을 읽는 basic 검출기 다음에 날씨 검출기. */
    private fun sess(cfg: SessionConfig = SessionConfig(mode = 2), ids: List<String> = emptyList()): CoachSession =
        CoachEngine().createSession(cfg, ids.map(::det) + det("weather.J3"), Lines.book)

    private fun j3(list: List<JudgeEvent>, kind: String) = list.filter { it.item == "J3" && it.data.kind == kind }

    private fun l(key: String, coach: Coach = Coach.DAD, vars: Map<String, Any?> = emptyMap()) = Lines.render(key, coach, vars, index = 0)

    /** t7 `speeds`: 속도 구간 [목표, 초, 고칠 값]을 0.1초 표본으로(기본 기어 D, 줄어들면 브레이크). */
    private class Part(val to: Double, val sec: Double, val fix: (VehicleSnapshot) -> VehicleSnapshot = { it })

    private fun speeds(parts: List<Part>, base: (VehicleSnapshot) -> VehicleSnapshot = { it }): List<VehicleSnapshot> {
        val out = ArrayList<VehicleSnapshot>()
        var t = 0.0
        var v = 0.0
        for (p in parts) {
            val n = Math.round(p.sec * 10).toInt()
            for (i in 1..n) {
                t = r1(t + 0.1)
                out += p.fix(base(snap(t = t, gear = Gear.D, speed = round2(v + (p.to - v) * i / n), brake = p.to < v)))
            }
            v = p.to
        }
        return out
    }

    private fun braked(s: VehicleSnapshot) = s.copy(brake = true)

    /** t7 `stops(n)`: 섰다(2초) → 20km/h → 섬(3초 서 있음) n번. */
    private fun stops(n: Int, base: (VehicleSnapshot) -> VehicleSnapshot = { it }): List<VehicleSnapshot> {
        val parts = arrayListOf(Part(0.0, 2.0))
        repeat(n) { parts += listOf(Part(20.0, 3.0), Part(20.0, 4.0), Part(0.0, 3.0), Part(0.0, 3.0, ::braked)) }
        parts += Part(20.0, 3.0)
        return speeds(parts, base)
    }

    private fun stepUntil(s: CoachSession, list: List<VehicleSnapshot>, end: Double): StepOutput? {
        var last: StepOutput? = null
        for (sn in list) {
            if (sn.t > end + 1e-9) break
            last = s.step(sn)
        }
        return last
    }

    @Test
    fun linesTipsAndReviewLines() {
        val t = mapOf(
            "j3.rain" to listOf("비 온다. 평소보다 천천히 가.", "어머 비 와! 평소보다 천천히 가자, 응?", "비가 오네. 평소보다 천천히 가 보자."),
            "j3.snow" to listOf("눈길이다. 살살, 천천히 가.", "어머 눈길이야! 살살 천천히 가자!", "눈길이야. 살살 천천히 가 보자."),
            "j3.cold" to listOf("길 얼었을 수 있어. 천천히 가.", "어머 추워라! 길 얼었을 수 있어, 천천히!", "길이 얼었을 수 있어. 천천히 가 보자."),
            "j3.slip" to listOf("미끄러운 길이다. 살살.", "어머 미끄러운 길이래! 살살!", "미끄러운 길이야. 살살 가자."),
        )
        for ((k, list) in t) listOf(Coach.SIB, Coach.MOM, Coach.DAD).forEachIndexed { i, c -> assertEquals("$k $c", list[i], l(k, c)) }
        val rain = listOf("평소보다 속도를 줄여요.", "앞차와 거리를 평소보다 넓혀요.", "브레이크는 일찍, 여러 번 나눠 밟아요.", "엑셀에서 발을 일찍 떼고 엔진브레이크를 같이 써요.", "물웅덩이 앞에서는 미리 속도를 줄여요.")
        val snow = listOf("엑셀을 아주 살살 밟아 출발해요. 스노 모드가 있으면 켜요.", "앞차와 거리를 평소의 2배 이상 둬요.",
            "엔진브레이크로 천천히 속도를 줄여요. 급브레이크를 밟으면 바퀴가 잠겨 미끄러져요.", "핸들은 급하게 돌리지 말고 천천히 돌려요.", "내리막 앞에서는 기어를 낮추고 미리 속도를 줄여요.")
        for (c in Coach.entries) {
            rain.forEachIndexed { i, x -> assertEquals(x, l("j3.tip.rain.${i + 1}", c)) }
            snow.forEachIndexed { i, x -> assertEquals(x, l("j3.tip.snow.${i + 1}", c)) }
        }
        assertEquals("빗길 회차예요. 빗길에선 브레이크를 일찍, 나눠서 밟아요.", l("j3.review.rain"))
        assertEquals("눈길 회차예요. 눈길은 엔진브레이크로 천천히 속도를 줄여요.", l("j3.review.snow"))
        assertEquals("비 오는 날은 40까지야, 조금 줄이자.", l("e1.wx", Coach.DAD, mapOf("wx" to "rain", "L" to 40)))
        assertEquals("오늘 같은 날은 25까지다. 발 떼.", l("e1.wx", Coach.SIB, mapOf("wx" to "severe", "L" to 25)))
        assertEquals("엔진브레이크로 천천히 속도를 줄여요." to "눈길 요령 · 급브레이크를 밟으면 바퀴가 잠겨 미끄러져요.", tipText(snow[2], "snow"))
        assertEquals("평소보다 속도를 줄여요." to "빗길 요령", tipText(rain[0], "rain"))
    }

    @Test
    fun registration() {
        val d = det("weather.J3")
        assertEquals(listOf("J3"), d.items)
        assertEquals(emptyList<Any>(), d.needs)
        assertEquals(listOf(1, 2, 3, 4, 5, 7, 8), d.modes)
        assertEquals(listOf("weather.J3" to "mode"), sess(SessionConfig(mode = 6)).info().off.map { it.id to it.reason })
        assertEquals(listOf("weather.J3"), sess(SessionConfig(mode = 5)).info().detectors)
        assertEquals(listOf(30.0, 0.0, 300.0), listOf(Thresholds["J3.wiperSec"], Thresholds["J3.cold"], Thresholds["J3.slip"]))
    }

    @Test
    fun rainAfterThirtySecondsOfWiper() {
        val s = sess()
        val list = (1..600).map { i -> r1(i * 0.1).let { t -> snap(t = t, gear = Gear.D, speed = 30.0, wiper = t >= 1 - 1e-9) } }
        val o299 = checkNotNull(stepUntil(s, list, 30.9))
        assertEquals("29.9초는 전환 없음", WeatherType.CLEAR, s.weather.type)
        assertNull("맑은 날은 배지 없음", o299.hints.badge)
        assertEquals(11.0, BaroEngine.threshold("B1.gain", s.weather))
        val o30 = s.step(list.first { it.t == 31.0 })
        assertEquals("30초 전환", WeatherType.RAIN, s.weather.type)
        assertEquals(WeatherBadge.RAIN, o30.hints.badge)
        assertEquals("바뀌는 순간 j3.rain", listOf(Triple("j3.rain", Category.INFO, 31.0)), o30.say.map { Triple(it.key, it.category, it.t) })
        assertEquals("비가 오네. 평소보다 천천히 가 보자.", o30.say[0].text)
        assertEquals(8.0, BaroEngine.threshold("B1.gain", s.weather))
        assertEquals(6.0, BaroEngine.threshold("F1.drop", s.weather))
        assertEquals("F2는 그대로", 11.0, BaroEngine.threshold("F2.drop", s.weather))
        assertEquals(40, BaroEngine.effLimit(50.0, s.weather))
        assertEquals(24, BaroEngine.effLimit(30.0, s.weather))
        assertEquals("j3.rain은 회차에 한 번", 0, drive(s, list.filter { it.t > 31 }).say.size)
        val rec = s.finish(arrived = true)
        val w = checkNotNull(rec.weather)
        assertEquals(WeatherType.RAIN, w.type)
        assertEquals(WeatherSource.WIPER, w.by)
        assertEquals("회차 처음(0.1초)부터 30.9초에", 30.9, w.at)
        assertEquals(listOf(30.0), j3(rec.events, "rain").map { it.data.wiperSec })
        val s2 = sess()
        val l2 = (1..500).map { i -> r1(i * 0.1).let { t -> snap(t = t, gear = Gear.D, speed = 0.0, brake = true, wiper = (t >= 1 && t < 10) || t >= 11 - 1e-9) } }
        stepUntil(s2, l2, 40.9)
        assertEquals("10초에 꺼졌다가 11초에 다시 켬 → 41초까지는 그대로", WeatherType.CLEAR, s2.weather.type)
        stepUntil(s2, l2.filter { it.t > 40.9 }, 41.0)
        assertEquals("서 있어도 30초면 전환", WeatherType.RAIN, s2.weather.type)
        val s3 = sess()
        drive(s3, list.map { it.copy(wiper = null) })
        assertEquals("와이퍼를 못 읽으면 자동 전환 없음", WeatherType.CLEAR, s3.weather.type)
    }

    @Test
    fun snowFromTheStartAndTheSnowLineAtTheFirstD() {
        val s = sess(SessionConfig(mode = 2, weather = Weather(snow = true)))
        val o0 = s.step(snap(t = 0.1, gear = Gear.P))
        assertEquals("처음부터", WeatherType.SNOW, s.weather.type)
        assertEquals(WeatherBadge.SNOW, o0.hints.badge)
        assertEquals("출발 전에는 말하지 않음", 0, o0.say.size)
        assertEquals(8.0, BaroEngine.threshold("B1.gain", s.weather))
        assertEquals(6.0, BaroEngine.threshold("F1.drop", s.weather))
        assertEquals(40, BaroEngine.effLimit(50.0, s.weather))
        val o1 = s.step(snap(t = 0.2, gear = Gear.D))
        assertEquals("첫 D에 j3.snow", listOf("j3.snow" to "눈길이야. 살살 천천히 가 보자."), o1.say.map { it.key to it.text })
        val list = (3..600).map { i -> snap(t = r1(i * 0.1), gear = if (i % 200 < 100) Gear.D else Gear.R, speed = 20.0, wiper = true) }
        val out = drive(s, list)
        assertEquals("와이퍼 30초가 와도 눈은 눈", WeatherType.SNOW, s.weather.type)
        assertEquals("j3.snow는 한 번, j3.rain 없음", 0, out.say.size)
        val w = checkNotNull(s.finish(arrived = true).weather)
        assertEquals(listOf<Any?>(WeatherType.SNOW, WeatherSource.SNOW, 0.0, false), listOf(w.type, w.by, w.at, w.cold))
    }

    @Test
    fun severeHalvesTheLimitAndItsBadgeComesFirst() {
        val s = sess(SessionConfig(mode = 3, weather = Weather(severe = Severe.ICE)), listOf("basic.E1"))
        assertEquals(25, BaroEngine.effLimit(50.0, s.weather))
        assertEquals(15, BaroEngine.effLimit(30.0, s.weather))
        assertEquals(8.0, BaroEngine.threshold("B1.gain", s.weather))
        assertEquals(6.0, BaroEngine.threshold("F1.drop", s.weather))
        val out = drive(s, (1..60).map { i -> snap(t = r1(i * 0.1), gear = Gear.D, speed = 30.0, nav = nav(limit = 50)) })
        assertEquals(WeatherBadge.SEVERE, out.hints.badge)
        val e1 = out.events.of("E1", "fail")
        assertEquals(1, e1.size)
        assertEquals("50 구역 30km/h는 아주 나쁜 날엔 초과", 25.0, e1[0].data.effLimit)
        assertEquals(listOf("e1.wx" to "오늘 같은 날은 25까지야, 조금 줄이자."), out.say.map { it.key to it.text })
        assertFalse("아주 나쁜 날만이면 출발 문장 없음", said(out, "j3.*"))
        val s2 = sess(SessionConfig(mode = 2, weather = Weather(snow = true, severe = Severe.FOG)))
        assertEquals(WeatherBadge.SEVERE, s2.step(snap(t = 0.1, gear = Gear.D)).hints.badge)
        assertEquals(15, BaroEngine.effLimit(30.0, s2.weather))
        assertEquals("눈이면 눈길 요령", "snow", tipSetOf(s2.weather))
        assertEquals("앞이 안 보이는 날만이면 빗길 요령", "rain", tipSetOf(SessionWeather(severe = Severe.FOG)))
        assertEquals("snow", tipSetOf(SessionWeather(severe = Severe.ICE)))
        assertEquals(WeatherBadge.RAIN, badgeOf(SessionWeather(type = WeatherType.RAIN)))
        assertNull(badgeOf(SessionWeather()))
    }

    @Test
    fun badWeatherLowersB1To8AndF1To6EvenAfterTheWiperSwitch() {
        class R(val b1: JudgeEvent, val f1: JudgeEvent, val out: Drive)
        fun run(cfg: SessionConfig, pre: List<Part> = emptyList(), wiper: Boolean = false): R {
            val parts = pre + listOf(Part(0.0, 2.0), Part(9.0, 1.0), Part(28.0, 4.0), Part(28.0, 5.0), Part(0.0, 4.0), Part(0.0, 2.0, ::braked))
            val out = drive(sess(cfg, listOf("basic.B1", "basic.F1")), speeds(parts) { if (wiper) it.copy(wiper = true) else it })
            return R(out.events.of("B1")[0], out.events.of("F1")[0], out)
        }
        val clear = run(SessionConfig(mode = 2))
        assertEquals(listOf<Any?>("ok", 11.0, 9.0), listOf(clear.b1.result.code, clear.b1.data.threshold, clear.b1.data.maxGain1s))
        assertEquals(listOf<Any?>("ok", 7.5, 7.0), listOf(clear.f1.result.code, clear.f1.data.threshold, clear.f1.data.maxDrop1s))
        val snowR = run(SessionConfig(mode = 2, weather = Weather(snow = true)))
        assertEquals(listOf<Any?>("fail", 8.0), listOf(snowR.b1.result.code, snowR.b1.data.threshold))
        assertEquals(listOf<Any?>("fail", 6.0), listOf(snowR.f1.result.code, snowR.f1.data.threshold))
        assertTrue("교정 카드", snowR.out.cards.any { it.item == "B1" } && snowR.out.cards.any { it.item == "F1" })
        val rain = run(SessionConfig(mode = 2), listOf(Part(0.0, 31.0)), wiper = true)
        assertEquals(listOf<Any?>("fail", 8.0), listOf(rain.b1.result.code, rain.b1.data.threshold))
        assertEquals(listOf<Any?>("fail", 6.0), listOf(rain.f1.result.code, rain.f1.data.threshold))
    }

    @Test
    fun tipCardsOneNewTipPerSignalWait() {
        val s = sess(SessionConfig(mode = 2, weather = Weather(snow = true)))
        val out = drive(s, stops(7))
        val tips = out.cards.filter { it.category == CardCategory.TIP }
        assertEquals("눈길 요령 5개, 6·7번째 신호 대기엔 없음", 5, tips.size)
        assertEquals(listOf("엑셀을 아주 살살 밟아 출발해요.", "앞차와 거리를 평소의 2배 이상 둬요.", "엔진브레이크로 천천히 속도를 줄여요.",
            "핸들은 급하게 돌리지 말고 천천히 돌려요.", "내리막 앞에서는 기어를 낮추고 미리 속도를 줄여요."), tips.map { it.title })
        assertEquals("눈길 요령 · 스노 모드가 있으면 켜요.", tips[0].detail)
        assertTrue("말풍선 문장 없음", tips.all { it.item == "J3" && it.icon == "i-snow" && it.text == null })
        val first = out.steps.first { it.cards.isNotEmpty() }
        assertTrue("처음 움직이기 전의 신호 대기에는 없음 · 첫 카드 ${first.t}", first.t > 5)
        assertEquals("요령 카드는 말하지 않음", listOf("j3.snow"), out.say.map { it.key })
        assertTrue("신호 대기마다 하나씩", out.steps.all { it.cards.size <= 1 })
        val rec = s.finish(arrived = true)
        assertEquals(5, rec.weather?.tipCards)
        assertEquals(5, j3(rec.events, "tip").size)
        val r = drive(sess(SessionConfig(mode = 2, weather = Weather(rain = true))), stops(2))
        assertEquals(listOf(Triple("평소보다 속도를 줄여요.", "빗길 요령", "i-rain"), Triple("앞차와 거리를 평소보다 넓혀요.", "빗길 요령", "i-rain")),
            r.cards.map { Triple(it.title, it.detail, it.icon) })
        assertEquals("맑은 날엔 요령 카드 없음", 0, drive(sess(), stops(3)).cards.size)
        val o4 = drive(sess(SessionConfig(mode = 2, weather = Weather(rain = true)), listOf("basic.B1", "basic.F1")), speeds(listOf(
            Part(0.0, 2.0), Part(12.0, 1.0), Part(28.0, 4.0), Part(28.0, 5.0), Part(0.0, 3.0), Part(0.0, 3.0, ::braked), Part(20.0, 3.0), Part(20.0, 4.0),
            Part(0.0, 4.0), Part(0.0, 3.0, ::braked), Part(20.0, 3.0), Part(20.0, 4.0), Part(0.0, 4.0), Part(0.0, 3.0, ::braked), Part(20.0, 3.0),
        )))
        val t4 = o4.cards.filter { it.category == CardCategory.TIP }.map { it.title }
        assertEquals("같은 요령 두 번 없음: $t4", t4.size, t4.toSet().size)
        assertEquals("첫 신호 대기: 교정 카드 둘(요령은 밀림)", listOf(CardCategory.FIX, CardCategory.FIX), o4.steps.first { it.cards.isNotEmpty() }.cards.map { it.category })
        assertTrue("밀린 요령은 다음 신호 대기에: $t4", t4.size >= 2 && t4[0] == "평소보다 속도를 줄여요.")
    }

    @Test
    fun j3OffOrAfterKeepsTheLoweredThresholds() {
        val sx = sess(SessionConfig(mode = 2, weather = Weather(rain = true), items = mapOf("J3" to Delivery.OFF)), listOf("basic.B1", "basic.F1"))
        val ox = drive(sx, speeds(listOf(Part(0.0, 2.0), Part(9.0, 1.0), Part(28.0, 4.0), Part(28.0, 5.0), Part(0.0, 4.0), Part(0.0, 2.0, ::braked),
            Part(20.0, 3.0), Part(20.0, 4.0), Part(0.0, 3.0), Part(0.0, 3.0, ::braked))))
        assertFalse(said(ox, "j3.rain"))
        assertTrue("막힌 말(X)로 기록", blocked(ox, "j3.rain", BlockReason.OFF))
        assertEquals("B1 8 기준 그대로", listOf("fail" to 8.0, "ok" to 8.0), ox.events.of("B1").map { it.result.code to it.data.threshold })
        assertEquals("F1 6 기준 그대로", 6.0, ox.events.of("F1")[0].data.threshold)
        assertEquals("요령 카드 없음", 0, ox.cards.count { it.category == CardCategory.TIP })
        assertEquals("배지는 보임", WeatherBadge.RAIN, ox.hints.badge)
        val rx = sx.finish(arrived = true)
        assertEquals("리뷰 팁 줄 없음", emptyList<ReviewTip>(), rx.tips)
        assertEquals(WeatherType.RAIN, rx.weather?.type)
        assertEquals(Delivery.OFF, rx.items["J3"])
        val sx2 = sess(SessionConfig(mode = 2, items = mapOf("J3" to Delivery.OFF)))
        val ox2 = drive(sx2, (1..320).map { i -> snap(t = r1(i * 0.1), gear = Gear.D, speed = 30.0, wiper = true) })
        assertEquals(WeatherType.RAIN, sx2.weather.type)
        assertEquals(40, BaroEngine.effLimit(50.0, sx2.weather))
        assertTrue(blocked(ox2, "j3.rain", BlockReason.OFF))
        val ss = sess(SessionConfig(mode = 2, weather = Weather(snow = true), items = mapOf("J3" to Delivery.AFTER)))
        val os = drive(ss, stops(2) { it.copy(outsideTemp = -3.0) })
        assertFalse(said(os, "j3.*"))
        assertTrue(blocked(os, "j3.snow", BlockReason.AFTER) && blocked(os, "j3.cold", BlockReason.AFTER))
        assertEquals("S면 요령 카드 없음(리뷰에만)", 0, os.cards.size)
        assertEquals(listOf(ReviewTip(text = "눈길 회차예요. 눈길은 엔진브레이크로 천천히 속도를 줄여요.", icon = "i-snow")), ss.finish(arrived = true).tips)
    }

    @Test
    fun coldAtTheFirstDWhenAtOrBelowZero() {
        class R(val s: CoachSession, val out: Drive, val rec: com.barodrive.core.model.SessionRecord)
        fun cold(temp: Double?, cfg: SessionConfig = SessionConfig(mode = 2)): R {
            val s = sess(cfg)
            val list = listOf(snap(t = 0.1, gear = Gear.P, outsideTemp = temp)) +
                (2..400).map { i -> snap(t = r1(i * 0.1), gear = Gear.D, speed = if (i > 20) 20.0 else 0.0, outsideTemp = temp) }
            val out = drive(s, list)
            return R(s, out, s.finish(arrived = true))
        }
        val a = cold(-2.0)
        assertEquals(listOf(Triple("j3.cold", 0.2, "길이 얼었을 수 있어. 천천히 가 보자.")), a.out.say.map { Triple(it.key, it.t, it.text) })
        assertEquals(true, a.rec.weather?.cold)
        assertEquals("추운 것만으로는 날씨 안내가 아님", WeatherType.CLEAR, a.rec.weather?.type)
        assertEquals("기준 그대로", 11.0, BaroEngine.threshold("B1.gain", a.s.weather))
        assertNull(a.out.hints.badge)
        assertEquals(-2.0, j3(a.rec.events, "cold")[0].data.temp)
        assertTrue("0°C 포함", said(cold(0.0).out, "j3.cold"))
        assertFalse(said(cold(1.0).out, "j3.cold"))
        assertFalse("바깥 온도를 못 읽으면 뺌", said(cold(null).out, "j3.cold"))
        val b = cold(-2.0, SessionConfig(mode = 2, weather = Weather(snow = true)))
        assertEquals("눈 문장 다음 언 길 문장", listOf("j3.snow", "j3.cold"), b.out.say.map { it.key })
        assertTrue("앞 문장이 끝나고 20초 뒤 ${b.out.say[1].t}", b.out.say[1].t >= b.out.say[0].t + 20)
        assertEquals(WeatherType.SNOW to true, b.rec.weather?.type to b.rec.weather?.cold)
    }

    @Test
    fun slipperyRoadOnlyInBadWeatherOncePerCaution() {
        fun run(cfg: SessionConfig = SessionConfig(mode = 3)): Drive = drive(sess(cfg), (1..600).map { i ->
            val t = r1(i * 0.1)
            val remain = maxOf(0.0, Math.floor((420 - t * 10) / 10) * 10)
            val cautions = ArrayList<CautionItem>()
            if (t < 45) cautions += CautionItem("ca1", CautionType.SLIPPERY_ROAD_ALERT, remain)
            if (t >= 20) cautions += CautionItem("ca2", CautionType.DOWNHILL_CAUTION_AREA, 100.0)
            snap(t = t, gear = Gear.D, speed = 36.0, nav = nav(limit = 60, cautions = cautions))
        })
        val o = run(SessionConfig(mode = 3, weather = Weather(rain = true)))
        val slip = o.say.filter { it.key == "j3.slip" }
        assertEquals("구간마다 한 번", 1, slip.size)
        assertEquals("미끄러운 길이야. 살살 가자.", slip[0].text)
        assertEquals("310m(11.0초)에서는 아직, 300m(11.1초)에서", listOf(Triple("ca1", 300.0, 11.1)),
            j3(o.events, "slip").map { Triple(it.data.cautionId, it.data.remain, it.t) })
        assertFalse("맑은 날엔 없음", said(run(), "j3.slip"))
        assertTrue("아주 나쁜 날도 날씨 안내 중", said(run(SessionConfig(mode = 3, weather = Weather(severe = Severe.FOG))), "j3.slip"))
    }

    @Test
    fun recordWeatherAndReviewTips() {
        fun fin(cfg: SessionConfig) = sess(cfg).let { s -> drive(s, stops(1)); s.finish(arrived = true) }
        val rain = fin(SessionConfig(mode = 2, weather = Weather(rain = true)))
        assertEquals(SessionWeather(WeatherType.RAIN, null, false, WeatherSource.RAIN, 0.0, 1), rain.weather)
        assertEquals(listOf(ReviewTip(text = "빗길 회차예요. 빗길에선 브레이크를 일찍, 나눠서 밟아요.", icon = "i-rain")), rain.tips)
        val both = fin(SessionConfig(mode = 2, weather = Weather(snow = true, severe = Severe.ICE)))
        assertEquals(SessionWeather(WeatherType.SNOW, Severe.ICE, false, WeatherSource.SNOW, 0.0, 1), both.weather)
        assertEquals(listOf("눈길 회차예요. 눈길은 엔진브레이크로 천천히 속도를 줄여요." to "i-snow", "아주 나쁜 날이라 제한속도 기준을 50% 낮춰서 봤어요." to "i-hazard"),
            both.tips.map { it.text to it.icon })
        val sev = fin(SessionConfig(mode = 2, weather = Weather(severe = Severe.FOG)))
        assertEquals(listOf<Any?>(WeatherType.CLEAR, Severe.FOG, WeatherSource.SEVERE), listOf(sev.weather?.type, sev.weather?.severe, sev.weather?.by))
        assertEquals(listOf(ReviewTip(text = "아주 나쁜 날 회차예요. 제한속도 기준을 50% 낮춰서 봤어요.", icon = "i-hazard")), sev.tips)
        val clear = fin(SessionConfig(mode = 2))
        assertEquals(SessionWeather(WeatherType.CLEAR, null, false, null, null, 0), clear.weather)
        assertEquals(emptyList<ReviewTip>(), clear.tips)
        assertEquals("맑은 날 J3 판정 없음", 0, clear.events.count { it.item == "J3" })
    }
}
