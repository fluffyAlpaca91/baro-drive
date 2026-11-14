package com.barodrive.core.engine.detect.weather

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.engine.detect.basic.DRIVE_MODES
import com.barodrive.core.engine.round1
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.CautionType
import com.barodrive.core.model.Category
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.ReviewTip
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.Severe
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WaitStartEvent
import com.barodrive.core.model.WeatherBadge
import com.barodrive.core.model.WeatherSource
import com.barodrive.core.model.WeatherType

/*
 * 날씨 검출기 'weather.J3' (프로토타입 detectors/weather.js, 설계 6.2·6.3.1·6.5.3·6.8·5.6, 결정-17·82, 결정사항 E T1c-12·T7-4).
 * J3 전달 방식과 상관없이 돌고 날씨를 바꾼다(설계 6.8 'J3을 X로 바꿔도 낮춘 기준은 그대로').
 */

/** 요령 묶음마다 요령 수. */
private val TIP_COUNT = mapOf("rain" to 5, "snow" to 5)
private val ICON = mapOf("rain" to "i-rain", "snow" to "i-snow", "severe" to "i-hazard")

/** 날씨 안내 중인가: 'snow' · 'rain' · 'severe' · null (weather.js `kindOf`). */
internal fun kindOf(w: SessionWeather): String? = when {
    w.type == WeatherType.SNOW -> "snow"
    w.type == WeatherType.RAIN -> "rain"
    w.severe != null -> "severe"
    else -> null
}

/** DR-1 배지: 아주 나쁜 날(50%)이 비·눈(20%)보다 앞선다 (weather.js `badgeOf`). */
internal fun badgeOf(w: SessionWeather): WeatherBadge? = when {
    w.severe != null -> WeatherBadge.SEVERE
    w.type == WeatherType.SNOW -> WeatherBadge.SNOW
    w.type == WeatherType.RAIN -> WeatherBadge.RAIN
    else -> null
}

/** 요령 묶음: 눈 → 눈길, 비 → 빗길, 아주 나쁜 날만 → 앞이 안 보임(fog)은 빗길, 그 밖은 눈길 (weather.js `tipSetOf`). */
internal fun tipSetOf(w: SessionWeather): String? = when {
    w.type == WeatherType.SNOW -> "snow"
    w.type == WeatherType.RAIN -> "rain"
    w.severe == Severe.FOG -> "rain"
    w.severe != null -> "snow"
    else -> null
}

private fun roadOf(set: String): String = when (set) {
    "rain" -> WeatherBadge.RAIN.road
    "snow" -> WeatherBadge.SNOW.road
    else -> WeatherBadge.SEVERE.road
}

private val TIP_SPLIT = Regex("^(.+?[.!?])\\s+(\\S.*)$")

/** 요령 한 문장 → 카드 제목(첫 문장) · 보조 줄('빗길 요령' + ' · ' 둘째 문장) (weather.js `tipText`). */
internal fun tipText(text: String, set: String): Pair<String, String> {
    val s = text.trim()
    val m = TIP_SPLIT.find(s)
    val head = m?.groupValues?.get(1) ?: s
    val rest = m?.groupValues?.get(2).orEmpty()
    return head to (roadOf(set) + " 요령" + if (rest.isNotEmpty()) " · $rest" else "")
}

/** J3 상태 (weather.js `st`). */
private class J3State(val ctx: DetectorContext) {
    var t0: Double? = null
    var wiperOn: Double? = null
    var moved = false
    var departed = false
    var by: WeatherSource? = null
    var at: Double? = null
    var badge: WeatherBadge? = null
    val said = HashSet<String>()
    val used = HashMap<String, Int>()
    val queued = ArrayList<String>()
    val slipSeen = HashSet<String>()
    var slipNow: Set<String> = emptySet()

    fun paintBadge() {
        val b = badgeOf(ctx.weather())
        if (b == badge) return
        badge = b
        ctx.hints.badge = b
    }

    fun announce(kind: String, data: EventData) {
        if (!said.add(kind)) return
        ctx.emit("J3", EventResult.INFO, EventData(kind = kind) + data)
        ctx.say("j3.$kind", item = "J3", category = Category.INFO)
    }

    /** 출발(첫 D): 시작 전에 고른 비·눈 문장, 이어서 0°C 이하면 언 길 문장. */
    fun depart(snap: VehicleSnapshot) {
        departed = true
        val w = ctx.weather()
        if (w.type == WeatherType.SNOW || w.type == WeatherType.RAIN) {
            announce(if (w.type == WeatherType.SNOW) "snow" else "rain", EventData(trigger = "depart", by = by?.code(), severe = w.severe))
        }
        val temp = snap.outsideTemp
        if (temp != null && temp <= ctx.th("J3.cold") + EPS) {
            ctx.setWeather(cold = true)
            announce("cold", EventData(trigger = "depart", temp = temp))
        }
    }

    /** 와이퍼 30초 → 비 오는 날 안내(이미 비·눈이면 그대로). */
    fun toRain(snap: VehicleSnapshot) {
        val w = ctx.weather()
        if (w.type == WeatherType.RAIN || w.type == WeatherType.SNOW) return
        ctx.setWeather(type = WeatherType.RAIN)
        by = WeatherSource.WIPER
        at = round1(snap.t - (t0 ?: snap.t))
        paintBadge()
        announce("rain", EventData(trigger = "wiper", wiperSec = round1(snap.t - (wiperOn ?: snap.t)), severe = w.severe))
    }

