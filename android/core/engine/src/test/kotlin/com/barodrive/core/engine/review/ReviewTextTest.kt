package com.barodrive.core.engine.review

import com.barodrive.core.engine.content.Lines
import com.barodrive.core.model.Coach
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.NextTask
import com.barodrive.core.model.NextTaskReason
import com.barodrive.core.model.ProgressResult
import com.barodrive.core.model.ReviewLine
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.Severe
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.WeatherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RV-1 리뷰 화면 글 (프로토타입 tests/unit/t2d_review.test.js의 화면 없는 계산: rv.end.*·rv.task.* 대사, 과제 문구 자리,
 * 회차 끝 대사 고르기, 부제·과제 달성 줄·급제동 줄·K6 줄·잘한 것/아쉬운 것 나누기). 화면 모델·저장·라우팅은 앱 몫이다.
 */
class ReviewTextTest {
    private fun line(key: String, coach: Coach, vars: Map<String, Any?> = emptyMap(), hon: Honorific? = null) =
        Lines.render(key, coach, vars, hon, peek = true)

    /** 설계 5.3 10번 예(2단계 3회차, 빗길, 급제동 1건, K4·도움 줄여 가기) — 회차 저장 뒤 모양. */
    private val pass = SessionRecord(
        id = "sess-20261110-0912", mode = 2, stage = 2, noInMode = 3, courseId = "c2-B", startedAt = "2026-11-10T09:12:00+09:00",
        durationSec = 1080.0, driveSec = 1060.0, distanceM = 4200.0, arrived = true, weather = SessionWeather(type = WeatherType.RAIN),
        tasks = listOf(
            TaskResult("T-30", "시속 30 넘지 않기", "30 구역", 2, 3), TaskResult("T-SIGNAL30", "꺾기 30m 전에 깜빡이 켜기", "깜빡이", 5, 6),
            TaskResult("T-WAIT3", "깜빡이 켜자마자 꺾지 않기", "깜빡이 켜고 셋 세기", 6, 7), TaskResult("T-STOP", "멈출 때 부드럽게 멈추기", "멈출 때 브레이크", 7, 8),
        ),
        achievement = 0.833, qualified = true, fewEvents = false,
        nextTask = NextTask("T-30", "30 구역 지키기", "30 구역", NextTaskReason.SAFETY),
        good = listOf(ReviewLine("T-STOP", "멈출 때 부드럽게 멈추기 7/8번"), ReviewLine("G1", "직진할 때 핸들 첫날보다 30% 안정")),
        bad = listOf(ReviewLine("E1-30", "30 구역 초과 1번"), ReviewLine("C4", "깜빡이 켜고 바로 꺾음 1번")),
        metrics = SessionMetrics(per10min = 4.2, praise = 6, correction = 2),
        f2 = listOf(F2Check(id = "f2-1", t = 412.3, v0 = 28.0, v1 = 0.0, dur = 1.4, maxDrop1s = 11.6, stopped = true)),
        progress = ProgressResult(applied = true, mode = 2, stage = 2, qualified = true, qualifiedBefore = 1, qualifiedAfter = 2, need = 2, passed = true, nextStage = 3),
    )

    // ---------------- 대사

