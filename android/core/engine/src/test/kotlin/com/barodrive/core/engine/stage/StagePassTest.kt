package com.barodrive.core.engine.stage

import com.barodrive.core.engine.api.EngineReview
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.review.ReviewTasks
import com.barodrive.core.model.Coach
import com.barodrive.core.model.FirstMetrics
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.Mode
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ProgressResult
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.Severe
import com.barodrive.core.model.StageProgress
import com.barodrive.core.model.StageState
import com.barodrive.core.model.WeatherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 단계 통과(RV-2)·졸업(RV-3)의 화면 없는 계산 (프로토타입 tests/unit/t3_stage.test.js의 screenFor·lineKey·passModel·gradSummary).
 * 실제 시나리오로 1~4단계를 도는 시험은 엔진 코어·시뮬레이터가 붙은 뒤의 몫이고, 여기서는 진행 반영을 바로 불러 같은 흐름을 본다.
 */
class StagePassTest {
    private fun line(key: String?, coach: Coach, hon: Honorific? = null) = Lines.render(key.orEmpty(), coach, emptyMap(), hon, peek = true)

    private fun rec(stage: Int, progress: ProgressResult) = SessionRecord(id = "r", mode = stage, stage = stage, progress = progress)

    @Test
    fun screenForOnlyWhenThisSessionPassedTheCurrentStage() {
        assertEquals("RV-2", StagePass.screenFor(rec(1, ProgressResult(stage = 1, passed = true, nextStage = 2))))
        assertEquals("3단계 통과 → 4단계가 열림(졸업 아님)", "RV-2", StagePass.screenFor(rec(3, ProgressResult(stage = 3, passed = true, nextStage = 4))))
        assertEquals("RV-3", StagePass.screenFor(rec(4, ProgressResult(stage = 4, passed = true, graduated = true))))
        assertNull("통과 회차 1번째", StagePass.screenFor(rec(2, ProgressResult(stage = 2, passed = false, qualifiedAfter = 1))))
        assertNull("건너뛴 지난 단계가 두 번째로 해냄(결정-89 — passedAt만)", StagePass.screenFor(rec(1, ProgressResult(stage = 1, pastStage = true, passedAt = "x"))))
        assertNull("자율", StagePass.screenFor(SessionRecord(mode = 5, progress = ProgressResult(mode = 5, passed = false))))
        assertNull("진행 반영 전", StagePass.screenFor(SessionRecord(mode = 2)))
        assertEquals(listOf("rv.pass.1", "rv.pass.2", "rv.pass.3", "rv.grad", null), (1..5).map(StagePass::lineKey))
    }

    @Test
    fun passModelMatchesMockupS06() {
        val m = StagePass.passModel(SessionRecord(id = "sess-1", mode = 1, stage = 1, progress = ProgressResult(stage = 1, passed = true, nextStage = 2)))
        requireNotNull(m)
        assertEquals(listOf<Any?>("sess-1", 1, 2, false, true, "1"), listOf(m.recordId, m.stage, m.nextStage, m.graduated, m.passed, m.medal))
        assertEquals(listOf("1단계 통과!", "다음은", "동네 골목 다니기"), listOf(m.title, m.nextLead, m.nextName))
        assertEquals("rv.pass.1", m.lineKey)
        assertEquals("1단계 통과 축하해. 골목도 지금처럼 천천히 가 보자.", line(m.lineKey, Coach.DAD))
        assertEquals("2단계에서 해 볼 것", m.tryTitle)
        assertEquals(listOf("시속 30 넘지 않기", "꺾기 30m 전에 깜빡이 켜기", "깜빡이 켜자마자 꺾지 않기", "멈출 때 부드럽게 멈추기"), m.tryList.map { it.label })
        assertEquals("이제 코치가 미리 안내를 줄이고 결과 위주로 말해요.", m.talkNote)
        assertEquals(listOf<Any?>("2단계 코스 보기", 2), listOf(m.courseLabel, m.courseMode))
        // 2·3단계(설계 5.4 문장) · 4단계는 졸업 모델
        val m2 = requireNotNull(StagePass.passModel(SessionRecord(mode = 2, progress = ProgressResult(stage = 2, passed = true))))
        assertEquals(listOf("2단계 통과!", "큰 도로 달리기", "3단계에서 해 볼 것", "3단계 코스 보기"), listOf(m2.title, m2.nextName, m2.tryTitle, m2.courseLabel))
        assertEquals(listOf("제한속도 지키기", "꺾기 전에 미리 깜빡이 켜기", "신호 앞에서 부드럽게 멈추기"), m2.tryList.map { it.label })
        assertEquals("이제 코치가 위험한 것만 바로 말하고 나머지는 연습이 끝난 뒤 알려 줘요.", m2.talkNote)
        val m3 = requireNotNull(StagePass.passModel(SessionRecord(mode = 3, progress = ProgressResult(stage = 3, passed = true))))
        assertEquals(listOf("들어갈 때·나갈 때 깜빡이 미리 켜기", "제한속도 지키기", "갑자기 브레이크 밟지 않기"), m3.tryList.map { it.label })
        assertEquals("이제 코치가 달리는 중엔 거의 말하지 않고 끝난 뒤 알려 줘요.", m3.talkNote)
        val m4 = requireNotNull(StagePass.passModel(SessionRecord(mode = 4, progress = ProgressResult(stage = 4, passed = true, graduated = true))))
        assertEquals(listOf<Any?>(true, null, "rv.grad", 0, null), listOf(m4.graduated, m4.nextStage, m4.lineKey, m4.tryList.size, m4.courseLabel))
        assertNull(StagePass.passModel(SessionRecord(mode = 5)))
        // 통과 대사(코치·호칭)
        assertEquals("세상에, 2단계 통과야! 큰길도 천천히 가자, 응?", line("rv.pass.2", Coach.MOM))
        assertEquals("세상에, 다 끝냈어 우리 아들! 너무 대견해!", line("rv.grad", Coach.MOM, Honorific(HonorificType.SON)))
        assertEquals("세상에, 다 끝냈어 우리 딸! 너무 대견해!", line("rv.grad", Coach.MOM, Honorific(HonorificType.DAUGHTER)))
    }

