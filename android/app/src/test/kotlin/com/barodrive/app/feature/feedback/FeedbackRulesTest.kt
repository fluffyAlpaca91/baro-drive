package com.barodrive.app.feature.feedback

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Delivery.AFTER
import com.barodrive.core.model.Delivery.NOW
import com.barodrive.core.model.Delivery.OFF
import com.barodrive.core.model.DeliveryReason
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.Mode
import com.barodrive.core.model.Onboarding
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FB-1 계산 (프로토타입 tests/unit/t4_feedback.test.js 'FB-1' 묶음 — 같은 입력, 같은 기대값).
 * 항목 정의·resolveItems에 기대는 시험은 엔진이 옮겨질 때까지 건너뛴다([assumeItemsPorted]).
 */
class FeedbackRulesTest {
    private fun fresh() = Profile(onboarding = Onboarding(done = true), myPresets = BaroEngine.examplePresets())

    private fun ModeRows.row(id: String): Pair<FeedbackGroupId, FeedbackRow>? =
        groups.firstNotNullOfOrNull { g -> g.rows.firstOrNull { it.id == id }?.let { g.id to it } }

    private fun ModeRows.of(id: String): FeedbackRow = requireNotNull(row(id)) { "$id 줄이 없어요" }.second

    @Test
    fun stage1GroupsLocksAndNotes() {
        assumeItemsPorted()
        val m = FeedbackRules.modeRows(1, fresh())
        assertEquals("1 · 차량 감 익히기", m.title)
        assertEquals(Mode.of(1).talk, m.talk)
        assertNull(m.extraNote)
        assertEquals(
            listOf(
                Triple(FeedbackGroupId.SAFETY, "안전 항목 · 늘 바로 피드백", listOf("E1", "E2", "C3", "C6", "J1")),
                Triple(FeedbackGroupId.RULE, "늘 지키는 규칙", listOf("F2")),
                Triple(
                    FeedbackGroupId.USED, "이 모드의 피드백 항목 12개",
                    listOf("J3", "A1", "PB1", "L1", "B1", "C1", "C2", "C4", "C5", "F1", "G1", "PB2"),
                ),
                Triple(FeedbackGroupId.OFF, "이 모드에서 기본으로 안 하는 항목 6개", listOf("PK1", "PK2", "PK3", "HL1", "HL2", "HL3")),
            ),
            m.groups.map { g -> Triple(g.id, g.title, g.rows.map { it.id }) },
        )
        val e1 = m.of("E1")
        assertEquals(NOW, e1.value)
        assertEquals(true, e1.locked)
        assertEquals(listOf(NOW), e1.allowed)
        assertEquals("안전 항목 · 바꿀 수 없어요", e1.note)
        val safe = "안전 항목이라 늘 바로 알려 줘요"
        assertEquals(mapOf(NOW to safe, AFTER to safe, OFF to safe), e1.lock)
        val f2 = m.of("F2")
        assertEquals(FeedbackRowKind.RULE, f2.kind)
        assertEquals("급제동 뒤 30초는 조용히, 정차 후 직접 확인", f2.note)
        assertEquals("비·눈 오는 날에만 말해요", m.of("J3").note)
        for (r in m.groups[2].rows) assertEquals("${r.id} 기본값", BaroEngine.defaultFor(r.id, 1), r.value)
    }

    @Test
    fun locksFollowAllowedValues() {
        assumeItemsPorted()
        val m = FeedbackRules.modeRows(1, fresh())
        assertEquals(listOf(NOW, OFF), m.of("C1").allowed)
        assertEquals("이 항목은 달리는 중에만 쓸 수 있어요", m.of("C1").lock[AFTER])
        assertNull(m.of("C1").lock[NOW])
        assertEquals("정차 중에 바로 하는 안내라 끝난 뒤에는 쓸 수 없어요", m.of("L1").lock[AFTER])
        assertEquals(listOf(AFTER, OFF), m.of("G1").allowed)
        assertEquals("달리는 중에는 말할 일이 없어서 끝난 뒤에 알려 줘요", m.of("G1").lock[NOW])
        assertEquals("단계마다 바로 하는 안내라 끝난 뒤에는 쓸 수 없어요", m.of("PK1").lock[AFTER])
        assertEquals("단계마다 바로 하는 안내라 끝난 뒤에는 쓸 수 없어요", m.of("HL1").lock[AFTER])
        assertEquals(mapOf(NOW to null, AFTER to null, OFF to null), m.of("B1").lock)
    }

