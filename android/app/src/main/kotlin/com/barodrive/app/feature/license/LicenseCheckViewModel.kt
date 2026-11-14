package com.barodrive.app.feature.license

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.session.api.SessionController
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.LicenseChecks
import com.barodrive.core.engine.api.LicenseStartInput
import com.barodrive.core.model.LicenseStartCheck
import com.barodrive.core.model.LicenseStatus
import com.barodrive.core.model.SeatState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** 본인 체크 하나(license.js `CHECKS` id). */
enum class LicenseCheckItem { COMPANION, SIGN, PURPOSE }

/** LC-1 운전면허 연습 모드 시작 체크에 그릴 값 (license.js LC-1 `paint`). */
data class LicenseCheckUiState(
    val loading: Boolean = true,
    /** 유효기간 카드(없음 · D-141 · 30일 이하 주의 · 지남). */
    val status: LicenseStatus = LicenseStatus(),
    val seat: SeatState = SeatState.UNKNOWN,
    val checks: LicenseChecks = LicenseChecks(),
    /** 시작할 수 있음. */
    val ok: Boolean = false,
    /** 유효기간이 지남 — 시작 버튼 대신 안내 + [발급일 바꾸기]. */
    val expired: Boolean = false,
    /** 하단 버튼 글('모두 체크하면 시작할 수 있어요' · '시험장 주변 코스 고르기'). */
    val label: String = "",
)

/** LC-1이 화면에 시키는 일. */
sealed interface LicenseCheckEffect {
    data class Toast(val text: String) : LicenseCheckEffect

    /** 모두 됐으니 CR-1(모드 8)로. */
    data object GoCourses : LicenseCheckEffect
}

/**
 * LC-1 운전면허 연습 모드 시작 체크 (프로토타입 screens/license.js LC-1, 설계 4.2 LC-1·5.9, 목업 s09, 결정-57·63).
 * 조수석 줄은 차(시연 패널 착석 값)를 그대로 따르고, 좌석을 못 읽으면 이 차에서는 시작하지 않는다(설계 7.8).
 * 본인 체크 셋은 이 화면에 들어올 때마다 비운다([resetChecks] — 화면이 다시 보일 때). 시작 판단은 엔진(license.startCheck).
 */
@HiltViewModel
class LicenseCheckViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val session: SessionController,
    private val clock: Clock,
) : ViewModel() {
    private val checks = MutableStateFlow(LicenseChecks())
    private val _effects = Channel<LicenseCheckEffect>(Channel.BUFFERED)

    val effects: Flow<LicenseCheckEffect> = _effects.receiveAsFlow()

    private val passenger: Flow<Boolean?> = session.state.map { it.snapshot?.passenger }.distinctUntilChanged()

    private val check: StateFlow<LicenseStartCheck?> = combine(profiles.profile, passenger, checks) { p, seat, c ->
        BaroEngine.license.startCheck(LicenseStartInput(passenger = seat, checks = c, issueDate = p.license.issueDate, today = today()))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<LicenseCheckUiState> = combine(check, checks) { sc, c ->
        if (sc == null) {
            LicenseCheckUiState(checks = c)
        } else {
            LicenseCheckUiState(
                loading = false, status = sc.status, seat = sc.seat, checks = c, ok = sc.ok, expired = sc.expired, label = sc.label,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LicenseCheckUiState())

    /** 본인 체크 하나 켜고 끄기. */
    fun setCheck(item: LicenseCheckItem, on: Boolean) = checks.update {
        when (item) {
            LicenseCheckItem.COMPANION -> it.copy(companion = on)
            LicenseCheckItem.SIGN -> it.copy(sign = on)
            LicenseCheckItem.PURPOSE -> it.copy(purpose = on)
        }
    }

    /** 들어올 때마다 비움(설계 4.2 LC-1 '매번 확인'). */
    fun resetChecks() {
        checks.value = LicenseChecks()
    }

    /** 하단 버튼: 모두 되면 CR-1(모드 8), 아니면 까닭 토스트. */
    fun start() {
        val sc = check.value ?: return
        _effects.trySend(if (sc.ok) LicenseCheckEffect.GoCourses else LicenseCheckEffect.Toast(sc.hint.orEmpty()))
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
