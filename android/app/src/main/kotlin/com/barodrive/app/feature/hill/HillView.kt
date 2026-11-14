package com.barodrive.app.feature.hill

import androidx.compose.runtime.Immutable
import com.barodrive.app.R
import com.barodrive.app.feature.parking.ModeLines
import com.barodrive.app.feature.parking.TextRes
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.ui.components.StepState
import com.barodrive.core.engine.content.HillGuide
import com.barodrive.core.engine.content.Words
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillHint
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.abs

/** 연습 id ('up'·'down'·'park' — hill.json·대사 키와 같은 글자). */
val HillPractice.id: String get() = name.lowercase()

/** 상황 id ('down'·'up'·'none'). */
val HillSlope.id: String get() = name.lowercase()

/** 신호 안내 줄 (hill.js `view` sig). 콘텐츠 글이거나 화면 글. */
sealed interface HillSig {
    /** 단계 표 글('차가 멈추면 넘어가요') 또는 계속 밟음 줄(hill.json `holdWarn`). */
    data class Text(val text: String) : HillSig

    /** 연습을 마침: '다른 연습은 멈췄을 때 위 칩으로 골라요'. */
    data object Done : HillSig

    /** 내리막 2단계인데 내비 구간에 묶이지 않음(칩으로 시작): '내리막이 끝나면 [했어요]를 눌러요'. */
    data object DownUnbound : HillSig

    /** 언덕 주차 2단계, 핸들을 못 읽음: '핸들 각도를 못 읽어요 · 돌렸으면 [했어요]를 눌러요'. */
    data object SteerOff : HillSig

    fun text(res: TextRes): String = when (this) {
        is Text -> text
        Done -> res.text(R.string.hl_sig_done)
        DownUnbound -> res.text(R.string.hl_sig_down_unbound)
        SteerOff -> res.text(R.string.hl_sig_steer_off)
    }
}

/** HL-1 그림 (hill.js `view` vis): 단계 아이콘 · 오르막 3단계 페달 · 내리막 2단계 나눠 밟기 숫자. */
sealed interface HillVis {
    /** [icon] = 프로토타입 심볼 id('i-brake' …). */
    data class Icon(val icon: String) : HillVis

    data object Pedals : HillVis

    data class Count(val n: Int) : HillVis
}

/** 단계 칸 하나 (목업 `.strip`): 번호·이름·아이콘·상태. */
@Immutable
data class HillStripCell(val n: Int, val label: String, val icon: String, val state: StepState)

/**
 * HL-2 방향 줄 (hill.js `parkView` okText). 화면 글은 [text]가 만든다.
 */
sealed interface HillWheelLine {
    /** 줄 없음(상황을 아직 안 골랐거나 앞바퀴 값이 없음). */
    data object None : HillWheelLine

    /** P에서 판정 — 앞바퀴를 못 읽음: '앞바퀴 방향을 확인하지 못했어요'. */
    data object ResultUnknown : HillWheelLine

    /** P에서 판정: '앞바퀴 오른쪽 · 방향이 맞아요' · '앞바퀴 오른쪽 · 왼쪽이 맞아요'([sideName] null = 똑바로). */
    data class Result(val sideName: String?, val ok: Boolean, val needName: String) : HillWheelLine

    /** 핸들을 못 읽음: '핸들 각도를 못 읽어요'. */
    data object SteerOff : HillWheelLine

    /** 지금 앞바퀴 똑바로: '지금 앞바퀴 똑바로 · 오른쪽으로 돌려요'. */
    data class Straight(val straightText: String, val needName: String) : HillWheelLine

    /** 지금 앞바퀴 쪽: '지금 앞바퀴 왼쪽 · 반대예요'. */
    data class Current(val sideName: String, val verdict: String) : HillWheelLine

    fun text(res: TextRes): String = when (this) {
        None -> ""
        ResultUnknown -> res.text(R.string.hl_wheel_unknown)
        is Result -> {
            val side = sideName?.let { res.text(R.string.hl_wheel_side, it) } ?: res.text(R.string.hl_wheel_straight)
            if (ok) res.text(R.string.hl_wheel_result_ok, side) else res.text(R.string.hl_wheel_result_wrong, side, needName)
        }
        SteerOff -> res.text(R.string.hl_wheel_steer_off)
        is Straight -> res.text(R.string.hl_wheel_turn, straightText, needName)
        is Current -> res.text(R.string.hl_wheel_now, sideName, verdict)
    }
}