    @Test
    fun endLinesFollowTheDesignTable() {
        val v = ReviewText.taskVars("T-30")
        assertEquals(mapOf("taskSib" to "30 구역이나 지켜", "taskMom" to "30 구역만 지키자", "taskDad" to "30 구역"), v)
        val want = mapOf(
            "rv.end.good" to listOf("뭐, 오늘은 봐줄 만했다. 다음엔 30 구역이나 지켜.", "세상에 오늘 너무 잘했어! 다음엔 30 구역만 지키자!", "오늘 고생 많았어. 다음엔 30 구역 하나만 신경 써 보자."),
            "rv.end.ok" to listOf("음, 그럭저럭이네. 다음엔 30 구역이나 지켜.", "어머 오늘도 고생했어! 다음엔 30 구역만 지키자!", "오늘도 수고했어. 다음엔 30 구역 하나만 신경 써 보자."),
            "rv.end.hard" to listOf(
                "오늘은 좀 힘들었지? 그래도 끝까지 했네. 다음엔 30 구역이나 지켜.", "아이고 오늘 힘들었지? 괜찮아, 다음엔 30 구역만 지키자!",
                "오늘 쉽지 않았지? 잘 버텼어. 다음엔 30 구역 하나만 해 보자.",
            ),
        )
        for ((key, texts) in want) listOf(Coach.SIB, Coach.MOM, Coach.DAD).forEachIndexed { i, c -> assertEquals("$key $c", texts[i], line(key, c, v)) }
        assertEquals("세상에 오늘 너무 잘했어 우리 딸! 다음엔 30 구역만 지키자!", line("rv.end.good", Coach.MOM, v, Honorific(HonorificType.DAUGHTER)))
        assertEquals("어머 오늘도 고생했어 지은아! 다음엔 30 구역만 지키자!", line("rv.end.ok", Coach.MOM, v, Honorific(HonorificType.CUSTOM, "지은아")))
        assertEquals("형제/자매는 호칭을 안 씀", want.getValue("rv.end.good")[0], line("rv.end.good", Coach.SIB, v, Honorific(HonorificType.DAUGHTER)))
        // 다음 과제가 없는 회차(결정사항 E T2d-2)
        assertEquals("뭐, 오늘도 끝까지 했네.", line("rv.end.none", Coach.SIB, hon = Honorific(HonorificType.SON)))
        assertEquals("어머 오늘도 고생했어!", line("rv.end.none", Coach.MOM))
        assertEquals("오늘도 수고 많았어 우리 아들! 다음에 또 해 보자.", line("rv.end.none", Coach.DAD, hon = Honorific(HonorificType.SON)))
        assertEquals("오늘도 수고 많았어! 다음에 또 해 보자.", line("rv.end.none", Coach.DAD))
    }

    @Test
    fun everyTaskHasItsSlot() {
        val table = mapOf(
            "start" to listOf("출발이나 살살 해", "출발만 조금 살살", "출발"),
            "stop" to listOf("브레이크나 미리 살살 밟아", "브레이크만 미리 살살", "멈출 때 브레이크"),
            "signal" to listOf("깜빡이나 일찍 켜", "깜빡이만 조금 일찍", "깜빡이"),
            "wait3" to listOf("깜빡이 켜고 셋은 세고 꺾어", "깜빡이 켜고 셋만 세자", "깜빡이 켜고 셋 세기"),
            "z30" to listOf("30 구역이나 지켜", "30 구역만 지키자", "30 구역"),
            "limit" to listOf("제한속도나 지켜", "제한속도만 지키자", "제한속도"),
            "school" to listOf("어린이보호구역에선 30 아래로 가", "어린이보호구역만 조심하자", "어린이보호구역"),
            "nobrake" to listOf("브레이크나 미리 살살 밟아", "브레이크만 미리 살살", "갑자기 밟지 않기"),
            "hillup" to listOf("오르막에선 발 빨리 옮겨", "오르막 출발만 천천히", "오르막 출발"),
            "hilldown" to listOf("내리막에선 나눠 밟아", "내리막 브레이크만 나눠서", "내리막 브레이크"),
            "hillpark" to listOf("언덕 주차 바퀴나 맞춰", "언덕 주차만 꼼꼼히", "언덕 주차"),
        )
        for ((slot, texts) in table) listOf(Coach.SIB, Coach.MOM, Coach.DAD).forEachIndexed { i, c -> assertEquals("$slot $c", texts[i], line("rv.task.$slot", c)) }
        val short = mapOf(
            "출발" to "start", "멈출 때 브레이크" to "stop", "깜빡이" to "signal", "깜빡이 켜고 셋 세기" to "wait3", "30 구역" to "z30",
            "제한속도" to "limit", "어린이보호구역" to "school", "브레이크" to "nobrake", "오르막 출발" to "hillup", "내리막 브레이크" to "hilldown",
            "언덕 주차" to "hillpark",
        )
        for (t in TaskTable.all) {
            val slot = ReviewText.taskSlot(t.id)
            assertEquals("${t.id}(${t.short})의 자리", short[t.short], slot)
            val v = ReviewText.taskVars(t.id)
            assertEquals(t.id, table[slot], listOf(v["taskSib"], v["taskMom"], v["taskDad"]))
        }
        assertEquals("짧은 말로도 찾음", "signal", ReviewText.taskSlot(null, short = "깜빡이"))
        assertEquals("자리가 없으면 짧은 말 그대로", mapOf("taskSib" to "새 과제", "taskMom" to "새 과제", "taskDad" to "새 과제"), ReviewText.taskVars("T-NEW", short = "새 과제"))
        assertEquals(mapOf("taskSib" to "연습", "taskMom" to "연습", "taskDad" to "연습"), ReviewText.taskVars(null))
    }

