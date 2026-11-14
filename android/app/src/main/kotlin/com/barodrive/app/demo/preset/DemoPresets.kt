package com.barodrive.app.demo.preset

import com.barodrive.app.data.repo.StoreRules
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.api.RenderOptions
import com.barodrive.core.engine.api.ResolveOptions
import com.barodrive.core.engine.api.ScoreCounts
import com.barodrive.core.engine.content.ParkingGuide
import com.barodrive.core.model.AppState
import com.barodrive.core.model.Category
import com.barodrive.core.model.Consent
import com.barodrive.core.model.Courses
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.DemoPanel
import com.barodrive.core.model.DemoPresetInfo
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.FeedbackPreset
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillDownRun
import com.barodrive.core.model.HillDownSummary
import com.barodrive.core.model.HillParkResult
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillResult
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.HillTaskCount
import com.barodrive.core.model.HillUpAttempt
import com.barodrive.core.model.HillUpSummary
import com.barodrive.core.model.ItemStat
import com.barodrive.core.model.LicenseAge
import com.barodrive.core.model.LicenseResult
import com.barodrive.core.model.LicenseSceneCounts
import com.barodrive.core.model.MonthlyDrives
import com.barodrive.core.model.Onboarding
import com.barodrive.core.model.OnboardingAnswers
import com.barodrive.core.model.ParkingHandbrake
import com.barodrive.core.model.ParkingResult
import com.barodrive.core.model.ParkingStepLog
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.ParkingWheel
import com.barodrive.core.model.Profile
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.ReviewTip
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.ShareState
import com.barodrive.core.model.ShareStatus
import com.barodrive.core.model.StageProgress
import com.barodrive.core.model.StageState
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.UiMemory
import com.barodrive.core.model.UtteranceLog
import com.barodrive.core.model.WeatherType
import com.barodrive.core.sim.util.SeedRandom
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 미리 만든 데모 상태 11개 (프로토타입 js/data/presets.js, 설계 8.3·8.1, 결정-79·80, 결정 T1c-Q1). 시연 패널 '데모 도구'에서 고르고
 * [적용]하면 앱 저장을 이 상태로 바꾼다([build]가 null이면 저장을 지움 = 처음 실행).
 *
 * 날짜는 '오늘'에서 거꾸로 센다(결정-80). 같은 오늘·같은 시드면 결과가 늘 같다(난수 = `rng('preset:' + id)`, 프로토타입과 같은 수열).
 * 회차 기록은 실제 회차와 같은 모양이되 요약만 있다(events·utterances 비움). 오래된 것부터 그때 프로필로 전달 방식(resolveItems)·
 * 요약(review.compute) → 모드 5는 점수(score.fromCounts) → 진행 반영(review.applyProgress) → 프로필 sessions 맨 앞. 그래서 홈·리뷰·PR-1·FB-1이
 * 실제로 달린 것과 같은 값을 그린다. 모드 전용 결과도 넣는다(자율 score · 주차 parking · 가상 주차 · 언덕 hill · 면허 license).
 * 패널 값([DemoPresetInfo.panel])은 네트워크·동네·혼잡 시드·다른 앱·프로필 — 신호 지원은 데모 상태와 따로라 건드리지 않는다.
 */
object DemoPresets {
    /** 설계 8.3 순서의 목록(id·이름·설명·패널 값). */
    val list: List<DemoPresetInfo> by lazy { defs.map { it.info } }

    /** id로 찾기. */
    fun info(id: String?): DemoPresetInfo? = defs.firstOrNull { it.info.id == id }?.info

    /**
     * [id] 상태를 [today](그 지역 [zone])로 만든다. null = 저장을 지운다(처음 실행). 모르는 id면 [IllegalArgumentException].
     */
    fun build(id: String, today: LocalDate, zone: ZoneId): AppState? {
        val def = requireNotNull(defs.firstOrNull { it.info.id == id }) { "데모 상태 없음: $id" }
        return def.build(Ctx(today, zone, SeedRandom.of("preset:$id")))
    }

    // ================================================================ 정의

    private class Def(val info: DemoPresetInfo, val build: (Ctx) -> AppState?)

    private val BASE_PANEL = DemoPanel(network = true, town = "home", congestionSeed = 1, otherApp = false, profileId = "p1")

    private fun def(id: String, order: Int, title: String, desc: String, panel: DemoPanel = BASE_PANEL, build: (Ctx) -> AppState?) =
        Def(DemoPresetInfo(id = id, title = title, order = order, desc = desc, panel = panel), build)

