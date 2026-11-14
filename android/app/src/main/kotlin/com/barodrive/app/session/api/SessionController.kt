package com.barodrive.app.session.api

import com.barodrive.core.model.Delivery
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TodayTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 주행 세션의 공개 API (프로토타입 app/session.js `BaroApp.session`, 설계 3.1·3.2, 구현계획 1.5). 앱에 하나.
 * 구현은 [com.barodrive.app.session.DrivingSessionManager]. 화면·ViewModel은 이 인터페이스만 주입받고, 미리보기·테스트는
 * [FakeSessionController]를 쓴다.
 *
 * 패키지: 화면이 쓰는 앱 계약은 `session.api`(세션·음성 기록)와 `data.repo`(프로필·기록 저장소)에만 둔다.
 * 구현(`session`, `data.store`, `data.voice` …)은 바뀌어도 이 두 패키지의 모양은 지킨다.
 *
 * 세션 수명(설계 3.2): [prepare](PR-1) → 기어가 P를 벗어나면 회차 시작([SessionEvent.Started]) → P가 되면
 * 도착이면 P 마무리(3 sim초) 뒤 저장 → [SessionEvent.Finished], 아니면 DR-3([ActivePhase.STOPPED]) →
 * [continueHere] 뒤 D 또는 [endHere]. 시작·끝은 신호(기어)가 정한다 — 화면이 부르는 것이 아니다.
 * 바꾸는 함수는 모두 suspend다: 신호 처리와 같은 차례(뮤텍스)로 돈다. 화면은 `viewModelScope.launch`에서 부른다.
 */
interface SessionController {
    /** 화면이 그릴 주행 상태. 신호(약 10Hz)와 엔진 출력마다 바뀐다. */
    val state: StateFlow<DrivingState>

    /** 한 번씩 알리는 일(시작·끝·단계 통과·취소). 구독자가 없을 때 온 알림은 사라진다 — 화면 이동은 앱 뿌리가 늘 듣는다. */
    val events: Flow<SessionEvent>

    /**
     * 준비 세션을 둔다(session.js `prepare`, PR-1·CR-1). 빠진 칸을 채운다: 회차 번호 = 프로필 `sessionCount[mode]` + 1, 모드는 1~8로.
     * 오늘 과제([DrivingState.today])를 다시 계산한다. 회차가 진행 중이면 바꾸지 않고 지금 준비 세션을 돌려준다.
     */
    suspend fun prepare(prep: PrepSession): PrepSession?

    /** 준비 세션 고치기(PR-1 날씨 칩·내 설정·주차 종류 등). 준비 세션이 없거나 회차가 진행 중이면 null. */
    suspend fun updatePrep(transform: (PrepSession) -> PrepSession): PrepSession?

    /** 준비 세션을 버린다(session.js `drop` — PR-1 밖 정차 화면으로 감. 그 뒤 D는 DR-0). 버렸으면 true. */
    suspend fun drop(): Boolean

    /**
     * PR-1에 들어올 때(session.js `enterPredrive`): 오늘 과제 읽기([readTasks], [readTasks]가 false면 건너뜀) → [onEnter](변형의
     * 정차 중 말, 준비 세션마다 한 번) → 조수석이 이미 앉아 있으면 L1 → 사이드브레이크를 못 읽으면 PB1 질문. 모두 준비 세션마다 한 번.
     */
    suspend fun enterPredrive(readTasks: Boolean = true, onEnter: (() -> Unit)? = null): Boolean

    /** 오늘 과제를 읽는다('p.tasks'). 과제가 없거나 준비 세션이 없으면 false. */
    suspend fun readTasks(): Boolean

    /** 조수석에 사람이 앉음(L1, 설계 6.3.1) — 착석 신호를 못 읽을 때 PR-1 [탔어요]도 부른다. 준비 세션마다 한 번. */
    suspend fun passengerAboard(): Boolean

    /** 사이드브레이크를 못 읽을 때 한 번 묻는다(PB1 'pb1.ask'). 전달 방식 X면 묻지 않는다. */
    suspend fun askHandbrake(): Boolean

    /** PR-1 [풀었어요](PB1 확인). */
    suspend fun confirmHandbrake(): Boolean

    /** PR-1 거울 점검(A5) 체크. 바뀐 값. 준비 세션이 없으면 false. */
    suspend fun setMirror(on: Boolean): Boolean

