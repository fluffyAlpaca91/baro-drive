package com.barodrive.app.feature.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.data.repo.updateConsent
import com.barodrive.core.model.ShareStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** RV-7 시트 단계: 가족 공유 동의를 먼저 묻기 · 보낼 내용 미리보기. */
enum class ShareStep { CONSENT, PREVIEW }

/** RV-7 가족에게 요약 보내기 시트에 그릴 값. */
data class ShareSheetUiState(
    val loading: Boolean = true,
    /** 기록이 없음(시트를 닫는다). */
    val missing: Boolean = false,
    val step: ShareStep = ShareStep.PREVIEW,
    val summary: ShareSummary? = null,
    /** 시트를 열 때 이미 보낸 회차('다시 보내기'). */
    val wasSent: Boolean = false,
    val online: Boolean = true,
    /** 보내는 중(버튼 막음 + '보내는 중이에요'). */
    val busy: Boolean = false,
)

/** RV-7이 화면에 시키는 일. */
sealed interface ShareEffect {
    data class Toast(val text: String) : ShareEffect

    data object Close : ShareEffect
}

/**
 * RV-7 가족에게 요약 보내기(시트) (프로토타입 screens/share.js `open`·`shareSheet`, 설계 4.2 RV-7, 결정-45·46):
 * 가족 공유 동의가 꺼져 있으면 먼저 동의를 묻고, 받는 사람 · 보낼 내용 미리보기(위치 없음) · [취소] [보내기].
 * 연결이면 잠깐 '보내는 중' 뒤 "아버지에게 보냈어요", 끊김이면 "연결되면 보낼게요"(보낼 예정).
 */
@HiltViewModel
class ShareSheetViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val records: RecordRepository,
    private val share: ShareCenter,
    private val clock: Clock,
) : ViewModel() {
    private val recordId: String = savedStateHandle.get<String>(KEY_RECORD_ID).orEmpty()
    private val busy = MutableStateFlow(false)
    private var wasSent: Boolean? = null
    private val _effects = Channel<ShareEffect>(Channel.BUFFERED)

    val effects: Flow<ShareEffect> = _effects.receiveAsFlow()

    val uiState: StateFlow<ShareSheetUiState> = combine(records.observe(recordId), profiles.profile, share.online, busy) { rec, p, online, b ->
        if (rec == null) {
            ShareSheetUiState(loading = false, missing = true)
        } else {
            val was = wasSent ?: (ShareRules.status(rec) == ShareStatus.SENT).also { wasSent = it }
            ShareSheetUiState(
                loading = false,
                step = if (p.consent.family) ShareStep.PREVIEW else ShareStep.CONSENT,
                summary = ShareRules.summary(rec, p, ZonedDateTime.now(clock)),
                wasSent = was,
                online = online,
                busy = b,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShareSheetUiState())

    /** [동의하고 계속]: 가족 공유를 켜고 보낼 내용으로. */
    fun agree() {
        viewModelScope.launch { profiles.updateConsent { it.copy(family = true) } }
    }

    /** [보내기]·[다시 보내기]. */
    fun send() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = share.online.value
            val r = share.send(recordId)
            busy.value = false
            when (r) {
                ShareSendResult.Queued -> _effects.send(ShareEffect.Toast("연결되면 보낼게요"))
                is ShareSendResult.Sent -> _effects.send(ShareEffect.Toast("${r.to}에게 보냈어요"))
                null -> Unit
            }
            _effects.send(ShareEffect.Close)
        }
    }

    companion object {
        /** 경로 인자 이름([ShareSheet.recordId]). */
        const val KEY_RECORD_ID = "recordId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
