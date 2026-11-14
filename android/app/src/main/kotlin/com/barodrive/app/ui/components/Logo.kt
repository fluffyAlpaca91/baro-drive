package com.barodrive.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/** 로고 표시 (목업 심볼 `logo-mark`): 민트 모서리 둥근 칸 + 체크·화살표. 색이 정해진 그림이다. */
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = modifier.size(size))
}

/**
 * 글자 로고 (홈 목업 `.wm`, 프로토타입 home.js `wordmark()`): 로고 56 + '바로'(민트 글자) '운전', 40 아주 굵게, 자간 −1.
 * 글은 앱 이름(`app_name`)을 쓰고 앞 두 글자에 민트를 입힌다.
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val name = stringResource(R.string.app_name)
    val accent = name.take(WORDMARK_ACCENT_CHARS)
    val brandText = BaroTheme.colors.brandText
    Row(
        modifier = modifier
            .height(56.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark()
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = brandText)) { append(accent) }
                append(name.drop(WORDMARK_ACCENT_CHARS))
            },
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
            maxLines = 1,
        )
    }
}

private const val WORDMARK_ACCENT_CHARS = 2

@BaroPreviews
@Composable
private fun LogoPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            Wordmark()
            LogoMark(size = 88.dp)
        }
    }
}
