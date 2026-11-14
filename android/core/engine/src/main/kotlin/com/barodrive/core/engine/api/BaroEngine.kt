package com.barodrive.core.engine.api

import com.barodrive.core.engine.CoachEngine
import com.barodrive.core.engine.CoachSession
import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.config.ItemResolver
import com.barodrive.core.engine.config.ModeContent
import com.barodrive.core.engine.config.toModel
import com.barodrive.core.engine.content.Items
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.model.Coach
import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.Courses
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EngineFlags
import com.barodrive.core.model.FeedbackPreset
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.ItemDef
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.RecordSummary
import com.barodrive.core.model.ResolvedDelivery
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionHistory
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.TaskDef
import com.barodrive.core.model.Weather

/**
 * 코칭 엔진 파사드 (설계 6.1.1 공개 API 표, 프로토타입 `window.BaroEngine` — core.js 머리 주석 목록).
 * 앱·시뮬레이터 테스트는 엔진을 이것으로만 부른다. 순수 함수 묶음이다: 시계·시드 없는 난수·안드로이드를 쓰지 않고, 입력을 고치지 않는다
 * (프로토타입이 프로필을 고치는 함수는 바뀐 값을 돌려준다).
 *
 * 하위 묶음은 파일마다 따로 둔다: [feedback] · [lines] · [courses] · [review] · [score] · [fading] · [license].
 * 이 파일과 하위 묶음의 함수는 모두 프로토타입 규칙을 그대로 옮겼다(골든 18회차와 옮긴 프로토타입 단위 테스트로 확인).
 */
object BaroEngine {
    /** 전달 방식 결정(설계 6.3.2·6.7). */
    val feedback: EngineFeedback get() = EngineFeedback

    /** 대사(설계 6.6). */
    val lines: EngineLines get() = EngineLines

    /** 코스 추천(설계 6.10.6). */
    val courses: EngineCourses get() = EngineCourses

    /** 리뷰 계산·진행 반영(설계 6.10). */
    val review: EngineReview get() = EngineReview

    /** 자율 점수(설계 6.11). */
    val score: EngineScore get() = EngineScore

    /** 도움 줄여 가기(설계 6.7). */
    val fading: EngineFading get() = EngineFading

    /** 운전면허 연습 모드 계산(설계 6.12.3). */
    val license: EngineLicense get() = EngineLicense

    // ================================================================ 세션 (설계 6.1)

    /**
     * 회차 하나의 엔진 세션 (설계 6.1.1 `createSession(cfg)`, core.js `createSession` — '세션' 머리 주석).
     * 세션: `step(snapshot)` → [com.barodrive.core.model.StepOutput] · `input(event)` · `finish(arrived)` → 회차 기록.
     * 등록된 검출기 전부([com.barodrive.core.engine.detect.Detectors])와 앱 대사를 쓴다.
     */
    fun createSession(cfg: SessionConfig): CoachSession = CoachEngine().createSession(cfg)

