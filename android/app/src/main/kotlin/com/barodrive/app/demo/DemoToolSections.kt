package com.barodrive.app.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.feature.history.BaroFormat
import com.barodrive.app.feature.share.FamilyCard
import com.barodrive.app.session.api.Speaker
import com.barodrive.app.session.api.VoiceEntry
import com.barodrive.app.session.api.fmtT
import com.barodrive.app.ui.components.CoachAvatar
import com.barodrive.app.ui.components.CoachFace
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.core.model.SignalKey
import java.time.OffsetDateTime

/** '데모 도구' 묶음 조작. */
internal class ToolActionsUi(
    val applyPreset: (String) -> Unit = {},
    val resetApp: () -> Unit = {},
    val resetPanel: () -> Unit = {},
    val setNetwork: (Boolean) -> Unit = {},
    val setTown: (String) -> Unit = {},
    val reloadCongestion: () -> Unit = {},
    val setProfile: (String) -> Unit = {},
    val setOtherApp: (Boolean) -> Unit = {},
    val addExampleCourses: () -> Unit = {},
    val startRules: () -> Unit = {},
    val setCap: (SignalKey, Boolean) -> Unit = { _, _ -> },
    val allCaps: () -> Unit = {},
    val setLogFilter: (LogFilter) -> Unit = {},
    val clearLog: () -> Unit = {},
    val copyLog: () -> Unit = {},
    val clearFamily: () -> Unit = {},
)

/**
 * 묶음 '데모 도구'(설계 9 6번, panel.js reset-app·reset-panel · panel-demo.js preset·congestion·profile·other-app·my-courses·rules ·
 * share.js network · course.js town): [처음 실행으로 되돌리기](한 번 더 확인) · 미리 만든 상태 + [적용] · 네트워크 · 동네 · 혼잡도 ·
 * 프로필 · 다른 앱 · 예시 내 코스 · 규칙 확인 · [패널 설정 초기화].
 */
@Composable
internal fun ToolsSection(s: ToolsSectionUi, a: ToolActionsUi) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    if (!confirm) {
        PnButton(stringResource(R.string.demo_reset_app), { confirm = true }, kind = PnKind.Warn, modifier = Modifier.fillMaxWidth())
    } else {
        Column(
            Modifier.fillMaxWidth().border(BorderStroke(1.dp, BaroTheme.colors.caution), RoundedCornerShape(8.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PnNote(stringResource(R.string.demo_reset_app_note))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PnButton(stringResource(R.string.demo_reset_app_go), { confirm = false; a.resetApp() }, kind = PnKind.Danger)
                PnButton(stringResource(R.string.demo_cancel), { confirm = false })
            }
        }
    }
    // 미리 만든 상태: 마지막에 적용한 것을 고른 채로(T11-10)
    var cur by rememberSaveable(s.preset) { mutableStateOf(s.preset?.takeIf { id -> s.presets.any { it.id == id } } ?: s.presets.firstOrNull()?.id) }
    PnRow(stringResource(R.string.demo_presets)) {
        PnSelect(
            options = s.presets.map { Triple(it.id, it.title, "") }, selected = cur, placeholder = "", onSelect = { cur = it },
            modifier = Modifier.width(300.dp),
        )
        PnButton(stringResource(R.string.demo_apply), { cur?.let(a.applyPreset) }, kind = PnKind.Primary)
    }
    s.presets.firstOrNull { it.id == cur }?.let { PnNote(stringResource(R.string.demo_preset_desc, it.desc.ifEmpty { it.title })) }
    PnRow(stringResource(R.string.demo_network)) {
        PnSeg(listOf(true to stringResource(R.string.demo_network_on), false to stringResource(R.string.demo_network_off)), s.network, a.setNetwork)
    }
    PnNote(if (s.queued > 0) stringResource(R.string.demo_network_queued, s.queued) else stringResource(R.string.demo_network_note))
    PnRow(stringResource(R.string.demo_town)) {
        PnSeg(s.towns.map { it.id to it.name }, s.town, a.setTown)
    }
    PnNote(stringResource(R.string.demo_town_note))
    PnRow(stringResource(R.string.demo_congestion)) {
        PnButton(stringResource(R.string.demo_congestion_reload), a.reloadCongestion)
    }
    PnNote(
        stringResource(if (s.congestionSeed <= 1) R.string.demo_congestion_first else R.string.demo_congestion_more, s.congestionSeed) +
            if (s.tripOff) stringResource(R.string.demo_congestion_trip_off) else "",
    )
    PnRow(stringResource(R.string.demo_profile)) {
        PnSeg(listOf("p1" to stringResource(R.string.demo_profile_1), "p2" to stringResource(R.string.demo_profile_2)), s.profileId, a.setProfile)
    }
    PnNote(stringResource(R.string.demo_profile_note))
    PnToggle(stringResource(R.string.demo_other_app), s.otherApp, a.setOtherApp)
    PnNote(stringResource(R.string.demo_other_app_note))
    PnRow(stringResource(R.string.demo_my_courses)) {
        PnButton(stringResource(R.string.demo_example_courses), a.addExampleCourses)
    }
    PnNote(stringResource(R.string.demo_example_courses_note))
    PnButton(stringResource(R.string.demo_rules), a.startRules, modifier = Modifier.fillMaxWidth())
    PnNote(stringResource(R.string.demo_rules_note))
    PnButton(stringResource(R.string.demo_reset_panel), a.resetPanel, modifier = Modifier.fillMaxWidth())
}

