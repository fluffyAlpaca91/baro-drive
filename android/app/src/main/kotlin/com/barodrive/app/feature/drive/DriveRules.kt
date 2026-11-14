package com.barodrive.app.feature.drive

import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SessionController
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Courses
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Mode
import com.barodrive.core.model.NavStatus
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.Weather
import com.barodrive.core.model.WeatherBadge
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** 기어 칩 설명 (drive.js `gearText`). */
enum class DriveGearLabel { PARKED, DRIVING, STOPPED, REVERSE, NEUTRAL }

/** 깜빡이 히어로 상태 (결정-92 B): 꺼짐 · 맞게 켬 · 반대쪽 켬. */
enum class BlinkState { OFF, ON, WRONG }

/** DR-1 가운데 히어로 하나 (drive.js `heroModel`: 위가 우선). */
sealed interface DriveHero {
    /** R 후진(주차 모드 밖). */
    data object Reverse : DriveHero

    /** 회전 접근(엔진 출력 turn, 내비 회전 150m·IC 300m 창). [dist]는 10m 내림. */
    data class Turn(val dir: TurnDir, val dist: Int, val state: BlinkState, val lit: TurnDir?, val ic: Boolean, val enter: Boolean) : DriveHero

    /** 최근 8 sim초 안에 코치가 말한 항목(아이콘 + 짧은 이름). [icon]은 심볼 id('i-brake'). */
    data class Said(val icon: String, val name: String) : DriveHero

    /** 기본: 오늘 과제 1번 · 자율 '안전하게 마트까지' · 면허 '구간 2/3 · …'. [label]이 비면 라벨 줄 없음. */
    data class Task(val label: String, val text: String) : DriveHero
}

/**
 * 주행·신호 대기 화면 계산 (프로토타입 drive.js `headSub`·`heroModel`·`defaultHero`·`badgeModel`·`gearText`·`recentSay`, 설계 4.2 DR-0~DR-2,
 * 4.6.1, 결정-92). 세션 상태만 보고 그린다 — 판정은 엔진이 했다(설계 6.1).
 */
object DriveRules {
    private const val EPS = 1e-6
    private val J3_NAMES = mapOf("j3.rain" to "비 오는 날", "j3.snow" to "눈길", "j3.cold" to "언 길 조심", "j3.slip" to "미끄러운 길")

    /** 덮개 안 화면(session.js `driveScreenId` 중 덮개 몫): 회차가 없으면 DR-0, 신호 대기면 DR-2, 아니면 DR-1. */
    fun screen(s: DrivingState): DriveCoverScreen = when {
        !s.active -> DriveNotPracticing
        s.waiting -> DriveWaitCard
        else -> DriveOneLine
    }

    /** 움직이나(속도 크기 0.5km/h 이상). */
    fun moving(s: DrivingState): Boolean = s.speed?.let { abs(it) >= 0.5 } == true

    /** 기어 칩 설명(drive.js `gearText` + DR-1은 D면 늘 '주행 중' — `paintGear({ fixed })`). */
    fun gearLabel(gear: Gear?, moving: Boolean, screen: DriveCoverScreen): DriveGearLabel {
        val wait = screen == DriveWaitCard
        if (screen == DriveOneLine && gear == Gear.D) return DriveGearLabel.DRIVING
        return when (gear) {
            Gear.R -> DriveGearLabel.REVERSE
            Gear.N -> if (wait) DriveGearLabel.STOPPED else DriveGearLabel.NEUTRAL
            Gear.P -> DriveGearLabel.PARKED
            Gear.D -> if (wait || !moving) DriveGearLabel.STOPPED else DriveGearLabel.DRIVING
            null -> if (moving) DriveGearLabel.DRIVING else DriveGearLabel.STOPPED
        }
    }

    /** 목적지 이름(내비 목적지 › 준비 세션 장소 › 코스). */
    fun destName(s: DrivingState): String? {
        val prep = s.prep ?: return null
        prep.placeId?.let { Courses.place(it)?.name }?.let { return it }
        prep.navi?.name?.takeIf { it.isNotEmpty() }?.let { return it }
        return prep.courseId?.let { Courses.course(it)?.name }
    }

    /** 머리줄 글(drive.js `headSub`): '1단계 · 2회차' · '자율 · 마트까지' · '면허 · 구간 2/3'. */
    fun headSub(s: DrivingState): String {
        val mode = s.mode ?: return ""
        return when (mode) {
            5 -> "자율 · " + (destName(s)?.let { "${it}까지" } ?: "목적지까지")
            8 -> {
                val sg = s.snapshot?.nav?.segment
                "면허 · " + if (sg != null && sg.count > 1) "구간 ${sg.index}/${sg.count}" else "${s.noInMode ?: 1}회차"
            }
            else -> (Mode.ofOrNull(mode)?.short.orEmpty()) + " · ${s.noInMode ?: 1}회차"
        }
    }

    /** 지금 시각(마지막 엔진 출력 › 스냅숏). */
    private fun nowT(s: DrivingState): Double = s.output?.t ?: s.snapshot?.t ?: 0.0

