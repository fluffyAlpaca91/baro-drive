package com.barodrive.app.feature.autonomous

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.components.BadgeKind
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.CardEmphasis
import com.barodrive.app.ui.components.CoachAvatar
import com.barodrive.app.ui.components.CoachFace
import com.barodrive.app.ui.components.HeroCard
import com.barodrive.app.ui.components.ReviewHeader
import com.barodrive.app.ui.components.StatusBadge
import com.barodrive.app.ui.components.lineAt
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.review.AchievementLine
import com.barodrive.core.engine.review.ReviewText
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.ReviewLine
import com.barodrive.core.model.SessionRecord

// RV-1 구역 2~5·7·8·10 (프로토타입 screens/review.js `S.hero`·`achievement`·`good`·`bad`·`next`·`k4`·`k6`·`fade`, 설계 4.2 RV-1) —
// RV-5가 RV-1과 같은 구성으로 쓴다(결정사항 E T8-Q2). 글은 엔진 review.ReviewText가 만든다.
// 리뷰 화면 공용 구역의 한 벌: RV-1(feature/review)·RV-4·RV-5가 같이 쓴다.

/** 리뷰 줄 하나 '이름 | 값'(review.js `split`). [better]는 K4 좋아짐(값을 민트로). */
@Immutable
data class ReviewRowUi(val id: String?, val label: String, val value: String?, val better: Boolean = false)

/** RV-1 공통 구역 값 (review.js `model`의 hero·achv·good·bad·next·k4·k6·fade). */
@Immutable
data class ReviewSectionsUi(
    val heroKey: String = "",
    val heroVars: Map<String, Any?> = emptyMap(),
    val heroText: String = "",
    val achievement: AchievementLine? = null,
    val good: List<ReviewRowUi> = emptyList(),
    val bad: List<ReviewRowUi> = emptyList(),
    /** 아쉬운 것이 없지만 판정은 있었음 → '이번엔 아쉬운 것이 없어요.'. */
    val badEmpty: Boolean = false,
    val next: String? = null,
    /** K4 카드: null이면 없음, first면 '첫 기록이에요'. */
    val k4First: Boolean? = null,
    val k4Rows: List<ReviewRowUi> = emptyList(),
    val k6: String? = null,
    val fade: String? = null,
)

/** 기록 → RV-1 공통 구역 값. 코치·호칭은 지금 프로필 값(회차 끝 대사 rv.end.*). */
fun reviewSections(rec: SessionRecord, coach: Coach, honorific: Honorific?): ReviewSectionsUi {
    val hero = ReviewText.heroLine(rec, coach, honorific)
    val achv = ReviewText.achievement(rec)
    val bad = rows(rec.bad)
    val k4 = rec.metrics.k4
    val k4Rows = k4?.rows.orEmpty().filter { it.text.isNotEmpty() }.map { ReviewRowUi(it.id, it.label, it.text, better = it.better == true) }
    return ReviewSectionsUi(
        heroKey = hero.key, heroVars = hero.vars, heroText = hero.text, achievement = achv,
        good = rows(rec.good), bad = bad, badEmpty = bad.isEmpty() && achv != null && achv.total > 0,
        next = rec.nextTask?.let { n -> n.label.ifEmpty { n.id } },
        k4First = k4?.let { if (it.first || k4Rows.isNotEmpty()) it.first else null },
        k4Rows = k4Rows,
        k6 = ReviewText.k6(rec.metrics),
        fade = if (rec.fadedNow.isEmpty()) null else rec.fadedNote?.takeIf { it.isNotEmpty() } ?: BaroEngine.fading.note(rec.fadedNow),
    )
}

private fun rows(list: List<ReviewLine>): List<ReviewRowUi> = list.filter { it.text.isNotEmpty() }.map {
    val s = ReviewText.split(it.text)
    ReviewRowUi(it.id, s.label, s.value)
}

/** 2 코치 히어로(목업 `.hero`, 강한 말투): 회차 끝 대사. 글이 없으면 그리지 않는다. */
@Composable
fun CoachHero(face: CoachFace, text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    HeroCard(text = text, modifier = modifier, leading = { CoachAvatar(face, size = Dimens.avatarXL) })
}

/** 3 과제 달성 줄: 80% 이상(통과 회차로 셈)이면 민트 체크, 아니면 회색. */
@Composable
fun AchievementRow(a: AchievementLine?, modifier: Modifier = Modifier) {
    if (a == null) return
    val colors = BaroTheme.colors
    Row(modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
        StatusBadge(R.drawable.ic_check, if (a.ok) BadgeKind.Good else BadgeKind.Off)
        val text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = if (a.ok) colors.brandText else colors.t1)) { append(a.main) }
            a.pass?.let { append(" · $it") }
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.t2)
    }
}

