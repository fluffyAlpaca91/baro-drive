package com.barodrive.app.ui.navigation

import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.feature.share.ShareCenter
import com.barodrive.app.session.api.SessionController
import java.time.Clock
import kotlinx.coroutines.test.TestScope
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.onboarding.OnboardingStart
import com.barodrive.app.session.api.ActivePhase
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.Onboarding
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.StepOutput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 앱 뿌리의 화면 이동 규칙(router.js `start`·session.js `onRouterChange`·리졸버). */
class RootViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun startRouteIsOnboardingUntilDone() {
        assertEquals(OnboardingStart, RootViewModel.startRouteFor(Profile()))
        assertEquals(Home, RootViewModel.startRouteFor(Profile(onboarding = Onboarding(done = true))))
    }

    @Test
    fun startRouteComesFromTheStoredProfile() = runTest {
        val store = InMemoryBaroStore()
        store.update { it.copy(onboarding = Onboarding(done = true)) }
        val vm = RootViewModel(FakeSessionController(), store, share(store, FakeSessionController()))
        assertEquals(Home, vm.startRoute.value)
    }

    @Test
    fun prepIsDroppedOnParkScreensOtherThanPredrive() {
        assertTrue(RootViewModel.dropsPrep(ScreenId.HM_1))
        assertTrue(RootViewModel.dropsPrep(ScreenId.CR_1))
        assertFalse("PR-1에서는 그대로", RootViewModel.dropsPrep(ScreenId.PR_1))
        assertFalse("시트(아주 나쁜 날)는 화면 이동이 아니다", RootViewModel.dropsPrep(ScreenId.PR_1_SEVERE))
        assertFalse("움직이는 중 화면", RootViewModel.dropsPrep(ScreenId.PK_3))
        assertFalse(RootViewModel.dropsPrep(null))
    }

    @Test
    fun dropPrepAsksTheSession() = runTest {
        val session = FakeSessionController()
        session.prepare(PrepSession(mode = 1))
        val store = InMemoryBaroStore()
        val vm = RootViewModel(session, store, share(store, session))
        vm.dropPrep()
        assertNull(session.state.value.prep)
        assertEquals(SessionPhase.IDLE, session.state.value.phase)
    }

    @Test
    fun sessionKeepsTheCoverUntilReviewExceptAtAMidwayStop() {
        assertFalse(RootViewModel.coverForSession(DrivingState()))
        assertFalse("준비만", RootViewModel.coverForSession(DrivingState(phase = SessionPhase.READY)))
        assertTrue(RootViewModel.coverForSession(DrivingState(phase = SessionPhase.ACTIVE, activePhase = ActivePhase.DRIVING)))
        assertTrue("P 마무리 동안", RootViewModel.coverForSession(DrivingState(phase = SessionPhase.ACTIVE, activePhase = ActivePhase.FINISHING)))
        assertTrue(RootViewModel.coverForSession(DrivingState(phase = SessionPhase.ACTIVE, activePhase = ActivePhase.PARK_WAIT)))
        assertFalse("DR-3", RootViewModel.coverForSession(DrivingState(phase = SessionPhase.ACTIVE, activePhase = ActivePhase.STOPPED)))
    }

    @Test
    fun demoPanelSeesTheScreenInsideTheCover() {
        assertEquals("회차 없이 달림", ScreenId.DR_0, RootViewModel.coverScreenFor(DrivingState()))
        val driving = DrivingState(phase = SessionPhase.ACTIVE, activePhase = ActivePhase.DRIVING)
        assertEquals(ScreenId.DR_1, RootViewModel.coverScreenFor(driving))
        assertEquals("신호 대기", ScreenId.DR_2, RootViewModel.coverScreenFor(driving.copy(output = StepOutput(waiting = true))))
        assertEquals("P 마무리 동안도 DR-1", ScreenId.DR_1, RootViewModel.coverScreenFor(driving.copy(activePhase = ActivePhase.FINISHING)))
    }

    @Test
    fun coverScreenFollowsTheSessionState() = runTest {
        val session = FakeSessionController()
        val store = InMemoryBaroStore()
        val vm = RootViewModel(session, store, share(store, session))
        assertEquals(ScreenId.DR_0, vm.coverScreen.value)
        session.setState { DrivingState(phase = SessionPhase.ACTIVE, activePhase = ActivePhase.DRIVING) }
        assertEquals(ScreenId.DR_1, vm.coverScreen.value)
    }

    private fun TestScope.share(store: InMemoryBaroStore, session: SessionController) =
        ShareCenter(store, store, session, Clock.systemUTC(), backgroundScope)

    @Test
    fun reviewScreensAreKnown() {
        assertTrue(ScreenId.RV_1.isReview)
        assertTrue(ScreenId.RV_4.isReview)
        assertTrue(ScreenId.PK_4.isReview)
        assertFalse("시트", ScreenId.RV_7.isReview)
        assertFalse(ScreenId.HM_1.isReview)
    }
}
