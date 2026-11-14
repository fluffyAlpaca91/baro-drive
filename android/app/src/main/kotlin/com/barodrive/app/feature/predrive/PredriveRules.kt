package com.barodrive.app.feature.predrive

import com.barodrive.app.feature.course.CourseRules
import com.barodrive.core.model.Courses
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.Mode
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.VehicleSnapshot

/** 운전석 벨트 줄 (predrive.js `paintChecks`). */
enum class BeltStatus { UNKNOWN, ON, OFF }

/** 사이드브레이크 줄: 숨김(PB1 '안 함') · 풀었어요(확인함) · 풀었나요?([풀었어요] 버튼) · 채워져 있어요 · 풀려 있어요. */
enum class HandbrakeStatus { HIDDEN, CONFIRMED, ASK, ON, OFF }

/** 조수석 가족 줄 (predrive.js `paintPassenger`). */
enum class PassengerStatus {
    /** 착석 신호를 못 읽음. */
    UNKNOWN,

    /** 탑승 · L1 '바로' · 아직 말 안 함('출발 전에 코치가 말해요'). */
    ABOARD_PENDING,

    /** 탑승 · L1 '바로' · 말함('코치가 역할을 알려 드렸어요'). */
    ABOARD_SAID,

    /** 탑승 · L1이 '바로'가 아님('동승자 안내는 하지 않아요'). */
    ABOARD_QUIET,

    /** 비어 있음. */
    EMPTY,
}

/** 코스 카드 값 (predrive.js `courseInfo`). */
data class PredriveCourse(
    /** '연습 코스' · '내 코스' · '목적지'. */
    val kind: CourseKind,
    val letter: String,
    val name: String,
    val routeOption: String,
    val km: Double?,
    val min: Int?,
    val congestion: Int?,
)

/** 코스 카드 머리 글. */
enum class CourseKind { COURSE, MINE, DESTINATION }

/** 신호 확인 줄 (demo.js `signalModel`, 설계 7.8). [title]·[text]는 프로토타입 글 그대로. */
data class SignalLine(val ok: Boolean, val off: List<String>, val title: String, val text: String)

/** 깜빡이를 못 읽어 시작할 수 없는 모드 (demo.js `startBlock`). */
data class StartBlock(val title: String, val sub: String)

/**
 * PR-1 계산 (프로토타입 predrive.js `subtitleFor`·`courseInfo`·점검·조수석, demo.js `signalModel`·`startBlock`).
 * 날씨 줄은 feature/weather(PredriveWeatherCardRoute·WeatherRules)가 맡는다. 순수 함수 — 스냅숏·준비 세션·전달 방식을 받아 값만 낸다.
 */
object PredriveRules {
    /** 앱 바 부제 '1단계 · 차량 감 익히기 · 2회차'(predrive.js `subtitleFor`). */
    fun subtitle(prep: PrepSession): String {
        val label = Mode.ofOrNull(prep.mode)?.label ?: "${prep.mode}번 모드"
        return "$label · ${prep.noInMode ?: 1}회차"
    }

    /** 벨트 줄. */
    fun belt(s: VehicleSnapshot?): BeltStatus = when (s?.beltDriver) {
        null -> BeltStatus.UNKNOWN
        true -> BeltStatus.ON
        false -> BeltStatus.OFF
    }

    /** 사이드브레이크 줄(설계 4.2 PR-1, 결정-84): PB1이 그 모드에서 '안 함'이면 숨김. 못 읽으면 묻고 [풀었어요]. */
    fun handbrake(s: VehicleSnapshot?, prep: PrepSession, pb1: Delivery): HandbrakeStatus = when {
        pb1 == Delivery.OFF -> HandbrakeStatus.HIDDEN
        s?.handbrake == null -> if (prep.checks.pbConfirmed) HandbrakeStatus.CONFIRMED else HandbrakeStatus.ASK
        s.handbrake == true -> HandbrakeStatus.ON
        else -> HandbrakeStatus.OFF
    }

