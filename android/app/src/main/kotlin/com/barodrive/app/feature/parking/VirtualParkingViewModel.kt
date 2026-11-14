package com.barodrive.app.feature.parking

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.R
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.data.repo.StoreRules
import com.barodrive.app.data.repo.setParkingDone
import com.barodrive.app.session.api.SessionController
import com.barodrive.core.model.Gear
import com.barodrive.core.model.ParkingResult
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.parking.ParkInput
import com.barodrive.core.sim.parking.ParkPose
import com.barodrive.core.sim.parking.ParkingPractice
import com.barodrive.core.sim.parking.VirtualParking as ParkModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** PK-2에서 화면 밖으로 할 일. */
sealed interface VirtualParkingEffect {
    data class Toast(@param:StringRes val text: Int) : VirtualParkingEffect

    /** PK-1로(뒤에 PK-1이 있으면 돌아가고, 없으면 바꿔 끼움 — parking-virtual.js `toPK1`). */
    data class ToSelect(val type: String, val side: String) : VirtualParkingEffect
}

/**
 * PK-2 세워 두고 연습 (프로토타입 parking-virtual.js, 설계 4.2 PK-2·3.2·7.9, 결정-58·59). P에서 진짜 핸들(앞바퀴각)·브레이크로 화면 속 차를
 * 움직인다. 모델은 시뮬레이터의 가상 주차 모델([ParkModel]) 그대로이고, 화면이 열려 있는 동안 0.1초 틱마다 지금 신호의 핸들·브레이크와
 * 가상 기어로 한 걸음 나아간다(화면 프레임이 [onFrame]으로 실제 시간을 넘기고, 틱 사이는 이어 그린다).
 *
 * - 기어가 P를 벗어나면 바로 멈춘다(주행 덮개가 덮음, 기록 없음). P로 돌아오면 PK-1로 간다(설계 4.2 PK-2 '돌아오면 PK-1').
 * - 기어를 못 읽으면 차가 움직이는 동안(0.5km/h 넘게) 화면 속 차를 세워 둔다. 차종이 바뀌면 그 크기로 처음부터.
 * - 성공하면 모드 6 기록 하나(`kind: virtualParking`)와 프로필 `parking.done[키]`, [그만하기]는 그사이 움직였을 때만 기록(결정사항 E T5a-7).
 */