    @Test
    fun parkingModeKeepsSafetyLockedInOffGroup() {
        assumeItemsPorted()
        val m = FeedbackRules.modeRows(6, fresh())
        assertEquals(listOf(FeedbackGroupId.USED, FeedbackGroupId.OFF), m.groups.map { it.id })
        assertEquals(listOf("PK1", "PK2", "PK3", "PB2"), m.groups[0].rows.map { it.id })
        val (group, e1) = requireNotNull(m.row("E1"))
        assertEquals(FeedbackGroupId.OFF, group)
        assertEquals(OFF, e1.value)
        assertEquals(true, e1.locked)
        assertEquals("주차 연습에서는 쓰지 않는 항목이에요", e1.lock[NOW])
        assertEquals(FeedbackRowKind.RULE, m.of("F2").kind)
        assertEquals("주차 연습에서는 쓰지 않아요", m.of("F2").note)
        // 안전3 스위치(EXTRA_SAFETY_ITEMS)는 Kotlin에서 상수라 꺼 보는 부분은 옮기지 않았다.
        assertEquals("자율 모드 머리 글", FeedbackRules.MODE5_NOTE, FeedbackRules.modeRows(5, fresh()).extraNote)
        assertEquals("뒤로 가는 속도를 못 읽으면 꺼져요", FeedbackRules.modeRows(7, fresh()).of("HL2").note)
    }

    @Test
    fun c1FirstTwoSessionsInModes2And8() {
        assumeItemsPorted()
        var p = fresh()
        for (mode in listOf(2, 8)) {
            p = p.copy(sessionCount = p.sessionCount + (mode to 0))
            var c1 = FeedbackRules.modeRows(mode, p).of("C1")
            assertEquals(Triple(NOW, DeliveryReason.FIRST2, "처음 2회차만 바로 말해요"), Triple(c1.value, c1.reason, c1.note))
            p = p.copy(sessionCount = p.sessionCount + (mode to 1))
            assertEquals("2회차", NOW, FeedbackRules.modeRows(mode, p).of("C1").value)
            p = p.copy(sessionCount = p.sessionCount + (mode to 2))
            c1 = FeedbackRules.modeRows(mode, p).of("C1")
            assertEquals(Triple(OFF, DeliveryReason.FIRST2, "3회차부터는 말하지 않아요"), Triple(c1.value, c1.reason, c1.note))
        }
        p = requireNotNull(FeedbackRules.setOverride(p, 2, "C1", NOW)) { "3회차에 다시 켜면 내 변경" }
        val c1 = FeedbackRules.modeRows(2, p).of("C1")
        assertEquals(Triple(NOW, DeliveryReason.USER, ""), Triple(c1.value, c1.reason, c1.note))
        assertEquals("1단계는 매번(글 없음)", "", FeedbackRules.modeRows(1, p).of("C1").note)
    }

