package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 미리 만든 데모 상태 하나의 정보 (설계 8.3, 프로토타입 data/presets.js `Baro.preset({ id, title, order, desc, build, panel })`).
 * 상태 자체는 [AppState](모양만 — 만드는 규칙은 앱 demo/가 '오늘'에서 거꾸로 센다). `build`가 null이면 저장을 지운다(처음 실행).
 */
@Serializable
data class DemoPresetInfo(
    /** 'stage2-session3' …. */
    val id: String,
    val title: String,
    val order: Int = 0,
    val desc: String = "",
    /** 시연 패널 값. 신호 지원(caps)은 데모 상태와 따로라 건드리지 않는다. */
    val panel: DemoPanel = DemoPanel(),
)

/** 데모 상태가 바꾸는 시연 패널 값 (presets.js `BASE_PANEL`). */
@Serializable
data class DemoPanel(
    /** 네트워크 연결(끊기면 요약 보내기가 줄을 선다). */
    val network: Boolean = true,
    /** 동네 id('home'·'moved'). */
    val town: String = "home",
    /** 혼잡 시드(1 이하 = 코스 기본값 그대로). */
    val congestionSeed: Int = 1,
    /** 다른 앱이 앞에 있음(백그라운드 흉내). */
    val otherApp: Boolean = false,
    val profileId: String = "p1",
)

/**
 * 시연 패널 상태 (설계 8.1 `baro.panel.v1`·9장, 프로토타입 shell/panel.js `defaults`) 중 앱 시연 패널과 시뮬레이터가 쓰는 값.
 * 패널 접힘·보기·목업 원안·기록 필터·묶음 펼침처럼 브라우저 화면에만 있는 값과 가족 휴대폰 카드는 두지 않는다.
 */
@Serializable
data class DemoPanelState(
    /** 시뮬레이터 배속(×1 …, 설계 7.2). [realtime]이면 쓰지 않는다(×1로 본다). */
    val speed: Double = 1.0,
    /**
     * 배속 '실제': 재생 중에도 실제 시간 그대로(압축·배속 없이). 처음 값은 참 — 새로 깔았을 때·[패널 설정 초기화]·이 값이 없는
     * 옛 저장도 '실제'다(2026-11-11 drive-spec A).
     */
    val realtime: Boolean = true,
    /** 시나리오 자동 재생. */
    val autoplay: Boolean = true,
    /** 운전 실력(시나리오 variants, 설계 7.3). */
    val skill: DriverSkill = DriverSkill.NORMAL,
    /** 신호 지원(설계 7.8). */
    val caps: SignalCaps = SignalCaps(),
    /** 네트워크 연결. */
    val network: Boolean = true,
    /** 주간/야간(야간이 처음 값). */
    val night: Boolean = true,
    /** 동네 id. */
    val town: String = "home",
    /** 혼잡 시드('혼잡도 다시 받기'). */
    val congestionSeed: Int = 1,
    /** 차종 id('avante'·'grandeur'·'tucson', 결정-58). */
    val model: String = "avante",
    val profileId: String = "p1",
    /** 다른 앱이 앞에 있음(백그라운드 흉내). */
    val otherApp: Boolean = false,
)

/** 운전 실력 (프로토타입 sim/driver.js `SKILLS`: 'poor' 서툰 · 'normal' 보통 · 'good' 능숙). */
@Serializable
enum class DriverSkill(val label: String) {
    @SerialName("poor")
    POOR("서툰"),

    @SerialName("normal")
    NORMAL("보통"),

    @SerialName("good")
    GOOD("능숙"),
}
