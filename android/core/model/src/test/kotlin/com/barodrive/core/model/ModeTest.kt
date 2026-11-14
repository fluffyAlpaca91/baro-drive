package com.barodrive.core.model

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ModeTest {
    @Test
    fun namesMatchPrototypeCopy() {
        assertEquals((1..8).toList(), Mode.numbers)
        assertEquals("1단계 · 차량 감 익히기", Mode.of(1).label)
        assertEquals("4단계 · 고속도로 타 보기", Mode.of(4).label)
        assertEquals("자율 모드", Mode.of(5).label)
        assertEquals("운전면허 연습 모드", Mode.LICENSE.label)
        assertEquals("면허", Mode.LICENSE.short)
        assertEquals(listOf(true, true, true, true, false, false, false, false), Mode.entries.map { it.isStage })
        assertEquals("주차 안내만 말해요.", Mode.PARKING.talk)
    }

    @Test
    fun badNumberFailsLoudlyOrReturnsNull() {
        assertNull(Mode.ofOrNull(0))
        val error = assertThrows(IllegalArgumentException::class.java) { Mode.of(9) }
        assertEquals("모드는 1~8이에요: 9", error.message)
    }

    @Test
    fun serializesAsNumber() {
        val serializer = ListSerializer(Mode.serializer())

        val text = BaroJson.encodeToString(serializer, listOf(Mode.STAGE2, Mode.LICENSE))

        assertEquals("[2,8]", text)
        assertEquals(listOf(Mode.STAGE2, Mode.LICENSE), BaroJson.decodeFromString(serializer, text))
    }

    @Test
    fun displayLabelsMatchPrototypeCopy() {
        assertEquals(listOf("형제/자매", "엄마", "아빠/배우자"), Coach.entries.map { it.title })
        assertEquals("바로 피드백", Delivery.NOW.label)
        assertEquals("완료 후", Delivery.AFTER.shortLabel)
        assertEquals("50m 안", BlockReason.UNDER_50.label)
        assertEquals("요령", CardCategory.TIP.label)
        assertEquals("사전 안내", Category.PRE.label)
        assertEquals(Delivery.AFTER, ItemDef(id = "B1", defaults = "OOSSSXSO").defaultFor(3))
        assertNull(ItemDef(id = "B1", defaults = "OOSSSXSO").defaultFor(9))
    }
}
