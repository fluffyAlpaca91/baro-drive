package com.barodrive.app.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.sim.InjectionInfo

/**
 * 묶음 '시나리오'(panel-sim.js `renderScenario`): 고르기 · 운전 실력 · ▶/⏸ · 배속 [실제 | ×1 | ×2 | ×4] + 안내 한 줄 ·
 * [다음 장면] [끝으로] · 자동 재생 · 진행 막대.
 */
@Composable
internal fun ScenarioSection(s: ScenarioSectionUi, enabled: Boolean, a: DemoActionsUi) {
    PnSelect(
        options = s.options.map { Triple(it.key, it.label, it.group) },
        selected = s.selected,
        placeholder = stringResource(R.string.demo_scenario_pick),
        onSelect = a.selectScenario,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    PnNote(s.info)
    PnRow(stringResource(R.string.demo_skill)) {
        PnSeg(DriverSkill.entries.map { it to it.label }, s.skill, a.setSkill, enabled)
    }
    PnRow(null) {
        PnButton(
            text = stringResource(
                when {
                    s.playing -> R.string.demo_pause
                    s.ended -> R.string.demo_replay
                    else -> R.string.demo_play
                },
            ),
            onClick = a.togglePlay,
            kind = PnKind.Primary,
            selected = s.playing,
            enabled = enabled,
        )
        val realLabel = stringResource(R.string.demo_speed_real)
        PnSeg(
            PACES.map { it to if (it == PACE_REAL) realLabel else stringResource(R.string.demo_speed_factor, it) },
            if (s.realtime) PACE_REAL else s.speed,
            { if (it == PACE_REAL) a.setRealtime() else a.setSpeed(it) },
            enabled,
        )
    }
    PnNote(stringResource(R.string.demo_speed_note))
    PnRow(null) {
        PnButton(stringResource(R.string.demo_next_scene), a.skipNext, enabled = enabled)
        PnButton(stringResource(R.string.demo_to_end), a.skipEnd, enabled = enabled)
    }
    PnToggle(stringResource(R.string.demo_autoplay), s.autoplay, a.setAutoplay, enabled)
    ProgressWithMarks(s.progress, s.marks.mapNotNull { m -> m.ratio?.let { it to m.passed } })
    PnNote(s.progressText, color = BaroTheme.colors.t2)
}

/** 진행 막대(`.ps-prog`): 채움 + 장면 표시(지난 장면은 진하게). */
@Composable
private fun ProgressWithMarks(ratio: Double, marks: List<Pair<Double, Boolean>>) {
    val c = BaroTheme.colors
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(c.line),
    ) {
        val w = maxWidth
        Box(Modifier.fillMaxHeight().width(w * ratio.toFloat().coerceIn(0f, 1f)).background(c.brand))
        for ((r, passed) in marks) {
            Box(
                Modifier
                    .offset(x = w * r.toFloat().coerceIn(0f, 1f) - 3.dp)
                    .size(6.dp, 14.dp)
                    .background(if (passed) c.t1 else c.caution),
            )
        }
    }
}

