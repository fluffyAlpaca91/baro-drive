package com.barodrive.app.feature.parking

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.barodrive.app.ui.theme.BaroColors
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.core.sim.parking.ParkCar
import com.barodrive.core.sim.parking.ParkPose
import com.barodrive.core.sim.parking.ParkScene
import com.barodrive.core.sim.parking.ParkedCar
import com.barodrive.core.sim.parking.Pt
import com.barodrive.core.sim.parking.Touch
import com.barodrive.core.sim.parking.VirtualParking as ParkModel
import kotlin.math.abs
import kotlin.math.min

// PK-2 위에서 본 그림 (프로토타입 parking-virtual.js `drawScene`·`drawParked`·`drawMe`, parking.css .pk2-*). 840×640(1m = 60px)을
// 자리 크기에 맞춰 같은 비율로 그린다. 장면은 m 단위(x 오른쪽 · y 아래), 내 차는 뒷바퀴 축 가운데가 원점이고 앞이 +x다.

/** 그림 크기(px 단위 viewBox). */
internal const val PK2_VIEW_W = 840f
internal const val PK2_VIEW_H = 640f

private const val PX = ParkModel.PX.toFloat()

/** 장면 + 내 차 + 닿은 곳. [pose]는 이어 그린 자세, [steer]는 앞바퀴각°. */
@Composable
internal fun VirtualParkingCanvas(
    scene: ParkScene,
    car: ParkCar,
    pose: ParkPose,
    steer: Double,
    touched: Boolean,
    done: Boolean,
    touch: Touch?,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = BaroTheme.colors
    Canvas(modifier.semantics { contentDescription = description }) {
        val u = min(size.width / PK2_VIEW_W, size.height / PK2_VIEW_H)
        val left = (size.width - PK2_VIEW_W * u) / 2f
        val top = (size.height - PK2_VIEW_H * u) / 2f
        withTransform({
            translate(left, top)
            scale(u, u, pivot = Offset.Zero)
        }) {
            // SVG viewBox처럼 840×640 밖은 그리지 않는다 — 연석·차선이 그림 밖(전진·후진 줄, 좌우 여백)까지 번지던 것.
            clipRect(0f, 0f, PK2_VIEW_W, PK2_VIEW_H) {
                drawScene(scene, colors, done)
                drawMe(car, pose, steer, colors, touched)
                if (touched && touch != null) {
                    val at = Offset((touch.x * PX).toFloat(), (touch.y * PX).toFloat())
                    drawCircle(colors.danger, radius = 26f, center = at, style = Stroke(width = 6f))
                    drawCircle(colors.danger, radius = 9f, center = at)
                }
            }
        }
    }
}

private fun px(v: Double): Float = (v * PX).toFloat()

private fun Pt.offset(): Offset = Offset(px(x), px(y))

private fun polygon(points: List<Pt>): Path = Path().apply {
    points.forEachIndexed { i, q -> if (i == 0) moveTo(px(q.x), px(q.y)) else lineTo(px(q.x), px(q.y)) }
    close()
}

/** 장면 (parking-virtual.js `drawScene`): 바닥 · 인도 · 차선 · 칸 · 주차선 · 멈춤턱 · 연석 · 세워 둔 차. */
private fun DrawScope.drawScene(sc: ParkScene, c: BaroColors, done: Boolean) {
    drawRect(c.bgCard, topLeft = Offset.Zero, size = Size(px(sc.w), px(sc.h)))
    for (curb in sc.curbs) drawPath(polygon(curb.side), c.btnGhostBg)
    for (d in sc.dashes) {
        drawLine(c.t4, d[0].offset(), d[1].offset(), strokeWidth = 6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(150f, 120f)))
    }
    val b = sc.slot.box
    val slotTop = Offset(px(b.x0), px(b.y0))
    val slotSize = Size(px(b.x1 - b.x0), px(b.y1 - b.y0))
    drawRoundRect(c.brandSoft, topLeft = slotTop, size = slotSize, cornerRadius = CornerRadius(10f))
    drawRoundRect(
        c.brandText,
        topLeft = slotTop,
        size = slotSize,
        cornerRadius = CornerRadius(10f),
        style = if (done) Stroke(width = 6f) else Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 18f))),
    )
    for (l in sc.lines) drawLine(c.t3, l[0].offset(), l[1].offset(), strokeWidth = 6f)
    for (st in sc.stops) {
        val x0 = min(st.a.x, st.b.x)
        val y0 = min(st.a.y, st.b.y)
        val vertical = abs(st.a.x - st.b.x) < 1e-6
        if (vertical) {
            drawRoundRect(c.t4, topLeft = Offset(px(x0 - 0.08), px(y0)), size = Size(px(0.16), px(abs(st.b.y - st.a.y))), cornerRadius = CornerRadius(4f))
        } else {
            drawRoundRect(c.t4, topLeft = Offset(px(x0), px(y0 - 0.08)), size = Size(px(abs(st.b.x - st.a.x)), px(0.16)), cornerRadius = CornerRadius(4f))
        }
    }
    for (curb in sc.curbs) drawLine(c.t3, curb.line[0].offset(), curb.line[1].offset(), strokeWidth = 8f)
    for (k in sc.cars) drawParked(k, c)
}

