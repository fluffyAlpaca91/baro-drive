package com.barodrive.app.demo

import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.signal.sim.SimController
import com.barodrive.app.demo.preset.DemoPresets
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.Courses
import com.barodrive.core.model.Gear
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.SessionInput
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** 시연 도구 동작 뒤 갈 화면 (패널이 경로로 바꾼다). */
enum class DemoGo { HOME, ONBOARDING, PREDRIVE }

/** 시연 도구 동작 결과: 패널 안내 줄 · 갈 화면. */
data class DemoResult(val notice: String? = null, val go: DemoGo? = null)

/**
 * 시연 패널 '데모 도구'·주입 K8의 앱 쪽 동작 (프로토타입 js/app/screens/demo.js `BaroApp.demo` — applyPreset·switchProfile·
 * addExampleCourses·startRules, 주입 'driverAsk'; shell/panel.js `resetApp`). 화면 없이 돈다(경로는 [DemoGo]로 넘긴다).
 *
 * 프로토타입은 데모 상태를 적용하거나 처음 실행으로 되돌리면 페이지를 새로고침해 시뮬레이터·말한 내용까지 처음으로 돌린다. 앱은 새로고침 대신
 * 진행 중 회차를 기록 없이 버리고([SessionController.cancel]) 시뮬레이터를 처음으로([SimController.resetAll]) 말한 내용을 지운 뒤 홈(또는 첫 화면)으로 간다.
 */