    /** 최근 코치 말(drive.js `recentSay`): 마지막 코치 문장을 [SessionController.SAY_HOLD] sim초 동안. */
    fun recentSay(s: DrivingState): Utterance? {
        val say = s.lastSay ?: return null
        val t = nowT(s)
        return say.takeIf { t >= it.t - EPS && t - it.t <= SessionController.SAY_HOLD + EPS }
    }

    /** 날씨 배지(엔진 hints.badge). */
    fun badge(s: DrivingState): WeatherBadge? = s.hints.badge

    /** 깜빡이 'L'/'R'. */
    private fun blinkOf(b: Blinker?): TurnDir? = when (b) {
        Blinker.LEFT -> TurnDir.LEFT
        Blinker.RIGHT -> TurnDir.RIGHT
        else -> null
    }

    /**
     * 히어로(drive.js `heroModel`): R 후진 → 회전 접근(C2가 그 모드에서 '안 함'이 아니고 깜빡이를 읽을 때) → 최근 말 항목 → 기본.
     * [itemValue]는 이번 회차 그 항목의 전달 방식(session.itemValue).
     */
    fun hero(s: DrivingState, itemValue: (String) -> Delivery): DriveHero {
        val snap = s.snapshot
        if (snap?.gear == Gear.R && s.mode != 6) return DriveHero.Reverse
        val turn = s.turn
        val dir = turn?.dir
        if (s.active && turn != null && dir != null && turn.distance > 0 && snap?.blinker != null && itemValue("C2") != Delivery.OFF) {
            val b = turn.blinkDir ?: blinkOf(snap.blinker)
            val state = when {
                b == null -> BlinkState.OFF
                b == dir -> BlinkState.ON
                else -> BlinkState.WRONG
            }
            return DriveHero.Turn(dir, round10(turn.distance), state, b, turn.ic, turn.enter)
        }
        val say = recentSay(s)
        if (say?.item != null) return saidHero(say, s)
        return defaultHero(s)
    }

    /** 10m 내림(util.js `round10`). */
    fun round10(m: Double): Int = (floor(m / 10) * 10).toInt()

    /** 최근 말 항목 이름·아이콘(drive.js `saidHero`). */
    private fun saidHero(say: Utterance, s: DrivingState): DriveHero.Said {
        val def = say.item?.let { runCatching { BaroEngine.itemDef(it) }.getOrNull() }
        var name = def?.short.orEmpty()
        val icon = def?.icon ?: "i-voice"
        when (say.item) {
            "E1" -> {
                val limit = s.snapshot?.nav?.limit
                val weather = s.prep?.weather?.let { w -> Weather(snow = w.snow, rain = w.rain, severe = w.severe).initial }
                val eff = limit?.let { l -> weather?.let { BaroEngine.effLimit(l.toDouble(), it) } ?: l }
                name = when {
                    limit != null && limit <= 30 -> "$limit 구역"
                    eff != null -> "제한속도 $eff"
                    else -> "제한속도"
                }
            }
            "E2" -> name = "어린이보호구역"
            "J3" -> name = J3_NAMES[say.key] ?: "날씨"
            "C1", "C2", "C5" -> name = "깜빡이"
        }
        return DriveHero.Said(icon, name.ifEmpty { "코치" })
    }

    /** 기본 히어로(drive.js `defaultHero`): 엔진 hints.hero › 자율 › 면허 › 오늘 과제 1번 › '○○ 중이에요'. */
    fun defaultHero(s: DrivingState): DriveHero.Task {
        s.hints.hero?.let { return DriveHero.Task("", it) }
        return when (s.mode) {
            5 -> DriveHero.Task("자율 모드", "안전하게 ${destName(s) ?: "목적지"}까지")
            8 -> {
                val sg = s.snapshot?.nav?.segment
                val letter = s.prep?.courseId?.let { Courses.course(it)?.letter }?.takeIf { it.isNotEmpty() }
                DriveHero.Task(
                    "운전면허 연습",
                    (if (sg != null && sg.count > 1) "구간 ${sg.index}/${sg.count} · " else "") + "시험장 주변 " + (letter?.let { "$it 코스" } ?: "코스"),
                )
            }
            else -> {
                val t = s.today.firstOrNull()
                if (t != null) DriveHero.Task("오늘 과제", t.label)
                else DriveHero.Task("오늘 연습", (s.mode?.let { Mode.ofOrNull(it)?.title } ?: "연습") + " 중이에요")
            }
        }
    }

    /**
     * DR-3 남은 거리 글(drive.js `remainText`): 내비 안내 중이면 '목적지까지 1.2km 남았어요'(1km 아래는 10m 내림, 10m 이상),
     * 아니면 '여기서 마치면 지금까지 달린 것으로 돌아봐요'.
     */
    fun remainText(s: DrivingState): String {
        val nav = s.snapshot?.nav
        if (nav == null || nav.status != NavStatus.ROUTE_DRIVE) return "여기서 마치면 지금까지 달린 것으로 돌아봐요"
        val m = nav.remain ?: 0.0
        val what = if (m >= 1000) String.format(java.util.Locale.ROOT, "%.1f", (m / 100).roundToInt() / 10.0) + "km" else "${max(10, round10(m))}m"
        return "목적지까지 $what 남았어요"
    }
}
