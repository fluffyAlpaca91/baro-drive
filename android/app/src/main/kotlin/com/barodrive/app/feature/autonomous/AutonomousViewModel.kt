package com.barodrive.app.feature.autonomous

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.feature.share.ShareCenter
import com.barodrive.app.feature.share.ShareLinkInfo
import com.barodrive.app.feature.share.ShareRules
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.app.ui.components.CoachFace
import com.barodrive.core.engine.score.ScoreText
import com.barodrive.core.model.Category
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ScoreFootnote
import com.barodrive.core.model.ScoreTableRow
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

/** RV-4 자율 모드 도착·점수에 그릴 값 (autonomous.js RV-4 + review.js 틀). */
data class AutonomousUiState(
    val loading: Boolean = true,
    /** 기록이 있음(없으면 '이 회차 기록을 찾을 수 없어요'). */
    val found: Boolean = false,
    val readOnly: Boolean = false,
    val recordId: String = "",
    val title: String = "",
    val sub: String = "",
    val total: Int = 100,
    val lostText: String = "",
    val prevText: String? = null,
    val coach: CoachFace = CoachFace.Dad,
    val lineText: String = "",
    val table: List<ScoreTableRow> = emptyList(),
    val excludedText: String? = null,
    val footnotes: List<ScoreFootnote> = emptyList(),
    val f2: List<F2RowUi> = emptyList(),
    val tips: List<ReviewTipUi> = emptyList(),
    val share: ShareLinkInfo = ShareLinkInfo(ShareStatus.NONE, "가족에게 요약 보내기"),
)

/** RV-4가 화면에 시키는 일. */
sealed interface AutonomousEffect {
    data class Toast(val text: String) : AutonomousEffect

    /** 가족에게 요약 보내기 시트(RV-7). */
    data class OpenShare(val recordId: String) : AutonomousEffect
}

/**
 * RV-4 자율 모드 도착·점수 (프로토타입 screens/autonomous.js RV-4 = review.js `frame` + 점수 구역, 설계 4.2 RV-4·5.5·6.11, 목업 s07).
 * 기록을 지켜보며 그리고(급제동 확인·공유가 바뀌면 다시), 회차 끝에 열리면(읽기 전용이 아니면) 점수 대사를 정차 중 말로 한 번 한다.
 */
@HiltViewModel
class AutonomousViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val records: RecordRepository,
    private val voice: VoiceLog,
    private val share: ShareCenter,
) : ViewModel() {
    private val recordId: String = savedStateHandle.get<String>(KEY_RECORD_ID).orEmpty()
    private val readOnly: Boolean = savedStateHandle.get<Boolean>(KEY_READ_ONLY) ?: false
    private val _effects = Channel<AutonomousEffect>(Channel.BUFFERED)

    val effects: Flow<AutonomousEffect> = _effects.receiveAsFlow()

    val uiState: StateFlow<AutonomousUiState> = combine(records.observe(recordId), profiles.profile, records.records) { rec, p, all ->
        build(rec, p, all)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AutonomousUiState(readOnly = readOnly, recordId = recordId))

    init {
        // 들어올 때 점수 대사를 정차 중 말로 한 번(기록에서 다시 열면 말하지 않음, 결정사항 E T2d-4)
        if (!readOnly) {
            viewModelScope.launch {
                val rec = records.get(recordId) ?: return@launch
                val m = AutonomousText.model(rec, profiles.current(), records.list())
                if (m.lineText.isNotEmpty()) voice.sayLine(m.line.key, ScoreText.vars(m.line), category = Category.INFO)
            }
        }
    }

    /** 급제동 본인 확인(결정-54: 고른 값만 기록에 — 감점·통과와 상관없음). */
    fun chooseF2(f2Id: String, choice: F2Choice) {
        viewModelScope.launch {
            records.update(recordId) { rec ->
                rec.copy(f2 = rec.f2.mapIndexed { i, f -> if (f.id.ifEmpty { "f2-${i + 1}" } == f2Id) f.copy(choice = choice) else f })
            }
        }
    }

    /** '가족에게 요약 보내기 ›': 보낼 예정이면 지금 보내 보고(안 되면 토스트), 아니면 RV-7 시트. */
    fun shareClick() {
        viewModelScope.launch {
            val rec = records.get(recordId) ?: return@launch
            if (ShareRules.status(rec) == ShareStatus.QUEUED) {
                val n = share.flush()
                _effects.send(AutonomousEffect.Toast(if (n > 0) "보내지 못한 요약 ${n}건을 보냈어요" else "연결되면 보낼게요"))
                return@launch
            }
            _effects.send(AutonomousEffect.OpenShare(recordId))
        }
    }

    private fun build(rec: SessionRecord?, p: Profile, all: List<SessionRecord>): AutonomousUiState {
        if (rec == null) return AutonomousUiState(loading = false, found = false, readOnly = readOnly, recordId = recordId)
        val m = AutonomousText.model(rec, p, all)
        return AutonomousUiState(
            loading = false, found = true, readOnly = readOnly, recordId = recordId, title = m.title, sub = m.sub, total = m.score.total,
            lostText = m.lostText, prevText = m.prevText, coach = CoachFace.fromId(p.coach.name.lowercase()), lineText = m.lineText,
            table = m.table, excludedText = m.excludedText, footnotes = m.footnotes, f2 = f2Rows(rec), tips = tipsOf(rec),
            share = ShareRules.linkInfo(rec, p),
        )
    }

    companion object {
        /** 경로 인자 이름([AutonomousResult]). */
        const val KEY_RECORD_ID = "recordId"
        const val KEY_READ_ONLY = "readOnly"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