    private val defs: List<Def> = listOf(
        def("first-run", 10, "처음 실행", "저장 없음 → 첫 화면(ON-1)부터") { null },
        def("stage1-session1", 20, "1단계 1회차 마침", "1단계 · 회차 1개(과제 75%, 통과 0/2)") { c ->
            var p = c.baseProfile(since = 1, stage = 1, answers = answers(LicenseAge.UNDER_1, MonthlyDrives.NONE))
            p = c.drills(p, S1_FIRST.at(1, 18, 10))
            appState(p)
        },
        def("stage1-almost", 30, "1단계 통과 직전", "1단계 · 회차 2개(통과 1/2) · 능숙으로 한 번 더 하면 통과") { c ->
            var p = c.baseProfile(since = 4, stage = 1, myCourses = true, answers = answers(LicenseAge.UNDER_1, MonthlyDrives.NONE))
            p = c.drills(p, S1_FIRST.at(4, 18, 10), S1_PASS.at(1, 19, 20))
            appState(p)
        },
        def(
            "stage2-session3", 40, "2단계 3회차",
            "1단계 통과(3회) · 2단계 2회(통과 1/2) · 다음은 3회차라 회전 사전 안내(C1)를 안 함 · 출발(B1)은 리포트로만",
        ) { c ->
            var p = c.baseProfile(since = 12, stage = 1, myCourses = true, answers = answers(LicenseAge.UNDER_1, MonthlyDrives.FEW))
            p = c.drills(
                p,
                S1_FIRST.at(12, 18, 10), S1_PASS.at(10, 19, 0), S1_PASS2.at(8, 9, 30),
                S2_MISS.at(5, 18, 40).copy(x = linkedMapOf("B1" to (3 to 3))), S2_PASS.at(2, 10, 15).copy(x = linkedMapOf("B1" to (4 to 4))),
            )
            appState(p)
        },
        def("stage4-almost", 50, "4단계 통과 직전", "1~3단계 통과 · 4단계 통과 1/2") { c ->
            var p = c.baseProfile(since = 26, stage = 1, myCourses = true, answers = answers(LicenseAge.UNDER_1, MonthlyDrives.FEW))
            p = c.drills(
                p,
                S1_FIRST.at(26, 18, 10), S1_PASS.at(24, 19, 0), S1_PASS2.at(22, 9, 30),
                S2_PASS.at(19, 18, 40), S2_MISS.at(17, 10, 15), S2_PASS2.at(15, 17, 50),
                S3_PASS.at(11, 19, 10), S3_PASS2.at(8, 8, 40).copy(weather = RAIN),
                S4_PASS.at(3, 14, 20),
            )
            appState(p)
        },
        def("graduated", 60, "졸업 후", "1~4단계 통과(합계 12회) · 자율 2회 · 주차·언덕도 한 번씩") { c ->
            var p = c.baseProfile(
                since = 44, stage = 1, myCourses = true, family = true, familyName = "아버지",
                answers = answers(LicenseAge.UNDER_1, MonthlyDrives.FEW),
            )
            p = c.drills(
                p,
                S1_FIRST.at(44, 18, 10), S1_PASS.at(42, 19, 0), S1_PASS2.at(40, 9, 30),
                S2_PASS.at(37, 18, 40), S2_MISS.at(35, 10, 15).copy(weather = RAIN), S2_PASS2.at(33, 17, 50),
            )
            p = c.virtualParking(p, ago = 31, hh = 11, mm = 5, type = ParkingType.PARALLEL, side = TurnDir.RIGHT, sec = 96.0, switches = 3)
            p = c.virtualParking(p, ago = 31, hh = 11, mm = 25, type = ParkingType.BACK, side = TurnDir.RIGHT, sec = 74.0, switches = 2)
            p = c.parkingRecord(p, ago = 30, hh = 15, mm = 0, type = ParkingType.PARALLEL, side = TurnDir.RIGHT)
            p = c.drills(p, S3_PASS.at(27, 19, 10), S3_MISS.at(25, 8, 40).copy(weather = RAIN), S3_PASS2.at(22, 18, 20))
            p = c.hillRecord(p, ago = 20, hh = 10, mm = 30)
            p = c.drills(
                p,
                S4_PASS.at(16, 14, 20), S4_MISS.at(13, 9, 0).copy(weather = SessionWeather(type = WeatherType.SNOW, cold = true)),
                S4_PASS2.at(10, 15, 40),
            )
            p = c.autoRecord(p, Auto(6, 18, 30, place = "company", presetId = "ex-commute", counts = ScoreCounts(late = 2, almost = 1, c4 = 1, bf = 1, g1Points = 6), c2 = 44.0))
            p = c.autoRecord(p, Auto(2, 11, 0, place = "mart", presetId = "ex-commute", counts = ScoreCounts(late = 1, almost = 2, c4 = 1, bf = 1, g1Points = 4, f2 = 1), c2 = 47.0))
            appState(p)
        },
        def(
            "autonomous-3", 70, "자율 기록 3회",
            "2단계 진행 중 · 자율 3회(78·82(비)·86점) · 내 설정 3개(출퇴근용·비 오는 날·주말 장보기)",
        ) { c ->
            var p = c.baseProfile(since = 15, stage = 2, myCourses = true, answers = answers(LicenseAge.OVER_3, MonthlyDrives.FEW))
            val weekend = FeedbackPreset(
                id = "my-weekend", name = "주말 장보기", example = false,
                items = LinkedHashMap(p.myPresets[0].items).apply {
                    put("C2", Delivery.NOW)
                    put("C4", Delivery.NOW)
                    put("B1", Delivery.NOW)
                },
            )
            p = p.copy(myPresets = p.myPresets + weekend)
            p = c.drills(p, S2_MISS.at(15, 18, 40))
            p = c.autoRecord(p, Auto(11, 8, 20, place = "company", presetId = "ex-commute", counts = ScoreCounts(late = 2, almost = 2, c4 = 1, bf = 2, g1Points = 6), c2 = 41.0))
            p = c.autoRecord(p, Auto(6, 8, 10, place = "company", presetId = "ex-rain", weather = RAIN, counts = ScoreCounts(late = 2, almost = 1, c4 = 1, bf = 1, g1Points = 6), c2 = 44.0))
            p = c.autoRecord(p, Auto(1, 10, 40, place = "mart", presetId = "my-weekend", counts = ScoreCounts(late = 1, almost = 2, c4 = 1, bf = 1, g1Points = 4, f2 = 1), c2 = 47.0))
            appState(p)
        },
        def(
            "license-2of4", 80, "운전면허 연습 2/4",
            "연습면허 발급일 = 오늘에서 224일 전(오늘이 2026-11-11이면 2026-04-01, D-140) · A·B 코스 완주 · 면허 회차 2개",
        ) { c ->
            var p = c.baseProfile(since = 9, stage = 1, answers = answers(LicenseAge.LEARNER, MonthlyDrives.FEW))
            p = p.copy(license = p.license.copy(issueDate = c.ymd(224)))
            val l = Drill(mode = 8, t = tasks("T-LIMIT" to (3 to 3), "T-SIGNAL30" to (4 to 5), "T-WAIT3" to (3 to 4), "T-STOPSIG" to (4 to 5)), c2 = 46.0, g1 = 0.95, k6 = Triple(4.0, 5, 2))
            p = c.drills(
                p,
                l.at(9, 16, 10).copy(course = "c8-A", license = licenseNote("A", listOf("A"), LicenseSceneCounts(lateSignal = 1, hardStart = 1))),
                l.at(3, 15, 30).copy(
                    course = "c8-B",
                    t = tasks("T-LIMIT" to (4 to 4), "T-SIGNAL30" to (5 to 6), "T-WAIT3" to (4 to 4), "T-STOPSIG" to (4 to 5)),
                    c2 = 51.0, g1 = 0.9, license = licenseNote("B", listOf("A", "B"), LicenseSceneCounts(lateSignal = 1)),
                ),
            )
            appState(p.copy(license = p.license.copy(coursesDone = listOf("A", "B"))))
        },
        def("license-expired", 90, "운전면허 유효기간 지남", "발급일이 오늘 기준 1년 1개월 전 · 면허 모드(8) 잠김") { c ->
            var p = c.baseProfile(since = 330, stage = 1, answers = answers(LicenseAge.LEARNER, MonthlyDrives.FEW))
            p = p.copy(license = p.license.copy(issueDate = c.jsDate(c.today.year - 1, c.today.monthValue - 1 - 1, c.today.dayOfMonth).format(YMD)))
            p = c.drills(
                p,
                Drill(
                    mode = 8, course = "c8-A", ago = 330, hh = 16, mm = 0,
                    t = tasks("T-LIMIT" to (3 to 3), "T-SIGNAL30" to (4 to 5), "T-WAIT3" to (3 to 4), "T-STOPSIG" to (4 to 5)), c2 = 46.0, g1 = 0.95,
                    license = licenseNote("A", listOf("A"), LicenseSceneCounts(lateSignal = 1)),
                ),
            )
            appState(p.copy(license = p.license.copy(coursesDone = listOf("A"))))
        },
        def(
            "needs-check", 100, "확인할 것 있음",
            "마지막 2단계 회차에 확인 안 한 급제동 1건 · 보내지 못한 요약 1건 · 패널 네트워크 끊김",
            panel = BASE_PANEL.copy(network = false),
        ) { c ->
            var p = c.baseProfile(
                since = 9, stage = 1, myCourses = true, family = true, familyName = "아버지",
                answers = answers(LicenseAge.UNDER_1, MonthlyDrives.FEW),
            )
            p = c.drills(
                p,
                S1_FIRST.at(9, 18, 10), S1_PASS.at(7, 19, 0), S1_PASS2.at(5, 9, 30),
                S2_MISS.at(1, 18, 40).copy(f2 = listOf(f2Row(1).copy(t = 412.3, v0 = 28.0, dur = 1.4, maxDrop1s = 12.6))),
            )
            val last = p.sessions[0].copy(share = ShareState(status = ShareStatus.QUEUED, to = p.consent.familyName))
            p = p.copy(sessions = listOf(last) + p.sessions.drop(1), shareQueue = listOfNotNull(last.id))
            appState(p)
        },
        def(
            "moved-town", 110, "코스 없는 동네", "2단계 진행 중 · 패널 동네 = 이사 간 동네(팀 코스 없음)",
            panel = BASE_PANEL.copy(town = "moved"),
        ) { c ->
            var p = c.baseProfile(since = 6, stage = 2, myCourses = true, answers = answers(LicenseAge.OVER_3, MonthlyDrives.FEW))
            p = c.drills(p, S2_PASS.at(6, 18, 40))
            appState(p)
        },
    )

