package com.barodrive.app.feature.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.feature.autonomous.RecordMissing
import com.barodrive.app.feature.autonomous.ReviewRowUi
import com.barodrive.app.feature.autonomous.RvRows
import com.barodrive.app.feature.coach.face
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.CoachSay
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.components.ReviewHeader
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.engine.stage.GradSummary
import com.barodrive.core.engine.stage.GradWeather
import com.barodrive.core.engine.stage.StageCount
import com.barodrive.core.engine.stage.StagePassModel
import com.barodrive.core.engine.stage.StageTry
import com.barodrive.core.model.Coach
import com.barodrive.core.model.K4Compare
import com.barodrive.core.model.K4Row
import kotlin.math.cos
import kotlin.math.sin

/** RV-2·RV-3에서 갈 곳. */
@Immutable
data class StageActions(
    val onHome: () -> Unit = {},
    /** [N+1단계 코스 보기] → CR-1(그 모드). */
    val onCourse: (Int) -> Unit = {},
    /** [자율 모드로 달리기] → FB-2. */
    val onFree: () -> Unit = {},
    /** 4단계 기록으로 RV-2가 열리면 RV-3으로 바꿔 간다. */
    val onGraduation: () -> Unit = {},
)

@Composable
fun StagePassedRoute(actions: StageActions, viewModel: StagePassedViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.toGraduation) { if (state.toGraduation) actions.onGraduation() }
    StagePassedScreen(state, actions)
}

@Composable
fun GraduationRoute(actions: StageActions, viewModel: GraduationViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GraduationScreen(state, actions)
}

/** 단계 화면 틀: 닫기(→ 홈)만 있는 앱 바 + P 정차 중 · 본문 스크롤 · 하단 [홈으로] + 둘째 버튼. */
@Composable
private fun StageFrame(actions: StageActions, second: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    BaroScaffold(
        topBar = { BaroAppBar(title = "", nav = AppBarNav.Close, onNav = actions.onHome) },
        bottomBar = {
            BaroButton(stringResource(R.string.st_home), onClick = actions.onHome, modifier = Modifier.weight(1f))
            second?.invoke(this)
        },
        content = content,
    )
}

/**
 * RV-2 단계 통과 (stage.js RV-2, 목업 s06): 메달(단계 숫자) · 'N단계 통과!' · '다음은 ○○' · 코치 말풍선(rv.pass.N) · 'N+1단계에서 해 볼 것'
 * (다음 단계 과제 2열) · 말하는 양 안내. 하단 [홈으로] [N+1단계 코스 보기].
 */
@Composable
fun StagePassedScreen(state: StagePassedUiState, actions: StageActions) {
    val colors = BaroTheme.colors
    val m = state.pass
    StageFrame(
        actions = actions,
        second = m?.courseMode?.let { next ->
            {
                BaroButton(m.courseLabel.orEmpty(), onClick = { actions.onCourse(next) }, modifier = Modifier.weight(1f), kind = BaroButtonKind.Primary)
            }
        },
    ) {
        if (!state.loaded) return@StageFrame
        if (m == null) {
            RecordMissing()
            return@StageFrame
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Medal(m.medal, 220.dp, stringResource(R.string.st_medal_desc, m.title))
            Text(m.title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            if (m.nextName != null) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.t2)) { append(m.nextLead.orEmpty()) }
                        append(" ")
                        withStyle(SpanStyle(color = colors.brandText)) { append(m.nextName.orEmpty()) }
                    },
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
            if (state.line.isNotEmpty()) {
                // 말풍선 글은 화면 기본 글자(.scr 26/34 보통) — 프로토타입 `.bub`는 굵기를 따로 주지 않는다.
                CoachSay(state.coach.face(), state.line, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), strong = true)
            }
            if (m.tryList.isNotEmpty()) TryCard(m)
        }
    }
}

