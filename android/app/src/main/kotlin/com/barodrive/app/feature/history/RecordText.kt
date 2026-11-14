package com.barodrive.app.feature.history

import com.barodrive.core.model.Courses
import com.barodrive.core.model.Mode
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareStatus
import com.barodrive.core.model.TurnDir
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** 기록 묶음 칩 하나 (history.js `FILTERS`): 그 묶음의 모드(null = 전체). */
data class RecordFilter(val id: String, val label: String, val modes: List<Int>?)

/** 회차 줄 글 (history.js `describe`): 제목 '2단계 3회차' · 코스 이름 · '오늘 09:12 · 18분 · 4.2km · 86점'. */
data class RecordLine(
    val mode: Int?,
    val title: String,
    val place: String,
    val whenText: String,
    val dur: String,
    val dist: String,
    val extra: String,
    val sub: String,
)

/** 상태 태그 종류 (history.js `tags`의 kind): 민트 · 옅은 민트 · 주의 테두리 · 회색. */
enum class RecordTagKind { BRAND, SOFT, WARN, MUTE }

/** 상태 태그 하나: 'passed'·'qualified'·'f2'·'share'. [icon]은 심볼 id('i-medal' …). */
data class RecordTag(val id: String, val text: String, val kind: RecordTagKind, val icon: String)

/** 기록 줄을 눌렀을 때 열 리뷰(읽기 전용): 그 모드 리뷰 화면 + 기록 id (history.js `target`). 경로는 [openRecordReview]가 만든다. */
data class RecordTarget(val mode: Int, val recordId: String)

/**
 * 연습 기록 줄 글과 고르기 (프로토타입 screens/history.js `BaroApp.history`, 설계 4.2 ST-2·5.10, 8.1, 결정-90). ST-2 목록, RV-7 보낼 내용,
 * 가족 휴대폰 카드가 같이 쓴다. 화면 없는 계산이고 시계는 넘겨받는다.
 */
object RecordText {
    val FILTERS: List<RecordFilter> = listOf(
        RecordFilter("all", "전체", null),
        RecordFilter("stage", "단계 연습", listOf(1, 2, 3, 4)),
        RecordFilter("free", "자율", listOf(5)),
        RecordFilter("parking", "주차", listOf(6)),
        RecordFilter("hill", "언덕", listOf(7)),
        RecordFilter("license", "면허", listOf(8)),
    )

    const val MAX_TEXT = "최근 30회차까지 차 안에 둬요"

    fun filterOf(id: String?): RecordFilter? = FILTERS.firstOrNull { it.id == id }

    private fun inFilter(rec: SessionRecord, f: RecordFilter?): Boolean = f?.modes == null || modeOf(rec) in f.modes

    /**
     * 보일 묶음 칩 (history.js `filters`): 전체·단계 연습은 늘, 모드 칩은 그 모드 입구 화면이 있거나 그 모드 기록이 있을 때.
     * 이 앱은 입구 화면(FB-2·PK-1·HL-1·LC-1)이 늘 경로로 있어 [hasEntry]의 기본값은 참이다.
     */
    fun filters(records: List<SessionRecord>, hasEntry: (RecordFilter) -> Boolean = { true }): List<RecordFilter> =
        FILTERS.filter { f -> f.modes == null || f.id == "stage" || hasEntry(f) || records.any { inFilter(it, f) } }

    /** 그 묶음의 기록, 저장된 순서(최신이 앞). 모르는 묶음이면 전체. */
    fun list(records: List<SessionRecord>, filterId: String?): List<SessionRecord> {
        val f = filterOf(filterId) ?: FILTERS.first()
        return records.filter { inFilter(it, f) }
    }

    fun modeOf(rec: SessionRecord?): Int? = rec?.mode?.takeIf { it in 1..8 }

    /** '2단계 3회차' · '자율 2회차' · '세워 두고 연습' · 회차 번호가 없으면 모드 이름만. */
    fun title(rec: SessionRecord?): String {
        if (rec == null) return ""
        if (rec.isVirtualParking) return "세워 두고 연습"
        val m = modeOf(rec)
        val short = m?.let { Mode.of(it).short } ?: "연습"
        val n = rec.noInMode ?: 0
        return if (n > 0) "$short ${n}회차" else short
    }

    /** '평행 · 오른쪽'(sim/parking.js `label`). */
    fun parkingLabel(type: ParkingType?, side: TurnDir?): String =
        if (type == null || side == null) "" else "${type.label} · ${side.label}"

