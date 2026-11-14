package com.barodrive.core.sim.scenario.data

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.DriverSkill.GOOD
import com.barodrive.core.model.DriverSkill.NORMAL
import com.barodrive.core.model.DriverSkill.POOR
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SessionInput
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.DriveKind
import com.barodrive.core.sim.scenario.ParkingChoice
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioEvent
import com.barodrive.core.sim.scenario.ScenarioRegistry
import com.barodrive.core.sim.scenario.ScenarioTemplate
import com.barodrive.core.sim.scenario.StartState

/**
 * 실제 주차 시나리오 (프로토타입 data/scenarios/m6.js, 설계 7.7 S6-P, 8.2 c6-A·B, 5.7, 표 A-5).
 *
 * S6-P는 경로가 없다(사건으로만 움직임, 결정사항 E T1b-16). 종류·방향은 PK-1에서 고른 것([com.barodrive.core.sim.BaroSim.chooseParking])을
 * 따른다 — 등록한 'S6-P'는 읽을 때마다 지금 고른 종류·방향의 사건을 돌려준다. 모드 6 코스(c6-A·c6-B)는 모드 6 틀이 같은 S6-P를 준다
 * (코스는 '어디서 연습하나'일 뿐 내비 경로를 넣지 않는다).
 * 보통: 단계 신호가 표 A-5 순서대로 나온다 · 후진 9km/h 한 번(PK2) · 끝 바퀴 8°(PK3) · 사이드브레이크 안 채움(PB2).
 * 서툰: 핸들을 끝까지 못 돌려(26°) 그 단계는 [다음 단계]로 넘김, 후진이 빠른 때가 한 번 더, 끝 바퀴 15°. 능숙: 모두 맞음.
 * 장면 '1단계' ~ 'n단계' = 그 단계가 시작된 직후.
 */
internal object M6Scenarios {
    private val NAMES = mapOf("parallel" to "평행", "back" to "후방(후진)", "front" to "전방")
    private const val FULL = 33 // 끝까지 돌린 앞바퀴각(세 차종 모두 최대각 × 0.9 넘음)
    private const val POOR_TURN = 26 // 서툰: 끝까지 못 돌림(아반떼 30.6° 아래) → 그 단계는 화면 누름
    private const val END_WHEEL_POOR = 15
    private const val END_WHEEL_NORMAL = 8
    private val NP = listOf(POOR, NORMAL)
    private val NG = listOf(NORMAL, GOOD)
    private val P_ = listOf(POOR)
    private val N_ = listOf(NORMAL)
    private val G_ = listOf(GOOD)

    private fun e(t: Number, act: Act, v: Any? = null, skills: List<DriverSkill>? = null, speed: Number? = null, dur: Number? = null, handbrake: Boolean? = null) =
        ev(t = t, act = act, v = v, skills = skills, speed = speed, dur = dur, handbrake = handbrake)

    /** [다음 단계] 대신 누름 — 그 단계일 때만 넘긴다(사람이 먼저 눌렀으면 엔진이 무시). */
    private fun press(t: Number, step: Int, skills: List<DriverSkill>? = null) = e(t, Act.INPUT, SessionInput.PkNext(step), skills)
    private fun step(n: Int, t: Number) = mark(name = "${n}단계", t = t)
    private fun gear(t: Number, g: Gear, sk: List<DriverSkill>? = null) = e(t, Act.GEAR, g, sk)
    private fun brake(t: Number, on: Boolean, sk: List<DriverSkill>? = null) = e(t, Act.BRAKE, on, sk)
    private fun steer(t: Number, deg: Int, sk: List<DriverSkill>? = null) = e(t, Act.STEER, deg, sk)
    private fun fast(t: Number, dur: Number, sk: List<DriverSkill>? = null) = e(t, Act.OVER, skills = sk, speed = -9, dur = dur) // 후진 9km/h(능숙은 끔)
    private fun park(t: Number) = listOf(e(t, Act.PARK, skills = NP, handbrake = false), e(t, Act.PARK, skills = G_))

