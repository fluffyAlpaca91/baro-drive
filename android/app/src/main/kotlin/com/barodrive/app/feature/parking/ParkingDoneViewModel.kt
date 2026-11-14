package com.barodrive.app.feature.parking

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.R
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.data.repo.updateMyCourses
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.core.model.Category
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 리뷰 화면(PK-4·RV-6)에서 화면 밖으로 할 일. */
sealed interface ReviewEffect {
    data class Toast(@param:StringRes val text: Int) : ReviewEffect

    /** [다시 하기]: 같은 코스로 준비 세션을 만들었다 → PR-1. */
    data object ToPredrive : ReviewEffect

    /** [다시 하기]: 코스가 없음 → CR-1 [mode]. */
    data class ToCourses(val mode: Int) : ReviewEffect
}

/**
 * 리뷰 화면 공통 ViewModel 조각 (review.js `frame`): 경로의 기록 id로 기록을 지켜보고(지우면 '기록 없음'), 들어올 때 정차 중 말을
 * 한 번 하고(읽기 전용이면 말하지 않음), 내 코스 저장을 맡는다.
 */
abstract class ModeReviewViewModel(
    savedStateHandle: SavedStateHandle,
    records: RecordRepository,
    protected val profiles: ProfileRepository,
    private val voice: VoiceLog,
    protected val clock: Clock,
) : ViewModel() {
    protected val recordId: String = savedStateHandle.get<String>("recordId").orEmpty()

    /** 기록에서 다시 열었는지(하단 [닫기] 하나, 말 없음). */
    val readOnly: Boolean = savedStateHandle.get<Boolean>("readOnly") ?: false

    /** 기록 + 프로필 → 리뷰 값(기록이 없으면 null). 읽을 때마다 새로 만든다. */
    protected val reviews: Flow<ModeReviewModel?> = combine(records.observe(recordId), profiles.profile) { rec, p -> rec?.let { model(it, p) } }
        .onEach { m -> if (m != null) speakOnEnter(m) }

    private val _effects = Channel<ReviewEffect>(Channel.BUFFERED)
    val effects: Flow<ReviewEffect> = _effects.receiveAsFlow()

    private var latest: SessionRecord? = null

    private fun model(rec: SessionRecord, p: Profile): ModeReviewModel {
        latest = rec
        return ModeReviews.model(rec, p, readOnly, ModeReviews.today(clock))
    }

    /** 들어올 때 말할 대사(키·변수) — 없으면 null. */
    protected abstract fun enterLine(m: ModeReviewModel): Pair<String, Map<String, Any?>>?

    private fun speakOnEnter(m: ModeReviewModel) {
        if (m.readOnly) return
        val (key, vars) = enterLine(m) ?: return
        if (ModeSpoken.first(m.record.id, key)) voice.sayLine(key, vars, category = Category.INFO)
    }

    /** '이 코스 내 코스로 저장' (mycourses.js `saveLink` 누름): 저장하면 '내 코스에 저장했어요'. */
    fun saveCourse() {
        val rec = latest ?: return
        viewModelScope.launch {
            var saved = false
            profiles.updateMyCourses { list ->
                ModeReviews.saveCourse(list, rec, town = DEFAULT_TOWN, savedAt = ModeReviews.now(clock))?.also { saved = true } ?: list
            }
            send(ReviewEffect.Toast(if (saved) R.string.pk_rv_course_saved_toast else R.string.pk_rv_course_error))
        }
    }

    protected suspend fun send(effect: ReviewEffect) = _effects.send(effect)

    protected companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** 장소 코스의 동네(시연 패널 동네를 모를 때). */
        const val DEFAULT_TOWN = "home"
    }
}

/**
 * PK-4 주차 연습 마무리 (프로토타입 parking-guide.js PK-4, 설계 4.2 PK-4, 결정-60). 모드 6 회차가 끝나면 열리고 기록에서도 연다.
 * 들어오면 다 마친 회차만 'pk.done'을 정차 중 말로 한 번 한다.
 */
@HiltViewModel
class ParkingDoneViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    records: RecordRepository,
    profiles: ProfileRepository,
    voice: VoiceLog,
    clock: Clock,
) : ModeReviewViewModel(savedStateHandle, records, profiles, voice, clock) {
    val uiState: StateFlow<ParkingDoneUiState> = reviews
        .map { ParkingDoneUiState(loading = false, model = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ParkingDoneUiState())

    override fun enterLine(m: ModeReviewModel): Pair<String, Map<String, Any?>>? =
        ParkingDoneText.heroKey(m.record)?.let { it to emptyMap() }
}
