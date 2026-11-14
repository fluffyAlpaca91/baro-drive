package com.barodrive.app.feature.autonomous

import com.barodrive.app.feature.feedback.PresetRules
import com.barodrive.app.feature.history.BaroFormat
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.review.ReviewText
import com.barodrive.core.engine.score.ScoreText
import com.barodrive.core.model.Courses
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.PreviousScore
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ScoreFootnote
import com.barodrive.core.model.ScoreLine
import com.barodrive.core.model.ScoreResult
import com.barodrive.core.model.ScoreTableRow
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.WeatherBadge
import com.barodrive.core.model.WeatherType
import kotlin.math.PI
import kotlin.math.floor

/** 점수 고리 (목업 s07 `stroke-dasharray "507.9 590.6"`): 채운 길이와 둘레. */
data class ScoreRing(val dash: Double, val gap: Double)

/** RV-4가 그릴 값 (autonomous.js `model`). */
data class AutonomousModel(
    val score: ScoreResult,
    val line: ScoreLine,
    /** 점수 대사(지금 코치·호칭, 정차 중 말과 같은 글). 못 그리면 ''. */
    val lineText: String,
    /** 목적지 이름(장소가 없으면 null). */
    val dest: String?,
    /** '도착했어요' · '자율 주행을 마쳤어요'. */
    val title: String,
    /** '자율 모드 · 집 → 마트 · 12.4km · 17분'. */
    val sub: String,
    val prev: PreviousScore?,
    /** '지난번 82점 → 오늘 86점'. */
    val prevText: String?,
    val ring: ScoreRing,
    val table: List<ScoreTableRow>,
    val excludedText: String?,
    val footnotes: List<ScoreFootnote>,
    /** '14점 깎였어요'. */
    val lostText: String,
)

/** PR-1 자율 변형 맨 위 카드 (autonomous.js `topModel`). [destText]는 고른 뒤 '마트 · 12.4km · 31분'. */
data class AutonomousTop(val title: String, val sub: String, val destName: String?, val destText: String?, val preset: String)

/**
 * RV-4 자율 모드 도착·점수와 PR-1 자율 변형 맨 위 카드의 화면 없는 계산 (프로토타입 screens/autonomous.js `BaroApp.autonomous`,
 * 설계 4.2 RV-4·PR-1 자율 변형, 5.5, 6.11). 점수는 회차를 저장할 때 엔진이 붙인 기록 `score`를 그리고, 없으면(요약만 있는 기록)
 * 그 자리에서 계산한다. 점수 표·글은 엔진(score.ScoreText)이 만든다.
 */
object AutonomousText {
    const val TITLE_ARRIVED = "도착했어요"
    const val TITLE_ENDED = "자율 주행을 마쳤어요"
    const val TOP_TITLE = "목적지는 내비에서 직접 넣어요"
    const val TOP_SUB = "고른 곳에 도착하면 안전운전 점수를 보여 줘요"
    const val TOP_SUB_PICKED = "도착하면 안전운전 점수를 보여 줘요"
    const val PRESET_NONE = "자율 모드 기본값"

    /** 고리 반지름(목업 220 상자 안 r = 94)과 둘레. */
    const val RING_R = 94.0
    val RING_C: Double = 2 * PI * RING_R

    /** 기록의 점수, 없으면 판정으로 계산(G1 기준 = 프로필 g1Baseline). */
    fun scoreOf(rec: SessionRecord, profile: Profile?): ScoreResult = rec.score ?: BaroEngine.score.compute(rec, profile)

    /** 날씨 길 글자(빗길·눈길·아주 나쁜 날). */
    fun roadWord(w: SessionWeather?): String? = when {
        w == null -> null
        w.type == WeatherType.RAIN -> WeatherBadge.RAIN.road
        w.type == WeatherType.SNOW -> WeatherBadge.SNOW.road
        w.severe != null -> WeatherBadge.SEVERE.road
        else -> null
    }

    /** 앱 바 부제(목업 s07 '자율 모드 · 집 → 마트 · 12.4km · 31분'): 실제 회차의 거리·시간, 날씨 회차면 '빗길' 등. */
    fun sub(rec: SessionRecord): String {
        val parts = arrayListOf("자율 모드")
        Courses.place(rec.placeId)?.let { pl -> parts += if (pl.here) pl.name else "집 → " + pl.name }
        if (rec.distanceM >= 50) parts += BaroFormat.km(rec.distanceM)
        if (rec.durationSec >= 1) parts += BaroFormat.min(rec.durationSec)
        roadWord(rec.weather)?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** 점수 고리 값: 채운 길이 = 둘레 × 점수/100(소수 한 자리). */
    fun ring(total: Int): ScoreRing {
        val dash = floor(RING_C * total.coerceIn(0, 100) / 100 * 10 + 0.5) / 10
        return ScoreRing(dash = dash, gap = floor(RING_C * 10 + 0.5) / 10)
    }

    /**
     * RV-4 값. [records]는 최신이 앞인 기록 목록(지난 점수 찾기). 도착 전에 마친 회차는 대사의 목적지를 '여기'로(T11-11).
     */
    fun model(rec: SessionRecord, profile: Profile?, records: List<SessionRecord>): AutonomousModel {
        val sc = scoreOf(rec, profile)
        val dest = Courses.place(rec.placeId)?.name
        val line = BaroEngine.score.line(sc, if (!rec.arrived) "여기" else dest ?: "목적지")
        val p = profile ?: Profile()
        val prev = rec.id?.let { BaroEngine.score.previous(records, it) }
        return AutonomousModel(
            score = sc,
            line = line,
            lineText = ReviewText.lineText(line.key, p.coach, ScoreText.vars(line), p.honorific),
            dest = dest,
            title = if (!rec.arrived) TITLE_ENDED else TITLE_ARRIVED,
            sub = sub(rec),
            prev = prev,
            prevText = prev?.let { "지난번 ${it.total}점 → 오늘 ${sc.total}점" },
            ring = ring(sc.total),
            table = BaroEngine.score.table(sc),
            excludedText = BaroEngine.score.excludedText(sc),
            footnotes = BaroEngine.score.footnotes(sc),
            lostText = BaroEngine.score.lostText(sc),
        )
    }

    /** PR-1 자율 변형 맨 위 카드: 목적지(내비에서 고르면) + 고른 설정 이름('출퇴근용' · '(바꿈)' · '새로 만들기' · 없으면 기본값). */
    fun topModel(prep: PrepSession?, profile: Profile?): AutonomousTop {
        val preset = PresetRules.label(profile, prep?.presetId, prep?.presetItems)
        val pl = Courses.place(prep?.placeId)
        val destText = pl?.let {
            val bits = ArrayList<String>()
            if (it.km > 0) bits += BaroFormat.toFixed1(it.km) + "km"
            if (it.min > 0) bits += "${it.min}분"
            it.name + if (bits.isNotEmpty()) " · " + bits.joinToString(" · ") else ""
        }
        return AutonomousTop(
            title = TOP_TITLE, sub = if (pl != null) TOP_SUB_PICKED else TOP_SUB, destName = pl?.name, destText = destText,
            preset = preset ?: PRESET_NONE,
        )
    }
}