    /** 미끄러운 길 주의 구간: 날씨 안내 중에만, 300m 안에 들어온 구간마다 한 번(지나면 만료). */
    fun slippery(snap: VehicleSnapshot) {
        val cs = snap.nav?.cautions.orEmpty().filter { it.type == CautionType.SLIPPERY_ROAD_ALERT }
        slipNow = cs.map { it.id }.toSet()
        if (slipNow.isEmpty() || kindOf(ctx.weather()) == null) return
        for (c in cs) {
            val id = c.id
            if (id in slipSeen || !(c.remain <= ctx.th("J3.slip") + EPS)) continue
            slipSeen += id
            ctx.emit("J3", EventResult.INFO, EventData(kind = "slip", cautionId = id, remain = c.remain))
            ctx.say("j3.slip", item = "J3", category = Category.INFO, alive = { id in slipNow })
        }
    }

    /** 신호 대기 요령 카드 — 신호 대기가 시작될 때 다음 요령 하나(말 없음, 결정-82). */
    fun tipCard() {
        if (!moved) return // 출발할 때 서 있는 것은 넘긴다
        val set = tipSetOf(ctx.weather()) ?: return
        if (ctx.deliver("J3") != Delivery.NOW) return // S면 리뷰 팁 줄만, X면 없음
        val n = used[set] ?: 0
        if (n >= (TIP_COUNT[set] ?: 0)) return
        val key = "j3.tip.$set.${n + 1}"
        val text = ctx.line(key)
        if (text.isEmpty()) return
        val (title, detail) = tipText(text, set)
        ctx.card(item = "J3", category = CardCategory.TIP, icon = ICON[set], title = title, detail = detail) ?: return
        used[set] = n + 1
        queued += key
        ctx.emit("J3", EventResult.INFO, EventData(kind = "tip", tip = key, set = set))
    }

    /** 회차 끝: 날씨 기록(by·at·tipCards)과 리뷰 날씨 팁 줄(J3가 X가 아닐 때). */
    fun finish() {
        ctx.noteWeather(by, at, queued.size)
        val w = ctx.weather()
        if (kindOf(w) == null || ctx.deliver("J3") == Delivery.OFF) return
        val list = ArrayList<ReviewTip>()
        if (w.type == WeatherType.RAIN || w.type == WeatherType.SNOW) {
            val type = if (w.type == WeatherType.RAIN) "rain" else "snow"
            list += ReviewTip(text = ctx.line("j3.review.$type"), icon = ICON[type])
            if (w.severe != null) list += ReviewTip(text = ctx.line("j3.review.severe50"), icon = ICON["severe"])
        } else {
            list += ReviewTip(text = ctx.line("j3.review.severe"), icon = ICON["severe"])
        }
        ctx.noteTips(list.filter { it.text.isNotEmpty() })
    }
}

/** 날씨 안내가 시작된 까닭 글자('snow' …). */
private fun WeatherSource.code(): String = name.lowercase()

/**
 * J3 날씨 안내 (weather.js 'weather.J3'). 처음 날씨는 cfg(PR-1에서 고름). 와이퍼가 이어서 30초 켜지면 비. 출발(첫 D) 때
 * 'j3.snow'·'j3.rain'·'j3.cold'(회차에 한 번씩), 날씨 안내 중 미끄러운 길 300m 안이면 'j3.slip'. 날씨 안내 중이고 J3가 O면
 * 신호 대기마다 요령 카드 1장(같은 요령은 회차에 한 번). 화면 배지 힌트(badge)는 J3가 S·X여도 보인다.
 */
internal val J3 = DetectorDef(id = "weather.J3", items = listOf("J3"), modes = DRIVE_MODES) { ctx ->
    val st = J3State(ctx)
    val w0 = ctx.weather()
    if (w0.type == WeatherType.SNOW || w0.type == WeatherType.RAIN) {
        st.by = if (w0.type == WeatherType.SNOW) WeatherSource.SNOW else WeatherSource.RAIN
        st.at = 0.0
    } else if (w0.severe != null) {
        st.by = WeatherSource.SEVERE
        st.at = 0.0
    }
    st.paintBadge()
    ctx.on<WaitStartEvent> { st.tipCard() }
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            if (st.t0 == null) st.t0 = snap.t
            if (ctx.track.moving) st.moved = true
            // 비: 와이퍼가 이어서 30초(정차 중 포함). 못 읽으면(null) 세지 않는다
            if (snap.wiper == true) {
                val on = st.wiperOn
                if (on == null) st.wiperOn = snap.t else if (snap.t - on >= ctx.th("J3.wiperSec") - EPS) st.toRain(snap)
            } else {
                st.wiperOn = null
            }
            // 출발(첫 D · 기어를 못 읽으면 처음 움직일 때)
            if (!st.departed && (snap.gear == Gear.D || (snap.gear == null && st.moved))) st.depart(snap)
            st.slippery(snap)
        }

        override fun finish(rec: RecordDraft) = st.finish()
    }
}
