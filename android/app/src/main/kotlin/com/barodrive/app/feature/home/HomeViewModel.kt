package com.barodrive.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * HM-1 홈 (프로토타입 home.js, 설계 4.2 HM-1·5.2). 저장된 프로필(진행·기록·면허)만 보고 홈 상태를 만든다. 화면 이동은 화면 쪽이 한다.
 * [clock]은 면허 유효기간을 볼 오늘 날짜에만 쓴다.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val clock: Clock,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = profiles.profile
        .map { p -> HomeUiState(loaded = true, coach = p.coach, summary = HomeRules.summary(p, LocalDate.now(clock))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