/**
 * HL-2 값 (hill.js `parkView`): 고른 상황 · 맞는 앞바퀴 쪽 · 그림 · 방향 줄 · 까닭 · 다음 카드.
 * @property side 맞는 앞바퀴 쪽 'L'·'R'(상황을 안 골랐으면 null — 그림은 똑바로). [curb] 연석이 있는지(안 골랐으면 있음).
 * @property why 까닭(안 골랐으면 null — 화면은 '상황을 고르면 앞바퀴 방향을 알려 드려요'). [ok] 줄 색: false면 주의 색.
 */
@Immutable
data class HillParkView(
    val situation: HillSlope? = null,
    val label: String? = null,
    val side: String? = null,
    val sideText: String? = null,
    val curb: Boolean = true,
    val front: String = "",
    val why: String? = null,
    val ok: Boolean? = null,
    val line: HillWheelLine = HillWheelLine.None,
    val nextDone: Boolean = false,
)

/**
 * HL-1·HL-2 화면 모델 (프로토타입 hill.js `view(hint, snap)`). 엔진 힌트 `hints.hill`을 그대로 옮기고, 힌트가 아직 없으면 오르막 1단계.
 * @property no '3단계 · 지금 할 일' 또는 '오르막 출발 · 끝'은 [noText]. [text] 단계 문장(대사 `hl1.*`) 또는 마친 뒤 글.
 * @property tap [했어요]를 보임(화면 누름 단계). [cheer] 오르막 3단계 응원 말풍선. [gearP] 지금 기어가 P.
 */
@Immutable
data class HillView(
    val practice: HillPractice = HillPractice.UP,
    val step: Int = 1,
    val steps: Int = 4,
    val done: Boolean = false,
    val tap: Boolean = false,
    val practiceName: String = "",
    val sub: String = "",
    val text: String = "",
    val sig: HillSig = HillSig.Text(""),
    val warn: Boolean = false,
    val vis: HillVis = HillVis.Icon("i-info"),
    val cheer: Boolean = false,
    val strip: List<HillStripCell> = emptyList(),
    val park: HillParkView = HillParkView(),
    val gearP: Boolean = false,
) {
    fun noText(res: TextRes): String =
        if (done) res.text(R.string.hl_no_done, practiceName) else res.text(R.string.hl_no_step, step)
}

/** 화면 없이 계산 (hill.js `BaroApp.hill`의 `view`·`still` + 최근 코치 말 `recentSay`). */
internal object HillViews {
    private const val EPS = 1e-6
    private const val STILL_KMH = 0.5

    /** 힌트가 없을 때(hill.js `view`의 기본 H): 오르막 1단계. */
    val START = HillHint(practice = HillPractice.UP, step = 1, steps = 4, key = "hl1.up.1")