@HiltViewModel
class VirtualParkingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val session: SessionController,
    private val profiles: ProfileRepository,
    private val records: RecordRepository,
    private val clock: Clock,
) : ViewModel() {
    val type: String
    val side: String

    init {
        val c = ParkModel.parse(ParkModel.key(savedStateHandle.get<String>("type"), savedStateHandle.get<String>("side")))
        type = c?.first ?: ParkingSpot.PARALLEL
        side = c?.second ?: ParkingSpot.RIGHT
    }

    private val key: String = ParkModel.key(type, side)
    private var snap: VehicleSnapshot? = session.state.value.snapshot
    private var modelId: String = snap?.model ?: DEFAULT_MODEL
    private var inst: ParkingPractice = ParkModel.create(type, side, modelId)
    private var vgear = "D"
    private var prev: ParkPose = poseOf(inst)
    private var cur: ParkPose = prev
    private var run = Run()
    private var lastStatus: String = inst.state.status
    private var lastBlocked: String? = null
    private var interrupted = false
    private var accum = 0.0
    private var lastFrame: Long? = null

    private val _uiState = MutableStateFlow(state(1.0))
    val uiState: StateFlow<VirtualParkingUiState> = _uiState.asStateFlow()

    private val _effects = Channel<VirtualParkingEffect>(Channel.BUFFERED)
    val effects: Flow<VirtualParkingEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch { session.state.collect { onSignals(it.snapshot) } }
    }

    /** 이번 연습(기록하지 않은 동안): 닿은 수 · [처음부터] 수 · 처음 움직인 때. */
    private data class Run(val touches: Int = 0, val restarts: Int = 0, val startedAt: ZonedDateTime? = null)

    /** 새 신호: 기어가 P를 벗어나면 멈춤 표시, P로 돌아오면 PK-1. 차종이 바뀌면 그 크기로 처음부터. */
    private fun onSignals(s: VehicleSnapshot?) {
        snap = s
        val gear = s?.gear
        if (gear != null && gear != Gear.P) interrupted = true
        if (interrupted && gear == Gear.P) {
            interrupted = false
            _effects.trySend(VirtualParkingEffect.ToSelect(type, side))
            return
        }
        val model = s?.model
        if (model != null && model != modelId) {
            modelId = model
            if (run.startedAt != null) run = run.copy(restarts = run.restarts + 1)
            build()
        }
    }

    /** 화면 프레임(실제 시간 나노초): 0.1초 틱을 채운 만큼 나아가고 틱 사이를 이어 그린다(parking-virtual.js `frame`·`clock.alpha`). */
    fun onFrame(frameNanos: Long) {
        val last = lastFrame
        lastFrame = frameNanos
        if (last == null) return
        accum += ((frameNanos - last) / NANOS).coerceIn(0.0, MAX_FRAME_SEC)
        var steps = 0
        while (accum >= ParkModel.DT - 1e-9 && steps < MAX_STEPS) {
            tick(ParkModel.DT)
            accum -= ParkModel.DT
            steps++
        }
        if (steps == MAX_STEPS) accum = 0.0
        publish((accum / ParkModel.DT).coerceIn(0.0, 1.0))
    }

    /** 화면을 떠남(프레임이 멈춤) — 다음 프레임은 처음부터 시간을 잰다. */
    fun onPause() {
        lastFrame = null
        accum = 0.0
    }

    /** 틱 하나 (parking-virtual.js `onTick`). 시험은 이것을 바로 부른다. */
    fun tick(dt: Double = ParkModel.DT) {
        val s = snap ?: return
        if (interrupted) return
        val parked = s.gear == Gear.P || (s.gear == null && abs(s.speed ?: 0.0) <= MOVING_KMH)
        prev = cur
        if (parked) inst.step(dt, ParkInput(steer = s.steer ?: 0.0, brake = s.brake == true, dir = vgear))
        val st = inst.state
        cur = poseOf(inst)
        if (st.moved && run.startedAt == null) run = run.copy(startedAt = ZonedDateTime.now(clock))
        if (st.status != lastStatus) {
            if (st.status == VirtualParkingUiState.STATUS_TOUCHED) {
                run = run.copy(touches = run.touches + 1)
                _effects.trySend(VirtualParkingEffect.Toast(VirtualParkingText.touch(st.touch)))
            }
            if (st.status == VirtualParkingUiState.STATUS_DONE) record(RESULT_SUCCESS)
            lastStatus = st.status
        }
        if (st.blocked != lastBlocked) {
            if (st.blocked == BLOCKED_EDGE) _effects.trySend(VirtualParkingEffect.Toast(R.string.pk2_edge))
            lastBlocked = st.blocked
        }
        publish(1.0)
    }

    /** 가상 기어 [전진 D | 후진 R]. */
    fun setGear(g: String) {
        val want = if (g == "R") "R" else "D"
        if (vgear == want) return
        vgear = want
        publish(1.0)
    }

    /** [처음부터]·[다시 하기] (parking-virtual.js `restart`). */
    fun restart() {
        if (run.startedAt != null) run = run.copy(restarts = run.restarts + 1)
        inst.reset()
        resetPose()
        publish(1.0)
    }

    /** [그만하기]: 그사이 움직였으면 '그만했어요' 기록을 남기고 PK-1로(결정사항 E T5a-7). */
    fun stop() {
        if (run.startedAt != null || inst.state.moved) record(RESULT_STOPPED)
        _effects.trySend(VirtualParkingEffect.ToSelect(type, side))
    }

    /** [다른 주차] → PK-1. */
    fun other() {
        _effects.trySend(VirtualParkingEffect.ToSelect(type, side))
    }

    private fun build() {
        inst = ParkModel.create(type, side, modelId)
        resetPose()
        publish(1.0)
    }

    private fun resetPose() {
        vgear = "D"
        prev = poseOf(inst)
        cur = prev
        lastStatus = inst.state.status
        lastBlocked = null
    }

    /** 세워 두고 연습 기록 하나 (parking-virtual.js `record`, 결정사항 E T5a-7). 성공이면 프로필 완료 표시도. */
    private fun record(result: String) {
        val st = inst.state
        val now = ZonedDateTime.now(clock)
        val parking = ParkingResult(
            type = ParkingType.entries.firstOrNull { it.code == type } ?: ParkingType.PARALLEL,
            side = TurnDir.fromCode(side) ?: TurnDir.RIGHT,
            key = key,
            model = inst.car.id,
            sec = jsRound(st.sec).toDouble(),
            virtual = true,
            result = result,
            switches = st.switches,
            wheelAngle = st.result?.wheelAngle ?: (jsRound(st.steer * 10) / 10.0),
            wheelStraight = st.result?.wheelStraight ?: (abs(st.steer) <= ParkModel.WHEEL_OK),
            touches = run.touches,
            restarts = run.restarts,
        )
        val rec = SessionRecord(
            kind = RecordKind.VIRTUAL_PARKING,
            mode = PARKING_MODE,
            startedAt = StoreRules.isoLocal(run.startedAt ?: now),
            durationSec = jsRound(st.sec).toDouble(),
            distanceM = 0.0,
            arrived = false,
            fewEvents = true,
            parking = parking,
        )
        run = Run()
        viewModelScope.launch {
            if (result == RESULT_SUCCESS) profiles.setParkingDone(key)
            records.add(rec)
        }
    }

    private fun publish(alpha: Double) {
        val a = if (prev === cur) 1.0 else alpha
        val pose = ParkPose(
            prev.x + (cur.x - prev.x) * a,
            prev.y + (cur.y - prev.y) * a,
            lerpAngle(prev.h, cur.h, a),
        )
        _uiState.value = state(pose)
    }

    private fun state(alpha: Double): VirtualParkingUiState = state(if (alpha >= 1.0) cur else prev)

    private fun state(pose: ParkPose): VirtualParkingUiState {
        val st = inst.state
        val max = inst.car.maxSteer
        return VirtualParkingUiState(
            type = type,
            side = side,
            scene = inst.scene,
            car = inst.car,
            pose = pose,
            steer = st.steer.coerceIn(-max, max),
            status = st.status,
            touch = st.touch,
            armed = st.armed,
            moved = st.moved,
            tip = st.tip,
            vgear = vgear,
            result = st.result,
        )
    }

    private companion object {
        const val DEFAULT_MODEL = "avante"
        const val PARKING_MODE = 6
        const val RESULT_SUCCESS = "success"
        const val RESULT_STOPPED = "stopped"
        const val BLOCKED_EDGE = "edge"
        const val MOVING_KMH = 0.5
        const val NANOS = 1_000_000_000.0

        /** 앱이 잠깐 멈췄다 돌아와도 한꺼번에 몰아서 가지 않게(실제 시간 한 프레임 최대). */
        const val MAX_FRAME_SEC = 0.25
        const val MAX_STEPS = 3

        fun poseOf(p: ParkingPractice) = ParkPose(p.state.x, p.state.y, p.state.h)

        /** 짧은 쪽으로 각도를 잇는다(parking-virtual.js `lerpAngle`). */
        fun lerpAngle(a: Double, b: Double, t: Double): Double {
            var d = b - a
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            return a + d * t
        }
    }
}
