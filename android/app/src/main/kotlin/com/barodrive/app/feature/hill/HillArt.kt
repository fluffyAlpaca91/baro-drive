package com.barodrive.app.feature.hill

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.barodrive.app.R
import com.barodrive.app.feature.parking.TextAnchor
import com.barodrive.app.feature.parking.drawSvgText
import com.barodrive.app.feature.parking.svgPath
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.theme.BaroTheme

// 언덕 그림 (목업 s13a 3단계 페달 · s13b 바퀴 방향, hill.js `pedals`·`wheelPic`, hill.css .pedals .hp .hl-count).
// 움직이는 중 화면이라 움직이지 않는다. 자리가 좁거나(가로 창) 넓어도 같은 비율로 맞춘다.

/** 프로토타입 심볼 id('i-brake') → 아이콘. 모르는 id는 정보 아이콘. */
@DrawableRes
internal fun protoIcon(id: String?): Int = when (id) {
    "i-brake" -> R.drawable.ic_brake
    "i-pbrake" -> R.drawable.ic_pbrake
    "i-pedals" -> R.drawable.ic_pedals
    "i-up" -> R.drawable.ic_up
    "i-slow" -> R.drawable.ic_slow
    "i-pulse" -> R.drawable.ic_pulse
    "i-check" -> R.drawable.ic_check
    "i-wheel" -> R.drawable.ic_wheel
    "i-hazard" -> R.drawable.ic_hazard
    "i-flag-check" -> R.drawable.ic_flag_check
    "i-flag" -> R.drawable.ic_flag
    "i-rain" -> R.drawable.ic_rain
    "i-snow" -> R.drawable.ic_snow
    "i-sun" -> R.drawable.ic_sun
    "i-temp" -> R.drawable.ic_temp
    "i-wiper" -> R.drawable.ic_wiper
    "i-slowdown" -> R.drawable.ic_slow
    else -> R.drawable.ic_info
}

/** HL-1 그림 자리(`.vis`, 남은 높이를 다 씀): 단계 아이콘(220) · 페달(420×270) · 나눠 밟기 숫자. */
@Composable
internal fun HillVisBox(vis: HillVis, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        when (vis) {
            is HillVis.Icon -> BaroIcon(protoIcon(vis.icon), contentDescription = null, size = min(BIG_ICON, maxHeight), tint = BaroTheme.colors.t1)
            HillVis.Pedals -> Pedals(fit(PEDALS_W, PEDALS_H, maxWidth, maxHeight))
            is HillVis.Count -> PressCount(vis.n)
        }
    }
}

private val BIG_ICON = 220.dp
private const val PEDALS_W = 420f
private const val PEDALS_H = 270f

/** 그림 크기(설계 크기 [w]×[h] dp를 넘지 않고 자리에 맞춤, 비율 그대로). */
internal fun fit(w: Float, h: Float, maxW: Dp, maxH: Dp): Pair<Dp, Dp> {
    val k = minOf(1f, maxW.value / w, maxH.value / h).coerceAtLeast(0f)
    return (w * k).dp to (h * k).dp
}

/** 오르막 3단계 그림 (목업 s13a, hill.js `pedals`): 브레이크 → 엑셀 살살 화살표. viewBox 420×270. */
@Composable
internal fun Pedals(size: Pair<Dp, Dp>) {
    val colors = BaroTheme.colors
    val measurer = rememberTextMeasurer()
    val brake = stringResource(R.string.hl_pedal_brake)
    val go = stringResource(R.string.hl_pedal_go)
    Canvas(Modifier.size(size.first, size.second)) {
        val u = this.size.width / PEDALS_W
        withTransform({ scale(u, u, pivot = Offset.Zero) }) {
            drawPath(PedalPaths.arc, colors.brandText, style = Stroke(width = 8f, cap = StrokeCap.Round))
            drawPath(PedalPaths.head, colors.brandText)
            drawRoundRect(colors.lineStrong, topLeft = Offset(60f, 110f), size = Size(150f, 104f), cornerRadius = CornerRadius(22f))
            drawRoundRect(colors.brandSoft, topLeft = Offset(290f, 80f), size = Size(70f, 150f), cornerRadius = CornerRadius(20f))
            drawRoundRect(colors.brandText, topLeft = Offset(290f, 80f), size = Size(70f, 150f), cornerRadius = CornerRadius(20f), style = Stroke(5f))
        }
        drawSvgText(measurer, brake, 135f, 256f, u, 26f, colors.t2, TextAnchor.MIDDLE)
        drawSvgText(measurer, go, 325f, 262f, u, 26f, colors.t2, TextAnchor.MIDDLE)
    }
}

private object PedalPaths {
    val arc by lazy { svgPath("M135 92C160 18 270 8 318 52") }
    val head by lazy { svgPath("M300 58l30 6-10-28z") }
}

