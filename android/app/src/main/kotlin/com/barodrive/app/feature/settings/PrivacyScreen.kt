package com.barodrive.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroConfirmDialog
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroTag
import com.barodrive.app.ui.components.BaroTextButton
import com.barodrive.app.ui.components.BaroTextField
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.components.SwitchRow
import com.barodrive.app.ui.components.TagKind
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Consent
import com.barodrive.core.model.Profile

/** ST-4 경로 화면. [onWithdrawn] = 처음 화면(ON-1)으로(뒤로 스택 비움). */
@Composable
fun PrivacyRoute(onBack: () -> Unit, onWithdrawn: () -> Unit, viewModel: PrivacyViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is PrivacyEffect.Toast -> toast.show(e.text)
                PrivacyEffect.GoOnboarding -> onWithdrawn()
            }
        }
    }
    PrivacyScreen(
        state = state,
        name = viewModel.nameText,
        onBack = onBack,
        onName = viewModel::setName,
        onNameBlur = viewModel::nameBlur,
        onToggle = viewModel::toggle,
        onClear = viewModel::askClear,
        onDismiss = viewModel::dismissDialog,
        onConfirmWithdraw = viewModel::confirmWithdraw,
        onConfirmClear = viewModel::confirmClear,
    )
}

/**
 * ST-4 공유와 개인정보 (settings.js ST-4): '받는 사람' 이름 칸 + 안내 · '동의' 스위치 4개(연습 기록 저장은 '필수') · 녹음·위치 안내 ·
 * [모든 연습 기록 지우기](위험 색). 되돌릴 수 없는 일(동의 철회·모두 지우기)은 확인 대화상자를 거친다.
 */
@Composable
fun PrivacyScreen(
    state: PrivacyUiState,
    name: String,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onNameBlur: () -> Unit,
    onToggle: (ConsentKey) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onConfirmWithdraw: () -> Unit,
    onConfirmClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaroScaffold(
        modifier = modifier,
        topBar = { BaroAppBar(title = stringResource(R.string.st4_title), sub = stringResource(R.string.st4_sub), nav = AppBarNav.Back, onNav = onBack) },
        overlay = {
            when (state.dialog) {
                PrivacyDialog.WITHDRAW -> BaroConfirmDialog(
                    title = stringResource(R.string.st4_withdraw_title),
                    onConfirm = onConfirmWithdraw,
                    onDismissRequest = onDismiss,
                    confirmLabel = stringResource(R.string.st4_withdraw_confirm),
                    danger = true,
                ) { Text(stringResource(R.string.st4_withdraw_body)) }
                PrivacyDialog.CLEAR -> BaroConfirmDialog(
                    title = stringResource(R.string.st4_clear_title),
                    onConfirm = onConfirmClear,
                    onDismissRequest = onDismiss,
                    confirmLabel = stringResource(R.string.st4_clear_confirm),
                    danger = true,
                ) { Text(stringResource(R.string.st4_clear_body)) }
                null -> Unit
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            SectionTitle(stringResource(R.string.st4_to))
            BaroTextField(
                value = name,
                onValueChange = onName,
                modifier = Modifier.onFocusChanged { if (!it.hasFocus) onNameBlur() },
                placeholder = SettingsRules.DEFAULT_TO,
                maxLength = SettingsRules.NAME_MAX,
                label = stringResource(R.string.st4_to_label),
            )
            NoteRow(stringResource(R.string.st4_to_note), icon = R.drawable.ic_send)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            SectionTitle(stringResource(R.string.st4_consent))
            Column {
                state.consents.forEachIndexed { i, c ->
                    SwitchRow(
                        title = c.title,
                        checked = c.on,
                        onCheckedChange = { onToggle(c.key) },
                        sub = c.sub,
                        divider = i != state.consents.lastIndex,
                        titleTrailing = if (c.required) ({ BaroTag(stringResource(R.string.st4_required), kind = TagKind.Soft) }) else null,
                    )
                }
            }
        }
        NoteRow(stringResource(R.string.st4_privacy_note), icon = R.drawable.ic_shield)
        BaroTextButton(text = stringResource(R.string.st4_clear), onClick = onClear, danger = true)
    }
}

/** 묶음 제목(`.st-sec .grp-h`, 위아래 여백 없이). */
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = BaroTheme.colors.t3)
}

private val previewState = PrivacyUiState(SettingsRules.consentRows(Profile(consent = Consent(record = true, family = true))))

@BaroScreenPreviews
@Composable
private fun PrivacyPreview() {
    BaroTheme {
        PrivacyScreen(previewState, name = "아버지", onBack = {}, onName = {}, onNameBlur = {}, onToggle = {}, onClear = {}, onDismiss = {}, onConfirmWithdraw = {}, onConfirmClear = {})
    }
}

@BaroScreenPreviews
@Composable
private fun PrivacyWithdrawPreview() {
    BaroTheme {
        PrivacyScreen(
            previewState.copy(dialog = PrivacyDialog.WITHDRAW), name = "가족",
            onBack = {}, onName = {}, onNameBlur = {}, onToggle = {}, onClear = {}, onDismiss = {}, onConfirmWithdraw = {}, onConfirmClear = {},
        )
    }
}

@BaroWideScreenPreviews
@Composable
private fun PrivacyWidePreview() {
    BaroTheme {
        PrivacyScreen(previewState, name = "아버지", onBack = {}, onName = {}, onNameBlur = {}, onToggle = {}, onClear = {}, onDismiss = {}, onConfirmWithdraw = {}, onConfirmClear = {})
    }
}
