package com.barodrive.app.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.barodrive.app.R
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroListRow
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroSheet
import com.barodrive.app.ui.components.BaroTag
import com.barodrive.app.ui.components.CardEmphasis
import com.barodrive.app.ui.components.GearChip
import com.barodrive.app.ui.components.GroupHeader
import com.barodrive.app.ui.components.SheetAction
import com.barodrive.app.ui.components.TagKind
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Gear

/** 자리 표시 화면에서 갈 수 있는 곳 하나(설계 4.2 '나감'). 줄에는 [target]의 id와 이름이 보인다. */
@Immutable
data class PlaceholderLink(val target: ScreenId, val onClick: () -> Unit)

/**
 * 아직 만들지 않은 화면의 자리 (탐색 뼈대). 앱 바에 화면 이름, 본문에 화면 id·이름·화면 상태와 '나감' 줄을 보인다.
 * 화면 작업이 이 자리를 진짜 화면으로 바꾼다. 움직이는 중 화면도 스크롤 없이 한 화면에 들어간다.
 */
@Composable
fun PlaceholderScreen(
    screen: ScreenId,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    links: List<PlaceholderLink> = emptyList(),
) {
    BaroScaffold(
        modifier = modifier,
        topBar = {
            BaroAppBar(
                title = stringResource(screen.title),
                sub = screen.code,
                nav = if (onBack != null) AppBarNav.Back else AppBarNav.None,
                onNav = { onBack?.invoke() },
                // 정차 화면은 늘 'P 정차 중'. 움직이는 중 화면은 지금 기어를 그 화면이 넣는다.
                gearChip = if (screen.kind == ScreenKind.PARK) ({ GearChip(Gear.P) }) else null,
            )
        },
        scrollable = screen.kind == ScreenKind.PARK,
    ) {
        BaroCard(emphasis = CardEmphasis.Hero, verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            Text(screen.code, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(screen.title), style = MaterialTheme.typography.titleMedium)
            BaroTag(stringResource(screen.kindLabel()), kind = TagKind.Soft)
            Text(stringResource(R.string.placeholder_body), style = MaterialTheme.typography.bodySmall)
        }
        if (links.isNotEmpty()) {
            GroupHeader(stringResource(R.string.placeholder_links))
            links.forEachIndexed { index, link ->
                BaroListRow(
                    title = stringResource(link.target.title),
                    sub = link.target.code,
                    onClick = link.onClick,
                    divider = index != links.lastIndex,
                )
            }
        }
    }
}

/** 아직 만들지 않은 시트의 자리. 제목에 시트 이름, 부제에 id, 버튼 [닫기]. */
@Composable
fun PlaceholderSheet(screen: ScreenId, onClose: () -> Unit) {
    BaroSheet(
        onDismissRequest = onClose,
        title = stringResource(screen.title),
        sub = screen.code,
        actions = listOf(SheetAction(stringResource(R.string.ui_close), onClick = onClose)),
    ) {
        Text(stringResource(R.string.placeholder_body))
    }
}

private fun ScreenId.kindLabel(): Int = when {
    host == ScreenHost.SHEET -> R.string.screen_kind_sheet
    host == ScreenHost.COVER -> R.string.screen_kind_cover
    kind == ScreenKind.MOVING -> R.string.screen_kind_moving
    else -> R.string.screen_kind_park
}

@BaroScreenPreviews
@Composable
private fun PlaceholderScreenPreview() {
    BaroTheme {
        PlaceholderScreen(
            screen = ScreenId.ON_2,
            onBack = {},
            links = listOf(PlaceholderLink(ScreenId.ON_3) {}, PlaceholderLink(ScreenId.ON_1) {}),
        )
    }
}

@BaroScreenPreviews
@Composable
private fun PlaceholderSheetPreview() {
    BaroTheme {
        PlaceholderSheet(ScreenId.CO_2, onClose = {})
    }
}
