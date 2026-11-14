package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroColors
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 카드 강조 (설계 2.4 카드 줄, components.css): [None] 보통 `.card` · [Selected] 고른 것·다음 과제 `.next`·`.card.on`(민트 3px) ·
 * [Hero] 강조 `.hero`·`.dday`(옅은 민트 바탕 + 민트 2px) · [Warn] 주의 `.hm-alert.warn`·`.rv-f2.pending`(주황 2px) ·
 * [Danger] 위험 `.dday.expired`·`.pk2-res.touched`(빨강 3px).
 */
enum class CardEmphasis { None, Selected, Hero, Warn, Danger }

/**
 * 카드 (목업 `.card`, components.js `card()`): 모서리 32, 안쪽 28/32, 바탕 `--bg-card`(라이트는 안쪽 테두리 `--card-line`).
 * [onClick]이 있으면 카드 전체를 누를 수 있다(코스 카드·주차 카드). 머리줄은 [CardHeader]로 넣는다.
 */
@Composable
fun BaroCard(
    modifier: Modifier = Modifier,
    emphasis: CardEmphasis = CardEmphasis.None,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.cardPaddingH, vertical = Dimens.cardPaddingV),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Dimens.space2),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusL)
    val (background, borderWidth, borderColor) = emphasis.style(colors)
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.pressClickable(enabled = true, onClick = onClick, role = Role.Button) else Modifier)
            .background(background, shape)
            .border(borderWidth, borderColor, shape)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

private data class CardStyle(val background: Color, val borderWidth: Dp, val borderColor: Color)

private fun CardEmphasis.style(c: BaroColors): CardStyle = when (this) {
    CardEmphasis.None -> CardStyle(c.bgCard, 2.dp, c.cardLine)
    CardEmphasis.Selected -> CardStyle(c.bgCard, 3.dp, c.brandText)
    CardEmphasis.Hero -> CardStyle(c.brandSoft, 2.dp, c.brandText)
    CardEmphasis.Warn -> CardStyle(c.bgCard, 2.dp, c.caution)
    CardEmphasis.Danger -> CardStyle(c.bgCard, 3.dp, c.danger)
}

/**
 * 카드 머리줄 (`.card-h`): 아이콘(40) + 제목(28/36 굵게, `h3.bm.b`) + 오른쪽 [trailing]. 제목 아래 작은 글(`p.lm.c3`)은
 * 카드 안에 [Text]로 따로 넣는다.
 */
@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) BaroIcon(icon, contentDescription = null)
        Text(
            text = title,
            modifier = Modifier
                .weight(1f, fill = trailing != null)
                .semantics { heading() },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        )
        trailing?.invoke(this)
    }
}

/**
 * 강조 카드 (목업 `.hero`): 옅은 민트 바탕 + 민트 테두리, 30/38 굵은 글. 리뷰 코치 히어로(RV-1)·코스 추천 코치 한마디(CR-1)에
 * 쓴다. 왼쪽 [leading]에 보통 코치 아바타를 둔다.
 */
@Composable
fun HeroCard(
    text: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusL)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.brandSoft, shape)
            .border(2.dp, colors.brandText, shape)
            .padding(horizontal = 28.dp, vertical = Dimens.space3),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, lineBreak = LineBreak.Heading),
        )
    }
}

@BaroPreviews
@Composable
private fun BaroCardPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            BaroCard {
                CardHeader(title = "오늘 과제", icon = R.drawable.ic_flag, trailing = { BaroTag("3개", kind = TagKind.Soft) })
                Text("깜빡이를 40m 전에 켜기", style = MaterialTheme.typography.bodyMedium)
            }
            BaroCard(emphasis = CardEmphasis.Selected) { Text("다음 과제 하나 · 정지선 앞에서 부드럽게 서기") }
            BaroCard(emphasis = CardEmphasis.Warn) { Text("확인 안 한 급제동 1건") }
            BaroCard(emphasis = CardEmphasis.Danger) { Text("유효기간이 지났어요") }
            HeroCard(
                text = "오늘 정말 잘했어. 깜빡이가 한결 빨라졌어!",
                leading = { CoachAvatar(CoachFace.Dad, size = Dimens.avatarL) },
            )
        }
    }
}
