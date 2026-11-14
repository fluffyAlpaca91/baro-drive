package com.barodrive.core.engine.review

import com.barodrive.core.model.Course
import com.barodrive.core.model.CourseDoneUpdate
import com.barodrive.core.model.Courses
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.LicenseCourseResult
import com.barodrive.core.model.LicenseLetter
import com.barodrive.core.model.LicenseProgress
import com.barodrive.core.model.LicenseReview
import com.barodrive.core.model.LicenseScene
import com.barodrive.core.model.LicenseSceneCounts
import com.barodrive.core.model.Mode
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord

/**
 * 운전면허 연습 모드의 코스 진행·시험 기준 장면·RV-5 값 (프로토타입 js/engine/detectors/license.js `BaroEngine.license`의
 * 화면 없는 함수, 설계 6.12.3, 4.2 RV-5, 결정-56·57·63). 유효기간·LC-1 시작 판단은 [LicenseStart].
 *
 * 면허 검출기(license.course — 회차 끝 장면 수 메모)도 [countScenes]·[segmentText]·[isTestCourse]를 쓸 수 있게 공개한다.
 *
 * 장면 매핑(설계 6.12.3) — 판정 기록(결과 fail·late, 출발 전 판정·빠진 회전 제외)과 본인 확인으로 센다:
 * 방향지시등 없이 회전 = C5 fail + C2 fail(안 켬) 중 같은 회전의 C5 판정이 없는 것 · 회전 신호가 늦음 = C2 late + C2 fail인데
 * 같은 회전 C5가 ok · 급브레이크 = F1 fail + 급제동 본인 확인 '내 실수'(결정-54) · 제한속도 초과 = E1 fail(어린이보호구역 밖) ·
 * 어린이보호구역 속도 초과 = E2 fail + 어린이보호구역 안 E1 fail 중 같은 구역 E2가 없는 것 · 급출발 = B1 fail.
 * 그 회차에 '안 하기'(X)로 둔 항목의 판정은 넣지 않는다. 판정 기록이 없는 회차(미리 만든 상태)는 `rec.license.scenes` 개수.
 */
object LicenseCalc {
    /** 장면 id와 이름(license.js `SCENES`), RV-5 줄 순서. */
    val SCENES: List<Pair<String, String>> = listOf(
        "noSignal" to "방향지시등 없이 회전",
        "lateSignal" to "회전 신호가 늦음",
        "hardBrake" to "급브레이크",
        "speed" to "제한속도 초과",
        "school" to "어린이보호구역 속도 초과",
        "hardStart" to "급출발",
    )

    /** RV-5·안전 카드 글(license.js `TEXT`). */
    object Text {
        const val SCENE_TITLE = "시험 기준으로 다시 볼 장면"
        const val SCENE_SUB = "점수가 아니에요. 도로주행시험 채점기준(시행규칙 별표 26)에 나오는 장면을 모았어요."
        const val SCENE_NONE = "다시 볼 장면이 없어요"
        const val ALONE = "조수석이 빈 채로 달린 때가 있었어요 · 동승자 없이 연습하면 안 돼요"
        const val CARD_TITLE = "조수석이 비었어요"
        const val CARD_DETAIL = "동승자 없이 연습하면 안 돼요"
    }

    // ================================================================ 코스 (설계 8.2, 결정-63)

    /** 시험장 주변 코스 A~D (license.js `testCourses`: 모드 8, own 아님, 글자 있음 — 글자 순). */
    fun testCourses(): List<Course> =
        Courses.all.filter { it.mode == Mode.LICENSE.no && !it.own && it.letter.isNotEmpty() }.sortedBy { it.letter }

    fun isTestCourse(courseId: String?): Boolean = testCourses().any { it.id == courseId }

    /** 'A' · 'c8-A' → 'A'(시험장 코스 글자만, license.js `letterOf`). 모르는 값은 null. */
    fun letterOf(value: String?): String? = value?.let { s -> testCourses().firstOrNull { it.letter == s || it.id == s }?.letter }

    /** '구간 2/3' (license.js `segmentText`). */
    fun segmentText(index: Int?, count: Int?): String {
        val n = maxOf(1, count?.takeIf { it != 0 } ?: 1)
        return "구간 ${(index?.takeIf { it != 0 } ?: 1).coerceIn(1, n)}/$n"
    }

    // ================================================================ 장면 (설계 6.12.3, 4.2 RV-5)

