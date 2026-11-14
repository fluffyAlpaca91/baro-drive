package com.barodrive.app.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import com.barodrive.core.model.Utterance
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 안드로이드 기본 TTS로 코치 말을 소리 낸다 — Pleos TTS를 못 쓸 때의 대신([AutoCoachVoice]). TTS 엔진이나 한국어 음성이 없으면
 * 켜기에 실패한다(Pleos 에뮬레이터가 그렇다: 기본 엔진 없음, status=-1).
 */
@Singleton
class AndroidTtsCoachVoice @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechEngine {
    override val name: String = "안드로이드 TextToSpeech"

    private enum class State { NEW, STARTING, READY, FAILED }

    private val lock = Any()
    private var state = State.NEW
    private var tts: TextToSpeech? = null
    private var onResult: ((Boolean) -> Unit)? = null

    override fun start(onResult: (ok: Boolean) -> Unit) {
        var failedNow = false
        synchronized(lock) {
            if (state != State.NEW) return
            state = State.STARTING
            this.onResult = onResult
            val engine = try {
                TextToSpeech(context) { status -> onInit(status) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "TTS를 만들지 못했어요", e)
                state = State.FAILED
                failedNow = true
                null
            }
            // 엔진에 못 붙으면 onInit(ERROR)이 생성자 안에서 바로 불려 이미 FAILED일 수 있다.
            if (engine != null) {
                if (state == State.FAILED) engine.shutdown() else tts = engine
            }
        }
        if (failedNow) report(false)
    }

    override fun speak(utterance: Utterance) {
        synchronized(lock) {
            if (state != State.READY) return
            // 엔진이 차례로 말한다(QUEUE_ADD) — 겹치지 않는 간격은 엔진의 전달 관리자가 이미 지켰다.
            val result = tts?.speak(utterance.text, TextToSpeech.QUEUE_ADD, null, "${utterance.key}@${utterance.t}")
            if (result != TextToSpeech.SUCCESS) Log.w(TAG, "말하기 실패(result=$result): ${utterance.key}")
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (state == State.READY) tts?.stop()
        }
    }

    override fun release() {
        synchronized(lock) {
            state = State.FAILED
            tts?.shutdown()
            tts = null
        }
    }

    /** 성공은 늘 나중에(메인 스레드) 온다 — 그때는 [start]가 끝나 [tts]가 들어 있다. */
    private fun onInit(status: Int) {
        val ok = synchronized(lock) {
            val engine = tts
            when {
                status != TextToSpeech.SUCCESS || engine == null -> fail("TTS 엔진이 없어요(status=$status)")
                engine.setLanguage(Locale.KOREAN).let { it == TextToSpeech.LANG_MISSING_DATA || it == TextToSpeech.LANG_NOT_SUPPORTED } ->
                    fail("한국어 음성이 없어요")
                else -> {
                    // TODO(Pleos 확인): 코칭 음성의 오디오 용도. 내비 안내와 같은 용도로 두어 음악은 낮추고 내비와는 차례로 나오게 한다.
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    state = State.READY
                    true
                }
            }
        }
        report(ok)
    }

    /** lock 안에서 부른다. */
    private fun fail(reason: String): Boolean {
        Log.w(TAG, reason)
        state = State.FAILED
        tts?.shutdown()
        tts = null
        return false
    }

    /** 결과는 한 번만, lock 밖에서 알린다. */
    private fun report(ok: Boolean) {
        val callback = synchronized(lock) { onResult.also { onResult = null } } ?: return
        callback(ok)
    }

    private companion object {
        const val TAG = "AndroidTts"
    }
}
