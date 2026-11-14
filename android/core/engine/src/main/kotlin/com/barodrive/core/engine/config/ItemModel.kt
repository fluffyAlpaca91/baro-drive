package com.barodrive.core.engine.config

import com.barodrive.core.engine.content.ItemGroup as ContentGroup
import com.barodrive.core.model.ItemDef
import com.barodrive.core.model.ItemGroup
import com.barodrive.core.model.ItemLock
import com.barodrive.core.model.SignalKey
import com.barodrive.core.engine.content.ItemDef as ContentItem

/**
 * 콘텐츠 항목 정의(`content/items.json`, 단 하나의 원본) → 모델 [ItemDef](파사드가 화면에 주는 모양).
 * 두 타입의 칸은 같고 신호 이름만 [SignalKey]로, 작은 글(note)은 키 → 글 맵으로 바꾼다.
 */
internal fun ContentItem.toModel(): ItemDef = ItemDef(
    id = id,
    order = order,
    name = name,
    short = short,
    icon = icon.takeIf { it.isNotEmpty() },
    group = when (group) {
        ContentGroup.SAFETY -> ItemGroup.SAFETY
        ContentGroup.RULE -> ItemGroup.RULE
        ContentGroup.DRIVE -> ItemGroup.DRIVE
        ContentGroup.PARKING -> ItemGroup.PARKING
        ContentGroup.HILL -> ItemGroup.HILL
    },
    cats = cats,
    defaults = defaults,
    allowed = if (fixed) emptyList() else allowed,
    fixed = fixed,
    extra = extra,
    needs = needs.mapNotNull(SignalKey::fromKey),
    uses = uses.mapNotNull(SignalKey::fromKey),
    fade = fade,
    first2 = first2,
    guide = guide,
    card = card,
    fb2 = fb2,
    lock = lock?.let { ItemLock(all = it.all, off = it.off, after = it.after, now = it.now) },
    note = note?.let { n ->
        listOfNotNull(
            n.wx?.let { "wx" to it }, n.first2On?.let { "first2On" to it }, n.first2Off?.let { "first2Off" to it },
            n.rule?.let { "rule" to it }, n.needs?.let { "needs" to it },
        ).toMap().takeIf { it.isNotEmpty() }
    },
)
