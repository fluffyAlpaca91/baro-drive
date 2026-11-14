package com.barodrive.app.feature.feedback

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.ResolveOptions
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.FeedbackPreset
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile

/**
 * FB-2에서 편집 중인 값 (feedback.js `draft` — 페이지가 열려 있는 동안, 프로필별). [selId]가 null이면 '새로 만들기'.
 */
data class PresetDraft(
    val selId: String?,
    val items: Map<String, Delivery>,
    /** 이름 칸에 쓴 글(다듬기 전). */
    val name: String,
)

/** 설정 저장·이름 바꾸기 결과 (feedback.js `saveNew`·`saveOver`·`rename`의 `{ preset } | { error }`). */
sealed interface PresetResult {
    /** 성공: 고친 [profile]과 그 설정. [changed]는 덮어쓸 때 값이 바뀌었나, [same]은 이름 바꾸기에서 같은 이름이었나. */
    data class Ok(val profile: Profile, val preset: FeedbackPreset, val changed: Boolean = true, val same: Boolean = false) : PresetResult

    /** 실패: 토스트 글. */
    data class Error(val message: String) : PresetResult
}

/**
 * 자율 모드 '내 음성 피드백 설정'(FB-2)의 화면 없는 계산 (프로토타입 screens/feedback.js `presets`, 설계 4.2 FB-2·5.5, 8.1 `myPresets`·
 * `lastPresetId`, 결정-23·47). 항목 12개는 엔진 항목 정의의 `fb2`. 프로필을 고치는 함수는 고친 새 프로필을 돌려준다.
 */
object PresetRules {
    const val MAX = 6
    const val MAX_NAME = 12
    const val NEW_DESC = "기본값에서 시작해요. 고른 뒤 이름을 붙여 저장해요."
    private const val MODE = 5

    /** FB-2 항목 id, FB-1 순서(결정-23: J3·A1·PB1·L1·B1·C1·C2·C4·C5·F1·G1·PB2). */
    fun ids(): List<String> = BaroEngine.itemIds().filter { BaroEngine.itemDef(it)?.fb2 == true }

    /** 표 A-4 5열(자율 모드 기본값). */
    fun tableDefaults(): Map<String, Delivery> = ids().associateWith { BaroEngine.defaultFor(it, MODE) ?: Delivery.OFF }

    /** '새로 만들기'·[기본값으로 되돌리기]의 값 = 표 A-4 5열 + FB-1 모드 5에서 바꾼 값(결정사항 E T4-4). */
    fun base(p: Profile?): Map<String, Delivery> {
        val profile = p ?: Profile()
        val r = BaroEngine.resolveItems(MODE, profile, ResolveOptions(noInMode = FeedbackRules.noInModeOf(profile, MODE)))
        val table = tableDefaults()
        return ids().associateWith { r[it]?.value ?: table.getValue(it) }
    }

    /** 항목 12개를 다 채운 값: 허용 값 밖이거나 빠진 항목은 [fallback](없으면 표 A-4 5열). */
    fun norm(items: Map<String, Delivery>?, fallback: Map<String, Delivery>? = null): Map<String, Delivery> {
        val b = fallback ?: tableDefaults()
        return ids().associateWith { id ->
            val v = items?.get(id)
            if (v != null && v in BaroEngine.itemDef(id)?.allowed.orEmpty()) v else b[id] ?: Delivery.OFF
        }
    }

    /** 항목 12개 값이 같은가. */
    fun same(a: Map<String, Delivery>?, b: Map<String, Delivery>?): Boolean = ids().all { a?.get(it) == b?.get(it) }

    fun list(p: Profile?): List<FeedbackPreset> = p?.myPresets.orEmpty()

    fun find(p: Profile?, id: String?): FeedbackPreset? = id?.let { want -> list(p).firstOrNull { it.id == want } }

    /** 처음 고를 설정: lastPresetId → 첫 설정 → null(새로 만들기). */
    fun pick(p: Profile?): String? {
        p?.lastPresetId?.let { if (find(p, it) != null) return it }
        return list(p).firstOrNull()?.id
    }

    /** 이름 다듬기: 공백 하나로, 앞뒤 공백 빼고 12자까지. */
    fun cleanName(name: String?): String = name.orEmpty().replace(Regex("\\s+"), " ").trim().take(MAX_NAME)

    /** 이름 검사: 문제가 없으면 null, 있으면 토스트 글. [exceptId] 설정은 같은 이름 검사에서 뺀다(이름 바꾸기). */
    fun checkName(p: Profile?, name: String?, exceptId: String? = null): String? {
        val n = cleanName(name)
        if (n.isEmpty()) return "설정 이름을 써 주세요"
        if (list(p).any { it.id != exceptId && cleanName(it.name) == n }) return "같은 이름의 설정이 있어요"
        return null
    }

    private fun nextId(p: Profile): String {
        val ids = list(p).map { it.id }.toSet()
        var k = 1
        while ("my-$k" in ids) k++
        return "my-$k"
    }

    /** 새 이름으로 저장(고른 것으로 둠). 6개까지. */
    fun saveNew(p: Profile, name: String?, items: Map<String, Delivery>?): PresetResult {
        checkName(p, name)?.let { return PresetResult.Error(it) }
        if (list(p).size >= MAX) return PresetResult.Error("설정은 ${MAX}개까지 저장할 수 있어요")
        val pr = FeedbackPreset(id = nextId(p), name = cleanName(name), example = false, items = norm(items, base(p)))
        return PresetResult.Ok(p.copy(myPresets = p.myPresets + pr, lastPresetId = pr.id), pr)
    }

