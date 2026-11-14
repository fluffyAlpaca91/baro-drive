package com.barodrive.app.feature.parking

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.session.api.SessionController
import com.barodrive.core.model.SessionInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** PK-3에서 화면 밖으로 할 일(이동·토스트). */
sealed interface ParkingGuideEffect {
    data class Toast(@param:StringRes val text: Int) : ParkingGuideEffect

    /** 기록 없이 끝내고 PK-1([type]·[side] 그대로, 뒤로 = 홈). */
    data class ToSelect(val type: String, val side: String) : ParkingGuideEffect

    /** 기록 없이 끝내고 이 화면을 닫는다(움직이는 중 → 주행 덮개 DR-0, P가 되면 직전 정차 화면). */
    data object Close : ParkingGuideEffect
}

/**
 * PK-3 실제 주차 안내 (프로토타입 parking-guide.js PK-3, 설계 4.2 PK-3·5.7·6.12.1, 목업 s08b). 움직이는 중 화면이다.
 * 화면은 엔진 힌트 `hints.parking`만 그리고, [다음 단계]는 엔진에 `pkNext { step }`을 넣는다 — 이미 신호로 넘어간 단계를 늦게
 * 누른 것은 엔진이 무시한다. 다 마치면 엔진이 `hints.arrived`를 세워 다음 P에서 세션이 PK-4로 보낸다(앱 뿌리의 회차 끝 이동).
 */
@HiltViewModel
class ParkingGuideViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val session: SessionController,
) : ViewModel() {
    private val routeType: String? = savedStateHandle["type"]
    private val routeSide: String? = savedStateHandle["side"]

    val uiState: StateFlow<ParkingGuideUiState> = session.state
        .map { ParkingGuideMapper.map(it, routeType, routeSide) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), current())

    private val _effects = Channel<ParkingGuideEffect>(Channel.BUFFERED)
    val effects: Flow<ParkingGuideEffect> = _effects.receiveAsFlow()

    /** [다음 단계] (parking-guide.js `press`): 회차 중이고 아직 다 마치지 않았을 때만. */
    fun next() {
        val ui = current()
        if (!ui.canPress || !session.state.value.active) return
        viewModelScope.launch { session.input(SessionInput.PkNext(ui.step)) }
    }

    /**
     * [그만하기] (parking-guide.js `stop`, 결정사항 E T5b-Q4 기록 안 함·회차 번호 그대로). 다 마치고 P에서 마무리 중이면 토스트만,
     * 움직이는 중이면 끝내고 닫기, P면 끝내고 PK-1.
     */
    fun stop() {
        val s = session.state.value
        val ui = ParkingGuideMapper.map(s, routeType, routeSide)
        when (ParkingGuideMapper.stopOf(s, ui)) {
            GuideStop.FINISHING -> _effects.trySend(ParkingGuideEffect.Toast(GuideBlock.DONE_PARKED.text))
            GuideStop.MOVING -> viewModelScope.launch {
                session.cancel()
                _effects.send(ParkingGuideEffect.Close)
            }
            GuideStop.PARKED -> viewModelScope.launch {
                session.cancel()
                _effects.send(ParkingGuideEffect.ToSelect(ui.type, ui.side))
            }
        }
    }

    private fun current(): ParkingGuideUiState = ParkingGuideMapper.map(session.state.value, routeType, routeSide)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
