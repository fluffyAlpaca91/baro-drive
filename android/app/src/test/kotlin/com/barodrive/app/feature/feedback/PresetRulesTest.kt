package com.barodrive.app.feature.feedback

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.Delivery.AFTER
import com.barodrive.core.model.Delivery.NOW
import com.barodrive.core.model.Delivery.OFF
import com.barodrive.core.model.DeliveryReason
import com.barodrive.core.model.Onboarding
import com.barodrive.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FB-2 자율 '내 음성 피드백 설정' 계산 (프로토타입 tests/unit/t4_feedback.test.js 'FB-2' 묶음).
 * 항목 값(norm·base)에 기대는 시험은 엔진 항목 정의가 옮겨질 때까지 건너뛴다.
 */
class PresetRulesTest {
    private fun fresh() = Profile(onboarding = Onboarding(done = true), myPresets = BaroEngine.examplePresets())

    private fun ok(r: PresetResult): PresetResult.Ok = r as? PresetResult.Ok ?: error("실패: $r")

    private fun err(r: PresetResult): String = (r as? PresetResult.Error)?.message ?: error("성공함: $r")

    @Test
    fun twelveItemsAndAutonomousDefaults() {
        assumeItemsPorted()
        assertEquals(listOf("J3", "A1", "PB1", "L1", "B1", "C1", "C2", "C4", "C5", "F1", "G1", "PB2"), PresetRules.ids())
        val table = mapOf(
            "J3" to NOW, "A1" to NOW, "PB1" to OFF, "L1" to NOW, "B1" to AFTER, "C1" to OFF, "C2" to AFTER, "C4" to AFTER,
            "C5" to AFTER, "F1" to AFTER, "G1" to AFTER, "PB2" to OFF,
        )
        assertEquals(table, PresetRules.tableDefaults())
        val p = fresh()
        assertEquals(table, PresetRules.base(p))
        val changed = requireNotNull(FeedbackRules.setOverride(p, 5, "B1", NOW))
        assertEquals(NOW, PresetRules.base(changed)["B1"])
        assertEquals("허용 값 밖은 기본값", table + ("B1" to NOW), PresetRules.norm(mapOf("B1" to NOW, "C1" to AFTER, "G1" to NOW)))
    }

    @Test
    fun examplesAndFirstPick() {
        var p = fresh()
        assertEquals(
            listOf(Triple("ex-commute", "출퇴근용", true), Triple("ex-rain", "비 오는 날", true)),
            PresetRules.list(p).map { Triple(it.id, it.name, it.example) },
        )
        assertEquals("ex-commute", PresetRules.pick(p))
        p = p.copy(lastPresetId = "ex-rain")
        assertEquals("ex-rain", PresetRules.pick(p))
        p = p.copy(lastPresetId = "gone")
        assertEquals("ex-commute", PresetRules.pick(p))
        p = p.copy(myPresets = emptyList())
        assertNull(PresetRules.pick(p))
        assertEquals("기본값에서 시작해요. 고른 뒤 이름을 붙여 저장해요.", PresetRules.desc(null, PresetRules.base(p)))
    }

    @Test
    fun saveNewAndSaveOver() {
        assumeItemsPorted()
        var p = fresh()
        val items = PresetRules.norm(PresetRules.find(p, "ex-commute")?.items) + ("B1" to NOW)
        assertEquals("설정 이름을 써 주세요", err(PresetRules.saveNew(p, "   ", items)))
        assertEquals("같은 이름의 설정이 있어요", err(PresetRules.saveNew(p, " 출퇴근용 ", items)))
        val r = ok(PresetRules.saveNew(p, "  주말   장보기 ", items))
        assertEquals(listOf<Any?>("my-1", "주말 장보기", false, NOW), listOf(r.preset.id, r.preset.name, r.preset.example, r.preset.items["B1"]))
        p = r.profile
        assertEquals("my-1", p.lastPresetId)
        assertEquals("원래 설정은 그대로", AFTER, PresetRules.find(p, "ex-commute")?.items?.get("B1"))
        val long = ok(PresetRules.saveNew(p, "열세 글자가 넘는 아주 긴 이름", items))
        assertEquals("열세 글자가 넘는 아주 긴".take(12), long.preset.name)
        p = ok(PresetRules.saveNew(long.profile, "셋째", items)).profile
        p = ok(PresetRules.saveNew(p, "넷째", items)).profile
        assertEquals(6, PresetRules.list(p).size)
        assertEquals("설정은 6개까지 저장할 수 있어요", err(PresetRules.saveNew(p, "일곱째", items)))
        // 덮어쓰기: 값을 바꾸면 예시 표시·설명이 빠짐, 그대로면 남음
        val same = ok(PresetRules.saveOver(p, "ex-rain", PresetRules.find(p, "ex-rain")?.items))
        assertFalse(same.changed)
        assertEquals(true, PresetRules.find(same.profile, "ex-rain")?.example)
        val ch = ok(PresetRules.saveOver(same.profile, "ex-commute", items))
        assertTrue(ch.changed)
        val commute = requireNotNull(PresetRules.find(ch.profile, "ex-commute"))
        assertEquals(false, commute.example)
        assertNull(commute.desc)
        assertEquals(NOW, commute.items["B1"])
        assertEquals("ex-commute", ch.profile.lastPresetId)
        assertEquals("저장한 설정을 찾지 못했어요", err(PresetRules.saveOver(p, "nope", items)))
    }