    /** 같은 이름이면 덮어쓰기. 값을 바꿔 저장하면 내 설정이 된다(예시 표시·설명 없음). */
    fun saveOver(p: Profile, id: String?, items: Map<String, Delivery>?): PresetResult {
        val pr = find(p, id) ?: return PresetResult.Error(NOT_FOUND)
        val b = base(p)
        val next = norm(items, b)
        val changed = !same(next, norm(pr.items, b))
        val saved = if (changed && pr.example) pr.copy(items = next, example = false, desc = null) else pr.copy(items = next)
        return PresetResult.Ok(replace(p, saved).copy(lastPresetId = saved.id), saved, changed = changed)
    }

    /** 이름 바꾸기. 바꾸면 내 설정(예시 표시가 빠짐 — 값은 같으니 설명은 둠). 같은 이름이면 [PresetResult.Ok.same]. */
    fun rename(p: Profile, id: String?, name: String?): PresetResult {
        val pr = find(p, id) ?: return PresetResult.Error(NOT_FOUND)
        checkName(p, name, pr.id)?.let { return PresetResult.Error(it) }
        val n = cleanName(name)
        if (n == pr.name) return PresetResult.Ok(p, pr, changed = false, same = true)
        val renamed = pr.copy(name = n, example = false)
        return PresetResult.Ok(replace(p, renamed), renamed)
    }

    /** 지우기. 마지막으로 고른 설정이면 그것도 비운다. 없는 설정이면 null. */
    fun remove(p: Profile, id: String?): Profile? {
        if (find(p, id) == null) return null
        return p.copy(myPresets = p.myPresets.filterNot { it.id == id }, lastPresetId = p.lastPresetId.takeIf { it != id })
    }

    /** 마지막으로 고른 설정(목록에 없으면 null). */
    fun select(p: Profile, id: String?): Profile = p.copy(lastPresetId = id?.takeIf { find(p, it) != null })

    /** 고른 설정을 저장하지 않고 바꿨나('(바꿈)'). */
    fun isModified(p: Profile?, d: PresetDraft?): Boolean {
        val pr = find(p, d?.selId) ?: return false
        val b = base(p)
        return !same(norm(d?.items, b), norm(pr.items, b))
    }

    /** 설명 줄: 예시 설명(값이 같을 때) · '이 설정: 바로 1개 · 완료 후 9개 · 안 함 2개' · 새로 만들기 안내. */
    fun desc(pr: FeedbackPreset?, items: Map<String, Delivery>?): String {
        if (pr == null) return NEW_DESC
        if (pr.desc != null && same(norm(items), norm(pr.items))) return pr.desc.orEmpty()
        val v = norm(items)
        val c = Delivery.entries.associateWith { d -> v.values.count { it == d } }
        return "이 설정: ${Delivery.NOW.shortLabel} ${c[Delivery.NOW]}개 · ${Delivery.AFTER.shortLabel} ${c[Delivery.AFTER]}개 · " +
            "${Delivery.OFF.shortLabel} ${c[Delivery.OFF]}개"
    }

    /** 칩 이름: '출퇴근용' · '출퇴근용 (바꿈)' · 새로 만들기. */
    fun chipName(pr: FeedbackPreset?, modified: Boolean): String =
        if (pr == null) NEW_NAME else cleanName(pr.name) + if (modified) " (바꿈)" else ""

    /** PR-1·RV-4가 쓰는 설정 이름(feedback.js `label`): 저장한 설정 · '(바꿈)' · '새로 만들기' · 없으면 null. */
    fun label(p: Profile?, presetId: String?, presetItems: Map<String, Delivery>?): String? {
        val pr = find(p, presetId)
        if (pr != null) return chipName(pr, presetItems != null && !same(norm(presetItems, base(p)), norm(pr.items, base(p))))
        return if (presetItems != null) NEW_NAME else null
    }

    /** [이 설정으로 출발] 준비 세션 값: 저장한 그대로면 presetId만, 바꿨거나 새로 만들기면 presetItems도(그 회차만 그 값). */
    fun startPrep(p: Profile?, d: PresetDraft?): PrepSession {
        val presetId = d?.selId?.takeIf { find(p, it) != null }
        val items = norm(d?.items, base(p))
        return PrepSession(mode = MODE, presetId = presetId, presetItems = if (presetId == null || isModified(p, d)) items else null)
    }

    /** 그 설정(또는 새로 만들기)으로 편집 값을 새로 만든다(feedback.js `setDraftTo`). */
    fun draftFor(p: Profile?, id: String?): PresetDraft {
        val pr = find(p, id)
        return PresetDraft(selId = pr?.id, items = if (pr != null) norm(pr.items, base(p)) else base(p), name = pr?.let { cleanName(it.name) }.orEmpty())
    }

    private fun replace(p: Profile, pr: FeedbackPreset): Profile = p.copy(myPresets = p.myPresets.map { if (it.id == pr.id) pr else it })

    private const val NOT_FOUND = "저장한 설정을 찾지 못했어요"
    private const val NEW_NAME = "새로 만들기"
}
