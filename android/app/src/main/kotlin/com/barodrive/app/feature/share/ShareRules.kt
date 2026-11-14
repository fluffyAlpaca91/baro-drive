package com.barodrive.app.feature.share

import com.barodrive.app.data.repo.StoreRules
import com.barodrive.app.feature.history.BaroFormat
import com.barodrive.app.feature.history.RecordText
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.ShareStatus
import com.barodrive.core.model.WeatherBadge
import com.barodrive.core.model.WeatherType
import java.time.ZonedDateTime

/** 보낼 내용 한 줄 (share.js `summary` lines): 'trip'·'place'·'tasks'·'pass'·'score'·'parking'·'license'·'good'·'next'·'f2'·'weather'. */
data class ShareLine(val id: String, val label: String, val text: String)

/** 보낼 내용 (share.js `summary`) — 미리보기와 가족 카드가 같이 쓴다. 위치·경로는 넣지 않는다. */
data class ShareSummary(val recordId: String?, val mode: Int, val title: String, val whenText: String, val to: String, val lines: List<ShareLine>)

/** 리뷰 화면 줄 버튼 상태 (share.js `linkInfo`). */
data class ShareLinkInfo(val state: ShareStatus, val text: String)

/** 가족 휴대폰에 도착한 요약 카드 (share.js `card`, 시연 패널 '가족 휴대폰 미리보기'). */
data class FamilyCard(
    val id: String,
    val recordId: String?,
    val profileId: String?,
    val from: String,
    val to: String,
    val at: String,
    val title: String,
    val whenText: String,
    val lines: List<ShareLine>,
)

/**
 * 가족에게 요약 보내기의 화면 없는 계산 (프로토타입 screens/share.js `BaroApp.share`의 순수 부분, 설계 4.2 RV-7·9, 8.1 shareQueue·share,
 * 결정-45·46). 기록·프로필은 고치지 않고 새 값을 돌려준다. 날짜는 넘겨받은 시각만 쓴다.
 */
object ShareRules {
    const val DEFAULT_TO = "가족"
    const val SEND_MS = 600L
    const val FAMILY_MAX = 20
    private val CHOICE = mapOf(F2Choice.AVOID to "위험 피하기", F2Choice.MISTAKE to "내 실수")

    fun status(rec: SessionRecord?): ShareStatus = rec?.share?.status ?: ShareStatus.NONE

    /** 받는 사람 이름(설정에 넣은 이름, 비면 '가족'). */
    fun familyName(p: Profile?): String = p?.consent?.familyName?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TO

    private fun absWhen(iso: String?, now: ZonedDateTime): String {
        val d = BaroFormat.toDateTime(iso, now.zone) ?: return ""
        return BaroFormat.md(d.toLocalDate()) + " " + BaroFormat.time(d)
    }

    /** 급제동 줄 글: '1번 · 아직 확인 전' · '1번 · 본인 확인: 위험 피하기' · '3번 · 위험 피하기 1번 · 내 실수 1번 · 확인 전 1번'. */
    fun f2Text(list: List<F2Check>): String {
        val n = list.size
        if (n == 0) return ""
        if (n == 1) {
            val c = list[0].choice
            return "1번 · " + (c?.let { "본인 확인: " + CHOICE.getValue(it) } ?: "아직 확인 전")
        }
        val parts = ArrayList<String>()
        val avoid = list.count { it.choice == F2Choice.AVOID }
        val mistake = list.count { it.choice == F2Choice.MISTAKE }
        val open = list.count { it.choice == null }
        if (avoid > 0) parts += "${CHOICE[F2Choice.AVOID]} ${avoid}번"
        if (mistake > 0) parts += "${CHOICE[F2Choice.MISTAKE]} ${mistake}번"
        if (open > 0) parts += "확인 전 ${open}번"
        return "${n}번 · " + parts.joinToString(" · ")
    }