    /** s = 고른 칸 쪽 핸들 부호(오른쪽 +1, 왼쪽 −1). 시각은 시나리오 sim초. */
    private fun parallelEvents(s: Int): List<ScenarioEvent> = listOf(
        gear(0.5, Gear.D), step(1, 1.0),
        press(9.0, 1), step(2, 9.3),
        brake(11.0, false), // 앞차 옆까지 기어감
        brake(19.0, true), step(3, 21.5), // 멈춤 1초 → 3단계
        gear(28.0, Gear.R), step(4, 28.3),
        steer(33.0, s * FULL, NG), steer(33.0, s * POOR_TURN, P_), press(37.0, 4, P_), step(5, 37.3),
        brake(35.0, false), fast(35.5, 2, P_), // 비스듬히 들어감(서툰은 이때도 빠름)
        brake(42.0, true), steer(43.5, 0), step(6, 47.0), // 멈추고 핸들 풂 → 6단계
        brake(49.0, false), fast(54.0, 3, NP), // 후진 9km/h → '천천히'
        steer(63.0, -s * FULL), step(7, 64.3), // 반대쪽 끝까지 → 7단계
        brake(70.0, true), step(8, 72.2), // 길과 나란 · 멈춤 → 8단계
        steer(80.0, -s * END_WHEEL_NORMAL, N_), steer(80.0, -s * END_WHEEL_POOR, P_), steer(80.0, 0, G_),
    ) + park(84.0)

    private fun backEvents(s: Int): List<ScenarioEvent> = listOf(
        gear(0.5, Gear.D), step(1, 1.0),
        press(9.0, 1), step(2, 9.3),
        brake(11.0, false), // 칸 반대쪽으로 붙어 지나감
        brake(20.0, true), step(3, 22.4),
        gear(29.0, Gear.R), step(4, 29.3),
        steer(34.0, s * FULL, NG), steer(34.0, s * POOR_TURN, P_), press(38.0, 4, P_), step(5, 38.3),
        brake(36.0, false), fast(36.5, 2, P_),
        steer(44.0, 0), step(6, 47.5), // 칸과 나란 · 핸들 풂(뒤로 가면서) → 6단계
        fast(54.0, 2, NP),
        steer(59.0, s * END_WHEEL_NORMAL, N_), steer(59.0, s * END_WHEEL_POOR, P_), // 바로잡으며 조금 꺾음
        brake(63.0, true), step(7, 65.2), // 멈춤턱 앞 멈춤 → 7단계
    ) + park(73.0)

    private fun frontEvents(s: Int): List<ScenarioEvent> = listOf(
        gear(0.5, Gear.D), step(1, 1.0),
        press(9.0, 1), step(2, 9.3),
        brake(11.0, false), // 칸 반대쪽으로 붙어 다가감
        steer(19.0, s * FULL, NG), steer(19.0, s * POOR_TURN, P_), press(22.0, 2, P_), step(3, 22.3),
        // 보통·서툰: 비뚤게 들어가 잠깐 뒤로 뺐다가(9km/h → '천천히') 다시 들어감
        brake(25.0, true, NP), gear(27.0, Gear.R, NP), brake(27.5, false, NP), fast(28.0, 1.5, NP),
        brake(32.0, true, NP), gear(34.0, Gear.D, NP), brake(34.5, false, NP),
        steer(37.0, 0), step(4, 40.5), // 칸과 나란 · 핸들 풂 → 4단계
        brake(47.0, true), step(5, 49.4), // 멈춤턱 앞 멈춤 → 5단계
        steer(51.0, s * END_WHEEL_NORMAL, N_), steer(51.0, s * END_WHEEL_POOR, P_),
    ) + park(57.0)

    /** '평행 · 오른쪽 칸'. */
    fun title(c: ParkingChoice): String = NAMES.getValue(c.type) + " · " + (if (c.side == "L") "왼쪽" else "오른쪽") + " 칸"

    /** 그 종류·방향의 S6-P 정의. courseId가 없어 내비 경로를 넣지 않는다. */
    fun scenario(choice: ParkingChoice): ScenarioDef {
        val c = ParkingChoice.norm(choice.type, choice.side)
        val s = if (c.side == "L") -1 else 1
        return ScenarioDef(
            id = "S6-P", title = "주차 연습 · 실제 주차 안내(" + title(c) + ")", mode = 6,
            drive = DriveKind.EVENTS, parking = c,
            start = StartState(gear = Gear.P, speed = 0.0, steer = 0.0, blinker = com.barodrive.core.model.Blinker.OFF, handbrake = false),
            events = when (c.type) {
                "back" -> backEvents(s)
                "front" -> frontEvents(s)
                else -> parallelEvents(s)
            },
            expect = listOf("PK1.ok", "PK2.fail", "PK3.fail", "PB2.fail"),
        )
    }

    fun register(reg: ScenarioRegistry) {
        reg.registerDynamic("S6-P", 6) { scenario(it) }
        // 모드 6 코스(c6-A·c6-B, 내 코스)는 S6-P로(설계 8.2)
        reg.registerTemplate(6, ScenarioTemplate { _, opts -> scenario(opts.parking) })
    }
}
