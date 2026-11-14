package com.barodrive.core.engine.golden

import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.content.ParkingGuide
import com.barodrive.core.engine.of
import com.barodrive.core.engine.review.LicenseCalc
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.HillUpAttempt
import com.barodrive.core.model.HillUpSummary
import com.barodrive.core.model.LicenseSceneCounts
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.ParkingWheel
import com.barodrive.core.model.TurnDir
import kotlin.math.abs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프로토타입 시나리오 시험(t5b S6-P · t6 S7-B · t8 S8-C, 실력 '보통')의 엔진 쪽 단언을 골든 회차(같은 시뮬레이터 스냅숏)로 다시 돌려 본다.
 * 시나리오 정의(expect 목록·장면 이름)·내비 음성처럼 시뮬레이터에만 있는 값은 시뮬레이터 포트 쪽 몫이다.
 */
class GoldenModesScenarioTest {
    @Test
    fun s6pParallelRightNormalStepsBySignalPk2Pk3AndPb2() {
        val r = GoldenReplay.replay("m6-S6-P-normal")
        val rec = r.record
        val pk = checkNotNull(rec.parking)
        assertEquals(listOf<Any?>(ParkingType.PARALLEL, TurnDir.RIGHT, true, 8, 8), listOf(pk.type, pk.side, pk.done, pk.reached, pk.total))
        assertTrue("다 마치면 도착(hints.arrived)으로 끝남", rec.arrived)
        assertEquals("넘긴 방법: 1단계만 누름", (listOf("press") + List(7) { "signal" }).joinToString(","), pk.steps.joinToString(",") { it.by.orEmpty() })
        val keys = rec.utterances.filter { it.spoken }.map { it.key }
        for (st in ParkingGuide.steps("parallel", "R")) assertTrue(st.key, st.key in keys)
        assertEquals("후진 9km/h 한 번", 1, keys.count { it == "pk2" })
        assertEquals(1, pk.pk2)
        assertTrue("끝 바퀴 8°", "pk3" in keys)
        assertEquals(ParkingWheel(steer = -8.0, straight = false), pk.wheel)
        assertEquals("사이드브레이크 안 채움", EventResult.FAIL, pk.handbrake?.result)
        val pk3 = rec.utterances.first { it.key == "pk3" && it.spoken }
        val pb2 = rec.utterances.first { it.key == "pb2" && it.spoken }
        assertTrue("P 때 말이 다 나옴: pk3(P) → pb2(3초 뒤, 결정 T5b-Q2)", pb2.t > pk3.t)
        // 단계 문장은 차례대로, 겹치지 않고
        val said = rec.utterances.filter { it.spoken }
        for (i in 1 until said.size) assertTrue("겹침 ${said[i].key}", said[i].t >= said[i - 1].t + Lines.speakDur(said[i - 1].text) - 1e-6)
        assertTrue(r.session.errors.isEmpty())
    }

