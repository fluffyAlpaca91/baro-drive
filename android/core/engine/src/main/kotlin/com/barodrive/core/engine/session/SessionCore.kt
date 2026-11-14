package com.barodrive.core.engine.session

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.content.Items
import com.barodrive.core.engine.content.LineBook
import com.barodrive.core.engine.content.LineRenderException
import com.barodrive.core.engine.content.SessionLines
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorHandle
import com.barodrive.core.engine.detect.HintBoard
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.engine.track.Tracker
import com.barodrive.core.engine.track.TrackerHost
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.BlockedUtterance
import com.barodrive.core.model.Category
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.InputEvent
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TrackerEvent
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.UtteranceLog
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WaitEndEvent
import com.barodrive.core.model.WaitStartEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.reflect.KClass

/** 꺼진 검출기와 그 이유 (core.js `info().off[]`): 'mode' · 'caps:신호' · 'items' · 'error'. */
data class DetectorOff(val id: String, val reason: String)

/** 한 걸음 출력을 모으는 곳 (core.js `S.out`). */
internal class OutBuilder(val t: Double) {
    val say = ArrayList<Utterance>()
    val blocked = ArrayList<BlockedUtterance>()
    val events = ArrayList<JudgeEvent>()
}

/**
 * 세션 하나의 상태와 규칙 (프로토타입 core.js `createSession`의 `S`와 그 안 함수들, 설계 6.1·6.5·6.9).
 * 말 후보·카드는 [say]·[card], 말 고르기는 [DeliveryRules](설계 6.5.2), 카드 줄은 [CardRow](6.5.3), 회차 기록은 [buildRecord](6.9).
 * 한 스레드에서 차례로 부른다.
 */
