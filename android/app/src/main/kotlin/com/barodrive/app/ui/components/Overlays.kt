package com.barodrive.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.LocalDriving
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 시트 버튼 하나 (components.js `sheet({ actions })`). [kind]를 아무도 주지 않으면 마지막 버튼이 민트(primary)다.
 * 막힌 버튼은 [enabled] = false + [onDisabledClick](이유 토스트).
 */
@Immutable
data class SheetAction(
    val label: String,
    val onClick: () -> Unit,
    val kind: BaroButtonKind? = null,
    @param:DrawableRes val icon: Int? = null,
    val enabled: Boolean = true,
    val onDisabledClick: (() -> Unit)? = null,
)

/**
 * 시트 (components.js `sheet()`, voice.css `.ui-sheet`, 설계 2.4 '시트'): 정차 중에만. 앱 창 아래에 붙는 판(폭 840, 위 모서리 40,
 * 떠 있는 면 바탕, 여백 40, 사이 24) + 어두운 판. 제목 36/44, 부제 24/28, 본문(넘치면 스크롤, 26/34 보조 글), 버튼 2개까지.
 *
 * - 바깥(어두운 판)을 눌러도 닫히지 않는다 — 명시 버튼으로만 닫는다(설계 2.4, Pleos UX 7.3). 아래 화면은 눌리지 않는다.
 * - 뒤로 키와 출발(기어가 P를 벗어남, [LocalDriving])은 [onDismissRequest](= 취소)를 부른다(설계 2.5). 동의로 보지 않는다.
 * - 화면 경로로 여는 시트(CO-2·LC-2·RV-7 …)는 [com.barodrive.app.ui.navigation.sheet]로 등록하고 이 컴포저블을 그린다.
 *   [onDismissRequest]는 보통 `navController.popBackStack()`이다.
 * - 한 화면 안에서만 쓰는 작은 시트(예: FB-2 이름 바꾸기, ST-3 모드 바꾸기)는 화면 상태로 열고 [BaroScaffold]의 overlay에 둔다.
 */
@Composable
fun BaroSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    sub: String? = null,
    actions: List<SheetAction> = emptyList(),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = BaroTheme.colors
    DismissOnBackAndDrive(onDismissRequest)
    Scrim(Alignment.BottomCenter) {
        Column(
            modifier = modifier
                // 넓은 창(1682)에서는 화면 기둥과 같은 폭까지만, 가운데 아래(BaroScaffold와 같은 규칙).
                .widthIn(max = BaroContentMaxWidth)
                .fillMaxWidth()
                .padding(top = Dimens.sheetTopGap)
                .shadow(40.dp, SheetShape)
                .background(colors.bgRaised, SheetShape)
                .semantics {
                    isTraversalGroup = true
                    if (title != null) paneTitle = title
                }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(Dimens.edge),
            verticalArrangement = Arrangement.spacedBy(Dimens.space3),
        ) {
            if (title != null || sub != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space1)) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                    if (sub != null) Text(sub, style = MaterialTheme.typography.labelLarge, color = colors.t3)
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.space3),
            ) {
                CompositionLocalProvider(LocalContentColor provides colors.t2) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall) { content() }
                }
            }
            if (actions.isNotEmpty()) {
                val shown = actions.take(MAX_SHEET_ACTIONS)
                val anyKind = shown.any { it.kind != null }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
                    shown.forEachIndexed { index, action ->
                        BaroButton(
                            text = action.label,
                            onClick = action.onClick,
                            modifier = Modifier.weight(1f),
                            kind = action.kind ?: if (!anyKind && index == shown.lastIndex) BaroButtonKind.Primary else BaroButtonKind.Ghost,
                            icon = action.icon,
                            enabled = action.enabled,
                            onDisabledClick = action.onDisabledClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 확인 대화상자 (components.js `dialog()`, voice.css `.ui-dlg`, 설계 2.4 '확인 대화상자'): Pleos 시스템 팝업 Medium — 창 가운데
 * 폭 760, 여백 48, 모서리 48, 제목 36/44 + 본문 28/36 + 버튼 2개([cancelLabel] 회색 '취소', [confirmLabel] 민트·[danger]면 빨강,
 * 기본 '확인').
 * 삭제·철회처럼 되돌릴 수 없는 일에만 쓴다(ST-2 '이 회차 기록을 지울까요?', ST-4 '기록 저장 동의를 거둘까요?').
 * 바깥을 눌러도 닫히지 않고, 뒤로 키·출발은 [onDismissRequest](= 취소). [cancelLabel]이 null이면 버튼 1개.
 */
@Composable
fun BaroConfirmDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = stringResource(R.string.ui_confirm),
    cancelLabel: String? = stringResource(R.string.ui_cancel),
    danger: Boolean = false,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.dialogRadius)
    DismissOnBackAndDrive(onDismissRequest)
    Scrim(Alignment.Center) {
        Column(
            modifier = modifier
                .padding(Dimens.space5)
                .widthIn(max = Dimens.dialogWidth)
                .fillMaxWidth()
                .shadow(40.dp, shape)
                .background(colors.bgRaised, shape)
                .semantics {
                    isTraversalGroup = true
                    paneTitle = title
                }
                .padding(Dimens.dialogPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.space3),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
            if (body != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    CompositionLocalProvider(LocalContentColor provides colors.t2) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { body() }
                    }
                }
            }
            Row(Modifier.padding(top = Dimens.space2), horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
                if (cancelLabel != null) BaroButton(cancelLabel, onClick = onDismissRequest, modifier = Modifier.weight(1f))
                BaroButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    kind = if (danger) BaroButtonKind.Danger else BaroButtonKind.Primary,
                )
            }
        }
    }
}

