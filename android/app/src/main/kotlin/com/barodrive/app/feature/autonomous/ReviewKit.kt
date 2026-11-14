package com.barodrive.app.feature.autonomous

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.components.BadgeKind
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroTextButton
import com.barodrive.app.ui.components.CardEmphasis
import com.barodrive.app.ui.components.EmptyState
import com.barodrive.app.ui.components.ReviewHeader
import com.barodrive.app.ui.components.StatusBadge
import com.barodrive.app.ui.components.WeatherTip
import com.barodrive.app.ui.components.lineAt
import com.barodrive.app.ui.components.pressClickable
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.engine.review.ReviewText
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareStatus
import com.barodrive.core.model.WeatherType

// 리뷰 화면 구역 중 RV-4·RV-5가 쓰는 것 (프로토타입 screens/review.js `BaroApp.review.sections`의 f2·tips·links·actions와 '기록 없음',
// review.css [data-rv-kit]). 리뷰 화면 공용 구역의 한 벌 — RV-1(feature/review)·RV-4·RV-5가 같이 쓴다.

/** 급제동 한 건 (review.js 모델 `f2[]`): 글 '28km/h에서 1.4초 만에 정지'와 본인 확인 답. */
@Immutable
data class F2RowUi(val id: String, val text: String, val choice: F2Choice?)

/** 날씨 팁 한 줄 (review.js `tipsOf`). */
@Immutable
data class ReviewTipUi(val text: String, @param:DrawableRes val icon: Int)

/** 기록의 급제동 줄 (id가 없으면 'f2-순번'). */
fun f2Rows(rec: SessionRecord): List<F2RowUi> = rec.f2.mapIndexed { i, r ->
    F2RowUi(id = r.id.ifEmpty { "f2-${i + 1}" }, text = ReviewText.f2(r), choice = r.choice)
}

/** 기록의 팁 줄: 팁 아이콘(없으면 날씨 아이콘 — 비 · 눈 · 아주 나쁜 날 · 그 밖 정보). */
fun tipsOf(rec: SessionRecord): List<ReviewTipUi> {
    val w = rec.weather
    val wx = when {
        w?.type == WeatherType.RAIN -> "i-rain"
        w?.type == WeatherType.SNOW -> "i-snow"
        w?.severe != null -> "i-hazard"
        else -> "i-info"
    }
    return rec.tips.filter { it.text.isNotBlank() }.map { ReviewTipUi(it.text.trim(), iconFor(it.icon ?: wx)) }
}

/** 프로토타입 심볼 id('i-rain') → 그림. 모르는 id는 정보 아이콘. */
@DrawableRes
fun iconFor(id: String?): Int = when (id) {
    "i-rain" -> R.drawable.ic_rain
    "i-snow" -> R.drawable.ic_snow
    "i-hazard" -> R.drawable.ic_hazard
    "i-shield" -> R.drawable.ic_shield
    "i-sun" -> R.drawable.ic_sun
    "i-temp" -> R.drawable.ic_temp
    "i-slow" -> R.drawable.ic_slow
    "i-check" -> R.drawable.ic_check
    "i-brake" -> R.drawable.ic_brake
    "i-voice" -> R.drawable.ic_voice
    else -> R.drawable.ic_info
}

/**
 * 급제동 본인 확인 카드 (review.js `S.f2`, 설계 4.2 RV-1 6번, 결정-54): '급제동 n번 · 기록에서 뺐어요', 건마다 글 + "어떤 상황이었나요?"
 * [위험 피하기] [내 실수]. 고르면 "본인 확인: 위험 피하기" + [바꾸기]. 확인 안 한 건이 있으면 주의 색 테두리. 감점·통과와는 상관없다.
 */
