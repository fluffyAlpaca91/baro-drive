package com.barodrive.app.feature.hill

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.feature.parking.ModeReviewModel
import com.barodrive.app.feature.parking.ModeReviewViewModel
import com.barodrive.app.feature.parking.ReviewEffect
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.Mode
import com.barodrive.core.model.PrepSession
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** RV-6 화면 값: [loading]이면 기록을 읽는 중, 읽었는데 [model]이 null이면 '이 회차 기록을 찾을 수 없어요'. */
@Immutable
data class HillReviewUiState(
    val loading: Boolean = true,
    val model: ModeReviewModel? = null,
)

/**
 * RV-6 언덕 주행 돌아보기 (프로토타입 hill.js RV-6 + 리뷰 틀, 설계 4.2 RV-6·5.8): 들어오면 회차 끝 대사 rv.hill을 정차 중 말로 한 번(읽기 전용은
 * 말 없음). 급제동 본인 확인은 그 회차 기록에 고른 값만 저장한다(결정-54). [다시 하기]는 같은 코스로 PR-1부터(코스가 없으면 CR-1 모드 7).
 */
@HiltViewModel
class HillReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val records: RecordRepository,
    profiles: ProfileRepository,
    private val session: SessionController,
    voice: VoiceLog,
    clock: Clock,
) : ModeReviewViewModel(savedStateHandle, records, profiles, voice, clock) {
    val uiState: StateFlow<HillReviewUiState> = reviews
        .map { HillReviewUiState(loading = false, model = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HillReviewUiState())

    override fun enterLine(m: ModeReviewModel): Pair<String, Map<String, Any?>> =
        HillReviewText.HERO_KEY to HillReviewText.taskVars(m.record.nextTask)

    /** 급제동 본인 확인([F2Choice.AVOID] 위험 피하기 · [F2Choice.MISTAKE] 내 실수) 저장 (review.js `setF2Choice`). */
    fun setF2Choice(id: String, choice: F2Choice) {
        viewModelScope.launch { records.update(recordId) { HillReviewText.withF2Choice(it, id, choice) } }
    }

    /** [다시 하기] (hill.js `again`): 코스·장소가 있고 회차가 없으면 같은 코스로 준비 세션을 만들어 PR-1, 아니면 CR-1 모드 7. */
    fun again() {
        val rec = uiState.value.model?.record ?: return
        viewModelScope.launch {
            val hasCourse = rec.courseId != null || rec.placeId != null
            if (hasCourse && !session.state.value.active) {
                session.prepare(PrepSession(mode = Mode.HILL.no, courseId = rec.courseId, placeId = rec.placeId))
                send(ReviewEffect.ToPredrive)
            } else {
                send(ReviewEffect.ToCourses(Mode.HILL.no))
            }
        }
    }
}
