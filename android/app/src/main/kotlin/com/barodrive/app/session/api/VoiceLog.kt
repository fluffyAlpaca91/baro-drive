package com.barodrive.app.session.api

import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.Category
import com.barodrive.core.model.Coach
import kotlinx.coroutines.flow.StateFlow

/**
 * 음성 기록과 자막 (프로토타입 app/voice.js `BaroApp.voice`, 설계 2.6·6.9). 코치·내비가 말한 것과 코치가 참은 것(막힌 말과 이유)을
 * 한 목록에 시간 순으로 남긴다. 주행·리뷰 화면과 시연 패널 '말한 내용'이 읽는다.
 * 소리(TTS)는 구현이 따로 낸다 — 소리가 안 나도 기록·자막 상태는 늘 있다.
 * 엔진 출력의 말·막힌 말은 세션 관리자가 넣는다. 화면이 직접 말할 때는(정차 중 말) [sayLine]만 쓴다.
 */
interface VoiceLog {
    /** 기록 전부, 오래된 것이 앞(최대 [MAX_ENTRIES]줄). */
    val entries: StateFlow<List<VoiceEntry>>

    /** 지금 자막에 보이는 줄(코치 자리·내비 자리). */
    val captions: StateFlow<Captions>

    /**
     * 정차 중 말(voice.js `sayLine`): 지금 코치·호칭으로 대사를 그려 말하고 기록한다. 정차 중 말끼리는 차례로 말한다
     * (기다리는 말은 3개까지, 같은 키는 새 것으로). 대사를 그리지 못하면 null.
     * [coach]를 주면 그 코치로 그리고 그 목소리로 말한다(voice.js `opts.coach` — CO-1·ON-5 [미리 듣기]). 없으면 지금 프로필 코치.
     */
    fun sayLine(
        key: String,
        vars: Map<String, Any?> = emptyMap(),
        item: String? = null,
        category: Category = Category.INFO,
        coach: Coach? = null,
    ): VoiceEntry?

    /** 기록을 비운다(voice.js `clear`). 자막은 그대로. */
    fun clear()

    companion object {
        /** 기록 줄 상한(voice.js `MAX_ENTRIES`). */
        const val MAX_ENTRIES = 2000
    }
}

/** 누가 말했나 (voice.js `speaker`). */
enum class Speaker { COACH, NAVI }

/**
 * 기록 한 줄 (voice.js 기록 `{ seq, t, speaker, coach, item, key, category, text, dur, spoken, blocked, reason, stationary, ff }`).
 * 말한 줄: [spoken] 참 · [reason] null. 막힌 줄: [spoken] 거짓 · [reason] 코드.
 */
data class VoiceEntry(
    /** 1부터 늘어나는 순번. */
    val seq: Long,
    /** 세션 초(정차 중 말은 그때 sim 시각). 모르면 null. */
    val t: Double?,
    val speaker: Speaker,
    /** 코치 말이면 그 코치, 내비면 null. */
    val coach: Coach?,
    val item: String? = null,
    val key: String? = null,
    val category: Category? = null,
    val text: String,
    /** 말하는 시간 sim초(글자 수 × 0.15 + 0.4, 소수 둘째 자리). */
    val dur: Double,
    val spoken: Boolean,
    val reason: BlockReason? = null,
    /** 정차 중 말(차례로 말함). */
    val stationary: Boolean = false,
    /** 빨리 감기 중에 생긴 줄. */
    val ff: Boolean = false,
) {
    val blocked: Boolean get() = !spoken
}

/** 지금 자막에 보이는 줄 (voice.js `current()`). */
data class Captions(val coach: VoiceEntry? = null, val navi: VoiceEntry? = null)

/** 기록 시각 글 'mm:ss'(한 시간 넘으면 'h:mm:ss'), 모르면 '--:--' (voice.js `fmtT`). */
fun fmtT(t: Double?): String {
    if (t == null || !t.isFinite()) return "--:--"
    val s = kotlin.math.floor(t).toLong().coerceAtLeast(0)
    val hh = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    val head = if (hh > 0) "$hh:" + mm.toString().padStart(2, '0') else mm.toString().padStart(2, '0')
    return head + ":" + ss.toString().padStart(2, '0')
}
