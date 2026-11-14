package com.barodrive.app.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.data.signal.car.CarSignalSource
import com.barodrive.app.demo.DemoTables.Section
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.onboarding.OnboardingStart
import com.barodrive.app.feature.predrive.Predrive
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.screenCatalog
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 시연 손잡이와 패널 (시연 빌드만, 설계 9장, 프로토타입 shell/panel*.js). 실제 앱 화면과 헷갈리지 않게 개발 도구 모양(작은 글자·회색 판·
 * 주황 테두리)이다. 손잡이는 프로토타입의 접힌 패널처럼 세로 탭(폭 40)이고, 창 오른쪽 아래 구석에 둔다 — 오른쪽 가운데는 Pleos 창 막대가
 * 누름을 가로챈다.
 * 주행 화면 위에도 떠 있다 — 시연에서는 이것이 차를 대신하기 때문이다.
 *
 * 묶음(프로토타입 차례): 지금 화면 · 시나리오 · 차량 · 내비 · 코칭 이벤트 넣기 · 데모 도구 · 신호 지원 · 말한 내용 · 가족 휴대폰 미리보기.
 * 맨 위 고정 줄은 상태 줄 "D · 28km/h · 2단계 3회차 · sim 04:12"와 안내 줄. '다른 앱(음악)이 앞에'를 켜면 창 전체를 음악 앱으로 덮는다
 * (손잡이·패널은 그 위). 지금 화면([currentScreen])은 PK-2·PR-1의 시계 붙잡기에도 쓴다.
 */
@Composable
fun DemoPanel(
    currentScreen: ScreenId?,
    onOpenRoute: (Any) -> Unit,
    modifier: Modifier = Modifier,
    onOpenRoot: (Any) -> Unit = onOpenRoute,
    viewModel: DemoViewModel = hiltViewModel(),
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val otherApp by viewModel.otherApp.collectAsStateWithLifecycle()
    LaunchedEffect(currentScreen) { viewModel.onScreen(currentScreen) }
    LaunchedEffect(viewModel) { viewModel.pickerRequests.collect { open = true } }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { go ->
            when (go) {
                // 데모 상태·처음 실행·프로필: 프로토타입은 새로고침 — 뒤로 스택 없이 그 화면부터
                DemoGo.HOME -> onOpenRoot(Home)
                DemoGo.ONBOARDING -> onOpenRoot(OnboardingStart)
                DemoGo.PREDRIVE -> onOpenRoute(Predrive)
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        if (otherApp) OtherAppCover()
        Row(
            // Pleos 홈 화면이 창 오른쪽 가운데의 창 막대('⋮') 둘레 누름을 가로채서(제스처 감시 창) 손잡이를 오른쪽 아래 구석에 둔다(에뮬레이터 실측).
            modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = HANDLE_INSET, bottom = HANDLE_INSET),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space1),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (open) DemoPanelRoute(viewModel, currentScreen, onOpenRoute = { route -> onOpenRoute(route); open = false }, onClose = { open = false })
            DemoHandle(onClick = { open = !open })
        }
    }
}

/** 열린 패널: 상태를 모으고(열려 있는 동안만) 본문에 넘긴다. */
@Composable
private fun DemoPanelRoute(viewModel: DemoViewModel, currentScreen: ScreenId?, onOpenRoute: (Any) -> Unit, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 진짜 차로 바꾸기 전에 속도 권한부터(CAR_SPEED는 dangerous). 거절해도 바꾼다 — 속도만 null로 온다.
    val speedPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.setDataSource(DataSource.CAR)
    }
    val onSource: (DataSource) -> Unit = { source ->
        val needsPermission = source == DataSource.CAR &&
            ContextCompat.checkSelfPermission(context, CarSignalSource.SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) speedPermission.launch(CarSignalSource.SPEED_PERMISSION) else viewModel.setDataSource(source)
    }
    val sim = remember(viewModel) { simActions(viewModel) }
    val view = remember(viewModel) { ViewActionsUi(viewModel::pickDestination, viewModel::setPickerOpen, viewModel::setMockupHero) }
    val tools = remember(viewModel, context) {
        toolActions(viewModel) {
            val text = viewModel.logText()
            if (text.isNotEmpty()) {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("말한 내용", text))
            }
            viewModel.copied(if (text.isEmpty()) 0 else text.count { it == '\n' } + 1)
        }
    }
    DemoPanelFrame {
        DemoPanelContent(
            state = state, currentScreen = currentScreen, sim = sim, tools = tools, view = view, onSource = onSource,
            onVoiceOn = viewModel::setVoiceOn, onToggle = viewModel::toggleSection, onOpenRoute = onOpenRoute, onClose = onClose,
        )
    }
}

