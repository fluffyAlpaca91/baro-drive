package com.barodrive.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.barodrive.app.demo.keyboard.DemoKeyboard
import com.barodrive.app.ui.BaroRoot
import com.barodrive.app.ui.DrivingGate
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 하나뿐인 Activity (매니페스트에 DO 선언 — 주행 중에도 쓴다. 주행 중 화면은 [DrivingGate]가 고른다).
 * CCP 뒤로 키: 맨 위 화면에서는 `moveTaskToBack(true)`로 숨기기만 한다. `finish()`는 부르지 않는다 — 심사 반려 사유(Pleos UX 4.4).
 * 세션 알림(회차 끝 → 리뷰 등)은 앱 뿌리([BaroRoot])가 SessionController에서 직접 듣는다.
 * 시연 빌드는 PC 키보드 운전([DemoKeyboard])을 키 이벤트 맨 앞에서 받는다(뒤로 키·맵에 없는 키는 그대로 아래로).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var drivingGate: DrivingGate

    /** 시연 빌드에서만 꺼낸다(출시용 빌드는 시뮬레이터를 만들지 않는다). */
    @Inject
    lateinit var demoKeyboard: Lazy<DemoKeyboard>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 먼저 등록한 콜백이 가장 나중에 불린다: 화면 안 뒤로 가기(정차 화면 이동·주행 덮개)가 받지 않을 때만 여기 온다.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        setContent {
            BaroRoot(
                driving = drivingGate.driving,
                onMoveTaskToBack = { moveTaskToBack(true) },
            )
        }
        if (BuildConfig.DEMO_TOOLS) {
            demoKeyboard.get() // 자료 출처를 미리 읽어 둔다 — 첫 키부터 받게
            holdKeyFocus()
        }
    }

    /**
     * 시연 키보드 운전: 화면을 누른 뒤(터치 모드)의 첫 화살표 누름은 시스템(ViewRootImpl)이 '터치 모드 벗어나기'에 써 버려 앱에 오지
     * 않는다. 아무 뷰도 포커스가 없을 때만 그러므로 내용 틀이 포커스를 쥐고 있게 한다(포커스 테두리는 그리지 않음). 입력 칸을 누르면
     * Compose가 포커스를 가져가고, 놓으면 시스템이 다시 이 틀에 준다.
     */
    private fun holdKeyFocus() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        content.isFocusableInTouchMode = true
        content.defaultFocusHighlightEnabled = false
        content.post { if (!content.hasFocus()) content.requestFocus() }
    }

    /**
     * 시연 키보드 운전: 화살표가 Compose 포커스를 옮기기 전에 먼저 받는다. 받지 않은 키는 평소대로.
     * RestrictedApi: 플랫폼 `Activity.dispatchKeyEvent`(공개 API)를 덮어쓰는데, androidx `ComponentActivity`가 자기 덮어쓰기에
     * `@RestrictTo`를 달아 lint가 잘못 잡는다. super를 그대로 부르므로 androidx 쪽 처리도 그대로 돈다.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (BuildConfig.DEMO_TOOLS && demoKeyboard.get().onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** 창이 포커스를 잃으면(다른 창·팝업 메뉴·앱 숨김) 뗌 이벤트가 오지 않으므로 누르던 페달을 뗀다. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (BuildConfig.DEMO_TOOLS && !hasFocus) demoKeyboard.get().releaseAll()
    }
}