    // ================================================================ 자주 쓰는 회차 묶음 (presets.js S1_FIRST …)

    /** 단계 연습·면허 회차 하나(presets.js `drillRecord`의 x). [t] = 과제 → (성공, 판정), [x] = 과제에 없는 항목 판정 수. */
    private data class Drill(
        val mode: Int,
        val course: String? = null,
        val t: List<Pair<String, Pair<Int, Int>>>,
        val c2: Double? = null,
        val g1: Double,
        /** K6: 10분당 · 칭찬 · 교정(없으면 4.2 · 6 · 2). */
        val k6: Triple<Double, Int, Int>? = null,
        val ago: Int = 0,
        val hh: Int = 0,
        val mm: Int = 0,
        val x: Map<String, Pair<Int, Int>>? = null,
        val f2: List<F2Check>? = null,
        val weather: SessionWeather? = null,
        val license: LicenseResult? = null,
    ) {
        fun at(ago: Int, hh: Int, mm: Int) = copy(ago = ago, hh = hh, mm = mm)
    }

    private fun tasks(vararg t: Pair<String, Pair<Int, Int>>) = t.toList()

    private val RAIN = SessionWeather(type = WeatherType.RAIN)

    // 1단계: 첫 회차(75%, 통과 아님) — K4 기준(깜빡이 38m · 급정지 3/8 · 핸들 1.1°)이 이 회차에서 정해진다
    private val S1_FIRST = Drill(1, "c1-B", tasks("T-START" to (3 to 4), "T-STOP" to (5 to 8), "T-SIGNAL1" to (4 to 4)), 38.0, 1.1, Triple(5.6, 5, 3))
    private val S1_PASS = Drill(1, "c1-B", tasks("T-START" to (4 to 4), "T-STOP" to (6 to 7), "T-SIGNAL1" to (4 to 5)), 44.0, 0.98, Triple(5.1, 6, 2))
    private val S1_PASS2 = Drill(1, "c1-A", tasks("T-START" to (5 to 5), "T-STOP" to (7 to 8), "T-SIGNAL1" to (5 to 5)), 47.0, 0.92, Triple(4.8, 6, 1))