internal class SessionCore(
    val cfg: SessionConfig,
    defs: List<DetectorDef>,
    val book: LineBook,
) : TrackerHost {
    val lines = SessionLines(cfg.coach, cfg.honorific, book)
    var t: Double? = null
        private set
    var t0: Double? = null
        private set
    private var lastT: Double? = null
    var snap: VehicleSnapshot? = null
        private set
    var driveSec = 0.0
        private set
    var distM = 0.0
        private set
    var trip0: Double? = null
        private set
    var tripLast: Double? = null
        private set
    val weather = cfg.weather.initial.let { WeatherState(it.type, it.severe, cold = false) }
    val events = ArrayList<JudgeEvent>()
    val utterances = ArrayList<UtteranceLog>()
    val notes = SessionNotes()
    val hints = HintBoard()
    val pending = ArrayList<Candidate>()
    var seq = 0
    var navVoiceUntil = Double.NEGATIVE_INFINITY
        private set
    private val silences = ArrayList<Silence>()
    var muted = cfg.muted
        private set
    val missed = HashSet<String>()
    val skipped = HashSet<String>()
    var arrivedNav = false
        private set
    val dets = ArrayList<DetectorHandle>()
    val off = ArrayList<DetectorOff>()
    var out: OutBuilder? = null
        private set
    var finishing = false
        private set
    var finished = false
        private set
    var record: SessionRecord? = null
        private set

    /** 검출기·대사 오류(core.js `logError` → console.error). 세션은 멈추지 않는다. */
    val errors = ArrayList<String>()
    val shared = HashMap<KClass<*>, Any>()
    val tracker = Tracker(this, cfg.startGear)
    val rules = DeliveryRules(this)
    val cards = CardRow(this)

    init {
        for (def in defs) {
            val why = offReason(def)
            if (why != null) {
                off += DetectorOff(def.id, why)
                continue
            }
            val handle = DetectorHandle(def.id)
            try {
                handle.inst = def.create(DetectorContext(this, handle))
                dets += handle
            } catch (e: Exception) {
                logError("검출기 ${def.id} 만들기 오류", e)
                off += DetectorOff(def.id, "error")
            }
        }
    }

    fun now(): Double = t ?: 0.0

    /** 24개 항목이면 이 회차 값(스위치로 빠진 항목은 X), 항목이 아닌 말(K8 답 등)은 O (core.js `S.deliver`). */
    fun deliver(item: String?): Delivery {
        if (item == null || Items.def(item) == null) return Delivery.NOW
        return cfg.items[item] ?: Delivery.OFF
    }

    fun dropped(key: String): Boolean = key in missed || key in skipped

    override fun addSilence(until: Double, reason: String) {
        val now = t
        silences.removeAll { now != null && it.until <= now }
        silences += Silence(until, reason)
    }

    fun inSilence(at: Double): Boolean = silences.any { at < it.until - EPS }

    /** 재탐색으로 놓친·건너뛴 회전: 남은 말 후보·카드를 버리고 끝날 때 그 판정을 뺀다(6.9). */
    override fun turnMissed(id: String) = dropTurn(missed, id)

    override fun turnSkipped(id: String) = dropTurn(skipped, id)

    override fun navArrived() {
        arrivedNav = true
    }

    private fun dropTurn(set: MutableSet<String>, id: String) {
        set += id
        pending.removeAll { it.pairWith == id }
        cards.dropTurn(id)
    }

    fun render(key: String, vars: Map<String, Any?>, peek: Boolean): String = lines.render(key, vars, peek)

    fun lineOrEmpty(key: String, index: Int): String = try {
        book.render(key, cfg.coach, emptyMap(), cfg.honorific, index = index)
    } catch (e: LineRenderException) {
        ""
    }

    fun logError(message: String, e: Throwable? = null) {
        errors += if (e == null) message else "$message: ${e.message}"
    }

    /* ---------- 기록 ---------- */
    fun logBlocked(item: String?, key: String, category: Category, text: String, reason: BlockReason, at: Double) {
        utterances += UtteranceLog(t = at, item = item, key = key, category = category, text = text, spoken = false, reason = reason)
        out?.blocked?.add(BlockedUtterance(Utterance(key = key, text = text, category = category, item = item, t = at, coach = cfg.coach), reason))
    }

    fun logBlocked(c: Candidate, reason: BlockReason, at: Double) = logBlocked(c.item, c.key, c.category, c.text, reason, at)

    /** 대사를 그리지 못한 말은 버리고 막힌 말로 남긴다(엔진은 멈추지 않음). */
    fun renderFailed(detId: String, item: String?, key: String, category: Category, e: LineRenderException, at: Double) {
        logError("$detId · ${e.message}")
        logBlocked(item, key, category, "", BlockReason.EXPIRED, at)
    }

    fun emit(item: String?, result: EventResult, data: EventData): JudgeEvent {
        val ev = JudgeEvent(t = now(), item = item, result = result, data = data)
        events += ev
        out?.events?.add(ev)
        return ev
    }

    /* ---------- 말 후보 (설계 6.5.1, core.js say) ---------- */
    fun say(detId: String, x: SayRequest): String? {
        val at = now()
        val category = x.category ?: Items.def(x.item ?: "")?.cats?.firstOrNull() ?: Category.INFO
        val text = try {
            render(x.key, x.vars, peek = true)
        } catch (e: LineRenderException) {
            renderFailed(detId, x.item, x.key, category, e, at)
            return null
        }
        val item = x.item
        val c = Candidate(
            seq = ++seq, item = item, key = x.key, vars = x.vars, category = category, priority = x.priority ?: category.priority,
            createdAt = at, pairWith = x.pairWith, alive = x.alive, expireReason = x.expireReason, cardable = x.cardable, card = x.card,
            answer = x.answer || item == K8, guide = x.guide || (item != null && Items.def(item)?.guide == true), text = text,
            fromCard = null, expiresAt = expiresAt(category, x, at),
        )
        val dv = deliver(item)
        if (dv != Delivery.NOW) {
            logBlocked(c, if (dv == Delivery.AFTER) BlockReason.AFTER else BlockReason.OFF, at) // 규칙 1
            return null
        }
        if (finishing || finished) {
            logBlocked(c, BlockReason.EXPIRED, at)
            return null
        }
        if (muted && category != Category.SAFETY) { // 규칙 2
            logBlocked(c, BlockReason.MUTED, at)
            return null
        }
        if (c.pairWith != null && dropped(c.pairWith)) return null
        pending.firstOrNull { it.fromCard == null && it.item == c.item && it.key == c.key && it.pairWith == c.pairWith }?.let { return it.id }
        pending += c
        return c.id
    }

    private fun expiresAt(category: Category, x: SayRequest, at: Double): Double {
        x.expiresIn?.takeIf { it.isFinite() }?.let { return at + it }
        return at + when (category) {
            Category.SAFETY -> Thresholds[if (x.alive != null) "safety.maxWait" else "safety.expire"]
            Category.PRE -> Thresholds[if (x.pairWith != null || x.alive != null) "pre.maxWait" else "info.expire"]
            Category.PRAISE -> Thresholds["praise.expire"]
            Category.FIX -> Thresholds["fix.expire"]
            Category.INFO -> Thresholds["info.expire"]
        }
    }

    fun card(detId: String, x: CardRequest): String? = cards.add(detId, x)

    /* ---------- 한 스냅숏 (core.js step) ---------- */
    fun step(snapIn: VehicleSnapshot): StepOutput {
        if (finished || finishing) return closeOut(OutBuilder(now()))
        val s = normSnap(snapIn)
        val at = s.t
        if (t0 == null) t0 = at
        var dt = lastT?.let { max(0.0, at - it) } ?: 0.0
        if (s.skip || dt > Thresholds["jump.sec"] + EPS) dt = 0.0 // 건너뛴 시간은 달린 시간이 아님
        if (s.gear != Gear.P) driveSec += dt
        s.speed?.let { distM += abs(it) / MS_TO_KMH * dt }
        s.tripDist?.let {
            if (trip0 == null) trip0 = it
            tripLast = it
        }
        t = at
        lastT = at
        snap = s
        if (s.nav?.voice == true) navVoiceUntil = at + Thresholds["navVoice.after"]
        val o = OutBuilder(at)
        out = o
        val evs = tracker.step(s)
        var waitStart = false
        var waitEnd = false
        for (e in evs) {
            if (e is WaitStartEvent) waitStart = true
            if (e is WaitEndEvent) waitEnd = true
            dispatch(e)
        }
        for (d in dets) {
            try {
                d.inst.step(s)
            } catch (e: Exception) {
                logError("검출기 ${d.id} · step 오류", e)
            }
        }
        rules.manage(s, at, waitStart, waitEnd)
        out = null
        return closeOut(o)
    }

    /** 화면 사건 (core.js input): mute·unmute는 여기서, 모든 사건을 검출기에 'input'으로 넘긴 뒤 전달 관리자를 한 번 돌린다. */
    fun input(ev: SessionInput): StepOutput {
        if (finished || finishing) return closeOut(OutBuilder(now()))
        val o = OutBuilder(now())
        out = o
        when (ev) {
            SessionInput.Mute -> muted = true
            SessionInput.Unmute -> muted = false
            else -> Unit
        }
        dispatch(InputEvent(ev))
        snap?.let { rules.manage(it, now(), waitStart = false, waitEnd = false) }
        out = null
        return closeOut(o)
    }

    /** 회차 끝 (core.js finish): 열린 창 닫기 → 검출기 finish → 남은 후보는 막힌 말로 → 기록 → 리뷰·점수. 두 번 불러도 같은 기록. */
    fun finish(arrived: Boolean?): SessionRecord {
        record?.let { return it }
        finishing = true
        out = null
        val at = now()
        for (e in tracker.flush(at)) dispatch(e)
        val draft = RecordDraft(cfg.mode, cfg.items) { events }
        for (d in dets) {
            try {
                d.inst.finish(draft)
            } catch (e: Exception) {
                logError("검출기 ${d.id} · finish 오류", e)
            }
        }
        for (c in pending) if (!(c.pairWith != null && dropped(c.pairWith))) logBlocked(c, c.lastReason ?: BlockReason.EXPIRED, at)
        pending.clear()
        cards.clear() // 남은 카드는 리포트로(6.5.3)
        val rec = runHooks(buildRecord(draft, arrived ?: arrivedNav))
        finished = true
        record = rec
        return rec
    }

    fun dispatch(e: TrackerEvent) {
        for (d in dets) {
            val hs = d.handlers[e.javaClass] ?: continue
            for (fn in hs.toList()) {
                try {
                    fn(e)
                } catch (ex: Exception) {
                    logError("검출기 ${d.id} · ${e.event.name} 처리 오류", ex)
                }
            }
        }
    }

    private fun closeOut(o: OutBuilder): StepOutput = StepOutput(
        say = o.say.toList(), cards = cards.visibleView(), hints = hints.snapshot(), blocked = o.blocked.toList(), t = o.t,
        events = o.events.toList(), waiting = tracker.view.waiting, turn = tracker.view.turn, muted = muted,
    )

    /** 못 읽는 신호는 null로(설계 6.1.2), 수가 아닌 값도 null (core.js normSnap — caps.trip은 tripDist를 지움). */
    private fun normSnap(sn: VehicleSnapshot): VehicleSnapshot {
        val c = cfg.caps
        fun fin(x: Double?) = x?.takeIf { it.isFinite() }
        return sn.copy(
            t = if (sn.t.isFinite()) sn.t else (t?.plus(SNAP_STEP) ?: 0.0),
            speed = fin(sn.speed).takeIf { c.speed },
            gear = sn.gear.takeIf { c.gear },
            blinker = sn.blinker.takeIf { c.blinker },
            steer = fin(sn.steer).takeIf { c.steer },
            nav = sn.nav.takeIf { c.nav },
            passenger = sn.passenger.takeIf { c.passenger },
            beltDriver = sn.beltDriver.takeIf { c.beltDriver },
            handbrake = sn.handbrake.takeIf { c.handbrake },
            wiper = sn.wiper.takeIf { c.wiper },
            outsideTemp = fin(sn.outsideTemp).takeIf { c.outsideTemp },
            tripDist = fin(sn.tripDist).takeIf { c.trip },
        )
    }

    /** 검출기가 꺼지는 이유 (core.js `offReason`). */
    private fun offReason(def: DetectorDef): String? {
        val modes = def.modes
        if (modes != null && cfg.mode !in modes) return "mode"
        def.needs.firstOrNull { !cfg.caps[it] }?.let { return "caps:${it.key}" }
        if (def.items.isNotEmpty() && def.items.none { it in cfg.items }) return "items"
        return null
    }

    private companion object {
        const val K8 = "K8"
        const val MS_TO_KMH = 3.6
        const val SNAP_STEP = 0.1
    }
}
