package com.barodrive.app.session.api

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Card
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Hints
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TodayTask
import com.barodrive.core.model.TurnApproach
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.VehicleSnapshot

/**
 * 세션 단계 (설계 3.1·3.2, 프로토타입 session.js `phase`를 셋으로 묶은 것): 준비 없음('idle') · 준비만('prepared') ·
 * 회차 진행 중(엔진 세션 있음). 진행 중의 자세한 단계는 [ActivePhase].
 */
enum class SessionPhase { IDLE, READY, ACTIVE }

/** 회차 진행 중의 자세한 단계 (session.js `phase`의 'driving'·'parkWait'·'stopped'·'finishing'). */
enum class ActivePhase(val protoName: String) {
    /** 주행(P가 아님). */
    DRIVING("driving"),

    /** P가 됨 — 엔진이 P 표본을 본 다음 틱에 마무리인지 중간 정차인지 정한다. */
    PARK_WAIT("parkWait"),

    /** 중간 정차(DR-3 '여기서 마칠까요?'). */
    STOPPED("stopped"),

    /** 도착 뒤 P에서 남은 말(PB2·PK3·HL3)을 마저 본다 — 끝나면 저장. */
    FINISHING("finishing"),
}

/**
 * 화면이 그릴 주행 상태 (session.js `BaroApp.session` 게터 + 마지막 스냅숏). [SessionController.state]가 낸다.
 * 엔진 출력은 그대로 옮길 뿐 화면에서 판정하지 않는다(설계 6.1).
 */
data class DrivingState(
    val phase: SessionPhase = SessionPhase.IDLE,
    /** [SessionPhase.ACTIVE]일 때만 값이 있다. */
    val activePhase: ActivePhase? = null,
    /** 마지막 스냅숏(세션 초로 맞춘 것, 못 읽는 신호는 null). 신호가 아직 없으면 null. */
    val snapshot: VehicleSnapshot? = null,
    val gear: Gear? = null,
    /** km/h, 후진은 음수. 못 읽으면 null. */
    val speed: Double? = null,
    val blinker: Blinker? = null,
    /** 준비 세션(session.js `prep`). 회차가 시작돼도 끝날 때까지 남는다. */
    val prep: PrepSession? = null,
    /** 회차(없으면 준비 세션)의 모드 1~8. 둘 다 없으면 null. */
    val mode: Int? = null,
    /** 회차(없으면 준비 세션)의 회차 번호. */
    val noInMode: Int? = null,
    /** PR-1 오늘 과제(최대 3, session.js `today`). */
    val today: List<TodayTask> = emptyList(),
    /** 이번 회차에서 마지막 엔진 출력(session.js `lastOutput`). 회차가 없으면 null. */
    val output: StepOutput? = null,
    /** 이번 회차에 마지막으로 말한 코치 문장(session.js `lastSay`) — DR-1 말풍선·'최근 말' 히어로, [SessionController.SAY_HOLD] 동안. */
    val lastSay: Utterance? = null,
    /** 지금 보이는 신호 대기 카드(최대 2장, DR-2). */
    val cards: List<Card> = emptyList(),
    /** 소리 끔(안전만 말함, DR-1 버튼). */
    val muted: Boolean = false,
    /** 내비 도착 또는 검출기의 '도착'(hints.arrived)을 봄 — 다음 P에서 마무리. */
    val arrived: Boolean = false,
    /** 회차 시작부터 sim초(마지막 스냅숏 t). */
    val elapsedSec: Double = 0.0,
    /** DR-3에서 [이어서 달리기]를 누름 — 기어를 D로 바꾸길 기다린다. */
    val dr3Continue: Boolean = false,
    /** PR-1 [내비로 보내고 출발]로 보낸 경로가 아직 내비에 있음(session.js `sent()`). */
    val sent: Boolean = false,
    /** 기어를 못 읽어 트립 거리로 출발·정차를 정하는 중(설계 7.8). */
    val gearless: Boolean = false,
    /** 마지막으로 저장한 회차 기록 id(session.js `record`). */
    val lastRecordId: String? = null,
    /** 시연 패널 상태 줄 글(session.js `label()`): '1단계 3회차 준비' · '자율 2회차 중간 정차' · '… 마무리'. 없으면 ''. */
    val label: String = "",
) {
    /** 화면 힌트(마지막 출력). */
    val hints: Hints get() = output?.hints ?: Hints()

    /** 신호 대기 중(DR-1 ↔ DR-2). */
    val waiting: Boolean get() = output?.waiting == true

    /** 창 안의 다음 내비 회전(깜빡이 히어로). */
    val turn: TurnApproach? get() = output?.turn

    /** 맨 앞 카드(DR-2). */
    val card: Card? get() = cards.firstOrNull()

    /** 옛 이름: [lastSay]. */
    val line: Utterance? get() = lastSay

    /** 회차 진행 중. */
    val active: Boolean get() = phase == SessionPhase.ACTIVE

    /** DR-3 중간 정차. */
    val stopped: Boolean get() = activePhase == ActivePhase.STOPPED

    /** 프로토타입 `phase` 글('idle'·'prepared'·'driving'·'parkWait'·'stopped'·'finishing') — 테스트·시연 패널용. */
    val phaseName: String
        get() = when (phase) {
            SessionPhase.IDLE -> "idle"
            SessionPhase.READY -> "prepared"
            SessionPhase.ACTIVE -> (activePhase ?: ActivePhase.DRIVING).protoName
        }

    /** [lastSay]가 아직 말풍선에 보일 때인가([SessionController.SAY_HOLD] sim초). */
    fun sayVisible(): Boolean {
        val say = lastSay ?: return false
        return elapsedSec - say.t < SessionController.SAY_HOLD
    }
}