    @Test
    fun gradSummaryCountsStageSessionsOnly() {
        var seq = 0
        fun mk(mode: Int, sec: Double, m: Double, metrics: SessionMetrics = SessionMetrics(), weather: SessionWeather = SessionWeather(), kind: RecordKind? = null) =
            SessionRecord(id = "r${++seq}", mode = mode, durationSec = sec, distanceM = m, metrics = metrics, weather = weather, kind = kind)
        val p = Profile(
            progress = StageProgress(
                currentStage = 4, unlockedStage = 4, graduatedAt = "2026-11-09T10:00:00+09:00",
                stages = mapOf(1 to StageState(2, "a"), 2 to StageState(2, "b"), 3 to StageState(2, "c"), 4 to StageState(2, "d")),
            ),
            sessionCount = mapOf(1 to 3, 2 to 2, 3 to 2, 4 to 2, 5 to 4, 6 to 1, 7 to 0, 8 to 0),
            firstMetrics = FirstMetrics(c2Avg = 38.0, stopTotal = 8, stopFail = 3, stopFailRate = 0.375, g1 = 1.1),
            g1Baseline = 1.1,
            sessions = listOf( // 최신이 앞
                mk(4, 600.0, 11300.0, SessionMetrics(c2Avg = null, g1 = 0.7)),
                mk(5, 900.0, 12400.0, weather = SessionWeather(type = WeatherType.RAIN)), // 자율은 세지 않음
                mk(4, 620.0, 11300.0, SessionMetrics(c2Avg = 55.0, stopTotal = 7, stopFail = 1, g1 = 0.75), SessionWeather(type = WeatherType.RAIN)),
                mk(3, 560.0, 6000.0, weather = SessionWeather(type = WeatherType.SNOW, severe = Severe.ICE)),
                mk(2, 480.0, 2950.0), mk(2, 470.0, 2950.0), mk(1, 400.0, 2100.0), mk(1, 390.0, 2100.0),
                mk(6, 50.0, 0.0, kind = RecordKind.VIRTUAL_PARKING),
            ),
        )
        val g = StagePass.gradSummary(p)
        assertEquals(listOf<Any?>(true, "2026-11-09T10:00:00+09:00"), listOf(g.graduated, g.graduatedAt))
        assertEquals("1단계 3 + 2·3·4단계 2씩", 9, g.sessions)
        assertEquals("1단계 3회 · 2단계 2회 · 3단계 2회 · 4단계 2회", g.perStageText)
        assertEquals(listOf("차량 감 익히기", "동네 골목 다니기", "큰 도로 달리기", "고속도로 타 보기"), g.perStage.map { it.name })
        assertEquals("1단계 1회·3단계 1회 기록이 없음(시간·거리는 남은 기록)", listOf<Any?>(7, true), listOf(g.kept, g.partial))
        assertEquals(listOf(3520L, 38700L), listOf(g.totalSec, g.totalM))
        assertEquals("9회 · 59분 · 38.7km", g.totalText)
        assertEquals(GradWeather(rain = 1, snow = 1, severe = 1), g.weather)
        assertEquals("빗길 1회 · 눈길 1회 · 아주 나쁜 날 1회", g.weatherText)
        // K4: 지표마다 가장 최근 값(c2Avg는 두 번째 4단계 회차, 급정지도 그 회차, G1은 마지막 회차)
        assertEquals(false, g.k4.first)
        assertEquals(
            listOf("c2Avg" to "38m → 55m", "stop" to "3/8 → 1/7", "g1" to "첫날보다 36% 안정"),
            g.k4.rows.map { it.id to it.text },
        )
        assertEquals(listOf("rv.grad", "이제 자율 모드로 원하는 곳까지 달려 보세요. 단계 연습은 언제든 다시 할 수 있어요."), listOf(g.lineKey, g.note))
        // 기록이 없는 졸업(데모 상태 등): 회차 수만, 날씨 줄 없음, K4 첫 기록
        val empty = StagePass.gradSummary(
            Profile(sessionCount = mapOf(1 to 2, 2 to 2, 3 to 2, 4 to 2), progress = StageProgress(graduatedAt = "x")),
        )
        assertEquals(
            listOf<Any?>(8, 0, true, null, true, "8회 · 0초 · 0.0km"),
            listOf(empty.sessions, empty.kept, empty.partial, empty.weatherText, empty.k4.first, empty.totalText),
        )
    }

