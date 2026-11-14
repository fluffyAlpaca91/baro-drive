package com.barodrive.core.engine

import com.barodrive.core.engine.content.LineBook
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.Detectors
import com.barodrive.core.engine.review.round2
import com.barodrive.core.model.ArrivedEvent
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.BlockedUtterance
import com.barodrive.core.model.BrakeEndEvent
import com.barodrive.core.model.Card
import com.barodrive.core.model.CautionItem
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Gear
import com.barodrive.core.model.GearEvent
import com.barodrive.core.model.Hints
import com.barodrive.core.model.InputEvent
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.NavStatus
import com.barodrive.core.model.RerouteEvent
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.StartEvent
import com.barodrive.core.model.SteerTurn
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.StraightWindow
import com.barodrive.core.model.TbtItem
import com.barodrive.core.model.TrackerEvent
import com.barodrive.core.model.TurnApproach
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.TurnPassed
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WaitEndEvent
import com.barodrive.core.model.WaitStartEvent
import com.barodrive.core.model.WaypointEvent
import com.barodrive.core.model.ZoneEnter
import com.barodrive.core.model.ZoneExit

/*
 * 엔진 단위 시험 도우미 (프로토타입 tests/lib.js의 snap·drive·said·blocked·ramp·hold와 t1c_engine.test.js의 recorder·script·시험 대사).
 */

/** 0.1초 걸음 반올림(JS round1). */
fun r1(x: Double): Double = round1(x)

/** lib.js 기본 내비: 안내 없음. */
val NAV0 = NavSnapshot(status = NavStatus.INACTIVE, heading = 0.0)

/** 내비 값 (lib.js `snap({ nav: {...} })`의 한 단계 합치기 — 안 준 칸은 [NAV0]). */
fun nav(
    tbt: List<TbtItem> = emptyList(),
    limit: Int? = null,
    status: NavStatus = NavStatus.ROUTE_DRIVE,
    voice: Boolean = false,
    congested: Boolean = false,
    inSchoolZone: Boolean = false,
    heading: Double? = 0.0,
    cautions: List<CautionItem> = emptyList(),
    events: List<NavEvent> = emptyList(),
): NavSnapshot = NavSnapshot(
    status = status, tbt = tbt, limit = limit, cautions = cautions, inSchoolZone = inSchoolZone, congested = congested, voice = voice,
    heading = heading, events = events,
)

/** TBT 회전 하나. */
fun tbt(id: String, type: String, distance: Double, dir: TurnDir? = null, ic: Boolean = false) =
    TbtItem(id = id, type = type, distance = distance, dir = dir, ic = ic)

/** lib.js `snap(덮어쓸 값)`: 세워 둔 차(P, 0km/h, 벨트 맴, 낮, 내비 안내 없음). */
fun snap(
    t: Double = 0.0,
    gear: Gear? = Gear.P,
    speed: Double? = 0.0,
    brake: Boolean? = false,
    steer: Double? = 0.0,
    blinker: Blinker? = Blinker.OFF,
    beltDriver: Boolean? = true,
    passenger: Boolean? = false,
    handbrake: Boolean? = false,
    wiper: Boolean? = false,
    lowBeam: Boolean? = false,
    night: Boolean? = false,
    outsideTemp: Double? = 15.0,
    tripDist: Double? = 0.0,
    nav: NavSnapshot? = NAV0,
    skip: Boolean = false,
): VehicleSnapshot = VehicleSnapshot(
    t = t, gear = gear, speed = speed, brake = brake, steer = steer, blinker = blinker, beltDriver = beltDriver, passenger = passenger,
    handbrake = handbrake, wiper = wiper, lowBeam = lowBeam, night = night, outsideTemp = outsideTemp, tripDist = tripDist, nav = nav, skip = skip,
)

/**
 * lib.js `ramp(from, to, 초, 기본값)`: 0.1초 간격 속도 변화(시작 점은 빼고 끝 점은 넣음). [t0] = 시작 시각.
 * 기어는 [tpl] 것(기본 D), 브레이크는 따로 안 주면 감속일 때 true.
 */
fun ramp(
    from: Double,
    to: Double,
    sec: Double,
    t0: Double = 0.0,
    brake: Boolean? = to < from,
    tpl: VehicleSnapshot = snap(gear = Gear.D),
): List<VehicleSnapshot> {
    val n = maxOf(1, Math.round(sec / 0.1).toInt())
    return (1..n).map { i -> tpl.copy(t = r1(t0 + i * 0.1), speed = round2(from + (to - from) * i / n), brake = brake) }
}

/** lib.js `hold(속도, 초, 기본값)`: 같은 속도(브레이크 false). */
fun hold(speed: Double, sec: Double, t0: Double = 0.0, brake: Boolean? = false, tpl: VehicleSnapshot = snap(gear = Gear.D)) =
    ramp(speed, speed, sec, t0, brake, tpl)