    /**
     * PR-1 [내비로 보내고 출발](session.js `send`): 고른 코스·장소를 내비에 넣고(시연은 시나리오도 불러 자동 재생) 토스트 글을 돌려준다.
     * 코스·장소가 없거나 보낼 수 없으면 null. 보낸 뒤 [DrivingState.sent]가 참이 된다.
     * 진짜 차 자료 출처면 차 내비(NaviHelper)에 좌표로 안내를 부탁한다 — 좌표가 없는 팀 코스이거나 내비에 붙지 않았으면 보내지 않고
     * 까닭 글을 돌려준다([SendResult.sent] 거짓).
     */
    suspend fun send(): SendResult?

    /** 화면 사건을 엔진으로(session.js `input` — 소리 끄기·PB 확인·운전자 질문·주차 다음·언덕 …). 회차가 없으면 null. */
    suspend fun input(event: SessionInput): StepOutput?

    /** 소리 끄기(DR-1). [on]이 null이면 바꾼다. 회차가 끝나면 다시 켜진다(결정-49). 회차가 없으면 null. */
    suspend fun toggleMute(on: Boolean? = null): Boolean?

    /** DR-3 [이어서 달리기]. 기어를 D로 바꾸면 이어 달린다. 중간 정차가 아니면 false. */
    suspend fun continueHere(): Boolean

    /** DR-3 [여기서 마치기]: P 마무리를 바로 돌린 뒤 도착 안 함으로 저장한다. 저장한 기록 id(실패하면 null). */
    suspend fun endHere(): String?

    /**
     * 기록 없이 끝낸다(session.js `cancel` — PK-3 [그만하기] 등). 회차 수·진행은 그대로. 준비 세션도 버린다.
     * [SessionEvent.Cancelled]를 보낸다. 화면 이동은 부른 쪽이 한다. 끝낼 것이 없으면 false.
     */
    suspend fun cancel(): Boolean

    /** 이번 회차(없으면 준비 세션)에서 그 항목의 전달 방식(session.js `itemValue`). 준비 세션도 없으면 [Delivery.NOW]. */
    fun itemValue(item: String): Delivery

    /** 그 모드 오늘 과제(PR-1·DR-1 기본 히어로, session.js `todayTasks`). 못 읽는 신호 때문에 항목이 모두 꺼지는 과제는 뺀다. */
    suspend fun todayTasks(mode: Int): List<TodayTask>

    companion object {
        /** 마무리는 P 표본 30개(3 sim초) 이상 — PB2가 2.9초에 정한다(session.js `P_MIN`). */
        const val P_MIN = 30

        /** P 때 말이 밀려도 길어야 표본 60개(6 sim초)에서 끝낸다(session.js `P_MAX`). */
        const val P_MAX = 60

        /** 마지막 코치 문장을 주행 화면에 보이는 sim초(session.js `SAY_HOLD`, 설계 4.2 DR-1). */
        const val SAY_HOLD = 8.0
    }
}

/** PR-1 [내비로 보내고 출발] 결과 (session.js `send`가 돌려주는 `{ summary, toast }`의 토스트 부분). */
data class SendResult(
    /** '내비에 A 코스를 넣었어요 · 경유지 2곳' · '내비에 OO공원을 넣었어요' · (보내지 못함) 까닭 글. */
    val toast: String,
    /** 내비에 넣었나. 거짓이면 [toast]는 보내지 못한 까닭이다(진짜 차: 좌표 없는 팀 코스 · 내비 연결 안 됨). */
    val sent: Boolean = true,
)

/** 한 번씩 알리는 세션 일 (프로토타입 버스 사건 'app:sessionStart'·'app:sessionEnd'·'app:sessionCancel'). */
sealed interface SessionEvent {
    /** 회차 시작('app:sessionStart'). */
    data class Started(
        val mode: Int,
        val noInMode: Int,
        val stage: Int?,
        val courseId: String? = null,
        val placeId: String? = null,
        val presetId: String? = null,
        val startedAt: String? = null,
    ) : SessionEvent

    /**
     * 회차가 끝나 기록이 저장됨('app:sessionEnd') → 그 모드 리뷰 화면(없으면 RV-1). 뒤로 스택은 [홈, 리뷰].
     * 저장하지 못했으면 [recordId]가 null → 홈.
     */
    data class Finished(val recordId: String?, val mode: Int, val arrived: Boolean) : SessionEvent

    /** 이번 회차로 지금 단계를 통과함(applyProgress `passed`). [graduated]면 4단계 통과(졸업). */
    data class StagePassed(val stage: Int, val graduated: Boolean, val nextStage: Int?, val recordId: String?) : SessionEvent

    /** 기록 없이 끝냄('app:sessionCancel'). [active]면 달리던 회차, 아니면 준비 세션만. */
    data class Cancelled(val mode: Int, val noInMode: Int?, val active: Boolean) : SessionEvent
}