    @Test
    fun fadedItemsShowReportOnlyAndUserChangeWins() {
        assumeItemsPorted()
        var p = fresh().copy(fading = mapOf(2 to mapOf("B1" to FadeState(2, true), "C2" to FadeState(2, true))))
        val m = FeedbackRules.modeRows(2, p)
        assertEquals(AFTER to "2회 연속 잘해서 지금은 리포트로만 알려요", m.of("B1").value to m.of("B1").note)
        assertEquals(AFTER to DeliveryReason.FADING, m.of("C2").value to m.of("C2").reason)
        assertEquals(OFF to FeedbackRules.C1_FADING, m.of("C1").value to m.of("C1").note)
        assertEquals("안 내려간 항목", "", m.of("F1").note)
        p = requireNotNull(FeedbackRules.setOverride(p, 2, "B1", NOW))
        assertEquals(mapOf("B1" to NOW), p.feedback.overrides[2])
        FeedbackRules.modeRows(2, p).of("B1").let { assertEquals(NOW to "", it.value to it.note) }
        // 다시 '완료 후'(도움 줄여 가기 값)를 고르면 내 변경을 지워 도움 줄여 가기에 맡긴다
        p = requireNotNull(FeedbackRules.setOverride(p, 2, "B1", AFTER))
        assertNull("빈 모드 줄은 지움", p.feedback.overrides[2])
        assertEquals(DeliveryReason.FADING, FeedbackRules.modeRows(2, p).of("B1").reason)
    }

    @Test
    fun setOverrideRejectsLockedAndClearsDefaults() {
        assumeItemsPorted()
        var p = fresh()
        p = requireNotNull(FeedbackRules.setOverride(p, 1, "C2", OFF))
        assertEquals(mapOf(1 to mapOf("C2" to OFF)), p.feedback.overrides)
        assertNull("같은 값", FeedbackRules.setOverride(p, 1, "C2", OFF))
        assertNull("C1 완료 후는 고를 수 없음", FeedbackRules.setOverride(p, 1, "C1", AFTER))
        assertNull("안전 항목 고정", FeedbackRules.setOverride(p, 1, "E1", OFF))
        assertNull("늘 지키는 규칙", FeedbackRules.setOverride(p, 1, "F2", OFF))
        assertNull("없는 모드", FeedbackRules.setOverride(p, 9, "C2", OFF))
        p = requireNotNull(FeedbackRules.setOverride(p, 1, "G1", OFF))
        p = requireNotNull(FeedbackRules.setOverride(p, 3, "B1", NOW))
        p = requireNotNull(FeedbackRules.setOverride(p, 1, "C2", NOW)) { "기본값으로 돌아감" }
        assertEquals(mapOf(1 to mapOf("G1" to OFF), 3 to mapOf("B1" to NOW)), p.feedback.overrides)
        val (reset, n) = FeedbackRules.resetMode(p, 1)
        assertEquals(1, n)
        assertEquals("그 모드만 되돌림", mapOf(3 to mapOf("B1" to NOW)), reset.feedback.overrides)
        assertEquals(0, FeedbackRules.resetMode(reset, 1).second)
    }

    @Test
    fun resetToastUsesModeNameWithJosa() {
        assertEquals("1 · 차량 감 익히기를 기본값으로 되돌렸어요", FeedbackRules.resetToast(1))
        assertEquals("5 · 자율 모드를 기본값으로 되돌렸어요", FeedbackRules.resetToast(5))
        assertEquals("7 · 언덕 주행을 기본값으로 되돌렸어요", FeedbackRules.resetToast(7))
        // 바뀐 것이 없으면 0개를 지운 그대로
        val p = fresh()
        assertEquals(p to 0, FeedbackRules.resetMode(p, 1))
    }

    @Test
    fun changedValueGoesIntoTheNextSessionConfig() {
        assumeItemsPorted()
        val p = requireNotNull(FeedbackRules.setOverride(fresh(), 1, "C2", OFF))
        val cfg = BaroEngine.buildConfig(1, p, PrepSession(mode = 1, courseId = "c1-B"))
        assertEquals(OFF, cfg.items["C2"])
        assertEquals(DeliveryReason.USER, cfg.itemReasons["C2"])
        // (프로토타입은 이어서 S1-B를 돌려 c2.* 말이 X로 막히는 것까지 본다 — 시뮬레이터 골든 시험의 몫)
        assertNotNull(cfg)
    }

    @Test
    fun lockTextFallsBackToNotAllowedReason() {
        val row = FeedbackSamples.c1
        assertEquals("이 항목은 달리는 중에만 쓸 수 있어요", row.lockText(AFTER))
        assertEquals(FeedbackRules.NA_REASON, row.lockText(Delivery.NOW))
    }
}