    @Test
    fun s7bNormalUphillTwiceDownhillAutoSwitchHillParkingThenHl3OkAndPb2Hill() {
        val r = GoldenReplay.replay("m7-S7-B-normal")
        val rec = r.record
        assertTrue(rec.arrived)
        val keys = rec.utterances.filter { it.spoken }.map { it.key }
        assertEquals(
            listOf(
                "pb1.on", "hl1.up.1", "hl1.up.2", "hl1.up.3", "hl1.up.4", "hl1.up.1", "hl1.up.2", "hl1.up.3", "hl1.up.4", "hl1.up.1",
                "hl1.down.1", "hl1.down.2", "hl1.down.3", "hl1.park.2", "hl1.park.3", "hl3.ok", "pb2.hill",
            ),
            keys.filter { it.startsWith("hl") || it.startsWith("pb") },
        )
        val hill = checkNotNull(rec.hill)
        assertEquals(HillUpSummary(attempts = 2, rollbacks = 0, rollOff = false, list = listOf(HillUpAttempt(1, false, true), HillUpAttempt(2, false, true))), hill.up)
        assertEquals(Triple(1, 4, 0), Triple(hill.down.runs, hill.down.presses, hill.down.holds))
        val park = checkNotNull(hill.park)
        assertEquals(listOf<Any?>(HillSlope.DOWN, true, TurnDir.RIGHT, HandbrakeState.OFF), listOf(park.situation, park.wheelOk, park.wheelSide, park.handbrake))
        assertEquals(listOf(Triple("T-HILLUP", 2, 2), Triple("T-HILLDOWN", 1, 1), Triple("T-HILLPARK", 0, 1)), rec.tasks.map { Triple(it.id, it.success, it.total) })
        assertEquals("T-HILLPARK", rec.nextTask?.id)
        assertEquals(0.75, rec.achievement)
        assertTrue(rec.good.toString(), rec.good.any { it.text == "오르막에서 뒤로 밀리지 않고 출발하기 2/2번" })
        // 언덕 주차 자리에 선 뒤에 상황·핸들·P가 오고, 서 있는 채로 끝남(t6 '세 실력 모두'의 보통)
        val rows = GoldenFixtures.load("m7-S7-B-normal").snapshots.map { s ->
            Triple(s.double("t"), s["speed"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull ?: 0.0, s["gear"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content)
        }
        val sp = rec.utterances.filter { it.spoken }
        val tSit = sp.first { it.key == "hl1.park.2" }.t
        val tP = rows.first { it.third == "P" && it.first > 100 }.first
        val tStop = rows.last { it.first < tSit && abs(it.second) >= 0.5 }.first + 0.1
        assertTrue("선 뒤 1초 넘게 지나서 상황($tStop → $tSit)", tSit - tStop >= 1)
        assertTrue("P까지 서 있음", rows.filter { it.first >= tStop && it.first <= tP }.all { abs(it.second) < 0.5 })
        val tPark3 = sp.first { it.key == "hl1.park.3" }.t
        assertTrue("바퀴를 돌린 뒤 P", tPark3 > tSit && tPark3 < tP)
        assertEquals(1, rec.events.of("HL3", "ok").size)
        assertTrue(r.session.errors.isEmpty())
    }

    @Test
    fun s8cNormalThreeSegmentsCompleteCompanionCardAndFiveScenes() {
        val r = GoldenReplay.replay("m8-S8-C-normal")
        val rec = r.record
        assertTrue("마지막 구간 도착 = 세션 도착", rec.arrived)
        val lic = checkNotNull(rec.license)
        assertEquals(listOf<Any?>(true, 3, 3, "C"), listOf(lic.complete, lic.reached, lic.segmentsDone, lic.letter))
        assertEquals("구간 3/3" to true, r.lastHints.license?.text to r.lastHints.license?.complete)
        // 동승자 카드: 2구간에서 조수석이 비고, 바로 다음 신호 대기에 안전 카드 → 그 문장을 한 번 말함(안전)
        assertEquals(1, lic.alone)
        assertEquals("2구간에서 조수석 비움(설계 7.7)", 2, lic.aloneAt[0].segment)
        val m2 = rec.events.of("M2", "fail")
        assertEquals(1, m2.size)
        val card = r.cards.first { it.item == "M2" }
        assertTrue(card.category == CardCategory.SAFETY && card.key == "lic.alone")
        val next = r.cards.filter { it.shownAt != null && checkNotNull(it.shownAt) >= m2[0].t }
        assertEquals("조수석이 빈 뒤 첫 신호 대기의 맨 앞 카드", card.id, next.first().id)
        val u = rec.utterances.first { it.spoken && it.key == "lic.alone" }
        assertTrue(u.category == Category.SAFETY && u.t >= checkNotNull(card.shownAt))
        // 사이드브레이크 채운 채 출발(PB1)·사전 안내(C1, 처음 2회차)·어린이보호구역(E2)·제한속도(E1)를 실제로 말함
        for (k in listOf("pb1.on", "c1", "c2.ok", "e2", "e1", "lic.alone", "pb2")) assertTrue(k, rec.utterances.any { it.spoken && it.key == k })
        // 장면(RV-5): 방향지시등 없이 회전·신호 늦음·급브레이크·제한속도·어린이보호구역
        assertEquals(
            listOf("방향지시등 없이 회전 1번", "회전 신호가 늦음 1번", "급브레이크 1번", "제한속도 초과 1번", "어린이보호구역 속도 초과 1번"),
            LicenseCalc.scenes(rec).map { it.text },
        )
        assertEquals("회차 메모도 같은 개수", LicenseSceneCounts(noSignal = 1, lateSignal = 1, hardBrake = 1, speed = 1, school = 1, hardStart = 0), lic.scenes)
        // 모드 8 과제(6.4)와 다음 과제(안전 위반 1순위: 어린이보호구역)
        assertEquals(listOf("T-LIMIT", "T-SIGNAL30", "T-WAIT3", "T-STOPSIG"), rec.tasks.map { it.id })
        assertEquals("T-SCHOOL", rec.nextTask?.id)
        assertEquals(listOf("A", "C"), LicenseCalc.coursesAfter(listOf("A"), rec))
        assertTrue("코스 데이터 5.3km ${rec.distanceM}", abs(rec.distanceM - 5300) < 160)
        assertTrue(r.session.errors.isEmpty())
    }
}
