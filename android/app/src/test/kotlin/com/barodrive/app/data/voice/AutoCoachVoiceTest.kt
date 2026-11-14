package com.barodrive.app.data.voice

import com.barodrive.core.model.Utterance
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 코치 소리 엔진 고르기: Pleos TTS → 안드로이드 TTS → 소리 없음, 켜는 동안 쌓은 말, 시간 초과, 소리 끔. */
class AutoCoachVoiceTest {
    /** [answer]가 null이면 답하지 않는다(시간 초과 흉내). [sync]면 start 안에서 바로 답한다. */
    private class FakeEngine(override val name: String, var answer: Boolean?, private val sync: Boolean = false) : SpeechEngine {
        val spoken = mutableListOf<String>()
        var starts = 0
        var stops = 0
        var released = 0
        var callback: ((Boolean) -> Unit)? = null

        override fun start(onResult: (ok: Boolean) -> Unit) {
            starts++
            callback = onResult
            if (sync) answer?.let(onResult)
        }

        fun answerNow() = answer?.let { callback?.invoke(it) }

        override fun speak(utterance: Utterance) {
            spoken += utterance.key
        }

        override fun stop() {
            stops++
        }

        override fun release() {
            released++
        }
    }

    private fun u(key: String) = Utterance(key = key, text = key)

    @Test
    fun pleosFirstAndLinesSaidWhileStartingAreFlushedInOrder() = runTest {
        val pleos = FakeEngine("Pleos", answer = true)
        val android = FakeEngine("Android", answer = true)
        val logs = mutableListOf<String>()
        val v = AutoCoachVoice(listOf(pleos, android), backgroundScope, log = { logs += it })
        v.speak(u("a"))
        v.speak(u("b"))
        assertNull(v.engineName)
        pleos.answerNow()
        v.speak(u("c"))
        assertEquals(listOf("a", "b", "c"), pleos.spoken)
        assertEquals(0, android.starts)
        assertEquals("Pleos", v.engineName)
        assertEquals(listOf("코치 음성: Pleos"), logs)
    }

    @Test
    fun failedPleosFallsBackToAndroid() = runTest {
        val pleos = FakeEngine("Pleos", answer = false, sync = true)
        val android = FakeEngine("Android", answer = true)
        val v = AutoCoachVoice(listOf(pleos, android), backgroundScope, log = {})
        v.speak(u("a"))
        assertEquals(1, pleos.released)
        android.answerNow()
        assertEquals(listOf("a"), android.spoken)
        assertEquals("Android", v.engineName)
    }

    @Test
    fun throwingStartCountsAsFailure() = runTest {
        val broken = object : SpeechEngine {
            override val name = "Broken"
            override fun start(onResult: (ok: Boolean) -> Unit) = throw SecurityException("TTS_SERVICE 권한 없음")
            override fun speak(utterance: Utterance) = Unit
            override fun stop() = Unit
            override fun release() = Unit
        }
        val android = FakeEngine("Android", answer = true, sync = true)
        val v = AutoCoachVoice(listOf(broken, android), backgroundScope, log = {})
        v.speak(u("a"))
        assertEquals(listOf("a"), android.spoken)
    }

    @Test
    fun timeoutMovesOnAndLateSuccessIsReleased() = runTest {
        val pleos = FakeEngine("Pleos", answer = true)
        val android = FakeEngine("Android", answer = true, sync = true)
        val logs = mutableListOf<String>()
        val v = AutoCoachVoice(listOf(pleos, android), backgroundScope, timeoutMs = 5_000, log = { logs += it })
        v.speak(u("a"))
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals("시간 초과 → 다음 엔진", listOf("a"), android.spoken)
        assertTrue(logs.first().contains("시간 초과"))
        pleos.answerNow()
        assertEquals("늦은 성공은 놓는다", 2, pleos.released)
        v.speak(u("b"))
        assertEquals(listOf("a", "b"), android.spoken)
        assertTrue(pleos.spoken.isEmpty())
    }

    @Test
    fun noEngineMeansSilentAndMutedSpeaksNothing() = runTest {
        val pleos = FakeEngine("Pleos", answer = false, sync = true)
        val android = FakeEngine("Android", answer = false, sync = true)
        val logs = mutableListOf<String>()
        val v = AutoCoachVoice(listOf(pleos, android), backgroundScope, log = { logs += it })
        v.speak(u("a"))
        v.speak(u("b"))
        assertNull(v.engineName)
        assertTrue(logs.last().contains("소리 없이"))
        assertEquals(1, pleos.starts)
        assertEquals(1, android.starts)

        val ok = FakeEngine("Pleos", answer = true, sync = true)
        val muted = AutoCoachVoice(listOf(ok), backgroundScope, log = {})
        muted.speak(u("x"))
        muted.muted = true
        muted.speak(u("y"))
        assertEquals(listOf("x"), ok.spoken)
        assertEquals("소리 끄면 하던 말도 멈춤", 1, ok.stops)
    }
}
