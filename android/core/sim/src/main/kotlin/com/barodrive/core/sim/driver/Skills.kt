package com.barodrive.core.sim.driver

import com.barodrive.core.model.DriverSkill

/**
 * 운전 실력 표 (프로토타입 sim/driver.js `SKILLS`, 설계 7.3 표).
 * 서툰: 깜빡이 거리 × 0.6 · 1.5초 먼저 꺾음 · 출발 +4 · 정지 +3(11 아래는 10.9까지) · 흔들림 1.25°
 * 보통: 그대로 · 흔들림 0.95° / 능숙: 깜빡이 60m 넘게 · 켜고 3.5초 뒤 꺾음 · 출발 6 이하 · 정지 5 이하 · 흔들림 0.6° · 안전 위반 없음.
 */
data class SkillSpec(
    val skill: DriverSkill,
    val blinkMul: Double = 1.0,
    val steerShift: Double = 0.0,
    val gainAdd: Double = 0.0,
    val dropAdd: Double = 0.0,
    val wobble: Double,
    val blinkMin: Double? = null,
    val steerMin: Double? = null,
    val gainMax: Double? = null,
    val dropMax: Double? = null,
    val safe: Boolean = false,
) {
    companion object {
        val POOR = SkillSpec(DriverSkill.POOR, blinkMul = 0.6, steerShift = -1.5, gainAdd = 4.0, dropAdd = 3.0, wobble = 1.25)
        val NORMAL = SkillSpec(DriverSkill.NORMAL, wobble = 0.95)
        val GOOD = SkillSpec(DriverSkill.GOOD, blinkMin = 60.0, steerMin = 3.5, gainMax = 6.0, dropMax = 5.0, wobble = 0.6, safe = true)

        fun of(skill: DriverSkill): SkillSpec = when (skill) {
            DriverSkill.POOR -> POOR
            DriverSkill.NORMAL -> NORMAL
            DriverSkill.GOOD -> GOOD
        }
    }
}

/** 실력 글자 → 값(별칭: 서툰·보통·능숙, bad·novice, skilled·expert). 모르면 보통. */
fun skillOf(v: String?): DriverSkill = when (v?.trim()?.lowercase()) {
    "poor", "bad", "novice", "서툰" -> DriverSkill.POOR
    "good", "skilled", "expert", "능숙" -> DriverSkill.GOOD
    else -> DriverSkill.NORMAL
}

/** 프로토타입 글자('poor'·'normal'·'good'). */
val DriverSkill.code: String get() = name.lowercase()
