package com.barodrive.app.demo

import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.SignalKey

/**
 * 시연 패널 표 (프로토타입 shell/panel-sim.js `MODE_NAME`·`TBT_TYPES`, shell/panel-demo.js `CAP_ROWS` — 설계 7.8 표).
 * 패널은 개발 도구라 프로토타입 글을 그대로 둔다.
 */
object DemoTables {
    /** 시나리오 고르기 묶음 이름(모드 → 이름, 없으면 '그 밖'). */
    val MODE_NAME: Map<Int, String> = mapOf(
        1 to "1단계", 2 to "2단계", 3 to "3단계", 4 to "4단계", 5 to "자율", 6 to "주차", 7 to "언덕", 8 to "면허", 9 to "규칙",
    )

    fun modeName(mode: Int?): String = mode?.let { MODE_NAME[it] } ?: "그 밖"

    /** 내비 '덮어쓰기' 회전 종류. */
    data class TbtType(val type: String, val label: String)

    val TBT_TYPES: List<TbtType> = listOf(
        TbtType("TURN_RIGHT", "우회전"), TbtType("TURN_LEFT", "좌회전"), TbtType("TURN_UTURN", "유턴"),
        TbtType("TURN_SHARP_R", "오른쪽 급회전"), TbtType("TURN_KEEP_R", "오른쪽 방향"), TbtType("TURN_ENTER_IC_R", "IC 진입(오른쪽)"),
        TbtType("TURN_EXIT_IC_R", "IC 진출(오른쪽)"),
    )

    fun tbtLabel(type: String): String = TBT_TYPES.firstOrNull { it.type == type }?.label ?: type

    /** 덮어쓰기 거리 m. */
    val TBT_DISTANCES: List<Int> = listOf(100, 150, 300)

    /** 제한속도 덮어쓰기. */
    val LIMITS: List<Int> = listOf(30, 50, 60, 80, 100)

    /** 신호 지원 줄(설계 7.8 표): 신호 · 이름 · 대신 하는 것. */
    data class CapRow(val key: SignalKey, val name: String, val alt: String)

    val CAP_ROWS: List<CapRow> = listOf(
        CapRow(SignalKey.SPEED, "차속", "정차·주행은 트립 거리로 · E1 대신 내비 과속 리포트(E3) · RV-4 '점수에서 뺐어요'"),
        CapRow(SignalKey.GEAR, "기어", "기어로 하는 화면 전환 끔 · 트립 거리가 10초 안 변하면 정차로 봄"),
        CapRow(SignalKey.BLINKER, "깜빡이", "깜빡이 과제 끔 · 모드 2·8은 시작 안 함"),
        CapRow(SignalKey.STEER, "핸들", "주차·언덕 핸들 단계는 화면 누름"),
        CapRow(SignalKey.NAV, "내비", "코스 넣기 대신 목적지는 내비에 직접 · 깜빡이 과제는 C4·C5로만"),
        CapRow(SignalKey.PASSENGER, "좌석(착석)", "L1 자동 끔 · PR-1 [탔어요]·[혼자예요] · 모드 8은 시작 안 함"),
        CapRow(SignalKey.BELT_DRIVER, "운전석 벨트", "PR-1 벨트 줄 '벨트 확인 못 함'"),
        CapRow(SignalKey.HANDBRAKE, "사이드브레이크", "PB1·PB2 자동 끔 · 말로 묻기(pb1.ask·pb2) · PR-1 [풀었어요]"),
        CapRow(SignalKey.WIPER, "와이퍼", "비 자동 전환 끔 · PR-1 [오늘 비 와요]"),
        CapRow(SignalKey.OUTSIDE_TEMP, "바깥 온도", "0°C 이하 안내(j3.cold)·날씨 줄 맨 위로 올리기 끔"),
        CapRow(SignalKey.REVERSE_SPEED, "뒤로 가는 속도", "RV-6 '뒤로 밀림은 기록하지 않았어요'"),
        CapRow(SignalKey.TRIP, "코스 혼잡도", "가까운 순으로만 추천 · '덜 막혀요'·코치 한마디 없음(결정-81)"),
    )

    /** '운전자: 방금 어땠어?'(K8) — 엔진 입력이라 시뮬레이터 주입 목록 밖(js/app/screens/demo.js `Baro.injection`). */
    const val DRIVER_ASK_ID = "driverAsk"
    const val DRIVER_ASK_LABEL = "운전자: 방금 어땠어?"
    const val DRIVER_ASK_GROUP = "운전자"
    const val DRIVER_ASK_ITEM = "K8"

    /** 패널 묶음 id와 처음 펼침(설계 9: 기본 펼침 1·2·3·7·9 — 지금 화면·시나리오·차량·말한 내용·보기). */
    enum class Section(val id: String, val defaultOpen: Boolean) {
        NOW("now", true),
        SCENARIO("scenario", true),
        CAR("car", true),
        NAVI("navi", false),
        INJECT("inject", false),
        DEMO("demo", false),
        CAPS("caps", false),
        VOICE("voice", true),
        FAMILY("family", false),
        VIEW("view", true),
    }

    /**
     * 깜빡이를 못 읽으면 모드 2·8은 시작하지 않는다(설계 7.8, demo.js `startBlock`) → 안내 제목 | null.
     * '깜빡이 신호를 못 읽어 2단계를 시작할 수 없어요' · '… 운전면허 연습을 시작할 수 없어요'.
     */
    fun startBlock(mode: Int, caps: SignalCaps): String? {
        if (mode !in BLOCK_MODES || caps.blinker) return null
        val name = if (mode == 8) "운전면허 연습" else "${mode}단계"
        return "깜빡이 신호를 못 읽어 " + name + (if (mode == 8) "을 시작할 수 없어요" else "를 시작할 수 없어요")
    }

    private val BLOCK_MODES = listOf(2, 8)

    /** 'mm:ss'(panel-sim.js `fmtSim`). */
    fun fmtSim(t: Double): String {
        val s = maxOf(0L, kotlin.math.floor(if (t.isFinite()) t else 0.0).toLong())
        return (s / 60).toString().padStart(2, '0') + ":" + (s % 60).toString().padStart(2, '0')
    }
}
