package com.barodrive.app.feature.coach

import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType

/**
 * 호칭 값 규칙 (프로토타입 coach.js `normHonorific`·`honorificValid`·`honorificLabel`, 설계 6.6.1, 결정-77).
 * 칩 4개(우리 딸 · 우리 아들 · 직접 쓰기 · 부르지 않기). 직접 쓴 말은 앞뒤 공백을 빼고 8자까지, 비어 있으면 '부르지 않기'.
 */
object Honorifics {
    /** 칩 순서(copy.js `honorifics.order`). */
    val ORDER: List<HonorificType> = listOf(HonorificType.DAUGHTER, HonorificType.SON, HonorificType.CUSTOM, HonorificType.NONE)

    /** 저장할 모양(coach.js `normHonorific`). */
    fun normalize(h: Honorific?): Honorific {
        if (h == null) return Honorific()
        if (h.type != HonorificType.CUSTOM) return Honorific(h.type, "")
        val text = h.text.trim().take(Honorific.MAX_LENGTH)
        return if (text.isEmpty()) Honorific() else Honorific(HonorificType.CUSTOM, text)
    }

    /** 저장할 수 있나(coach.js `honorificValid`): 직접 쓰기면 한 글자 이상. */
    fun valid(h: Honorific?): Boolean = h == null || h.type != HonorificType.CUSTOM || h.text.trim().isNotEmpty()

    /** 줄에 보일 이름(coach.js `honorificLabel`): '우리 딸' · '지은아' · '부르지 않기'. */
    fun label(h: Honorific?): String {
        val v = normalize(h)
        return if (v.type == HonorificType.CUSTOM) v.text else v.type.label
    }
}

/**
 * 호칭 고르는 중인 값 (coach.js `honorificEditor`의 `type`·`customText`): 직접 쓰기 글은 다른 칩을 골랐다 돌아와도 남는다.
 */
data class HonorificDraft(val type: HonorificType = HonorificType.NONE, val customText: String = "") {
    /** 지금 고른 값(coach.js `current()`). 직접 쓰기면 쓰는 중인 글 그대로. */
    val current: Honorific get() = if (type == HonorificType.CUSTOM) Honorific(HonorificType.CUSTOM, customText) else Honorific(type, "")

    val valid: Boolean get() = Honorifics.valid(current)

    companion object {
        /** 저장된 호칭에서 시작(coach.js `normHonorific(o.value)`). */
        fun of(h: Honorific?): HonorificDraft {
            val v = Honorifics.normalize(h)
            return HonorificDraft(v.type, if (v.type == HonorificType.CUSTOM) v.text else "")
        }
    }
}