    @Test
    fun fourStagesPassedTwiceEachLeadToGraduation() {
        // t3 '1~4단계를 능숙 두 번씩' — 시나리오 대신 통과 회차(qualified)를 바로 진행 반영
        var p = Profile()
        var n = 0
        for (stage in 1..4) {
            for (k in 1..2) {
                val r = SessionRecord(id = "sess-t3-${++n}", mode = stage, stage = stage, noInMode = k, qualified = true, startedAt = "2026-10-1${n % 10}T09:00:00+09:00")
                val a = EngineReview.applyProgress(p, r)
                p = a.profile.copy(sessions = listOf(a.record) + a.profile.sessions)
                if (k == 1) {
                    assertEquals("$stage 1번째", listOf<Any?>(false, null), listOf(a.result.passed, StagePass.screenFor(a.record)))
                    assertEquals(stage, p.progress.currentStage)
                } else if (stage < 4) {
                    assertEquals("RV-2", StagePass.screenFor(a.record))
                    assertEquals(listOf(stage + 1, stage + 1), listOf(p.progress.currentStage, p.progress.unlockedStage))
                    val m = requireNotNull(StagePass.passModel(a.record))
                    assertEquals(listOf<Any?>(stage, stage + 1, "rv.pass.$stage", stage + 1), listOf(m.stage, m.nextStage, m.lineKey, m.courseMode))
                    assertEquals(ReviewTasks.modeTasks(stage + 1).map { it.id }, m.tryList.map { it.id })
                } else {
                    assertEquals("RV-3", StagePass.screenFor(a.record))
                    assertEquals(true, a.result.graduated)
                    assertEquals("졸업 날짜", a.record.startedAt, p.progress.graduatedAt)
                }
            }
        }
        val g = StagePass.gradSummary(p)
        assertEquals(listOf<Any?>(true, 8, 8, false), listOf(g.graduated, g.sessions, g.kept, g.partial))
        assertEquals("1단계 2회 · 2단계 2회 · 3단계 2회 · 4단계 2회", g.perStageText)
        assertNull("맑은 날만", g.weatherText)
        // 졸업 뒤 4단계를 또 해도 진행은 그대로(결정-89, T2a-14), RV-3으로도 안 감
        val again = EngineReview.applyProgress(p, SessionRecord(mode = 4, stage = 4, qualified = true))
        assertEquals(listOf<Any?>(false, true, null), listOf(again.result.passed, again.result.alreadyPassed, StagePass.screenFor(again.record)))
        // 지난 단계(2단계) 다시 연습도 그대로
        val past = EngineReview.applyProgress(p, SessionRecord(mode = 2, stage = 2, qualified = true))
        assertEquals(listOf<Any?>(true, false, null, 4), listOf(past.result.pastStage, past.result.passed, StagePass.screenFor(past.record), past.profile.progress.currentStage))
        assertEquals(Mode.STAGE4.no, p.progress.currentStage)
    }
}
