package com.barodrive.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.data.signal.SpeedPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 진짜 차 속도 권한(CAR_SPEED) 묻기 (설계 7.8 — 시연 패널 밖, 출시용 빌드에도 있는 길). 자료 출처가 진짜 차이고(`-PdemoTools=false`면 늘)
 * [active](앱 뿌리: PR-1이고 정차)일 때, 권한이 없으면 앱 실행마다 한 번 시스템 권한 창을 띄운다. 연습을 시작하기 바로 전이라
 * 왜 묻는지 알 수 있고, 첫 실행 화면이나 주행 중에는 뜨지 않는다.
 * 거절해도 그대로 간다: 속도는 null, 속도가 필요한 항목은 신호 지원 규칙대로 꺼지고 PR-1 신호 줄에 '못 읽는 신호: 차속'이 보인다.
 * 앱으로 돌아올 때마다 권한을 다시 본다(설정 앱에서 허용했을 수 있다).
 */
@Composable
fun SpeedPermissionRequest(active: Boolean, viewModel: SpeedPermissionViewModel = hiltViewModel()) {
    val car by viewModel.carSource.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
    LaunchedEffect(active, car) {
        if (viewModel.takeAsk(active)) launcher.launch(viewModel.permission)
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
}

/** [SpeedPermissionRequest]의 상태: 지금 자료 출처와 '이번 실행에서 물었나'([SpeedPermission]). */
@HiltViewModel
class SpeedPermissionViewModel @Inject constructor(
    settings: SettingsRepository,
    private val speed: SpeedPermission,
) : ViewModel() {
    /** 자료 출처가 진짜 차인가(시연 도구를 뺀 빌드는 늘 참). */
    val carSource: StateFlow<Boolean> = settings.dataSource.map { it == DataSource.CAR }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 안드로이드 권한 이름. */
    val permission: String get() = speed.name

    /** 지금 권한 창을 띄울까: [active]이고 진짜 차이고, 권한이 없고 이번 실행에서 처음이면 참(물은 것으로 친다). */
    fun takeAsk(active: Boolean): Boolean = active && carSource.value && speed.takeAsk()

    /** 권한 창 결과 뒤·앱으로 돌아올 때: caps와 속도 구독을 지금 권한에 맞춘다. */
    fun refresh() = speed.refresh()
}
