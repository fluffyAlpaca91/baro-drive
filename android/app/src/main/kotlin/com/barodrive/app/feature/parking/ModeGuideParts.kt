package com.barodrive.app.feature.parking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barodrive.app.R
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

// 움직이는 중 화면(PK-3 · HL-1 · HL-2)이 같이 쓰는 조각 (프로토타입 components.css '단계 안내' 묶음: .stepbar .do-l .do-t .sig).
// 움직이는 중 화면이라 움직임·스크롤이 없다(설계 2.5).

/**
 * 지금 할 일 (목업 `.do-l` + `.do-t`): 24/28 민트 라벨 + 44/56 아주 굵은 문장(두 줄 자리 112 확보, 줄은 고르게 — `text-wrap: balance`).
 * [label]이 null이면 문장만(HL-2). 라벨과 문장 사이는 PK-3 4 · HL-1 8(hill.css `.hl-do`).
 */
@Composable
internal fun GuideInstruction(label: String?, text: String, modifier: Modifier = Modifier, gap: Dp = 4.dp) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = BaroTheme.colors.brandText,
            )
        }
        Text(text, modifier = Modifier.heightIn(min = 112.dp), style = DoTextStyle)
    }
}

/** `.do-t` 44/56 800, 자간 −0.6. */
private val DoTextStyle = TextStyle(
    fontSize = 44.sp,
    lineHeight = 56.sp,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = (-0.6).sp,
    lineBreak = LineBreak.Heading,
)

/**
 * 신호 안내 줄 (목업 `.sig`): 가운데 맞춘 아이콘(32) + 24/28 3차 글자. [warn]이면 주의 줄(hill.css `.sig.warn`: 주의 글자색·아주 굵게,
 * 아이콘 `i-hazard` 주의 색). [icon] = false면 글만(HL-2 까닭 줄 `p.sig`).
 */
@Composable
internal fun ModeSignalLine(text: String, modifier: Modifier = Modifier, warn: Boolean = false, icon: Boolean = true) {
    val colors = BaroTheme.colors
    val color = if (warn) colors.cautionText else colors.t3
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            BaroIcon(
                if (warn) R.drawable.ic_hazard else R.drawable.ic_info,
                contentDescription = null,
                size = Dimens.iconS,
                tint = if (warn) colors.caution else color,
            )
        }
        Text(
            text,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (warn) FontWeight.ExtraBold else FontWeight.Normal),
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 단계 막대 (목업 `.stepbar`): 왼쪽 '4 / 8'(36/44, '/ 8'은 3차 글자) + 같은 폭 칸들(높이 14, 사이 8). 지난 칸은 옅은 민트(민트 글자색 50%),
 * 지금 칸([nowIndex], 0부터)은 민트. [done]이면 모두 지난 칸이고, [highlight]면 숫자를 민트로(PK-3 다 마침 `[data-done="1"] .tm`).
 */
@Composable
internal fun ModeStepBar(
    shown: Int,
    of: String,
    total: Int,
    nowIndex: Int,
    done: Boolean,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val colors = BaroTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (highlight) colors.brandText else colors.t1)) { append(shown.toString()) }
                append(" ")
                withStyle(SpanStyle(color = colors.t3)) { append(of) }
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dimens.space1)) {
            for (i in 0 until total) {
                val color = when {
                    done || i < nowIndex -> colors.brandText.copy(alpha = colors.brandText.alpha * 0.5f)
                    i == nowIndex -> colors.brand
                    else -> colors.lineStrong
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(Dimens.barHeight)
                        .background(color, SegShape),
                )
            }
        }
    }
}

private val SegShape = RoundedCornerShape(7.dp)

/** SVG 경로 글('M62 -10 A62 62 0 0 1 108 26') → 그림 경로. 목업·프로토타입 그림을 숫자 그대로 옮길 때 쓴다. */
internal fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

/** SVG `text-anchor`. */
internal enum class TextAnchor { START, MIDDLE, END }

/**
 * 그림 좌표(SVG 단위)에 글을 그린다: ([x], [y]) = 글 기준선 위치, [unit] = 그림 단위 → px 배율, [size] = 그림 단위 글자 크기.
 * 글은 배율을 건 캔버스 밖에서 실제 글자 크기로 재서 그린다(작은 그림에서도 또렷하게).
 */
internal fun DrawScope.drawSvgText(
    measurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    unit: Float,
    size: Float,
    color: Color,
    anchor: TextAnchor = TextAnchor.START,
    origin: Offset = Offset.Zero,
    weight: FontWeight = FontWeight.Bold,
) {
    if (text.isEmpty()) return
    val layout = measurer.measure(text, TextStyle(fontSize = (size * unit).toSp(), fontWeight = weight, letterSpacing = 0.sp))
    val w = layout.size.width.toFloat()
    val left = origin.x + x * unit - when (anchor) {
        TextAnchor.START -> 0f
        TextAnchor.MIDDLE -> w / 2f
        TextAnchor.END -> w
    }
    drawText(layout, color = color, topLeft = Offset(left, origin.y + y * unit - layout.firstBaseline))
}
