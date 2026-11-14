package com.barodrive.app.data.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.core.model.Utterance
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pleos TextToSpeech SDK로 코치 말을 소리 낸다 (API-레퍼런스 6장). 한국어 음성은 이 SDK에만 있다 — Pleos 에뮬레이터의 플랫폼
 * TextToSpeech에는 한국어 엔진이 없다. `Mode.ON_DEVICE`는 앱 등록(registerApp) 없이 쓴다. 권한: `pleos.car.permission.TTS_SERVICE`.
 * SDK의 speak는 한 문장씩이라 앞 말이 끝나면(onDone·onStop·onError) 다음 말을 한다 — 겹치지 않는 간격은 엔진의 전달 관리자가 이미 지켰다.
 * TODO(Pleos 확인): 오디오 용도(setAudioAttributes)·목소리(setSpeakerType)는 SDK 기본값 그대로.
 */
@Singleton
class PleosTtsCoachVoice @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechEngine {
    override val name: String = "Pleos TTS(ON_DEVICE)"

    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var speaking = false
    private var speakingUntilMs = 0L
    private val queue = ArrayDeque<Utterance>()

    private val events = object : EventListener {
        override fun onStart() = Unit

        override fun onReady() = Unit

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() = next()

        override fun onStop() = next()

        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "말하기 실패: $errorType")
            next()
        }
    }

    override fun start(onResult: (ok: Boolean) -> Unit) {
        val engine = TextToSpeech(context, Mode.ON_DEVICE)
        synchronized(lock) { tts = engine }
        engine.initialize(
            object : OnInitializeListener {
                override fun onSuccess() {
                    val ok = try {
                        engine.addEventListener(events)
                        engine.setLocale(Locale.KOREA)
                        true
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "한국어로 맞추지 못했어요", e)
                        false
                    }
                    synchronized(lock) { ready = ok }
                    onResult(ok)
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "초기화 실패: $errorType")
                    onResult(false)
                }
            },
        )
    }

    override fun speak(utterance: Utterance) {
        synchronized(lock) {
            if (!ready) return
            // 끝 알림이 오지 않아 멈춰 있으면(말 길이 + 여유가 지남) 다음 말을 막지 않는다.
            if (speaking && SystemClock.elapsedRealtime() > speakingUntilMs) speaking = false
            if (speaking) {
                queue.addLast(utterance)
                while (queue.size > MAX_QUEUE) queue.removeFirst()
                return
            }
            say(utterance)
        }
    }

    override fun stop() {
        synchronized(lock) {
            queue.clear()
            speaking = false
            if (ready) runCatching { tts?.stop() }
        }
    }

    override fun release() {
        synchronized(lock) {
            ready = false
            speaking = false
            queue.clear()
            runCatching { tts?.release() }
            tts = null
        }
    }

    /** lock 안에서 부른다. */
    private fun say(utterance: Utterance) {
        val engine = tts ?: return
        speaking = true
        val durMs = ((utterance.dur ?: InMemoryVoiceLog.speakDur(utterance.text)) * 1000).toLong()
        speakingUntilMs = SystemClock.elapsedRealtime() + durMs + STUCK_SLACK_MS
        try {
            engine.speak(utterance.text, true)
        } catch (e: RuntimeException) {
            Log.w(TAG, "말하기 실패: ${utterance.key}", e)
            speaking = false
        }
    }

    private fun next() {
        synchronized(lock) {
            speaking = false
            queue.removeFirstOrNull()?.let(::say)
        }
    }

    private companion object {
        const val TAG = "PleosTts"
        const val MAX_QUEUE = 4
        const val STUCK_SLACK_MS = 5_000L
    }
}
