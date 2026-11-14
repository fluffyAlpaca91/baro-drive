package com.barodrive.app.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.core.model.SessionRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** ST-2에서 고른 묶음 칩 (history.js `lastFilter` — 앱이 떠 있는 동안 기억). */
@Singleton
class HistoryFilterMemory @Inject constructor() {
    @Volatile
    var last: String = RecordText.FILTERS.first().id
}

/** 회차 줄 하나 (history.js `recordRow`). */
data class RecordRowUi(
    val id: String,
    val mode: Int?,
    /** 세워 두고 연습(리뷰 없음, 모드 번호 테두리만). */
    val virtual: Boolean,
    val title: String,
    val place: String,
    val sub: String,
    val tags: List<RecordTag>,
    /** 누르면 열 리뷰. null이면 누름 없음. */
    val target: RecordTarget?,
)

/** 빈 목록 모양: 기록이 아예 없음 · 고른 묶음만 빔. */
enum class HistoryEmpty { NONE, ALL, FILTER }

/** ST-2 연습 기록에 그릴 값. */
data class HistoryUiState(
    val loading: Boolean = true,
    val filters: List<RecordFilter> = emptyList(),
    val selected: String = RecordText.FILTERS.first().id,
    /** 저장된 회차 수(모든 묶음). */
    val total: Int = 0,
    val rows: List<RecordRowUi> = emptyList(),
    val empty: HistoryEmpty = HistoryEmpty.NONE,
    /** 빈 기록의 [연습하러 가기] → CR-1 지금 단계. */
    val stage: Int = 1,
    /** 지우기 확인 대화상자에 띄운 줄. */
    val confirm: RecordRowUi? = null,
) {
    /** 고른 묶음 이름(묶음이 빌 때 제목). */
    val selectedLabel: String get() = RecordText.filterOf(selected)?.label.orEmpty()
}

/**
 * ST-2 연습 기록 (프로토타입 screens/history.js ST-2, 설계 4.2 ST-2·5.10, 8.1, 결정-90): 묶음 칩 · '최근 30회차까지 차 안에 둬요 · 지금 n회차' ·
 * 회차 줄(최신이 위, 상태 태그) · 줄 끝 지우기(확인 대화상자). 줄을 누르면 그 회차 리뷰를 읽기 전용으로. 지워도 진행·기준값은 남는다.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    profiles: ProfileRepository,
    private val records: RecordRepository,
    private val memory: HistoryFilterMemory,
    private val clock: Clock,
) : ViewModel() {
    private val filter = MutableStateFlow(
        savedStateHandle.get<String>(KEY_FILTER)?.takeIf { RecordText.filterOf(it) != null } ?: memory.last,
    )
    private val confirm = MutableStateFlow<RecordRowUi?>(null)
    private val _toasts = Channel<String>(Channel.BUFFERED)

    val toasts: Flow<String> = _toasts.receiveAsFlow()

    val uiState: StateFlow<HistoryUiState> = combine(records.records, profiles.profile, filter, confirm) { recs, p, f, c ->
        build(recs, RecordText.currentStage(p), f, c)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HistoryUiState())

    /** 묶음 칩 고르기(앱이 떠 있는 동안 기억). */
    fun pick(id: String) {
        if (RecordText.filterOf(id) == null || id == filter.value) return
        filter.value = id
        memory.last = id
    }

    /** 줄 끝 지우기 → 확인 대화상자. */
    fun askDelete(row: RecordRowUi) {
        confirm.value = row
    }

    fun dismiss() {
        confirm.value = null
    }

    /** 확인 → 그 회차 기록만 지운다(보낼 예정 줄에서도 빠짐). */
    fun confirmDelete() {
        val row = confirm.value ?: return
        confirm.value = null
        viewModelScope.launch { if (records.remove(row.id)) _toasts.send("기록을 지웠어요") }
    }

    private fun build(recs: List<SessionRecord>, stage: Int, f: String, c: RecordRowUi?): HistoryUiState {
        val visible = RecordText.filters(recs)
        val selected = if (visible.any { it.id == f }) f else RecordText.FILTERS.first().id
        val now = ZonedDateTime.now(clock)
        val rows = RecordText.list(recs, selected).mapNotNull { r ->
            val id = r.id ?: return@mapNotNull null
            val d = RecordText.describe(r, now)
            RecordRowUi(
                id = id, mode = d.mode, virtual = r.isVirtualParking, title = d.title, place = d.place, sub = d.sub,
                tags = RecordText.tags(r), target = RecordText.target(r),
            )
        }
        val empty = when {
            recs.isEmpty() -> HistoryEmpty.ALL
            rows.isEmpty() -> HistoryEmpty.FILTER
            else -> HistoryEmpty.NONE
        }
        return HistoryUiState(
            loading = false, filters = visible, selected = selected, total = recs.size, rows = rows, empty = empty, stage = stage, confirm = c,
        )
    }

    companion object {
        /** 경로 인자 이름([History.filter]). */
        const val KEY_FILTER = "filter"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
