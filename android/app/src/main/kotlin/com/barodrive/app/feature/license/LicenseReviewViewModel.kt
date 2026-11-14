package com.barodrive.app.feature.license

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.feature.autonomous.F2RowUi
import com.barodrive.app.feature.autonomous.ReviewSectionsUi
import com.barodrive.app.feature.autonomous.ReviewTipUi
import com.barodrive.app.feature.autonomous.f2Rows
import com.barodrive.app.feature.autonomous.reviewSections
import com.barodrive.app.feature.autonomous.tipsOf
import com.barodrive.app.feature.share.ShareCenter
import com.barodrive.app.feature.share.ShareLinkInfo
import com.barodrive.app.feature.share.ShareRules
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.app.ui.components.CoachFace
import com.barodrive.core.model.Category
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.LicenseCourseResult
import com.barodrive.core.model.LicenseProgress
import com.barodrive.core.model.LicenseScene
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareStatus
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

/** RV-5 운전면허 연습 돌아보기에 그릴 값 (license.js RV-5 + review.js 틀). */
data class LicenseReviewUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val readOnly: Boolean = false,
    val recordId: String = "",
    /** '면허 · C 코스 · 1회차 · 11분 · 5.3km'. */
    val sub: String = "",
    val coach: CoachFace = CoachFace.Dad,
    val sections: ReviewSectionsUi = ReviewSectionsUi(),
    val course: LicenseCourseResult = LicenseCourseResult(),
    val progress: LicenseProgress = LicenseProgress(),
    /** 조수석이 빈 채로 달린 때가 있었음. */
    val aloneText: String? = null,
    val scenes: List<LicenseScene> = emptyList(),
    val sceneTitle: String = "",
    val sceneSub: String = "",
    val sceneNone: String = "",
    val f2: List<F2RowUi> = emptyList(),
    val tips: List<ReviewTipUi> = emptyList(),
    val share: ShareLinkInfo = ShareLinkInfo(ShareStatus.NONE, "가족에게 요약 보내기"),
)

/** RV-5가 화면에 시키는 일. */
sealed interface LicenseReviewEffect {
    data class Toast(val text: String) : LicenseReviewEffect

    data class OpenShare(val recordId: String) : LicenseReviewEffect
}

/**
 * RV-5 운전면허 연습 돌아보기 (프로토타입 screens/license.js RV-5, 설계 4.2 RV-5·6.12.3, 결정-54·56·63, 결정사항 E T8-Q2):
 * RV-1의 1~8구역(코치 히어로·과제 달성·잘한 것·아쉬운 것·다음 과제·급제동 확인·첫 회차 비교·말 수) + 코스 진행 카드 + 시험 기준 장면 카드
 * + 날씨 팁·도움 줄여 가기 알림·공유 줄. 단계 통과 개념 없음. 장면 수는 그릴 때마다 엔진(license.scenes)으로 센다 —
 * 급제동 '내 실수'를 고르면 바로 맞춰진다. 회차 끝에 열리면 회차 끝 대사를 정차 중 말로 한 번 한다.
 */
@HiltViewModel
class LicenseReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val records: RecordRepository,
    private val voice: VoiceLog,
    private val share: ShareCenter,
) : ViewModel() {
    private val recordId: String = savedStateHandle.get<String>(KEY_RECORD_ID).orEmpty()
    private val readOnly: Boolean = savedStateHandle.get<Boolean>(KEY_READ_ONLY) ?: false
    private val _effects = Channel<LicenseReviewEffect>(Channel.BUFFERED)

    val effects: Flow<LicenseReviewEffect> = _effects.receiveAsFlow()

    val uiState: StateFlow<LicenseReviewUiState> = combine(records.observe(recordId), profiles.profile) { rec, p -> build(rec, p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LicenseReviewUiState(readOnly = readOnly, recordId = recordId))

    init {
        if (!readOnly) {
            viewModelScope.launch {
                val rec = records.get(recordId) ?: return@launch
                val p = profiles.current()
                val s = reviewSections(rec, p.coach, p.honorific)
                if (s.heroText.isNotEmpty()) voice.sayLine(s.heroKey, s.heroVars, category = Category.INFO)
            }
        }
    }

    /** 급제동 본인 확인(결정-54). '내 실수'는 급브레이크 장면 수에 들어간다. */
    fun chooseF2(f2Id: String, choice: F2Choice) {
        viewModelScope.launch {
            records.update(recordId) { rec ->
                rec.copy(f2 = rec.f2.mapIndexed { i, f -> if (f.id.ifEmpty { "f2-${i + 1}" } == f2Id) f.copy(choice = choice) else f })
            }
        }
    }

    /** '가족에게 요약 보내기 ›'. */
    fun shareClick() {
        viewModelScope.launch {
            val rec = records.get(recordId) ?: return@launch
            if (ShareRules.status(rec) == ShareStatus.QUEUED) {
                val n = share.flush()
                _effects.send(LicenseReviewEffect.Toast(if (n > 0) "보내지 못한 요약 ${n}건을 보냈어요" else "연결되면 보낼게요"))
                return@launch
            }
            _effects.send(LicenseReviewEffect.OpenShare(recordId))
        }
    }

    private fun build(rec: SessionRecord?, p: Profile): LicenseReviewUiState {
        if (rec == null) return LicenseReviewUiState(loading = false, found = false, readOnly = readOnly, recordId = recordId)
        val v = LicenseText.review(rec, p)
        return LicenseReviewUiState(
            loading = false, found = true, readOnly = readOnly, recordId = recordId, sub = LicenseText.reviewSub(rec),
            coach = CoachFace.fromId(p.coach.name.lowercase()), sections = reviewSections(rec, p.coach, p.honorific),
            course = v.course, progress = v.progress, aloneText = v.aloneText, scenes = v.scenes, sceneTitle = v.sceneTitle,
            sceneSub = v.sceneSub, sceneNone = v.sceneNone, f2 = f2Rows(rec), tips = tipsOf(rec), share = ShareRules.linkInfo(rec, p),
        )
    }

    companion object {
        /** 경로 인자 이름([LicenseReview]). */
        const val KEY_RECORD_ID = "recordId"
        const val KEY_READ_ONLY = "readOnly"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