/** 'N+1단계에서 해 볼 것'(목업 s06 `.todo`): 체크 + 과제 2열 · 말하는 양 안내. */
@Composable
private fun TryCard(m: StagePassModel) {
    val colors = BaroTheme.colors
    BaroCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(m.tryTitle.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
        m.tryList.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
                row.forEach { t ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BaroIcon(R.drawable.ic_check, contentDescription = null, size = Dimens.iconS, tint = colors.brandText)
                        Text(t.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
        m.talkNote?.let { NoteRow(it, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/**
 * RV-3 졸업 · 연습 기록 요약 (stage.js RV-3, 설계 4.2 RV-3): 메달(별) · '4단계까지 마쳤어요' · 졸업 날 · 코치 말풍선(rv.grad) · 요약 카드
 * (총 회차·시간·거리, 단계별 회차, 날씨 회차) · 첫 회차 → 마지막 회차(K4) · 안내. 졸업 전이면 메달·코치 없이 '연습 기록 요약'만.
 */
@Composable
fun GraduationScreen(state: GraduationUiState, actions: StageActions) {
    val colors = BaroTheme.colors
    val g = state.summary
    StageFrame(
        actions = actions,
        second = { BaroButton(stringResource(R.string.st_free), onClick = actions.onFree, modifier = Modifier.weight(1f), kind = BaroButtonKind.Primary) },
    ) {
        if (!state.loaded || g == null) return@StageFrame
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (g.graduated) Medal(null, 180.dp, stringResource(R.string.st_grad_medal))
            Text(stringResource(if (g.graduated) R.string.st_grad_title else R.string.st_summary_title), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            if (g.graduated) {
                state.date?.let { Text(stringResource(R.string.st_grad_date, it), style = MaterialTheme.typography.bodySmall, color = colors.t3) }
            } else {
                Text(stringResource(R.string.st_not_yet), style = MaterialTheme.typography.bodySmall, color = colors.t2)
            }
            if (state.line.isNotEmpty()) {
                // 말풍선 글은 화면 기본 글자(.scr 26/34 보통) — 프로토타입 `.bub`는 굵기를 따로 주지 않는다.
                CoachSay(state.coach.face(), state.line, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), strong = true)
            }
        }
        SummaryCard(g)
        FirstToLastCard(g)
        NoteRow(g.note)
    }
}

@Composable
private fun SummaryCard(g: GradSummary) {
    BaroCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space1)) {
        ReviewHeader(stringResource(R.string.st_summary_title))
        Row(Modifier.padding(top = Dimens.space1, bottom = Dimens.space1), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            Stat(stringResource(R.string.st_sessions_value, g.sessions), stringResource(R.string.st_stat_sessions), Modifier.weight(1f))
            Stat(g.timeText, stringResource(R.string.st_stat_time), Modifier.weight(1f))
            Stat(g.distText, stringResource(R.string.st_stat_dist), Modifier.weight(1f))
        }
        val rows = g.perStage.map { x -> ReviewRowUi(null, stringResource(R.string.st_stage_row, x.stage, x.name), stringResource(R.string.st_sessions_value, x.count)) } +
            listOfNotNull(g.weatherText?.let { ReviewRowUi("weather", stringResource(R.string.st_weather_row), it) })
        RvRows(rows, valueColor = BaroTheme.colors.t1)
        if (g.partial) {
            NoteRow(stringResource(R.string.st_partial), modifier = Modifier.padding(top = 12.dp), icon = R.drawable.ic_history)
        }
    }
}

/** 첫 회차 → 마지막 회차(K4, stage.js rv3Body): 좋아진 값은 민트 · 비교할 첫 기록이 없으면 그 글. */
@Composable
private fun FirstToLastCard(g: GradSummary) {
    val colors = BaroTheme.colors
    BaroCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space1)) {
        ReviewHeader(stringResource(R.string.st_k4_title))
        if (!g.k4.first && g.k4.rows.isNotEmpty()) {
            RvRows(g.k4.rows.map { ReviewRowUi(it.id, it.label, it.text, better = it.better == true) }, valueColor = colors.t1, betterColor = colors.brandText)
        } else {
            Text(stringResource(R.string.st_k4_none), modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = colors.t2)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    Column(
        modifier
            .background(colors.bgRaised, shape)
            .border(2.dp, colors.cardLine, shape)
            .padding(horizontal = 20.dp, vertical = Dimens.space2),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.t3)
    }
}

/**
 * 메달(stage.js `medal`, 목업 s06 그림 — 색은 토큰): 단계 숫자([n]) 또는 졸업 별(null). 0.25초 한 번 나타난다(설계 2.5 '0.3초 이하로 한 번').
 * 그림 좌표는 목업 viewBox 240 그대로.
 */
@Composable
fun Medal(n: String?, size: Dp, description: String) {
    val colors = BaroTheme.colors
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(MEDAL_MS)) }
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                alpha = appear.value
                val s = 0.9f + 0.1f * appear.value
                scaleX = s
                scaleY = s
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val k = this.size.width / 240f
            drawMedal(k, colors.caution, colors.positive, colors.danger, colors.brand, colors.onBrand, star = n == null)
        }
        if (n != null) {
            // 숫자 글자: 얼굴 원 가운데(목업 text y=130 기준선 ≈ 원 중심 104).
            Text(
                n,
                modifier = Modifier.padding(bottom = size * (32f / 240f)),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = (76f * size.value / 240f).sp, lineHeight = (76f * size.value / 240f).sp),
                color = colors.onBrand,
            )
        }
    }
}

