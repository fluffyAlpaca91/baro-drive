package com.barodrive.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.barodrive.app.R
import com.barodrive.app.data.settings.DataSource
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Gear

// 차 상태를 화면 글로 옮기는 작은 함수들. 못 읽는 값(null)은 '—'.

@Composable
fun speedLabel(kmh: Int?): String =
    if (kmh == null) stringResource(R.string.value_unknown) else stringResource(R.string.speed_kmh, kmh)

@Composable
fun gearLabel(gear: Gear?): String = gear?.name ?: stringResource(R.string.value_unknown)

@Composable
fun blinkerLabel(blinker: Blinker?): String = when (blinker) {
    null -> stringResource(R.string.value_unknown)
    Blinker.OFF -> stringResource(R.string.blinker_off)
    Blinker.LEFT -> stringResource(R.string.blinker_left)
    Blinker.RIGHT -> stringResource(R.string.blinker_right)
}

@Composable
fun dataSourceLabel(source: DataSource): String = when (source) {
    DataSource.SIM -> stringResource(R.string.data_source_sim)
    DataSource.CAR -> stringResource(R.string.data_source_car)
}
