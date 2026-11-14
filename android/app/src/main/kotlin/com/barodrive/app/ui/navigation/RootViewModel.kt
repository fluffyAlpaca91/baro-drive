package com.barodrive.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.feature.drive.DriveNotPracticing
import com.barodrive.app.feature.drive.DriveOneLine
import com.barodrive.app.feature.drive.DriveRules
import com.barodrive.app.feature.drive.DriveWaitCard
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.onboarding.OnboardingStart
import com.barodrive.app.feature.share.ShareCenter
import com.barodrive.app.session.api.ActivePhase
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.core.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 앱 뿌리의 화면 이동 상태 (프로토타입 app/router.js `start`·`home` + session.js 리졸버·`toReview`·`router:change` 구독, 설계 3.1·3.3).
 * [com.barodrive.app.ui.BaroRoot]가 쓴다. 화면 이동 자체는 뿌리가 NavController로 한다 — 여기는 세션·프로필에서 '언제'만 낸다.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val session: SessionController,
    profiles: ProfileRepository,
    shareCenter: ShareCenter,
) : ViewModel() {
    /**
     * 화면 밖에서 보낸 공유의 토스트 글('보내지 못한 요약 2건을 보냈어요', share.js). 이 ViewModel이 [ShareCenter]를 주입받아 앱 화면이
     * 뜰 때 만들어 두므로, 공유 화면을 열기 전에도 다음 P에서 보낼 예정 요약을 보낸다.
     */
    val shareNotices: Flow<String> = shareCenter.notices

    /** 첫 화면(router.start): 온보딩 전 ON-1, 끝났으면 HM-1. 프로필을 처음 읽을 때 한 번 정한다(그 전엔 null). */
    val startRoute: StateFlow<Any?> = flow<Any?> { emit(startRouteFor(profiles.current())) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 세션 알림(끝·취소 …) — 뿌리가 늘 듣는다. */
    val events: Flow<SessionEvent> get() = session.events

    /** 중간 정차(DR-3 '여기서 마칠까요?')인가. 참이 되면 뿌리가 DR-3을 연다. */
    val stopped: StateFlow<Boolean> = session.state.map { it.stopped }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 기어와 상관없이 주행 덮개를 둘 때(회차 진행 중, 중간 정차가 아닐 때) — [coverForSession]. */
    val sessionCover: StateFlow<Boolean> = session.state.map(::coverForSession).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 주행 덮개가 덮을 때 그 안에 보이는 화면(DR-0·DR-1·DR-2, [coverScreenFor]) — 시연 패널 '지금 화면'. */
    val coverScreen: StateFlow<ScreenId> = session.state.map(::coverScreenFor).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScreenId.DR_0)

    /** 회차가 달리는 중이고 그 모드 주행 화면이 따로 있으면 그 경로(6 → PK-3 · 7 → HL-1, [driveRouteFor]). 아니면 null. */
    val movingRoute: StateFlow<Any?> = session.state
        .map { s -> if (s.active && s.activePhase == ActivePhase.DRIVING) driveRouteFor(s.mode, s.prep) else null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 지금 회차가 진행 중인가. */
    val sessionActive: Boolean get() = session.state.value.active

    /** 준비 세션을 버린다(session.js `onRouterChange` — PR-1 밖의 정차 화면으로 감). 회차 중이면 아무 일도 없다. */
    fun dropPrep() {
        viewModelScope.launch { session.drop() }
    }

    companion object {
        /** 첫 화면: 온보딩 전이면 ON-1, 끝났으면 HM-1(router.js `homeId`). */
        fun startRouteFor(p: Profile): Any = if (p.onboarding.done) Home else OnboardingStart

        /**
         * 회차 중 주행 덮개(session.js: 주행 화면은 P가 돼도 마무리하는 동안 그대로 — 리뷰로 갈 때까지 정차 화면을 다시 보이지 않는다).
         * 주행·P 마무리 기다림·마무리 중이면 참, 중간 정차(DR-3)·준비·없음은 거짓. 기어를 못 읽어 트립 거리로 달릴 때도 덮는다(설계 7.8).
         */
        fun coverForSession(s: DrivingState): Boolean = s.active && s.activePhase != ActivePhase.STOPPED

        /**
         * 덮개 안 화면 id(session.js `driveScreenId` — 프로토타입은 주행 화면도 라우터 화면이라 패널 '지금 화면'이 DR-1 등을 보인다).
         * 그리는 쪽(DriveRoute)과 같은 [DriveRules.screen]: 회차가 없으면 DR-0, 신호 대기면 DR-2, 아니면 DR-1.
         */
        fun coverScreenFor(s: DrivingState): ScreenId = when (DriveRules.screen(s)) {
            DriveNotPracticing -> ScreenId.DR_0
            DriveWaitCard -> ScreenId.DR_2
            DriveOneLine -> ScreenId.DR_1
        }

        /** 이 정차 화면으로 가면 준비 세션을 버리나(session.js `onRouterChange`): PR-1이 아닌 정차 화면(시트 제외). */
        fun dropsPrep(screen: ScreenId?): Boolean =
            screen != null && screen.host == ScreenHost.SCREEN && screen.kind == ScreenKind.PARK && screen != ScreenId.PR_1
    }
}