/** 세워 둔 차 (parking-virtual.js `drawParked`): 가운데 기준 사각형 + 앞·뒤 유리. */
private fun DrawScope.drawParked(k: ParkedCar, c: BaroColors) {
    val l = px(k.length)
    val w = px(k.width)
    translate(px(k.x), px(k.y)) {
        rotate(Math.toDegrees(k.h).toFloat(), pivot = Offset.Zero) {
            drawRoundRect(c.parkCar, topLeft = Offset(-l / 2, -w / 2), size = Size(l, w), cornerRadius = CornerRadius(20f))
            val glass = c.onBrand.copy(alpha = 0.55f)
            drawRoundRect(glass, topLeft = Offset(l / 2 - 1.75f * PX, -w / 2 + 10), size = Size(0.55f * PX, w - 20), cornerRadius = CornerRadius(8f))
            drawRoundRect(glass, topLeft = Offset(-l / 2 + 0.32f * PX, -w / 2 + 12), size = Size(0.34f * PX, w - 24), cornerRadius = CornerRadius(6f))
        }
    }
}

/** 내 차 (parking-virtual.js `drawMe`): 거울 · 몸통(닿으면 붉은 테두리) · 뒷바퀴 · 유리 · 앞바퀴(조향각만큼). */
private fun DrawScope.drawMe(car: ParkCar, pose: ParkPose, steer: Double, c: BaroColors, touched: Boolean) {
    val f = px(car.wheelbase + car.front)
    val b = -px(car.rear)
    val w = px(car.width)
    val wb = px(car.wheelbase)
    val t = px(car.width / 2 - 0.22)
    translate(px(pose.x), px(pose.y)) {
        rotate(Math.toDegrees(pose.h).toFloat(), pivot = Offset.Zero) {
            drawRoundRect(c.brand, topLeft = Offset(f - 1.98f * PX, -w / 2 - 9), size = Size(12f, 11f), cornerRadius = CornerRadius(3f))
            drawRoundRect(c.brand, topLeft = Offset(f - 1.98f * PX, w / 2 - 2), size = Size(12f, 11f), cornerRadius = CornerRadius(3f))
            drawRoundRect(c.brand, topLeft = Offset(b, -w / 2), size = Size(f - b, w), cornerRadius = CornerRadius(20f))
            if (touched) {
                drawRoundRect(c.danger, topLeft = Offset(b, -w / 2), size = Size(f - b, w), cornerRadius = CornerRadius(20f), style = Stroke(width = 8f))
            }
            val rear = c.onBrand.copy(alpha = 0.45f)
            wheel(rear, 0f, -t, 0f)
            wheel(rear, 0f, t, 0f)
            val glass = c.onBrand.copy(alpha = 0.55f)
            drawRoundRect(glass, topLeft = Offset(f - 1.78f * PX, -w / 2 + 10), size = Size(0.56f * PX, w - 20), cornerRadius = CornerRadius(9f))
            drawRoundRect(glass, topLeft = Offset(b + 0.3f * PX, -w / 2 + 12), size = Size(0.36f * PX, w - 24), cornerRadius = CornerRadius(7f))
            wheel(c.onBrand, wb, -t, steer.toFloat())
            wheel(c.onBrand, wb, t, steer.toFloat())
        }
    }
}

/** 바퀴 하나(40×14, 모서리 4)를 ([x], [y])에 [deg]°만큼 돌려 그린다. */
private fun DrawScope.wheel(color: Color, x: Float, y: Float, deg: Float) {
    translate(x, y) {
        rotate(deg, pivot = Offset.Zero) {
            drawRoundRect(color, topLeft = Offset(-20f, -7f), size = Size(40f, 14f), cornerRadius = CornerRadius(4f))
        }
    }
}
