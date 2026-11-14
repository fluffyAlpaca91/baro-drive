package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import kotlinx.coroutines.delay

/** 토스트 한 장. [id]가 달라야 같은 글을 다시 띄워도 시간이 처음부터 다시 간다. */
@Immutable
data class ToastMessage(val text: String, @param:DrawableRes val icon: Int?, val durationMillis: Long, val id: Long)

/**
 * 토스트 상태 (components.js `toast()`·`hideToast()`, 설계 2.4·Pleos UX 2.6): 한 번에 하나, 새 토스트가 앞의 것을 바꾼다.
 * 화면을 옮겨도 남는다. 기어가 P를 벗어나면 [com.barodrive.app.ui.BaroRoot]가 [hide]한다(정차 중 토스트가 주행 화면을 가리지 않게, T11-1).
 * 화면에서는 `LocalBaroToast.current.show("…")`로 띄운다.
 */
@Stable
class BaroToastState {
    var current: ToastMessage? by mutableStateOf(null)
        private set

    private var nextId = 0L

    fun show(text: String, @DrawableRes icon: Int? = null, durationMillis: Long = TOAST_MILLIS) {
        current = ToastMessage(text, icon, durationMillis, nextId++)
    }

    fun hide() {
        current = null
    }

    internal fun expire(message: ToastMessage) {
        if (current == message) current = null
    }

    companion object {
        /** 프로토타입 TOAST_MS(2.2초). */
        const val TOAST_MILLIS = 2_200L
    }
}

/** 앱 하나에 하나뿐인 토스트 상태. [com.barodrive.app.ui.BaroRoot]가 깔고, 미리보기에서는 보이지 않는 기본값이다. */
val LocalBaroToast = staticCompositionLocalOf { BaroToastState() }

@Composable
fun rememberBaroToastState(): BaroToastState = remember { BaroToastState() }

/**
 * 토스트 자리: 앱 창 안쪽 아래·좌우 24 여백에 [BaroToast]를 하나 띄우고 시간이 지나면 거둔다. 움직임 없이 나타나고 사라진다.
 * 토스트는 누름을 받지 않는다(아래 버튼이 그대로 눌린다).
 */
@Composable
fun BaroToastHost(state: BaroToastState, modifier: Modifier = Modifier) {
    val message = state.current ?: return
    LaunchedEffect(message) {
        delay(message.durationMillis)
        state.expire(message)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(Dimens.toastInset),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BaroToast(text = message.text, icon = message.icon)
    }
}

/** 토스트 한 장 (voice.css `.ui-toast`): 최대 폭 792, 최소 높이 88, 좌우 40, 모서리 16, 아이콘 48, 28/36 굵은 글. */
@Composable
fun BaroToast(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusS)
    Row(
        modifier = modifier
            .widthIn(max = Dimens.toastMaxWidth)
            .heightIn(min = Dimens.toastMinHeight)
            .shadow(24.dp, shape)
            .background(colors.toastBg, shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = Dimens.space5, vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) BaroIcon(icon, contentDescription = null, size = Dimens.iconL, tint = colors.toastText)
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = colors.toastText)
    }
}

@BaroPreviews
@Composable
private fun BaroToastPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.toastInset), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            BaroToast("코치 소리를 껐어요 · 안전 항목은 계속 말해요", icon = R.drawable.ic_mute)
            BaroToast("내비에 B 코스를 넣었어요 · 경유지 2곳")
        }
    }
}
