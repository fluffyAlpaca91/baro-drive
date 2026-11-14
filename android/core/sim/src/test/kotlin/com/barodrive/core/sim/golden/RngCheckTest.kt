package com.barodrive.core.sim.golden

import com.barodrive.core.sim.util.SeedRandom
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** 시드 난수 포트 = 프로토타입 `Baro.util.rng`·`hash`(골든 manifest `rngCheck`: 시드마다 hash와 첫 값 5개). */
class RngCheckTest {
    @Test
    fun hashAndFirstFiveValuesMatchThePrototype() {
        val checks = GoldenFixtures.manifest.array("rngCheck")
        assertEquals(4, checks.size)
        for (c in checks) {
            val o = c.jsonObject
            val seed = o["seed"]?.jsonPrimitive ?: error("seed")
            val r = if (seed.isString) SeedRandom.of(seed.content) else SeedRandom.of(requireNotNull(seed.doubleOrNull))
            if (seed.isString) assertEquals(seed.content, o.double("hash").toLong(), SeedRandom.hash(seed.content))
            val first5 = o.array("first5").map { requireNotNull((it as JsonPrimitive).doubleOrNull) }
            assertEquals(seed.content, first5, List(5) { r() })
        }
    }
}
