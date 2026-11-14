package com.barodrive.app.feature.hill

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.R
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SessionController
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 언덕 화면에서 화면 밖으로 할 일. */
sealed interface HillEffect {
    data class Toast(@param:StringRes val text: Int, @param:DrawableRes val icon: Int? = null) : HillEffect

    /** P에서 [그만하기]: 기록 없이 끝내고 홈(hill.js `cancel({ go: 'HM-1' })`). */
    data object Home : HillEffect

    /** 멈췄지만 P가 아닌 채 [그만하기]: 기록 없이 끝내고 이 화면을 닫는다(주행 덮개 DR-0 → P면 직전 정차 화면). */
    data object Close : HillEffect
}

/**
 * HL-1·HL-2가 같이 쓰는 버튼 (프로토타입 hill.js `hillHead`·`practiceChips`·단계 칸·상황 칩·[했어요]).
 * 칩·칸은 멈췄을 때만 엔진에 넣고, 아니면 '멈춘 뒤에 바꿀 수 있어요'(설계 4.2 HL-1). 판정·단계 넘김은 엔진이 한다.
 */
abstract class HillSessionViewModel(
    protected val session: SessionController,
    profiles: ProfileRepository,
) : ViewModel() {
    private val profile: StateFlow<Profile> = profiles.profile.stateIn(viewModelScope, SharingStarted.Eagerly, Profile())

    private val _effects = Channel<HillEffect>(Channel.BUFFERED)
    val effects: Flow<HillEffect> = _effects.receiveAsFlow()

    /** 세션 상태와 프로필을 묶어 화면 값으로. */
    protected fun <T> states(initial: T, map: (DrivingState, Profile) -> T): StateFlow<T> =
        combine(session.state, profile, map).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial)

    /** 지금 칩·칸을 받을 수 있나(HL-1 = 멈춤 · HL-2 = 멈춤 + P 아님). */
    protected abstract fun canTap(s: DrivingState): Boolean

    /** 연습 칩 (hill.js `practiceChips`). */
    fun pickPractice(practice: HillPractice) = tapOrHint { session.input(SessionInput.PickHillPractice(practice)) }

    /** 단계 칸 (HL-1). */
    fun pickStep(step: Int) = tapOrHint { session.input(SessionInput.HillStep(step)) }

    /** 상황 칩 (HL-2). */
    fun pickSituation(slope: HillSlope) = tapOrHint { session.input(SessionInput.HillSituation(slope)) }

    /** [했어요] — 화면 누름 단계에서만 보인다. */
    fun press() {
        viewModelScope.launch { session.input(SessionInput.HillPress) }
    }

    /** 소리 끄기·켜기 (hill.js `hillHead` mute): 회차가 없으면 아무 일도 없다. */
    fun toggleMute() {
        viewModelScope.launch {
            val on = session.toggleMute() ?: return@launch
            _effects.send(
                if (on) HillEffect.Toast(R.string.hl_mute_on_toast, R.drawable.ic_mute) else HillEffect.Toast(R.string.hl_mute_off_toast, R.drawable.ic_speaker),
            )
        }
    }

    /**
     * [그만하기] (hill.js `stop`, 결정사항 E T6-13·T5b-Q4): 움직이면 토스트만, 멈췄으면 기록 없이 끝낸다. P면 홈, P가 아니면 이 화면을
     * 닫는다. 끝낼 회차가 없어도(시연 화면 목록으로 연 경우) 같은 곳으로 간다.
     */
    fun stop() {
        val s = session.state.value
        if (!HillViews.still(s.snapshot, s.output)) {
            _effects.trySend(HillEffect.Toast(R.string.hl_stop_hint))
            return
        }
        val gear = s.snapshot?.gear
        val parked = gear == null || gear == Gear.P
        viewModelScope.launch {
            session.cancel()
            _effects.send(if (parked) HillEffect.Home else HillEffect.Close)
        }
    }

    private fun tapOrHint(block: suspend () -> Unit) {
        if (!canTap(session.state.value)) {
            _effects.trySend(HillEffect.Toast(R.string.hl_still_hint))
            return
        }
        viewModelScope.launch { block() }
    }

    protected companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** HL-1 언덕 주행 (프로토타입 hill.js HL-1, 설계 4.2 HL-1·5.8·6.12.2, 목업 s13a). 움직이는 중 화면이다. */
@HiltViewModel
class HillDriveViewModel @Inject constructor(
    session: SessionController,
    profiles: ProfileRepository,
) : HillSessionViewModel(session, profiles) {
    val uiState: StateFlow<HillDriveUiState> =
        states(HillStates.drive(session.state.value, Profile())) { s, p -> HillStates.drive(s, p) }

    override fun canTap(s: DrivingState): Boolean = HillStates.canTapDrive(s)
}

/** HL-2 언덕 주차 (프로토타입 hill.js HL-2, 설계 4.2 HL-2, 목업 s13b). 움직이는 중 화면이다. */
@HiltViewModel
class HillParkViewModel @Inject constructor(
    session: SessionController,
    profiles: ProfileRepository,
) : HillSessionViewModel(session, profiles) {
    val uiState: StateFlow<HillParkUiState> =
        states(HillStates.park(session.state.value, Profile())) { s, p -> HillStates.park(s, p) }

    override fun canTap(s: DrivingState): Boolean = HillStates.canTapPark(s)
}
