package com.barodrive.core.engine.session

import com.barodrive.core.engine.detect.CardSpec
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category

/** 검출기가 부탁한 말 (core.js `ctx.say({ … })` 인자). */
internal class SayRequest(
    val key: String,
    val item: String?,
    val vars: Map<String, Any?>,
    val category: Category?,
    val pairWith: String?,
    val expiresIn: Double?,
    val alive: (() -> Boolean)?,
    val expireReason: BlockReason?,
    val cardable: Boolean,
    val card: CardSpec?,
    val answer: Boolean,
    val guide: Boolean,
    val priority: Int?,
)

/** 검출기가 부탁한 카드 (core.js `ctx.card({ … })` 인자). */
internal class CardRequest(
    val item: String?,
    val key: String?,
    val vars: Map<String, Any?>,
    val text: String?,
    val icon: String?,
    val title: String,
    val detail: String,
    val category: CardCategory,
    val turnId: String?,
    val speak: Boolean,
)

/**
 * 말 후보 (설계 6.5.1, core.js say()의 `c`). 전달 관리자가 만료 전까지 들고 있다가 막는 규칙이 없을 때 말한다.
 * [text]는 미리 그린 문장(카드에서 온 후보는 카드 문장 그대로 말함), [lastReason]은 마지막으로 막은 규칙.
 */
internal class Candidate(
    val seq: Int,
    val item: String?,
    val key: String,
    val vars: Map<String, Any?>,
    val category: Category,
    val priority: Int,
    val createdAt: Double,
    val pairWith: String?,
    val alive: (() -> Boolean)?,
    val expireReason: BlockReason?,
    val cardable: Boolean,
    val card: CardSpec?,
    val answer: Boolean,
    val guide: Boolean,
    val text: String,
    val fromCard: String?,
    val expiresAt: Double,
) {
    val id: String get() = "u$seq"
    var lastReason: BlockReason? = null
}

/** 신호 대기 카드 줄의 카드 (core.js card()의 `cd`). [shownAt]이 있으면 보인 카드. */
internal class QueuedCard(
    val id: String,
    val item: String?,
    val key: String?,
    val vars: Map<String, Any?>,
    val icon: String?,
    val title: String,
    val detail: String,
    val category: CardCategory,
    val createdAt: Double,
    val turnId: String?,
    val speak: Boolean,
    var text: String?,
) {
    var shownAt: Double? = null
}

/** 말한 것 (core.js `S.spoken[]`). [counted] = P가 아닐 때 말함(상한·K6에 셈). */
internal class Spoken(
    val t: Double,
    val end: Double,
    val item: String?,
    val key: String,
    val category: Category,
    val pairWith: String?,
    val counted: Boolean,
)

/** 침묵 (core.js `S.silences[]`): [until] sim초까지 말하지 않음. [reason] 'F2' · 'merge' · 검출기가 정한 글. */
internal class Silence(val until: Double, val reason: String)

/** 카드 분류 → 말 분류 (core.js `speechCat`): 안전 → 안전, 칭찬 → 칭찬, 그 밖 → 교정. */
internal fun CardCategory.speech(): Category = when (this) {
    CardCategory.SAFETY -> Category.SAFETY
    CardCategory.PRAISE -> Category.PRAISE
    else -> Category.FIX
}
