package com.barodrive.app.feature.parking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.barodrive.app.ui.theme.BaroColors
import com.barodrive.app.ui.theme.BaroTheme

// 주차 그림 (프로토타입 index.html 심볼 dg-par·dg-back·dg-front, parking.css .dg-*, 목업 s08a). 오른쪽 칸 그림이고,
// 왼쪽 칸은 좌우로 뒤집어 쓴다(목업 메모 '왼쪽 칸 그림은 오른쪽 칸을 좌우로 뒤집어 씀').

/** PK-1 카드 그림 220×140. [type] 'parallel'·'back'·'front', [side] 'L'이면 좌우를 뒤집는다. */
@Composable
internal fun ParkingDiagram(type: String, side: String, modifier: Modifier = Modifier.size(220.dp, 140.dp)) {
    val colors = BaroTheme.colors
    Canvas(modifier) {
        val u = size.width / VIEW_W
        withTransform({
            if (side == ParkingSpot.LEFT) scale(-1f, 1f, pivot = center)
            scale(u, u, pivot = Offset.Zero)
            translate(if (type == ParkingSpot.PARALLEL) -46f else -39f, 0f)
        }) {
            when (type) {
                ParkingSpot.BACK -> perpendicular(colors, front = false)
                ParkingSpot.FRONT -> perpendicular(colors, front = true)
                else -> parallel(colors)
            }
        }
    }
}

private const val VIEW_W = 220f

/** dg-par: 연석 · 앞뒤 차 · 빈 칸 · 내 차 · 들어가는 길. */
private fun DrawScope.parallel(c: BaroColors) {
    drawLine(c.t3, Offset(200f, 0f), Offset(200f, 140f), strokeWidth = 4f)
    car(c.parkCar, 164f, 4f, 28f, 44f)
    car(c.parkCar, 164f, 100f, 28f, 44f)
    slot(c, 160f, 52f, 36f, 44f)
    car(c.brand, 112f, 8f, 28f, 46f)
    car(c.onBrand.copy(alpha = 0.55f), 116f, 14f, 20f, 9f, radius = 3f)
    drawPath(DiagramPaths.parPath, c.brandText, style = PathStroke)
    drawPath(DiagramPaths.parHead, c.brandText)
}

/** dg-back · dg-front: 주차선 4줄 · 위아래 차 · 빈 칸 · 내 차(후방은 위, 전방은 아래) · 들어가는 길. */
private fun DrawScope.perpendicular(c: BaroColors, front: Boolean) {
    drawPath(DiagramPaths.lines, c.t3, style = Stroke(width = 3f))
    car(c.parkCar, 160f, 10f, 48f, 32f)
    car(c.parkCar, 160f, 98f, 48f, 32f)
    slot(c, 154f, 52f, 60f, 36f)
    val y = if (front) 88f else 14f
    car(c.brand, 80f, y, 30f, 48f)
    car(c.onBrand.copy(alpha = 0.55f), 84f, y + 6f, 22f, 9f, radius = 3f)
    drawPath(if (front) DiagramPaths.frontPath else DiagramPaths.backPath, c.brandText, style = PathStroke)
    drawPath(DiagramPaths.perpHead, c.brandText)
}

private fun DrawScope.car(color: Color, x: Float, y: Float, w: Float, h: Float, radius: Float = 8f) {
    drawRoundRect(color, topLeft = Offset(x, y), size = Size(w, h), cornerRadius = CornerRadius(radius))
}

/** 빈 칸(`.dg-slot`): 옅은 민트 + 민트 점선 3(7 6). */
private fun DrawScope.slot(c: BaroColors, x: Float, y: Float, w: Float, h: Float) {
    drawRoundRect(c.brandSoft, topLeft = Offset(x, y), size = Size(w, h), cornerRadius = CornerRadius(6f))
    drawRoundRect(
        c.brandText,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(6f),
        style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))),
    )
}

/** 들어가는 길(`.dg-path`): 민트 점선 4(8 7), 둥근 끝. */
private val PathStroke = Stroke(width = 4f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f)))

private object DiagramPaths {
    val parPath by lazy { svgPath("M126 60c0 22 52 6 52 28") }
    val parHead by lazy { svgPath("M171 84l7 14 7-14z") }
    val lines by lazy { svgPath("M150 4h68M150 48h68M150 92h68M150 136h68") }
    val backPath by lazy { svgPath("M95 68c0 4 4 2 10 2h52") }
    val frontPath by lazy { svgPath("M95 82c0-10 6-12 16-12h46") }
    val perpHead by lazy { svgPath("M156 63l14 7-14 7z") }
}

@Preview(widthDp = 700, heightDp = 160, showBackground = true, backgroundColor = 0xFF1D1E21)
@Composable
private fun ParkingDiagramPreview() {
    BaroTheme(darkTheme = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParkingDiagram(ParkingSpot.PARALLEL, ParkingSpot.RIGHT)
            ParkingDiagram(ParkingSpot.BACK, ParkingSpot.LEFT)
            ParkingDiagram(ParkingSpot.FRONT, ParkingSpot.RIGHT)
        }
    }
}
