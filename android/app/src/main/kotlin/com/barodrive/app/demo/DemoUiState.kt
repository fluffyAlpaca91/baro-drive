package com.barodrive.app.demo

import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.data.signal.sim.CarView
import com.barodrive.app.feature.share.FamilyCard
import com.barodrive.app.session.api.VoiceEntry
import com.barodrive.core.model.DemoPresetInfo
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Place
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.Town
import com.barodrive.core.sim.InjectionInfo
import com.barodrive.core.sim.MarkProgress

/**
 * 시연 패널에 보일 값 (프로토타입 shell/panel*.js 묶음들, 설계 9). 묶음마다 따로 바뀌는 값을 나눠 담아 바뀐 묶음만 다시 그린다.
 */
data class DemoUiState(
    /** 맨 위 상태 줄 "D · 28km/h · 2단계 3회차 · 못 읽는 신호 2 · sim 04:12"(panel.js `setStatus`). */
    val status: String = "",
    /** 상태 줄 아래 안내 줄(4초). */
    val notice: String? = null,
    val dataSource: DataSource = DataSource.SIM,
    val voiceOn: Boolean = true,
    /** 묶음 id → 펼침. */
    val open: Map<String, Boolean> = emptyMap(),
    val scenario: ScenarioSectionUi = ScenarioSectionUi(),
    val car: CarSectionUi = CarSectionUi(),
    val navi: NaviSectionUi = NaviSectionUi(),
    val injections: List<InjectGroupUi> = emptyList(),
    val tools: ToolsSectionUi = ToolsSectionUi(),
    val caps: CapsSectionUi = CapsSectionUi(),
    val voice: VoiceSectionUi = VoiceSectionUi(),
    val family: List<FamilyCard> = emptyList(),
    /** 다른 앱(음악)이 앞에(덮개). */
    val otherApp: Boolean = false,
    /** '보기' [목업 원안 보기]. */
    val mockupHero: Boolean = false,
    /** 내비 '목적지 고르기'가 열려 있음(맨 위 카드). */
    val pickerOpen: Boolean = false,
    /** 목적지 목록: 즐겨찾기 · 최근 목적지(navi-ui.js `renderList`). */
    val places: List<PlaceGroupUi> = emptyList(),
) {
    /** 시뮬레이터 조작은 시뮬레이터 출처일 때만 뜻이 있다. */
    val simControlsEnabled: Boolean get() = dataSource == DataSource.SIM

    fun isOpen(section: DemoTables.Section): Boolean = open[section.id] ?: section.defaultOpen
}

/** 목적지 묶음 하나(즐겨찾기 · 최근 목적지). */
data class PlaceGroupUi(val title: String, val places: List<Place>)

/** 시나리오 고르기 한 줄. [key] = 'prep'(앱이 고른 코스) · 시나리오 id. */
data class ScenarioOption(val key: String, val label: String, val group: String)

/** 묶음 '시나리오'. */
data class ScenarioSectionUi(
    val options: List<ScenarioOption> = emptyList(),
    val selected: String? = null,
    /** 'S1-B · 1단계 · 보통 · 재생 중' · '불러온 시나리오 없음 · 손 조작'. */
    val info: String = "",
    val skill: DriverSkill = DriverSkill.NORMAL,
    val playing: Boolean = false,
    val ended: Boolean = false,
    /** 배속 ×1·×2·×4 · 배속 '실제'(참이면 [speed]는 보지 않는다, 처음 값). */
    val speed: Int = 1,
    val realtime: Boolean = true,
    val autoplay: Boolean = true,
    val progress: Double = 0.0,
    val marks: List<MarkProgress> = emptyList(),
    /** 'sim 04:12 · 1.2 / 3.4km · 장면 ✓출발 → 회전'. */
    val progressText: String = "",
)

/** 묶음 '차량'. */
data class CarSectionUi(
    val car: CarView = CarView(),
    val manualTarget: Double = 0.0,
    val brakeLock: Boolean = false,
    /** 비상등(시연 전용 — 시뮬레이터 차 상태 밖, 키보드 W와 같은 값). */
    val hazard: Boolean = false,
    /** 패널 주간/야간(야간이 처음 값). */
    val night: Boolean = true,
    val model: String = "avante",
    /** 차종 id → 이름. */
    val models: List<Pair<String, String>> = emptyList(),
    /** 'D · 28km/h · 핸들 3° · 브레이크 뗌 · 이번 주행 1.23km'. */
    val readLine: String = "",
)

/** 묶음 '내비'. */
data class NaviSectionUi(
    /** 지금 TBT 줄. */
    val now: String = "",
    val school: Boolean = false,
    val jam: Boolean = false,
    val limit: Int? = null,
)

/** 주입 버튼 묶음(그룹 이름 → 버튼). */
data class InjectGroupUi(val group: String, val items: List<InjectionInfo>)

/** 묶음 '데모 도구'. */
data class ToolsSectionUi(
    val presets: List<DemoPresetInfo> = emptyList(),
    /** 마지막에 적용한 데모 상태(없으면 첫 줄). */
    val preset: String? = null,
    val network: Boolean = true,
    /** 보낼 예정 요약 수. */
    val queued: Int = 0,
    val town: String = "home",
    val towns: List<Town> = emptyList(),
    val congestionSeed: Int = 1,
    val tripOff: Boolean = false,
    val profileId: String = "p1",
    val otherApp: Boolean = false,
)

/** 묶음 '신호 지원' 한 줄: 신호 · 읽음 · 꺼지는 항목 · 대신 하는 것. */
data class CapRowUi(val row: DemoTables.CapRow, val readable: Boolean, val items: List<String>)

/** 묶음 '신호 지원'. */
data class CapsSectionUi(val caps: SignalCaps = SignalCaps(), val rows: List<CapRowUi> = emptyList(), val summary: String = "")

/** 묶음 '말한 내용': 보이는 줄(최근 [VOICE_ROWS]줄) · 세기 · 거르기. */
data class VoiceSectionUi(
    val rows: List<VoiceEntry> = emptyList(),
    val coach: Int = 0,
    val navi: Int = 0,
    val blocked: Int = 0,
    /** 거르기에 맞는 줄 수(보이는 줄이 이보다 적으면 앞을 자름). */
    val shown: Int = 0,
    val filter: LogFilter = LogFilter(),
) {
    val total: Int get() = coach + navi + blocked

    companion object {
        /** 패널에 그리는 줄 수(기록은 2000줄까지, 복사는 전부). */
        const val VOICE_ROWS = 200
    }
}