/** t1c `tl(t0, t1, fn, base)`: t0 다음 0.1초부터 t1까지(포함). 기본은 기어 D, 30km/h. [fn]이 그 시각의 스냅숏을 고친다. */
fun tl(t0: Double, t1: Double, fn: (Double, VehicleSnapshot) -> VehicleSnapshot = { _, s -> s }): List<VehicleSnapshot> {
    val n = Math.round((t1 - t0) * 10).toInt()
    return (1..n).map { i ->
        val t = Math.round(t0 * 10 + i) / 10.0
        fn(t, snap(t = t, gear = Gear.D, speed = 30.0))
    }
}

/** lib.js `drive` 결과. [cards]는 겹친 것 뺌(id로), [hints]는 마지막 값. */
class Drive(
    val say: List<Utterance>,
    val blocked: List<BlockedUtterance>,
    val events: List<JudgeEvent>,
    val cards: List<Card>,
    val hints: Hints,
    val steps: List<StepOutput>,
)

/** lib.js `drive(session, 스냅숏들)`: 차례로 step(입력이면 input). */
fun drive(session: CoachSession, items: List<Any>): Drive {
    val steps = items.map {
        when (it) {
            is VehicleSnapshot -> session.step(it)
            is SessionInput -> session.input(it)
            else -> error("스냅숏이나 입력이 아님: $it")
        }
    }
    val seen = HashSet<String>()
    val cards = ArrayList<Card>()
    for (o in steps) for (c in o.cards) if (seen.add(c.id ?: c.toString())) cards += c
    return Drive(
        say = steps.flatMap { it.say }, blocked = steps.flatMap { it.blocked }, events = steps.flatMap { it.events }, cards = cards,
        hints = steps.lastOrNull()?.hints ?: Hints(), steps = steps,
    )
}

private fun keyMatch(k: String, key: Any): Boolean = when (key) {
    is Regex -> key.containsMatchIn(k)
    is String -> if (key.endsWith(".*")) k.startsWith(key.dropLast(1)) else k == key
    else -> false
}

/** lib.js `said(out, key)`: key는 글자('c2.ok'), 'c2.*'(앞부분), 정규식. */
fun said(out: Drive, key: Any): Boolean = out.say.any { keyMatch(it.key, key) }

/** lib.js `blocked(out, key, reason?)`. */
fun blocked(out: Drive, key: Any, reason: BlockReason? = null): Boolean =
    out.blocked.any { keyMatch(it.utterance.key, key) && (reason == null || it.reason == reason) }

/** 판정 고르기 (t2a `evOf`). */
fun List<JudgeEvent>.of(item: String, result: String? = null): List<JudgeEvent> =
    filter { it.item == item && (result == null || it.result.code == result) }

/** 말한 시각들 (t1c `sayAt`). */
fun Drive.sayAt(key: String): List<Double> = say.filter { it.key == key }.map { it.t }

/** 막힌 말 (t1c `blockedOf`). */
fun Drive.blockedOf(key: String): List<BlockedUtterance> = blocked.filter { it.utterance.key == key }

/** t1c 시험용 대사(Baro.lines로 등록하던 것). 앱 대사 표에 더해 쓴다. */
val TEST_LINES: LineBook = Lines.book + LineBook.of(
    mapOf(
        "t1c.safety" to lines("조심해.", "조심해!", "조심하자."),
        "t1c.pre" to lines("미리 켜.", "미리 켜자!", "미리 켜 볼까?"),
        "t1c.praise" to lines("잘했네.", "잘했다!", "좋았어."),
        "t1c.praise2" to lines("오, 좋네.", "최고야!", "아주 좋아."),
        "t1c.fix" to lines("살살 해.", "살살!", "살살 해 보자."),
        "t1c.fix2" to lines("천천히.", "천천히!", "천천히 가자."),
        "t1c.info" to lines("벨트 매.", "벨트!", "벨트 매자."),
        "t1c.info2" to lines("확인해.", "확인!", "확인해 보자."),
        "t1c.rot" to mapOf(Coach.SIB to listOf("하나.", "둘."), Coach.MOM to listOf("하나!", "둘!"), Coach.DAD to listOf("첫째 문장.", "둘째 문장.")),
        "t1c.hon" to lines("오, 웬일. 잘했네.", "어머 잘했다{ 호칭}! 딱 좋았어!", "{호칭}, 오늘 고생 많았어."),
        "t1c.hon2" to lines("됐다.", "오늘 끝! {호칭}, 고생했어.", "그래, {호칭}!"),
        "t1c.vars" to lines("{N}미터 전. {dir}으로 가는 길이야.", "{wx} {L}까지야!", "{N}미터 전에 켰어."),
    ),
)

private fun lines(sib: String, mom: String, dad: String) = mapOf(Coach.SIB to listOf(sib), Coach.MOM to listOf(mom), Coach.DAD to listOf(dad))

/** 시험 세션 (t1c `sess`): 등록된 검출기 대신 [dets]만, 시험 대사 포함. */
fun sess(cfg: SessionConfig = SessionConfig(mode = 1), dets: List<DetectorDef> = emptyList()): CoachSession =
    CoachEngine().createSession(cfg, dets, TEST_LINES)

/** 등록된 검출기 하나(id로). */
fun det(id: String): DetectorDef = Detectors.all.firstOrNull { it.id == id } ?: error("검출기 없음: $id")