    // 2단계: 30 구역을 한 번 넘은 회차(65%) · 통과 회차(83%)
    private val S2_MISS = Drill(2, "c2-B", tasks("T-30" to (1 to 2), "T-SIGNAL30" to (4 to 6), "T-WAIT3" to (3 to 4), "T-STOP" to (3 to 5)), 45.0, 0.9, Triple(4.4, 4, 2))
    private val S2_PASS = Drill(2, "c2-B", tasks("T-30" to (2 to 2), "T-SIGNAL30" to (5 to 6), "T-WAIT3" to (4 to 5), "T-STOP" to (4 to 5)), 52.0, 0.84, Triple(4.2, 6, 2))
    private val S2_PASS2 = Drill(2, "c2-A", tasks("T-30" to (3 to 3), "T-SIGNAL30" to (5 to 5), "T-WAIT3" to (4 to 5), "T-STOP" to (5 to 6)), 55.0, 0.81, Triple(3.9, 6, 1))

    // 3단계
    private val S3_PASS = Drill(3, "c3-A", tasks("T-LIMIT" to (4 to 4), "T-SIGNALPRE" to (4 to 5), "T-STOPSIG" to (5 to 6)), 58.0, 0.8, Triple(2.3, 3, 1))
    private val S3_MISS = Drill(3, "c3-C", tasks("T-LIMIT" to (3 to 4), "T-SIGNALPRE" to (3 to 5), "T-STOPSIG" to (4 to 6)), 49.0, 0.83, Triple(2.6, 2, 2))
    private val S3_PASS2 = Drill(3, "c3-A", tasks("T-LIMIT" to (5 to 5), "T-SIGNALPRE" to (5 to 5), "T-STOPSIG" to (5 to 6)), 61.0, 0.78, Triple(2.1, 3, 1))

