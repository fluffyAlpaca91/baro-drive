package com.barodrive.app.feature.stage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.feature.coach.CoachLines
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.core.engine.stage.GradSummary
import com.barodrive.core.engine.stage.StagePass
import com.barodrive.core.engine.stage.StagePassModel
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** RV-2 단계 통과 화면 값 (stage.js `rv2Model`). */
data class StagePassedUiState(
    val loaded: Boolean = false,
    /** 1~4단계 회차가 아니거나 기록이 없으면 null(기록 없음 상태). */
    val pass: StagePassModel? = null,
    val coach: Coach = Coach.DAD,
    /** 통과 대사(rv.pass.N, 지금 코치). */
    val line: String = "",
    /** 4단계 회차면 RV-3으로 바꿔 간다. */
    val toGraduation: Boolean = false,
)

/** RV-3 졸업 · 연습 기록 요약 화면 값 (stage.js `rv3Model`). */
data class GraduationUiState(
    val loaded: Boolean = false,
    val summary: GradSummary? = null,
    val coach: Coach = Coach.DAD,
    /** 졸업 대사(rv.grad). 졸업 전이면 ''. */
    val line: String = "",
    /** '졸업 2026. 11. 9.'의 날짜 부분('2026. 11. 9.'). */
    val date: String? = null,
)

/**
 * RV-2 단계 통과 (프로토타입 stage.js RV-2, 목업 s06, 설계 4.2 RV-2·5.4·6.3.3·6.4). 통과는 회차를 저장할 때 이미 반영됐다 — 읽기만 한다.
 * 들어올 때 통과 대사(rv.pass.N)를 정차 중 말로 한 번. 4단계 기록이면 RV-3으로 바꿔 간다.
 */
@HiltViewModel
class StagePassedViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    records: RecordRepository,
    profiles: ProfileRepository,
    private val voice: VoiceLog,
) : ViewModel() {
    private val recordId: String = savedStateHandle.get<String>(ARG_RECORD).orEmpty()

    val uiState: StateFlow<StagePassedUiState> = combine(records.observe(recordId), profiles.profile) { rec, p ->
        val pass = rec?.let(StagePass::passModel)
        StagePassedUiState(
            loaded = true,
            pass = pass,
            coach = p.coach,
            line = pass?.lineKey?.let { CoachLines.render(it, p.coach, emptyMap(), p.honorific) }.orEmpty(),
            toGraduation = pass?.graduated == true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StagePassedUiState())

    init {
        viewModelScope.launch {
            val rec = records.get(recordId) ?: return@launch
            val pass = StagePass.passModel(rec) ?: return@launch
            if (!pass.graduated) pass.lineKey?.let { voice.sayLine(it, category = Category.INFO) }
        }
    }

    companion object {
        const val ARG_RECORD = "recordId"
    }
}

/**
 * RV-3 졸업 · 연습 기록 요약 (프로토타입 stage.js RV-3, 설계 4.2 RV-3·6.10.5). 단계 연습(모드 1~4) 회차만 센다.
 * 졸업 대사(rv.grad)는 그 회차로 들어왔을 때만 한 번 말한다(홈에서 열면 말하지 않음).
 */
@HiltViewModel
class GraduationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    records: RecordRepository,
    profiles: ProfileRepository,
    private val voice: VoiceLog,
) : ViewModel() {
    private val recordId: String? = savedStateHandle.get<String>(StagePassedViewModel.ARG_RECORD)

    val uiState: StateFlow<GraduationUiState> = combine(profiles.profile, recordId?.let(records::observe) ?: flowOf(null)) { p, _ ->
        val g = StagePass.gradSummary(p)
        GraduationUiState(
            loaded = true,
            summary = g,
            coach = p.coach,
            line = if (g.graduated) CoachLines.render(g.lineKey, p.coach, emptyMap(), p.honorific) else "",
            date = if (g.graduated) dotDate(g.graduatedAt) else null,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GraduationUiState())

    init {
        val id = recordId
        if (id != null) {
            viewModelScope.launch {
                records.get(id) ?: return@launch
                if (StagePass.gradSummary(profiles.current()).graduated) voice.sayLine("rv.grad", category = Category.INFO)
            }
        }
    }

    companion object {
        /** 날짜 'YYYY. M. D.'(util.js `fmtDate` 기본). 못 읽으면 null. */
        fun dotDate(iso: String?): String? {
            if (iso.isNullOrEmpty()) return null
            val d = try {
                OffsetDateTime.parse(iso).toLocalDate()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(iso.take(10))
                } catch (_: DateTimeParseException) {
                    return null
                }
            }
            return "${d.year}. ${d.monthValue}. ${d.dayOfMonth}."
        }
    }
}