    @Test
    fun heroLinePicksTheTier() {
        assertEquals(
            listOf("good", "good", "ok", "ok", "hard", "hard", "ok"),
            listOf(1.0, 0.8, 0.7999, 0.5, 0.4999, 0.0, null).map(ReviewText::tier),
        )
        val start = NextTask("T-START", "엑셀 살살 밟아서 출발하기", "출발", NextTaskReason.LOWEST)
        val a = ReviewText.heroLine(SessionRecord(mode = 1, achievement = 0.83, nextTask = start), Coach.DAD, null)
        assertEquals("목업 s05 문장", listOf("rv.end.good", "good", "오늘 고생 많았어. 다음엔 출발 하나만 신경 써 보자."), listOf(a.key, a.tier, a.text))
        assertEquals("rv.end.ok", ReviewText.heroLine(SessionRecord(mode = 1, achievement = 0.6, nextTask = start), Coach.DAD, null).key)
        assertEquals("rv.end.hard", ReviewText.heroLine(SessionRecord(mode = 1, achievement = 0.2, nextTask = start), Coach.DAD, null).key)
        assertEquals(
            "달성률이 비어 있으면 과제 합으로", "rv.end.hard",
            ReviewText.heroLine(SessionRecord(mode = 1, tasks = listOf(TaskResult("T-START", success = 1, total = 4)), nextTask = start), Coach.DAD, null).key,
        )
        val none = ReviewText.heroLine(SessionRecord(mode = 5), Coach.DAD, null)
        assertEquals(listOf("rv.end.none", "오늘도 수고 많았어! 다음에 또 해 보자."), listOf(none.key, none.text))
        // 다른 화면 대사(rv.hill 등)도 같은 과제 자리로
        if (Lines.has("rv.hill")) {
            val hl = ReviewText.heroLine(SessionRecord(mode = 7, achievement = 0.5, nextTask = NextTask("T-HILLUP")), Coach.DAD, null, key = "rv.hill")
            assertEquals("언덕 연습 고생했어. 다음엔 오르막 출발 하나만 해 보자.", hl.text)
        }
        assertEquals(
            "지금 코치·호칭", "세상에 오늘 너무 잘했어 우리 딸! 다음엔 출발만 조금 살살!",
            ReviewText.heroLine(SessionRecord(mode = 1, achievement = 1.0, nextTask = start), Coach.MOM, Honorific(HonorificType.DAUGHTER)).text,
        )
        // 다음 과제 글이 비면 과제 정의의 nextLabel로(review.js nextOf)
        val bare = ReviewText.heroLine(SessionRecord(mode = 2, achievement = 0.9, nextTask = NextTask("T-30")), Coach.DAD, null)
        assertEquals("오늘 고생 많았어. 다음엔 30 구역 하나만 신경 써 보자.", bare.text)
    }

    // ---------------- 글

