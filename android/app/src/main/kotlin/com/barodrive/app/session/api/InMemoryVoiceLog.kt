package com.barodrive.app.session.api

import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.BlockedUtterance
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Utterance
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 메모리 안 음성 기록 (프로토타입 app/voice.js의 기록·자막 차례 부분, 소리 없이). 미리보기·테스트용이고, 소리를 내는 앱 구현도
 * 기록은 이것에 맡긴다. 자막은 말이 들어오면 그 자리에 뜨고, 정차 중 말은 앞 말이 끝날 때까지 기다린다([advance]로 시간을 민다).
 *
 * @param render 대사 키 → 문장(지금 코치·호칭으로). 기본은 키 그대로. 그리지 못하면 예외를 던지게 둔다 → [sayLine]이 null.
 * @param coach 지금 코치(기록 줄의 `coach`).
 * @param now 정차 중 말의 시각(sim초). 모르면 null.
 * @param renderAs 코치를 정해 그리기([sayLine]에 `coach`를 줄 때, voice.js `opts.coach`). 없으면 [render].
 */
class InMemoryVoiceLog(
    private val render: (key: String, vars: Map<String, Any?>) -> String = { key, _ -> key },
    private val coach: () -> Coach = { Coach.DAD },
    private val now: () -> Double? = { null },
    private val renderAs: ((key: String, vars: Map<String, Any?>, coach: Coach) -> String)? = null,
) : VoiceLog {
    private val lock = Any()
    private var seq = 0L
    private var ff = false

    /** 자막 칸 하나: 보이는 줄과 지금까지 말한 sim초. */
    private class Shown(val entry: VoiceEntry, var spoken: Double = 0.0) {
        val over: Boolean get() = spoken >= entry.dur
    }

    private var shownCoach: Shown? = null
    private var shownNavi: Shown? = null
    private val pending = ArrayDeque<VoiceEntry>()

    private val _entries = MutableStateFlow<List<VoiceEntry>>(emptyList())
    override val entries: StateFlow<List<VoiceEntry>> = _entries.asStateFlow()

    private val _captions = MutableStateFlow(Captions())
    override val captions: StateFlow<Captions> = _captions.asStateFlow()

    /** 기다리는 정차 중 말(voice.js `pending()`). */
    val pendingLines: List<VoiceEntry> get() = synchronized(lock) { pending.toList() }

    /** 엔진이 말한 것(voice.js `say`, 코치). */
    fun say(u: Utterance, ffNow: Boolean = false): VoiceEntry = add(
        speaker = Speaker.COACH, coach = u.coach ?: coach(), item = u.item, key = u.key, category = u.category,
        text = u.text, t = u.t, dur = u.dur, spoken = true, reason = null, stationary = false, ffNow = ffNow,
    )

    /** 엔진이 막은 말(voice.js `blocked`) — 기록만, 자막 없음. */
    fun blocked(b: BlockedUtterance): VoiceEntry = add(
        speaker = Speaker.COACH, coach = b.utterance.coach ?: coach(), item = b.utterance.item, key = b.utterance.key,
        category = b.utterance.category, text = b.utterance.text, t = b.utterance.t, dur = b.utterance.dur, spoken = false,
        reason = b.reason, stationary = false, ffNow = false,
    )

    /** 정차 중에 막힌 말(session.js `blockedLine` — L1·PB1이 S·X일 때). [reason]은 전달 방식 코드. */
    fun blockedLine(key: String, item: String?, reason: BlockReason, vars: Map<String, Any?> = emptyMap()): VoiceEntry = add(
        speaker = Speaker.COACH, coach = coach(), item = item, key = key, category = Category.INFO,
        text = runCatching { render(key, vars) }.getOrDefault(""), t = now(), dur = null, spoken = false, reason = reason,
        stationary = false, ffNow = false,
    )

    /** 내비 음성(voice.js 'sim:navVoice'). 빈 글은 무시(null). */
    fun navi(text: String, t: Double?, dur: Double? = null, key: String? = null): VoiceEntry? = if (text.isEmpty()) null else add(
        speaker = Speaker.NAVI, coach = null, item = null, key = key, category = null, text = text, t = t, dur = dur,
        spoken = true, reason = null, stationary = false, ffNow = false,
    )

    override fun sayLine(key: String, vars: Map<String, Any?>, item: String?, category: Category, coach: Coach?): VoiceEntry? {
        val as_ = renderAs
        val text = try {
            if (coach != null && as_ != null) as_(key, vars, coach) else render(key, vars)
        } catch (e: IllegalArgumentException) {
            return null
        } catch (e: IllegalStateException) {
            return null
        }
        return add(
            speaker = Speaker.COACH, coach = coach ?: coach(), item = item, key = key, category = category, text = text, t = now(),
            dur = null, spoken = true, reason = null, stationary = true, ffNow = false,
        )
    }

    override fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
    }

    /** 자막 시간을 [dtSim] sim초 민다: 말이 끝난 자막은 내리고 기다리던 정차 중 말을 띄운다(voice.js `frame`, 최소 표시 시간은 화면 몫). */
    fun advance(dtSim: Double) = synchronized(lock) {
        shownCoach?.let { it.spoken += dtSim }
        shownNavi?.let { it.spoken += dtSim }
        if (shownCoach?.over == true) shownCoach = pending.removeFirstOrNull()?.let(::Shown)
        if (shownNavi?.over == true) shownNavi = null
        if (shownCoach == null) shownCoach = pending.removeFirstOrNull()?.let(::Shown)
        publishCaptions()
    }

    /** 출발하면(기어가 P를 벗어나면) 정차 중 말을 거둔다 — 기다리던 것은 버리고 보이던 것은 내린다. 기록은 그대로(voice.js 'sim:gear'). */
    fun dropStationary() = synchronized(lock) {
        pending.clear()
        if (shownCoach?.entry?.stationary == true) shownCoach = null
        publishCaptions()
    }

    /** 빨리 감기 시작·끝(voice.js `onTick`·`endFF`): 끝나면 자막에는 마지막 말 하나만 남긴다. */
    fun setFastForward(on: Boolean) = synchronized(lock) {
        if (ff == on) return@synchronized
        ff = on
        if (!on) {
            val last = _entries.value.lastOrNull { it.ff && it.spoken }
            if (last != null) {
                pending.clear()
                shownCoach = null
                shownNavi = null
                if (last.speaker == Speaker.NAVI) shownNavi = Shown(last) else shownCoach = Shown(last)
                publishCaptions()
            }
        }
    }

    private fun add(
        speaker: Speaker, coach: Coach?, item: String?, key: String?, category: Category?, text: String, t: Double?,
        dur: Double?, spoken: Boolean, reason: BlockReason?, stationary: Boolean, ffNow: Boolean,
    ): VoiceEntry = synchronized(lock) {
        val shownText = text.ifEmpty { key?.let { "($it)" } ?: "" }
        val e = VoiceEntry(
            seq = ++seq, t = t, speaker = speaker, coach = if (speaker == Speaker.COACH) coach else null, item = item, key = key,
            category = category, text = shownText, dur = dur?.takeIf { it > 0 } ?: speakDur(shownText), spoken = spoken,
            reason = if (spoken) null else reason, stationary = stationary, ff = ff || ffNow,
        )
        _entries.update { (it + e).takeLast(VoiceLog.MAX_ENTRIES) }
        if (spoken && !e.ff) show(e)
        e
    }

    /** lock 안에서 부른다. */
    private fun show(e: VoiceEntry) {
        when {
            e.speaker == Speaker.NAVI -> shownNavi = Shown(e)
            e.stationary && shownCoach?.over == false -> {
                pending.removeAll { it.key != null && it.key == e.key }
                pending.addLast(e)
                while (pending.size > QUEUE_MAX) pending.removeFirst()
            }
            else -> shownCoach = Shown(e)
        }
        publishCaptions()
    }

    private fun publishCaptions() {
        _captions.value = Captions(coach = shownCoach?.entry, navi = shownNavi?.entry)
    }

    companion object {
        /** 기다리는 정차 중 말 상한(voice.js `QUEUE_MAX`). */
        const val QUEUE_MAX = 3

        /** 말하는 시간 sim초(설계 2.6: 글자 수 × 0.15 + 0.4, 소수 둘째 자리 — voice.js `speakDur`). */
        fun speakDur(text: String): Double = ((text.length * 0.15 + 0.4) * 100).roundToLong() / 100.0
    }
}