    // 4단계
    private val S4_PASS = Drill(4, "c4-A", tasks("T-IC" to (3 to 4), "T-LIMIT" to (3 to 3), "T-NOBRAKE" to (3 to 3)), 118.0, 0.76, Triple(1.3, 1, 1))
    private val S4_MISS = Drill(4, "c4-B", tasks("T-IC" to (2 to 4), "T-LIMIT" to (2 to 3), "T-NOBRAKE" to (2 to 3)), 104.0, 0.8, Triple(1.6, 1, 2))
    private val S4_PASS2 = Drill(4, "c4-A", tasks("T-IC" to (4 to 4), "T-LIMIT" to (3 to 3), "T-NOBRAKE" to (3 to 3)), 121.0, 0.74, Triple(1.1, 1, 0))

    /** 자율 회차(presets.js `autoRecord`의 x). */
    private data class Auto(
        val ago: Int,
        val hh: Int,
        val mm: Int,
        val place: String,
        val presetId: String,
        val counts: ScoreCounts,
        val c2: Double?,
        val weather: SessionWeather? = null,
    )

    /** 과제 → 그 과제를 세는 항목(설계 6.4) — 요약만 있는 기록의 itemStats(도움 줄여 가기)를 과제에서 만든다. */
    private val TASK_ITEM = mapOf(
        "T-START" to "B1", "T-STOP" to "F1", "T-STOPSIG" to "F1", "T-NOBRAKE" to "F1", "T-SIGNAL1" to "C5", "T-30" to "E1",
        "T-LIMIT" to "E1", "T-SIGNAL30" to "C2", "T-SIGNALPRE" to "C2", "T-IC" to "C2", "T-WAIT3" to "C4",
    )

    /** 과제에 없는 항목의 판정 수(모드별 기본) — 내려가지 않게(80% 아래) 두거나 그 모드에서 '완료 후'라 상관없는 값. */
    private val EXTRA: Map<Int, Map<String, Pair<Int, Int>>> = mapOf(
        1 to linkedMapOf("C2" to (2 to 3), "C4" to (2 to 3)),
        2 to linkedMapOf("B1" to (3 to 3), "C5" to (3 to 4)),
        3 to linkedMapOf("B1" to (3 to 3), "C4" to (2 to 3), "C5" to (3 to 4)),
        4 to linkedMapOf("B1" to (2 to 2)),
        8 to linkedMapOf("B1" to (2 to 3), "C5" to (3 to 4)),
    )
    private val STAGE_COURSES = mapOf(1 to "c1-B", 2 to "c2-B", 3 to "c3-A", 4 to "c4-A")
    private const val MAX_RECORDS = Profile.MAX_RECORDS
    private val WX_ICON = mapOf(WeatherType.RAIN to "i-rain", WeatherType.SNOW to "i-snow")
    private const val ICON_SEVERE = "i-hazard"
    private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val ID_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    private fun answers(age: LicenseAge, drives: MonthlyDrives) = OnboardingAnswers(licenseAge = age, monthlyDrives = drives)

    private fun appState(p: Profile) = AppState(version = 1, activeProfileId = p.id, ui = UiMemory(), profiles = mapOf(p.id to p))

    /** 급제동(F2) 줄 — 설계 8.1 f2. */
    private fun f2Row(n: Int) = F2Check(id = "f2-$n", t = 300.0 + n * 37, v0 = 28.0, v1 = 0.0, dur = 1.4, maxDrop1s = 12.6, stopped = true, choice = null)

    /** 면허 회차의 rec.license(T8-10). */
    private fun licenseNote(letter: String, doneAfter: List<String>, scenes: LicenseSceneCounts): LicenseResult {
        val c = Courses.course("c8-$letter")
        return LicenseResult(
            courseId = "c8-$letter", placeId = null, letter = letter, name = c?.name ?: "시험장 주변 $letter 코스", test = true,
            segments = 3, reached = 3, segmentsDone = 3, complete = true, alone = 0, aloneAt = emptyList(), scenes = scenes,
            coursesDoneAfter = doneAfter,
        )
    }

    private fun r2(x: Double): Double = Math.round(x * 100) / 100.0

    // ================================================================ 만들기 (presets.js 함수들)