    /** 조수석 가족 줄. */
    fun passenger(s: VehicleSnapshot?, prep: PrepSession, l1: Delivery): PassengerStatus = when (s?.passenger) {
        null -> PassengerStatus.UNKNOWN
        false -> PassengerStatus.EMPTY
        true -> when {
            l1 != Delivery.NOW -> PassengerStatus.ABOARD_QUIET
            prep.lines.l1Said -> PassengerStatus.ABOARD_SAID
            else -> PassengerStatus.ABOARD_PENDING
        }
    }

    /** 조수석 카드 아래 L2 안내('3·4단계는 가족이 옆에 타면 더 좋아요'): 조수석이 비었고 3·4단계. */
    fun l2(s: VehicleSnapshot?, mode: Int): Boolean = s?.passenger == false && (mode == 3 || mode == 4)

    /**
     * 코스 카드(predrive.js `courseInfo`): CR-1과 같은 계산(내 코스 글자·혼잡 — 시연 패널 동네·혼잡 시드)을 먼저, 없으면 코스·장소 데이터. 코스·장소가 없으면 null
     * ('목적지를 내비에서 직접 골라요').
     */
    fun course(prep: PrepSession, profile: Profile, tripOk: Boolean = true, town: String = "home", congestionSeed: Int? = null): PredriveCourse? {
        val item = runCatching { CourseRules.rank(prep.mode, profile, town = town, congestionSeed = congestionSeed, tripOk = tripOk) }.getOrNull()?.list?.firstOrNull { x ->
            (prep.courseId != null && x.courseId == prep.courseId) || (prep.courseId == null && prep.placeId != null && x.placeId == prep.placeId) ||
                (prep.courseId == null && prep.placeId == null && prep.navi != null && x.navi?.key == prep.navi?.key)
        }
        prep.courseId?.let { id ->
            val c = Courses.course(id)
            return PredriveCourse(
                kind = CourseKind.COURSE,
                letter = item?.letter ?: c?.letter.orEmpty(),
                name = item?.name ?: c?.name ?: id,
                routeOption = c?.routeOption?.takeIf { it.isNotEmpty() } ?: ROUTE_DEFAULT,
                km = item?.km ?: c?.km,
                min = item?.min ?: c?.min,
                congestion = if (item != null) item.congestion else c?.congestion,
            )
        }
        prep.placeId?.let { id ->
            val pl = Courses.place(id)
            return PredriveCourse(
                kind = if (item != null) CourseKind.MINE else CourseKind.DESTINATION,
                letter = item?.letter.orEmpty(),
                name = pl?.name ?: id,
                routeOption = ROUTE_DEFAULT,
                km = item?.km ?: pl?.km,
                min = item?.min ?: pl?.min,
                congestion = if (item != null) item.congestion else pl?.congestion,
            )
        }
        // 진짜 차 내비에서 가져온 곳: 이름만(거리·시간·혼잡은 내비가 안내할 때 안다).
        prep.navi?.let { n ->
            return PredriveCourse(
                kind = if (item != null) CourseKind.MINE else CourseKind.DESTINATION,
                letter = item?.letter.orEmpty(),
                name = item?.name ?: n.name,
                routeOption = ROUTE_DEFAULT,
                km = null,
                min = null,
                congestion = null,
            )
        }
        return null
    }

    /** 경로 옵션 기본(목업 s03 '편안한 길'). */
    const val ROUTE_DEFAULT = "편안한 길"

    // ---------------------------------------------------------------- 신호 확인 줄(demo.js, 설계 7.8)

    /** 신호 이름 · 빠지는 과제 · 대신 하는 것(demo.js `SIGNALS`, 설계 7.8 표). 순서는 caps 키 순서. */
    private class Sig(val key: String, val name: String, val drop: String? = null, val note: String? = null)

