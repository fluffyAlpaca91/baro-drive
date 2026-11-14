package com.barodrive.core.engine.config

import com.barodrive.core.engine.api.FeedbackContext
import com.barodrive.core.engine.api.ResolveOptions
import com.barodrive.core.engine.content.Items
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.DeliveryReason
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.FeedbackPreset
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ResolvedDelivery

/**
 * 항목별 이 회차 전달 방식 정하기 (설계 6.3.2 아래 줄·6.7, 프로토타입 core.js `resolveOne`·`couple`·`resolveItems`·`feedbackResolve`·
 * `examplePresets`). 우선순위: 고정 → 자율 설정(모드 5) → 사용자 변경 → 도움 줄여 가기(그 회차 값이 O인 fade 항목만 S) →
 * C1 처음 2회차 → 기본값. 허용 값 밖의 값은 버린다. C2가 도움 줄여 가기로 내려가면 C1도 X(사용자·자율 설정이 정했으면 그대로).
 */
internal object ItemResolver {
    /** 항목 하나를 정할 때 보는 값 (core.js resolveOne의 ctx). */
    class Context(
        val overrides: Map<String, Delivery>,
        val fading: Map<String, FadeState>,
        val noInMode: Int,
        val preset: Map<String, Delivery>?,
    )

    /** 이 회차 항목 전부, FB-1 순서 (core.js `resolveItems`). [extraSafety]가 false면 안전3이 빠진다. */
    fun resolve(mode: Int, profile: Profile?, opts: ResolveOptions, extraSafety: Boolean = Items.EXTRA_SAFETY_ITEMS): Map<String, ResolvedDelivery> {
        require(mode in 1..8) { "모드는 1~8이에요: $mode" }
        val p = profile ?: Profile()
        val ctx = Context(
            overrides = p.feedback.overrides[mode].orEmpty(),
            fading = p.fading[mode].orEmpty(),
            noInMode = opts.noInMode ?: p.nextNoInMode(mode),
            preset = if (mode == AUTONOMOUS) presetItems(p, opts) else null,
        )
        val out = LinkedHashMap<String, ResolvedDelivery>()
        for (id in Items.ids(extraSafety)) out[id] = one(mode, id, ctx)
        return couple(out)
    }

    /** 항목 하나 (설계 6.1.1 `feedback.resolve`). C1은 C2와 묶어 본다. 모르는 항목이면 null. */
    fun feedback(mode: Int, item: String, c: FeedbackContext): ResolvedDelivery? {
        require(mode in 1..8) { "모드는 1~8이에요: $mode" }
        if (Items.def(item) == null) return null
        val ctx = Context(c.overrides, c.fading, c.sessionNoInMode ?: 1, c.preset)
        val r = one(mode, item, ctx)
        if (item == C1 && Items.def(C2) != null) return couple(linkedMapOf(C1 to r, C2 to one(mode, C2, ctx))).getValue(C1)
        return r
    }

    /**
     * 예시 자율 설정 2개 (core.js `examplePresets`, 설계 4.2 FB-2, 결정-47). 출퇴근용 = FB-2 항목을 고를 수 있으면 S('완료 후'),
     * 없으면 X · 비 오는 날 = 출퇴근용 + 부드러운 출발·정지·날씨 안내(B1·F1·J3) 바로. 부를 때마다 새 값.
     */
    fun examplePresets(): List<FeedbackPreset> {
        val ids = Items.ids().filter { Items.def(it)?.fb2 == true }
        val commute = LinkedHashMap<String, Delivery>()
        for (id in ids) commute[id] = if (Items.def(id)?.allowed?.contains(Delivery.AFTER) == true) Delivery.AFTER else Delivery.OFF
        val rain = LinkedHashMap(commute)
        for (id in listOf("B1", "F1", "J3")) if (id in rain) rain[id] = Delivery.NOW
        return listOf(
            FeedbackPreset(id = "ex-commute", name = "출퇴근용", example = true, desc = "안전 항목만 바로 피드백하고, 나머지는 주행 완료 후 피드백해요.", items = commute),
            FeedbackPreset(id = "ex-rain", name = "비 오는 날", example = true, desc = "부드러운 출발·정지와 날씨 안내도 바로 피드백해요.", items = rain),
        )
    }

    /** 항목 하나 (core.js `resolveOne`). */
    fun one(mode: Int, id: String, ctx: Context): ResolvedDelivery {
        val d = Items.def(id) ?: throw IllegalArgumentException("모르는 항목: $id")
        val base = d.defaultFor(mode)
        if (d.fixed) return ResolvedDelivery(base, DeliveryReason.FIXED)
        fun ok(v: Delivery?) = v != null && v in d.allowed
        val pv = ctx.preset?.get(id)
        if (mode == AUTONOMOUS && pv != null && ok(pv)) return ResolvedDelivery(pv, DeliveryReason.PRESET)
        val ov = ctx.overrides[id]
        if (ov != null && ok(ov)) return ResolvedDelivery(ov, DeliveryReason.USER)
        var value = base
        var reason = DeliveryReason.DEFAULT
        if (d.first2?.contains(mode) == true) {
            val n = if (ctx.noInMode == 0) 1 else ctx.noInMode
            value = if (n <= 2) Delivery.NOW else Delivery.OFF
            reason = DeliveryReason.FIRST2
        }
        if (d.fade && value == Delivery.NOW && ctx.fading[id]?.faded == true) {
            value = Delivery.AFTER
            reason = DeliveryReason.FADING
        }
        return ResolvedDelivery(value, reason)
    }

    /** 설계 6.7: C2가 도움 줄여 가기로 내려가면 C1도 X(사용자·자율 설정이 C1을 정했으면 그대로) (core.js `couple`). */
    private fun couple(out: MutableMap<String, ResolvedDelivery>): Map<String, ResolvedDelivery> {
        val c1 = out[C1]
        val c2 = out[C2]
        if (c1 != null && c2 != null && c2.reason == DeliveryReason.FADING && c1.value == Delivery.NOW &&
            c1.reason != DeliveryReason.USER && c1.reason != DeliveryReason.PRESET
        ) {
            out[C1] = ResolvedDelivery(Delivery.OFF, DeliveryReason.FADING)
        }
        return out
    }

    /** 자율: 저장하지 않은 '새로 만들기' 값, 없으면 고른 내 설정의 값 (core.js `presetItemsOf`). */
    private fun presetItems(p: Profile, o: ResolveOptions): Map<String, Delivery>? {
        o.presetItems?.let { return it }
        val id = o.presetId ?: return null
        return p.myPresets.firstOrNull { it.id == id }?.items
    }

    private const val C1 = "C1"
    private const val C2 = "C2"
    private const val AUTONOMOUS = 5
}