    @Test
    fun renameAndRemove() {
        var p = fresh()
        assertEquals("같은 이름의 설정이 있어요", err(PresetRules.rename(p, "ex-rain", "출퇴근용")))
        assertEquals("설정 이름을 써 주세요", err(PresetRules.rename(p, "ex-rain", "")))
        assertTrue(ok(PresetRules.rename(p, "ex-rain", "비 오는 날")).same)
        assertEquals("같은 이름이면 그대로", true, PresetRules.find(p, "ex-rain")?.example)
        val r = ok(PresetRules.rename(p, "ex-rain", "장마철"))
        assertEquals("장마철", r.preset.name)
        assertEquals(false, r.preset.example)
        assertNotNull("값은 그대로라 설명은 둠", r.preset.desc)
        p = PresetRules.select(r.profile, "ex-rain")
        assertEquals("ex-rain", p.lastPresetId)
        p = requireNotNull(PresetRules.remove(p, "ex-rain"))
        assertNull(p.lastPresetId)
        assertNull(PresetRules.remove(p, "ex-rain"))
        p = requireNotNull(PresetRules.remove(p, "ex-commute"))
        assertEquals("예시도 지울 수 있음 → 칩 줄엔 새로 만들기만", emptyList<Any>(), PresetRules.list(p))
        assertNull(PresetRules.select(p, "nope").lastPresetId)
    }

    @Test
    fun modifiedLabelDescAndStartPrep() {
        assumeItemsPorted()
        val p = fresh()
        val drafts = PresetDrafts()
        var d = drafts.draft(p, reset = true)
        assertEquals("ex-commute" to "출퇴근용", d.selId to d.name)
        assertFalse(PresetRules.isModified(p, d))
        assertEquals("안전 항목만 바로 피드백하고, 나머지는 주행 완료 후 피드백해요.", PresetRules.desc(PresetRules.find(p, d.selId), d.items))
        PresetRules.startPrep(p, d).let { assertEquals(Triple(5, "ex-commute", null), Triple(it.mode, it.presetId, it.presetItems)) }
        d = drafts.update(p) { it.copy(items = it.items + ("B1" to NOW)) }
        assertTrue(PresetRules.isModified(p, d))
        assertEquals("출퇴근용 (바꿈)", PresetRules.chipName(PresetRules.find(p, d.selId), true))
        assertEquals("이 설정: 바로 1개 · 완료 후 9개 · 안 함 2개", PresetRules.desc(PresetRules.find(p, d.selId), d.items))
        val prep = PresetRules.startPrep(p, d)
        assertEquals("ex-commute" to NOW, prep.presetId to prep.presetItems?.get("B1"))
        assertEquals("출퇴근용 (바꿈)", PresetRules.label(p, prep.presetId, prep.presetItems))
        assertEquals("비 오는 날", PresetRules.label(p, "ex-rain", null))
        assertEquals("새로 만들기", PresetRules.label(p, null, PresetRules.base(p)))
        assertNull(PresetRules.label(p, null, null))
        // 엔진이 그 값으로 회차 설정을 만든다(설계 6.3.2: 고른 설정이 앞섬, 안전은 고정)
        val cfg = BaroEngine.buildConfig(5, p, prep)
        assertEquals(listOf(NOW, AFTER, NOW), listOf(cfg.items["B1"], cfg.items["C2"], cfg.items["E1"]))
        assertEquals(DeliveryReason.PRESET, cfg.itemReasons["B1"])
        assertEquals("ex-commute", cfg.presetId)
        val cfg2 = BaroEngine.buildConfig(5, p, PresetRules.startPrep(p, drafts.draft(p, reset = true)))
        assertEquals("저장한 출퇴근용 그대로", AFTER, cfg2.items["B1"])
        // 새로 만들기(저장 안 함)
        val n = drafts.setTo(p, null)
        assertEquals(Triple(null, "", false), Triple(n.selId, n.name, PresetRules.isModified(p, n)))
        val np = PresetRules.startPrep(p, n)
        assertEquals(null to NOW, np.presetId to np.presetItems?.get("J3"))
        assertEquals(NOW, BaroEngine.buildConfig(5, p, np).items["L1"])
    }

    @Test
    fun draftsArePerProfileAndRepickWhenDeleted() {
        val p = fresh()
        val drafts = PresetDrafts()
        drafts.draft(p, reset = true)
        drafts.update(p) { it.copy(items = it.items + ("C2" to OFF)) }
        assertEquals("다시 들어와도 그대로", OFF, drafts.draft(p).items["C2"])
        val q = fresh().copy(id = "p2")
        assertEquals("다른 프로필은 따로", PresetRules.find(q, "ex-commute")?.let { PresetRules.norm(it.items, PresetRules.base(q)) }?.get("C2"), drafts.draft(q).items["C2"])
        val removed = requireNotNull(PresetRules.remove(p, "ex-commute"))
        assertEquals("지워진 설정 → 남은 첫 설정", "ex-rain", drafts.draft(removed).selId)
        drafts.drop(p.id)
        drafts.drop("p2")
    }

    @Test
    fun cleanNameAndCheckName() {
        assertEquals("주말 장보기", PresetRules.cleanName("  주말   장보기 "))
        assertEquals("가나다라마바사아자차카타", PresetRules.cleanName("가나다라마바사아자차카타파하"))
        assertNull(PresetRules.checkName(fresh(), "새 설정"))
        assertNull("자기 자신은 빼고 본다", PresetRules.checkName(fresh(), "출퇴근용", exceptId = "ex-commute"))
    }
}
