package com.barodrive.app.demo

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.barodrive.app.testing.InMemoryDataStore
import com.barodrive.core.model.DriverSkill
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시연 패널 설정 `baro.panel.v1`(panel.js `set`·`reset`·`load`): 고친 값은 다음 실행에도 남고, 초기화는 처음 값으로. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoPanelRepositoryTest {
    @Test
    fun valuesSurviveARestartAndResetGoesBackToDefaults() = runTest {
        val disk = InMemoryDataStore(emptyPreferences())
        val a = DataStoreDemoPanelRepository(disk, backgroundScope)
        runCurrent()
        a.update { it.copy(demo = it.demo.copy(speed = 4.0, skill = DriverSkill.GOOD, network = false, town = "moved"), mockupHero = true) }
        val b = DataStoreDemoPanelRepository(disk, backgroundScope)
        runCurrent()
        val s = b.state.value
        assertEquals(4.0, s.demo.speed, 0.0)
        assertEquals(DriverSkill.GOOD, s.demo.skill)
        assertFalse(s.demo.network)
        assertEquals("moved", s.town)
        assertTrue(s.mockupHero)
        b.reset()
        assertEquals(PanelSettings(), b.state.value)
        val c = DataStoreDemoPanelRepository(disk, backgroundScope)
        runCurrent()
        assertEquals("초기화하면 저장도 지운다", PanelSettings(), c.state.value)
    }

    @Test
    fun paceDefaultsToRealTimeEvenForOldSavesAndAPickedXnSurvives() = runTest {
        assertTrue("새로 깔면 배속 '실제'", PanelSettings().demo.realtime)
        // '실제' 칸이 생기기 전 저장(×4를 골라 둔 것) → '실제'로 연다
        val old = """{"demo":{"speed":4.0,"autoplay":true,"skill":"normal","network":true,"night":true},"mockupHero":false}"""
        val disk = InMemoryDataStore(preferencesOf(stringPreferencesKey("baro.panel.v1") to old))
        val a = DataStoreDemoPanelRepository(disk, backgroundScope)
        runCurrent()
        assertTrue(a.state.value.demo.realtime)
        assertEquals(4.0, a.state.value.demo.speed, 0.0)
        // 지금 ×2를 고르면 다음 실행에도 ×2
        a.update { it.copy(demo = it.demo.copy(speed = 2.0, realtime = false)) }
        val b = DataStoreDemoPanelRepository(disk, backgroundScope)
        runCurrent()
        assertFalse(b.state.value.demo.realtime)
        assertEquals(2.0, b.state.value.demo.speed, 0.0)
        b.reset()
        assertTrue("[패널 설정 초기화] → '실제'", b.state.value.demo.realtime)
    }
}
