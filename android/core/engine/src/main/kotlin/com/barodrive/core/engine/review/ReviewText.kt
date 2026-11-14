package com.barodrive.core.engine.review

import com.barodrive.core.engine.content.LineRenderException
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.model.Coach
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.Mode
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.WeatherBadge
import com.barodrive.core.model.WeatherType
import kotlin.math.floor

/**
 * RV-1 리뷰 화면 글의 화면 없는 계산 (프로토타입 js/app/screens/review.js `BaroApp.review`의 `taskSlot`·`taskVars`·`tier`·`heroLine`·
 * `text.sub`·`text.achievement`·`text.f2`·`text.k6`·`text.split`, 설계 4.2 RV-1·6.6.3, 결정사항 E T2d-2·T2d-3).
 * 회차 끝 대사(rv.end.*)와 과제 문구 자리(rv.task.*)는 콘텐츠 대사([Lines])로 그린다. 기록은 고치지 않는다.
 */
object ReviewText {
    private const val EPS = 1e-6
    private const val FEW = "기록이 적어 통과 횟수에는 넣지 않았어요"

    /** 과제 → 과제 문구 자리(설계 6.6.3 표의 '과제(짧은 말)' 열). id로 먼저, 없으면 짧은 말로. */
    private val TASK_SLOT = mapOf(
        "T-START" to "start", "T-STOP" to "stop", "T-STOPSIG" to "stop", "T-SIGNAL1" to "signal", "T-SIGNAL30" to "signal",
        "T-SIGNALPRE" to "signal", "T-IC" to "signal", "T-WAIT3" to "wait3", "T-30" to "z30", "T-LIMIT" to "limit", "T-SCHOOL" to "school",
        "T-NOBRAKE" to "nobrake", "T-HILLUP" to "hillup", "T-HILLDOWN" to "hilldown", "T-HILLPARK" to "hillpark",
    )
    private val SHORT_SLOT = mapOf(
        "출발" to "start", "멈출 때 브레이크" to "stop", "깜빡이" to "signal", "깜빡이 켜고 셋 세기" to "wait3", "30 구역" to "z30",
        "제한속도" to "limit", "어린이보호구역" to "school", "브레이크" to "nobrake", "오르막 출발" to "hillup",
        "내리막 브레이크" to "hilldown", "언덕 주차" to "hillpark",
    )

    /** 코치 → 대사 변수 이름. */
    private val COACH_VAR = listOf(Coach.SIB to "taskSib", Coach.MOM to "taskMom", Coach.DAD to "taskDad")

    /** 엔진 글의 끝 값: '5/6번' · '2번' · '첫날보다 30% 안정' · '첫날과 비슷해요' · '다 지켰어요'. */
    private val VALUE_RE = Regex("^(.*\\S)\\s+(\\d+\\s*/\\s*\\d+번|\\d+번|첫날보다 .+|첫날과 비슷해요|다 지켰어요)$")

    /** 대사 글(정차 중 말과 같은 글 — 순번은 넘기지 않음). 못 그리면 ''(review.js `lineText`). */
    fun lineText(key: String?, coach: Coach, vars: Map<String, Any?> = emptyMap(), honorific: Honorific? = null): String {
        if (key.isNullOrEmpty() || !Lines.has(key)) return ""
        return try {
            Lines.render(key, coach, vars, honorific, peek = true)
        } catch (_: LineRenderException) {
            ""
        }
    }

    // ---------------- 과제 문구 자리(설계 6.6.3 아래 표)

    /** 과제의 문구 자리('start'…) (review.js `taskSlot`): id 표, 없으면 짧은 말([short] 또는 과제 정의의 짧은 말) 표. 없으면 null. */
    fun taskSlot(id: String?, short: String? = null): String? {
        if (id == null && short == null) return null
        id?.let { TASK_SLOT[it] }?.let { return it }
        val s = short?.takeIf { it.isNotEmpty() } ?: ReviewTasks.def(id)?.short
        return s?.takeIf { it.isNotEmpty() }?.let { SHORT_SLOT[it] }
    }

    /**
     * 과제 문구 자리 변수 { taskSib, taskMom, taskDad } (review.js `taskVars`) — rv.end.*·rv.hill의 `{task…}` 자리.
     * 자리가 없으면 짧은 말(또는 [label]·과제 정의 글), 과제가 없으면 '연습'.
     */
    fun taskVars(id: String?, short: String? = null, label: String? = null): Map<String, String> {
        val slot = taskSlot(id, short)
        val key = slot?.let { "rv.task.$it" }
        val def = ReviewTasks.def(id)
        val fallback = short?.takeIf { it.isNotEmpty() } ?: label?.takeIf { it.isNotEmpty() }
            ?: def?.short?.takeIf { it.isNotEmpty() } ?: def?.label?.takeIf { it.isNotEmpty() } ?: "연습"
        return COACH_VAR.associate { (coach, name) -> name to lineText(key, coach).ifEmpty { fallback } }
    }