/**
 * 묶음 '차량'(panel-sim.js `renderCar`). 토글 줄의 '비상등'(와이퍼 다음, 프로토타입과 같은 자리)은 시연 전용(시뮬레이터 차 상태 밖,
 * 키보드 W와 같은 호출 — drive-spec-2). 맨 끝에 키보드 운전 안내 한 줄(demo/keyboard).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CarSection(s: CarSectionUi, enabled: Boolean, a: DemoActionsUi) {
    val car = s.car
    PnRow(stringResource(R.string.demo_gear)) {
        PnSeg(Gear.entries.map { it to it.name }, car.gear, a.shift, enabled)
    }
    PnRow(stringResource(R.string.demo_blinker)) {
        PnSeg(
            listOf(
                Blinker.LEFT to stringResource(R.string.demo_blinker_left),
                Blinker.OFF to stringResource(R.string.demo_blinker_off),
                Blinker.RIGHT to stringResource(R.string.demo_blinker_right),
            ),
            car.blinker, a.setBlinker, enabled,
        )
    }
    PnRow(stringResource(R.string.demo_pedals)) {
        PnHoldButton(stringResource(R.string.demo_brake), { a.pedal("brake", it) }, enabled)
        PnToggle(stringResource(R.string.demo_brake_lock), s.brakeLock, a.lockBrake, enabled)
        PnHoldButton(stringResource(R.string.demo_accel), { a.pedal("throttle", it) }, enabled)
    }
    PnRow(stringResource(R.string.demo_speed)) {
        PnSlider(s.manualTarget.toFloat(), 0f..120f, { "${it.toInt()}km/h" }, a.setTargetSpeed, enabled = enabled)
    }
    PnRow(stringResource(R.string.demo_steer)) {
        PnSlider(car.steer.toFloat(), -35f..35f, { (if (it > 0) "+" else "") + "${it.toInt()}°" }, a.setSteer, enabled = enabled)
        PnButton(stringResource(R.string.demo_steer_center), { a.setSteer(0f) }, enabled = enabled)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PnToggle(stringResource(R.string.demo_belt), car.beltDriver, a.setBelt, enabled)
        PnToggle(stringResource(R.string.demo_passenger), car.passenger, a.setPassenger, enabled)
        PnToggle(stringResource(R.string.demo_handbrake), car.handbrake, a.setHandbrake, enabled)
        PnToggle(stringResource(R.string.demo_wiper), car.wiper, a.setWiper, enabled)
        PnToggle(stringResource(R.string.demo_hazard), s.hazard, a.setHazard, enabled)
        PnToggle(stringResource(R.string.demo_low_beam), car.lowBeam, a.setLowBeam, enabled)
    }
    PnRow(stringResource(R.string.demo_day_night)) {
        PnSeg(listOf(false to stringResource(R.string.demo_day), true to stringResource(R.string.demo_night)), s.night, a.setNight, enabled)
    }
    PnRow(stringResource(R.string.demo_temp)) {
        PnSlider(car.outsideTemp.toFloat(), -10f..35f, { "${it.toInt()}°C" }, a.setTemp, enabled = enabled)
    }
    PnRow(stringResource(R.string.demo_model)) {
        PnSeg(s.models, s.model, a.setModel, enabled)
    }
    PnNote(s.readLine, color = BaroTheme.colors.t2)
    PnNote(stringResource(R.string.demo_keys_note))
}

/** 묶음 '내비'(panel-sim.js `renderNavi`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NaviSection(s: NaviSectionUi, enabled: Boolean, a: DemoActionsUi) {
    var type by rememberSaveable { mutableIntStateOf(0) }
    var dist by rememberSaveable { mutableIntStateOf(0) }
    PnRow(stringResource(R.string.demo_tbt_now)) { PnNote(s.now, color = BaroTheme.colors.t1) }
    PnRow(stringResource(R.string.demo_tbt_put_row)) {
        PnSelect(
            options = DemoTables.TBT_TYPES.mapIndexed { i, t -> Triple(i.toString(), t.label, "") },
            selected = type.toString(), placeholder = "", onSelect = { type = it.toInt() }, enabled = enabled,
        )
        PnSeg(DemoTables.TBT_DISTANCES.indices.map { it to "${DemoTables.TBT_DISTANCES[it]}m" }, dist, { dist = it }, enabled)
        PnButton(stringResource(R.string.demo_tbt_put), { a.injectTurn(DemoTables.TBT_TYPES[type].type, DemoTables.TBT_DISTANCES[dist]) }, enabled = enabled)
    }
    PnRow(stringResource(R.string.demo_limit)) {
        PnSeg(DemoTables.LIMITS.map { it to it.toString() }, s.limit, { a.setLimit(it) }, enabled)
        PnButton(stringResource(R.string.demo_limit_auto), { a.setLimit(null) }, enabled = enabled)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PnToggle(stringResource(R.string.demo_school), s.school, a.setSchool, enabled)
        PnToggle(stringResource(R.string.demo_jam), s.jam, a.setJam, enabled)
    }
    PnRow(stringResource(R.string.demo_cautions)) {
        PnButton(stringResource(R.string.demo_slippery), { a.inject("slippery") }, enabled = enabled)
        PnButton(stringResource(R.string.demo_downhill), { a.inject("downhill") }, enabled = enabled)
    }
    PnRow(null) {
        PnButton(stringResource(R.string.demo_navi_voice), { a.inject("naviVoice") }, enabled = enabled)
        PnButton(stringResource(R.string.demo_reroute), { a.inject("reroute") }, enabled = enabled)
        PnButton(stringResource(R.string.demo_arrive), { a.inject("arrive") }, enabled = enabled)
        PnButton(stringResource(R.string.demo_end_route), a.clearRoute, enabled = enabled)
        PnButton(stringResource(R.string.demo_pick_dest), a.openPicker, kind = PnKind.Primary, enabled = enabled)
    }
}

/** 내비 '목적지 고르기'(navi-ui.js picker): "어디로 갈까요?" + 즐겨찾기 · 최근 목적지. 누르면 그 장소로 안내(PR-1이면 그 장소 시나리오). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DestinationPicker(groups: List<PlaceGroupUi>, onPick: (String) -> Unit, onClose: () -> Unit) {
    val c = BaroTheme.colors
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.demo_pick_title), style = PnTextStyle.copy(fontWeight = FontWeight.Bold), color = c.t1, modifier = Modifier.weight(1f))
            PnButton(stringResource(R.string.demo_cancel), onClose)
        }
        PnNote(stringResource(R.string.demo_pick_note))
        for (g in groups) {
            Text(g.title, style = PnSmallStyle.copy(fontWeight = FontWeight.Bold), color = c.t3)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                g.places.forEach { p ->
                    val dist = if (p.here) stringResource(R.string.demo_pick_here) else if (p.km > 0) stringResource(R.string.demo_pick_km, String.format(java.util.Locale.ROOT, "%.1f", p.km)) else ""
                    PnButton(p.name + if (dist.isNotEmpty()) " · $dist" else "", { onPick(p.id) })
                }
            }
        }
    }
}

/** 묶음 '코칭 이벤트 넣기'(panel-sim.js `renderInject`): 그룹마다 버튼(항목 번호 표시). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InjectSection(groups: List<InjectGroupUi>, enabled: Boolean, onInject: (String) -> Unit) {
    PnNote(stringResource(R.string.demo_inject_note))
    for (g in groups) {
        Text(g.group, style = PnSmallStyle.copy(fontWeight = FontWeight.Bold), color = BaroTheme.colors.t3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            g.items.forEach { InjectButton(it, enabled) { onInject(it.id) } }
        }
    }
}

@Composable
private fun InjectButton(inj: InjectionInfo, enabled: Boolean, onClick: () -> Unit) {
    PnButton(inj.label + (inj.item?.let { " · $it" } ?: ""), onClick, enabled = enabled)
}

/** 상태 줄의 작은 점(재생 중). */
@Composable
internal fun Dot(on: Boolean) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) BaroTheme.colors.brand else BaroTheme.colors.line))
}

