package com.barodrive.app.feature.parking

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.barodrive.app.R
import com.barodrive.app.session.api.DrivingState
import com.barodrive.core.engine.content.ParkingGuide as ParkingSteps
import com.barodrive.core.engine.content.ParkingVisualKind
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Mode
import com.barodrive.core.model.ParkingHint
import kotlin.math.abs
import com.barodrive.core.engine.content.ParkingVisual as TableVisual
import com.barodrive.core.model.ParkingVisual as HintVisual

/** PK-3 그림 종류 (주차 단계 표 `visual.kind`, 목업 s08b): 비상등 · 핸들 · 기어 · 마무리(P + 사이드브레이크). */
enum class GuideArt { HAZARD, WHEEL, GEAR, FINAL }

/**
 * PK-3 단계 그림 (parking-guide.js `visual`). [rot] = 그림 속 핸들 회전°(오른쪽 +, 왼쪽 칸은 단계 표가 이미 뒤집음),
 * [gear] = 기어 그림 글자, [label] = 그림 아래 민트 글('오른쪽 끝까지' · '비상등 켜기' …).
 */
@Immutable
data class GuideVisual(
    val art: GuideArt = GuideArt.HAZARD,
    val rot: Float = 0f,
    val gear: String = "R",
    val label: String = "",
)

/** PK-3 '다음 단계 신호' 줄을 바꾸고 [다음 단계]를 막는 까닭 (parking-guide.js WAIT_TEXT·DONE_P_TEXT·DONE_TEXT). */
enum class GuideBlock(@param:StringRes val text: Int) {
    /** 회차 전(PR-1 [주차 안내 시작] 직후 P): '기어를 D로 바꾸면 안내를 시작해요'. */
    WAIT(R.string.pk3_wait),

    /** 다 마치고 P: '결과를 정리하고 있어요'. */
    DONE_PARKED(R.string.pk3_done_parked),

    /** 다 마쳤는데 아직 P가 아님: '기어를 P로 바꾸면 결과를 보여 드려요'. */
    DONE_MOVING(R.string.pk3_done_moving),
}

/**
 * PK-3 실제 주차 안내 화면 값 (프로토타입 parking-guide.js `viewOf`·`update`, 설계 4.2 PK-3, 목업 s08b).
 * 회차 중이면 엔진 힌트 `hints.parking`을 그대로, 회차 전이면 단계 표 1단계를 그린다([live] = false).
 *
 * @property type 'parallel'·'back'·'front'. [side] 'L'·'R' — 앱 바 제목 '평행 · 오른쪽 칸'.
 * @property step 엔진 단계 번호(1부터, [다음 단계]가 넘기는 단계). [index] 0부터(단계 막대 지금 칸).
 * @property text 단계 문장(표 A-5, `{dir}`을 채움). [signalText] 그 단계의 신호 줄 — [block]이 있으면 그 글을 대신 보인다.
 * @property tip 기준점 꿀팁 번호(0·1 = 표 A-6, 2 = 바퀴). [buttonLabel] '다음 단계' 또는 마지막 단계·다 마침 '주차 마쳤어요'.
 * @property gear 시연 신호의 기어 그대로(설계 4.2 PK-3 '기어 칩은 시연 신호 그대로'). [moving] 속도 0.5km/h 이상.
 */
@Immutable
data class ParkingGuideUiState(
    val type: String = ParkingSpot.PARALLEL,
    val side: String = ParkingSpot.RIGHT,
    val live: Boolean = false,
    val step: Int = 1,
    val total: Int = 8,
    val index: Int = 0,
    val last: Boolean = false,
    val done: Boolean = false,
    val text: String = "",
    val visual: GuideVisual = GuideVisual(),
    val signalText: String = "",
    val block: GuideBlock? = GuideBlock.WAIT,
    val tip: Int? = 0,
    val buttonLabel: String = "",
    val gear: Gear? = null,
    val moving: Boolean = false,
) {
    /** 단계 막대 숫자(다 마치면 전체). */
    val shownStep: Int get() = if (done) total else step

    /** [다음 단계]를 누를 수 있나(회차 중이고 아직 다 마치지 않음). */
    val canPress: Boolean get() = block == null
}

/** PK-3 [그만하기]가 할 일 (parking-guide.js `stop`). */
enum class GuideStop {
    /** 다 마치고 P에서 마무리 중(몇 초 뒤 PK-4) — 끝낸 회차를 버리지 않고 토스트만. */
    FINISHING,

    /** 움직이는 중: 기록 없이 끝내고 이 화면을 닫는다 → 주행 덮개(DR-0), P가 되면 직전 정차 화면. */
    MOVING,

    /** P: 기록 없이 끝내고 PK-1(고른 종류·방향 그대로, 뒤로 = 홈). */
    PARKED,
}

/** 세션 상태 → PK-3 화면 값. 화면은 엔진 힌트만 그린다(판정 없음, 설계 1.4). */
internal object ParkingGuideMapper {
    private const val MOVING_KMH = 0.5

