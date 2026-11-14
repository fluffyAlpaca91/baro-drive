package com.barodrive.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Gear

/** 앱 바 왼쪽 버튼 (components.js `appBar({ left: 'back'|'close'|null })`). */
enum class AppBarNav { None, Back, Close }

/**
 * 앱 바 (목업 `.ab`, components.js `appBar()`, 설계 2.4): 높이 112, 왼쪽 아이콘 버튼(뒤로·닫기 80×80), 제목 32/40, 부제 24/28,
 * 오른쪽 [actions]와 기어 칩. 기어 칩은 기본이 'P 정차 중'이다 — 정차 화면은 기어가 P일 때만 보이기 때문이다(주행 중에는 덮개가 덮는다).
 * 움직이는 중 화면(PK-3·HL-1)은 [gearChip]에 지금 기어로 만든 [GearChip]을 넘기고, 칩이 없는 화면은 null을 넘긴다.
 * [navDescription]은 왼쪽 버튼의 화면 읽기 이름(기본 '뒤로'·'닫기' — PK-3은 닫기 버튼이 '그만하기').
 */
@Composable
fun BaroAppBar(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    nav: AppBarNav = AppBarNav.None,
    onNav: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    gearChip: (@Composable () -> Unit)? = { GearChip(Gear.P) },
    navDescription: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.appBarHeight)
            .padding(start = Dimens.edge, end = Dimens.edge, top = Dimens.appBarTopPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (nav) {
            AppBarNav.Back -> BaroIconButton(R.drawable.ic_back, navDescription ?: stringResource(R.string.ui_back), onClick = onNav)
            AppBarNav.Close -> BaroIconButton(R.drawable.ic_close, navDescription ?: stringResource(R.string.ui_close), onClick = onNav)
            AppBarNav.None -> Unit
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelLarge,
                    color = BaroTheme.colors.t3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
        gearChip?.invoke()
    }
}

/**
 * 기어 칩 (목업 `.gear`, components.js `gearChip()`): 높이 56 알약, 왼쪽 40×40 칸에 기어 글자 + 오른쪽 설명.
 * 기본 설명: P 정차 중 · D 주행 중(움직이면)/멈춤 · R 후진 · N 중립. 'D 주행 중'이면 민트(`.gear.d`), [mint]로 바꿀 수 있다.
 * 기어를 못 읽으면(null) '—'만 보인다(설계 7.8).
 */
@Composable
fun GearChip(
    gear: Gear?,
    modifier: Modifier = Modifier,
    moving: Boolean = false,
    label: String? = null,
    mint: Boolean? = null,
) {
    val colors = BaroTheme.colors
    val text = label ?: gearLabelText(gear, moving)
    val isMint = mint ?: (gear == Gear.D && moving && label == null)
    val pill = CircleShape
    Row(
        modifier = modifier
            .height(Dimens.gearChipHeight)
            .semantics(mergeDescendants = true) {}
            .background(colors.bgCard, pill)
            .border(2.dp, colors.cardLine, pill)
            .padding(start = Dimens.space1, end = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(if (isMint) colors.brand else colors.t1, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = gear?.name ?: stringResource(R.string.value_unknown),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = if (isMint) colors.onBrand else colors.bgWindow,
            )
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.t2,
                maxLines = 1,
            )
        }
    }
}

/** 기어 칩 기본 설명(components.js GEAR_LABEL + 설계 2.4 'D 멈춤'). */
@Composable
fun gearLabelText(gear: Gear?, moving: Boolean): String = when (gear) {
    Gear.P -> stringResource(R.string.gear_label_p)
    Gear.D -> stringResource(if (moving) R.string.gear_label_d_moving else R.string.gear_label_d_stopped)
    Gear.R -> stringResource(R.string.gear_label_r)
    Gear.N -> stringResource(R.string.gear_label_n)
    null -> ""
}

@BaroPreviews
@Composable
private fun BaroAppBarPreview() {
    BaroTheme {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            BaroAppBar(title = "코치 고르기", sub = "캐릭터마다 말투와 목소리가 달라요", nav = AppBarNav.Back)
            BaroAppBar(title = "실제 주차 안내", sub = "평행 · 오른쪽 칸", nav = AppBarNav.Close, gearChip = { GearChip(Gear.R) })
            BaroAppBar(title = "설정", gearChip = null)
            Row(Modifier.padding(horizontal = Dimens.edge), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                GearChip(Gear.D, moving = true)
                GearChip(Gear.D)
                GearChip(Gear.N)
                GearChip(null)
            }
        }
    }
}