    /** 만들 때 쓰는 오늘·시간대·난수. */
    private class Ctx(val today: LocalDate, val zone: ZoneId, val rng: SeedRandom) {
        /** 오늘에서 [ago]일 전 [hh]:[mm](분이 넘치면 JS Date처럼 넘김). */
        fun dayAt(ago: Int, hh: Int, mm: Int): ZonedDateTime =
            LocalDateTime.of(today.minusDays(ago.toLong()), LocalTime.of(hh, 0)).plusMinutes(mm.toLong()).atZone(zone)

        fun ymd(ago: Int): String = today.minusDays(ago.toLong()).format(YMD)

        /** JS `new Date(y, m, d)`(달 0부터, 넘치면 넘김). */
        fun jsDate(y: Int, m0: Int, d: Int): LocalDate = LocalDate.of(y, 1, 1).plusMonths(m0.toLong()).plusDays(d - 1L)

        fun iso(d: ZonedDateTime): String = StoreRules.isoLocal(d)

        fun recId(d: ZonedDateTime): String = "sess-" + ID_STAMP.format(d)

        /** 프로필 처음 모양(store.js `defaultProfile`과 같은 칸). */
        fun baseProfile(
            since: Int,
            stage: Int,
            answers: OnboardingAnswers,
            myCourses: Boolean = false,
            family: Boolean = false,
            familyName: String? = null,
        ): Profile = Profile(
            id = "p1",
            name = "운전자 1",
            createdAt = iso(dayAt(since + 1, 20, 10)),
            onboarding = Onboarding(done = true, answers = answers, recommendedStage = stage),
            consent = Consent(record = true, family = family, academy = false, insurer = false, familyName = familyName ?: "가족"),
            progress = StageProgress(currentStage = stage, unlockedStage = stage, graduatedAt = null, stages = (1..4).associateWith { StageState() }),
            sessionCount = (1..8).associateWith { 0 },
            myPresets = BaroEngine.examplePresets(),
            lastPresetId = null,
            myCourses = if (myCourses) BaroEngine.exampleMyCourses() else emptyList(),
        )

        private fun itemsFor(p: Profile, mode: Int, noInMode: Int, presetId: String?): Map<String, Delivery> =
            BaroEngine.resolveItems(mode, p, ResolveOptions(presetId = presetId, noInMode = noInMode)).mapValues { it.value.value }

        /** 날씨 회차의 리뷰 팁 줄 — 날씨 검출기(J3)가 회차 끝에 쓰는 것과 같은 글·아이콘(J3가 '안 하기'면 없음). */
        private fun weatherTips(p: Profile, w: SessionWeather, items: Map<String, Delivery>): List<ReviewTip> {
            if (items["J3"] == Delivery.OFF) return emptyList()
            fun line(key: String): String = try {
                BaroEngine.lines.render(key, p.coach, emptyMap(), p.honorific, RenderOptions(peek = true, index = 0))
            } catch (e: RuntimeException) {
                ""
            }
            val out = ArrayList<ReviewTip>()
            if (w.type == WeatherType.RAIN || w.type == WeatherType.SNOW) {
                out += ReviewTip(text = line("j3.review." + if (w.type == WeatherType.RAIN) "rain" else "snow"), icon = WX_ICON[w.type])
                if (w.severe != null) out += ReviewTip(text = line("j3.review.severe50"), icon = ICON_SEVERE)
            } else if (w.severe != null) {
                out += ReviewTip(text = line("j3.review.severe"), icon = ICON_SEVERE)
            }
            return out.filter { it.text.isNotEmpty() }
        }

        /** 요약만 있는 회차 기록(presets.js `blankRecord`). */
        fun blankRecord(
            p: Profile,
            mode: Int,
            d: ZonedDateTime,
            course: String? = null,
            place: String? = null,
            presetId: String? = null,
            weather: SessionWeather? = null,
            km: Double? = null,
            min: Double? = null,
            slow: Double? = null,
        ): SessionRecord {
            val noInMode = (p.sessionCount[mode] ?: 0) + 1
            val c = course?.let { Courses.course(it) }
            val pl = place?.let { Courses.place(it) }
            val kmV = km ?: c?.km ?: pl?.km ?: 2.0
            val minV = min ?: c?.min?.toDouble() ?: pl?.min?.toDouble() ?: 8.0
            val simSec = Math.round(minV * 60 * (slow ?: 1.1)).toDouble()
            val w = weather ?: SessionWeather()
            val items = itemsFor(p, mode, noInMode, presetId)
            return SessionRecord(
                id = recId(d), mode = mode, stage = if (mode <= 4) mode else null, noInMode = noInMode,
                courseId = course, placeId = place, presetId = presetId, startedAt = iso(d),
                durationSec = simSec, driveSec = Math.round(simSec * 0.93).toDouble(), distanceM = Math.round(kmV * 1000).toDouble(), arrived = true,
                weather = w, items = items, tips = weatherTips(p, w, items), share = ShareState(),
            )
        }