    /**
     * 장면 수 (license.js `countScenes(events, items, dropped)`). [items] = 그 회차 전달 방식(X 항목은 세지 않음),
     * [dropped] = 빼야 할 회전 id(검출기가 회차 끝 전에 셀 때 — 재탐색으로 놓친·건너뛴 회전).
     */
    fun countScenes(events: List<JudgeEvent>, items: Map<String, Delivery>, dropped: Set<String>? = null): LicenseSceneCounts {
        fun on(id: String) = items[id] != Delivery.OFF
        val l = events.filter { e ->
            e.isJudged && !(dropped != null && e.data["turnId"] != null && e.data.jsString("turnId") in dropped)
        }
        val c5 = HashMap<String, EventResult>() // 회전 id → C5 결과(같은 회전에 둘이면 fail 우선)
        for (e in l) {
            if (e.item != "C5" || e.data["turnId"] == null) continue
            val k = e.data.jsString("turnId")
            if (c5[k] != EventResult.FAIL) c5[k] = e.result
        }
        val e2Zones = l.filter { it.item == "E2" && it.result == EventResult.FAIL }.map { it.data.jsString("zoneId") }.toSet()
        var noSignal = 0
        var lateSignal = 0
        var hardBrake = 0
        var speed = 0
        var school = 0
        var hardStart = 0
        for (e in l) {
            val x = e.data
            val failed = e.result == EventResult.FAIL
            when (e.item) {
                "C5" -> if (failed && on("C5")) noSignal++
                "C2" -> if (on("C2")) {
                    if (e.result == EventResult.LATE) {
                        lateSignal++
                    } else if (failed) {
                        val r = if (on("C5") && x["turnId"] != null) c5[x.jsString("turnId")] else null
                        when (r) {
                            EventResult.FAIL -> Unit // 같은 회전을 C5가 '깜빡이 없이 회전'으로 셈
                            EventResult.OK -> lateSignal++ // 깜빡이는 켰지만 회전 지점을 지나서
                            else -> noSignal++ // C5 판정이 없는 회전
                        }
                    }
                }
                "F1" -> if (failed && on("F1")) hardBrake++
                "B1" -> if (failed && on("B1")) hardStart++
                "E2" -> if (failed) school++
                "E1" -> if (failed) {
                    if (x.truthy("school")) {
                        if (x.jsString("zoneId") !in e2Zones) school++
                    } else {
                        speed++
                    }
                }
                else -> Unit
            }
        }
        return LicenseSceneCounts(noSignal, lateSignal, hardBrake, speed, school, hardStart)
    }

    /** 급제동 본인 확인 '내 실수' 수(결정-54). */
    private fun f2Mistakes(record: SessionRecord): Int = record.f2.count { it.choice == F2Choice.MISTAKE }

    /** 장면 수 (license.js `sceneCounts`): 판정이 있으면 판정으로, 없으면 `rec.license.scenes` — 급브레이크에 '내 실수'를 더한다. */
    fun sceneCounts(record: SessionRecord): LicenseSceneCounts {
        val hasEvents = record.events.any { it.isJudged }
        val src = if (hasEvents) countScenes(record.events, record.items) else record.license?.scenes ?: LicenseSceneCounts()
        return LicenseSceneCounts(
            noSignal = maxOf(0, src.noSignal),
            lateSignal = maxOf(0, src.lateSignal),
            hardBrake = maxOf(0, src.hardBrake) + f2Mistakes(record),
            speed = maxOf(0, src.speed),
            school = maxOf(0, src.school),
            hardStart = maxOf(0, src.hardStart),
        )
    }

    private fun LicenseSceneCounts.of(id: String): Int = when (id) {
        "noSignal" -> noSignal
        "lateSignal" -> lateSignal
        "hardBrake" -> hardBrake
        "speed" -> speed
        "school" -> school
        "hardStart" -> hardStart
        else -> 0
    }

    /** 시험 기준으로 다시 볼 장면 (license.js `scenes`): '급브레이크 1번' — 횟수만, 점수·감점 말 없음(결정-56). 0번인 장면은 뺀다. */
    fun scenes(record: SessionRecord): List<LicenseScene> {
        val c = sceneCounts(record)
        return SCENES.mapNotNull { (id, label) ->
            val n = c.of(id)
            if (n > 0) LicenseScene(id = id, label = label, n = n, text = "$label ${n}번") else null
        }
    }