    /**
     * 세션 설정 만들기 (core.js `buildConfig(mode, profile, prep, panelState)` — 'cfg' 머리 주석). [caps]는 패널 상태 대신
     * 지금 신호 출처의 caps, [flags]는 엔진 스위치(안전3 — 끄면 C3·C6·J1이 항목에서 빠진다). 모드가 1~8이 아니면 1~8 안으로 자른다(예외 없음).
     */
    fun buildConfig(
        mode: Int,
        profile: Profile?,
        prep: PrepSession? = null,
        caps: SignalCaps = SignalCaps(),
        flags: EngineFlags = EngineFlags(),
    ): SessionConfig {
        val m = mode.coerceIn(1, 8)
        val p = profile ?: Profile()
        val pr = prep ?: PrepSession(mode = m)
        val noInMode = (pr.noInMode ?: p.nextNoInMode(m)).coerceAtLeast(1)
        val resolved = ItemResolver.resolve(
            m, p, ResolveOptions(presetId = pr.presetId, presetItems = pr.presetItems, noInMode = noInMode), flags.extraSafety,
        )
        val course = Courses.course(pr.courseId)
        val place = Courses.place(pr.placeId)
        val w = pr.weather
        return SessionConfig(
            mode = m,
            stage = if (m <= 4) m else null,
            sessionNoInMode = noInMode,
            coach = p.coach,
            honorific = p.honorific,
            items = resolved.mapValues { it.value.value },
            itemReasons = resolved.mapValues { it.value.reason },
            weather = Weather(snow = w.snow, rain = w.rain, severe = w.severe),
            caps = caps,
            history = SessionHistory(
                baselineG1 = p.g1Baseline,
                firstSessionMetrics = p.firstMetrics,
                recentSessionsInMode = p.sessions.filter { it.mode == m }.take(RECENT_IN_MODE).map(RecordSummary::of),
            ),
            fading = p.fading[m].orEmpty(),
            overrides = p.feedback.overrides[m].orEmpty(),
            seed = "baro:${profile?.id ?: "p"}:m$m:n$noInMode:${pr.courseId ?: pr.placeId ?: ""}",
            profileId = profile?.id,
            courseId = pr.courseId,
            placeId = pr.placeId,
            presetId = pr.presetId,
            dest = place?.name ?: course?.name,
            preEvents = pr.preEvents,
            parking = pr.parking,
            startedAt = pr.startedAt,
            muted = false,
            startGear = Gear.P,
            flags = flags,
        )
    }

    // ================================================================ 항목·전달 방식 (설계 6.3.2)

    /** 지금 쓰는 항목 id, FB-1 순서(core.js `itemIds`: 안전3 스위치가 꺼지면 C3·C6·J1이 빠짐 — [extraSafety]). */
    fun itemIds(extraSafety: Boolean = Items.EXTRA_SAFETY_ITEMS): List<String> = Items.ids(extraSafety)

    /** 항목 정의(data/items.js = 엔진 콘텐츠 `content/items.json`을 모델 모양으로). 모르는 id면 null. */
    fun itemDef(id: String): ItemDef? = Items.def(id)?.toModel()

    /** 모드 기본 전달 방식(core.js `defaultFor(item, mode)`, 설계 6.3.2 표). 모르는 항목이면 null, 모드가 1~8이 아니면 예외. */
    fun defaultFor(item: String, mode: Int): Delivery? = Items.defaultFor(item, mode)

    /** 사용자가 고를 수 있는 값(core.js `allowed(item)`, 결정-20). 고정 항목·모르는 항목은 []. */
    fun allowed(item: String): List<Delivery> = Items.allowed(item)

    /** 고정 항목인가(core.js `isFixed(item)`: 안전 항목·F2). */
    fun isFixed(item: String): Boolean = Items.isFixed(item)

    /**
     * 이 회차 항목별 전달 방식과 까닭(core.js `resolveItems(mode, profile, opts)`, 설계 6.3.2 아래 줄·6.7).
     * 우선순위: 사용자 변경(자율은 고른 설정) > 도움 줄여 가기 > C1 처음 2회차 > 기본값. 허용 값 밖의 값은 버린다.
     * C2가 도움 줄여 가기로 내려가면 C1도 X(사용자가 C1을 정했으면 그대로). 모드가 1~8이 아니면 예외(프로토타입과 같음).
     */
    fun resolveItems(mode: Int, profile: Profile?, opts: ResolveOptions = ResolveOptions()): Map<String, ResolvedDelivery> =
        ItemResolver.resolve(mode, profile, opts)

    /** 못 읽는 신호 때문에 꺼지는 항목(core.js `itemsOffByCaps`, 설계 7.8: 항목 `needs` 중 하나라도 못 읽음), FB-1 순서. */
    fun itemsOffByCaps(caps: SignalCaps): List<String> = Items.itemsOffByCaps(caps)

