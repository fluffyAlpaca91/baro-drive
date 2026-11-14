package com.barodrive.core.sim

import com.barodrive.core.model.Gear
import com.barodrive.core.sim.util.JsMath
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * sim 시계 (프로토타입 sim/clock.js `BaroSim.clock`, 설계 7.2). 0.1 sim초(틱) 단위로만 나아간다.
 *
 * 틱 하나: 단계 `vehicle.pre → player → driver → vehicle.move → navi` → 미룬 일([later]) → [SimEvent.Tick].
 * [simT]는 reset 뒤 틱 수 / 10 — 세션 연결이 세션 시작 때 [reset]으로 0을 만든다.
 * 실제 시간 고리([advance])는 재생 중이면 실제 × 압축(3·6·10) × 배속([realtime]이면 실제 그대로 × 1), 손 조작이면 × 1,
 * 아무 일도 없으면 시계를 세운다.
 * 벽시계는 쓰지 않는다 — 실제 경과 초는 부르는 쪽(앱 고리)이 넘긴다.
 */
class SimClock internal constructor(private val sim: BaroSim) {
    private var tenths = 0
    private var acc = 0.0
    private var rate = 0.0
    private var keep = 0.0
    private val holds = LinkedHashSet<String>()
    private var laterQ = ArrayList<Later>()
    private var realQ = ArrayList<RealLater>()
    private var realT = 0.0
    private var silentDepth = 0

    /** 배속 ×1·×2·×4 — 시나리오 재생 압축에만 곱한다(손 조작은 늘 ×1). */
    var speed: Int = 1
        private set

    /**
     * 실제 속도(패널 배속 '실제'): 참이면 재생 중에도 sim 시간이 실제 시간과 1:1로 간다(압축 3·6·10도 배속도 없음).
     * 처음 값은 거짓 — 헤드리스·골든 실행은 그대로다. 앱 시연 패널이 자기 설정(처음 값 참)을 넣는다.
     */
    var realtime: Boolean = false
        private set

    /** true면 실제 시간 고리가 시계를 멈춘다(▶/⏸). 동기 [tick]·[step]은 그대로 돈다. */
    var paused: Boolean = false

    /** 틱 안인가. */
    var inTick: Boolean = false
        private set

    /** 지금까지 돈 틱 수(reset과 상관없이). */
    var ticks: Long = 0
        private set

    /** 다음 틱까지 쌓인 비율(0~1) — 지도가 틱 사이를 이어 그릴 때. */
    var alpha: Double = 0.0
        private set

    /** sim초(reset 뒤 0부터). */
    val simT: Double get() = tenths / 10.0

    private class Later(var left: Int, val fn: () -> Unit)
    private class RealLater(val due: Double, val fn: () -> Unit)

    /** 틱 하나(설계 7.2). [ff] = 빨리 감기(runUntil·건너뛰기) 표시. */
    fun tick(ff: Boolean = false): SimEvent.Tick {
        inTick = true
        tenths++
        ticks++
        try {
            stage("vehicle.pre") { sim.vehicle.preTick() }
            stage("player") { sim.player.tick(DT) }
            stage("driver") { sim.driver.tick(DT) }
            stage("vehicle.move") { sim.vehicle.move(DT) }
            stage("navi") { sim.navi.tick() }
            runLater()
        } finally {
            inTick = false
        }
        if (keep > 0) keep = max(0.0, JsMath.round((keep - DT) * 10) / 10)
        val info = SimEvent.Tick(tenths / 10.0, DT, ff)
        if (silentDepth == 0) sim.emit(info)
        return info
    }

    private inline fun stage(name: String, fn: () -> Unit) {
        try {
            fn()
        } catch (e: RuntimeException) {
            sim.report("$name 단계", e)
        }
    }

    private fun runLater() {
        if (laterQ.isEmpty()) return
        val due = ArrayList<Later>()
        val keepQ = ArrayList<Later>()
        for (x in laterQ) {
            x.left--
            if (x.left <= 0) due += x else keepQ += x
        }
        laterQ = keepQ
        for (x in due) {
            try {
                x.fn()
            } catch (e: RuntimeException) {
                sim.report("미룬 일", e)
            }
        }
    }

    /** 동기로 [sec]만큼(틱 수 = round(sec / 0.1)) → 돈 틱 수. */
    fun step(sec: Double, ff: Boolean = false): Int {
        val n = max(0, JsMath.round((if (sec.isNaN()) 0.0 else sec) / DT).toInt())
        repeat(n) { tick(ff) }
        return n
    }

    /** sim 시계를 [t]초로(세션 시작 = 0). */
    fun reset(t: Double = 0.0) {
        tenths = JsMath.round(t * 10).toInt()
        acc = 0.0
        alpha = 0.0
    }

    private fun vehicleLive(): Boolean {
        val v = sim.vehicle.state
        return v.gear != Gear.P || abs(v.speed) > 0.01
    }

    /** 손 조작 중 시계가 돌아야 하나. */
    fun live(): Boolean {
        if (keep > 0 || holds.isNotEmpty() || laterQ.isNotEmpty()) return true
        if (vehicleLive()) return true
        return sim.driver.busy()
    }