/** 배속 칸 차례: 실제 · ×1 · ×2 · ×4. [PACE_REAL]은 '실제'(배속 수가 아님). */
private const val PACE_REAL = 0
private val PACES = listOf(PACE_REAL, 1, 2, 4)

/** 패널 조작 묶음(상태 없는 컴포저블에 넘기는 람다들). */
internal class DemoActionsUi(
    val selectScenario: (String) -> Unit = {},
    val togglePlay: () -> Unit = {},
    val setSkill: (DriverSkill) -> Unit = {},
    val setSpeed: (Int) -> Unit = {},
    val setRealtime: () -> Unit = {},
    val setAutoplay: (Boolean) -> Unit = {},
    val skipNext: () -> Unit = {},
    val skipEnd: () -> Unit = {},
    val shift: (Gear) -> Unit = {},
    val setBlinker: (Blinker) -> Unit = {},
    val pedal: (String, Boolean) -> Unit = { _, _ -> },
    val lockBrake: (Boolean) -> Unit = {},
    val setTargetSpeed: (Float) -> Unit = {},
    val setSteer: (Float) -> Unit = {},
    val setBelt: (Boolean) -> Unit = {},
    val setPassenger: (Boolean) -> Unit = {},
    val setHandbrake: (Boolean) -> Unit = {},
    val setWiper: (Boolean) -> Unit = {},
    val setLowBeam: (Boolean) -> Unit = {},
    val setHazard: (Boolean) -> Unit = {},
    val setNight: (Boolean) -> Unit = {},
    val setTemp: (Float) -> Unit = {},
    val setModel: (String) -> Unit = {},
    val injectTurn: (String, Int) -> Unit = { _, _ -> },
    val setLimit: (Int?) -> Unit = {},
    val setSchool: (Boolean) -> Unit = {},
    val setJam: (Boolean) -> Unit = {},
    val clearRoute: () -> Unit = {},
    val openPicker: () -> Unit = {},
    val inject: (String) -> Unit = {},
)

/** 미리보기·시험용 조작 없음. */
internal val NoDemoActions = DemoActionsUi()

@Composable
internal fun PnStatusRow(status: String, playing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(playing)
        Text(status, style = PnMonoStyle.copy(fontWeight = FontWeight.Bold), color = BaroTheme.colors.t1)
    }
}
