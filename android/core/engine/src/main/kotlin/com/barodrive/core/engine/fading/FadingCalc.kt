package com.barodrive.core.engine.fading

import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.content.Items
import com.barodrive.core.engine.review.Josa
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.FadingUpdate
import com.barodrive.core.model.ItemStat
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord

/**
 * 도움 줄여 가기 (프로토타입 js/engine/fading.js, 설계 6.7, 결정-28). 상태는 프로필 `fading[mode][item] = {streak, faded}`.
 *
 * - 회차 성공률 = ok ÷ 판정(ok·almost·late·fail). 판정 2번 미만인 회차는 세지 않는다(연속도 끊지 않음, 결정사항 E T2a-9).
 * - 80% 이상이면 streak + 1, 연속 2회차가 되면 faded(다음 회차부터 그 모드에서 O → S, C2가 내려가면 C1도 X — 코어 resolveItems).
 * - 80% 아래면 streak 0, 내려가 있었으면 되돌림(restoredNow).
 * - faded는 그 모드 기본값이 O이고 사용자가 FB-1에서 바꾸지 않은 항목에만 켠다. 사용자가 바꾼 항목은 상태를 건드리지 않는다.
 *
 * 프로토타입과 달리 프로필을 고치지 않는다 — 새 상태를 돌려주고 프로필에 넣는 것은 부르는 쪽(리뷰 `applyProgress`).
 */
object FadingCalc {
    private const val EPS = 1e-6

    /** RV-1 알림 줄에 쓰는 이름(항목 짧은 이름은 C2·C5가 같은 '깜빡이'라 따로 둔다). */
    val NAMES: Map<String, String> = mapOf("B1" to "출발", "F1" to "정지", "C2" to "깜빡이 타이밍", "C4" to "깜빡이 켜고 셋 세기", "C5" to "꺾기 전 깜빡이")

    /** 알림 줄 이름 순서: 출발 → 정지 → 깜빡이 셋. */
    private val NAME_ORDER = listOf("B1", "F1", "C2", "C4", "C5")

    /** 도움 줄여 가기 대상 항목(items.js `fade`), FB-1 줄 순서. */
    val items: List<String> by lazy { Items.all.filter { it.fade }.sortedBy { it.order }.map { it.id } }

    /** 사용자가 FB-1에서 그 항목을 고를 수 있는 값으로 바꿨나(fading.js `userSet` — 항목 정의의 `allowed` 그대로 본다). */
    fun userSet(overrides: Map<String, Delivery>, id: String): Boolean {
        val v = overrides[id] ?: return false
        return v in Items.def(id)?.allowed.orEmpty()
    }

    /** 이 모드에서 O → S로 내릴 수 있나 (fading.js `eligible`): fade 항목, 고정 아님, 사용자가 안 바꿈, 그 모드 기본값 O. */
    fun eligible(id: String, mode: Int, overrides: Map<String, Delivery>): Boolean {
        val d = Items.def(id) ?: return false
        if (!d.fade || d.fixed) return false
        if (userSet(overrides, id)) return false
        if (mode !in 1..8) return false
        return d.defaultFor(mode) == Delivery.NOW
    }

    /** 항목별 성공·판정 수 (fading.js `stats`): 기록 `itemStats`가 있으면 그것(빈 맵이어도), 없으면 판정 기록에서(출발 전 사건 뺌). */
    fun stats(record: SessionRecord): Map<String, ItemStat> {
        record.itemStats?.let { return it }
        val out = LinkedHashMap<String, ItemStat>()
        for (e in record.events) {
            val item = e.item
            if (item.isNullOrEmpty() || e.pre || !e.result.isJudged) continue
            val s = out[item] ?: ItemStat()
            out[item] = ItemStat(ok = s.ok + if (e.result == EventResult.OK) 1 else 0, total = s.total + 1)
        }
        return out
    }

    /** 같은 계산, 아무것도 고치지 않음 (fading.js `next(prev, stats, { mode, overrides })`). */
    fun next(prev: Map<String, FadeState>, stats: Map<String, ItemStat>, mode: Int, overrides: Map<String, Delivery>): FadingUpdate {
        val state = LinkedHashMap(prev)
        val fadedNow = ArrayList<String>()
        val restoredNow = ArrayList<String>()
        val rate = Thresholds["fade.rate"]
        val min = Thresholds["fade.min"]
        val need = Thresholds["fade.streak"]
        for (id in items) {
            if (userSet(overrides, id)) continue // 사용자 우선(6.7)
            val s = stats[id]
            val total = s?.total ?: 0
            if (total < min) continue // 판정 2번 미만: 그대로
            var cur = state[id] ?: FadeState()
            if ((s?.ok ?: 0).toDouble() / total >= rate - EPS) {
                cur = cur.copy(streak = cur.streak + 1)
                if (!cur.faded && cur.streak >= need && eligible(id, mode, overrides)) {
                    cur = cur.copy(faded = true)
                    fadedNow += id
                }
            } else {
                val was = cur.faded
                cur = FadeState(streak = 0, faded = false)
                if (was) restoredNow += id
            }
            state[id] = cur
        }
        return FadingUpdate(mode = mode, state = state, fadedNow = fadedNow, restoredNow = restoredNow)
    }

    /**
     * 회차 끝 새 상태 (fading.js `update(profile, rec)`): 프로필의 그 모드 상태·사용자 변경과 기록의 성공 수로 [next].
     * 모드가 1~8이 아니면 빈 상태.
     */
    fun update(profile: Profile, record: SessionRecord): FadingUpdate {
        val mode = record.mode
        if (mode !in 1..8) return FadingUpdate(mode = mode)
        val overrides = profile.feedback.overrides[mode].orEmpty()
        return next(profile.fading[mode].orEmpty(), stats(record), mode, overrides)
    }

    /**
     * RV-1 알림 줄 (fading.js `note`, 설계 4.2 RV-1 10번): '출발·정지는 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.'
     * 셋 넘으면 '출발·정지 등 5가지는 …'. 이름 순서는 출발 → 정지 → 깜빡이 셋. 내려간 항목이 없으면 null.
     */
    fun note(fadedNow: List<String>): String? {
        val ids = fadedNow.sortedBy { NAME_ORDER.indexOf(it).let { i -> if (i < 0) UNKNOWN_ORDER else i } }
        val names = ArrayList<String>()
        for (id in ids) {
            val n = NAMES[id] ?: Items.def(id)?.short?.takeIf { it.isNotEmpty() } ?: id
            if (n !in names) names += n
        }
        if (names.isEmpty()) return null
        val head = if (names.size <= 2) {
            (names.dropLast(1) + Josa.josa(names.last(), "은/는")).joinToString("·")
        } else {
            names.take(2).joinToString("·") + " 등 " + names.size + "가지는"
        }
        return "$head 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요."
    }

    /** NAME_ORDER에 없는 항목은 맨 뒤(fading.js `(indexOf + 99) % 99`). */
    private const val UNKNOWN_ORDER = 98
}