private val SheetShape = RoundedCornerShape(topStart = Dimens.radiusWindow, topEnd = Dimens.radiusWindow)
private const val MAX_SHEET_ACTIONS = 2 // 결정-18 · 설계 2.4 '버튼 2개 이하'

/**
 * 어두운 판: 창 전체를 덮고 아래 화면으로 누름을 넘기지 않는다. 판을 눌러도 아무 일도 없다.
 * 누름을 '보기만' 하고 소비하지 않는다 — 맨 위 형제가 누름을 받으면 아래 형제(화면)에는 가지 않으므로 그것으로 충분하고,
 * 부모가 소비하면 판 위 버튼의 누름이 취소되고 본문 스크롤이 시작되지 않는다.
 */
@Composable
private fun Scrim(contentAlignment: Alignment, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BaroTheme.colors.scrim)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            },
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

/** 뒤로 키 → 닫기(취소). 출발(주행 중 화면 문이 닫힘) → 닫기. */
@Composable
private fun DismissOnBackAndDrive(onDismissRequest: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    BackHandler { dismiss() }
    val driving = LocalDriving.current
    LaunchedEffect(driving) { if (driving) dismiss() }
}

@BaroScreenPreviews
@Composable
private fun BaroSheetPreview() {
    BaroTheme {
        BaroSheet(
            onDismissRequest = {},
            title = "연습면허 발급일",
            sub = "받은 날부터 1년 동안 도로에서 연습할 수 있어요",
            actions = listOf(SheetAction("취소", onClick = {}), SheetAction("저장", onClick = {})),
        ) {
            Text("2027. 3. 31.까지 · D-141")
            BaroTextField(value = "", onValueChange = {}, placeholder = "예: 지은아")
        }
    }
}

@BaroScreenPreviews
@Composable
private fun BaroConfirmDialogPreview() {
    BaroTheme {
        BaroConfirmDialog(
            title = "이 회차 기록을 지울까요?",
            confirmLabel = "지우기",
            onConfirm = {},
            onDismissRequest = {},
            danger = true,
        ) {
            Text("지운 기록은 되돌릴 수 없어요.")
        }
    }
}