    /** 코스·목적지 이름: '초등학교 뒤 골목' · '마트까지'(자율) · '평행 · 오른쪽'(세워 두고) · 면허 코스 이름. */
    fun place(rec: SessionRecord?): String {
        if (rec == null) return ""
        if (rec.isVirtualParking) return rec.parking?.let { parkingLabel(it.type, it.side) }.orEmpty()
        val m = modeOf(rec)
        Courses.course(rec.courseId)?.let { return it.name }
        Courses.place(rec.placeId)?.let { return if (m == 5) it.name + "까지" else it.name }
        if (m == 8) rec.license?.name?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    /** '오늘 09:12' · '어제 09:12' · '11월 10일 09:12' · 다른 해 '2025. 11. 10. 09:12'. 못 읽으면 ''. */
    fun whenText(iso: String?, now: ZonedDateTime): String {
        val d = BaroFormat.toDateTime(iso, now.zone) ?: return ""
        val t = BaroFormat.time(d)
        val diff = ChronoUnit.DAYS.between(d.toLocalDate(), now.toLocalDate())
        return when {
            diff == 0L -> "오늘 $t"
            diff == 1L -> "어제 $t"
            d.year != now.year -> BaroFormat.dotTime(d)
            else -> BaroFormat.md(d.toLocalDate()) + " " + t
        }
    }

    /** 모드별 덧붙임: 자율 점수 '86점' · 주차 '평행 · 오른쪽' · 면허 'C 코스 완주' · 세워 두고 '성공'. */
    fun extra(rec: SessionRecord): String {
        val m = modeOf(rec)
        if (rec.isVirtualParking) {
            return when (rec.parking?.result) {
                "success" -> "성공"
                "stopped" -> "그만했어요"
                else -> ""
            }
        }
        if (m == 5) rec.score?.let { return "${it.total}점" }
        if (m == 6) rec.parking?.let { return parkingLabel(it.type, it.side) }
        if (m == 8) {
            val l = rec.license ?: return ""
            val letter = l.letter?.takeIf { it.isNotEmpty() }?.let { "$it 코스 " }.orEmpty()
            if (l.complete) return letter + "완주"
            val reached = maxOf(l.reached, l.segmentsDone)
            return if (reached > 0) letter + reached + "구간까지" else ""
        }
        return ""
    }

    /** 줄 글 묶음 (history.js `describe`). 주차 기록은 PK-2·PK-4가 보여 주는 걸린 시간(parking.sec)을 쓴다. */
    fun describe(rec: SessionRecord, now: ZonedDateTime): RecordLine {
        val pkSec = if (modeOf(rec) == 6) rec.parking?.sec else null
        val sec = pkSec ?: rec.durationSec
        val dur = if (sec > 0) BaroFormat.min(sec) else ""
        val dist = if (!rec.isVirtualParking && rec.distanceM > 0) BaroFormat.km(rec.distanceM) else ""
        val w = whenText(rec.startedAt, now)
        val ex = extra(rec)
        return RecordLine(
            mode = modeOf(rec), title = title(rec), place = place(rec), whenText = w, dur = dur, dist = dist, extra = ex,
            sub = listOf(w, dur, dist, ex).filter { it.isNotEmpty() }.joinToString(" · "),
        )
    }

    /** 확인 안 한 급제동 수. */
    fun f2Open(rec: SessionRecord?): Int = rec?.f2?.count { it.choice == null } ?: 0

    fun shareStatus(rec: SessionRecord?): ShareStatus = rec?.share?.status ?: ShareStatus.NONE

    /** 상태 태그: 단계 통과 > 통과 회차 · 확인 안 한 급제동 · 공유(보냈어요/보낼 예정). */
    fun tags(rec: SessionRecord): List<RecordTag> {
        val out = ArrayList<RecordTag>()
        val m = modeOf(rec)
        val pr = rec.progress
        val stage = m != null && m in 1..4
        if (stage && pr?.passed == true) {
            out += RecordTag("passed", "${pr.stage ?: rec.stage ?: m}단계 통과", RecordTagKind.BRAND, "i-medal")
        } else if (stage && rec.qualified) {
            out += RecordTag("qualified", "통과 회차", RecordTagKind.SOFT, "i-check")
        }
        val n = f2Open(rec)
        if (n > 0) out += RecordTag("f2", "급제동 확인 ${n}건", RecordTagKind.WARN, "i-brake")
        when (shareStatus(rec)) {
            ShareStatus.SENT -> out += RecordTag("share", "보냈어요", RecordTagKind.MUTE, "i-send")
            ShareStatus.QUEUED -> out += RecordTag("share", "보낼 예정", RecordTagKind.WARN, "i-send")
            ShareStatus.NONE -> Unit
        }
        return out
    }

    /** 그 회차 리뷰(읽기 전용). 세워 두고 연습·id 없는 기록은 리뷰가 없다(null). */
    fun target(rec: SessionRecord?): RecordTarget? {
        val id = rec?.id?.takeIf { it.isNotEmpty() } ?: return null
        if (rec.isVirtualParking) return null
        return RecordTarget(modeOf(rec) ?: 1, id)
    }

    /** 지금 단계 1~4(빈 기록의 [연습하러 가기] → CR-1 그 단계). */
    fun currentStage(p: Profile?): Int = p?.progress?.currentStage?.coerceIn(1, 4) ?: 1
}