/** 묶음 '신호 지원'(설계 7.8, panel-demo.js caps): 토글 12개(켬 = 읽힘) · 줄마다 꺼지는 항목과 대신 하는 것 · [모두 읽기]. */
@Composable
internal fun CapsSection(s: CapsSectionUi, enabled: Boolean, a: ToolActionsUi) {
    PnNote(stringResource(R.string.demo_caps_note))
    for (r in s.rows) {
        Column {
            PnToggle(r.row.name, r.readable, { a.setCap(r.row.key, it) }, enabled)
            PnNote(
                (if (r.items.isNotEmpty()) stringResource(R.string.demo_caps_items, r.items.joinToString("·")) else "") + r.row.alt,
                modifier = Modifier.padding(start = 34.dp),
            )
        }
    }
    PnNote(s.summary, color = BaroTheme.colors.t1)
    PnButton(stringResource(R.string.demo_caps_all), a.allCaps, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

/**
 * 묶음 '말한 내용'(설계 9 7번, panel-voice.js): 시간순 기록(오래된 것 위, 맨 아래로 따라감). 줄 = sim 시각 · 말한 이(코치 아바타/내비) ·
 * 분류 칩 · 항목 번호 · 문장. 막힌 말은 회색 + '막힘 · 이유', 정차 중 말·빨리 감기 표시. 거르기 [코치] [내비] [막힌 말] · [지우기] · [복사].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceSection(s: VoiceSectionUi, a: ToolActionsUi) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PnToggle(stringResource(R.string.demo_log_coach), s.filter.coach, { a.setLogFilter(s.filter.copy(coach = it)) })
        PnToggle(stringResource(R.string.demo_log_navi), s.filter.navi, { a.setLogFilter(s.filter.copy(navi = it)) })
        PnToggle(stringResource(R.string.demo_log_blocked), s.filter.blocked, { a.setLogFilter(s.filter.copy(blocked = it)) })
        PnButton(stringResource(R.string.demo_log_clear), a.clearLog)
        PnButton(stringResource(R.string.demo_log_copy), a.copyLog)
    }
    if (s.total > 0) {
        PnNote(
            stringResource(R.string.demo_log_summary, s.coach, s.blocked, s.navi) +
                if (s.shown != s.total) stringResource(R.string.demo_log_shown, s.shown) else "",
            color = BaroTheme.colors.t2,
        )
    }
    val scroll = rememberScrollState()
    // 새 줄이 오면 맨 아래로 따라간다(panel-voice.js `follow`)
    LaunchedEffect(s.rows.lastOrNull()?.seq) { scroll.scrollTo(scroll.maxValue) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .background(BaroTheme.colors.bgWindow, RoundedCornerShape(8.dp))
            .verticalScroll(scroll)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (s.rows.isEmpty()) {
            PnNote(stringResource(if (s.total > 0) R.string.demo_log_filtered_out else R.string.demo_log_empty))
        } else {
            s.rows.forEach { VoiceRow(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceRow(e: VoiceEntry) {
    val c = BaroTheme.colors
    val dim = e.blocked
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(fmtT(e.t), style = PnMonoStyle, color = c.t3, modifier = Modifier.width(56.dp))
        if (e.speaker == Speaker.NAVI) {
            Box(Modifier.size(24.dp).background(c.brandSoft, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Text("N", style = PnSmallStyle.copy(fontWeight = FontWeight.Bold), color = c.brandText)
            }
        } else {
            CoachAvatar(CoachFace.fromId(e.coach?.let { coachId(it) }), size = 24.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val chip = if (e.speaker == Speaker.NAVI) stringResource(R.string.demo_log_navi_chip) else e.category?.label
                chip?.let { Chip(it) }
                e.item?.let { Text(it, style = PnMonoStyle.copy(fontWeight = FontWeight.Bold), color = c.t2) }
                if (e.blocked) Text(stringResource(R.string.demo_log_blocked_reason, e.reason?.label ?: "—"), style = PnSmallStyle, color = c.caution)
                if (e.stationary) Text(stringResource(R.string.demo_log_stationary), style = PnSmallStyle, color = c.t3)
                if (e.ff) Text(stringResource(R.string.demo_log_ff), style = PnSmallStyle, color = c.t3)
            }
            Text(e.text, style = PnTextStyle, color = if (dim) c.t4 else c.t1)
        }
    }
}

@Composable
private fun Chip(text: String) {
    val c = BaroTheme.colors
    Box(Modifier.background(c.btnGhostBg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, style = PnSmallStyle, color = c.t2)
    }
}

private fun coachId(coach: com.barodrive.core.model.Coach): String = when (coach) {
    com.barodrive.core.model.Coach.SIB -> "sib"
    com.barodrive.core.model.Coach.MOM -> "mom"
    com.barodrive.core.model.Coach.DAD -> "dad"
}

/** 묶음 '가족 휴대폰 미리보기'(설계 9 8번, share.js family): 휴대폰 모양 틀(폭 300) 안 받은 요약 카드 · [받은 카드 지우기]. */
@Composable
internal fun FamilySection(cards: List<FamilyCard>, a: ToolActionsUi) {
    val c = BaroTheme.colors
    PnNote(stringResource(R.string.demo_family_note))
    Column(
        Modifier
            .width(320.dp)
            .background(c.bgWindow, RoundedCornerShape(28.dp))
            .border(BorderStroke(6.dp, c.lineStrong), RoundedCornerShape(28.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 80.dp, height = 6.dp).background(c.lineStrong, RoundedCornerShape(3.dp)))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.demo_family_head), style = PnTextStyle.copy(fontWeight = FontWeight.Bold), color = c.t1, modifier = Modifier.weight(1f))
            if (cards.isNotEmpty()) Text(stringResource(R.string.demo_family_count, cards.size), style = PnSmallStyle, color = c.t3)
        }
        if (cards.isEmpty()) {
            PnNote(stringResource(R.string.demo_family_empty))
        } else {
            cards.forEach { FamilyCardView(it) }
        }
        Box(Modifier.height(4.dp))
    }
    PnButton(stringResource(R.string.demo_family_clear), a.clearFamily, enabled = cards.isNotEmpty())
}

