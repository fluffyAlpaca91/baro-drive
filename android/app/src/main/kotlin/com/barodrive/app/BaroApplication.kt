package com.barodrive.app

import android.app.Application
import com.barodrive.app.session.DrivingSessionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** 앱 시작점. 세션 관리자는 화면과 상관없이 앱이 사는 동안 신호를 받는다. */
@HiltAndroidApp
class BaroApplication : Application() {
    @Inject
    lateinit var sessionManager: DrivingSessionManager

    override fun onCreate() {
        super.onCreate()
        sessionManager.start()
    }
}
