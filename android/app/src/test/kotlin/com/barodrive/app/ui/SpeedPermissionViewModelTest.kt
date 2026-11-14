package com.barodrive.app.ui

import androidx.datastore.preferences.core.emptyPreferences
import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.data.signal.SpeedPermission
import com.barodrive.app.testing.InMemoryDataStore
import com.barodrive.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 속도 권한 묻기(ui/SpeedPermissionRequest): 진짜 차 자료 출처이고 PR-1(정차)일 때, 권한이 없으면 앱 실행마다 한 번. */
class SpeedPermissionViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    /** 권한 없음에서 시작하는 가짜. 한 번 물으면 다시 묻지 않는다(CarSpeedPermission과 같은 약속). */
    private class FakeSpeedPermission(var granted: Boolean = false) : SpeedPermission {
        private var asked = false
        var refreshes = 0

        override val name: String = "android.car.permission.CAR_SPEED"

        override fun takeAsk(): Boolean {
            if (granted || asked) return false
            asked = true
            return true
        }

        override fun refresh() {
            refreshes++
        }
    }

    private fun settings() = SettingsRepository(InMemoryDataStore(emptyPreferences()))

    @Test
    fun asksOnceForTheRealCarOnly() = runTest {
        val settings = settings()
        val vm = SpeedPermissionViewModel(settings, FakeSpeedPermission())
        assertFalse("시뮬레이터(시연 빌드 처음 값)면 묻지 않는다", vm.takeAsk(active = true))
        settings.setDataSource(DataSource.CAR)
        vm.carSource.first { it }
        assertFalse("PR-1이 아니거나 주행 중이면 묻지 않는다", vm.takeAsk(active = false))
        assertTrue(vm.takeAsk(active = true))
        assertFalse("앱 실행마다 한 번", vm.takeAsk(active = true))
        assertEquals("android.car.permission.CAR_SPEED", vm.permission)
    }

    @Test
    fun grantedPermissionIsNotAskedAndRefreshIsPassedOn() = runTest {
        val settings = settings()
        settings.setDataSource(DataSource.CAR)
        val speed = FakeSpeedPermission(granted = true)
        val vm = SpeedPermissionViewModel(settings, speed)
        vm.carSource.first { it }
        assertFalse(vm.takeAsk(active = true))
        vm.refresh()
        assertEquals(1, speed.refreshes)
    }
}
