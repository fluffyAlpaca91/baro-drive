package com.barodrive.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** 코치 id → 얼굴 (프로토타입 components.js `coachId()`·`coachNow()`: 모르는 값이면 아빠/배우자). */
class CoachFaceTest {
    @Test
    fun mapsPrototypeCoachIdsAndFallsBackToDad() {
        assertEquals(CoachFace.Sibling, CoachFace.fromId("sib"))
        assertEquals(CoachFace.Mom, CoachFace.fromId("mom"))
        assertEquals(CoachFace.Dad, CoachFace.fromId("dad"))
        assertEquals(CoachFace.Dad, CoachFace.fromId(null))
        assertEquals(CoachFace.Dad, CoachFace.fromId("aunt"))
    }
}
