package com.barodrive.app.feature.parking

import androidx.compose.runtime.Immutable
import com.barodrive.app.R
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.ParkingResult
import com.barodrive.core.model.SessionRecord

/**
 * PK-4 주차 연습 마무리 화면 값 (프로토타입 parking-guide.js PK-4 + review.js `frame`, 설계 4.2 PK-4, 결정-60).
 * [loading]이면 기록을 아직 읽는 중, 읽었는데 [model]이 null이면 '이 회차 기록을 찾을 수 없어요'.
 */
@Immutable
data class ParkingDoneUiState(
    val loading: Boolean = true,
    val model: ModeReviewModel? = null,
)

/** PK-4 결과 줄 하나 (parking-guide.js `rowsOf`): 이름 · 값(좋으면 민트 [good], 주의면 [warn]) · 값 아래 코치 문장([line], PK3·PB2). */
@Immutable
data class ParkingDoneRow(
    val id: String,
    val label: String,
    val value: String,
    val good: Boolean = false,
    val warn: Boolean = false,
    val line: String? = null,
)

/** PK-4 글 (parking-guide.js `headline`·`rowsOf`·`subOf`·`doneOf`). 결과는 회차 기록 `rec.parking`(엔진 note)만 읽는다. */
internal object ParkingDoneText {
    fun parkingOf(rec: SessionRecord): ParkingResult? = rec.parking

    /** 세워 두고 연습 기록(PK-2)인가. */
    fun isVirtual(rec: SessionRecord): Boolean = rec.isVirtualParking

    /** 다 마침: 실제 안내는 마지막 단계까지, 세워 두고 연습은 성공(parking-guide.js `doneOf`). */
    fun done(rec: SessionRecord): Boolean {
        val pk = parkingOf(rec) ?: return false
        return if (isVirtual(rec)) pk.result == RESULT_SUCCESS else pk.done
    }

    /** '평행 · 오른쪽 칸'. */
    fun title(rec: SessionRecord, res: TextRes): String {
        val pk = parkingOf(rec)
        return parkingTitle(pk?.type?.code, pk?.side?.code, res)
    }

    /** 결과 카드 머리 '평행 · 오른쪽 칸 주차를 마쳤어요' · '… 3단계까지 했어요' · '… · 세워 두고 연습'. */
    fun headline(rec: SessionRecord, res: TextRes): String {
        val t = title(rec, res)
        val pk = parkingOf(rec)
        return when {
            isVirtual(rec) -> res.text(R.string.pk4_head_virtual, t)
            pk?.done == true -> res.text(R.string.pk4_head_done, t)
            else -> res.text(R.string.pk4_head_reached, t, pk?.reached?.takeIf { it > 0 } ?: 1)
        }
    }

    /** 앱 바 부제 '평행 · 오른쪽 칸 · 1회차'(세워 두고 연습은 '… · 세워 두고 연습'). */
    fun sub(rec: SessionRecord, res: TextRes): String {
        val parts = mutableListOf(title(rec, res))
        if (isVirtual(rec)) {
            parts += res.text(R.string.pk4_sub_virtual)
        } else {
            rec.noInMode?.takeIf { it > 0 }?.let { parts += res.text(R.string.pk4_sub_no, it) }
        }
        return parts.joinToString(res.text(R.string.pk_rv_sep))
    }

    /** 코치 히어로 대사 'pk.done' — 실제 안내를 다 마친 회차만(세워 두고 연습은 없음). */
    fun heroKey(rec: SessionRecord): String? = if (done(rec) && !isVirtual(rec)) HERO_KEY else null

    /** 결과 줄 (설계 4.2 PK-4: 걸린 시간 · 단계 n/전체 · '천천히' 안내 n번 · 바퀴 · 사이드브레이크). */
    fun rows(m: ModeReviewModel, res: TextRes): List<ParkingDoneRow> {
        val rec = m.record
        val pk = parkingOf(rec) ?: ParkingResult()
        val line = { key: String -> ModeLines.render(key, m.coach, emptyMap(), m.honorific) }
        val rows = mutableListOf<ParkingDoneRow>()
        if (isVirtual(rec)) {
            val ok = pk.result == RESULT_SUCCESS
            rows += ParkingDoneRow("result", res.text(R.string.pk4_row_result), res.text(if (ok) R.string.pk4_result_success else R.string.pk4_result_stopped), good = ok)
            rows += ParkingDoneRow("time", res.text(R.string.pk4_row_time), fmtSec(pk.sec, res))
            rows += ParkingDoneRow("switch", res.text(R.string.pk4_row_switch), res.text(R.string.pk_times, pk.switches ?: 0))
            if (ok) {
                val straight = pk.wheelStraight != false
                rows += wheelRow(straight, res, if (straight) null else line(PK3))
            }
            return rows
        }
        val total = pk.total ?: 0
        rows += ParkingDoneRow("time", res.text(R.string.pk4_row_time), fmtSec(pk.sec, res))
        rows += ParkingDoneRow("steps", res.text(R.string.pk4_row_steps), res.text(R.string.pk4_steps, if (pk.done) total else pk.reached ?: 0, total), good = pk.done)
        val slow = res.text(R.string.pk4_row_slow)
        val n2 = pk.pk2 ?: 0
        rows += if (!rec.caps.speed) {
            ParkingDoneRow("slow", slow, res.text(R.string.pk4_unknown)) // 차속을 못 읽으면 PK2 꺼짐(설계 7.8)
        } else {
            ParkingDoneRow("slow", slow, if (n2 > 0) res.text(R.string.pk_times, n2) else res.text(R.string.pk4_slow_none), good = n2 == 0)
        }
        val w = pk.wheel
        rows += if (w == null) {
            ParkingDoneRow("wheel", res.text(R.string.pk4_row_wheel), res.text(R.string.pk4_unknown))
        } else {
            wheelRow(w.straight, res, if (w.straight) null else line(PK3))
        }
        val hb = pk.handbrake
        val on = hb != null && (hb.result == EventResult.OK || (hb.result == null && hb.on == true))
        val off = hb != null && (hb.result == EventResult.FAIL || (hb.result == null && hb.on == false))
        val hbLabel = res.text(R.string.pk4_row_handbrake)
        rows += when {
            on -> ParkingDoneRow("handbrake", hbLabel, res.text(R.string.pk4_hb_on), good = true)
            off -> ParkingDoneRow("handbrake", hbLabel, res.text(R.string.pk4_hb_off), warn = true, line = line(PB2))
            else -> ParkingDoneRow("handbrake", hbLabel, res.text(if (hb?.result == EventResult.INFO) R.string.pk4_hb_asked else R.string.pk4_unknown))
        }
        return rows
    }

    private fun wheelRow(straight: Boolean, res: TextRes, line: String?) = ParkingDoneRow(
        "wheel",
        res.text(R.string.pk4_row_wheel),
        res.text(if (straight) R.string.pk4_wheel_straight else R.string.pk4_wheel_bent),
        good = straight,
        warn = !straight,
        line = line,
    )

    private const val RESULT_SUCCESS = "success"
    const val HERO_KEY = "pk.done"
    private const val PK3 = "pk3"
    private const val PB2 = "pb2"
}
