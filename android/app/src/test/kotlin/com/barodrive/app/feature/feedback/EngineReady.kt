package com.barodrive.app.feature.feedback

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.Profile
import org.junit.Assume.assumeTrue

/**
 * 엔진 파사드의 항목 정의·이번 회차 값(itemIds·itemDef·resolveItems — engine-core 몫)이 옮겨졌는가.
 * 아직 STUB이면 그 값에 기대는 시험은 건너뛴다(엔진이 들어오면 저절로 돈다).
 */
internal fun assumeItemsPorted() {
    assumeTrue(
        "엔진 항목 정의·resolveItems가 아직 STUB이에요(engine-core)",
        BaroEngine.itemIds().isNotEmpty() && BaroEngine.resolveItems(1, Profile()).isNotEmpty(),
    )
}
