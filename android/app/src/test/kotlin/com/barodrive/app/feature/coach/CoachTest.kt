package com.barodrive.app.feature.coach

import com.barodrive.app.data.repo.InMemoryBaroStore
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.testing.MainDispatcherRule
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 프로토타입 tests/unit/t2b_screens.test.js의 코치·호칭 부분(co.preview·co.honor·호칭 값 규칙) + CO-1·CO-2 화면 값. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun linesForEveryCoach() {
        for (key in listOf("co.preview", "co.honor", "p.course", "p.course.none")) {
            for (c in Coach.entries) {
                for (h in listOf(Honorific(), Honorific(HonorificType.DAUGHTER))) {
                    val vars: Map<String, Any?> = when (key) {
                        "co.preview" -> mapOf("T" to 40)
                        "p.course" -> mapOf("X" to "B")
                        else -> emptyMap()
                    }
                    val t = CoachLines.render(key, c, vars, h)
                    assertTrue("$key $c: $t", t.length > 4 && !t.contains('{') && !t.contains('}'))
                }
            }
        }
        assertEquals("지금은 B 코스가 덜 막혀.", CoachLines.render("p.course", Coach.DAD, mapOf("X" to "B")))
        assertEquals("어머, 지금은 C 코스가 덜 막힌대!", CoachLines.render("p.course", Coach.MOM, mapOf("X" to "C")))
        assertEquals("야, 지금 켜면 늦어. 다음엔 40미터 전에 켜라.", CoachLines.previewText(Coach.SIB))
        assertEquals("괜찮아, 다음엔 40미터쯤 전에 켜 보자.", CoachLines.previewText(Coach.DAD))
        assertEquals("", CoachLines.render("없는.키", Coach.DAD))
    }

    @Test
    fun honorificPreviewByCoach() {
        assertEquals("어머 잘했다 우리 딸! 딱 좋았어!", CoachLines.honorText(Coach.MOM, Honorific(HonorificType.DAUGHTER)))
        assertEquals("어머 잘했다! 딱 좋았어!", CoachLines.honorText(Coach.MOM, Honorific()))
        assertEquals("어머 잘했다 지은아! 딱 좋았어!", CoachLines.honorText(Coach.MOM, Honorific(HonorificType.CUSTOM, "  지은아 ")))
        assertEquals("빈 직접 쓰기 = 부르지 않기", "어머 잘했다! 딱 좋았어!", CoachLines.honorText(Coach.MOM, Honorific(HonorificType.CUSTOM, "")))
        assertEquals("4단계까지 다 마쳤어 우리 아들! 정말 고생 많았어.", CoachLines.honorText(Coach.DAD, Honorific(HonorificType.SON)))
        assertEquals("오, 웬일. 잘했네.", CoachLines.honorText(Coach.SIB, Honorific(HonorificType.DAUGHTER)))
    }

    @Test
    fun honorificValueRules() {
        assertEquals(Honorific(HonorificType.CUSTOM, "가나다라마바사아"), Honorifics.normalize(Honorific(HonorificType.CUSTOM, "  가나다라마바사아자차 ")))
        assertEquals(Honorific(), Honorifics.normalize(Honorific(HonorificType.CUSTOM, "   ")))
        assertEquals(Honorific(HonorificType.DAUGHTER, ""), Honorifics.normalize(Honorific(HonorificType.DAUGHTER, "x")))
        assertEquals(Honorific(), Honorifics.normalize(null))
        assertFalse(Honorifics.valid(Honorific(HonorificType.CUSTOM, "")))
        assertTrue(Honorifics.valid(Honorific(HonorificType.CUSTOM, "민수야")))
        assertTrue(Honorifics.valid(Honorific()))
        assertEquals(listOf("우리 딸", "우리 아들", "부르지 않기"), listOf(HonorificType.DAUGHTER, HonorificType.SON, HonorificType.NONE).map { Honorifics.label(Honorific(it)) })
        assertEquals("지은아", Honorifics.label(Honorific(HonorificType.CUSTOM, "지은아")))
        assertEquals(listOf(Coach.SIB, Coach.MOM, Coach.DAD), CoachOrder)
        assertEquals("다른 칩을 골랐다 돌아와도 글은 남는다", "지은아", HonorificDraft(HonorificType.CUSTOM, "지은아").copy(type = HonorificType.NONE).copy(type = HonorificType.CUSTOM).customText)
    }

    @Test
    fun coachPickSavesAndSheetSavesNormalized() = runTest(main.dispatcher.scheduler) {
        val store = InMemoryBaroStore()
        val vm = CoachViewModel(store, InMemoryVoiceLog())
        vm.pick(Coach.SIB)
        runCurrent()
        assertEquals(Coach.SIB, store.current().coach)
        assertEquals(Coach.SIB, vm.uiState.value.coach)
        val sheet = HonorificViewModel(store)
        runCurrent()
        sheet.pick(HonorificType.CUSTOM)
        assertFalse(sheet.uiState.value.draft.valid)
        var closed = false
        sheet.save { closed = true }
        runCurrent()
        assertFalse("빈 직접 쓰기는 저장하지 않는다", closed)
        sheet.setCustomText(" 민수야 ")
        sheet.save { closed = true }
        runCurrent()
        assertTrue(closed)
        assertEquals(Honorific(HonorificType.CUSTOM, "민수야"), store.current().honorific)
    }

    @Test
    fun listenSpeaksThePreviewOfThatCoachWithoutPickingIt() = runTest(main.dispatcher.scheduler) {
        val store = InMemoryBaroStore()
        val voice = InMemoryVoiceLog(
            render = { key, _ -> "[프로필 코치] $key" },
            renderAs = { key, vars, coach -> "[$coach] $key ${vars["T"]}" },
        )
        val vm = CoachViewModel(store, voice)
        runCurrent()
        vm.listen(Coach.MOM)
        val e = voice.entries.value.last()
        assertEquals("미리 듣는 코치 목소리·이름으로(coach.js listen → sayLine opts.coach)", Coach.MOM, e.coach)
        assertEquals("[MOM] ${CoachLines.PREVIEW_KEY} ${CoachLines.PREVIEW_T}", e.text)
        assertEquals("코치는 바꾸지 않는다", Coach.DAD, store.current().coach)
    }
}