    private val SIGNALS = listOf(
        Sig("speed", "차속", drop = "출발·정지·속도"),
        Sig("gear", "기어", note = "10초 서 있으면 정차로 봐요"),
        Sig("blinker", "깜빡이", drop = "깜빡이"),
        Sig("steer", "핸들", drop = "핸들"),
        Sig("nav", "내비", drop = "회전 안내·제한속도", note = "목적지는 내비에 직접 넣어요"),
        Sig("passenger", "좌석", note = "조수석은 직접 알려 주세요"),
        Sig("beltDriver", "벨트", note = "벨트는 직접 확인해요"),
        Sig("handbrake", "사이드브레이크", note = "사이드브레이크는 말로 물어봐요"),
        Sig("wiper", "와이퍼", note = "비 오는 날은 직접 골라요"),
        Sig("outsideTemp", "바깥 온도", note = "언 길 안내를 빼요"),
        Sig("reverseSpeed", "뒤로 가는 속도", note = "뒤로 밀림은 기록하지 않아요"),
        Sig("trip", "코스 혼잡도", note = "코스는 가까운 순이에요"),
    )

    private fun SignalCaps.readable(key: String): Boolean = when (key) {
        "speed" -> speed
        "gear" -> gear
        "blinker" -> blinker
        "steer" -> steer
        "nav" -> nav
        "passenger" -> passenger
        "beltDriver" -> beltDriver
        "handbrake" -> handbrake
        "wiper" -> wiper
        "outsideTemp" -> outsideTemp
        "reverseSpeed" -> reverseSpeed
        else -> trip
    }

    /** 신호 확인 줄(demo.js `signalModel`): 모두 읽히면 '차 신호 확인 끝', 아니면 못 읽는 신호와 빠지는 과제·대신 하는 것. */
    fun signals(mode: Int, caps: SignalCaps): SignalLine {
        val off = SIGNALS.filter { !caps.readable(it.key) }
        if (off.isEmpty()) return SignalLine(ok = true, off = emptyList(), title = "차 신호 확인 끝", text = "깜빡이·브레이크·핸들·좌석")
        val drops = ArrayList<String>()
        val notes = ArrayList<String>()
        for (s in off) {
            var drop = s.drop
            var note = s.note
            if (mode == 6) { // 실제 주차 안내: 주행 과제가 없다
                drop = null
                when (s.key) {
                    "speed" -> note = "'천천히' 안내를 빼요"
                    "steer" -> note = "핸들 단계는 화면을 눌러 넘겨요"
                    "gear" -> note = "기어 단계는 화면을 눌러 넘겨요"
                }
            } else if (mode == 7 && s.key == "steer") {
                note = "바퀴 방향 단계는 [했어요]로 넘겨요"
            }
            if (s.key == "reverseSpeed" && mode != 7) note = null // 뒤로 밀림은 언덕에서만 본다
            if (s.key == "wiper" && mode == 6) note = null
            if (drop != null && drop !in drops) drops += drop
            if (note != null && note !in notes) notes += note
        }
        val parts = ArrayList<String>()
        if (drops.isNotEmpty()) parts += "이번엔 " + drops.joinToString("·") + if (mode == 5) " 항목을 점수에서 빼요" else " 과제를 빼요"
        return SignalLine(
            ok = false,
            off = off.map { it.key },
            title = "못 읽는 신호: " + off.joinToString("·") { it.name },
            text = (parts + notes).joinToString(" · "),
        )
    }

    /** 깜빡이를 못 읽으면 모드 2·8은 시작하지 않는다(demo.js `startBlock`, 설계 7.8). */
    fun startBlock(mode: Int, caps: SignalCaps): StartBlock? {
        if (mode != 2 && mode != 8 || caps.blinker) return null
        val name = if (mode == 8) "운전면허 연습" else "${mode}단계"
        return StartBlock(
            title = "깜빡이 신호를 못 읽어 $name" + if (mode == 8) "을 시작할 수 없어요" else "를 시작할 수 없어요",
            sub = "깜빡이 과제가 중심인 연습이라 깜빡이 신호가 꼭 필요해요. 다른 연습을 골라 주세요.",
        )
    }
}