    /** 지금 따라가려는 배율(sim초/실제초). 재생 중 = 실제 속도면 1, 아니면 압축 × 배속. */
    fun targetRate(): Double {
        val p = sim.player
        if (p.state == PlayerState.PLAYING) return if (realtime) 1.0 else p.compression() * speed.toDouble()
        if (p.state == PlayerState.ENDED && keep > 0) return TAIL_COMPRESSION * speed.toDouble()
        return if (live()) 1.0 else 0.0
    }

    /** 지금 배율(0 = 서 있음). */
    fun rateNow(): Double = rate

    private fun runReal() {
        if (realQ.isEmpty()) return
        val due = realQ.filter { it.due <= realT }
        if (due.isEmpty()) return
        realQ = ArrayList(realQ.filter { it.due > realT })
        for (x in due) {
            try {
                x.fn()
            } catch (e: RuntimeException) {
                sim.report("실제 시간 일", e)
            }
        }
    }

    /**
     * 실제 경과 [real]초 → sim 경과. 돈 틱마다 [onTick]을 부른다(그 틱 바로 뒤). → 돈 틱 수.
     * 한 번에 실제 0.25초·400틱까지만(탭이 잠깐 멈췄다 돌아와도).
     */
    fun advance(real: Double, onTick: ((SimEvent.Tick) -> Unit)? = null): Int {
        val r = JsMath.clamp(if (real.isNaN()) 0.0 else real, 0.0, MAX_REAL)
        realT += r
        runReal()
        if (paused) {
            rate = 0.0; acc = 0.0; alpha = 0.0
            return 0
        }
        val target = targetRate()
        if (!(target > 0)) {
            rate = 0.0; acc = 0.0; alpha = 0.0
            return 0
        }
        if (rate <= 0) rate = target else rate += (target - rate) * min(1.0, r / RATE_EASE)
        acc += r * rate
        var n = floor(acc / DT + 1e-9).toInt()
        if (n > MAX_TICKS_PER_FRAME) {
            n = MAX_TICKS_PER_FRAME
            acc = 0.0
        } else {
            acc -= n * DT
        }
        repeat(n) {
            val info = tick()
            onTick?.invoke(info)
        }
        alpha = JsMath.clamp(acc / DT, 0.0, 1.0)
        return n
    }

    /** 배속 ×1·×2·×4(그 밖은 1). */
    fun setSpeed(x: Int): Int {
        speed = if (x == 2 || x == 4) x else 1
        return speed
    }

    /** 실제 속도 켜기·끄기([realtime]). 끄면 재생은 다시 압축 × 배속. → 지금 값. */
    fun setRealtime(on: Boolean): Boolean {
        realtime = on
        return realtime
    }

    /** 적어도 [sec] sim초는 시계를 더 돌린다(손 조작·기어 바꿈 뒤, 시나리오 끝 뒤 마무리). */
    fun keepAlive(sec: Double): Double {
        if (sec > keep) keep = sec
        return keep
    }

    fun keepLeft(): Double = keep

    /** 다른 쪽이 시계를 붙잡아 둠([on] = false로 놓음). */
    fun hold(name: String, on: Boolean = true): Int {
        if (on) holds += name else holds -= name
        return holds.size
    }

    /** sim 시간으로 미룬 일(그때 틱 안에서 부른다). → 취소 함수. */
    fun later(sec: Double, fn: () -> Unit): () -> Unit {
        val item = Later(max(1, JsMath.round((if (sec.isNaN()) 0.0 else sec) / DT).toInt()), fn)
        laterQ.add(item)
        return { laterQ = ArrayList(laterQ.filter { it !== item }) }
    }

    fun clearLater() {
        laterQ = ArrayList()
    }

    fun pendingLater(): Int = laterQ.size

    /** 실제 시간으로 미룬 일(자동 재생 1초 등). [advance]가 돌 때만 부른다. → 취소 함수. */
    fun realLater(sec: Double, fn: () -> Unit): () -> Unit {
        val item = RealLater(realT + max(0.0, if (sec.isNaN()) 0.0 else sec), fn)
        realQ.add(item)
        return { realQ = ArrayList(realQ.filter { it !== item }) }
    }

    /** [SimEvent.Tick]을 보내지 않고 [fn] 안의 틱을 돈다(건너뛰기). */
    fun <T> silent(fn: () -> T): T {
        silentDepth++
        try {
            return fn()
        } finally {
            silentDepth--
        }
    }

    fun isSilent(): Boolean = silentDepth > 0

    /** 모든 시간 상태를 처음으로(BaroSim.reset). */
    internal fun resetAll() {
        tenths = 0; acc = 0.0; rate = 0.0; keep = 0.0
        holds.clear(); laterQ = ArrayList(); realQ = ArrayList()
        paused = false; speed = 1; realtime = false; alpha = 0.0
    }

    companion object {
        /** 틱 길이(sim초). */
        const val DT = 0.1
        private const val MAX_TICKS_PER_FRAME = 400
        private const val MAX_REAL = 0.25
        private const val RATE_EASE = 0.25
        private const val TAIL_COMPRESSION = 6.0
    }
}
