package com.barodrive.app.feature.hill

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.feature.parking.ModeReviews
import com.barodrive.app.feature.parking.ReviewEffect
import com.barodrive.app.feature.parking.XmlTextRes
import com.barodrive.app.session.api.FakeSessionController
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Coach
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillDownSummary
import com.barodrive.core.model.HillParkResult
import com.barodrive.core.model.HillResult
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.HillUpSummary
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.ReviewTip
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.WeatherType
import java.time.Clock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * RV-6 (프로토타입 hill.js `resultModel`, 시험 t6_hill 'RV-6 결과 카드 셋', review.js `taskVars`·`f2Text`·`tipsOf`).
 * S7-B 보통 회차 기록은 골든 m7-S7-B-normal의 `record` 그대로다.
 */
class HillReviewTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val res = XmlTextRes

    private fun golden(): SessionRecord = BaroJson.decodeFromString(SessionRecord.serializer(), GOLDEN_M7)

    private fun cards(rec: SessionRecord) = HillReviewText.resultModel(rec, res).cards.map { Triple(it.id, it.state, it.text) }

    @Test
    fun goldenNormalRunCards() {
        val rec = golden()
        assertEquals(
            listOf(
                Triple("up", HillResultState.OK, "2번 · 뒤로 밀림 0번"),
                Triple("down", HillResultState.OK, "브레이크 나눠 밟기 4번 · 계속 밟음 0번"),
                Triple("park", HillResultState.WARN, "내리막·연석 있음 · 앞바퀴 오른쪽 맞음 · 사이드브레이크 안 채움"),
            ),
            cards(rec),
        )
        assertFalse(HillReviewText.resultModel(rec, res).retry)
        assertEquals(listOf("오르막 출발", "내리막 감속", "언덕 주차"), HillReviewText.resultModel(rec, res).cards.map { it.title })
        // 다음 과제 언덕 주차 → 히어로 자리 글(rv.task.hillpark)
        assertEquals("hillpark", HillReviewText.taskSlot(rec.nextTask))
        assertEquals(mapOf("taskSib" to "언덕 주차 바퀴나 맞춰", "taskMom" to "언덕 주차만 꼼꼼히", "taskDad" to "언덕 주차"), HillReviewText.taskVars(rec.nextTask))
        assertEquals("언덕 연습 고생했어. 다음엔 언덕 주차 하나만 해 보자.", HillReviewText.heroText(rec, Coach.DAD, null))
        assertEquals("어머 언덕도 해냈어 우리 딸! 다음엔 언덕 주차만 꼼꼼히!", HillReviewText.heroText(rec, Coach.MOM, Honorific(HonorificType.DAUGHTER)))
        assertEquals("오르막도 별거 아니지? 다음엔 언덕 주차 바퀴나 맞춰.", HillReviewText.heroText(rec, Coach.SIB, Honorific(HonorificType.DAUGHTER)))
        assertEquals("언덕에 세울 때 바퀴 방향 맞추고 사이드브레이크 채우기", HillReviewText.nextLabel(rec))
        assertEquals("언덕 · 1회차 · 6분 · 2.2km", ModeReviews.subText(ModeReviews.model(rec, com.barodrive.core.model.Profile(), false), res))
    }

    @Test
    fun poorRunCardsAndRetryLine() {
        // t6 S7-B 서툰 회차: 오르막 2번 중 밀림 1번 · 내리막 나눠 밟기 3번·계속 밟음 1번 · 내리막·연석 바퀴 맞음 · 사이드브레이크 안 채움
        val rec = SessionRecord(
            mode = 7,
            tasks = listOf(TaskResult("T-HILLUP", success = 1, total = 2), TaskResult("T-HILLDOWN", success = 0, total = 1), TaskResult("T-HILLPARK", success = 0, total = 1)),
            hill = HillResult(
                up = HillUpSummary(attempts = 2, rollbacks = 1),
                down = HillDownSummary(runs = 1, presses = 3, holds = 1),
                park = HillParkResult(n = 1, situation = HillSlope.DOWN, need = TurnDir.RIGHT, wheelOk = true, wheelSide = TurnDir.RIGHT, wheelDeg = 20.0, handbrake = HandbrakeState.OFF, ok = false),
            ),
        )
        assertEquals(
            listOf(
                Triple("up", HillResultState.WARN, "2번 · 뒤로 밀림 1번"),
                Triple("down", HillResultState.WARN, "브레이크 나눠 밟기 3번 · 계속 밟음 1번"),
                Triple("park", HillResultState.WARN, "내리막·연석 있음 · 앞바퀴 오른쪽 맞음 · 사이드브레이크 안 채움"),
            ),
            cards(rec),
        )
        val rm = HillReviewText.resultModel(rec, res)
        assertEquals(true to "처음엔 완만한 곳에서 다시 해 보세요", rm.retry to rm.retryText)
        // 능숙: 사이드브레이크까지 채우면 모두 잘함
        val good = rec.copy(
            tasks = listOf(TaskResult("T-HILLUP", success = 2, total = 2), TaskResult("T-HILLDOWN", success = 1, total = 1), TaskResult("T-HILLPARK", success = 1, total = 1)),
            hill = rec.hill!!.copy(up = HillUpSummary(attempts = 2), park = rec.hill!!.park!!.copy(handbrake = HandbrakeState.ON)),
        )
        assertEquals(listOf(HillResultState.OK, HillResultState.OK, HillResultState.OK), cards(good).map { it.second })
        assertEquals("내리막·연석 있음 · 앞바퀴 오른쪽 맞음 · 사이드브레이크 채움", cards(good)[2].third)
        // 반대로 돌린 바퀴
        val wrong = rec.copy(hill = rec.hill!!.copy(park = HillParkResult(situation = HillSlope.UP, need = TurnDir.LEFT, wheelOk = false, wheelSide = TurnDir.RIGHT, handbrake = HandbrakeState.ON)))
        assertEquals("오르막·연석 있음 · 앞바퀴 오른쪽(왼쪽이 맞아요) · 사이드브레이크 채움", cards(wrong)[2].third)
    }

    @Test
    fun skippedOrUnreadablePractices() {
        val none = SessionRecord(mode = 7, tasks = emptyList(), hill = null)
        assertEquals(List(3) { HillResultState.NONE to "이번엔 하지 않았어요" }, cards(none).map { it.second to it.third })
        val off = SessionRecord(
            mode = 7,
            tasks = listOf(TaskResult("T-HILLUP", success = 0, total = 0)),
            hill = HillResult(
                up = HillUpSummary(attempts = 2, rollbacks = 0, rollOff = true),
                down = HillDownSummary(runs = 0),
                park = HillParkResult(situation = HillSlope.NONE, need = TurnDir.RIGHT, wheelOk = null, handbrake = HandbrakeState.UNKNOWN),
            ),
        )
        assertEquals(
            listOf("2번 · 뒤로 밀림은 기록하지 않았어요", "이번엔 하지 않았어요", "연석 없음 · 앞바퀴 방향 확인 못 함 · 사이드브레이크 확인 못 함"),
            cards(off).map { it.third },
        )
        assertEquals("밀림을 못 읽으면 하지 않음 표시", HillResultState.NONE, cards(off)[0].second)
        // 다음 과제가 없으면 자리 글은 '연습'
        assertEquals("언덕 연습 고생했어. 다음엔 연습 하나만 해 보자.", HillReviewText.heroText(none, Coach.DAD, null))
    }

    @Test
    fun f2AndTipsLines() {
        assertEquals("28km/h에서 1.4초 만에 정지", HillReviewText.f2Text(F2Check(id = "f2-1", v0 = 28.2, v1 = 0.0, dur = 1.4, stopped = true), res))
        assertEquals("30km/h에서 1.2초 만에 12km/h까지 줄임", HillReviewText.f2Text(F2Check(v0 = 29.6, v1 = 12.4, dur = 1.2, stopped = false), res))
        assertEquals("21km/h에서 2초 만에 정지", HillReviewText.f2Text(F2Check(v0 = 21.0, v1 = 0.6, dur = 2.0), res))
        assertEquals("급하게 브레이크를 밟았어요", HillReviewText.f2Text(F2Check(), res))
        val rec = SessionRecord(
            mode = 7,
            weather = SessionWeather(type = WeatherType.RAIN),
            tips = listOf(ReviewTip("빗길 회차예요. 차간거리를 두 배로."), ReviewTip("  "), ReviewTip("과속 줄", icon = "i-slow")),
            f2 = listOf(F2Check(id = "f2-1", v0 = 28.0, v1 = 0.0, dur = 1.4), F2Check(v0 = 20.0, v1 = 0.0, choice = F2Choice.MISTAKE)),
        )
        assertEquals(listOf(HillTipLine("빗길 회차예요. 차간거리를 두 배로.", "i-rain"), HillTipLine("과속 줄", "i-slow")), HillReviewText.tips(rec))
        assertEquals(listOf("f2-1" to null, "f2-2" to F2Choice.MISTAKE), HillReviewText.f2Rows(rec, res).map { it.id to it.choice })
        val chosen = HillReviewText.withF2Choice(rec, "f2-1", F2Choice.AVOID)
        assertEquals(listOf(F2Choice.AVOID, F2Choice.MISTAKE), chosen.f2.map { it.choice })
    }

    @Test
    fun viewModelSpeaksSavesF2AndStartsAgain() = runTest {
        val store = InMemoryBaroStore()
        val rec = store.add(golden().copy(id = "sess-test-rv6", f2 = listOf(F2Check(id = "f2-1", v0 = 28.0, v1 = 0.0))))
        val voice = InMemoryVoiceLog()
        val session = FakeSessionController()
        val vm = HillReviewViewModel(SavedStateHandle(mapOf("recordId" to rec.id, "readOnly" to false)), store, store, session, voice, Clock.systemUTC())
        vm.uiState.test {
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertEquals(listOf("rv.hill"), voice.entries.value.map { it.key })
            vm.setF2Choice("f2-1", F2Choice.AVOID)
            var after = awaitItem()
            while (after.model?.record?.f2?.single()?.choice == null) after = awaitItem()
            assertEquals(F2Choice.AVOID, after.model?.record?.f2?.single()?.choice)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(F2Choice.AVOID, store.get("sess-test-rv6")?.f2?.single()?.choice)
        vm.effects.test {
            vm.again()
            assertEquals(ReviewEffect.ToPredrive, awaitItem())
        }
        val prep = session.state.value.prep
        assertEquals(listOf<Any?>(7, "c7-B", SessionPhase.READY), listOf(prep?.mode, prep?.courseId, session.state.value.phase))
    }

    @Test
    fun againWithoutACourseGoesToCourses() = runTest {
        val store = InMemoryBaroStore()
        val rec = store.add(SessionRecord(id = "sess-test-rv6-nocourse", mode = 7))
        val vm = HillReviewViewModel(SavedStateHandle(mapOf("recordId" to rec.id, "readOnly" to true)), store, store, FakeSessionController(), InMemoryVoiceLog(), Clock.systemUTC())
        vm.uiState.test {
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertTrue(s.model!!.readOnly)
            cancelAndIgnoreRemainingEvents()
        }
        vm.effects.test {
            vm.again()
            assertEquals(ReviewEffect.ToCourses(7), awaitItem())
        }
    }

    companion object {
        /** 골든 m7-S7-B-normal 회차 기록에서 RV-6이 읽는 칸. */
        const val GOLDEN_M7 = """{"mode":7,"noInMode":1,"courseId":"c7-B","placeId":null,"simSec":352.4,"distanceM":2239,
            "weather":{"type":"clear","severe":false,"cold":false,"by":null,"at":null,"tipCards":0},
            "tasks":[{"id":"T-HILLUP","label":"오르막에서 뒤로 밀리지 않고 출발하기","short":"오르막 출발","success":2,"total":2},
              {"id":"T-HILLDOWN","label":"내리막에서 브레이크 나눠 밟기","short":"내리막 브레이크","success":1,"total":1},
              {"id":"T-HILLPARK","label":"언덕에 세울 때 바퀴 방향 맞추고 사이드브레이크 채우기","short":"언덕 주차","success":0,"total":1}],
            "nextTask":{"id":"T-HILLPARK","label":"언덕에 세울 때 바퀴 방향 맞추고 사이드브레이크 채우기","short":"언덕 주차","reason":"lowest"},
            "hill":{"tasks":{"T-HILLUP":{"success":2,"total":2},"T-HILLDOWN":{"success":1,"total":1},
                "T-HILLPARK":{"success":0,"total":1,"badText":"언덕 주차 사이드브레이크 안 채움 {n}번"}},
              "up":{"attempts":2,"rollbacks":0,"rollOff":false,"list":[{"n":1,"rolled":false,"completed":true},{"n":2,"rolled":false,"completed":true}]},
              "down":{"runs":1,"presses":4,"holds":0,"list":[{"n":1,"presses":4,"holds":0,"ok":true,"viaNav":true}]},
              "park":{"n":1,"situation":"down","need":"R","wheelOk":true,"wheelSide":"R","wheelDeg":20,"handbrake":"off","ok":false},
              "parks":[{"n":1,"situation":"down","need":"R","wheelOk":true,"wheelSide":"R","wheelDeg":20,"handbrake":"off","ok":false}],
              "practice":"park","step":3,"done":true},
            "tips":[],"f2":[],"share":{"status":"none"}}"""
    }
}
