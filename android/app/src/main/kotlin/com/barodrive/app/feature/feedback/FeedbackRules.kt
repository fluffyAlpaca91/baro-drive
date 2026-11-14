package com.barodrive.app.feature.feedback

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.ResolveOptions
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.DeliveryReason
import com.barodrive.core.model.FeedbackSettings
import com.barodrive.core.model.ItemDef
import com.barodrive.core.model.ItemGroup
import com.barodrive.core.model.Mode
import com.barodrive.core.model.Profile

/** FB-1 줄 모양: 세 칸 고르기 · 칸 없는 규칙 한 줄(F2, 결정-22). */
enum class FeedbackRowKind { SEG, RULE }

/** FB-1 묶음 (feedback.js 묶음 id). */
enum class FeedbackGroupId { SAFETY, RULE, USED, OFF }

/**
 * FB-1·FB-2 항목 한 줄 (feedback.js `modeRows`의 row).
 * [lock]은 칸마다 막힌 이유 토스트 글(null = 고를 수 있는 칸, 결정-20). [note]는 줄 아래 작은 글(없으면 '').
 */
data class FeedbackRow(
    val id: String,
    val name: String,
    val kind: FeedbackRowKind,
    val value: Delivery,
    val reason: DeliveryReason,
    /** 그 모드 기본값(표 A-4). */
    val def: Delivery?,
    val allowed: List<Delivery>,
    val locked: Boolean,
    val lock: Map<Delivery, String?>,
    val note: String,
) {
    /** 막힌 칸을 눌렀을 때 토스트 글(feedback.js `reason`). */
    fun lockText(value: Delivery): String = lock[value] ?: FeedbackRules.NA_REASON
}

/** FB-1 묶음 하나: 제목('이 모드의 피드백 항목 12개')과 줄. */
data class FeedbackGroup(val id: FeedbackGroupId, val title: String, val rows: List<FeedbackRow>)

/** FB-1 한 모드의 그림 값 (feedback.js `modeRows` 결과). */
data class ModeRows(
    val mode: Int,
    /** '1 · 차량 감 익히기'. */
    val title: String,
    /** 말하는 양(설계 6.3.3). */
    val talk: String,
    /** 모드 5에서만: '새로 만들기'와 이어지는 글. */
    val extraNote: String?,
    val noInMode: Int,
    val groups: List<FeedbackGroup>,
)

/**
 * FB-1 음성 피드백 설정(모드별)의 화면 없는 계산 (프로토타입 screens/feedback.js `modeRows`·`setOverride`·`resetMode`·`resetToast`,
 * 설계 4.2 FB-1·6.3.2·6.7, 결정-20·22). 항목 정의·기본값·이번 회차 값은 엔진 파사드([BaroEngine])로 읽는다.
 * 프로필을 고치는 함수는 고친 새 프로필을 돌려준다(바뀌지 않으면 null) — 저장은 부르는 쪽([com.barodrive.app.data.repo.ProfileRepository.update]).
 */
object FeedbackRules {
    const val NA_REASON = "이 항목에서는 고를 수 없어요"
    const val OFF_REASON = "주차 연습에서는 쓰지 않는 항목이에요"
    const val OFF_NOTE = "주차 연습에서는 쓰지 않아요"
    const val SAFE_NOTE = "안전 항목 · 바꿀 수 없어요"
    const val C1_FADING = "깜빡이를 2회 연속 잘 켜서 지금은 미리 말하지 않아요"
    const val MODE5_NOTE = "여기서 고른 값은 내 음성 피드백 설정의 '새로 만들기'가 처음 쓰는 값이에요"

    /** copy.js `feedbackNotes.fading` — 도움 줄여 가기로 내려간 항목 줄 아래 글(설계 6.7). */
    const val FADING_NOTE = "2회 연속 잘해서 지금은 리포트로만 알려요"