    // ================================================================ 코스 진행 (설계 4.2 RV-5, 6.12.3)

    /**
     * RV-5 코스 진행 카드 첫 줄 (license.js `courseResult`): 완주 '시험장 주변 C 코스를 끝까지 달렸어요' ·
     * '2구간까지 달렸어요(완주 아님)' · 한 구간 코스 '목적지까지 가지 않았어요(완주 아님)'. 면허 메모가 없으면 코스 데이터와 도착으로.
     */
    fun courseResult(record: SessionRecord): LicenseCourseResult {
        val lic = record.license
        val courseId = lic?.courseId?.takeIf { it.isNotEmpty() } ?: record.courseId?.takeIf { it.isNotEmpty() }
        val c = Courses.course(courseId)
        val count = maxOf(1, lic?.segments?.takeIf { it != 0 } ?: c?.segments?.takeIf { it != 0 } ?: 1)
        val complete = lic?.complete ?: record.arrived
        val reached = (lic?.reached?.takeIf { it != 0 } ?: if (complete) count else 1).coerceIn(1, count)
        val segmentsDone = if (lic != null) lic.segmentsDone.coerceIn(0, count) else if (complete) count else reached - 1
        val letter = lic?.letter?.takeIf { it.isNotEmpty() } ?: c?.letter?.takeIf { it.isNotEmpty() }
        val name = lic?.name?.takeIf { it.isNotEmpty() } ?: c?.name?.takeIf { it.isNotEmpty() }
        val text = when {
            complete -> Josa.josa(name ?: "이 코스", "을/를") + " 끝까지 달렸어요"
            count > 1 -> "${reached}구간까지 달렸어요(완주 아님)"
            else -> "목적지까지 가지 않았어요(완주 아님)"
        }
        return LicenseCourseResult(
            courseId = courseId, letter = letter, name = name, test = isTestCourse(courseId), count = count, reached = reached,
            segmentsDone = segmentsDone, complete = complete, text = text,
        )
    }

    /** 완주한 코스 글자(A~D 순, 겹치지 않음, license.js `coursesAfter`). [record]가 시험장 코스 완주면 그 글자를 더한다. */
    fun coursesAfter(done: List<String>?, record: SessionRecord?): List<String> {
        val set = HashSet<String>()
        for (x in done.orEmpty()) letterOf(x)?.let { set += it }
        if (record != null) {
            val res = courseResult(record)
            val letter = res.letter
            if (res.complete && res.test && letter != null) set += letter
        }
        return testCourses().map { it.letter }.filter { it in set }
    }

    /** 시험장 코스 진행 (license.js `progress(done)`): '4개 코스 중 2개', 글자 A~D마다 완주 여부. */
    fun progress(done: List<String>?): LicenseProgress {
        val list = testCourses()
        val set = coursesAfter(done, null).toSet()
        val letters = list.map { LicenseLetter(letter = it.letter, courseId = it.id, done = it.letter in set) }
        val n = letters.count { it.done }
        return LicenseProgress(done = n, total = list.size, letters = letters, text = "${list.size}개 코스 중 ${n}개")
    }

    /**
     * 회차 끝 완주 반영 (license.js `applyCourseDone(profile, rec)`). 프로필은 고치지 않는다 — 결과 `after`를
     * `profile.license.coursesDone`에 넣는 것은 부르는 쪽. 같은 기록을 두 번 넣어도 같다.
     */
    fun applyCourseDone(profile: Profile, record: SessionRecord): CourseDoneUpdate {
        val before = profile.license.coursesDone
        val after = coursesAfter(before, record)
        val prev = coursesAfter(before, null).toSet()
        return CourseDoneUpdate(before = before, after = after, added = after.firstOrNull { it !in prev }, progress = progress(after))
    }

    /** RV-5가 그릴 값 묶음 (license.js `review(rec, profile)`). */
    fun review(record: SessionRecord, profile: Profile?): LicenseReview {
        val done = coursesAfter(profile?.license?.coursesDone, record)
        val alone = maxOf(0, record.license?.alone ?: 0)
        return LicenseReview(
            course = courseResult(record),
            progress = progress(done),
            coursesDone = done,
            scenes = scenes(record),
            sceneTitle = Text.SCENE_TITLE,
            sceneSub = Text.SCENE_SUB,
            sceneNone = Text.SCENE_NONE,
            alone = alone,
            aloneText = if (alone > 0) Text.ALONE else null,
        )
    }
}