    /**
     * 예시 자율 설정 2개 (core.js `examplePresets`, 결정-47): '출퇴근용' = FB-2 항목을 고를 수 있으면 S, 아니면 X ·
     * '비 오는 날' = 출퇴근용 + B1·F1·J3 바로. 부를 때마다 새 값.
     */
    fun examplePresets(): List<FeedbackPreset> = ItemResolver.examplePresets()

    // ================================================================ 기준 (설계 6.2·6.8)

    /**
     * 날씨를 반영한 기준값 (core.js `threshold(key, weather, arg)` = 검출기 `ctx.th`).
     * 'B1.gain' 11/8 · 'F1.drop' 7.5/6 · 'E.factor' 1/0.8/0.5 · 'E.limit'([limit]에 배율) · 'E2.limit' ·
     * 'C2.ok'·'C2.almost'·'turn.near'는 [ic]면 IC 값. 그 밖은 기준값 표 그대로. 표에 없는 키면 null(프로토타입은 예외).
     */
    fun threshold(key: String, weather: SessionWeather = SessionWeather(), ic: Boolean = false, limit: Double? = null): Double? = when {
        key == "E.limit" -> effLimit(limit, weather)?.toDouble()
        key in WEATHERED || key in Thresholds.keys -> Thresholds.weathered(key, weather, ic)
        else -> null
    }

    /** 날씨 배율을 적용해 반올림한 제한속도 (core.js `effLimit`, 설계 6.8: 50 → 40(비·눈) · 25(아주 나쁜 날)). 없으면 null. */
    fun effLimit(limit: Double?, weather: SessionWeather): Int? = Thresholds.effLimit(limit, weather)

    // ================================================================ 대사 도우미 (설계 6.6)

    /**
     * 문장에 넣을 호칭 (core.js `honorificText`, 설계 6.6.1): 형제/자매는 늘 '' · '우리 딸' · '우리 아들' · 직접 쓴 말(앞뒤 공백 빼고 8자까지).
     */
    fun honorificText(honorific: Honorific?, coach: Coach): String {
        if (coach == Coach.SIB || honorific == null) return ""
        return when (honorific.type) {
            HonorificType.DAUGHTER -> HonorificType.DAUGHTER.label
            HonorificType.SON -> HonorificType.SON.label
            HonorificType.CUSTOM -> honorific.text.trim().take(Honorific.MAX_LENGTH)
            HonorificType.NONE -> ""
        }
    }

    /** 문장을 말하는 데 걸리는 시간, 초 (core.js `speakDur`, 설계 2.6: 글자 수 × 0.15 + 0.4). */
    fun speakDur(text: String): Double = Lines.speakDur(text)

    // ================================================================ 과제·코스

    /** 모드 과제(설계 6.4, core.js `modeTasks`: copy.js `modes[m].tasks` 순서, 2단계 T-STOP처럼 모드 문구 반영). 5·6은 []. */
    fun modeTasks(mode: Int): List<TaskDef> = ModeContent.modeTasks(mode)

    /** 과제 정의(data/tasks.js = 엔진 콘텐츠 `content/tasks.json`, 모드 과제 밖의 T-SCHOOL 포함). 모르는 id면 null. */
    fun taskDef(id: String): TaskDef? = ModeContent.task(id)

    /** 예시 내 코스 (core.js `exampleMyCourses`): own 코스(c1-C·c2-A)를 내 코스 줄로. 부를 때마다 새 값. */
    fun exampleMyCourses(): List<MyCourse> = Courses.all.filter { it.own }.map { c ->
        MyCourse(id = "mc-${c.id}", name = c.name, mode = c.mode, source = CourseSource.SAVED, courseRef = c.id, example = true)
    }

    /** 날씨로 바뀌는 기준 이름(표에 그 이름의 줄이 없는 것 포함). */
    private val WEATHERED = setOf("B1.gain", "F1.drop", "E.factor", "E2.limit", "C2.ok", "C2.almost", "turn.near")
    private const val RECENT_IN_MODE = 5
}
