package com.barodrive.app.feature.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.data.repo.StoreRules
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.core.model.Category
import com.barodrive.core.model.F2Choice
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * RV-1 오늘 연습 돌아보기 (프로토타입 review.js, 설계 4.2 RV-1·5.3·6.10, 결정-24·39·54). 저장된 회차 기록만 보고 그린다(리뷰 계산은
 * 회차를 저장할 때 엔진이 했다). 들어올 때 회차 끝 대사(rv.end.*)를 정차 중 말로 한 번(기록에서 다시 열면 말하지 않음).
 * 급제동 본인 확인은 그 기록의 f2[].choice에만 저장한다(통과·점수 계산엔 안 넣음).
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val records: RecordRepository,
    private val profiles: ProfileRepository,
    private val voice: VoiceLog,
    private val clock: Clock,
) : ViewModel() {
    private val recordId: String = savedStateHandle.get<String>(ARG_RECORD).orEmpty()
    private val readOnly: Boolean = savedStateHandle.get<Boolean>(ARG_READ_ONLY) ?: false
    private val focus: String? = savedStateHandle.get<String>(ARG_FOCUS)
    val uiState: StateFlow<ReviewUiState> = combine(records.observe(recordId), profiles.profile) { rec, p ->
        ReviewUiState(loaded = true, model = rec?.let { ReviewModels.model(it, p, readOnly, focus, LocalDate.now(clock)) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReviewUiState())

    init {
        if (!readOnly) {
            viewModelScope.launch {
                val rec = records.get(recordId) ?: return@launch
                val sec = ReviewModels.model(rec, profiles.current(), readOnly = false, focus = null, today = LocalDate.now(clock)).sections
                if (sec.heroText.isNotEmpty()) voice.sayLine(sec.heroKey, sec.heroVars, category = Category.INFO)
            }
        }
    }

    /** 급제동 건마다 [위험 피하기]·[내 실수](고친 건 [바꾸기]는 카드가 맡는다). */
    fun chooseF2(id: String, choice: F2Choice) {
        viewModelScope.launch { records.update(recordId) { rec -> ReviewModels.withF2Choice(rec, id, choice) ?: rec } }
    }

    /** '이 코스 내 코스로 저장'. [onSaved]는 저장했을 때만. */
    fun saveCourse(onSaved: () -> Unit) {
        viewModelScope.launch {
            val rec = records.get(recordId) ?: return@launch
            var saved = false
            profiles.update { p ->
                val (next, ok) = ReviewModels.saveCourse(p, rec, StoreRules.isoLocal(ZonedDateTime.now(clock)))
                saved = ok
                next
            }
            if (saved) onSaved()
        }
    }

    companion object {
        const val ARG_RECORD = "recordId"
        const val ARG_READ_ONLY = "readOnly"
        const val ARG_FOCUS = "focus"
    }
}
