package com.barodrive.core.engine.session

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.content.LineRenderException
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.review.round2
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.Category
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.UtteranceLog
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.abs

/** 후보가 버려지는 까닭 (core.js `expiry`의 돌려주는 값). */
private enum class Expiry { DROPPED, UNDER50, ALIVE, TIME }

/**
 * 전달 관리자 (설계 6.5.2, 프로토타입 core.js `manage`·`expiry`·`expire`·`blockReason`·`speak`).
 * 스냅숏마다: 신호 대기 카드 열기·닫기 → 만료된 후보 버리기(막힌 말로, 만료된 교정은 카드로) → 막는 규칙 보기 → 막히지 않은 후보 중
 * 하나(안전 > 사전 안내·안내 > 칭찬 > 교정, 같으면 먼저 생긴 것)를 말한다.
 *
 * 막는 규칙(첫 규칙이 이유): 2 소리 끔 · 3 말하는 중 · 4 내비 음성 + 1초 · (P에서는 여기까지) · 5 큰 조향·급제동·합류·지정 침묵 ·
 * 6 혼잡 · 7 간격 20초(같은 회전 C1→C2 짝·K8 답은 예외) · 8 10분에 6번 · 9 교정 비율. 안전은 6~9를 받지 않고,
 * 단계 안내(guide)는 5(큰 조향)·6~9를 받지 않는다(결정사항 E T1c-3).
 */
internal class DeliveryRules(private val core: SessionCore) {
    private var speakingUntil = Double.NEGATIVE_INFINITY
    private var lastEnd = Double.NEGATIVE_INFINITY

    /** 말한 것, 시간 순. */
    val spoken = ArrayList<Spoken>()
    private var lastSpoken: Spoken? = null
    private var praiseCount = 0
    private var fixCount = 0

    fun manage(snap: VehicleSnapshot?, t: Double, waitStart: Boolean, waitEnd: Boolean) {
        if (waitEnd) core.cards.close() // 움직이면 닫히고 다시 보이지 않는다
        if (waitStart) core.cards.show(t)
        val pending = core.pending
        var i = 0
        while (i < pending.size) {
            val c = pending[i]
            val why = expiry(c, t)
            if (why != null) {
                pending.removeAt(i)
                expire(c, why, t)
            } else {
                i++
            }
        }
        val free = ArrayList<Candidate>()
        i = 0
        while (i < pending.size) {
            val c = pending[i]
            val r = blockReason(c, snap, t)
            if (r == BlockReason.MUTED) {
                pending.removeAt(i)
                core.logBlocked(c, BlockReason.MUTED, t)
                continue
            }
            if (r != null) c.lastReason = r else free += c
            i++
        }
        if (free.isEmpty()) return
        val c = free.sortedWith(compareBy<Candidate>({ it.priority }, { it.createdAt }, { it.seq })).first() // 10 고르기
        pending.remove(c)
        speak(c, t, snap?.gear == Gear.P)
    }

    private fun expiry(c: Candidate, t: Double): Expiry? {
        val pair = c.pairWith
        if (pair != null && core.dropped(pair)) return Expiry.DROPPED
        if (c.category == Category.PRE && pair != null) {
            val turn = core.tracker.turn(pair)
            if (turn != null && (turn.reachedT != null || turn.dist < Thresholds["C1.min"] - EPS)) return Expiry.UNDER50
        }
        val alive = c.alive
        if (alive != null) {
            val ok = try {
                alive()
            } catch (e: Exception) {
                core.logError("후보 ${c.key} alive() 오류", e)
                false
            }
            if (!ok) return Expiry.ALIVE
        }
        if (t >= c.expiresAt - EPS) return Expiry.TIME
        return null
    }

    private fun expire(c: Candidate, why: Expiry, t: Double) {
        if (why == Expiry.DROPPED) return
        val reason = when {
            why == Expiry.UNDER50 -> BlockReason.UNDER_50
            why == Expiry.ALIVE && c.expireReason != null -> c.expireReason
            else -> c.lastReason ?: BlockReason.EXPIRED
        }
        core.logBlocked(c, reason, t)
        if (c.category == Category.FIX && c.cardable && c.fromCard == null) core.cards.fromCandidate(c) // 만료된 교정 → 카드(6.5.1)
    }

    /** 같은 회전의 사전 안내(C1) 바로 뒤 짝(C2)인가 — 간격 예외(규칙 7). */
    private fun pairOk(c: Candidate): Boolean {
        val l = lastSpoken ?: return false
        return c.pairWith != null && l.pairWith == c.pairWith && l.category == Category.PRE
    }

    private fun recentCount(t: Double): Int {
        var n = 0
        for (i in spoken.indices.reversed()) {
            val u = spoken[i]
            if (u.t <= t - Thresholds["cap.window"] + EPS) break
            if (u.counted) n++
        }
        return n
    }

    /** 막는 첫 규칙의 이유. P에서 하는 말(PB2·PK3·HL3 등)은 주행 중 말 규칙(5~9)을 받지 않는다(결정사항 E T1c-3). */
    private fun blockReason(c: Candidate, snap: VehicleSnapshot?, t: Double): BlockReason? {
        val safety = c.category == Category.SAFETY
        if (core.muted && !safety) return BlockReason.MUTED // 2 소리 끄기
        if (t < speakingUntil - EPS) return BlockReason.GAP // 3 말하는 중
        val nav = snap?.nav
        if (nav?.voice == true || t < core.navVoiceUntil - EPS) return BlockReason.NAV_VOICE // 4 내비 음성 + 1초
        if (snap?.gear == Gear.P) return null
        val guide = c.guide
        val steer = snap?.steer
        if (!guide && steer != null && abs(steer) >= Thresholds["steer.big"] - EPS) return BlockReason.SILENCE // 5 큰 조향
        if (core.tracker.hardBrake || core.inSilence(t)) return BlockReason.SILENCE // 5 급제동·합류·지정
        if (!guide && !safety && c.category != Category.PRE && nav?.congested == true) return BlockReason.JAM // 6 혼잡
        if (!guide && !safety && !c.answer && !pairOk(c) && t < lastEnd + Thresholds["gap"] - EPS) return BlockReason.GAP // 7 간격
        if (!guide && !safety && recentCount(t) >= Thresholds["cap.n"]) return BlockReason.CAP // 8 상한
        if (!guide && c.category == Category.FIX && fixCount >= praiseCount / 2 + 1) return BlockReason.RATIO // 9 비율
        return null
    }

    private fun speak(c: Candidate, t: Double, inP: Boolean) {
        val text = if (c.fromCard != null) {
            c.text
        } else {
            try {
                core.render(c.key, c.vars, peek = false)
            } catch (e: LineRenderException) {
                core.renderFailed("말", c.item, c.key, c.category, e, t)
                return
            }
        }
        val dur = Lines.speakDur(text)
        speakingUntil = t + dur
        lastEnd = t + dur
        val u = Spoken(t, t + dur, c.item, c.key, c.category, c.pairWith, counted = !inP)
        spoken += u
        lastSpoken = u
        if (c.category == Category.PRAISE) praiseCount++ else if (c.category == Category.FIX) fixCount++
        core.utterances += UtteranceLog(t = t, item = c.item, key = c.key, category = c.category, text = text, spoken = true, reason = null)
        core.out?.say?.add(
            Utterance(key = c.key, text = text, category = c.category, item = c.item, t = t, dur = round2(dur), pairWith = c.pairWith, coach = core.cfg.coach),
        )
    }
}
