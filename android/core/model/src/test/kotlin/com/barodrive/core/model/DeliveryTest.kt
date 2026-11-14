package com.barodrive.core.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryTest {
    @Test
    fun codesMatchPrototype() {
        assertEquals(listOf("O", "S", "X"), Delivery.entries.map { it.code })
    }

    @Test
    fun fromCodeFindsEachValueAndRejectsUnknown() {
        assertEquals(Delivery.NOW, Delivery.fromCode("O"))
        assertEquals(Delivery.AFTER, Delivery.fromCode("S"))
        assertEquals(Delivery.OFF, Delivery.fromCode("X"))
        assertNull(Delivery.fromCode("o"))
    }

    @Test
    fun serializesAsCode() {
        val serializer = ListSerializer(Delivery.serializer())

        val text = Json.encodeToString(serializer, Delivery.entries)

        assertEquals("""["O","S","X"]""", text)
        assertEquals(Delivery.entries, Json.decodeFromString(serializer, text))
    }
}
