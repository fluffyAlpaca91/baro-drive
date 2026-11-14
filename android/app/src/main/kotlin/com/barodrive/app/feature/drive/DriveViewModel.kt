package com.barodrive.app.feature.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.demo.DemoPanelRepository
import com.barodrive.app.demo.FixedDemoPanel
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SessionController
import com.barodrive.core.model.Coach
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 주행 덮개 화면 DR-0·DR-1·DR-2 (프로토타입 drive.js, 설계 4.2 DR-0~2·4.6.1·2.5). 세션 상태를 그대로 옮길 뿐 판정하지 않는다 —
 * 말·카드·회전은 엔진이 정했다(설계 6.1). 어느 화면인지도 세션 상태로 고른다([DriveRules.screen]).
 * 소리 끄기(DR-1·DR-2) = session.toggleMute — 회차가 끝나면 다시 켜진다(결정-49).
 */
@HiltViewModel
class DriveViewModel @Inject constructor(
    private val session: SessionController,
    profiles: ProfileRepository,
    panel: DemoPanelRepository = FixedDemoPanel,
) : ViewModel() {
    val uiState: StateFlow<DriveUiState> = combine(
        session.state,
        profiles.profile.map { it.coach },
        panel.state.map { it.mockupHero }.distinctUntilChanged(),
    ) { s, coach, mockup -> build(s, coach, mockup) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), build(session.state.value, Coach.DAD, false))

    private fun build(s: DrivingState, coach: Coach, mockup: Boolean): DriveUiState {
        val screen = DriveRules.screen(s)
        val base = DriveUiState(
            screen = screen, gear = s.gear, gearLabel = DriveRules.gearLabel(s.gear, DriveRules.moving(s), screen), coach = coach, mockupHero = mockup,
        )
        if (screen == DriveNotPracticing) return base
        val cards = if (screen == DriveWaitCard) s.cards.take(MAX_CARDS) else emptyList()
        return base.copy(
            sub = DriveRules.headSub(s),
            muted = s.muted,
            badge = DriveRules.badge(s),
            hero = if (screen == DriveWaitCard) DriveRules.defaultHero(s) else DriveRules.hero(s, session::itemValue),
            cards = cards,
            say = if (cards.isNotEmpty()) null else DriveRules.recentSay(s)?.text?.takeIf { it.isNotEmpty() },
        )
    }

    /** 소리 끄기·켜기. [onDone]에 꺼졌나(회차가 없으면 부르지 않음). */
    fun toggleMute(onDone: (Boolean) -> Unit) {
        viewModelScope.launch { session.toggleMute()?.let(onDone) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_CARDS = 2
    }
}

/**
 * DR-3 여기서 마칠까요? (drive.js DR-3, 설계 4.2 DR-3·3.2 중간 P, 결정-40). [이어서 달리기] → 기어를 D로 바꾸면 같은 회차를 이어서 기록 ·
 * [여기서 마치기] → session.endHere(P 마무리를 바로 돌린 뒤 도착 안 함으로 저장 → 앱 뿌리가 리뷰로).
 */
@HiltViewModel
class StopHereViewModel @Inject constructor(private val session: SessionController) : ViewModel() {
    private val ending = MutableStateFlow(false)

    val uiState: StateFlow<StopHereUiState> = combine(session.state, ending) { s, e ->
        StopHereUiState(active = s.active, sub = DriveRules.headSub(s), remain = DriveRules.remainText(s), waiting = s.dr3Continue, ending = e)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StopHereUiState())

    fun continueHere() {
        viewModelScope.launch { session.continueHere() }
    }

    /** [여기서 마치기]. 저장하지 못하면 [onFail](다시 누를 수 있게 버튼을 푼다). */
    fun endHere(onFail: () -> Unit) {
        if (ending.value) return
        ending.value = true
        viewModelScope.launch {
            val id = session.endHere()
            if (id == null) {
                ending.value = false
                onFail()
            }
        }
    }
}
