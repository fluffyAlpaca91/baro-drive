package com.barodrive.core.sim.scenario

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.data.M1Scenarios
import com.barodrive.core.sim.scenario.data.M2Scenarios
import com.barodrive.core.sim.scenario.data.M3Scenarios
import com.barodrive.core.sim.scenario.data.M4Scenarios
import com.barodrive.core.sim.scenario.data.M5Scenarios
import com.barodrive.core.sim.scenario.data.M6Scenarios
import com.barodrive.core.sim.scenario.data.M7Scenarios
import com.barodrive.core.sim.scenario.data.M8Scenarios
import com.barodrive.core.sim.scenario.data.RulesScenarios
import com.barodrive.core.sim.scenario.data.WeatherScenarios

/**
 * 시나리오·경로·틀 목록 (프로토타입 `BaroData.routes`·`scenarios`·`templates` — `Baro.route`·`Baro.scenario`·`Baro.template`로 등록).
 * 시뮬레이터마다 하나. [builtIn]이 m1~m8·weather·rules를 프로토타입과 같은 순서로 등록한다. 같은 id를 두 번 등록하면 오류.
 */
class ScenarioRegistry {
    private val routes = LinkedHashMap<String, RouteDef>()
    private val scenarios = LinkedHashMap<String, Entry>()
    private val templates = HashMap<Int, ScenarioTemplate>()

    /** 등록한 시나리오: 정해진 정의 · 읽을 때마다 고른 주차 종류·방향으로 만드는 정의(S6-P). */
    private sealed interface Entry {
        val id: String
        val mode: Int?
        val courseId: String?
        val placeId: String?
        val isDefault: Boolean
        fun def(parking: ParkingChoice): ScenarioDef

        class Static(val value: ScenarioDef) : Entry {
            override val id get() = value.id
            override val mode get() = value.mode
            override val courseId get() = value.courseId
            override val placeId get() = value.placeId
            override val isDefault get() = value.isDefault
            override fun def(parking: ParkingChoice) = value
        }

        class Dynamic(override val id: String, override val mode: Int?, val make: (ParkingChoice) -> ScenarioDef) : Entry {
            override val courseId: String? get() = null
            override val placeId: String? get() = null
            override val isDefault: Boolean get() = false
            override fun def(parking: ParkingChoice) = make(parking)
        }
    }

    /** `Baro.route(courseId, def)` — 코스 id 또는 'place:<장소 id>'. */
    fun registerRoute(id: String, def: RouteDef) {
        require(id.isNotEmpty()) { "등록 오류(route): 코스 id가 없어요" }
        check(id !in routes) { "중복 등록: route $id" }
        routes[id] = def
    }

    /** `Baro.scenario(def)`. */
    fun registerScenario(def: ScenarioDef) {
        require(def.id.isNotEmpty()) { "등록 오류(scenario): id가 없어요" }
        check(def.id !in scenarios) { "중복 등록: scenario ${def.id}" }
        scenarios[def.id] = Entry.Static(def)
    }

    /** 읽을 때마다 고른 주차 종류·방향으로 만드는 시나리오(m6.js S6-P의 getter). */
    fun registerDynamic(id: String, mode: Int?, make: (ParkingChoice) -> ScenarioDef) {
        check(id !in scenarios) { "중복 등록: scenario $id" }
        scenarios[id] = Entry.Dynamic(id, mode, make)
    }

    /** `Baro.template(mode, fn)`. */
    fun registerTemplate(mode: Int, template: ScenarioTemplate) {
        check(mode !in templates) { "중복 등록: template $mode" }
        templates[mode] = template
    }

    fun route(id: String?): RouteDef? = id?.let { routes[it] }

    fun template(mode: Int?): ScenarioTemplate? = mode?.let { templates[it] }

    fun hasScenario(id: String): Boolean = id in scenarios

    /** id로 시나리오(지금 고른 주차 종류·방향 [parking]으로). */
    fun scenario(id: String, parking: ParkingChoice = ParkingChoice()): ScenarioDef? = scenarios[id]?.def(parking)

    /** 등록 순서대로 모든 시나리오. */
    fun all(parking: ParkingChoice = ParkingChoice()): List<ScenarioDef> = scenarios.values.map { it.def(parking) }

    /** 그 코스의 시나리오들(장소 시나리오 빼고, 등록 순서). */
    fun forCourse(courseId: String, parking: ParkingChoice = ParkingChoice()): List<ScenarioDef> =
        scenarios.values.filter { it.courseId == courseId && it.placeId == null }.map { it.def(parking) }

    /** 그 장소의 시나리오들(등록 순서). */
    fun forPlace(placeId: String, parking: ParkingChoice = ParkingChoice()): List<ScenarioDef> =
        scenarios.values.filter { it.placeId == placeId }.map { it.def(parking) }

    companion object {
        /** 기본 데이터(index.html 순서: m1 · m2 · m3 · m4 · m5 · m6 · m7 · m8 · weather · rules). */
        fun builtIn(): ScenarioRegistry = ScenarioRegistry().apply {
            M1Scenarios.register(this)
            M2Scenarios.register(this)
            M3Scenarios.register(this)
            M4Scenarios.register(this)
            M5Scenarios.register(this)
            M6Scenarios.register(this)
            M7Scenarios.register(this)
            M8Scenarios.register(this)
            WeatherScenarios.register(this)
            RulesScenarios.register(this)
        }
    }
}

/** 틀에 넘기는 코스(데이터가 없는 코스는 id·모드만). */
data class TemplateCourse(val id: String, val name: String? = null, val mode: Int? = null)

/**
 * 틀에 넘기는 값 (player.js find의 `tpl(course, { skill, courseId, routeDef, legs, segments, generic })`).
 * [legs] = 구간이 하나면 그 다리 목록, [segments] = 모든 구간. [generic] = 기본 틀 시나리오 만들기(코스 id, 모드).
 */
class TemplateOptions(
    val skill: DriverSkill,
    /** 지금 고른 주차 종류·방향(모드 6 틀). */
    val parking: ParkingChoice,
    val courseId: String?,
    val routeDef: RouteDef?,
    val legs: List<Leg>?,
    val segments: List<List<Leg>>?,
    val generic: ((courseId: String, mode: Int) -> ScenarioDef)?,
)

/** 모드 틀(`Baro.template(mode, fn)`): 시나리오가 없는 그 모드 코스에 쓸 시나리오를 만든다. null이면 기본 틀로. */
fun interface ScenarioTemplate {
    fun make(course: TemplateCourse, opts: TemplateOptions): ScenarioDef?
}
