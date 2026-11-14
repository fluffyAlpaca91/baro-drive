package com.barodrive.core.engine.content

import com.barodrive.core.model.Category
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.SignalCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 코칭 항목 데이터와 항목 함수 (프로토타입 t1c_engine.test.js '데이터' 묶음, t10_caps · t3_safety3 · t6_hill · t4_feedback의 항목 단언,
 * 설계 6.3.2·7.8·12).
 */
class ItemsTest {
    /** 설계 6.3.2 표 그대로(모드 1~8). */
    private val table = mapOf(
        "J3" to "OOOOOXOO", "A1" to "OOOOOXOO", "PB1" to "OOXXXXOO", "L1" to "OOOOOXOO", "B1" to "OOSSSXSO", "C1" to "OOXXXXXO",
        "C2" to "OOSSSXSO", "C4" to "OOSSSXSO", "C5" to "OOSSSXSO", "F1" to "OOSSSXSO", "F2" to "OOOOOXOO", "E1" to "OOOOOXOO",
        "E2" to "OOOOOXOO", "C3" to "OOOOOXOO", "C6" to "OOOOOXOO", "J1" to "OOOOOXOO", "G1" to "SSSSSXSS", "PK1" to "XXXXXOXX",
        "PK2" to "XXXXXOXX", "PK3" to "XXXXXOXX", "PB2" to "OOXXXOOO", "HL1" to "XXXXXXOX", "HL2" to "XXXXXXOX", "HL3" to "XXXXXXOX",
    )

    private fun codes(list: List<Delivery>) = list.map { it.code }

    @Test
    fun twentyFourItemsWithTheDefaultsOfTable632() {
        assertEquals(24, Items.ids().size)
        assertEquals(table.keys.sorted(), Items.ids().sorted())
        for (m in 1..8) for ((id, row) in table) {
            assertEquals("$id 모드 $m 기본값", row[m - 1].toString(), Items.defaultFor(id, m)?.code)
        }
    }

    @Test
    fun idsFollowTheFb1RowOrder() {
        // 목업 s10 ALERTS 순서, 안전3은 E2 뒤(items.js 순서)
        val order = listOf(
            "J3", "A1", "PB1", "L1", "B1", "C1", "C2", "C4", "C5", "F1", "F2", "E1", "E2", "C3", "C6", "J1",
            "G1", "PK1", "PK2", "PK3", "PB2", "HL1", "HL2", "HL3",
        )
        assertEquals(order, Items.ids())
        assertEquals((1..24).toList(), Items.all.map { it.order })
    }

    @Test
    fun allowedValuesAndFixedItems() {
        assertEquals(listOf("O", "X"), codes(Items.allowed("C1")))
        assertEquals(listOf("O", "X"), codes(Items.allowed("L1")))
        assertEquals(listOf("O", "X"), codes(Items.allowed("PK1")))
        assertEquals(listOf("O", "X"), codes(Items.allowed("HL1")))
        assertEquals(listOf("S", "X"), codes(Items.allowed("G1")))
        for (id in listOf("J3", "A1", "PB1", "B1", "C2", "C4", "C5", "F1", "PK2", "PK3", "PB2", "HL2", "HL3")) {
            assertEquals(id, listOf("O", "S", "X"), codes(Items.allowed(id)))
            assertFalse(id, Items.isFixed(id))
        }
        for (id in listOf("F2", "E1", "E2", "C3", "C6", "J1")) {
            assertTrue(id, Items.isFixed(id))
            assertEquals(id, emptyList<Delivery>(), Items.allowed(id))
        }
        assertEquals("HL2는 안전 분류지만 고정은 아님(6.5.1, 6.3.2)", Category.SAFETY, Items.def("HL2")!!.cats[0])
        assertFalse(Items.isFixed("HL2"))
        for (id in Items.ids()) {
            val d = Items.def(id)!!
            assertTrue("$id 이름·짧은 이름·아이콘", d.name.isNotBlank() && d.short.isNotBlank() && d.icon.isNotBlank())
        }
    }

    @Test
    fun unknownItemsAndModes() {
        assertNull(Items.def("ZZ"))
        assertNull(Items.defaultFor("ZZ", 1))
        assertEquals(emptyList<Delivery>(), Items.allowed("ZZ"))
        assertFalse(Items.isFixed("ZZ"))
        val e = assertThrows(IllegalArgumentException::class.java) { Items.defaultFor("A1", 9) }
        assertEquals("모드는 1~8이에요: 9", e.message)
        assertThrows(IllegalArgumentException::class.java) { Items.defaultFor("A1", 0) }
    }

