package com.barodrive.app.feature.autonomous

import com.barodrive.app.feature.feedback.PresetRules
import com.barodrive.app.feature.feedback.assumeItemsPorted
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.ScoreCounts
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.Onboarding
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.PreviousScore
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.WeatherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RV-4·PR-1 자율 변형 계산 (프로토타입 tests/unit/t4_autonomous.test.js — 같은 기록, 같은 기대값). */
class AutonomousTextTest {
    private val profile = Profile(onboarding = Onboarding(done = true), myPresets = BaroEngine.examplePresets(), coach = Coach.DAD)

    private fun rec(
        id: String? = null,
        arrived: Boolean = true,
        placeId: String? = "mart",
        weather: SessionWeather = SessionWeather(type = WeatherType.CLEAR),
        counts: ScoreCounts? = ScoreCounts(late = 1, almost = 2, c4 = 1, bf = 1, g1Points = 4, f2 = 1),
        f2: List<F2Check> = listOf(F2Check(id = "f2-1", v0 = 56.0, dur = 4.3)),
        mode: Int = 5,
    ) = SessionRecord(
        id = id, mode = mode, placeId = placeId, presetId = "ex-commute", arrived = arrived, distanceM = 12440.0, durationSec = 1000.0,
        weather = weather, f2 = f2, score = counts?.let { BaroEngine.score.fromCounts(it) },
    )

    @Test
    fun mockupS07Model() {
        val a = AutonomousText.model(rec(), profile, emptyList())
        assertEquals("도착했어요", a.title)
        assertEquals("자율 모드 · 집 → 마트 · 12.4km · 17분", a.sub)
        assertEquals(86, a.score.total)
        assertEquals("14점 깎였어요", a.lostText)
        assertEquals("목업 stroke-dasharray \"507.9 590.6\"", ScoreRing(507.9, 590.6), a.ring)
        assertEquals("rv.score.mid", a.line.key)
        assertEquals("마트까지 잘 왔어. 깜빡이만 조금 일찍 켜 보자.", a.lineText)
        assertEquals(
            listOf(
                Triple("회전 전 깜빡이 '늦음'", "1번", "−3"), Triple("회전 전 깜빡이 '거의'", "2번", "−2"),
                Triple("깜빡이 켜자마자 꺾음", "1번", "−2"), Triple("급출발·급정지", "1번", "−3"),
                Triple("직진할 때 핸들 흔들림", "기준보다 큼", "−4"), Triple("제한속도 초과 · 깜빡이 없이 회전", "0번", "0"),
            ),
            a.table.map { Triple(it.label, it.count, it.points) },
        )
        assertEquals(listOf("위험을 피한 급제동 1번은 감점하지 않았어요.", "늦음 = 회전 30m 안에서 켬 · 거의 = 30~39m"), a.footnotes.map { it.text })
        assertNull(a.excludedText)
        assertNull("첫 자율 회차", a.prev)
        // 코치에 따라 말풍선 글이 바뀐다
        val mom = profile.copy(coach = Coach.MOM, honorific = Honorific(HonorificType.DAUGHTER))
        assertEquals("어머 86점이야! 깜빡이만 조금 일찍 켜자, 응?", AutonomousText.model(rec(), mom, emptyList()).lineText)
    }

    @Test
    fun endedEarlyRainyNoPlaceZeroAndFull() {
        assertEquals("자율 주행을 마쳤어요", AutonomousText.model(rec(arrived = false), profile, emptyList()).title)
        assertEquals(
            "자율 모드 · 집 → 마트 · 12.4km · 17분 · 빗길",
            AutonomousText.model(rec(weather = SessionWeather(type = WeatherType.RAIN)), profile, emptyList()).sub,
        )
        val noPlace = AutonomousText.model(rec(placeId = null), profile, emptyList())
        assertEquals("목적지 없이 달림", "자율 모드 · 12.4km · 17분", noPlace.sub)
        assertTrue("목적지 이름이 없으면 '목적지'", noPlace.lineText.startsWith("목적지까지"))
        val zero = AutonomousText.model(rec(counts = ScoreCounts(c5 = 30)), profile, emptyList())
        assertEquals(0, zero.score.total)
        assertEquals(0.0, zero.ring.dash, 0.0)
        assertEquals("rv.score.lo", zero.line.key)
        val full = AutonomousText.model(rec(counts = ScoreCounts(g1Points = 0), f2 = emptyList()), profile, emptyList())
        assertEquals(full.ring.gap, full.ring.dash, 0.0)
        assertEquals("한 점도 안 깎였어요", full.lostText)
        assertEquals("100점이야. 마트까지 아주 안전하게 왔어.", full.lineText)
    }

    @Test
    fun previousScoreIsTheOlderAutonomousRecord() {
        val a1 = rec(id = "a1", counts = ScoreCounts(late = 6))
        val s1 = SessionRecord(id = "s1", mode = 2)
        val a2 = rec(id = "a2")
        val list = listOf(a2, s1, a1) // 최신이 앞
        val a = AutonomousText.model(a2, profile, list)
        assertEquals(PreviousScore("a1", 82), a.prev)
        assertEquals("지난번 82점 → 오늘 86점", a.prevText)
        assertNull("그보다 앞선 자율 회차가 없음", AutonomousText.model(a1, profile, list).prev)
    }

    @Test
    fun recordWithoutScoreIsScoredFromEventsAndListsUnreadSignals() {
        val r = rec(counts = null).copy(
            caps = SignalCaps(speed = false),
            events = listOf(
                JudgeEvent(t = 1.0, item = "C2", result = EventResult.LATE, data = EventData(turnId = "t1")),
                JudgeEvent(t = 2.0, item = "G1", result = EventResult.INFO, data = EventData(value = 0.8, base = 0.8)),
            ),
        )
        val a = AutonomousText.model(r, profile, emptyList())
        assertEquals(97, a.score.total)
        assertEquals("차속을 못 읽어 급출발·급정지·제한속도는 점수에서 뺐어요", a.excludedText)
        assertFalse(a.table.any { Regex("급출발|제한속도").containsMatchIn(it.label) })
    }

    @Test
    fun predriveTopCard() {
        val t0 = AutonomousText.topModel(PrepSession(mode = 5, presetId = "ex-commute"), profile)
        assertEquals(listOf<Any?>("목적지는 내비에서 직접 넣어요", "고른 곳에 도착하면 안전운전 점수를 보여 줘요", null, "출퇴근용"), listOf(t0.title, t0.sub, t0.destText, t0.preset))
        val t1 = AutonomousText.topModel(PrepSession(mode = 5, presetId = "ex-commute", placeId = "mart"), profile)
        assertEquals("마트" to "마트 · 12.4km · 31분", t1.destName to t1.destText)
        assertEquals("도착하면 안전운전 점수를 보여 줘요", t1.sub)
        assertEquals("새로 만들기", AutonomousText.topModel(PrepSession(mode = 5, presetItems = PresetRules.base(profile)), profile).preset)
        assertEquals("자율 모드 기본값", AutonomousText.topModel(PrepSession(mode = 5), profile).preset)
        assertEquals("집은 거리 없이", "집", AutonomousText.topModel(PrepSession(mode = 5, placeId = "home"), profile).destText)
    }

    @Test
    fun predriveTopCardModifiedPreset() {
        assumeItemsPorted()
        val items = PresetRules.norm(profile.myPresets[0].items) + ("B1" to Delivery.NOW)
        assertEquals("출퇴근용 (바꿈)", AutonomousText.topModel(PrepSession(mode = 5, presetId = "ex-commute", presetItems = items), profile).preset)
    }
}