/** 손잡이: 오른쪽 가장자리 세로 탭(폭 40, 높이 160), 주황 테두리 + 고정폭 '시연'. */
@Composable
private fun DemoHandle(onClick: () -> Unit) {
    val shape = RoundedCornerShape(topStart = Dimens.radiusS, bottomStart = Dimens.radiusS)
    val handleDesc = stringResource(R.string.demo_handle_desc)
    Box(
        modifier = Modifier
            .size(width = Dimens.edge, height = 160.dp)
            .clip(shape)
            .background(BaroTheme.colors.bgRaised)
            .border(BorderStroke(2.dp, BaroTheme.colors.caution), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = handleDesc },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.demo_handle).toList().joinToString("\n"),
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = BaroTheme.colors.cautionText,
            textAlign = TextAlign.Center,
        )
    }
}

/** 패널 틀: 폭 620, 떠 있는 면 바탕 + 주황 테두리, 넘치면 스크롤. */
@Composable
private fun DemoPanelFrame(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .width(PANEL_WIDTH)
            .heightIn(max = PANEL_MAX_HEIGHT)
            .background(BaroTheme.colors.bgRaised, RoundedCornerShape(Dimens.radiusM))
            .border(BorderStroke(2.dp, BaroTheme.colors.caution), RoundedCornerShape(Dimens.radiusM))
            .verticalScroll(rememberScrollState())
            .padding(Dimens.space2),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

/** 패널 본문(상태 없는). */
@Composable
internal fun DemoPanelContent(
    state: DemoUiState,
    currentScreen: ScreenId?,
    sim: DemoActionsUi,
    tools: ToolActionsUi,
    view: ViewActionsUi,
    onSource: (DataSource) -> Unit,
    onVoiceOn: (Boolean) -> Unit,
    onToggle: (Section) -> Unit,
    onOpenRoute: (Any) -> Unit,
    onClose: () -> Unit,
) {
    val c = BaroTheme.colors
    val on = state.simControlsEnabled
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.demo_title), style = PnTextStyle.copy(fontWeight = FontWeight.Bold), color = c.cautionText, modifier = Modifier.weight(1f))
        PnButton(stringResource(R.string.demo_close), onClose)
    }
    PnStatusRow(state.status.ifEmpty { stringResource(R.string.demo_status_empty) }, state.scenario.playing)
    state.notice?.let { PnNote(it, color = c.cautionText) }
    if (state.pickerOpen) DestinationPicker(state.places, onPick = view.pickDestination, onClose = { view.setPickerOpen(false) })
    PnRow(stringResource(R.string.demo_source)) {
        PnSeg(
            listOf(DataSource.SIM to stringResource(R.string.data_source_sim), DataSource.CAR to stringResource(R.string.data_source_car)),
            state.dataSource, onSource,
        )
        PnButton(
            stringResource(R.string.demo_voice) + " " + stringResource(if (state.voiceOn) R.string.demo_voice_turn_off else R.string.demo_voice_turn_on),
            { onVoiceOn(!state.voiceOn) },
        )
    }
    var showScreens by rememberSaveable { mutableStateOf(false) }
    PnSection(stringResource(R.string.demo_sec_now), Section.NOW.id, state.isOpen(Section.NOW), { onToggle(Section.NOW) }) {
        CurrentScreenLine(currentScreen)
        if (showScreens) {
            ScreenCatalogContent(screenCatalog, onOpen = { onOpenRoute(it.route); showScreens = false }, onBack = { showScreens = false })
        } else {
            PnButton(stringResource(R.string.demo_screens_open), { showScreens = true })
        }
    }
    PnSection(stringResource(R.string.demo_sec_scenario), Section.SCENARIO.id, state.isOpen(Section.SCENARIO), { onToggle(Section.SCENARIO) }) {
        ScenarioSection(state.scenario, on, sim)
    }
    PnSection(stringResource(R.string.demo_sec_car), Section.CAR.id, state.isOpen(Section.CAR), { onToggle(Section.CAR) }) {
        CarSection(state.car, on, sim)
    }
    PnSection(stringResource(R.string.demo_sec_navi), Section.NAVI.id, state.isOpen(Section.NAVI), { onToggle(Section.NAVI) }) {
        NaviSection(state.navi, on, sim)
    }
    PnSection(stringResource(R.string.demo_sec_inject), Section.INJECT.id, state.isOpen(Section.INJECT), { onToggle(Section.INJECT) }) {
        InjectSection(state.injections, on, sim.inject)
    }
    PnSection(stringResource(R.string.demo_sec_tools), Section.DEMO.id, state.isOpen(Section.DEMO), { onToggle(Section.DEMO) }) {
        ToolsSection(state.tools, tools)
    }
    PnSection(stringResource(R.string.demo_sec_caps), Section.CAPS.id, state.isOpen(Section.CAPS), { onToggle(Section.CAPS) }) {
        CapsSection(state.caps, on, tools)
    }
    PnSection(stringResource(R.string.demo_sec_voice), Section.VOICE.id, state.isOpen(Section.VOICE), { onToggle(Section.VOICE) }) {
        VoiceSection(state.voice, tools)
    }
    PnSection(stringResource(R.string.demo_sec_family), Section.FAMILY.id, state.isOpen(Section.FAMILY), { onToggle(Section.FAMILY) }) {
        FamilySection(state.family, tools)
    }
    PnSection(stringResource(R.string.demo_sec_view), Section.VIEW.id, state.isOpen(Section.VIEW), { onToggle(Section.VIEW) }) {
        PnToggle(stringResource(R.string.demo_mockup_hero), state.mockupHero, view.setMockupHero)
        PnNote(stringResource(R.string.demo_mockup_note))
    }
}

