package com.barodrive.app.feature.weather

import app.cash.turbine.test
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.PrepWeather
import com.barodrive.core.model.Profile
import com.barodrive.core.model.RainBy
import com.barodrive.core.model.Severe
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.Weather
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** PR-1 날씨 줄·아주 나쁜 날 시트 (프로토타입 tests/unit/t7_weather.test.js 'PR-1 날씨 줄 모델' — 같은 입력, 같은 기대값). */
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun s(wiper: Boolean? = false, temp: Double? = 15.0) = VehicleSnapshot(t = 0.0, wiper = wiper, outsideTemp = temp)

    private fun pick(m: WeatherCardModel) = listOf<Any>(m.top, m.title, m.status, m.limit, m.rainChip)

    @Test
    fun orderComesFirstWhenWiperOnOrFreezing() {
        assertEquals(40, WeatherRules.orderFor(s()))
        assertEquals("와이퍼 켜짐 → 맨 위(변형 맨 위 카드 0보다 앞)", -1, WeatherRules.orderFor(s(wiper = true)))
        assertEquals("0°C 이하 → 맨 위", -1, WeatherRules.orderFor(s(temp = 0.0)))
        assertEquals("모드 6(실제 주차 안내) PR-1에는 없음", listOf(1, 2, 3, 4, 5, 7, 8), WeatherRules.MODES)
    }

    @Test
    fun cardModelWiperRainColdChipSnowAndSevere() {
        var w = PrepWeather()
        fun model(snap: VehicleSnapshot): WeatherCardModel = WeatherRules.model(w, snap).also { w = it.first }.second
        assertEquals(listOf<Any>(false, "오늘 날씨", "와이퍼가 30초 넘게 켜지면 비 오는 날 안내로 바뀌어요", "", false), pick(model(s())))
        assertEquals(
            listOf<Any>(true, "오늘 날씨", "와이퍼가 켜져 있어요 · 30초가 지나면 비 오는 날 안내로 시작해요", "", false),
            pick(model(s(wiper = true))),
        )
        w = w.copy(wiperSec = 29.9)
        assertEquals("29.9초는 아직", "", model(s(wiper = true)).limit)
        assertFalse(w.rain)
        w = w.copy(wiperSec = 30.0)
        assertEquals(
            listOf<Any>(true, "오늘 날씨", "와이퍼가 켜져 있어요 · 비 오는 날 안내로 시작해요", "빗길 · 제한속도 20% 낮춤", false),
            pick(model(s(wiper = true))),
        )
        assertEquals(true to RainBy.WIPER, w.rain to w.rainBy)
        model(s(wiper = false))
        assertEquals("와이퍼를 끄면 거둠", false to null, w.rain to w.rainBy)
        w = w.copy(wiperSec = 0.0)
        assertEquals(listOf<Any>(true, "오늘 날씨 · 바깥 −2°C"), pick(model(s(temp = -2.0))).take(2))
        assertFalse(model(s(temp = 0.4)).top)
        // 와이퍼를 못 읽음 → [오늘 비 와요]
        var m = model(s(wiper = null))
        assertEquals(true to "와이퍼를 못 읽어요 · 비가 오면 직접 골라 주세요", m.rainChip to m.status)
        w = w.copy(rainBy = RainBy.CHIP)
        m = model(s(wiper = null))
        assertEquals(listOf<Any>(true, "비 오는 날 안내로 시작해요", "빗길 · 제한속도 20% 낮춤"), listOf(w.rain, m.status, m.limit))
        val cfgRain = BaroEngine.buildConfig(2, Profile(sessionCount = mapOf(2 to 1)), PrepSession(mode = 2, weather = w))
        assertEquals("엔진은 처음부터 비", Weather(snow = false, rain = true, severe = null), cfgRain.weather)
        model(s())
        assertEquals("다시 읽으면 손으로 고른 비는 거둠(칩이 사라짐)", false to null, w.rain to w.rainBy)
        // 눈 · 아주 나쁜 날
        w = w.copy(snow = true)
        m = model(s(wiper = true))
        assertEquals("눈이면 와이퍼 글 대신 눈", "눈 오는 날 안내로 시작해요" to "눈길 · 제한속도 20% 낮춤", m.status to m.limit)
        w = w.copy(severe = Severe.ICE)
        assertEquals("아주 나쁜 날 · 제한속도 50% 낮춤", model(s()).limit)
        assertEquals(Weather(snow = true, rain = false, severe = Severe.ICE), BaroEngine.buildConfig(2, Profile(), PrepSession(mode = 2, weather = w)).weather)
        assertEquals("날씨 칸이 없으면 기본값", "와이퍼가 30초 넘게 켜지면 비 오는 날 안내로 바뀌어요", WeatherRules.model(PrepWeather(), s()).second.status)
    }

    @Test
    fun wiperTimeAddsUpAcrossSnapshotsAndTurnsRainOn() = runTest {
        val session = FakeSessionController()
        session.prepare(PrepSession(mode = 2))
        val vm = PredriveWeatherViewModel(session)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        for (t in listOf(0.0, 10.0, 20.0, 29.9)) session.setState { it.copy(snapshot = VehicleSnapshot(t = t, wiper = true, outsideTemp = 10.0)) }
        assertEquals(29.9, session.state.value.prep?.weather?.wiperSec ?: 0.0, 1e-9)
        assertFalse(session.state.value.prep?.weather?.rain == true)
        session.setState { it.copy(snapshot = VehicleSnapshot(t = 30.0, wiper = true, outsideTemp = 10.0)) }
        assertEquals(true to RainBy.WIPER, session.state.value.prep?.weather?.let { it.rain to it.rainBy })
        assertEquals("빗길 · 제한속도 20% 낮춤", vm.uiState.value?.model?.limit)
        session.setState { it.copy(snapshot = VehicleSnapshot(t = 31.0, wiper = false, outsideTemp = 10.0)) }
        val w = session.state.value.prep?.weather
        assertEquals(Triple(false, null, 0.0), Triple(w?.rain, w?.rainBy, w?.wiperSec))
    }

    @Test
    fun chipsAndSevereSheetUpdateThePrepSession() = runTest {
        val session = FakeSessionController()
        session.prepare(PrepSession(mode = 3))
        session.setState { it.copy(snapshot = VehicleSnapshot(t = 0.0)) }
        val vm = PredriveWeatherViewModel(session)
        vm.toasts.test {
            vm.toggleSnow()
            assertEquals("눈 오는 날 안내로 시작해요", awaitItem().text)
            assertTrue(session.state.value.prep?.weather?.snow == true)
            vm.toggleRain()
            assertEquals("비 오는 날 안내로 시작해요", awaitItem().text)
            assertEquals(RainBy.CHIP, session.state.value.prep?.weather?.rainBy)
            vm.toggleRain()
            assertEquals("비 오는 날 안내를 껐어요", awaitItem().text)
        }
        val sheet = SevereWeatherViewModel(session)
        sheet.effects.test {
            sheet.choose(Severe.FOG)
            assertEquals(SevereEffect.Toast("아주 나쁜 날로 기록해요 · 제한속도 기준 50% 낮춤"), awaitItem())
            assertEquals(SevereEffect.Close, awaitItem())
            sheet.choose(Severe.FOG)
            assertEquals("같은 값이면 토스트 없이 닫힘", SevereEffect.Close, awaitItem())
            sheet.choose(null)
            assertEquals(SevereEffect.Toast("아주 나쁜 날을 껐어요"), awaitItem())
            assertEquals(SevereEffect.Close, awaitItem())
        }
        assertNull(session.state.value.prep?.weather?.severe)
    }
}