    // ---------------- 회차 끝 대사(설계 6.6.3 rv.end.*)

    /** 과제 성공·판정 합(값을 0 이상 정수로, 성공은 판정 수까지). */
    private fun taskSums(record: SessionRecord): Pair<Int, Int> {
        var success = 0
        var total = 0
        for (t in record.tasks) {
            val tt = maxOf(0, t.total)
            success += minOf(tt, maxOf(0, t.success))
            total += tt
        }
        return success to total
    }

    /** 회차 달성률: 기록 achievement, 없으면 과제 합. */
    private fun rateOf(record: SessionRecord): Double? {
        record.achievement?.let { return it }
        val (s, t) = taskSums(record)
        return if (t > 0) s.toDouble() / t else null
    }

    /** 달성 80% 이상 'good' · 50~79% 'ok' · 50% 미만 'hard' · 달성률이 없으면 'ok' (review.js `tier`, 결정사항 E T2d-3). */
    fun tier(achievement: Double?): String = when {
        achievement == null || !achievement.isFinite() -> "ok"
        achievement >= 0.8 - EPS -> "good"
        achievement >= 0.5 - EPS -> "ok"
        else -> "hard"
    }

    /**
     * 회차 끝 대사 (review.js `heroLine`): 다음 과제가 있으면 'rv.end.<tier>', 없으면 'rv.end.none'. 변수 = 다음 과제 문구 자리
     * + [vars]. [key]를 주면 그 대사(rv.hill 등)를 같은 과제 자리로 그린다. 코치·호칭은 지금 프로필 값.
     */
    fun heroLine(
        record: SessionRecord,
        coach: Coach,
        honorific: Honorific?,
        key: String? = null,
        vars: Map<String, Any?> = emptyMap(),
    ): HeroLine {
        val n = record.nextTask?.takeIf { it.id.isNotEmpty() }
        val def = ReviewTasks.def(n?.id)
        val nextShort = n?.short?.takeIf { it.isNotEmpty() } ?: def?.short.orEmpty()
        val nextLabel = n?.label?.takeIf { it.isNotEmpty() } ?: def?.nextLabel?.takeIf { it.isNotEmpty() }
            ?: def?.label?.takeIf { it.isNotEmpty() } ?: n?.id
        val t = tier(rateOf(record))
        val k = key?.takeIf { it.isNotEmpty() } ?: if (n != null) "rv.end.$t" else "rv.end.none"
        val all: Map<String, Any?> = (if (n != null) taskVars(n.id, nextShort, nextLabel) else taskVars(null)) + vars
        return HeroLine(key = k, vars = all, tier = t, text = lineText(k, coach, all, honorific))
    }

    // ---------------- 글