/** 목적지 고르기·보기 조작. */
internal class ViewActionsUi(
    val pickDestination: (String) -> Unit = {},
    val setPickerOpen: (Boolean) -> Unit = {},
    val setMockupHero: (Boolean) -> Unit = {},
)

/** '지금 화면 · HM-1 홈 · 정차'(맨 위 경로). 덮개가 덮었을 때도 아래 정차 화면을 적는다. */
@Composable
private fun CurrentScreenLine(screen: ScreenId?) {
    Text(
        text = if (screen == null) {
            stringResource(R.string.demo_current_screen_unknown)
        } else {
            stringResource(R.string.demo_current_screen, screen.code, stringResource(screen.title))
        },
        style = PnMonoStyle.copy(fontWeight = FontWeight.Bold),
        color = BaroTheme.colors.t1,
    )
}

/** 화면 목록: 화면 id 단추들(설계 4.1 순서). 누르면 그 화면으로 가고 패널을 닫는다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenCatalogContent(entries: List<CatalogEntry>, onOpen: (CatalogEntry) -> Unit, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PnButton(stringResource(R.string.demo_screens_back), onBack)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.forEach { entry -> PnButton(entry.screen.code, { onOpen(entry) }) }
        }
    }
}

private fun simActions(vm: DemoViewModel) = DemoActionsUi(
    selectScenario = vm::selectScenario, togglePlay = vm::togglePlay, setSkill = vm::setSkill, setSpeed = vm::setSpeed,
    setRealtime = vm::setRealtime, setAutoplay = vm::setAutoplay, skipNext = vm::skipNext, skipEnd = vm::skipEnd, shift = vm::shift, setBlinker = vm::setBlinker,
    pedal = vm::pedal, lockBrake = vm::lockBrake, setTargetSpeed = vm::setTargetSpeed, setSteer = vm::setSteer, setBelt = vm::setBelt,
    setPassenger = vm::setPassenger, setHandbrake = vm::setHandbrake, setWiper = vm::setWiper, setLowBeam = vm::setLowBeam,
    setHazard = vm::setHazard, setNight = vm::setNight, setTemp = vm::setTemp, setModel = vm::setModel, injectTurn = vm::injectTurn, setLimit = vm::setLimit,
    setSchool = vm::setSchool, setJam = vm::setJam, clearRoute = vm::clearRoute, openPicker = { vm.setPickerOpen(true) }, inject = vm::inject,
)

private fun toolActions(vm: DemoViewModel, copy: () -> Unit) = ToolActionsUi(
    applyPreset = vm::applyPreset, resetApp = vm::resetApp, resetPanel = vm::resetPanel, setNetwork = vm::setNetwork, setTown = vm::setTown,
    reloadCongestion = vm::reloadCongestion, setProfile = vm::setProfile, setOtherApp = vm::setOtherApp,
    addExampleCourses = vm::addExampleCourses, startRules = vm::startRules, setCap = vm::setCap, allCaps = vm::allCaps,
    setLogFilter = vm::setLogFilter, clearLog = vm::clearLog, copyLog = copy, clearFamily = vm::clearFamily,
)

private val PANEL_WIDTH = 620.dp
private val HANDLE_INSET = 24.dp
private val PANEL_MAX_HEIGHT = 1080.dp

@BaroPreviews
@Composable
private fun DemoPanelPreview() {
    BaroTheme {
        DemoPanelFrame {
            DemoPanelContent(
                state = DemoUiState(
                    status = "D · 28km/h · 2단계 3회차 · sim 04:12",
                    notice = "직접 조작해서 재생을 멈췄어요",
                    scenario = ScenarioSectionUi(info = "S2-B · 2단계 · 보통 · 재생 중", playing = true, speed = 4, realtime = false, progress = 0.4, progressText = "sim 04:12 · 1.2 / 3.4km"),
                    caps = CapsSectionUi(summary = "모든 신호를 읽어요"),
                ),
                currentScreen = ScreenId.HM_1, sim = NoDemoActions, tools = ToolActionsUi(), view = ViewActionsUi(), onSource = {}, onVoiceOn = {},
                onToggle = {},
                onOpenRoute = {}, onClose = {},
            )
        }
    }
}

@BaroPreviews
@Composable
private fun DemoScreenCatalogPreview() {
    BaroTheme {
        DemoPanelFrame {
            CurrentScreenLine(ScreenId.CR_1)
            ScreenCatalogContent(entries = screenCatalog, onOpen = {}, onBack = {})
        }
    }
}