/** 모든 추적기 사건을 받아 두는 검출기 (t1c `recorder`). */
class Recorder(id: String = "t1c.rec") {
    val log = ArrayList<Pair<String, TrackerEvent>>()
    val def = DetectorDef(id = id) { ctx ->
        fun <E : TrackerEvent> rec(name: String, cls: kotlin.reflect.KClass<E>) = ctx.on(cls) { log += name to it }
        rec("gear", GearEvent::class)
        rec("start", StartEvent::class)
        rec("brakeEnd", BrakeEndEvent::class)
        rec("waitStart", WaitStartEvent::class)
        rec("waitEnd", WaitEndEvent::class)
        rec("turnApproach", TurnApproach::class)
        rec("turnPassed", TurnPassed::class)
        rec("steerTurn", SteerTurn::class)
        rec("straightWindow", StraightWindow::class)
        rec("zoneEnter", ZoneEnter::class)
        rec("zoneExit", ZoneExit::class)
        rec("reroute", RerouteEvent::class)
        rec("arrived", ArrivedEvent::class)
        rec("waypoint", WaypointEvent::class)
        rec("input", InputEvent::class)
        DetectorInstance.NONE
    }

    @Suppress("UNCHECKED_CAST")
    fun <E : TrackerEvent> of(name: String): List<E> = log.filter { it.first == name }.map { it.second as E }
}

/** 정한 시각에 일을 하는 검출기 (t1c `script`): [at]초 이후 첫 스냅숏에서 [fn]을 한 번 부른다. */
class Action(val at: Double, val fn: (DetectorContext, VehicleSnapshot) -> Unit)

fun script(actions: List<Action>, id: String = "t1c.script", items: List<String> = emptyList(), modes: List<Int>? = null): DetectorDef =
    DetectorDef(id = id, items = items, modes = modes) { ctx ->
        val done = BooleanArray(actions.size)
        object : DetectorInstance {
            override fun step(snap: VehicleSnapshot) {
                actions.forEachIndexed { i, a ->
                    if (done[i] || snap.t < a.at - 1e-9) return@forEachIndexed
                    done[i] = true
                    a.fn(ctx, snap)
                }
            }
        }
    }

/** 스크립트 한 줄: [at]초에 말 후보. */
fun sayAction(at: Double, key: String, item: String?, category: com.barodrive.core.model.Category? = null, f: SayOpts = SayOpts()) =
    Action(at) { ctx, _ ->
        ctx.say(
            key, item = item, category = category, pairWith = f.pairWith, expiresIn = f.expiresIn, alive = f.alive, cardable = f.cardable,
            card = f.card, answer = f.answer,
        )
    }

/** [sayAction]의 덜 쓰는 값. */
class SayOpts(
    val pairWith: String? = null,
    val expiresIn: Double? = null,
    val alive: (() -> Boolean)? = null,
    val cardable: Boolean = false,
    val card: com.barodrive.core.engine.detect.CardSpec? = null,
    val answer: Boolean = false,
)

/**
 * 0.1초씩 스냅숏을 쌓는 도구 (t2a·t3·t10의 `seq`). [defaults]가 그 시각의 기본 스냅숏(시험 파일마다 기본값이 다름),
 * 각 add의 함수가 (시각, 번째, 기본)으로 고친다 — JS `Object.assign(기본, base, fn(t, i))` 순서.
 */
class SnapSeq(private val defaults: (Double) -> VehicleSnapshot) {
    val list = ArrayList<VehicleSnapshot>()
    var t = 0.0
        private set

    /** 마지막 스냅숏 속도. */
    val v: Double? get() = list.lastOrNull()?.speed

    fun add(n: Int, fn: (Double, Int, VehicleSnapshot) -> VehicleSnapshot = { _, _, s -> s }): SnapSeq {
        for (i in 0 until n) {
            t = r1(t + 0.1)
            list += fn(t, i, defaults(t))
        }
        return this
    }

    /** [s]초 동안. */
    fun sec(s: Double, fn: (Double, Int, VehicleSnapshot) -> VehicleSnapshot = { _, _, x -> x }) = add(Math.round(s * 10).toInt(), fn)

    /** 속도를 [target]까지 부드럽게(브레이크 없이 0.1초에 0.5km/h). */
    fun to(target: Double, fn: (Double, Int, VehicleSnapshot) -> VehicleSnapshot = { _, _, x -> x }): SnapSeq {
        val v0 = v ?: target
        val n = Math.ceil(kotlin.math.abs(target - v0) / 0.5 - 1e-9).toInt()
        if (n > 0) add(n) { tt, i, s -> fn(tt, i, s.copy(speed = v0 + (target - v0) * (i + 1) / n, brake = false)) }
        return this
    }

    /** [end]초까지 한 표본씩(끝 시각 포함, t3 `until`). */
    fun until(end: Double, fn: (Double, VehicleSnapshot) -> VehicleSnapshot = { _, s -> s }): SnapSeq {
        while (t < end - 1e-9) add(1) { tt, _, s -> fn(tt, s) }
        return this
    }
}
