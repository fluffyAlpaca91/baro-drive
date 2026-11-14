package com.barodrive.app.feature.parking

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.R
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SessionController
import com.barodrive.core.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import com.barodrive.core.engine.content.ParkingGuide as ParkingSteps

/**
 * PK-1 화면 값 (프로토타입 parking-select.js `paint`, 설계 4.2 PK-1, 목업 s08a).
 * @property selected 고른 카드('parallel-R' 꼴 키, 민트 테두리). [done] 세워 두고 연습을 성공한 종류(작은 완료 체크, 프로필 `parking.done`).
 * @property model 차종 신호('avante' …, 태그 '내 차: 아반떼'). [steerReadable] 핸들 신호를 읽는지 — 못 읽으면 [세워 두고 연습]을 막는다.
 */
@Immutable
data class ParkingSelectUiState(
    val selected: String = DEFAULT_KEY,
    val done: Set<String> = emptySet(),
    val model: String? = null,
    val steerReadable: Boolean = true,
) {
    /** 6가지 중 해 본 수(머리 부제). */
    val doneCount: Int get() = PARKING_ORDER.count { (t, s) -> ParkingSteps.key(t, s) in done }

    val choice: ParkingSpotChoice get() = parseKey(selected) ?: ParkingSpotChoice(ParkingSpot.PARALLEL, ParkingSpot.RIGHT)

    /** 머리 부제 '6가지 중 n가지 해 봤어요'(하나도 없으면 목업 글 '6가지 중 하나를 골라요'). */
    fun sub(res: TextRes): String = if (doneCount > 0) res.text(R.string.pk1_sub_some, doneCount) else res.text(R.string.pk1_sub_none)

    companion object {
        const val DEFAULT_KEY = "parallel-R"
    }
}

/** 'front-L' → 종류·방향(sim/parking.js `parse`). 모양이 다르면 null. */
internal fun parseKey(key: String?): ParkingSpotChoice? {
    val m = Regex("^([a-z]+)-([LR])$").find(key.orEmpty()) ?: return null
    val type = m.groupValues[1]
    if (type !in ParkingSteps.TYPES) return null
    return ParkingSpotChoice(type, m.groupValues[2])
}

/** 이 앱이 떠 있는 동안 지난번에 고른 칸 (parking-select.js `lastKey`). */
internal object ParkingChoiceMemory {
    @Volatile
    var lastKey: String = ParkingSelectUiState.DEFAULT_KEY
}

/**
 * PK-1 주차 연습 고르기 (프로토타입 parking-select.js, 설계 4.2 PK-1·5.7, 목업 s08a). 경로 인자로 처음 고른 칸을 받고(PK-2·PK-4에서
 * 돌아올 때), 없으면 지난번에 고른 칸. 완료 체크는 프로필, 차종·핸들 신호는 지금 신호에서 읽는다.
 */
@HiltViewModel
class ParkingSelectViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    session: SessionController,
    profiles: ProfileRepository,
) : ViewModel() {
    private val selected = MutableStateFlow(initialKey(savedStateHandle.get<String>("type"), savedStateHandle.get<String>("side")))

    val uiState: StateFlow<ParkingSelectUiState> = combine(selected, profiles.profile, session.state) { key, profile, s ->
        stateOf(key, profile, s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), stateOf(selected.value, Profile(), session.state.value))

    /** 카드를 고른다(지난번 고른 칸으로 기억). */
    fun choose(key: String) {
        if (parseKey(key) == null) return
        ParkingChoiceMemory.lastKey = key
        selected.update { key }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** 경로 인자가 맞는 칸이면 그것(지난번 고른 칸도 바꿈), 아니면 지난번 고른 칸(parking-select.js `render` 앞부분). */
        internal fun initialKey(type: String?, side: String?): String {
            if (type != null) {
                val want = ParkingSteps.key(type, side)
                if (parseKey(want) != null) ParkingChoiceMemory.lastKey = want
            }
            return ParkingChoiceMemory.lastKey.takeIf { parseKey(it) != null } ?: ParkingSelectUiState.DEFAULT_KEY
        }

        internal fun stateOf(key: String, profile: Profile, s: DrivingState): ParkingSelectUiState {
            val snap = s.snapshot
            return ParkingSelectUiState(
                selected = key,
                done = profile.parking.done.filterValues { it }.keys,
                model = snap?.model,
                steerReadable = snap == null || snap.steer != null,
            )
        }
    }
}
