package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

private val BarShape = RoundedCornerShape(7.dp)

/**
 * 진행 막대 (voice.css `.ui-prog`, components.js `progress()`, 설계 2.4 '진행 막대'): 높이 14 둥근 막대 + 24/28 굵은 글.
 * 예: '과제 80% 이상 회차 1/2', '4개 코스 중 2개 달렸어요'. [progress]는 0~1.
 */
@Composable
fun BaroProgressBar(progress: Float, modifier: Modifier = Modifier, label: String? = null) {
    val colors = BaroTheme.colors
    val value = progress.coerceIn(0f, 1f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Dimens.barHeight)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }
                .background(colors.lineStrong, BarShape),
        ) {
            if (value > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(value)
                        .fillMaxHeight()
                        .background(colors.brand, BarShape),
                )
            }
        }
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = colors.t2)
        }
    }
}

/**
 * 단계 막대 (홈 목업 `.stepper` + home.css): 칸마다 높이 14 막대 + 20/24 이름. 지난 단계는 꽉 찬 민트, 지금 단계([current], 1부터)는
 * [currentFill](0~1, 통과 회차 / 2)만큼 민트, 다음 단계는 빈 막대. [graduated]면 모두 지난 단계. 예: 홈 진행 카드 '1 차량 감 · 2 골목 …'.
 */