        /** 요약 → 진행 반영 → 프로필에 넣기(최신이 앞, presets.js `commit`). */
        private fun commit(p: Profile, rec: SessionRecord): Profile {
            val summed = rec.withReview(BaroEngine.review.compute(rec, p))
            val applied = BaroEngine.review.applyProgress(p, summed, summed.startedAt)
            return applied.profile.copy(sessions = (listOf(applied.record) + applied.profile.sessions).take(MAX_RECORDS))
        }

        /** 단계 연습·면허 회차(presets.js `drillRecord`). */
        fun drill(p: Profile, x: Drill): Profile {
            val d = dayAt(x.ago, x.hh, x.mm + rng.int(0, 7))
            var rec = blankRecord(p, x.mode, d, course = x.course ?: STAGE_COURSES[x.mode], weather = x.weather)
            val stats = LinkedHashMap<String, ItemStat>()
            for ((id, v) in x.t) {
                val item = TASK_ITEM[id] ?: continue
                val s = stats[item] ?: ItemStat()
                stats[item] = ItemStat(s.ok + v.first, s.total + v.second)
            }
            val extra = LinkedHashMap(EXTRA[x.mode].orEmpty())
            x.x?.let { extra.putAll(it) }
            for ((item, v) in extra) if (item !in stats) stats[item] = ItemStat(v.first, v.second)
            val base = p.g1Baseline ?: requireNotNull(BaroEngine.threshold("G1.base"))
            val k6 = x.k6 ?: Triple(4.2, 6, 2)
            val f1 = stats["F1"] ?: ItemStat()
            val c2 = stats["C2"]
            rec = rec.copy(
                tasks = x.t.map { (id, v) -> TaskResult(id = id, success = v.first, total = v.second) },
                itemStats = stats,
                metrics = SessionMetrics(
                    c2Avg = if (c2 != null && c2.total > 0) x.c2 else null,
                    c2N = c2?.total ?: 0,
                    stopTotal = f1.total,
                    stopFail = f1.total - f1.ok,
                    stopFailRate = if (f1.total > 0) Math.round((f1.total - f1.ok).toDouble() / f1.total * 1000) / 1000.0 else null,
                    g1 = x.g1,
                    g1Windows = 8,
                    g1Better = r2((base - x.g1) / base),
                    per10min = k6.first,
                    praise = k6.second,
                    correction = k6.third,
                    spoken = Math.round(k6.first * rec.driveSec / 600).toInt(),
                ),
                f2 = x.f2?.mapIndexed { i, r -> r.copy(id = "f2-${i + 1}") } ?: emptyList(),
                license = x.license,
            )
            return commit(p, rec)
        }

        fun drills(p: Profile, vararg list: Drill): Profile = list.fold(p) { acc, x -> drill(acc, x) }

        /** 자율 회차(presets.js `autoRecord`, 설계 6.11): 점수는 fromCounts. */
        fun autoRecord(p0: Profile, x: Auto): Profile {
            val d = dayAt(x.ago, x.hh, x.mm + rng.int(0, 7))
            var rec = blankRecord(p0, 5, d, place = x.place, presetId = x.presetId, weather = x.weather)
            val sc = BaroEngine.score.fromCounts(x.counts)
            val bf = x.counts.bf > 0
            rec = rec.copy(
                score = sc,
                metrics = SessionMetrics(
                    c2Avg = x.c2, c2N = 6, stopTotal = 5, stopFail = if (bf) 1 else 0, stopFailRate = if (bf) 0.2 else 0.0,
                    g1 = sc.g1?.value, g1Windows = 9, g1Better = null, per10min = 2.4, praise = 3, correction = 1,
                    spoken = Math.round(2.4 * rec.driveSec / 600).toInt(),
                ),
                f2 = (1..x.counts.f2).map { i -> f2Row(i).copy(v0 = 31.0, dur = 1.6, maxDrop1s = 12.2, choice = F2Choice.AVOID) },
            )
            val p = p0.copy(lastPresetId = x.presetId)
            val after = commit(p, rec)
            // 요약이 점수를 지우지 않게(요약 칸에 score 없음 — 그대로 둠)
            return after.copy(sessions = after.sessions.mapIndexed { i, r -> if (i == 0) r.copy(score = sc) else r })
        }

