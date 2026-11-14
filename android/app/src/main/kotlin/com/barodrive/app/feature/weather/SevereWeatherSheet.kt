package com.barodrive.app.feature.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.barodrive.app.R
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroCheckBox
import com.barodrive.app.ui.components.BaroSheet
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.SheetAction
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Severe
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 아주 나쁜 날 시트가 화면에 시키는 일. */
sealed interface SevereEffect {
    data class Toast(val text: String) : SevereEffect

    data object Close : SevereEffect
}

/**
 * PR-1 '아주 나쁜 날' 시트 (프로토타입 screens/weather.js `openSevere`, 설계 4.2 PR-1·6.8): 세 줄 중 하나를 누르면 준비 세션
 * `weather.severe`에 두고 닫힌다(바뀌었으면 토스트). [닫기]는 그대로 닫는다.
 */
@HiltViewModel
class SevereWeatherViewModel @Inject constructor(
    private val session: SessionController,
) : ViewModel() {
    private val _effects = Channel<SevereEffect>(Channel.BUFFERED)

    val effects: Flow<SevereEffect> = _effects.receiveAsFlow()

    /** 지금 고른 까닭(없으면 null = 해당 없어요). */
    val selected: StateFlow<Severe?> = session.state.map { it.prep?.weather?.severe }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.state.value.prep?.weather?.severe)

    /** 줄 누름: 고르고 닫는다. */
    fun choose(severe: Severe?) {
        viewModelScope.launch {
            val prev = session.state.value.prep?.weather?.severe
            session.updatePrep { it.copy(weather = it.weather.copy(severe = severe)) }
            if (prev != severe) _effects.send(SevereEffect.Toast(WeatherRules.severeToast(severe)))
            _effects.send(SevereEffect.Close)
        }
    }
}

/** 아주 나쁜 날 시트 경로. [onClose] = 시트 닫기. */
@Composable
fun SevereWeatherSheetRoute(onClose: () -> Unit, viewModel: SevereWeatherViewModel = hiltViewModel()) {
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is SevereEffect.Toast -> toast.show(e.text, R.drawable.ic_hazard)
                SevereEffect.Close -> onClose()
            }
        }
    }
    SevereWeatherSheetContent(selected, onChoose = viewModel::choose, onClose = onClose)
}

/** '아주 나쁜 날인가요?' 시트(weather.css `.wx-sheet`): 고르기 줄 셋(높이 96, 고른 줄은 민트 3px 테두리) + [닫기]. */
@Composable
fun SevereWeatherSheetContent(selected: Severe?, onChoose: (Severe?) -> Unit, onClose: () -> Unit) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    BaroSheet(
        onDismissRequest = onClose,
        title = stringResource(R.string.weather_severe_title),
        sub = stringResource(R.string.weather_severe_sub),
        actions = listOf(SheetAction(stringResource(R.string.ui_close), onClick = onClose, kind = BaroButtonKind.Ghost)),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            WeatherRules.SEVERE_OPTIONS.forEach { o ->
                val on = o.severe == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .selectable(on, interactionSource = null, indication = BaroPressIndication, role = Role.RadioButton, onClick = { onChoose(o.severe) })
                        .background(colors.bgCard, shape)
                        .border(3.dp, if (on) colors.brand else Color.Transparent, shape)
                        .padding(horizontal = Dimens.space3, vertical = Dimens.space2),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BaroCheckBox(checked = on)
                    Text(o.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = colors.t1)
                }
            }
        }
    }
}

@BaroScreenPreviews
@Composable
private fun SevereWeatherSheetPreview() {
    BaroTheme { SevereWeatherSheetContent(selected = Severe.ICE, onChoose = {}, onClose = {}) }
}
