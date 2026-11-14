package com.barodrive.app.ui.components

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** 날짜 고르기 계산 (프로토타입 components.js `datePicker()`의 addUnit·clampDay, LC-2 연습면허 발급일). */
class DateStepperTest {
    private val today = LocalDate.of(2026, 11, 11)

    @Test
    fun movesByTheUnitAndKeepsTheDayInsideTheMonth() {
        val jan31 = LocalDate.of(2026, 1, 31)
        assertEquals(LocalDate.of(2026, 2, 28), DateStepper.step(jan31, DateUnit.Month, 1, null, null))
        assertEquals(LocalDate.of(2025, 1, 31), DateStepper.step(jan31, DateUnit.Year, -1, null, null))
        assertEquals(LocalDate.of(2026, 2, 1), DateStepper.step(jan31, DateUnit.Day, 1, null, null))
        assertEquals(LocalDate.of(2027, 2, 28), DateStepper.step(LocalDate.of(2028, 2, 29), DateUnit.Year, -1, null, null))
    }

    @Test
    fun neverGoesPastTheEnds() {
        // 오늘보다 뒤 날짜는 고를 수 없다(설계 4.2 LC-2): 넘으면 오늘에 맞춘다.
        assertEquals(today, DateStepper.step(LocalDate.of(2026, 10, 20), DateUnit.Month, 1, DateStepper.DEFAULT_MIN, today))
        assertEquals(today, DateStepper.step(today, DateUnit.Day, 1, DateStepper.DEFAULT_MIN, today))
        assertEquals(DateStepper.DEFAULT_MIN, DateStepper.step(LocalDate.of(1950, 3, 1), DateUnit.Year, -1, DateStepper.DEFAULT_MIN, today))
    }

    @Test
    fun clampsAValueOutsideTheRange() {
        assertEquals(today, DateStepper.clamp(LocalDate.of(2027, 1, 1), null, today))
        assertEquals(DateStepper.DEFAULT_MIN, DateStepper.clamp(LocalDate.of(1900, 1, 1), DateStepper.DEFAULT_MIN, null))
        assertEquals(LocalDate.of(2000, 5, 5), DateStepper.clamp(LocalDate.of(2000, 5, 5), DateStepper.DEFAULT_MIN, today))
    }
}
