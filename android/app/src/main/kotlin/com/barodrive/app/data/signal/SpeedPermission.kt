package com.barodrive.app.data.signal

/**
 * 실행 중에 받아야 하는 속도 권한 (진짜 차 속도 `CAR_SPEED` — AAOS 보호 수준 dangerous, 위치 묶음. 설계 7.8).
 * 구현은 `data/signal/car/CarSpeedPermission`. 화면 쪽(ui/SpeedPermissionRequest)은 이것만 보고 시스템 권한 창을 띄운다.
 * 거절해도 앱은 그대로 돈다: 속도는 null로 오고, 진짜 차 caps의 속도가 꺼져 속도가 필요한 항목은 신호 지원 규칙대로 꺼진다.
 */
interface SpeedPermission {
    /** 안드로이드 권한 이름. */
    val name: String

    /** 지금 물을까: 권한이 없고 이번 앱 실행에서 아직 묻지 않았으면 참 — 참을 돌려주면 물은 것으로 친다(한 실행에 한 번). */
    fun takeAsk(): Boolean

    /**
     * 권한이 바뀌었을 수 있을 때(권한 창 결과 · 앱으로 돌아옴): 읽는 신호(caps)를 다시 정하고, 허용이면 속도를 받기 시작한다.
     * 메인 스레드에서 불러도 된다 — 차 서비스 호출은 구현이 뒤에서 한다.
     */
    fun refresh()
}