    /** 칸 순서 O · S · X(목업 s10). */
    val OSX: List<Delivery> = listOf(Delivery.NOW, Delivery.AFTER, Delivery.OFF)

    /** 1~8이면 그 모드, 아니면 null(feedback.js `validMode`). */
    fun validMode(mode: Int?): Int? = mode?.takeIf { it in 1..8 }

    /** 지금 단계 1~4(feedback.js `currentStage`). */
    fun currentStage(p: Profile?): Int = p?.progress?.currentStage?.takeIf { it in 1..4 } ?: 1

    /** 다음 회차 번호(sessionCount + 1). */
    fun noInModeOf(p: Profile?, mode: Int): Int = (p?.sessionCount?.get(mode) ?: 0) + 1

    /** 칸마다 막힌 이유(feedback.js `lockTexts`): 고정 항목은 모든 칸, 아니면 허용 값 밖의 칸. F2는 칸이 없어 모두 null. */
    fun lockTexts(it: ItemDef, value: Delivery): Map<Delivery, String?> = OSX.associateWith { v ->
        when {
            it.fixed && it.id == F2 -> null
            it.fixed -> if (value == Delivery.NOW) it.lock?.all ?: NA_REASON else it.lock?.off ?: OFF_REASON
            v !in it.allowed -> when (v) {
                Delivery.NOW -> it.lock?.now
                Delivery.AFTER -> it.lock?.after
                Delivery.OFF -> null
            } ?: NA_REASON
            else -> null
        }
    }

    /** 줄 아래 작은 글(feedback.js `noteFor`): 안전 잠금 · 도움 줄여 가기 · C1 처음 2회차 · 날씨 · 언덕 뒤로 밀림. */
    fun noteFor(it: ItemDef, value: Delivery, reason: DeliveryReason, def: Delivery?): String {
        val note = it.note.orEmpty()
        return when {
            it.fixed -> if (value == Delivery.NOW) SAFE_NOTE else OFF_NOTE
            reason == DeliveryReason.FADING -> if (it.id == C1) C1_FADING else FADING_NOTE
            reason == DeliveryReason.FIRST2 && it.note != null ->
                (if (value == Delivery.NOW) note["first2On"] else note["first2Off"]).orEmpty()
            it.id == J3 && value != Delivery.OFF && note["wx"] != null -> note.getValue("wx")
            it.id == HL2 && def != Delivery.OFF && note["needs"] != null -> note.getValue("needs")
            else -> ""
        }
    }