@Singleton
class DemoActions @Inject constructor(
    private val session: SessionController,
    private val profiles: ProfileRepository,
    private val sim: SimController,
    private val voice: VoiceLog,
    private val panel: DemoPanelRepository,
    private val clock: Clock,
) {
    /** 데모 상태 적용(설계 8.3, demo.js `applyPreset`): 회차를 버리고 → 앱 저장을 그 상태로(처음 실행은 지움) → 패널 값 → 처음으로 → 홈. */
    suspend fun applyPreset(id: String): DemoResult {
        val def = DemoPresets.info(id) ?: return DemoResult("데모 상태를 적용하지 못했어요")
        cancelRunning()
        val st = try {
            DemoPresets.build(id, LocalDate.now(clock), clock.zone)
        } catch (e: RuntimeException) {
            return DemoResult("데모 상태를 적용하지 못했어요")
        }
        if (st != null) profiles.replace(st) else profiles.reset()
        val p = def.panel
        panel.update {
            it.copy(
                demo = it.demo.copy(network = p.network, town = p.town, congestionSeed = p.congestionSeed, otherApp = p.otherApp, profileId = p.profileId),
                demoPreset = id,
            )
        }
        restart()
        val onboarded = st?.activeProfile?.onboarding?.done == true
        return DemoResult("데모 상태 '${def.title}'을 적용했어요", if (onboarded) DemoGo.HOME else DemoGo.ONBOARDING)
    }

    /** [처음 실행으로 되돌리기](panel.js `resetApp`): 앱 저장만 지운다(패널 설정은 그대로) → 첫 화면. */
    suspend fun resetApp(): DemoResult {
        cancelRunning()
        profiles.reset()
        restart()
        return DemoResult(go = DemoGo.ONBOARDING)
    }

    /**
     * 프로필 바꾸기(결정-52, demo.js `switchProfile`): 진행 중 회차는 기록 없이 버리고 그 프로필로(없으면 '운전자 N' 새로).
     * 서 있으면 홈(첫 설정 전이면 첫 화면), 달리는 중이면 화면은 그대로(주행 덮개 — DR-0). 같은 프로필이면 null.
     */
    suspend fun switchProfile(id: String): DemoResult? {
        if (id.isEmpty() || profiles.state.first().activeProfileId == id) return null
        cancelRunning()
        val p = profiles.setActiveProfile(id)
        panel.update { it.copy(demo = it.demo.copy(profileId = id)) }
        val gear = session.state.value.gear
        val parked = gear == null || gear == Gear.P
        return DemoResult(go = if (!parked) null else if (p.onboarding.done) DemoGo.HOME else DemoGo.ONBOARDING)
    }

    /** [예시 내 코스 넣기](결정 T1c-Q1, demo.js `addExampleCourses`): c1-C·c2-A 중 없는 것만 → 더한 수. */
    suspend fun addExampleCourses(): Int {
        var added = 0
        profiles.update { p ->
            val have = p.myCourses.map { it.courseRef ?: it.id }.toSet()
            val more = BaroEngine.exampleMyCourses().filter { it.courseRef !in have }
            added = more.size
            p.copy(myCourses = p.myCourses + more)
        }
        return added
    }

    /**
     * [규칙 확인(S9-R) 준비하고 재생](설계 7.7, demo.js `startRules`): 1단계 준비 세션 → PR-1 → S9-R을 불러 자동 재생
     * (회차는 1단계 기록으로 남는다). 못 하면 그 까닭.
     */
    suspend fun startRules(): DemoResult {
        val st = session.state.value
        if (st.phase == SessionPhase.ACTIVE) return DemoResult("연습 중이에요 · 회차가 끝난 뒤에 눌러 주세요")
        val gear = st.snapshot?.gear
        if (gear != null && gear != Gear.P) return DemoResult("기어를 P로 바꾼 뒤에 눌러 주세요")
        if (!profiles.current().onboarding.done) return DemoResult("첫 설정을 마친 뒤에 쓸 수 있어요")
        session.prepare(PrepSession(mode = 1)) ?: return DemoResult("아직 준비되지 않았어요")
        sim.loadAndAutoplay(RULES_SCENARIO)
        return DemoResult("1단계로 규칙 확인(S9-R)을 준비했어요 · 1단계 회차로 기록돼요", DemoGo.PREDRIVE)
    }

    /**
     * 내비 창 '목적지 고르기'(navi-ui.js `pick` → session.js `onRoute`, 설계 5.5): 연습 준비(PR-1)가 있고 회차 전이면 그 장소를 준비 세션
     * 목적지로 하고 내비로 보낸다 — 내비 안내 + 그 장소 시나리오 자동 재생(자율 PR-1 [내비에서 목적지 고르기]). 아니면 내비 안내만 바꾼다.
     */
    suspend fun pickDestination(placeId: String): DemoResult {
        val place = Courses.place(placeId) ?: return DemoResult("목적지를 넣지 못했어요")
        val st = session.state.value
        if (st.prep != null && st.phase != SessionPhase.ACTIVE) {
            session.updatePrep { it.copy(courseId = null, placeId = placeId) }
            val r = session.send()
            return DemoResult(r?.toast ?: "목적지를 넣지 못했어요")
        }
        return try {
            sim.setDestination(placeId)
            DemoResult("내비 목적지: ${place.name} · 안내를 시작해요")
        } catch (e: RuntimeException) {
            DemoResult("목적지를 넣지 못했어요")
        }
    }

    /** 주입 '운전자: 방금 어땠어?'(K8, 설계 7.10): 연습 중이면 세션 입력으로 → 패널 글. */
    suspend fun driverAsk(): String {
        if (session.state.value.phase != SessionPhase.ACTIVE) return "연습 중이 아니라 답할 코치가 없어요"
        session.input(SessionInput.DriverAsk)
        return "운전자 질문 → 코치가 답해요(직전 30초의 회전·정지)"
    }

    /** 진행 중 회차·준비 세션을 기록 없이 끝내고 재생을 멈춘다(demo.js `cancelRunning`). */
    private suspend fun cancelRunning() {
        if (session.state.value.phase != SessionPhase.IDLE) session.cancel()
        sim.pauseScenario()
    }

    /** 새로고침 대신: 시뮬레이터 처음으로 · 말한 내용 지움. */
    private fun restart() {
        sim.resetAll()
        voice.clear()
    }

    private companion object {
        const val RULES_SCENARIO = "S9-R"
    }
}
