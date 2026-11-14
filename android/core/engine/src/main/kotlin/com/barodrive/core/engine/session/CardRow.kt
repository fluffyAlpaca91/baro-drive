package com.barodrive.core.engine.session

import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.content.Items
import com.barodrive.core.engine.content.LineRenderException
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.Card
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category
import com.barodrive.core.model.Delivery

/**
 * 신호 대기 카드 줄 (설계 6.5.3, 프로토타입 core.js `card`·`cardFromCandidate`·`showCards`·`cardView`).
 * 신호 대기가 시작되면 최대 2장: 안전 > 교정(오래된 것부터) > 요령. 교정이 없을 때만 가장 최근 칭찬 1장.
 * 못 보인 교정·칭찬은 리포트로(다시 안 보임), 안전·요령은 다음 신호 대기로. 맨 앞 카드 문장 하나만 말 후보가 된다(요령 빼고).
 * 차가 움직이면 닫히고 다시 보이지 않는다.
 */
internal class CardRow(private val core: SessionCore) {
    private val queue = ArrayList<QueuedCard>()
    private var visible: List<QueuedCard> = emptyList()
    private var seq = 0

    fun add(detId: String, x: CardRequest): String? {
        val t = core.now()
        val category = x.category
        var text = x.text
        if (x.key != null) {
            text = try {
                core.render(x.key, x.vars, peek = true)
            } catch (e: LineRenderException) {
                core.renderFailed(detId, x.item, x.key, category.speech(), e, t)
                return null
            }
        }
        val def = x.item?.let(Items::def)
        val cd = QueuedCard(
            id = "card-${++seq}", item = x.item, key = x.key, vars = x.vars,
            icon = x.icon?.takeIf { it.isNotEmpty() } ?: def?.icon?.takeIf { it.isNotEmpty() }, title = x.title, detail = x.detail,
            category = category, createdAt = t, turnId = x.turnId, speak = x.speak && category != CardCategory.TIP && x.key != null,
            text = text,
        )
        val dv = core.deliver(cd.item)
        if (dv != Delivery.NOW) {
            val key = cd.key
            if (key != null) {
                core.logBlocked(cd.item, key, category.speech(), text ?: "", if (dv == Delivery.AFTER) BlockReason.AFTER else BlockReason.OFF, t)
            }
            return null
        }
        if (core.finishing || core.finished) return null
        if (cd.turnId != null && core.dropped(cd.turnId)) return null
        queue += cd
        return cd.id
    }

    /** 만료된 교정 후보 → 다음 신호 대기 카드 (core.js `cardFromCandidate`). */
    fun fromCandidate(c: Candidate) {
        val cc = c.card
        val def = c.item?.let(Items::def)
        queue += QueuedCard(
            id = "card-${++seq}", item = c.item, key = c.key, vars = c.vars,
            icon = cc?.icon?.takeIf { it.isNotEmpty() } ?: def?.icon?.takeIf { it.isNotEmpty() }, title = cc?.title.orEmpty(),
            detail = cc?.detail.orEmpty(), category = CardCategory.FIX, createdAt = c.createdAt, turnId = c.pairWith, speak = true,
            text = c.text,
        )
    }

    /** 신호 대기 시작: 줄에서 최대 2장을 보이고 맨 앞 카드 문장을 말 후보로 (core.js `showCards`). */
    fun show(t: Double) {
        fun by(cat: CardCategory) = queue.filter { it.category == cat }.sortedBy { it.createdAt }
        val fix = by(CardCategory.FIX)
        val praise = by(CardCategory.PRAISE)
        val ordered = by(CardCategory.SAFETY) + fix + (if (fix.isNotEmpty()) emptyList() else praise.takeLast(1)) + by(CardCategory.TIP)
        val vis = ordered.take(Thresholds["card.max"].toInt())
        // 못 보인 교정·칭찬은 리포트로(설계 6.5.3 '방금' 일만), 안전·요령은 다음 신호 대기로 남긴다
        val keep = queue.filter { (it.category == CardCategory.SAFETY || it.category == CardCategory.TIP) && vis.none { v -> v === it } }
        queue.clear()
        queue += keep
        for (c in vis) {
            c.shownAt = t
            val key = c.key ?: continue
            try {
                c.text = core.render(key, c.vars, peek = false)
            } catch (e: LineRenderException) {
                core.logError("카드 $key · ${e.message}")
            }
        }
        visible = vis
        val front = vis.firstOrNull() ?: return
        val key = front.key
        if (!front.speak || key == null) return
        val cat = front.category.speech()
        val cand = Candidate(
            seq = ++core.seq, item = front.item, key = key, vars = front.vars, category = cat, priority = cat.priority, createdAt = t,
            pairWith = null, alive = { visible.any { it === front } }, expireReason = null, cardable = false, card = null, answer = false,
            guide = false, text = front.text.orEmpty(), fromCard = front.id, expiresAt = t + Thresholds["safety.maxWait"],
        )
        if (core.muted && cat != Category.SAFETY) core.logBlocked(cand, BlockReason.MUTED, t) else core.pending += cand
    }

    /** 신호 대기 끝: 보이던 카드를 닫는다. */
    fun close() {
        visible = emptyList()
    }

    /** 놓친·건너뛴 회전의 카드를 버린다. */
    fun dropTurn(id: String) {
        queue.removeAll { it.turnId != null && it.turnId == id }
    }

    /** 회차 끝: 남은 카드는 리포트로. */
    fun clear() {
        queue.clear()
        visible = emptyList()
    }

    /** 지금 보이는 카드 (core.js `cardView`). */
    fun visibleView(): List<Card> = visible.map { c ->
        Card(
            key = c.key, category = c.category, title = c.title, detail = c.detail, item = c.item, createdAt = c.createdAt, id = c.id,
            icon = c.icon, text = c.text, shownAt = c.shownAt,
        )
    }
}
