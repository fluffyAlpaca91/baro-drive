package com.barodrive.app.feature.history

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlin.math.floor
import kotlin.math.max

/**
 * 거리·시간·날짜 글 (프로토타입 core/util.js `fmtKm`·`fmtMin`·`fmtDate`·`toDate`, 설계 2.7 표기). 기록 줄(ST-2)·리뷰 부제(RV-4)·
 * 보낼 내용(RV-7)이 같이 쓴다. 시계를 읽지 않는다 — 시간대([zone])와 '지금'은 부르는 쪽이 넘긴다.
 */
object BaroFormat {
    /** 미터 → '2.6km'(늘 소수 한 자리, JS toFixed와 같은 반올림). */
    fun km(m: Double): String = toFixed1(max(0.0, m) / 1000) + "km"

    /** 초 → '45초' · '7분' · '1시간 5분'. */
    fun min(sec: Double): String {
        val s = jsRound(max(0.0, sec)).toLong()
        if (s < 60) return "${s}초"
        val m = jsRound(s / 60.0).toLong()
        if (m < 60) return "${m}분"
        val h = m / 60
        val mm = m % 60
        return if (mm > 0) "${h}시간 ${mm}분" else "${h}시간"
    }

    /** 'YYYY-MM-DD'(그 지역 날짜) · ISO 글자 → 그 지역 시각. 못 읽으면 null (util.js `toDate`). */
    fun toDateTime(value: String?, zone: ZoneId): ZonedDateTime? {
        val s = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (DATE_ONLY.matches(s)) return parseOrNull { LocalDate.parse(s).atStartOfDay(zone) }
        return parseOrNull { OffsetDateTime.parse(s).atZoneSameInstant(zone) }
            ?: parseOrNull { ZonedDateTime.parse(s).withZoneSameInstant(zone) }
            ?: parseOrNull { LocalDateTime.parse(s).atZone(zone) }
    }

    /** '2026. 4. 1.'(목업 표기). */
    fun dot(d: LocalDate): String = "${d.year}. ${d.monthValue}. ${d.dayOfMonth}."

    /** '4월 1일'. */
    fun md(d: LocalDate): String = "${d.monthValue}월 ${d.dayOfMonth}일"

    /** '09:12'. */
    fun time(t: ZonedDateTime): String = pad2(t.hour) + ":" + pad2(t.minute)

    /** '2026. 4. 1. 09:12'. */
    fun dotTime(t: ZonedDateTime): String = dot(t.toLocalDate()) + " " + time(t)

    /** JS Math.round(소수 .5는 위로). */
    fun jsRound(x: Double): Double = floor(x + 0.5)

    /** JS toFixed(1). */
    fun toFixed1(x: Double): String = BigDecimal(x).setScale(1, RoundingMode.HALF_UP).toPlainString()

    private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

    private inline fun <T> parseOrNull(block: () -> T): T? = try {
        block()
    } catch (_: DateTimeParseException) {
        null
    }

    private val DATE_ONLY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
}