    /** 앱 바 부제 '1단계 · 2회차 · 18분 · 4.2km · 빗길' (review.js `subText`, 설계 4.2 RV-1 1번). */
    fun sub(record: SessionRecord): String {
        val parts = ArrayList<String>()
        Mode.ofOrNull(record.mode)?.short?.takeIf { it.isNotEmpty() }?.let { parts += it }
        record.noInMode?.takeIf { it > 0 }?.let { parts += "${it}회차" }
        if (record.durationSec >= 1) parts += fmtMin(record.durationSec)
        if (record.distanceM >= 50) parts += fmtKm(record.distanceM)
        roadWord(record.weather)?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** 날씨 길 글자: 비 '빗길' · 눈 '눈길' · 아주 나쁜 날 '아주 나쁜 날'. 맑으면 null. */
    private fun roadWord(w: SessionWeather?): String? = when {
        w == null -> null
        w.type == WeatherType.RAIN -> WeatherBadge.RAIN.road
        w.type == WeatherType.SNOW -> WeatherBadge.SNOW.road
        w.severe != null -> WeatherBadge.SEVERE.road
        else -> null
    }

    /**
     * 과제 달성 줄 (review.js `achievementText`, 설계 4.2 RV-1 3번·6.10.1·6.10.4) '이번 회차 과제 83% (20/24) · 통과 회차 2/2'.
     * 80% 아래를 '80%'로 보이지 않게 내림. 단계 모드만 통과 글('기록이 적어 …' · '이미 통과한 단계예요' · '통과 회차 k/2').
     * 과제가 없는 기록은 null.
     */
    fun achievement(record: SessionRecord): AchievementLine? {
        if (record.tasks.isEmpty()) return null
        val (success, total) = taskSums(record)
        val rate = if (total > 0) success.toDouble() / total else null
        val pct = rate?.let { floor(it * 100 + EPS).toInt() }
        val few = record.fewEvents
        val ok = record.qualified
        val main = if (total > 0) "이번 회차 과제 $pct% ($success/$total)" else "이번 회차엔 판정한 과제가 없어요"
        var pass: String? = null
        if (record.mode in 1..4) {
            val pr = record.progress
            val need = pr?.need?.takeIf { it != 0 } ?: 2
            val after = pr?.qualifiedAfter
            pass = when {
                few -> FEW
                pr != null && pr.alreadyPassed -> "이미 통과한 단계예요"
                after != null -> "통과 회차 ${minOf(need, after)}/$need"
                else -> null
            }
        }
        return AchievementLine(
            success = success, total = total, rate = rate, pct = pct, ok = ok, few = few, main = main, pass = pass,
            text = if (pass != null) "$main · $pass" else main,
        )
    }

    /**
     * 급제동 한 건 '28km/h에서 1.4초 만에 정지' (review.js `f2Text`, 설계 4.2 RV-1 6번). 멈추지 않은 급감속은 '…만에 12km/h까지 줄임'.
     * 속도는 정수, 시간은 소수 한 자리. 속도를 모르면 '급하게 브레이크를 밟았어요'.
     */
    fun f2(row: F2Check): String {
        val v0 = row.v0?.takeIf { it.isFinite() } ?: return "급하게 브레이크를 밟았어요"
        val dur = row.dur?.takeIf { it.isFinite() }
        val v1 = row.v1?.takeIf { it.isFinite() }
        val head = "${jsRound(v0).toLong()}km/h에서 " + (dur?.let { "${num1(it)}초 만에 " } ?: "")
        val stopped = row.stopped == true || (row.stopped != false && !(v1 != null && v1 > 1))
        return head + if (stopped) "정지" else "${jsRound(v1 ?: 0.0).toLong()}km/h까지 줄임"
    }

    /** 말 수 줄(K6, 설계 6.10.5) '코치가 10분에 4.2번 말했어요 · 칭찬 6 : 교정 2' (review.js `k6Text`). 10분당 수가 없으면 null. */
    fun k6(metrics: SessionMetrics): String? {
        val per = metrics.per10min?.takeIf { it.isFinite() } ?: return null
        return "코치가 10분에 ${num1(per)}번 말했어요 · 칭찬 ${maxOf(0, metrics.praise ?: 0)} : 교정 ${maxOf(0, metrics.correction ?: 0)}"
    }

    /** 엔진의 잘한 것·아쉬운 것 글을 '이름 | 값'으로 (review.js `split`, 목업 s05 줄 모양). 값이 없으면 value null. */
    fun split(text: String?): SplitText {
        val s = text.orEmpty().trim()
        val m = VALUE_RE.find(s) ?: return SplitText(s, null)
        return SplitText(m.groupValues[1], m.groupValues[2].replaceFirst(Regex("\\s*/\\s*"), "/"))
    }

    /** 보임 숫자: 정수면 그대로, 아니면 소수 한 자리(4 · 4.2 · 1.4). */
    private fun num1(x: Double): String {
        val v = jsRound(x * 10) / 10
        return if (v == floor(v)) v.toLong().toString() else toFixed1(v)
    }
}

/** 회차 끝 대사 (review.js `heroLine` 값): 키·변수·달성 구간('good'·'ok'·'hard')·지금 코치로 그린 글. */
data class HeroLine(
    val key: String,
    val vars: Map<String, Any?>,
    val tier: String,
    val text: String,
)

/** 과제 달성 줄 값 (review.js `achievementText`). */
data class AchievementLine(
    val success: Int,
    val total: Int,
    val rate: Double?,
    /** 내린 퍼센트. */
    val pct: Int?,
    /** 통과 회차로 셈. */
    val ok: Boolean,
    /** 기록이 적은 회차. */
    val few: Boolean,
    val main: String,
    val pass: String?,
    val text: String,
)

/** 잘한 것·아쉬운 것 한 줄을 이름과 값으로. */
data class SplitText(val label: String, val value: String?)
