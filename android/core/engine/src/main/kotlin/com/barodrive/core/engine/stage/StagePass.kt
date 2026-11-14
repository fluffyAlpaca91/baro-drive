package com.barodrive.core.engine.stage

import com.barodrive.core.engine.review.K4Calc
import com.barodrive.core.engine.review.ReviewTasks
import com.barodrive.core.engine.review.fmtKm
import com.barodrive.core.engine.review.fmtMin
import com.barodrive.core.engine.review.jsRound
import com.barodrive.core.engine.review.round3
import com.barodrive.core.model.K4Compare
import com.barodrive.core.model.Mode
import com.barodrive.core.model.Profile
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.SessionHistory
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.WeatherType

/**
 * 단계 통과(RV-2)·졸업(RV-3)의 화면 없는 계산 (프로토타입 js/app/screens/stage.js `BaroApp.stage`의 순수 부분, 설계 4.2 RV-2·RV-3, 5.4,
 * 6.3.3, 6.4, 6.6.3, 6.10.5, 결정-88·89). 통과 자체는 회차를 저장할 때 리뷰의 진행 반영([com.barodrive.core.engine.api.EngineReview.applyProgress])이
 * 이미 했다 — 여기서는 기록·프로필을 읽기만 한다. 대사 글은 [lineKey] 키를 화면이 지금 코치로 그린다.
 */
object StagePass {
    private val STAGES = 1..4
    private const val GRAD_NOTE = "이제 자율 모드로 원하는 곳까지 달려 보세요. 단계 연습은 언제든 다시 할 수 있어요."
    private const val RV2 = "RV-2"
    private const val RV3 = "RV-3"

    /** 기록의 단계(진행 결과 stage › 기록 stage › 모드). 1~4가 아니면 null. */
    fun stageOf(record: SessionRecord): Int? {
        val n = record.progress?.stage ?: record.stage ?: record.mode
        return n.takeIf { it in STAGES }
    }

    /** 통과 대사 키: 1~3 → 'rv.pass.N', 4 → 'rv.grad'. 그 밖 null. */
    fun lineKey(stage: Int?): String? = when (stage) {
        4 -> "rv.grad"
        1, 2, 3 -> "rv.pass.$stage"
        else -> null
    }

    /**
     * 이번 회차로 지금 단계를 통과했으면 갈 화면 'RV-2'(1~3단계) · 'RV-3'(4단계 = 졸업) (stage.js `screenFor`).
     * 지난 단계 다시 연습(결정-89)·이미 통과한 단계·통과 회차 1번째·진행 반영 전이면 null.
     */
    fun screenFor(record: SessionRecord): String? {
        val pr = record.progress ?: return null
        if (!pr.passed) return null
        val n = stageOf(record) ?: return null
        return if (n == 4 || pr.graduated) RV3 else RV2
    }

    /** RV-2 모델 (stage.js `passModel`, 목업 s06). 1~4단계 회차가 아니면 null. */
    fun passModel(record: SessionRecord): StagePassModel? {
        val stage = stageOf(record) ?: return null
        val graduated = stage == 4
        val next = if (graduated) null else stage + 1
        val nm = next?.let(Mode::ofOrNull)
        return StagePassModel(
            recordId = record.id?.takeIf { it.isNotEmpty() },
            stage = stage,
            nextStage = next,
            graduated = graduated,
            passed = record.progress?.passed == true,
            medal = stage.toString(),
            title = "${stage}단계 통과!",
            nextLead = if (next != null) "다음은" else null,
            nextName = nm?.title,
            lineKey = lineKey(stage),
            tryTitle = next?.let { "${it}단계에서 해 볼 것" },
            tryList = next?.let { n -> ReviewTasks.modeTasks(n).map { StageTry(it.id, it.label) } }.orEmpty(),
            talkNote = nm?.talk?.takeIf { it.isNotEmpty() }?.let { "이제 코치가 $it" },
            courseLabel = next?.let { "${it}단계 코스 보기" },
            courseMode = next,
        )
    }

    /** 단계 연습 회차(모드 1~4, 세워 두고 연습 뺌), 최신이 앞. */
    private fun stageRecords(profile: Profile): List<SessionRecord> =
        profile.sessions.filter { it.mode in STAGES && it.kind != RecordKind.VIRTUAL_PARKING }

    /** 지표마다 가장 최근 단계 회차의 값(stage.js `lastMetrics`). 급정지는 횟수(stopFail/stopTotal)를 함께. */
    private fun lastMetrics(records: List<SessionRecord>): SessionMetrics {
        var out = SessionMetrics()
        for (r in records) {
            val m = r.metrics
            if (out.c2Avg == null && m.c2Avg != null) out = out.copy(c2Avg = m.c2Avg)
            if (out.g1 == null && m.g1 != null) out = out.copy(g1 = m.g1)
            val total = m.stopTotal
            val fail = m.stopFail
            if (out.stopTotal == null && total != null && total > 0 && fail != null) {
                out = out.copy(stopTotal = total, stopFail = fail, stopFailRate = m.stopFailRate ?: round3(fail.toDouble() / total))
            }
        }
        return out
    }

