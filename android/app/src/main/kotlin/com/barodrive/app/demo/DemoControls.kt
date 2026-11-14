package com.barodrive.app.demo

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barodrive.app.ui.theme.BaroTheme
import kotlinx.coroutines.delay

// 시연 패널의 개발 도구 모양 컨트롤 (프로토타입 shell/panel.js `ui` — button·seg·toggle·row·slider·select·note, css/panel.css `.pn-*`).
// 실제 앱 화면과 헷갈리지 않게 Pleos 컴포넌트가 아니라 작은 글자·회색 판·작은 버튼이다(설계 9).

/** 패널 글자(13~14px 상당). */
internal val PnTextStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
internal val PnSmallStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
internal val PnMonoStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)

/** 버튼 종류(`.pn-btn` · `.primary` · `.warn` · `.danger`). */
internal enum class PnKind { Normal, Primary, Warn, Danger }

/** 작은 버튼(`ui.button`). [selected]면 눌린 모양(seg의 aria-pressed). */
@Composable
internal fun PnButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PnKind = PnKind.Normal,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val c = BaroTheme.colors
    val (bg, fg) = when {
        !enabled -> c.btnGhostBg to c.t4
        selected || kind == PnKind.Primary -> c.brand to c.onBrand
        kind == PnKind.Warn -> c.caution to c.onCaution
        kind == PnKind.Danger -> c.danger to c.onDanger
        else -> c.btnGhostBg to c.t1
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(bg, shape)
            .border(BorderStroke(1.dp, if (selected) Color.Transparent else c.line), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PnTextStyle.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 누르고 있는 동안만 켜지는 버튼(패널 [브레이크]·[가속] — pointerdown/up). 짧게 톡 눌러도 [PEDAL_MIN_HOLD_MS]는 밟은 채로 둔다 —
 * 손가락 탭(0.1초 안팎)은 sim 틱(0.1초) 사이에 눌렀다 떼져 스냅숏에 안 보였다(PK-2 '브레이크를 밟았다 떼면 움직여요'가 안 움직임).
 * 키보드 페달(demo/keyboard)도 같은 최소 시간을 쓴다.
 */
@Composable
internal fun PnHoldButton(text: String, onHold: (Boolean) -> Unit, enabled: Boolean = true) {
    var held by remember { mutableStateOf(false) }
    val c = BaroTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (held) c.brand else c.btnGhostBg, shape)
            .border(BorderStroke(1.dp, c.line), shape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    held = true
                    onHold(true)
                    val down = SystemClock.uptimeMillis()
                    try {
                        tryAwaitRelease()
                        val left = PEDAL_MIN_HOLD_MS - (SystemClock.uptimeMillis() - down)
                        if (left > 0) delay(left)
                    } finally {
                        held = false
                        onHold(false)
                    }
                })
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PnTextStyle.copy(fontWeight = FontWeight.Bold), color = if (held) c.onBrand else if (enabled) c.t1 else c.t4)
    }
}

/** 페달([PnHoldButton]·키보드 ↑↓)을 짧게 눌러도 밟은 채로 두는 시간(ms) — ×1에서 sim 틱 3개. */
internal const val PEDAL_MIN_HOLD_MS = 300L

/** 붙은 선택 버튼들(`ui.seg`). */
@Composable
internal fun <T> PnSeg(items: List<Pair<T, String>>, value: T?, onChange: (T) -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (v, label) -> PnButton(label, { onChange(v) }, selected = v == value, enabled = enabled) }
    }
}

/** 체크 상자 + 글(`ui.toggle`). */
@Composable
internal fun PnToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = BaroTheme.colors
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(if (checked) c.brand else Color.Transparent, RoundedCornerShape(4.dp))
                .border(BorderStroke(2.dp, if (checked) c.brand else c.lineStrong), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", style = PnSmallStyle.copy(fontWeight = FontWeight.Bold), color = c.onBrand)
        }
        Text(label, style = PnTextStyle, color = if (enabled) c.t1 else c.t4)
    }
}

/** 이름 칸 + 컨트롤(`ui.row`). 넘치면 다음 줄로. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PnRow(label: String?, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) {
            Text(label, modifier = Modifier.width(112.dp), style = PnSmallStyle, color = BaroTheme.colors.t3)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** 안내 글(`ui.note`). */
@Composable
internal fun PnNote(text: String, modifier: Modifier = Modifier, color: Color = BaroTheme.colors.t3) {
    Text(text, modifier = modifier, style = PnSmallStyle, color = color)
}

/** 막대(`ui.slider`): 값 글을 옆에. 끄는 중 값은 패널이 가지고 있다가 놓으면 그대로 둔다. */
@Composable
internal fun PnSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    val shown = if (dragging) local else value
    val c = BaroTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Slider(
            value = shown.coerceIn(range.start, range.endInclusive),
            onValueChange = { v ->
                dragging = true
                local = Math.round(v).toFloat()
                onChange(local)
            },
            onValueChangeFinished = { dragging = false },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.width(260.dp),
            colors = SliderDefaults.colors(thumbColor = c.brand, activeTrackColor = c.brand, inactiveTrackColor = c.line),
        )
        Text(format(shown), style = PnMonoStyle, color = c.t2, modifier = Modifier.widthIn(min = 72.dp))
    }
}

/** 고르기(`ui.select`): 누르면 목록. [group]이 바뀌는 곳에 묶음 이름 줄(optgroup). */
@Composable
internal fun PnSelect(
    options: List<Triple<String, String, String>>,
    selected: String?,
    placeholder: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val c = BaroTheme.colors
    val label = options.firstOrNull { it.first == selected }?.second ?: placeholder
    Box(modifier) {
        PnButton("$label ▾", { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth())
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 560.dp)) {
            var group: String? = null
            for ((key, text, g) in options) {
                if (g.isNotEmpty() && g != group) {
                    Text(g, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = PnSmallStyle.copy(fontWeight = FontWeight.Bold), color = c.t3)
                }
                group = g
                DropdownMenuItem(
                    text = { Text(text, style = PnTextStyle, color = if (key == selected) c.brandText else c.t1) },
                    onClick = {
                        open = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

/** 접고 펴는 묶음(`.pn-sec`): 머리줄 = ▸/▾ + 이름 + id. */
@Composable
internal fun PnSection(title: String, id: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val c = BaroTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, c.line), RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bgCard)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (open) "▾" else "▸", style = PnTextStyle, color = c.t3)
            Text(title, style = PnTextStyle.copy(fontWeight = FontWeight.Bold), color = c.t1, modifier = Modifier.weight(1f))
            Text(id, style = PnMonoStyle, color = c.t4)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}