/** 내리막 2단계: '브레이크 나눠 밟기 n번' 숫자를 크게(설계 4.2 HL-1, hill.css `.hl-count`: 144/150 · '번' 60, 아래 36/44 3차 글). */
@Composable
internal fun PressCount(n: Int) {
    val colors = BaroTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                n.toString(),
                modifier = Modifier.alignByBaseline(),
                style = TextStyle(fontSize = 144.sp, lineHeight = 150.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-4).sp),
                color = colors.brandText,
            )
            Text(
                stringResource(R.string.hl_count_unit),
                modifier = Modifier.alignByBaseline(),
                style = TextStyle(fontSize = 60.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold),
                color = colors.brandText,
            )
        }
        Text(
            stringResource(R.string.hl_count_label),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = colors.t2,
        )
    }
}

/** HL-2 그림 자리: 바퀴 방향 그림(400×300)을 자리에 맞춘다. */
@Composable
internal fun HillParkPictureBox(park: HillParkView, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        HillParkPicture(park, fit(HP_W, HP_H, maxWidth, maxHeight))
    }
}

private const val HP_W = 400f
private const val HP_H = 300f

/**
 * 바퀴 방향 그림 (목업 s13b, hill.js `wheelPic`): 길 · 가운데 점선 · 연석(또는 길 가장자리) · 맞는 쪽 화살표 · 위에서 본 차와 앞바퀴(맞는 쪽으로
 * 26° — 상황을 안 골랐으면 똑바로). 글자는 '연석'·'길 가장자리'와 차 앞쪽 설명.
 */
@Composable
internal fun HillParkPicture(park: HillParkView, size: Pair<Dp, Dp>) {
    val colors = BaroTheme.colors
    val measurer = rememberTextMeasurer()
    val curbText = stringResource(R.string.hl_hp_curb)
    val edgeText = stringResource(R.string.hl_hp_edge)
    val side = when (park.side) {
        "L" -> -1
        "R" -> 1
        else -> 0
    }
    Canvas(Modifier.size(size.first, size.second)) {
        val u = this.size.width / HP_W
        withTransform({ scale(u, u, pivot = Offset.Zero) }) {
            drawRoundRect(colors.bgCard, topLeft = Offset(20f, 0f), size = Size(340f, 300f), cornerRadius = CornerRadius(20f))
            drawLine(
                colors.t3,
                Offset(60f, 10f),
                Offset(60f, 290f),
                strokeWidth = 4f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f)),
            )
            if (park.curb) {
                drawRoundRect(colors.parkCar, topLeft = Offset(344f, 0f), size = Size(22f, 300f), cornerRadius = CornerRadius(6f))
            } else {
                drawLine(colors.t3, Offset(352f, 10f), Offset(352f, 290f), strokeWidth = 4f)
            }
            if (side != 0) {
                val (arc, head) = if (side > 0) HpPaths.arcR to HpPaths.headR else HpPaths.arcL to HpPaths.headL
                drawPath(arc, colors.brandText, style = Stroke(width = 7f, cap = StrokeCap.Round))
                drawPath(head, colors.brandText)
            }
            val a = 26f * side
            wheel(colors.t1, 140f, 198f)
            wheel(colors.t1, 242f, 198f)
            rotate(a, pivot = Offset(149f, 105f)) { wheel(colors.t1, 140f, 82f) }
            rotate(a, pivot = Offset(251f, 105f)) { wheel(colors.t1, 242f, 82f) }
            drawRoundRect(colors.brand, topLeft = Offset(152f, 52f), size = Size(96f, 214f), cornerRadius = CornerRadius(30f))
            drawRoundRect(colors.onBrand.copy(alpha = 0.55f), topLeft = Offset(164f, 90f), size = Size(72f, 40f), cornerRadius = CornerRadius(10f))
            drawRoundRect(colors.onBrand.copy(alpha = 0.4f), topLeft = Offset(168f, 216f), size = Size(64f, 26f), cornerRadius = CornerRadius(8f))
        }
        if (park.curb) {
            drawSvgText(measurer, curbText, 334f, 288f, u, 22f, colors.t3, TextAnchor.END)
        } else {
            drawSvgText(measurer, edgeText, 340f, 288f, u, 22f, colors.t3, TextAnchor.END)
        }
        drawSvgText(measurer, park.front, 76f, 288f, u, 22f, colors.t3, TextAnchor.START)
    }
}

/** 바퀴 하나(18×46, 모서리 6). */
private fun DrawScope.wheel(color: androidx.compose.ui.graphics.Color, x: Float, y: Float) {
    drawRoundRect(color, topLeft = Offset(x, y), size = Size(18f, 46f), cornerRadius = CornerRadius(6f))
}

private object HpPaths {
    val arcR by lazy { svgPath("M205 26q60-8 92 30") }
    val headR by lazy { svgPath("M284 60l24 10-4-26z") }
    val arcL by lazy { svgPath("M195 26q-60-8-92 30") }
    val headL by lazy { svgPath("M116 60l-24 10 4-26z") }
}

@Preview(widthDp = 840, heightDp = 340, showBackground = true, backgroundColor = 0xFF131417)
@Composable
private fun HillArtPreview() {
    BaroTheme(darkTheme = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Pedals(fit(PEDALS_W, PEDALS_H, 300.dp, 300.dp))
            HillParkPicture(
                HillParkView(side = "R", curb = true, front = "차 앞쪽이 내리막"),
                fit(HP_W, HP_H, 260.dp, 300.dp),
            )
            PressCount(3)
        }
    }
}