    /** 보낼 내용: 회차·날짜·시간·거리·과제 달성·잘한 것 1줄·다음 과제·급제동 확인·날씨(+ 자율 점수·주차·면허 코스). */
    fun summary(rec: SessionRecord, p: Profile?, now: ZonedDateTime): ShareSummary {
        val d = RecordText.describe(rec, now)
        val m = rec.mode
        val lines = ArrayList<ShareLine>()
        fun add(id: String, label: String, text: String?) {
            if (!text.isNullOrEmpty()) lines += ShareLine(id, label, text)
        }
        add("trip", "시간·거리", listOf(d.dur, d.dist).filter { it.isNotEmpty() }.joinToString(" · "))
        val virtual = rec.isVirtualParking
        if (d.place.isNotEmpty() && !virtual && m in 5..7) add("place", if (m == 5) "목적지" else "장소", d.place)
        var s = 0
        var t = 0
        for (x in rec.tasks) {
            if (x.total <= 0) continue
            t += x.total
            s += x.success.coerceIn(0, x.total)
        }
        if (t > 0) add("tasks", "과제", "${BaroFormat.jsRound(s.toDouble() / t * 100).toInt()}% 해냈어요 ($s/$t)")
        val pr = rec.progress
        if (m in 1..4 && pr?.passed == true) add("pass", "단계", "${pr.stage ?: rec.stage ?: m}단계를 통과했어요")
        if (m == 5) rec.score?.let { add("score", "안전운전 점수", "${it.total}점") }
        if (m == 6) {
            rec.parking?.let { pk ->
                val label = RecordText.parkingLabel(pk.type, pk.side)
                if (virtual) {
                    add("parking", "주차", listOf("세워 두고 연습", label, if (pk.result == "success") "성공" else "").filter { it.isNotEmpty() }.joinToString(" · "))
                } else {
                    val steps = pk.total?.takeIf { it > 0 }?.let { "${pk.reached ?: 0}/${it}단계" }.orEmpty()
                    add("parking", "주차", listOf(label, steps).filter { it.isNotEmpty() }.joinToString(" · "))
                }
            }
        }
        if (m == 8) {
            rec.license?.let { l ->
                val name = l.name?.takeIf { it.isNotEmpty() } ?: l.letter?.takeIf { it.isNotEmpty() }?.let { "시험장 주변 $it 코스" }.orEmpty()
                val how = if (l.complete) "완주" else if (l.reached > 0) "${l.reached}구간까지" else ""
                add("license", "코스", listOf(name, how).filter { it.isNotEmpty() }.joinToString(" · "))
            }
        }
        rec.good.firstOrNull { it.text.isNotEmpty() }?.let { add("good", "잘한 것", it.text) }
        rec.nextTask?.label?.takeIf { it.isNotEmpty() }?.let { add("next", "다음 과제", it) }
        if (rec.f2.isNotEmpty()) add("f2", "급제동", f2Text(rec.f2))
        rec.weather?.let { w ->
            val road = when {
                w.severe != null -> WeatherBadge.SEVERE.road
                w.type == WeatherType.RAIN -> WeatherBadge.RAIN.road
                w.type == WeatherType.SNOW -> WeatherBadge.SNOW.road
                else -> ""
            }
            add("weather", "날씨", road)
        }
        val title = d.title + if (d.place.isNotEmpty() && m in 1..4) " · " + d.place else ""
        return ShareSummary(recordId = rec.id, mode = m, title = title, whenText = absWhen(rec.startedAt, now), to = familyName(p), lines = lines)
    }

    /** 리뷰 화면 줄 버튼 글: '가족에게 요약 보내기' · '연결되면 보내요' · '보냈어요 · 아버지'. */
    fun linkInfo(rec: SessionRecord?, p: Profile?): ShareLinkInfo = when (status(rec)) {
        ShareStatus.SENT -> ShareLinkInfo(ShareStatus.SENT, "보냈어요 · " + (rec?.share?.to?.takeIf { it.isNotEmpty() } ?: familyName(p)))
        ShareStatus.QUEUED -> ShareLinkInfo(ShareStatus.QUEUED, "연결되면 보내요")
        ShareStatus.NONE -> ShareLinkInfo(ShareStatus.NONE, "가족에게 요약 보내기")
    }

    /** 보낼 예정으로(끊김): 기록 share = queued + 프로필 shareQueue에 id(두 번 넣지 않음). */
    fun markQueued(p: Profile, rec: SessionRecord, to: String?, now: ZonedDateTime): Pair<Profile, SessionRecord> {
        val r = rec.copy(share = ShareState(status = ShareStatus.QUEUED, to = to ?: familyName(p), queuedAt = StoreRules.isoLocal(now)))
        val id = rec.id
        val q = if (id == null || id in p.shareQueue) p.shareQueue else p.shareQueue + id
        return p.copy(shareQueue = q) to r
    }

    /** 보냄: 기록 share = sent + shareQueue에서 뺌. */
    fun markSent(p: Profile, rec: SessionRecord, to: String?, now: ZonedDateTime): Pair<Profile, SessionRecord> {
        val r = rec.copy(share = ShareState(status = ShareStatus.SENT, to = to ?: familyName(p), at = StoreRules.isoLocal(now)))
        return p.copy(shareQueue = p.shareQueue.filterNot { it == rec.id }) to r
    }

    /** 가족 휴대폰 카드 하나. */
    fun card(rec: SessionRecord, p: Profile, to: String?, now: ZonedDateTime): FamilyCard {
        val s = summary(rec, p, now)
        val at = StoreRules.isoLocal(now)
        return FamilyCard(
            id = "fam-${rec.id}-" + at.filter { it.isDigit() }.take(14), recordId = rec.id, profileId = p.id, from = p.name,
            to = to ?: s.to, at = at, title = s.title, whenText = s.whenText, lines = s.lines,
        )
    }

    /** 가족 공유 동의를 끌 때(ST-4): 보낼 예정이던 기록을 none으로 되돌리고 줄을 비운다. 고친 기록 목록과 거둔 수. */
    fun cancelQueued(records: List<SessionRecord>): Pair<List<SessionRecord>, Int> {
        var n = 0
        val out = records.map { r ->
            if (status(r) == ShareStatus.QUEUED) {
                n++
                r.copy(share = ShareState(status = ShareStatus.NONE))
            } else {
                r
            }
        }
        return out to n
    }
}