        /** 실제 주차 안내 회차(T5b-5 rec.parking). */
        fun parkingRecord(p: Profile, ago: Int, hh: Int, mm: Int, type: ParkingType, side: TurnDir, course: String = "c6-A"): Profile {
            val d = dayAt(ago, hh, mm + rng.int(0, 7))
            val rec0 = blankRecord(p, 6, d, course = course, km = 0.1, min = 1.0, slow = 0.8)
            val total = runCatching { ParkingGuide.steps(type.code, side.code).size }.getOrDefault(0).takeIf { it > 0 } ?: 8
            val steps = (1..total).map { n ->
                ParkingStepLog(n = n, signal = if (n == 1) "press" else "auto", by = if (n == 1) "press" else "signal", t = Math.round(n * 5.5 * 10) / 10.0)
            }
            fun say(key: String): String = try {
                BaroEngine.lines.render(key, p.coach, emptyMap(), p.honorific, RenderOptions(peek = true))
            } catch (e: RuntimeException) {
                ""
            }
            val rec = rec0.copy(
                distanceM = 60.0,
                parking = ParkingResult(
                    type = type, side = side, key = "${type.code}-${side.code}", total = total, reached = total, done = true,
                    sec = Math.round(total * 5.5).toDouble(), pressed = 1, steps = steps, pk2 = 1,
                    wheel = ParkingWheel(steer = 8.0, straight = false), handbrake = ParkingHandbrake(result = EventResult.FAIL, on = false),
                    model = "avante",
                ),
                utterances = listOf(
                    UtteranceLog(t = 30.2, item = "PK2", key = "pk2", category = Category.FIX, text = say("pk2"), spoken = true),
                    UtteranceLog(t = 47.1, item = "PK3", key = "pk3", category = Category.INFO, text = say("pk3"), spoken = true),
                    UtteranceLog(t = 49.6, item = "PB2", key = "pb2", category = Category.INFO, text = say("pb2"), spoken = true),
                ).filter { it.text.isNotEmpty() },
            )
            return commit(p, rec)
        }

        /** 세워 두고 연습 성공(T5a-7) — 회차 수(sessionCount)를 늘리지 않는다. 난수를 쓰지 않는다. */
        fun virtualParking(p: Profile, ago: Int, hh: Int, mm: Int, type: ParkingType, side: TurnDir, sec: Double, switches: Int): Profile {
            val d = dayAt(ago, hh, mm)
            val key = "${type.code}-${side.code}"
            val rec = SessionRecord(
                kind = RecordKind.VIRTUAL_PARKING, id = recId(d), mode = 6, stage = null, noInMode = null, startedAt = iso(d),
                durationSec = sec, distanceM = 0.0, arrived = false, weather = null,
                parking = ParkingResult(
                    virtual = true, type = type, side = side, key = key, model = "avante", result = "success", sec = sec, switches = switches,
                    wheelAngle = 2.1, wheelStraight = true, touches = 0, restarts = 1,
                ),
            )
            return p.copy(parking = p.parking.copy(done = p.parking.done + (key to true)), sessions = listOf(rec) + p.sessions)
        }

        /** 언덕 회차(T6-7 rec.hill + T-HILL* 과제). */
        fun hillRecord(p: Profile, ago: Int, hh: Int, mm: Int): Profile {
            val d = dayAt(ago, hh, mm + rng.int(0, 7))
            val rec0 = blankRecord(p, 7, d, course = "c7-B")
            val park = HillParkResult(
                n = 1, situation = HillSlope.DOWN, need = TurnDir.RIGHT, wheelOk = true, wheelSide = TurnDir.RIGHT, wheelDeg = 14.0,
                handbrake = HandbrakeState.ON, ok = true,
            )
            val hillTasks = linkedMapOf(
                "T-HILLUP" to HillTaskCount(success = 1, total = 2),
                "T-HILLDOWN" to HillTaskCount(success = 1, total = 1),
                "T-HILLPARK" to HillTaskCount(success = 1, total = 1),
            )
            val rec = rec0.copy(
                hill = HillResult(
                    tasks = hillTasks,
                    up = HillUpSummary(attempts = 2, rollbacks = 1, rollOff = false, list = listOf(HillUpAttempt(1, rolled = true, completed = true), HillUpAttempt(2, rolled = false, completed = true))),
                    down = HillDownSummary(runs = 1, presses = 4, holds = 0, list = listOf(HillDownRun(n = 1, presses = 4, holds = 0, ok = true, viaNav = true))),
                    park = park, parks = listOf(park), practice = HillPractice.PARK, step = 3, done = true,
                ),
                tasks = hillTasks.map { (id, t) -> TaskResult(id = id, success = t.success, total = t.total) },
                metrics = SessionMetrics(
                    c2Avg = null, c2N = 0, stopTotal = 0, stopFail = 0, stopFailRate = null, g1 = null, g1Windows = 0, g1Better = null,
                    per10min = 3.1, praise = 3, correction = 0, spoken = 3,
                ),
            )
            return commit(p, rec)
        }
    }
}
