package com.barodrive.app.data.voice

import com.barodrive.core.model.Utterance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 소리를 내는 엔진 하나 (Pleos TTS·안드로이드 TTS). [AutoCoachVoice]가 차례로 켜 보고 켜진 것을 쓴다. */
interface SpeechEngine {
    /** 로그에 남길 이름. */
    val name: String

    /** 엔진을 켠다. 결과는 한 번만 [onResult]로 온다(어느 스레드든, 바로 올 수도 있다). 한 번만 부른다. */
    fun start(onResult: (ok: Boolean) -> Unit)

    /** 앞 말이 끝난 뒤 차례로 말한다. 켜지지 않았으면 무시. */
    fun speak(utterance: Utterance)

    /** 하던 말과 기다리던 말을 멈춘다. */
    fun stop()

    /** 엔진을 놓는다(쓰지 않기로 했을 때). */
    fun release()
}

/**
 * 코치 음성 고르기: 처음 말할 때 엔진을 차례로 켜 본다 — Pleos TTS(ON_DEVICE, 한국어) → 안드로이드 TTS → 소리 없음(말풍선·자막만).
 * 켜지는 동안 온 말은 쌓아 두었다가(최대 [MAX_PENDING]) 켜진 엔진으로 말한다. 엔진이 [timeoutMs] 안에 답하지 않으면 다음 엔진으로.
 * 어느 엔진을 쓰는지 로그에 남긴다. 말할지 말지는 엔진의 전달 관리자가 이미 정했다 — 여기서는 소리만.
 */
class AutoCoachVoice(
    private val engines: List<SpeechEngine>,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = INIT_TIMEOUT_MS,
    private val log: (String) -> Unit = { msg -> runCatching { android.util.Log.i(TAG, msg) } },
) : CoachVoice {
    private enum class State { NEW, STARTING, READY, SILENT }

    private val lock = Any()
    private var state = State.NEW
    private var index = -1
    private var attempt = 0
    private var timeout: Job? = null
    private var active: SpeechEngine? = null
    private val pending = ArrayDeque<Utterance>()

    /** 지금 쓰는 엔진 이름(아직 고르는 중이거나 소리 없음이면 null). */
    val engineName: String? get() = synchronized(lock) { active?.name }

    @Volatile
    override var muted: Boolean = false
        set(value) {
            field = value
            if (value) stop()
        }

    override fun speak(utterance: Utterance) {
        if (muted) return
        synchronized(lock) {
            when (state) {
                State.NEW -> {
                    pending.addLast(utterance)
                    state = State.STARTING
                    tryNext()
                }
                State.STARTING -> pending.addLast(utterance)
                State.READY -> active?.speak(utterance)
                State.SILENT -> Unit
            }
            while (pending.size > MAX_PENDING) pending.removeFirst()
        }
    }

    override fun stop() {
        synchronized(lock) {
            pending.clear()
            active?.stop()
        }
    }

    /** lock 안에서 부른다. 다음 엔진을 켜 본다. 없으면 소리 없음. */
    private fun tryNext() {
        index++
        val engine = engines.getOrNull(index)
        if (engine == null) {
            state = State.SILENT
            pending.clear()
            log("코치 음성: 쓸 수 있는 TTS 엔진이 없어요 — 소리 없이 말풍선·자막만 보여요")
            return
        }
        val gen = ++attempt
        timeout = scope.launch {
            delay(timeoutMs)
            onResult(engine, gen, ok = false, timedOut = true)
        }
        try {
            engine.start { ok -> onResult(engine, gen, ok, timedOut = false) }
        } catch (e: RuntimeException) {
            onResult(engine, gen, ok = false, timedOut = false)
        } catch (e: LinkageError) { // SDK 클래스가 없는 기기
            onResult(engine, gen, ok = false, timedOut = false)
        }
    }

    private fun onResult(engine: SpeechEngine, gen: Int, ok: Boolean, timedOut: Boolean) {
        synchronized(lock) {
            if (gen != attempt || state != State.STARTING) {
                // 늦게 온 답(시간 초과 뒤 성공 등): 이미 다른 엔진으로 넘어갔으니 놓는다.
                if (ok && active !== engine) runCatching { engine.release() }
                return
            }
            if (!timedOut) timeout?.cancel()
            timeout = null
            if (ok) {
                active = engine
                state = State.READY
                log("코치 음성: ${engine.name}")
                while (pending.isNotEmpty()) engine.speak(pending.removeFirst())
            } else {
                log("코치 음성: ${engine.name}을(를) 쓰지 못했어요${if (timedOut) "(시간 초과)" else ""} — 다음 엔진을 켜 봐요")
                runCatching { engine.release() }
                tryNext()
            }
        }
    }

    companion object {
        private const val TAG = "CoachVoice"

        /** 켜지는 동안 쌓아 둘 말 수. */
        const val MAX_PENDING = 4

        /** 엔진 하나를 켜 보는 시간. */
        const val INIT_TIMEOUT_MS = 5_000L
    }
}