    /**
     * 모드 하나의 묶음과 줄 (feedback.js `modeRows`). 묶음은 그 모드 기본값으로 나눈다 — 값을 바꿔도 줄이 옮겨 다니지 않게.
     * '안전 항목 · 늘 바로 피드백'(고정·안전·기본이 X 아님) · '늘 지키는 규칙'(F2) · '이 모드의 피드백 항목 n개' · '이 모드에서 기본으로 안 하는 항목 n개'.
     */
    fun modeRows(mode: Int, profile: Profile?): ModeRows {
        val m = validMode(mode) ?: 1
        val p = profile ?: Profile()
        val noInMode = noInModeOf(p, m)
        val res = BaroEngine.resolveItems(m, p, ResolveOptions(noInMode = noInMode))
        val safety = ArrayList<FeedbackRow>()
        val rule = ArrayList<FeedbackRow>()
        val used = ArrayList<FeedbackRow>()
        val off = ArrayList<FeedbackRow>()
        for (id in BaroEngine.itemIds()) {
            val it = BaroEngine.itemDef(id) ?: continue
            val def = BaroEngine.defaultFor(id, m)
            val r = res[id]
            val value = r?.value ?: def ?: Delivery.OFF
            val reason = r?.reason ?: DeliveryReason.DEFAULT
            var row = FeedbackRow(
                id = id, name = it.name, kind = FeedbackRowKind.SEG, value = value, reason = reason, def = def,
                allowed = if (it.fixed) listOf(value) else it.allowed, locked = it.fixed, lock = lockTexts(it, value),
                note = noteFor(it, value, reason, def),
            )
            if (id == F2) {
                row = row.copy(
                    kind = FeedbackRowKind.RULE, allowed = emptyList(),
                    note = if (def != Delivery.OFF) it.note?.get("rule").orEmpty() else OFF_NOTE,
                )
                (if (def != Delivery.OFF) rule else off) += row
            } else if (it.fixed && it.group == ItemGroup.SAFETY && def != Delivery.OFF) {
                safety += row
            } else if (def == Delivery.OFF) {
                off += row
            } else {
                used += row
            }
        }
        val groups = buildList {
            if (safety.isNotEmpty()) add(FeedbackGroup(FeedbackGroupId.SAFETY, "안전 항목 · 늘 바로 피드백", safety))
            if (rule.isNotEmpty()) add(FeedbackGroup(FeedbackGroupId.RULE, "늘 지키는 규칙", rule))
            if (used.isNotEmpty()) add(FeedbackGroup(FeedbackGroupId.USED, "이 모드의 피드백 항목 ${used.size}개", used))
            if (off.isNotEmpty()) add(FeedbackGroup(FeedbackGroupId.OFF, "이 모드에서 기본으로 안 하는 항목 ${off.size}개", off))
        }
        val info = Mode.of(m)
        return ModeRows(
            mode = m, title = "$m · ${info.title}", talk = info.talk, extraNote = if (m == 5) MODE5_NOTE else null,
            noInMode = noInMode, groups = groups,
        )
    }

    /**
     * 사용자 변경 하나(feedback.js `setOverride`): 고른 값이 '사용자 변경이 없을 때의 값'(도움 줄여 가기·C1 처음 2회차·C2 → C1 묶음 포함)과
     * 같으면 변경을 지운다 — 그래야 도움 줄여 가기가 다시 그 항목을 맡는다(결정사항 E T4-5). 고정 항목·허용 값 밖은 거부.
     * 바뀐 프로필, 바뀌지 않으면 null.
     */
    fun setOverride(p: Profile, mode: Int, item: String, value: Delivery): Profile? {
        val m = validMode(mode) ?: return null
        val it = BaroEngine.itemDef(item) ?: return null
        if (it.fixed || value !in it.allowed) return null
        val all = p.feedback.overrides
        val mine = all[m].orEmpty()
        val before = mine[item]
        val without = mine - item
        val probe = p.copy(feedback = FeedbackSettings(all + (m to without)))
        val plain = BaroEngine.resolveItems(m, probe, ResolveOptions(noInMode = noInModeOf(p, m)))[item]?.value
        if (value == plain) {
            if (before == null) return null
            return p.copy(feedback = FeedbackSettings(if (without.isEmpty()) all - m else all + (m to without)))
        }
        if (before == value) return null
        return p.copy(feedback = FeedbackSettings(all + (m to (mine + (item to value)))))
    }

    /** [기본값으로](feedback.js `resetMode`): 그 모드 사용자 변경을 지운 프로필과 지운 수. */
    fun resetMode(p: Profile, mode: Int): Pair<Profile, Int> {
        val m = validMode(mode) ?: return p to 0
        val mine = p.feedback.overrides[m] ?: return p to 0
        return p.copy(feedback = FeedbackSettings(p.feedback.overrides - m)) to mine.size
    }

    /** '1 · 차량 감 익히기를 기본값으로 되돌렸어요'(feedback.js `resetToast`). */
    fun resetToast(mode: Int): String {
        val m = validMode(mode) ?: 1
        return "$m · ${Josa.attach(Mode.of(m).title, "을", "를")} 기본값으로 되돌렸어요"
    }

    private const val F2 = "F2"
    private const val C1 = "C1"
    private const val J3 = "J3"
    private const val HL2 = "HL2"
}