@Composable
fun F2CheckCard(rows: List<F2RowUi>, onChoose: (String, F2Choice) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    val colors = BaroTheme.colors
    val editing = remember { mutableStateListOf<String>() }
    val pending = rows.any { it.choice == null }
    BaroCard(modifier = modifier.fillMaxWidth(), emphasis = if (pending) CardEmphasis.Warn else CardEmphasis.None) {
        ReviewHeader(
            title = stringResource(R.string.au_rv_f2_title, rows.size),
            leading = { StatusBadge(R.drawable.ic_brake, BadgeKind.Warn) },
        )
        rows.forEachIndexed { i, f ->
            val open = f.choice == null || f.id in editing
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (i > 0) Modifier.lineAt(bottom = false, color = colors.line) else Modifier)
                    .padding(top = if (i == 0) 12.dp else 20.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(f.text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                if (open) {
                    Text(stringResource(R.string.au_rv_f2_question), style = MaterialTheme.typography.labelLarge, color = colors.t2)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
                        F2Choice.entries.forEach { c ->
                            BaroButton(
                                text = f2Label(c),
                                onClick = {
                                    editing.remove(f.id)
                                    onChoose(f.id, c)
                                },
                                modifier = Modifier.weight(1f),
                                size = BaroButtonSize.S,
                                kind = if (f.choice == c) BaroButtonKind.Done else BaroButtonKind.Ghost,
                            )
                        }
                    }
                } else {
                    Row(Modifier.heightIn(min = Dimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
                        BaroIcon(R.drawable.ic_check, contentDescription = null, size = 28.dp, tint = colors.brandText)
                        Text(
                            stringResource(R.string.au_rv_f2_done, f2Label(f.choice)),
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = colors.brandText,
                        )
                        Spacer(Modifier.weight(1f))
                        BaroTextButton(text = stringResource(R.string.au_rv_f2_change), onClick = { editing.add(f.id) }, trailingIcon = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun f2Label(c: F2Choice): String = stringResource(if (c == F2Choice.AVOID) R.string.au_rv_f2_avoid else R.string.au_rv_f2_mistake)

/** 날씨 팁 줄들(review.js `S.tips`). */
@Composable
fun ReviewTips(tips: List<ReviewTipUi>, modifier: Modifier = Modifier) {
    if (tips.isEmpty()) return
    Column(modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tips.forEach { WeatherTip(it.text, icon = it.icon) }
    }
}

/**
 * '가족에게 요약 보내기 ›' 줄 버튼 (share.js `link`, settings.css `.sh-link`, 설계 4.2 RV-1 11번): 보냈으면 '보냈어요 · 아버지',
 * 못 보냈으면 '연결되면 보내요'(회색 글, 보낼 예정이면 주의 색 아이콘). 누르면 [onClick](RV-7 시트 또는 다시 보내기).
 */
@Composable
fun ShareLinkRow(state: ShareStatus, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    val content = if (state == ShareStatus.NONE) colors.brandText else colors.t2
    val iconTint = if (state == ShareStatus.QUEUED) colors.caution else content
    Row(
        modifier = modifier
            .heightIn(min = Dimens.minTouch)
            .pressClickable(enabled = true, onClick = onClick, role = Role.Button)
            .padding(horizontal = Dimens.space1),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroIcon(R.drawable.ic_share, contentDescription = null, size = 28.dp, tint = iconTint)
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = content)
        BaroIcon(R.drawable.ic_chev, contentDescription = null, size = 28.dp, tint = content)
    }
}

/**
 * 리뷰 하단 (review.js `S.actions`): 읽기 전용(또는 기록 없음)은 [닫기] 하나, 아니면 [홈으로] + [primary](있을 때).
 * 하단 버튼 줄(BaroScaffold bottomBar) 안에서 부른다.
 */
@Composable
fun RowScope.ReviewActions(readOnly: Boolean, onClose: () -> Unit, onHome: () -> Unit, primary: Pair<String, () -> Unit>?) {
    if (readOnly) {
        BaroButton(text = stringResource(R.string.ui_close), onClick = onClose, modifier = Modifier.weight(1f))
        return
    }
    BaroButton(text = stringResource(R.string.au_rv_home), onClick = onHome, modifier = Modifier.weight(1f))
    if (primary != null) {
        BaroButton(text = primary.first, onClick = primary.second, modifier = Modifier.weight(1f), kind = BaroButtonKind.Primary)
    }
}

/** 기록을 찾을 수 없을 때(review.js `missing`). */
@Composable
fun RecordMissing(modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.au_rv_missing_title),
        modifier = modifier,
        icon = R.drawable.ic_history,
        body = stringResource(R.string.au_rv_missing_body),
    )
}

@BaroPreviews
@Composable
private fun ReviewKitPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            F2CheckCard(
                rows = listOf(
                    F2RowUi("f2-1", "56km/h에서 4.3초 만에 정지", null),
                    F2RowUi("f2-2", "28km/h에서 1.4초 만에 12km/h까지 줄임", F2Choice.AVOID),
                ),
                onChoose = { _, _ -> },
            )
            ReviewTips(listOf(ReviewTipUi("빗길 회차예요. 다음에도 차간거리를 두 배로 두세요.", R.drawable.ic_rain)))
            ShareLinkRow(ShareStatus.NONE, "가족에게 요약 보내기", onClick = {})
            ShareLinkRow(ShareStatus.SENT, "보냈어요 · 아버지", onClick = {})
        }
    }
}