    /**
     * RV-3 요약 (stage.js `gradSummary`, 설계 4.2 RV-3): 단계 연습(모드 1~4) 회차만 센다(결정사항 E T3-7). 총 회차 = sessionCount 1~4 합
     * (30회차를 넘어 기록이 지워져도 맞음), 시간·거리·날씨는 남은 기록의 합, K4는 첫 회차 → 지표마다 가장 최근 값.
     */
    fun gradSummary(profile: Profile?): GradSummary {
        val p = profile ?: Profile()
        val pr = p.progress
        val recs = stageRecords(p)
        val perStage = STAGES.map { n ->
            StageCount(
                stage = n,
                name = Mode.ofOrNull(n)?.title.orEmpty(),
                count = maxOf(p.sessionCount[n] ?: 0, recs.count { it.mode == n }),
            )
        }
        val sessions = perStage.sumOf { it.count }
        var totalSec = 0.0
        var totalM = 0.0
        for (r in recs) {
            totalSec += r.durationSec
            totalM += r.distanceM
        }
        var rain = 0
        var snow = 0
        var severe = 0
        for (r in recs) {
            val w = r.weather ?: continue
            if (w.type == WeatherType.RAIN) rain++
            if (w.type == WeatherType.SNOW) snow++
            if (w.severe != null) severe++
        }
        val weather = GradWeather(rain = rain, snow = snow, severe = severe)
        val wx = listOf("빗길" to rain, "눈길" to snow, "아주 나쁜 날" to severe).filter { it.second > 0 }.map { "${it.first} ${it.second}회" }
        val timeText = fmtMin(totalSec)
        val distText = fmtKm(totalM)
        val stage4 = pr.stages[4]?.passedAt?.takeIf { it.isNotEmpty() }
        val graduatedAt = pr.graduatedAt?.takeIf { it.isNotEmpty() } ?: stage4
        return GradSummary(
            graduated = graduatedAt != null,
            graduatedAt = graduatedAt,
            sessions = sessions,
            perStage = perStage,
            perStageText = perStage.filter { it.count > 0 }.joinToString(" · ") { "${it.stage}단계 ${it.count}회" },
            totalSec = jsRound(totalSec).toLong(),
            totalM = jsRound(totalM).toLong(),
            timeText = timeText,
            distText = distText,
            totalText = "${sessions}회 · $timeText · $distText",
            partial = recs.size < sessions,
            kept = recs.size,
            k4 = K4Calc.k4(lastMetrics(recs), SessionHistory(baselineG1 = p.g1Baseline, firstSessionMetrics = p.firstMetrics)),
            weather = weather,
            weatherText = wx.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            lineKey = "rv.grad",
            note = GRAD_NOTE,
        )
    }
}

/** RV-2 단계 통과 화면 값 (stage.js `passModel`). */
data class StagePassModel(
    val recordId: String?,
    val stage: Int,
    /** 다음 단계(4단계 통과 = 졸업이면 null). */
    val nextStage: Int?,
    val graduated: Boolean,
    val passed: Boolean,
    /** 메달 글자('1'). */
    val medal: String,
    /** '1단계 통과!'. */
    val title: String,
    /** '다음은'. */
    val nextLead: String?,
    /** 다음 단계 이름('동네 골목 다니기'). */
    val nextName: String?,
    /** 코치 대사 키('rv.pass.1' · 'rv.grad'). */
    val lineKey: String?,
    /** '2단계에서 해 볼 것'. */
    val tryTitle: String?,
    /** 다음 단계 과제(설계 6.4 = 표 A-1 '해 볼 것'). */
    val tryList: List<StageTry>,
    /** 다음 단계 말하는 양 안내('이제 코치가 미리 안내를 줄이고 결과 위주로 말해요.', 설계 6.3.3). */
    val talkNote: String?,
    /** '2단계 코스 보기'. */
    val courseLabel: String?,
    val courseMode: Int?,
)

/** 다음 단계 과제 한 줄. */
data class StageTry(val id: String, val label: String)

/** RV-3 졸업 · 연습 기록 요약 값 (stage.js `gradSummary`). */
data class GradSummary(
    val graduated: Boolean,
    val graduatedAt: String?,
    /** 총 회차 = sessionCount 1~4 합(남은 기록이 더 많으면 그 수). */
    val sessions: Int,
    val perStage: List<StageCount>,
    /** '1단계 3회 · 2단계 2회 · …'. */
    val perStageText: String,
    val totalSec: Long,
    val totalM: Long,
    /** '1시간 5분'. */
    val timeText: String,
    /** '41.2km'. */
    val distText: String,
    /** '12회 · 1시간 5분 · 41.2km'. */
    val totalText: String,
    /** 시간·거리는 남은 기록(최근 30회차)의 합이라 오래된 회차가 지워졌으면 참. */
    val partial: Boolean,
    /** 시간·거리를 더한 회차 수. */
    val kept: Int,
    /** 첫 회차 → 마지막 회차(K4). */
    val k4: K4Compare,
    val weather: GradWeather,
    /** '빗길 2회 · 눈길 1회'. 날씨 회차가 없으면 null. */
    val weatherText: String?,
    val lineKey: String,
    /** 안내 문장. */
    val note: String,
)

/** 단계별 회차 수. */
data class StageCount(val stage: Int, val name: String, val count: Int)

/** 날씨 회차 수(아주 나쁜 날은 비·눈과 겹쳐 셀 수 있음). */
data class GradWeather(val rain: Int = 0, val snow: Int = 0, val severe: Int = 0)