    /** [routeType]·[routeSide] = 경로 인자(회차도 준비 세션도 주차 종류를 모를 때). */
    fun map(s: DrivingState, routeType: String?, routeSide: String?): ParkingGuideUiState {
        val snap = s.snapshot
        val gear = snap?.gear
        val moving = snap?.speed?.let { abs(it) >= MOVING_KMH } == true
        val hint = liveHint(s)
        val button = ParkingSteps.button
        if (hint != null) {
            val block = when {
                !hint.done -> null
                gear == Gear.P -> GuideBlock.DONE_PARKED
                else -> GuideBlock.DONE_MOVING
            }
            return ParkingGuideUiState(
                type = hint.type.code,
                side = hint.side.code,
                live = true,
                step = hint.step,
                total = hint.total,
                index = hint.index,
                last = hint.last,
                done = hint.done,
                text = hint.text.orEmpty(),
                visual = visualOf(hint.visual),
                signalText = hint.signalText.orEmpty(),
                block = block,
                tip = hint.tip,
                buttonLabel = if (hint.last || hint.done) button.last else button.next,
                gear = gear,
                moving = moving,
            )
        }
        val c = choice(s, routeType, routeSide)
        val steps = ParkingSteps.steps(c.type, c.side)
        val first = steps.first()
        return ParkingGuideUiState(
            type = c.type,
            side = c.side,
            live = false,
            step = 1,
            total = steps.size,
            index = 0,
            last = steps.size == 1,
            done = false,
            text = first.text,
            visual = visualOf(first.visual),
            signalText = first.signalText,
            block = GuideBlock.WAIT,
            tip = first.tip,
            buttonLabel = if (steps.size == 1) button.last else button.next,
            gear = gear,
            moving = moving,
        )
    }

    /** 진행 중인 모드 6 회차의 엔진 힌트(parking-guide.js `viewOf`). */
    fun liveHint(s: DrivingState): ParkingHint? = if (s.active && s.mode == Mode.PARKING.no) s.output?.hints?.parking else null

    /**
     * 지금 연습하는 종류·방향 (parking-guide.js `choiceOf`): 진행 중 회차의 엔진 힌트 → 준비 세션 `prep.parking`(모드 6) → 경로 인자.
     */
    fun choice(s: DrivingState, routeType: String?, routeSide: String?): ParkingSpotChoice {
        val hint = if (s.active) s.output?.hints?.parking else null
        if (hint != null) return ParkingSpotChoice(hint.type.code, hint.side.code)
        val prep = s.prep
        val setup = prep?.parking
        if (prep != null && prep.mode == Mode.PARKING.no && setup != null) {
            return ParkingSteps.norm(setup.type.code, setup.side.code).let { ParkingSpotChoice(it.type, it.side) }
        }
        return ParkingSteps.norm(routeType, routeSide).let { ParkingSpotChoice(it.type, it.side) }
    }

    /** [그만하기] (parking-guide.js `stop`): P(기어를 못 읽거나 신호가 없으면 P로 봄)인지와 다 마쳤는지로 정한다. */
    fun stopOf(s: DrivingState, ui: ParkingGuideUiState): GuideStop {
        val gear = s.snapshot?.gear
        val parked = gear == null || gear == Gear.P
        return when {
            parked && ui.live && ui.done -> GuideStop.FINISHING
            !parked -> GuideStop.MOVING
            else -> GuideStop.PARKED
        }
    }

    /** 엔진 힌트 그림(없으면 비상등, parking-guide.js `visual`의 `v || { kind: 'hazard', label: '' }`). */
    fun visualOf(v: HintVisual?): GuideVisual {
        if (v == null) return GuideVisual(GuideArt.HAZARD, label = "")
        val art = when (v.kind) {
            "wheel" -> GuideArt.WHEEL
            "gear" -> GuideArt.GEAR
            "final" -> GuideArt.FINAL
            else -> GuideArt.HAZARD
        }
        return GuideVisual(art = art, rot = (v.rot ?: 0.0).toFloat(), gear = v.gear?.name ?: "R", label = v.label.orEmpty())
    }

    /** 단계 표 그림(회차 전). */
    fun visualOf(v: TableVisual): GuideVisual {
        val art = when (v.kind) {
            ParkingVisualKind.WHEEL -> GuideArt.WHEEL
            ParkingVisualKind.GEAR -> GuideArt.GEAR
            ParkingVisualKind.FINAL -> GuideArt.FINAL
            ParkingVisualKind.HAZARD -> GuideArt.HAZARD
        }
        return GuideVisual(art = art, rot = (v.rot ?: 0).toFloat(), gear = v.gear ?: "R", label = v.label)
    }
}

/** 정리한 주차 종류·방향('parallel'·'back'·'front', 'L'·'R'). */
@Immutable
data class ParkingSpotChoice(val type: String, val side: String)
