package com.barodrive.app.feature.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.barodrive.app.ui.components.BaroChip
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.PrepWeather
import com.barodrive.core.model.RainBy
import com.barodrive.core.model.VehicleSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** PR-1 날씨 줄에 그릴 값: 그림 값 + 지금 준비 세션 날씨(칩 켜짐). 준비 세션이 없으면 null. */
data class WeatherCardUi(val model: WeatherCardModel, val weather: PrepWeather)

/** 날씨 줄 토스트(글 + 아이콘). */
data class WeatherToast(val text: String, val icon: Int)

/**
 * PR-1 날씨 줄 (프로토타입 screens/weather.js `Baro.predriveCard({ id: 'weather' })`, 설계 4.2 PR-1·5.6, 결정-17).
 * PR-1 화면 안에서만 산다(PR-1 경로 범위의 ViewModel): 와이퍼가 켜진 시간을 스냅숏 시각으로 쌓아 30초가 넘으면 비로 시작하게 하고,
 * 칩·시트로 고른 값을 준비 세션 `weather`에 둔다. 시연에서 세워 둔 차의 와이퍼 30초를 재도록 시뮬레이터 시계를 붙잡는 일
 * (weather.js `weather-wiper`)은 시연 패널(demo/DemoViewModel)이 한다.
 */
@HiltViewModel
class PredriveWeatherViewModel @Inject constructor(
    private val session: SessionController,
) : ViewModel() {
    private var lastT: Double? = null
    private val _toasts = Channel<WeatherToast>(Channel.BUFFERED)

    val toasts: Flow<WeatherToast> = _toasts.receiveAsFlow()

    val uiState: StateFlow<WeatherCardUi?> = session.state
        .map { s -> s.prep?.let { prep -> WeatherCardUi(WeatherRules.model(prep.weather, s.snapshot).second, prep.weather) } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch {
            session.state.map { it.snapshot }.distinctUntilChanged().collect { tick(it) }
        }
    }

    /** 스냅숏마다: 와이퍼 시간을 쌓고 비를 맞춘다(바뀔 때만 준비 세션을 고친다). */
    private suspend fun tick(snap: VehicleSnapshot?) {
        val t = snap?.t
        val prev = lastT
        val dt = if (t != null && prev != null) (t - prev).coerceAtLeast(0.0) else 0.0
        lastT = t
        val cur = session.state.value.prep?.weather ?: return
        if (next(cur, snap, dt) == cur) return
        session.updatePrep { p -> p.copy(weather = next(p.weather, snap, dt)) }
    }

    private fun next(w: PrepWeather, snap: VehicleSnapshot?, dt: Double): PrepWeather =
        WeatherRules.model(WeatherRules.addWiper(w, snap, dt), snap).first

    /** [오늘 눈 와요]. */
    fun toggleSnow() {
        viewModelScope.launch {
            val p = session.updatePrep { it.copy(weather = it.weather.copy(snow = !it.weather.snow)) } ?: return@launch
            _toasts.send(WeatherToast(WeatherRules.snowToast(p.weather.snow), R.drawable.ic_snow))
        }
    }

    /** [오늘 비 와요](와이퍼를 못 읽을 때만 보임). */
    fun toggleRain() {
        viewModelScope.launch {
            val p = session.updatePrep { pr ->
                val on = pr.weather.rainBy != RainBy.CHIP
                pr.copy(weather = pr.weather.copy(rainBy = if (on) RainBy.CHIP else null, rain = on))
            } ?: return@launch
            _toasts.send(WeatherToast(WeatherRules.rainToast(p.weather.rainBy == RainBy.CHIP), R.drawable.ic_rain))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * PR-1 날씨 줄 경로 조각 — PR-1(ui-loop)이 [WeatherRules.MODES]일 때 카드 자리([WeatherRules.orderFor])에 끼운다.
 * [onOpenSevere] = 아주 나쁜 날 시트([SevereWeatherSheet]) 열기.
 */
@Composable
fun PredriveWeatherCardRoute(onOpenSevere: () -> Unit, modifier: Modifier = Modifier, viewModel: PredriveWeatherViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LaunchedEffect(viewModel) { viewModel.toasts.collect { toast.show(it.text, it.icon) } }
    val s = state ?: return
    PredriveWeatherCard(s, onSnow = viewModel::toggleSnow, onSevere = onOpenSevere, onRain = viewModel::toggleRain, modifier = modifier)
}

/**
 * 날씨 줄 (목업 s03 `.wx-row`, weather.css): 칩 [오늘 눈 와요] · [아주 나쁜 날 ▾] · (와이퍼를 못 읽을 때) [오늘 비 와요] + 오른쪽 글
 * (제목 '오늘 날씨', 상태 글, 낮춘 기준 줄). 맨 위로 올라온 때(와이퍼 켜짐·0°C 이하)는 주의 색 테두리 카드 모양.
 * 글은 `flex:1 1 280px`처럼 칩 옆 남은 폭이 280 이상이면 칩 옆에서 남은 폭을 채우고(그 안에서 줄바꿈), 모자라면(칩 셋) 아래 줄로 내려간다 —
 * FlowRow는 무게(weight)가 있는 칸을 최소 고유 폭(여기서는 280)으로 줄을 나눈 뒤 남은 폭을 준다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PredriveWeatherCard(
    state: WeatherCardUi,
    onSnow: () -> Unit,
    onSevere: () -> Unit,
    onRain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BaroTheme.colors
    val m = state.model
    val shape = RoundedCornerShape(Dimens.radiusL)
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (m.top) {
                    Modifier
                        .background(colors.bgCard, shape)
                        .border(2.dp, colors.caution, shape)
                        .padding(horizontal = 28.dp, vertical = Dimens.space3)
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            BaroChip(
                stringResource(R.string.weather_snow), selected = state.weather.snow, onClick = onSnow,
                icon = R.drawable.ic_snow, role = Role.Checkbox,
            )
            BaroChip(stringResource(R.string.weather_severe_chip), selected = state.weather.severe != null, onClick = onSevere, icon = R.drawable.ic_hazard, role = Role.Button)
            if (m.rainChip) {
                BaroChip(
                    stringResource(R.string.weather_rain), selected = state.weather.rainBy == RainBy.CHIP, onClick = onRain,
                    icon = R.drawable.ic_rain, role = Role.Checkbox,
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .widthIn(min = 280.dp),
        ) {
            Text(m.title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = colors.t2)
            Text(m.status, style = MaterialTheme.typography.labelMedium, color = colors.t3)
            if (m.limit.isNotEmpty()) {
                Text(
                    m.limit,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.brandText,
                )
            }
        }
    }
}

@BaroPreviews
@Composable
private fun PredriveWeatherCardPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            val plain = PrepWeather()
            PredriveWeatherCard(WeatherCardUi(WeatherRules.model(plain, VehicleSnapshot(t = 0.0, wiper = false, outsideTemp = 15.0)).second, plain), {}, {}, {})
            val snow = PrepWeather(snow = true)
            PredriveWeatherCard(WeatherCardUi(WeatherRules.model(snow, VehicleSnapshot(t = 0.0, wiper = true, outsideTemp = -2.0)).second, snow), {}, {}, {})
            val blind = PrepWeather(rainBy = RainBy.CHIP, rain = true)
            PredriveWeatherCard(WeatherCardUi(WeatherRules.model(blind, VehicleSnapshot(t = 0.0)).second, blind), {}, {}, {})
        }
    }
}
