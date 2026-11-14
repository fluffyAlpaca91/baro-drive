package com.barodrive.app.feature.parking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.barodrive.app.R
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.theme.BaroTheme

// PK-3 단계 그림 (목업 s08b wheelSvg·비상등·기어·P+사이드브레이크, parking-guide.js `wheel`·`visual`, components.css .vis .wheel .bigic
// .combo, parking-guide.css .gtile). 그림은 단계가 바뀔 때만 바뀌고 움직이지 않는다(목업의 핸들 회전 0.3초 전환을 끔, 구현계획 1.10).

/** 그림 자리(`.vis`): 남은 높이를 다 쓰고, 그림은 [아래 글 52]를 뺀 높이까지 줄어든다(`max-height: calc(100% - 52px)`). */
@Composable
internal fun GuideArtBox(visual: GuideVisual, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val room = (maxHeight - LABEL_ROOM).coerceAtLeast(0.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (visual.art) {
                GuideArt.WHEEL -> BigWheel(visual.rot, Modifier.size(min(WHEEL, room)))
                GuideArt.GEAR -> GearTile(visual.gear, min(TILE, room))
                GuideArt.FINAL -> Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    GearTile("P", min(TILE, room))
                    BaroIcon(R.drawable.ic_pbrake, contentDescription = null, size = min(BIG_ICON, room), tint = BaroTheme.colors.t1)
                }
                GuideArt.HAZARD -> BaroIcon(R.drawable.ic_hazard, contentDescription = null, size = min(BIG_ICON, room), tint = BaroTheme.colors.t1)
            }
            Text(
                visual.label,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = BaroTheme.colors.brandText,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

private val LABEL_ROOM = 52.dp
private val WHEEL = 260.dp
private val TILE = 180.dp
private val BIG_ICON = 220.dp

/** 기어 그림 (parking-guide.css `.gtile`): 180×180 모서리 44, 진한 바탕에 120 글자. 자리가 좁으면 같은 비율로 줄인다. */
@Composable
internal fun GearTile(gear: String, size: Dp) {
    val colors = BaroTheme.colors
    val k = size / TILE
    val fontSize = with(LocalDensity.current) { (120.dp * k).toSp() }
    Box(
        Modifier
            .size(size)
            .background(colors.t1, RoundedCornerShape(44.dp * k)),
        contentAlignment = Alignment.Center,
    ) {
        Text(gear, style = TextStyle(fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.ExtraBold), color = colors.bgWindow)
    }
}

/**
 * 큰 핸들 그림 (목업 s08b `wheelSvg`, parking-guide.js `wheel(rot)`): viewBox −20…120, 핸들(심볼 `i-wheel`)을 [rot]°만큼 돌리고
 * 돌렸으면 위쪽에 도는 방향 화살표(왼쪽이면 좌우를 뒤집음). 핸들 위쪽 표시는 민트 그대로(심볼의 #35D6C0).
 */
@Composable
internal fun BigWheel(rot: Float, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    val ink = colors.t1
    val arrow = colors.brandText
    val mark = colors.brand
    Canvas(modifier) {
        val u = size.minDimension / VIEW
        withTransform({
            scale(u, u, pivot = Offset.Zero)
            translate(PAD, PAD)
        }) {
            if (rot != 0f) {
                withTransform({
                    if (rot < 0f) {
                        translate(100f, 0f)
                        scale(-1f, 1f, pivot = Offset.Zero)
                    }
                }) {
                    drawPath(WheelPaths.arc, arrow, style = Stroke(width = 7f, cap = StrokeCap.Round))
                    drawPath(WheelPaths.head, arrow)
                }
            }
            rotate(rot, pivot = Offset(50f, 50f)) {
                drawCircle(ink, radius = 40f, center = Offset(50f, 50f), style = Stroke(width = 10f))
                drawCircle(ink, radius = 11f, center = Offset(50f, 52f))
                drawPath(WheelPaths.spokes, ink, style = Stroke(width = 9f, cap = StrokeCap.Round))
                drawRoundRect(mark, topLeft = Offset(45f, 5f), size = Size(10f, 12f), cornerRadius = CornerRadius(3f))
            }
        }
    }
}

private const val VIEW = 140f
private const val PAD = 20f

/** 핸들 그림 경로(index.html 심볼 `i-wheel` · parking-guide.js 화살표). */
private object WheelPaths {
    val arc by lazy { svgPath("M62 -10 A62 62 0 0 1 108 26") }
    val head by lazy { svgPath("M100 30l17 6 2-18z") }
    val spokes by lazy { svgPath("M13 47Q50 39 87 47M50 62v27") }
}

@Preview(widthDp = 760, heightDp = 360, showBackground = true, backgroundColor = 0xFF131417)
@Composable
private fun GuideArtPreview() {
    BaroTheme(darkTheme = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            GuideArtBox(GuideVisual(GuideArt.WHEEL, rot = 120f, label = "오른쪽 끝까지"), Modifier.size(300.dp, 340.dp))
            GuideArtBox(GuideVisual(GuideArt.WHEEL, rot = -120f, label = "왼쪽 끝까지"), Modifier.size(200.dp, 240.dp))
            GuideArtBox(GuideVisual(GuideArt.GEAR, gear = "R", label = "기어 R"), Modifier.size(200.dp, 240.dp))
        }
    }
}
