package com.barodrive.core.engine.detect.license

import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.engine.review.LicenseCalc
import com.barodrive.core.engine.round1
import com.barodrive.core.model.ArrivedEvent
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Courses
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.LicenseAlone
import com.barodrive.core.model.LicenseHint
import com.barodrive.core.model.LicenseResult
import com.barodrive.core.model.RerouteEvent
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.TurnPassed
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WaypointEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * 운전면허 연습 모드 검출기 (프로토타입 detectors/license.js의 검출기 둘, 설계 6.12.3·5.9·6.5.3, 결정-56·57·63).
 * 24개 항목이 아니라 items 없음(항목으로는 꺼지지 않음). 화면 없는 계산(장면 수·구간 글·시험장 코스)은 [LicenseCalc].
 */

private val MODES = listOf(8)

/** '움직이는 중'으로 보는 속도(기어를 못 읽을 때, km/h). */
private const val MOVING_V = 0.5

/** 두 검출기가 나누는 회차 값 (license.js `shared(ctx)`): 지금 구간·구간 수·조수석이 빈 때. */
private class LicenseShared {
    var segment = 1
    var count: Int? = null
    val alone = ArrayList<LicenseAlone>()
}

/**
 * license.course 구간 진행 (license.js 'license.course'): 스냅숏 nav.segment · 경유지 사건(kind 'segment') · 도착 → 화면 힌트
 * hints.license(바뀔 때만). 완주 = 내비 도착이고 마지막 구간까지 왔음(강제 도착으로 1구간에서 끝나면 완주 아님, 결정사항 E T8-5).
 * 회차 끝 메모 rec.license = { courseId, placeId, letter, name, test, segments, reached, segmentsDone, complete, alone, aloneAt, scenes } —
 * 장면 수는 재탐색으로 놓친·건너뛴 회전을 빼고 센다(설계 6.9, 회차 끝에 코어가 excluded로 바꾸는 것과 같게).
 */
internal val LICENSE_COURSE = DetectorDef(id = "license.course", modes = MODES) { ctx ->
    val sh = ctx.shared(LicenseShared::class) { LicenseShared() }
    val course = Courses.course(ctx.cfg.courseId)
    val courseId = ctx.cfg.courseId?.takeIf { it.isNotEmpty() }
    val name = course?.name ?: ctx.cfg.dest?.takeIf { it.isNotEmpty() }
    var index = 1
    var count: Int? = course?.segments?.takeIf { it > 0 }
    var done = 0
    var arrived = false
    val dropped = HashSet<String>()
    var lastKey: String? = null

    fun complete(): Boolean = arrived && count.let { it == null || it <= 1 || index >= it }

    fun hint() {
        val cnt = count ?: 1
        val idx = index.coerceIn(1, cnt)
        val key = "$idx/$cnt:$done:${complete()}"
        if (key == lastKey) return
        lastKey = key
        val text = LicenseCalc.segmentText(idx, cnt)
        ctx.hints.license = LicenseHint(
            courseId = courseId, letter = course?.letter, name = name, segment = idx, count = cnt, done = done, complete = complete(),
            text = text, line = if (!name.isNullOrEmpty()) "$text · $name" else text, head = "면허 · $text",
        )
    }

    ctx.on<WaypointEvent> { w ->
        if (w.kind != "segment") return@on
        val n = w.segment ?: return@on
        if (n <= 0) return@on
        done = max(done, n)
        index = max(index, n + 1)
        count?.let { index = min(index, it) }
        sh.segment = index
        hint()
    }
    ctx.on<ArrivedEvent> {
        arrived = true
        hint()
    }
    // 재탐색으로 놓친·건너뛴 회전의 판정은 장면에서 뺀다(설계 6.9)
    ctx.on<TurnPassed> { p -> if (p.missed || p.skipped) dropped += p.id }
    ctx.on<RerouteEvent> { r -> r.turnId?.let { dropped += it } }
    hint()

    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            val sg = snap.nav?.segment
            if (sg != null && sg.count > 0) {
                count = sg.count
                if (sg.index > 0) index = max(index, min(sg.index, sg.count))
                sh.segment = index
                sh.count = sg.count
            }
            hint()
        }

        override fun finish(rec: RecordDraft) {
            val cnt = max(1, count ?: 1)
            val full = complete()
            val reached = if (full) cnt else index.coerceIn(1, cnt)
            ctx.noteLicense(
                LicenseResult(
                    courseId = courseId, placeId = ctx.cfg.placeId?.takeIf { it.isNotEmpty() }, letter = course?.letter, name = name,
                    test = course != null && LicenseCalc.isTestCourse(course.id), segments = cnt, reached = reached,
                    segmentsDone = if (full) cnt else max(done, reached - 1).coerceIn(0, cnt - 1), complete = full,
                    alone = sh.alone.size, aloneAt = sh.alone.toList(), scenes = LicenseCalc.countScenes(rec.events, rec.items, dropped),
                ),
            )
        }
    }
}

/**
 * license.alone 조수석 이탈 (license.js 'license.alone', 설계 5.9·6.5.3, 결정-57): 주행 중(기어가 P가 아님. 기어를 못 읽으면 움직일 때)
 * 조수석이 비면 — 빈 동안 한 번 — 판정 M2 fail `{alone, t, gear, speed, segment}` + 안전 카드 'lic.alone'(다음 신호 대기에 보이고
 * 맨 앞 카드 문장을 한 번 말함). 세션은 그대로 이어진다. 다시 앉았다 또 비면 또 한 번. 좌석을 못 읽으면 꺼짐(needs passenger).
 * M2는 24개 항목이 아니라 전달 방식이 늘 O(결정사항 E T1c-1).
 */
internal val LICENSE_ALONE = DetectorDef(id = "license.alone", needs = listOf(SignalKey.PASSENGER), modes = MODES) { ctx ->
    val sh = ctx.shared(LicenseShared::class) { LicenseShared() }
    var empty = false // 이번 '빈 동안'을 이미 기록했나
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            if (snap.passenger == true) {
                empty = false
                return
            }
            if (snap.passenger != false || empty) return
            val g = snap.gear
            val v = snap.speed
            val moving = v != null && abs(v) > MOVING_V
            if (if (g != null) g == Gear.P else !moving) return // 주행 중만(정차 P에서 내린 것은 다시 달릴 때 셈)
            empty = true
            val idx = snap.nav?.segment?.index
            val seg = if (idx != null && idx > 0) idx else sh.segment
            val row = LicenseAlone(t = round1(snap.t), gear = g, speed = v?.let(::round1), segment = seg.takeIf { it != 0 })
            sh.alone += row
            ctx.emit("M2", EventResult.FAIL, EventData(alone = true, t = row.t, gear = g, speed = row.speed, segment = row.segment))
            ctx.card(
                item = "M2", key = "lic.alone", icon = "i-seat", category = CardCategory.SAFETY, title = LicenseCalc.Text.CARD_TITLE,
                detail = LicenseCalc.Text.CARD_DETAIL,
            )
        }
    }
}