@Composable
fun StageStepper(
    labels: List<String>,
    current: Int,
    currentFill: Float,
    modifier: Modifier = Modifier,
    graduated: Boolean = false,
) {
    val colors = BaroTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        labels.forEachIndexed { index, label ->
            val step = index + 1
            val done = graduated || step < current
            val now = !graduated && step == current
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.space1)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(Dimens.barHeight)
                        .background(if (done) colors.brand else colors.lineStrong, BarShape),
                ) {
                    val fill = currentFill.coerceIn(0f, 1f)
                    if (now && fill > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(fill)
                                .fillMaxHeight()
                                .background(colors.brand, BarShape),
                        )
                    }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = when {
                        now -> colors.brandText
                        done -> colors.t2
                        else -> colors.t3
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 단계 칸 막대 (목업 `.stepbar`, 주차·언덕 '움직이는 중' 화면): 같은 폭 칸들(높이 14, 사이 8). 지난 칸은 옅은 민트,
 * 지금 칸([current], 1부터)은 민트. 오른쪽 [trailing]에 '3 / 8' 같은 글을 둔다.
 */
@Composable
fun StepBar(
    total: Int,
    current: Int,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = BaroTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dimens.space1)) {
            for (step in 1..total) {
                val color = when {
                    step < current -> colors.brandText.copy(alpha = colors.brandText.alpha * 0.5f)
                    step == current -> colors.brand
                    else -> colors.lineStrong
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(Dimens.barHeight)
                        .background(color, BarShape),
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 진행 점 (온보딩 onboarding.css `.ob-dots`, 설계 4.2 ON-3 '진행 점 3개(질문 → 추천 → 코치)'): 점 16 + 20/24 이름.
 * 지난 점은 민트, 지금 점([current], 0부터)은 민트 + 옅은 민트 고리, 다음 점은 회색.
 */
@Composable
fun ProgressDots(labels: List<String>, current: Int, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            val done = index < current
            val now = index == current
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space1), verticalAlignment = Alignment.CenterVertically) {
                val ring = colors.brandSoft
                Box(
                    Modifier
                        .size(Dimens.space2)
                        .then(if (now) Modifier.drawBehind { drawCircle(ring, radius = size.minDimension / 2 + 5.dp.toPx()) } else Modifier)
                        .background(
                            when {
                                now -> colors.brand
                                done -> colors.brandText
                                else -> colors.lineStrong
                            },
                            CircleShape,
                        ),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = when {
                        now -> colors.brandText
                        done -> colors.t2
                        else -> colors.t3
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** [StepStrip] 칸 상태: 아직 · 함(체크) · 지금(민트). */
enum class StepState { Todo, Done, Now }

/** [StepStrip] 칸 하나: 아이콘(44) + 이름. */
@Immutable
data class StripStep(val label: String, @param:DrawableRes val icon: Int, val state: StepState = StepState.Todo)

/**
 * 단계 칸 줄 (목업 `.strip`, 언덕 HL-1 단계 칸): 높이 120 칸들, 아이콘 44 + 20/24 이름. 한 칸은 '지금'(민트 바탕), 한 칸은 '함'
 * (오른쪽 위 민트 체크). [onStepClick]이 있으면 칸을 누를 수 있고, [dimmed]면 흐리게(움직이는 동안 — HL-1은 속도 0일 때만 누름).
 */
@Composable
fun StepStrip(
    steps: List<StripStep>,
    modifier: Modifier = Modifier,
    onStepClick: ((Int) -> Unit)? = null,
    dimmed: Boolean = false,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        steps.forEachIndexed { index, step ->
            val now = step.state == StepState.Now
            val content = when (step.state) {
                StepState.Now -> colors.onBrand
                StepState.Done -> colors.t2
                StepState.Todo -> colors.t3
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(120.dp)
                    .then(
                        if (onStepClick != null && !dimmed) {
                            Modifier.pressClickable(enabled = true, onClick = { onStepClick(index) }, role = Role.Button)
                        } else {
                            Modifier
                        },
                    )
                    // 흐림은 칸 전체(바탕·테두리 포함)에: 바탕보다 앞에 둔다.
                    .then(if (dimmed && !now) Modifier.alpha(DIMMED_ALPHA) else Modifier)
                    .background(if (now) colors.brand else colors.bgCard, shape)
                    .border(2.dp, if (now) Color.Transparent else colors.cardLine, shape),
            ) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BaroIcon(step.icon, contentDescription = null, size = 44.dp, tint = content)
                    Text(step.label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = content)
                }
                if (step.state == StepState.Done) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .size(28.dp)
                            .background(colors.brandText, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        BaroIcon(R.drawable.ic_check, contentDescription = null, size = 20.dp, tint = colors.bgWindow)
                    }
                }
            }
        }
    }
}

/** 동그라미 표시 종류 (목업 `.badge.good` 민트 · `.badge.warn` 주황 · review.css `.badge.off` 회색 · parking.css `.badge.bad` 빨강). */
enum class BadgeKind { Good, Warn, Off, Bad }

/** 동그라미 표시 (목업 `.badge`): 44 원 안 아이콘(28). 예: 리뷰 '잘한 것' 체크, 과제 달성 줄, 홈 알림 줄. */
@Composable
fun StatusBadge(
    @DrawableRes icon: Int,
    kind: BadgeKind,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    contentDescription: String? = null,
) {
    val colors = BaroTheme.colors
    val (background, content) = when (kind) {
        BadgeKind.Good -> colors.brand to colors.onBrand
        BadgeKind.Warn -> colors.caution to colors.onCaution
        BadgeKind.Off -> colors.btnGhostBg to colors.t3
        BadgeKind.Bad -> colors.danger to colors.onDanger
    }
    Box(modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
        BaroIcon(icon, contentDescription = contentDescription, size = 28.dp, tint = content)
    }
}

/** HL-1 흐린 칸(`.strip button.dim{opacity:.45}`). */
private const val DIMMED_ALPHA = 0.45f

@BaroPreviews
@Composable
private fun ProgressPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            BaroProgressBar(progress = 0.5f, label = "과제 80% 이상 회차 1/2")
            StageStepper(labels = listOf("1 차량 감", "2 골목", "3 큰 도로", "4 고속도로"), current = 2, currentFill = 0.5f)
            StepBar(total = 8, current = 3, trailing = { Text("3 / 8", style = MaterialTheme.typography.titleMedium) })
            ProgressDots(labels = listOf("질문", "추천", "코치"), current = 1)
            StepStrip(
                steps = listOf(
                    StripStep("브레이크", R.drawable.ic_brake, StepState.Done),
                    StripStep("사이드", R.drawable.ic_pbrake, StepState.Now),
                    StripStep("출발", R.drawable.ic_pedals),
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                StatusBadge(R.drawable.ic_check, BadgeKind.Good)
                StatusBadge(R.drawable.ic_brake, BadgeKind.Warn)
                StatusBadge(R.drawable.ic_check, BadgeKind.Off)
                StatusBadge(R.drawable.ic_close, BadgeKind.Bad)
            }
        }
    }
}
