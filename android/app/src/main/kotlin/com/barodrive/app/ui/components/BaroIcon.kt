package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 아이콘 (프로토타입 components.js `icon(id, cls)` + index.html 아이콘 묶음). 그림은 `res/drawable/ic_*.xml`
 * (심볼 `i-back` → `R.drawable.ic_back`, `sys-user` → `ic_user`). 색은 [tint](기본은 둘레 글자색 = CSS currentColor).
 * 크기는 `.ic` 40 · `.ic.s` 32 · `.ic.l` 48([Dimens.icon]·[Dimens.iconS]·[Dimens.iconL]).
 * 로고(`logo_mark`)와 코치 얼굴(`av_*`)은 색이 정해진 그림이라 [LogoMark]·[CoachAvatar]로 그린다.
 */
@Composable
fun BaroIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.icon,
    tint: Color = LocalContentColor.current,
) {
    Icon(painter = painterResource(icon), contentDescription = contentDescription, modifier = modifier.size(size), tint = tint)
}

/** 옮긴 아이콘 전부(프로토타입 index.html 심볼 `i-*` + `sys-user`). 미리보기 갤러리이자 화면 작업이 고를 목록이다. */
internal val BaroIconGallery = listOf(
    R.drawable.ic_back, R.drawable.ic_close, R.drawable.ic_chev, R.drawable.ic_voice, R.drawable.ic_speaker,
    R.drawable.ic_mute, R.drawable.ic_check, R.drawable.ic_lock, R.drawable.ic_star, R.drawable.ic_star_f,
    R.drawable.ic_send, R.drawable.ic_share, R.drawable.ic_settings, R.drawable.ic_history, R.drawable.ic_info,
    R.drawable.ic_plus, R.drawable.ic_pencil, R.drawable.ic_refresh, R.drawable.ic_up, R.drawable.ic_calendar,
    R.drawable.ic_user, R.drawable.ic_flag, R.drawable.ic_flag_check, R.drawable.ic_medal, R.drawable.ic_route,
    R.drawable.ic_belt, R.drawable.ic_pbrake, R.drawable.ic_seat, R.drawable.ic_brake, R.drawable.ic_pedals,
    R.drawable.ic_wheel, R.drawable.ic_slow, R.drawable.ic_pulse, R.drawable.ic_hazard, R.drawable.ic_shield,
    R.drawable.ic_bulb, R.drawable.ic_lamp, R.drawable.ic_turn_r, R.drawable.ic_blink_l, R.drawable.ic_blink_r,
    R.drawable.ic_blink_both, R.drawable.ic_car_top, R.drawable.ic_sun, R.drawable.ic_rain, R.drawable.ic_snow,
    R.drawable.ic_wiper, R.drawable.ic_temp,
)

@OptIn(ExperimentalLayoutApi::class)
@BaroPreviews
@Composable
private fun BaroIconPreview() {
    BaroTheme {
        FlowRow(
            modifier = Modifier.padding(Dimens.space3),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            BaroIconGallery.forEach { BaroIcon(it, contentDescription = null) }
            BaroIcon(R.drawable.ic_lock, contentDescription = null, tint = BaroTheme.colors.brandText)
        }
    }
}