    @Test
    fun itemsTurnedOffByUnreadableSignalsTable78() {
        fun off(k: String) = Items.itemsOffByCaps(mapOf(k to false)).sorted()
        assertEquals(listOf("B1", "E1", "E2", "F1", "F2", "HL2", "PK2"), off("speed"))
        assertEquals(listOf("C1", "C2", "C3", "C4", "C5", "C6"), off("blinker"))
        assertEquals(listOf("C4", "C5", "G1", "HL3", "PK3"), off("steer"))
        assertEquals(listOf("C1", "C2", "C3", "E1", "E2"), off("nav"))
        assertEquals(listOf("A1"), off("beltDriver"))
        assertEquals(listOf("HL2"), off("reverseSpeed"))
        for (k in listOf("gear", "passenger", "handbrake", "wiper", "outsideTemp", "trip")) {
            assertEquals("${k}는 항목을 끄지 않고 대안으로", emptyList<String>(), off(k))
        }
        assertEquals(
            "대안이 있는 신호는 항목을 끄지 않음",
            emptyList<String>(),
            Items.itemsOffByCaps(mapOf("handbrake" to false, "passenger" to false, "wiper" to false)),
        )
        assertEquals("FB-1 줄 순서", listOf("C1", "C2", "C4", "C5", "C3", "C6"), Items.itemsOffByCaps(mapOf("blinker" to false, "steer" to true)))
        assertEquals(emptyList<String>(), Items.itemsOffByCaps(SignalCaps()))
        assertEquals(listOf("HL2"), Items.itemsOffByCaps(SignalCaps(reverseSpeed = false)))
        assertEquals(Items.itemsOffByCaps(mapOf("nav" to false, "steer" to false)), Items.itemsOffByCaps(SignalCaps(nav = false, steer = false)))
        for (id in Items.ids()) for (k in Items.def(id)!!.needs + Items.def(id)!!.uses) assertTrue("$id $k", k in Items.CAP_KEYS)
    }

    @Test
    fun extraSafetySwitchOffLeavesTwentyOneItems() {
        assertTrue("기본값은 켬", Items.EXTRA_SAFETY_ITEMS)
        val ids = Items.ids(extraSafety = false)
        assertEquals(21, ids.size)
        for (id in listOf("C3", "C6", "J1")) {
            assertFalse(id, id in ids)
            assertTrue(id, Items.def(id)!!.extra)
            assertEquals("항목 정의는 스위치와 상관없이 찾음", Delivery.NOW, Items.defaultFor(id, 1))
        }
        assertEquals(listOf("C1", "C2", "C4", "C5"), Items.itemsOffByCaps(mapOf("blinker" to false), extraSafety = false))
        assertEquals(24, Items.ids().size)
    }

    @Test
    fun safety3ItemsAreAlwaysOnOutsideParkingT3() {
        for (m in listOf(1, 2, 3, 4, 5, 7, 8)) for (id in listOf("C3", "C6", "J1")) assertEquals("$id 모드 $m", Delivery.NOW, Items.defaultFor(id, m))
        for (id in listOf("C3", "C6", "J1")) assertEquals(Delivery.OFF, Items.defaultFor(id, 6))
    }

    @Test
    fun itemFieldsMirrorItemsJs() {
        val c1 = Items.def("C1")!!
        assertEquals(listOf(2, 8), c1.first2)
        assertEquals("처음 2회차만 바로 말해요", c1.note?.first2On)
        assertEquals("3회차부터는 말하지 않아요", c1.note?.first2Off)
        assertEquals("이 항목은 달리는 중에만 쓸 수 있어요", c1.lock?.after)
        assertNull(c1.lock?.now)
        assertEquals("정차 중에 바로 하는 안내라 끝난 뒤에는 쓸 수 없어요", Items.def("L1")!!.lock?.after)
        assertEquals("달리는 중에는 말할 일이 없어서 끝난 뒤에 알려 줘요", Items.def("G1")!!.lock?.now)
        assertEquals("단계마다 바로 하는 안내라 끝난 뒤에는 쓸 수 없어요", Items.def("PK1")!!.lock?.after)
        assertEquals("단계마다 바로 하는 안내라 끝난 뒤에는 쓸 수 없어요", Items.def("HL1")!!.lock?.after)
        assertNull(Items.def("B1")!!.lock)
        for (id in listOf("E1", "E2", "C3", "C6", "J1")) {
            assertEquals(id, ItemLock(all = "안전 항목이라 늘 바로 알려 줘요", off = "주차 연습에서는 쓰지 않는 항목이에요"), Items.def(id)!!.lock)
            assertEquals(id, ItemGroup.SAFETY, Items.def(id)!!.group)
        }
        val f2 = Items.def("F2")!!
        assertEquals(ItemGroup.RULE, f2.group)
        assertEquals("늘 지키는 규칙이라 바꿀 수 없어요", f2.lock?.all)
        assertEquals("급제동 뒤 30초는 조용히, 정차 후 직접 확인", f2.note?.rule)
        assertEquals("비·눈 오는 날에만 말해요", Items.def("J3")!!.note?.wx)
        assertEquals("뒤로 가는 속도를 못 읽으면 꺼져요", Items.def("HL2")!!.note?.needs)
        assertEquals("말하지 않는 항목", emptyList<Category>(), f2.cats)
        assertEquals(emptyList<Category>(), Items.def("G1")!!.cats)
        assertEquals("도움 줄여 가기 대상(설계 6.7)", listOf("B1", "C2", "C4", "C5", "F1"), Items.all.filter { it.fade }.map { it.id })
        assertEquals("단계 안내(결정사항 E T1c-3)", listOf("PK1", "PK2", "HL1"), Items.all.filter { it.guide }.map { it.id })
        assertEquals("신호 대기 카드(설계 6.5.1)", listOf("B1", "C4", "C5", "F1"), Items.all.filter { it.card }.map { it.id })
        assertEquals(
            "FB-2 항목 12개(결정-23)",
            listOf("J3", "A1", "PB1", "L1", "B1", "C1", "C2", "C4", "C5", "F1", "G1", "PB2"),
            Items.all.filter { it.fb2 }.map { it.id },
        )
        assertEquals(listOf("wiper", "outsideTemp"), Items.def("J3")!!.uses)
        assertEquals(listOf(Category.FIX, Category.PRAISE), Items.def("B1")!!.cats)
    }
}
