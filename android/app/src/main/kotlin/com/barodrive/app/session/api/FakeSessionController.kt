package com.barodrive.app.session.api

import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.Mode
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TodayTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 메모리 안 [SessionController] — 미리보기·화면 테스트용. 엔진·신호 없이 준비 세션과 버튼 동작만 흉내 낸다
 * (session.js의 준비 세션 규칙: 회차 중엔 준비를 바꾸지 않음, 정차 중 말은 준비 세션마다 한 번).
 * 주행 상태는 [setState]로 직접 만든다. 받은 입력은 [inputs], 보낸 알림은 [events]로 본다.
 *
 * @param initial 처음 상태(미리보기 값).
 * @param today [prepare] 때 넣을 오늘 과제.
 * @param items [itemValue]가 돌려줄 전달 방식(없으면 [Delivery.NOW]).
 */
class FakeSessionController(
    initial: DrivingState = DrivingState(),
    private val today: List<TodayTask> = emptyList(),
    private val items: Map<String, Delivery> = emptyMap(),
) : SessionController {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<DrivingState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    override val events: Flow<SessionEvent> = _events.asSharedFlow()

    /** 받은 화면 사건, 받은 순서. */
    val inputs: MutableList<SessionInput> = mutableListOf()

    /** 정차 중에 '말한' 대사 키(p.tasks·l1·pb1.ask …), 말한 순서. */
    val linesSaid: MutableList<String> = mutableListOf()

    private var recordNo = 0

    /** 다음 [send]가 내비에 넣지 못하고 돌려줄 까닭 글(테스트 — 진짜 차의 '좌표 없는 코스' 등). null이면 보낸다. */
    var refuseSend: String? = null

    /** 상태를 직접 바꾼다(미리보기·테스트). */
    fun setState(transform: (DrivingState) -> DrivingState) = _state.update(transform)

    /** 알림을 직접 보낸다(테스트). */
    suspend fun emit(event: SessionEvent) = _events.emit(event)

    override suspend fun prepare(prep: PrepSession): PrepSession? {
        val s = _state.value
        if (s.active) return s.prep
        val p = prep.copy(mode = prep.mode.takeIf { it in 1..Mode.entries.size } ?: 1, noInMode = prep.noInMode?.takeIf { it > 0 } ?: 1)
        _state.update {
            it.copy(phase = SessionPhase.READY, activePhase = null, prep = p, mode = p.mode, noInMode = p.noInMode, today = today, sent = false)
        }
        return p
    }

    override suspend fun updatePrep(transform: (PrepSession) -> PrepSession): PrepSession? {
        val s = _state.value
        val p = s.prep ?: return null
        if (s.active) return null
        val next = transform(p)
        _state.update { it.copy(prep = next, mode = next.mode, noInMode = next.noInMode) }
        return next
    }

    override suspend fun drop(): Boolean {
        val s = _state.value
        if (s.active || s.prep == null) return false
        _state.update { it.copy(phase = SessionPhase.IDLE, prep = null, mode = null, noInMode = null, today = emptyList(), sent = false) }
        return true
    }

    override suspend fun enterPredrive(readTasks: Boolean, onEnter: (() -> Unit)?): Boolean {
        if (prepOrNull() == null) return false
        if (readTasks) readTasks()
        onEnter?.invoke()
        val snap = _state.value.snapshot
        if (snap?.passenger == true) passengerAboard()
        if (snap != null && snap.handbrake == null) askHandbrake()
        return true
    }

    override suspend fun readTasks(): Boolean {
        val p = prepOrNull() ?: return false
        if (_state.value.today.isEmpty()) return false
        linesSaid += "p.tasks"
        setPrep(p.copy(lines = p.lines.copy(tasksRead = true)))
        return true
    }

    override suspend fun passengerAboard(): Boolean {
        val p = prepOrNull() ?: return false
        if (p.lines.l1Said) return false
        if (itemValue("L1") == Delivery.NOW) linesSaid += "l1"
        setPrep(p.copy(lines = p.lines.copy(l1Said = true)))
        return true
    }

    override suspend fun askHandbrake(): Boolean {
        val p = prepOrNull() ?: return false
        if (itemValue("PB1") == Delivery.OFF || "pb1.ask" in linesSaid) return false
        linesSaid += "pb1.ask"
        return true
    }

    override suspend fun confirmHandbrake(): Boolean {
        val p = prepOrNull() ?: return false
        if (p.checks.pbConfirmed) return false
        setPrep(p.copy(checks = p.checks.copy(pbConfirmed = true), preEvents = p.preEvents + JudgeEvent(item = "PB1", data = EventData(confirmed = true))))
        return true
    }

    override suspend fun setMirror(on: Boolean): Boolean {
        val p = prepOrNull() ?: return false
        setPrep(p.copy(checks = p.checks.copy(mirror = on)))
        return on
    }

    override suspend fun send(): SendResult? {
        val p = prepOrNull() ?: return null
        val target = p.courseId ?: p.placeId ?: p.navi?.name ?: return null
        refuseSend?.let { return SendResult(it, sent = false) }
        _state.update { it.copy(sent = true) }
        return SendResult(toast = "내비에 ${target}를 넣었어요")
    }

    override suspend fun input(event: SessionInput): StepOutput? {
        if (!_state.value.active) return null
        inputs += event
        when (event) {
            SessionInput.Mute -> _state.update { it.copy(muted = true) }
            SessionInput.Unmute -> _state.update { it.copy(muted = false) }
            else -> Unit
        }
        return _state.value.output ?: StepOutput()
    }

    override suspend fun toggleMute(on: Boolean?): Boolean? {
        if (!_state.value.active) return null
        val want = on ?: !_state.value.muted
        input(if (want) SessionInput.Mute else SessionInput.Unmute)
        return _state.value.muted
    }

    override suspend fun continueHere(): Boolean {
        if (!_state.value.stopped) return false
        _state.update { it.copy(dr3Continue = true) }
        return true
    }

    override suspend fun endHere(): String? {
        val s = _state.value
        if (!s.active) return null
        val id = "sess-fake-${++recordNo}"
        _state.value = DrivingState(snapshot = s.snapshot, gear = s.gear, speed = s.speed, blinker = s.blinker, lastRecordId = id)
        _events.emit(SessionEvent.Finished(recordId = id, mode = s.mode ?: 1, arrived = false))
        return id
    }

    override suspend fun cancel(): Boolean {
        val s = _state.value
        if (!s.active && s.prep == null) return false
        _state.value = DrivingState(snapshot = s.snapshot, gear = s.gear, speed = s.speed, blinker = s.blinker, lastRecordId = s.lastRecordId)
        _events.emit(SessionEvent.Cancelled(mode = s.mode ?: 1, noInMode = s.noInMode, active = s.active))
        return true
    }

    override fun itemValue(item: String): Delivery = items[item] ?: Delivery.NOW

    override suspend fun todayTasks(mode: Int): List<TodayTask> = today

    private fun prepOrNull(): PrepSession? = _state.value.takeIf { !it.active }?.prep

    private fun setPrep(p: PrepSession) = _state.update { it.copy(prep = p) }
}
