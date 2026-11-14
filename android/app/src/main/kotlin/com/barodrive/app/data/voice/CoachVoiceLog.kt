package com.barodrive.app.data.voice

import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.di.ApplicationScope
import com.barodrive.app.session.SessionEngine
import com.barodrive.app.session.api.Captions
import com.barodrive.app.session.api.InMemoryVoiceLog
import com.barodrive.app.session.api.VoiceEntry
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.BlockedUtterance
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Profile
import com.barodrive.core.model.Utterance
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 앱의 코치 음성 (프로토타입 app/voice.js): 기록·자막은 [InMemoryVoiceLog]에, 소리는 [CoachVoice](TTS)로.
 * 소리가 안 나도(TTS 없음·소리 끔) 기록과 자막은 그대로다. 대사는 지금 프로필의 코치·호칭으로 엔진 파사드가 그린다.
 * 세션 관리자가 엔진 출력을 [say]·[blocked]로 넣고, 화면은 [VoiceLog]로 읽고 정차 중 말만 [sayLine]으로 한다.
 */
@Singleton
class CoachVoiceLog @Inject constructor(
    private val tts: CoachVoice,
    private val engine: SessionEngine,
    profiles: ProfileRepository,
    @ApplicationScope scope: CoroutineScope,
) : VoiceLog {
    private val profile: StateFlow<Profile> = profiles.profile.stateIn(scope, SharingStarted.Eagerly, Profile())

    /** 정차 중 말의 시각(세션 sim초). 세션 관리자가 틱마다 넣는다. */
    @Volatile
    var simT: Double? = null

    private val log = InMemoryVoiceLog(
        render = { key, vars -> profile.value.let { engine.render(key, it.coach, vars, it.honorific) } },
        coach = { profile.value.coach },
        now = { simT },
        renderAs = { key, vars, coach -> engine.render(key, coach, vars, profile.value.honorific) },
    )

    override val entries: StateFlow<List<VoiceEntry>> get() = log.entries

    override val captions: StateFlow<Captions> get() = log.captions

    override fun sayLine(key: String, vars: Map<String, Any?>, item: String?, category: Category, coach: Coach?): VoiceEntry? =
        log.sayLine(key, vars, item, category, coach)?.also { speak(it) }

    override fun clear() = log.clear()

    /** 엔진이 말한 것: 기록·자막 + 소리. */
    fun say(u: Utterance, ff: Boolean = false): VoiceEntry {
        val e = log.say(u, ff)
        if (!e.ff) tts.speak(u)
        return e
    }

    /** 엔진이 막은 말: 기록만. */
    fun blocked(b: BlockedUtterance): VoiceEntry = log.blocked(b)

    /** 정차 중에 막힌 말(L1·PB1이 S·X): 기록만. */
    fun blockedLine(key: String, item: String?, reason: BlockReason, vars: Map<String, Any?> = emptyMap()): VoiceEntry =
        log.blockedLine(key, item, reason, vars)

    /** 내비 음성: 기록·자막만(소리는 내비 앱이 낸다). 빈 글은 무시(null). */
    fun navi(text: String, t: Double?, dur: Double? = null, key: String? = null): VoiceEntry? = log.navi(text, t, dur, key)

    /** 자막 시간을 민다(sim초). */
    fun advance(dtSim: Double) = log.advance(dtSim)

    /** 출발: 정차 중 말을 거둔다(자막·기다리는 말). 소리도 멈춘다. */
    fun dropStationary() {
        log.dropStationary()
    }

    /** 빨리 감기 켜고 끄기. */
    fun setFastForward(on: Boolean) = log.setFastForward(on)

    /** 하던 소리와 기다리던 소리를 멈춘다(세션을 버릴 때). 기록은 그대로. */
    fun stopSound() = tts.stop()

    private fun speak(e: VoiceEntry) {
        tts.speak(Utterance(key = e.key.orEmpty(), text = e.text, category = e.category ?: Category.INFO, item = e.item, t = e.t ?: 0.0, dur = e.dur, coach = e.coach))
    }
}