    @Test
    fun subAchievementF2K6AndSplit() {
        assertEquals("설계 4.2 RV-1 1번", "2단계 · 3회차 · 18분 · 4.2km · 빗길", ReviewText.sub(pass))
        assertEquals(
            "1단계 · 2회차 · 18분 · 4.2km · 눈길",
            ReviewText.sub(SessionRecord(mode = 1, noInMode = 2, durationSec = 1080.0, distanceM = 4200.0, weather = SessionWeather(type = WeatherType.SNOW))),
        )
        assertEquals("3단계 · 1회차 · 아주 나쁜 날", ReviewText.sub(SessionRecord(mode = 3, noInMode = 1, weather = SessionWeather(severe = Severe.ICE))))
        assertEquals(
            "자율 · 2회차 · 31분 · 12.4km",
            ReviewText.sub(SessionRecord(mode = 5, noInMode = 2, durationSec = 1860.0, distanceM = 12400.0, weather = SessionWeather())),
        )
        assertEquals("요약만 있는 기록", "1단계 · 1회차", ReviewText.sub(SessionRecord(mode = 1, noInMode = 1)))

        val a = ReviewText.achievement(pass)
        requireNotNull(a)
        assertEquals("설계 4.2 RV-1 3번 예", listOf<Any?>("이번 회차 과제 83% (20/24) · 통과 회차 2/2", true, false, 83), listOf(a.text, a.ok, a.few, a.pct))
        val r = pass.copy(tasks = listOf(TaskResult("T-30", success = 159, total = 200)), qualified = false, achievement = null, progress = ProgressResult(stage = 2, qualifiedAfter = 1, need = 2))
        val ar = requireNotNull(ReviewText.achievement(r))
        assertEquals("79.5%는 80%로 보이지 않게 내림", listOf<Any?>("이번 회차 과제 79% (159/200)", false), listOf(ar.main, ar.ok))
        assertEquals(
            "설계 6.10.1", "이번 회차 과제 100% (2/2) · 기록이 적어 통과 횟수에는 넣지 않았어요",
            ReviewText.achievement(pass.copy(fewEvents = true, qualified = false, tasks = listOf(TaskResult("T-30", success = 2, total = 2))))?.text,
        )
        assertEquals("이미 통과한 단계예요", ReviewText.achievement(pass.copy(progress = ProgressResult(alreadyPassed = true, qualifiedAfter = 2, need = 2)))?.pass)
        assertEquals("면허 모드는 통과 회차 없음", "이번 회차 과제 83% (20/24)", ReviewText.achievement(pass.copy(mode = 8))?.text)
        assertNull("과제가 없는 모드", ReviewText.achievement(SessionRecord(mode = 5)))
        assertEquals(
            "이번 회차엔 판정한 과제가 없어요 · 기록이 적어 통과 횟수에는 넣지 않았어요",
            ReviewText.achievement(SessionRecord(mode = 1, tasks = listOf(TaskResult("T-START", success = 0, total = 0))))?.text,
        )

        assertEquals("설계 4.2 RV-1 6번", "28km/h에서 1.4초 만에 정지", ReviewText.f2(F2Check(v0 = 28.0, dur = 1.4)))
        assertEquals("28km/h에서 2초 만에 정지", ReviewText.f2(F2Check(v0 = 27.7, v1 = 0.0, dur = 2.0, stopped = true)))
        assertEquals("42km/h에서 1.5초 만에 18km/h까지 줄임", ReviewText.f2(F2Check(v0 = 42.0, v1 = 18.4, dur = 1.5, stopped = false)))
        assertEquals("급하게 브레이크를 밟았어요", ReviewText.f2(F2Check()))

        assertEquals("설계 4.2 RV-1 8번", "코치가 10분에 4.2번 말했어요 · 칭찬 6 : 교정 2", ReviewText.k6(SessionMetrics(per10min = 4.2, praise = 6, correction = 2)))
        assertEquals("코치가 10분에 6번 말했어요 · 칭찬 2 : 교정 4", ReviewText.k6(SessionMetrics(per10min = 6.0, praise = 2, correction = 4)))
        assertNull(ReviewText.k6(SessionMetrics()))

        assertEquals(SplitText("엑셀 살살 밟아서 출발하기", "5/6번"), ReviewText.split("엑셀 살살 밟아서 출발하기 5/6번"))
        assertEquals(SplitText("직진할 때 핸들", "첫날보다 30% 안정"), ReviewText.split("직진할 때 핸들 첫날보다 30% 안정"))
        assertEquals(SplitText("30 구역", "다 지켰어요"), ReviewText.split("30 구역 다 지켰어요"))
        assertEquals(SplitText("출발할 때 엑셀을 조금 세게", "2번"), ReviewText.split("출발할 때 엑셀을 조금 세게 2번"))
        assertEquals(SplitText("내리막에서 브레이크 계속 밟음", "1번"), ReviewText.split("내리막에서 브레이크 계속 밟음 1번"))
        assertEquals(SplitText("그냥 글", null), ReviewText.split("그냥 글"))
    }

    @Test
    fun josaFollowsTheLastSyllable() {
        assertEquals("출발은", Josa.josa("출발", "은/는"))
        assertEquals("정지는", Josa.josa("정지", "은/는"))
        assertEquals("핸들을", Josa.josa("핸들", "을/를"))
        assertEquals("깜빡이를", Josa.josa("깜빡이", "을/를"))
        assertEquals("오른쪽으로", Josa.josa("오른쪽"))
        assertEquals("길로", Josa.josa("길"))
        assertEquals("3을", Josa.josa("3", "을/를"))
        assertEquals("10을", Josa.josa("10", "을/를"))
        assertEquals("시험장 주변 C 코스를", Josa.josa("시험장 주변 C 코스", "을/를"))
        assertTrue(Josa.pick("'마트'", "이/가") == "가")
    }
}