/** 4 잘한 것(값은 민트) · 아쉬운 것(엔진이 고른 3줄씩). */
@Composable
fun GoodBadCards(good: List<ReviewRowUi>, bad: List<ReviewRowUi>, badEmpty: Boolean) {
    val colors = BaroTheme.colors
    if (good.isNotEmpty()) {
        BaroCard(Modifier.fillMaxWidth()) {
            ReviewHeader(stringResource(R.string.au_rv_good), leading = { StatusBadge(R.drawable.ic_check, BadgeKind.Good) })
            RvRows(good, valueColor = colors.brandText)
        }
    }
    if (bad.isNotEmpty() || badEmpty) {
        BaroCard(Modifier.fillMaxWidth()) {
            ReviewHeader(stringResource(R.string.au_rv_bad), leading = { WarnMark() })
            if (bad.isNotEmpty()) {
                RvRows(bad, valueColor = colors.t1)
            } else {
                Text(stringResource(R.string.au_rv_no_bad), style = MaterialTheme.typography.bodySmall, color = colors.t2)
            }
        }
    }
}

/** 아쉬운 것 머리 표시(목업 `.badge.warn` 안 '!'). */
@Composable
private fun WarnMark() {
    val colors = BaroTheme.colors
    Box(Modifier.size(44.dp).background(colors.caution, CircleShape), contentAlignment = Alignment.Center) {
        Text("!", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold), color = colors.onCaution)
    }
}

/** 리뷰 줄 목록(목업 `.rv li`): 26/34 이름 + 28/36 아주 굵은 값, 줄 사이 2px. */
@Composable
internal fun RvRows(rows: List<ReviewRowUi>, valueColor: Color, modifier: Modifier = Modifier, betterColor: Color? = null) {
    val colors = BaroTheme.colors
    Column(modifier.padding(top = 8.dp)) {
        rows.forEachIndexed { i, r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .then(if (i > 0) Modifier.lineAt(bottom = false, color = colors.line) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                r.value?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = if (r.better && betterColor != null) betterColor else valueColor,
                    )
                }
            }
        }
    }
}

/** 5 다음 과제 하나(목업 `.next`): 민트 3px 테두리 카드, '다음 과제 하나' + 40/52 과제 이름. */
@Composable
fun NextTaskCard(label: String?, modifier: Modifier = Modifier) {
    if (label.isNullOrEmpty()) return
    BaroCard(modifier.fillMaxWidth(), emphasis = CardEmphasis.Selected, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.au_rv_next),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = BaroTheme.colors.brandText,
        )
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

/** 7 첫 회차와 비교(K4): 3줄(좋아진 값은 민트) 또는 '첫 기록이에요'. */
@Composable
fun K4Card(first: Boolean?, rows: List<ReviewRowUi>, modifier: Modifier = Modifier) {
    if (first == null) return
    val colors = BaroTheme.colors
    BaroCard(modifier.fillMaxWidth()) {
        ReviewHeader(stringResource(R.string.au_rv_k4))
        if (first) {
            Text(stringResource(R.string.au_rv_k4_first), style = MaterialTheme.typography.bodySmall, color = colors.t2)
        } else {
            RvRows(rows, valueColor = colors.t1, betterColor = colors.brandText)
        }
    }
}

/** 8 말 수(K6): 작은 글 한 줄. */
@Composable
fun K6Line(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrEmpty()) return
    val colors = BaroTheme.colors
    Row(modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BaroIcon(R.drawable.ic_voice, contentDescription = null, size = Dimens.iconS, tint = colors.t3)
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.t3)
    }
}

/** 10 도움 줄여 가기 알림(이번 회차로 내려간 항목이 있을 때). */
@Composable
fun FadeNote(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrEmpty()) return
    val colors = BaroTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.brandSoft, RoundedCornerShape(Dimens.radiusM))
            .padding(horizontal = Dimens.space3, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroIcon(R.drawable.ic_voice, contentDescription = null, size = Dimens.iconS, tint = colors.brandText)
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
    }
}

@BaroPreviews
@Composable
private fun ReviewSectionsPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            CoachHero(CoachFace.Dad, "오늘 잘했어. 다음엔 어린이보호구역 속도를 같이 보자.")
            AchievementRow(
                AchievementLine(
                    success = 20, total = 24, rate = 0.83, pct = 83, ok = true, few = false,
                    main = "이번 회차 과제 83% (20/24)", pass = null, text = "이번 회차 과제 83% (20/24)",
                ),
            )
            GoodBadCards(
                good = listOf(ReviewRowUi("T-SIGNAL30", "회전 30m 전 깜빡이", "18/21번")),
                bad = emptyList(),
                badEmpty = true,
            )
            NextTaskCard("어린이보호구역 30 지키기")
            K4Card(false, listOf(ReviewRowUi("c2Avg", "깜빡이 켠 거리 평균", "38m → 52m", better = true)))
            K6Line("코치가 10분에 4.2번 말했어요 · 칭찬 6 : 교정 2")
            FadeNote("출발은 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.")
        }
    }
}
