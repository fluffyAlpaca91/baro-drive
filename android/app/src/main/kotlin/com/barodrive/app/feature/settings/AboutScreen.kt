package com.barodrive.app.feature.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.LogoMark
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * ST-5 앱 안내 (프로토타입 screens/settings.js ST-5, 설계 4.2 ST-5): 정적 글 — 로고와 '바로운전' + 버전, 안내 세 줄(교습이 아니라 기록 도구 ·
 * 판정 기준 일부는 설계 가정 · 법규 위반을 판정하지 않음), 버전 줄. 상태가 없어 ViewModel이 없다.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    val version = SettingsRules.VERSION
    BaroScaffold(
        modifier = modifier,
        topBar = { BaroAppBar(title = stringResource(R.string.st5_title), nav = AppBarNav.Back, onNav = onBack) },
    ) {
        BaroCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
                LogoMark(size = 88.dp)
                Column {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = colors.brandText)) { append(stringResource(R.string.st5_brand_a)) }
                            append(stringResource(R.string.st5_brand_b))
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(version, style = MaterialTheme.typography.labelLarge, color = colors.t3)
                }
            }
        }
        BaroCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            AboutItem(R.drawable.ic_shield, stringResource(R.string.st5_item_tool))
            AboutItem(R.drawable.ic_info, stringResource(R.string.st5_item_rules))
            AboutItem(R.drawable.ic_check, stringResource(R.string.st5_item_law))
        }
        NoteRow(stringResource(R.string.st5_version, version))
    }
}

/** 안내 한 줄(`.st-about li`): 64 옅은 민트 원 안 아이콘 + 28/36 글. */
@Composable
private fun AboutItem(@DrawableRes icon: Int, text: String) {
    val colors = BaroTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        Box(Modifier.size(Dimens.minTouch).background(colors.brandSoft, CircleShape), contentAlignment = Alignment.Center) {
            BaroIcon(icon, contentDescription = null, tint = colors.brandText)
        }
        Text(text, Modifier.weight(1f).padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@BaroScreenPreviews
@Composable
private fun AboutPreview() {
    BaroTheme { AboutScreen(onBack = {}) }
}

@BaroWideScreenPreviews
@Composable
private fun AboutWidePreview() {
    BaroTheme { AboutScreen(onBack = {}) }
}