    fun view(hint: HillHint?, snap: VehicleSnapshot?, coach: Coach, honorific: Honorific?): HillView {
        val h = hint ?: START
        val pr = HillGuide.practice(h.practice.id)
        val prSteps = pr?.steps.orEmpty()
        val st = prSteps.firstOrNull { it.n == h.step } ?: prSteps.firstOrNull()
        val text = if (h.done) {
            HillGuide.text.done[h.practice.id].orEmpty()
        } else {
            ModeLines.render(h.key ?: st?.key, coach, ModeLines.vars(h.vars), honorific)
        }
        var sig: HillSig = if (h.done) HillSig.Done else HillSig.Text(st?.sig.orEmpty())
        var warn = false
        val downTwo = !h.done && h.practice == HillPractice.DOWN && h.step == 2
        if (downTwo && h.down.warn) {
            sig = HillSig.Text(HillGuide.text.holdWarn)
            warn = true
        }
        if (downTwo && !h.down.bound) sig = HillSig.DownUnbound
        if (!h.done && h.practice == HillPractice.PARK && h.step == 2 && h.park.steerOff) sig = HillSig.SteerOff
        val vis: HillVis = when {
            !h.done && h.practice == HillPractice.UP && h.step == 3 -> HillVis.Pedals
            downTwo -> HillVis.Count(h.down.presses)
            h.done -> HillVis.Icon("i-flag-check")
            else -> HillVis.Icon(st?.icon ?: "i-info")
        }
        return HillView(
            practice = h.practice,
            step = h.step,
            steps = if (h.steps > 0) h.steps else prSteps.size,
            done = h.done,
            tap = h.tap && !h.done,
            practiceName = pr?.name.orEmpty(),
            sub = pr?.sub.orEmpty(),
            text = text,
            sig = sig,
            warn = warn,
            vis = vis,
            cheer = h.cheer,
            strip = prSteps.map { s ->
                val state = when {
                    h.done || s.n < h.step -> StepState.Done
                    s.n == h.step -> StepState.Now
                    else -> StepState.Todo
                }
                HillStripCell(s.n, s.label, s.icon, state)
            },
            park = parkView(h),
            gearP = if (snap != null) snap.gear == Gear.P else h.gearP,
        )
    }

    /** HL-2: 고른 상황 · 바퀴 줄 · 다음 카드 (hill.js `parkView`). */
    fun parkView(h: HillHint): HillParkView {
        val p = h.park
        val sit = p.situation?.let { HillGuide.situation(it) }
        var ok: Boolean? = null
        var line: HillWheelLine = HillWheelLine.None
        if (h.practice == HillPractice.PARK && sit != null) {
            val res = p.result
            val w = p.wheel
            if (h.done && res != null) {
                val wheelOk = res.wheelOk
                if (wheelOk == null) {
                    line = HillWheelLine.ResultUnknown
                } else {
                    ok = wheelOk
                    line = HillWheelLine.Result(res.wheelSide?.let(::dirName), wheelOk, Words.dirName(sit.side))
                }
            } else if (p.steerOff) {
                line = HillWheelLine.SteerOff
            } else if (w != null) {
                val side = w.side
                if (side == null) {
                    ok = false
                    line = HillWheelLine.Straight(HillGuide.text.wheelStraight, sit.dirName)
                } else {
                    val good = w.ok == true
                    ok = good
                    line = HillWheelLine.Current(dirName(side), if (good) HillGuide.text.wheelOk else HillGuide.text.wheelWrong)
                }
            }
        }
        val res = p.result
        return HillParkView(
            situation = if (sit != null) p.situation else null,
            label = sit?.label,
            side = sit?.side,
            sideText = sit?.sideText,
            curb = sit?.curb ?: true,
            front = sit?.front.orEmpty(),
            why = sit?.why,
            ok = ok,
            line = line,
            nextDone = h.done && res != null && res.handbrake == HandbrakeState.ON,
        )
    }

    /** 멈춤: 속도 0.5 아래 또는 P. 차속을 못 읽으면 엔진의 신호 대기 판단(hill.js `still`). 신호가 아직 없으면 멈춤. */
    fun still(snap: VehicleSnapshot?, out: StepOutput?): Boolean {
        if (snap == null) return true
        if (snap.gear == Gear.P) return true
        val speed = snap.speed
        if (speed != null) return abs(speed) < STILL_KMH
        return out?.waiting == true
    }

    /** 기어 칩 '주행 중'(속도 0.5 이상). */
    fun moving(snap: VehicleSnapshot?): Boolean = snap?.speed?.let { abs(it) >= STILL_KMH } == true

    /** 최근 코치 말(DR-1과 같은 8 sim초). 단계 문장(HL1)은 큰 글과 같아서 말풍선에 다시 쓰지 않는다(hill.js `recentSay`). */
    fun recentSay(s: DrivingState): Utterance? {
        val say = s.lastSay ?: return null
        if (say.text.isEmpty() || say.item == "HL1") return null
        val t = s.output?.t ?: s.snapshot?.t ?: 0.0
        return if (t >= say.t - EPS && t - say.t <= SessionController.SAY_HOLD + EPS) say else null
    }

    private fun dirName(d: TurnDir): String = Words.dirName(d.code)
}
