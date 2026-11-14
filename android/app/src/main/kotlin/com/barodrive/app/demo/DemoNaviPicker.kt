package com.barodrive.app.demo

import com.barodrive.app.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 내비 '목적지 고르기' 열기 (프로토타입 navi-ui.js `openPicker({ flash })` — PR-1 자율 변형 [내비에서 목적지 고르기]).
 * 에뮬레이터의 내비는 손으로 몰 수 없어서 시연 빌드는 시연 패널의 목적지 목록을 연다. 시연 도구가 없는 빌드에서는 아무 일도 하지 않는다
 * (진짜 차에서는 내비 앱에서 고른다).
 */
@Singleton
class DemoNaviPicker @Inject constructor() {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 시연 패널이 듣는다. */
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    /** 목적지 고르기를 연다. 열 곳(시연 패널)이 없으면 false. */
    fun open(): Boolean {
        if (!BuildConfig.DEMO_TOOLS) return false
        _requests.tryEmit(Unit)
        return true
    }
}