private fun DrawScope.drawMedal(k: Float, caution: Color, positive: Color, danger: Color, brand: Color, onBrand: Color, star: Boolean) {
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    rotate(20f, pivot = p(25f, 59f)) { drawRoundRect(caution, topLeft = p(18f, 52f), size = Size(14f * k, 14f * k), cornerRadius = CornerRadius(3f * k)) }
    drawCircle(positive, radius = 8f * k, center = p(214f, 70f))
    rotate(-30f, pivot = p(208f, 154f)) {
        drawRoundRect(danger.copy(alpha = 0.55f), topLeft = p(200f, 150f), size = Size(16f * k, 9f * k), cornerRadius = CornerRadius(4f * k))
    }
    drawCircle(brand, radius = 6f * k, center = p(34f, 158f))
    drawPath(Path().apply { moveTo(196f * k, 26f * k); lineTo(202f * k, 38f * k); lineTo(214f * k, 34f * k) }, caution,
        style = Stroke(width = 5f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(Path().apply { moveTo(30f * k, 18f * k); lineTo(40f * k, 26f * k) }, brand, style = Stroke(width = 6f * k, cap = StrokeCap.Round))
    val ribbon = brand.copy(alpha = 0.62f)
    drawPath(Path().apply { moveTo(86f * k, 150f * k); lineTo(68f * k, 226f * k); lineTo(96f * k, 212f * k); lineTo(110f * k, 236f * k); lineTo(122f * k, 154f * k); close() }, ribbon)
    drawPath(Path().apply { moveTo(154f * k, 150f * k); lineTo(172f * k, 226f * k); lineTo(144f * k, 212f * k); lineTo(130f * k, 236f * k); lineTo(118f * k, 154f * k); close() }, ribbon)
    drawCircle(brand, radius = 78f * k, center = p(120f, 104f))
    drawCircle(
        onBrand.copy(alpha = 0.3f), radius = 60f * k, center = p(120f, 104f),
        style = Stroke(width = 4f * k, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * k, 9f * k))),
    )
    if (star) {
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 1) 19f else 44f
            val a = Math.toRadians((-90 + i * 36).toDouble())
            val x = (120 + r * cos(a)).toFloat() * k
            val y = (106 + r * sin(a)).toFloat() * k
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, onBrand.copy(alpha = 0.88f))
    }
}

private const val MEDAL_MS = 250

// ================================================================ 미리보기

@BaroScreenPreviews
@Composable
private fun StagePassedPreview() {
    BaroTheme {
        StagePassedScreen(
            StagePassedUiState(
                loaded = true,
                pass = StagePassModel(
                    recordId = "sess-1", stage = 1, nextStage = 2, graduated = false, passed = true, medal = "1", title = "1단계 통과!",
                    nextLead = "다음은", nextName = "동네 골목 다니기", lineKey = "rv.pass.1", tryTitle = "2단계에서 해 볼 것",
                    tryList = listOf(StageTry("T-30", "시속 30 넘지 않기"), StageTry("T-SIGNAL30", "꺾기 30m 전에 깜빡이 켜기"), StageTry("T-WAIT3", "깜빡이 켜자마자 꺾지 않기"), StageTry("T-STOP", "멈출 때 부드럽게 멈추기")),
                    talkNote = "이제 코치가 미리 안내를 줄이고 결과 위주로 말해요.", courseLabel = "2단계 코스 보기", courseMode = 2,
                ),
                coach = Coach.DAD,
                line = "1단계 통과 축하해. 골목도 지금처럼 천천히 가 보자.",
            ),
            StageActions(),
        )
    }
}

@BaroScreenPreviews
@Composable
private fun GraduationPreview() {
    BaroTheme {
        GraduationScreen(
            GraduationUiState(
                loaded = true,
                summary = GradSummary(
                    graduated = true, graduatedAt = "2026-11-09T10:00:00+09:00", sessions = 9,
                    perStage = listOf(StageCount(1, "차량 감 익히기", 3), StageCount(2, "동네 골목 다니기", 2), StageCount(3, "큰 도로 달리기", 2), StageCount(4, "고속도로 타 보기", 2)),
                    perStageText = "", totalSec = 3520, totalM = 38700, timeText = "59분", distText = "38.7km", totalText = "", partial = true, kept = 7,
                    k4 = K4Compare(first = false, rows = listOf(K4Row("c2Avg", "깜빡이 켠 거리 평균", "38m → 55m", better = true))),
                    weather = GradWeather(1, 1, 1), weatherText = "빗길 1회 · 눈길 1회 · 아주 나쁜 날 1회", lineKey = "rv.grad",
                    note = "이제 자율 모드로 원하는 곳까지 달려 보세요. 단계 연습은 언제든 다시 할 수 있어요.",
                ),
                coach = Coach.MOM,
                line = "세상에, 다 끝냈어! 너무 대견해!",
                date = "2026. 11. 9.",
            ),
            StageActions(),
        )
    }
}