@Composable
private fun FamilyCardView(card: FamilyCard) {
    val c = BaroTheme.colors
    val got = runCatching { OffsetDateTime.parse(card.at).toZonedDateTime() }.getOrNull()
    Column(
        Modifier.fillMaxWidth().background(c.bgCard, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row {
            Text(
                if (card.from.isNotEmpty()) stringResource(R.string.demo_family_from, card.from) else stringResource(R.string.demo_family_from_unknown),
                style = PnSmallStyle, color = c.t3, modifier = Modifier.weight(1f),
            )
            got?.let { Text(BaroFormat.md(it.toLocalDate()) + " " + BaroFormat.time(it), style = PnSmallStyle, color = c.t3) }
        }
        Text(card.title, style = PnTextStyle.copy(fontWeight = FontWeight.Bold), color = c.t1)
        if (card.whenText.isNotEmpty()) PnNote(stringResource(R.string.demo_family_when, card.whenText))
        card.lines.forEach { l ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(l.label, style = PnSmallStyle, color = c.t3, modifier = Modifier.width(96.dp))
                Text(l.text, style = PnSmallStyle.copy(fontWeight = FontWeight.Bold), color = c.t1)
            }
        }
        PnNote(stringResource(R.string.demo_family_foot), color = c.t4)
    }
}
