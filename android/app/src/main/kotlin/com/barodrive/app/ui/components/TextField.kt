package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 글자 입력 (목업 `.field`, components.js `field()`): 높이 80, 모서리 24, 카드 바탕 + 진한 테두리, 왼쪽 연필(32) + 28/36 굵은 글.
 * 정차 중에만 쓴다(설계 2.5 — 기어가 P를 벗어나면 [com.barodrive.app.ui.BaroRoot]가 입력 칸의 포커스를 거둔다).
 * [maxLength]를 넘는 글은 잘라 받는다(CO-2 호칭 8자). [label]은 화면 읽기용 이름(없으면 [placeholder]).
 * 키보드가 올라오면(포커스 중) 칸 아래 [KeyboardClearance]까지 보이게 스크롤한다 — 본문 끝의 흐림(`.more-below` 72dp)과
 * 하단 버튼 줄에 칸이 가려 쓰는 글이 안 보이던 문제(에뮬레이터 Pleos 키보드, ON-5 직접 쓰기).
 * 포커스는 [TextFieldFocus]에도 알린다 — 쓰는 동안 시연 키보드 운전이 글자를 가로채지 않게.
 */
@Composable
fun BaroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    maxLength: Int? = null,
    @DrawableRes icon: Int? = R.drawable.ic_pencil,
    label: String? = null,
    onDone: ((String) -> Unit)? = null,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = colors.t1)
    val density = LocalDensity.current
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val focusToken = remember { Any() }
    DisposableEffect(focusToken) { onDispose { TextFieldFocus.set(focusToken, false) } }
    var height by remember { mutableIntStateOf(0) }
    // 키보드 높이가 바뀔 때마다(올라오는 동안) 다시 맞춘다 — 마지막 값에서 끝까지 스크롤한다.
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(focused, imeBottom) {
        if (focused && imeBottom > 0 && height > 0) {
            val below = with(density) { KeyboardClearance.toPx() }
            requester.bringIntoView(Rect(0f, 0f, 1f, height + below))
        }
    }
    Row(
        modifier = modifier
            .bringIntoViewRequester(requester)
            .onSizeChanged { height = it.height }
            .onFocusChanged {
                focused = it.hasFocus
                TextFieldFocus.set(focusToken, it.hasFocus)
            }
            .fillMaxWidth()
            .height(Dimens.fieldHeight)
            .background(colors.bgCard, shape)
            .border(2.dp, colors.lineStrong, shape)
            .padding(horizontal = Dimens.space3),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) BaroIcon(icon, contentDescription = null, size = Dimens.iconS, tint = colors.t3)
        val description = label ?: placeholder
        BasicTextField(
            value = value,
            onValueChange = { next -> onValueChange(if (maxLength != null) next.take(maxLength) else next) },
            // 칸 높이(80) 전부를 누를 수 있게 — 글 줄 높이(48)만 눌리던 것(Pleos 터치 최소 64).
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
            textStyle = textStyle,
            singleLine = true,
            cursorBrush = SolidColor(colors.brand),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke(value) }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = textStyle.copy(fontWeight = FontWeight.Normal), color = colors.t3, maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
}

/** 키보드가 올라왔을 때 입력 칸 아래로 더 보이게 둘 높이: 본문 끝 흐림(72) + 여유. */
private val KeyboardClearance: Dp = 96.dp

/**
 * 글자 입력 칸에 포커스가 있나([BaroTextField]가 센다 — 앱의 입력 칸은 모두 이것). 시연 키보드 운전(demo/keyboard)이 쓰는 글자를
 * 가로채지 않게 본다. 메인 스레드에서만 바뀌고 읽힌다.
 */
object TextFieldFocus {
    private val focused = HashSet<Any>()

    /** 포커스가 있는 입력 칸이 하나라도 있음. */
    val active: Boolean get() = focused.isNotEmpty()

    internal fun set(token: Any, on: Boolean) {
        if (on) focused += token else focused -= token
    }
}

@BaroPreviews
@Composable
private fun BaroTextFieldPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            BaroTextField(value = "", onValueChange = {}, placeholder = "예: 지은아", maxLength = 8)
            BaroTextField(value = "출퇴근용", onValueChange = {})
        }
    }
}
