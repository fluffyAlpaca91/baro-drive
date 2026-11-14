package com.barodrive.app.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.feature.autonomous.ShareLinkRow
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroTextButton
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.ShareStatus

// RV-1만의 구역 11 (프로토타입 review.js `S.links`). 나머지 리뷰 구역은 리뷰 화면들이 같이 쓰는 feature/autonomous의 ReviewSections·ReviewKit.

/**
 * 11 텍스트 버튼: '이 코스 내 코스로 저장 ›'(저장했으면 '내 코스에 있어요') · '가족에게 요약 보내기 ›'(보냈으면 '보냈어요 · 아버지',
 * 못 보냈으면 '연결되면 보내요' — 공용 [ShareLinkRow]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewLinksSection(m: ReviewModel, onSaveCourse: () -> Unit, onShare: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap), itemVerticalAlignment = Alignment.CenterVertically) {
        m.course?.let { course ->
            if (course.saved) CourseSaved() else BaroTextButton(stringResource(R.string.rv_course_save), onClick = onSaveCourse)
        }
        val text = when (m.share.status) {
            ShareStatus.SENT -> stringResource(R.string.rv_share_sent, m.share.to)
            ShareStatus.QUEUED -> stringResource(R.string.rv_share_queued)
            ShareStatus.NONE -> stringResource(R.string.rv_share)
        }
        ShareLinkRow(m.share.status, text, onClick = onShare)
    }
}

/** '내 코스에 있어요'(누름 없음). */
@Composable
private fun CourseSaved() {
    val colors = BaroTheme.colors
    Row(
        Modifier.heightIn(min = 64.dp).padding(horizontal = Dimens.space1),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroIcon(R.drawable.ic_star_f, contentDescription = null, size = 28.dp, tint = colors.brandText)
        Text(stringResource(R.string.rv_course_saved), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = colors.t2)
    }
}
