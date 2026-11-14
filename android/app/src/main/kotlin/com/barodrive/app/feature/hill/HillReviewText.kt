package com.barodrive.app.feature.hill

import androidx.compose.runtime.Immutable
import com.barodrive.app.R
import com.barodrive.app.feature.parking.ModeLines
import com.barodrive.app.feature.parking.TextRes
import com.barodrive.app.feature.parking.jsRound
import com.barodrive.app.feature.parking.num1
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.content.HillGuide
import com.barodrive.core.engine.content.Words
import com.barodrive.core.model.Coach
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillDownSummary
import com.barodrive.core.model.HillUpSummary
import com.barodrive.core.model.NextTask
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.WeatherType

/** RV-6 결과 카드 상태 (hill.js `resultModel` state): 잘함 · 아쉬움 · 하지 않음(또는 판정 못 함). */
enum class HillResultState { OK, WARN, NONE }

/** RV-6 결과 카드 하나: 오르막 출발 · 내리막 감속 · 언덕 주차. [icon]은 하지 않았을 때 보이는 카드 아이콘(심볼 id). */
@Immutable
data class HillResultCard(val id: String, val title: String, val icon: String, val state: HillResultState, val text: String)

/** RV-6 결과 카드 셋과 안전 줄(밀림이 있었을 때). */
@Immutable
data class HillResultModel(val cards: List<HillResultCard>, val retry: Boolean, val retryText: String)

/** 급제동 한 건 (review.js `model` f2): 글 · 본인 확인. */
@Immutable
data class HillF2Row(val id: String, val text: String, val choice: F2Choice?)

/** 리뷰 날씨 팁 줄 하나: 글과 아이콘(심볼 id). */
@Immutable
data class HillTipLine(val text: String, val icon: String)

/** RV-6 글 (프로토타입 hill.js `resultModel` + review.js `taskVars`·`heroLine`·`nextOf`·`f2Text`·`tipsOf`). 화면 없이 계산한다. */
internal object HillReviewText {
    /** 언덕 회차 끝 대사(설계 6.6.3 rv.hill). */
    const val HERO_KEY = "rv.hill"

    /** 과제 → 과제 문구 자리(설계 6.6.3 표, review.js `TASK_SLOT`). */
    private val TASK_SLOT = mapOf(
        "T-START" to "start", "T-STOP" to "stop", "T-STOPSIG" to "stop", "T-SIGNAL1" to "signal", "T-SIGNAL30" to "signal",
        "T-SIGNALPRE" to "signal", "T-IC" to "signal", "T-WAIT3" to "wait3", "T-30" to "z30", "T-LIMIT" to "limit",
        "T-SCHOOL" to "school", "T-NOBRAKE" to "nobrake", "T-HILLUP" to "hillup", "T-HILLDOWN" to "hilldown", "T-HILLPARK" to "hillpark",
    )

    /** 짧은 말 → 자리(review.js `SHORT_SLOT`). */
    private val SHORT_SLOT = mapOf(
        "출발" to "start", "멈출 때 브레이크" to "stop", "깜빡이" to "signal", "깜빡이 켜고 셋 세기" to "wait3", "30 구역" to "z30",
        "제한속도" to "limit", "어린이보호구역" to "school", "브레이크" to "nobrake", "오르막 출발" to "hillup", "내리막 브레이크" to "hilldown",
        "언덕 주차" to "hillpark",
    )

    private val COACH_VAR = listOf(Coach.SIB to "taskSib", Coach.MOM to "taskMom", Coach.DAD to "taskDad")
    private val WX_ICON = mapOf(WeatherType.RAIN to "i-rain", WeatherType.SNOW to "i-snow")
    private const val FALLBACK_TASK = "연습"

    /** 과제 문구 자리 (review.js `taskSlot`): id로 먼저, 없으면 짧은 말로. */
    fun taskSlot(task: NextTask?): String? {
        if (task == null) return null
        TASK_SLOT[task.id]?.let { return it }
        val short = task.short.ifEmpty { BaroEngine.taskDef(task.id)?.short.orEmpty() }
        return SHORT_SLOT[short]
    }

    /** 대사 자리 {taskSib}·{taskMom}·{taskDad} (review.js `taskVars`): 코치마다 rv.task.<자리> 글, 없으면 짧은 말·과제 글·'연습'. */
    fun taskVars(task: NextTask?): Map<String, Any?> {
        val slot = taskSlot(task)
        val key = slot?.let { "rv.task.$it" }
        val def = task?.let { BaroEngine.taskDef(it.id) }
        val fallback = task?.short?.ifEmpty { null } ?: task?.label?.ifEmpty { null } ?: def?.short?.ifEmpty { null }
            ?: def?.label?.ifEmpty { null } ?: FALLBACK_TASK
        return COACH_VAR.associate { (coach, name) -> name to (key?.let { ModeLines.render(it, coach) }?.ifEmpty { null } ?: fallback) }
    }

    /** 코치 히어로 글 (review.js `heroLine(rec, { key: 'rv.hill' })`). */
    fun heroText(rec: SessionRecord, coach: Coach, honorific: com.barodrive.core.model.Honorific?): String =
        ModeLines.render(HERO_KEY, coach, taskVars(rec.nextTask), honorific)

