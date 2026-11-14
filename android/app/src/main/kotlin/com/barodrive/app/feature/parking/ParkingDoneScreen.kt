package com.barodrive.app.feature.parking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.lineAt
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Coach
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.ParkingHandbrake
import com.barodrive.core.model.ParkingResult
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.ParkingWheel
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.TurnDir

/**
 * PK-4 경로 화면. [onOther] = [다른 주차](→ PK-1, 그 종류·방향), [onClose] = 앱 바 닫기·[닫기](읽기 전용이면 온 곳으로, 아니면 홈),
 * [onShare] = '가족에게 요약 보내기'(요약 보내기 시트가 없으면 null — 그 줄을 상태 글로만 보인다).
 */
@Composable
fun ParkingDoneRoute(
    onHome: () -> Unit,
    onOther: (type: String, side: String) -> Unit,
    onClose: (readOnly: Boolean) -> Unit,
    onShare: ((recordId: String) -> Unit)?,
    viewModel: ParkingDoneViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    val res = rememberTextRes()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect -> if (effect is ReviewEffect.Toast) toast.show(res.text(effect.text)) }
    }
    val readOnly = state.model?.readOnly ?: viewModel.readOnly
    ParkingDoneScreen(
        state = state,
        onHome = onHome,
        onOther = {
            val pk = state.model?.record?.parking
            onOther(pk?.type?.code ?: ParkingSpot.PARALLEL, pk?.side?.code ?: ParkingSpot.RIGHT)
        },
        onClose = { onClose(readOnly) },
        onSaveCourse = viewModel::saveCourse,
        onShare = onShare?.let { share -> { state.model?.record?.id?.let(share) } },
        onQueuedShare = { toast.show(res.text(R.string.pk_rv_share_queued_toast)) },
    )
}

/**
 * PK-4 주차 연습 마무리 (프로토타입 parking-guide.js PK-4 + 리뷰 틀, 설계 4.2 PK-4, 결정-60): 앱 바(닫기 · '주차 연습 마무리' ·
 * '평행 · 오른쪽 칸 · 1회차') · 코치 히어로(pk.done, 다 마친 실제 안내만) · 결과 카드('평행 · 오른쪽 칸 주차를 마쳤어요' · 걸린 시간 ·
 * 단계 n/전체 · '천천히' 안내 · 바퀴 + PK3 문장 · 사이드브레이크 + PB2 문장) · 내 코스·요약 보내기 줄 · 하단 [홈으로] [다른 주차]
 * (읽기 전용·기록 없음은 [닫기]). 세워 두고 연습 기록도 같은 화면으로 그린다.
 */
@Composable
fun ParkingDoneScreen(
    state: ParkingDoneUiState,
    onHome: () -> Unit,
    onOther: () -> Unit,
    onClose: () -> Unit,
    onSaveCourse: () -> Unit,
    onShare: (() -> Unit)?,
    onQueuedShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = rememberTextRes()
    val m = state.model
    BaroScaffold(
        modifier = modifier,
        topBar = {
            BaroAppBar(
                title = stringResource(R.string.pk4_title),
                sub = m?.let { ParkingDoneText.sub(it.record, res) },
                nav = AppBarNav.Close,
                onNav = onClose,
            )
        },
        bottomBar = {
            ReviewActions(
                readOnly = m == null || m.readOnly,
                primaryLabel = stringResource(R.string.pk4_other),
                onHome = onHome,
                onPrimary = onOther,
                onClose = onClose,
            )
        },
    ) {
        when {
            state.loading -> Unit
            m == null -> ReviewMissing()
            else -> {
                ParkingDoneText.heroKey(m.record)?.let { key -> ReviewHero(m.coach, ModeLines.render(key, m.coach, emptyMap(), m.honorific)) }
                ParkingResultCard(ParkingDoneText.headline(m.record, res), ParkingDoneText.rows(m, res))
                ReviewLinks(m.course, m.share, onSaveCourse, onShare, onQueuedShare)
            }
        }
    }
}

/**
 * 결과 카드 (parking-guide.css `.pk4-res`): 머리(36/44 아주 굵게) + 줄(`.rv li` 높이 52 이상, 줄 사이 2px 선): 이름(26/34) · 값(28/36 아주
 * 굵게, 좋으면 민트·주의면 주의 글자색) · 값 아래 코치 문장(26/34 보조 글).
 */
@Composable
private fun ParkingResultCard(headline: String, rows: List<ParkingDoneRow>) {
    val colors = BaroTheme.colors
    BaroCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space1)) {
        Text(headline, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
        Column {
            rows.forEachIndexed { i, row ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (i > 0) Modifier.lineAt(bottom = false, color = colors.line) else Modifier)
                        .heightIn(min = 52.dp)
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        Text(row.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(
                            row.value,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = when {
                                row.good -> colors.brandText
                                row.warn -> colors.cautionText
                                else -> colors.t1
                            },
                            textAlign = TextAlign.End,
                        )
                    }
                    row.line?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, modifier = Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.t2)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 미리보기

private val previewRecord = SessionRecord(
    id = "sess-20261111-0900",
    mode = 6,
    noInMode = 1,
    durationSec = 89.4,
    parking = ParkingResult(
        type = ParkingType.PARALLEL,
        side = TurnDir.RIGHT,
        key = "parallel-R",
        total = 8,
        reached = 8,
        done = true,
        sec = 83.5,
        pk2 = 1,
        wheel = ParkingWheel(steer = -8.0, straight = false),
        handbrake = ParkingHandbrake(result = EventResult.FAIL, on = false),
    ),
)

@ModeScreenPreviews
@Composable
private fun ParkingDonePreview() {
    BaroTheme {
        ParkingDoneScreen(
            ParkingDoneUiState(loading = false, model = ModeReviewModel(previewRecord, readOnly = false, coach = Coach.DAD, course = ReviewCourse("시민체육관 주차장", saved = false))),
            onHome = {},
            onOther = {},
            onClose = {},
            onSaveCourse = {},
            onShare = {},
            onQueuedShare = {},
        )
    }
}

@ModeScreenPreviews
@Composable
private fun ParkingDoneVirtualPreview() {
    val rec = SessionRecord(
        id = "sess-20261111-0905",
        kind = RecordKind.VIRTUAL_PARKING,
        mode = 6,
        parking = ParkingResult(type = ParkingType.BACK, side = TurnDir.LEFT, key = "back-L", virtual = true, result = "success", sec = 74.0, switches = 2, wheelStraight = false),
    )
    BaroTheme {
        ParkingDoneScreen(
            ParkingDoneUiState(loading = false, model = ModeReviewModel(rec, readOnly = true, coach = Coach.MOM)),
            onHome = {},
            onOther = {},
            onClose = {},
            onSaveCourse = {},
            onShare = null,
            onQueuedShare = {},
        )
    }
}

@ModeScreenPreviews
@Composable
private fun ParkingDoneMissingPreview() {
    BaroTheme {
        ParkingDoneScreen(ParkingDoneUiState(loading = false), onHome = {}, onOther = {}, onClose = {}, onSaveCourse = {}, onShare = null, onQueuedShare = {})
    }
}
