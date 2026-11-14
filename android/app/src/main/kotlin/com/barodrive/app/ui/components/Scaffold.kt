package com.barodrive.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 정차 화면 틀 (목업 `.scr` + `.ab` + `.bd` + 하단 고정 버튼 줄 — 프로토타입 화면들의 `ob-foot`·`pr-foot`·`rv-foot`, 설계 2.5):
 * 창 바탕 위에 [topBar](보통 [BaroAppBar]), 본문(좌우 40·위 24 여백, 사이 24), 하단 [bottomBar](좌우·아래 40, 버튼 사이 24).
 *
 * - [scrollable]이면 본문만 세로로 스크롤하고, 아래에 더 있을 때만 끝 72dp를 흐리게 한다(`.more-below`). 하단 버튼은 고정이다.
 *   스크롤은 정차 화면에서만 쓴다 — 주행·움직이는 중 화면은 [scrollable] = false(설계 2.5).
 * - 스크롤하지 않을 때 본문 안 `Spacer(Modifier.weight(1f))`로 아래로 밀 수 있다(목업 `.acts{margin-top:auto}`).
 * - [bottomBar]는 RowScope라 버튼에 `Modifier.weight(1f)`를 준다. 주요 버튼은 2개까지(결정-18).
 * - [overlay]는 창 전체 위에 겹쳐 그린다: 이 화면 안에서만 여는 [BaroSheet]·[BaroConfirmDialog].
 * - 넓은 창(에뮬레이터의 가로 창 1682×1210 등)에서는 줄·버튼을 창 끝까지 늘리지 않는다: 앱 바·본문·하단 버튼 기둥을
 *   [BaroContentMaxWidth]까지만 쓰고 가운데에 둔다. 바탕은 창 끝까지 칠한다. 840 세로 창에서는 전과 같다.
 */
@Composable
fun BaroScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    bodyArrangement: Arrangement.Vertical = Arrangement.spacedBy(Dimens.space3),
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(BaroTheme.colors.bgWindow),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            BaroContentColumn(content = { ScaffoldColumn(topBar, bottomBar, scrollable, bodyArrangement, content) })
        }
        overlay()
    }
}

/**
 * 넓은 창에서 내용 기둥의 최대 폭. 목업은 840 세로 창이지만, 가로로 넓은 창(1682)에서는 이 폭까지만 쓰고 가운데에 둔다.
 * 주행 덮개·온보딩처럼 [BaroScaffold]를 쓰지 않는 화면도 [BaroContentColumn]으로 같은 규칙을 쓴다.
 */
val BaroContentMaxWidth = 960.dp

/** 내용 기둥: 창 높이 전부, 폭은 [BaroContentMaxWidth]까지. 부모가 가운데 맞춤이면 넓은 창에서 가운데에 선다. */
@Composable
fun BaroContentColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .widthIn(max = BaroContentMaxWidth)
            .fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
private fun ScaffoldColumn(
    topBar: @Composable () -> Unit,
    bottomBar: (@Composable RowScope.() -> Unit)?,
    scrollable: Boolean,
    bodyArrangement: Arrangement.Vertical,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        topBar()
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scrollable) Modifier.fadeWhenMoreBelow(scroll).verticalScroll(scroll) else Modifier)
                .padding(
                    start = Dimens.edge,
                    end = Dimens.edge,
                    top = Dimens.space3,
                    bottom = if (bottomBar != null) Dimens.space3 else Dimens.edge,
                ),
            verticalArrangement = bodyArrangement,
            content = content,
        )
        if (bottomBar != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.edge, end = Dimens.edge, bottom = Dimens.edge),
                horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap),
                content = bottomBar,
            )
        }
    }
}

/** 스크롤할 내용이 아래에 더 있을 때만 끝 [fade]를 흐리게(프로토타입 `.more-below` 마스크, 움직임 없음). */
fun Modifier.fadeWhenMoreBelow(state: ScrollState, fade: Dp = 72.dp): Modifier = this
    // 흐리게 할 때만 따로 그린 층을 쓴다(DstIn 마스크가 창 바탕까지 지우지 않게).
    .graphicsLayer { compositingStrategy = if (state.canScrollForward) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
    .drawWithContent {
        drawContent()
        if (state.canScrollForward && size.height > 0f) {
            val start = ((size.height - fade.toPx()) / size.height).coerceIn(0f, 1f)
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Black, start to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@BaroScreenPreviews
@Composable
private fun BaroScaffoldPreview() {
    BaroTheme {
        BaroScaffold(
            topBar = { BaroAppBar(title = "연습 시작 전", sub = "2단계 · 동네 골목 다니기 · 3회차", nav = AppBarNav.Back) },
            bottomBar = {
                BaroButton(
                    text = "내비로 보내고 출발",
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    size = BaroButtonSize.L,
                    kind = BaroButtonKind.Primary,
                )
            },
        ) {
            repeat(6) { index ->
                BaroCard {
                    CardHeader(title = "카드 ${index + 1}")
                    Text("본문만 스크롤하고 하단 버튼은 고정이에요.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}


@BaroWideScreenPreviews
@Composable
private fun BaroScaffoldWidePreview() {
    BaroScaffoldPreview()
}