    /** 다음 과제 하나 글 (review.js `nextOf`): 기록 글 → 과제 정의의 안전 위반 글 → 과제 글 → id. */
    fun nextLabel(rec: SessionRecord): String? {
        val n = rec.nextTask?.takeIf { it.id.isNotEmpty() } ?: return null
        val def = BaroEngine.taskDef(n.id)
        return n.label.ifEmpty { null } ?: def?.nextLabel?.ifEmpty { null } ?: def?.label?.ifEmpty { null } ?: n.id
    }

    /** 결과 카드 셋 (hill.js `resultModel`, 설계 4.2 RV-6): rec.hill(엔진 note)과 rec.tasks로. */
    fun resultModel(rec: SessionRecord, res: TextRes): HillResultModel {
        val hill = rec.hill
        val tasks = rec.tasks
        fun task(id: String): TaskResult? = tasks.firstOrNull { it.id == id }
        fun stateOf(t: TaskResult?, n: Int): HillResultState = when {
            n == 0 -> HillResultState.NONE
            t == null || t.total <= 0 -> HillResultState.NONE
            t.success >= t.total -> HillResultState.OK
            else -> HillResultState.WARN
        }
        val up = hill?.up ?: HillUpSummary()
        val down = hill?.down ?: HillDownSummary()
        val park = hill?.park
        val none = res.text(R.string.hl_rv_none)
        val upText = if (up.attempts == 0) {
            none
        } else {
            res.text(R.string.hl_rv_up_text, up.attempts, if (up.rollOff) HillGuide.text.rollbackOff else res.text(R.string.hl_rv_rollbacks, up.rollbacks))
        }
        val upState = when {
            up.attempts == 0 || up.rollOff -> HillResultState.NONE
            up.rollbacks > 0 -> HillResultState.WARN
            else -> HillResultState.OK
        }
        val downText = if (down.runs == 0) none else res.text(R.string.hl_rv_down_text, down.presses, down.holds)
        val parkText = if (park == null) {
            none
        } else {
            val parts = mutableListOf(park.situation?.let { HillGuide.situation(it)?.label } ?: res.text(R.string.hl_rv_park_default))
            val wheelOk = park.wheelOk
            if (wheelOk == null) {
                parts += res.text(R.string.hl_rv_wheel_unknown)
            } else {
                val side = park.wheelSide?.let { res.text(R.string.hl_wheel_side, Words.dirName(it.code)) } ?: res.text(R.string.hl_wheel_straight)
                parts += if (wheelOk) res.text(R.string.hl_rv_wheel_ok, side) else res.text(R.string.hl_rv_wheel_wrong, side, Words.dirName(park.need?.code))
            }
            parts += res.text(
                when (park.handbrake) {
                    HandbrakeState.ON -> R.string.hl_rv_hb_on
                    HandbrakeState.OFF -> R.string.hl_rv_hb_off
                    else -> R.string.hl_rv_hb_unknown
                },
            )
            parts.joinToString(" · ")
        }
        return HillResultModel(
            cards = listOf(
                HillResultCard("up", res.text(R.string.hl_rv_up), "i-up", upState, upText),
                HillResultCard("down", res.text(R.string.hl_rv_down), "i-pulse", stateOf(task("T-HILLDOWN"), down.runs), downText),
                HillResultCard("park", res.text(R.string.hl_rv_park), "i-wheel", if (park != null) stateOf(task("T-HILLPARK"), 1) else HillResultState.NONE, parkText),
            ),
            retry = up.rollbacks > 0,
            retryText = HillGuide.text.retry,
        )
    }

    /** 급제동 줄들 (review.js `model` f2 + `f2Text`). */
    fun f2Rows(rec: SessionRecord, res: TextRes): List<HillF2Row> =
        rec.f2.mapIndexed { i, r -> HillF2Row(r.id.ifEmpty { "f2-${i + 1}" }, f2Text(r, res), r.choice) }

    /** 급제동 한 건 '28km/h에서 1.4초 만에 정지' · '…만에 12km/h까지 줄임' (review.js `f2Text`). */
    fun f2Text(r: F2Check, res: TextRes): String {
        val v0 = r.v0 ?: return res.text(R.string.pk_rv_f2_unknown)
        val head = res.text(R.string.pk_rv_f2_head, jsRound(v0)) + (r.dur?.let { res.text(R.string.pk_rv_f2_dur, num1(it)) } ?: "")
        val v1 = r.v1
        val stopped = r.stopped == true || (r.stopped != false && !(v1 != null && v1 > 1))
        return head + if (stopped) res.text(R.string.pk_rv_f2_stop) else res.text(R.string.pk_rv_f2_slow, jsRound(v1 ?: 0.0))
    }

    /** 날씨 팁 줄 (review.js `tipsOf`): 팁 아이콘이 없으면 회차 날씨 아이콘(비·눈·아주 나쁜 날), 그 밖은 정보. */
    fun tips(rec: SessionRecord): List<HillTipLine> {
        val w = rec.weather
        val icon = w?.type?.let { WX_ICON[it] } ?: if (w?.severe != null) "i-hazard" else "i-info"
        return rec.tips.mapNotNull { t -> t.text.trim().takeIf { it.isNotEmpty() }?.let { HillTipLine(it, t.icon ?: icon) } }
    }

    /** 급제동 본인 확인을 기록에 저장한 사본 (review.js `setF2Choice`, 결정-54: 고른 값만). */
    fun withF2Choice(rec: SessionRecord, id: String, choice: F2Choice?): SessionRecord =
        rec.copy(f2 = rec.f2.mapIndexed { i, r -> if (r.id.ifEmpty { "f2-${i + 1}" } == id) r.copy(choice = choice) else r })
}
