package com.barodrive.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import java.time.LocalDate

/** 날짜 고르기 칸 (연·월·일). */
enum class DateUnit { Year, Month, Day }

/**
 * 날짜 고르기 계산 (components.js `datePicker()`의 `addUnit`·`clampDay`). 한 칸 옮기면 그 단위만큼 바뀌고, 달·해를 옮겨 그 날이
 * 없으면 그 달 마지막 날로(1월 31일 + 1달 = 2월 28일), [min]·[max]를 넘으면 거기에 맞춘다.
 */
object DateStepper {
    /** 프로토타입 기본 가장 이른 날. */
    val DEFAULT_MIN: LocalDate = LocalDate.of(1950, 1, 1)

    fun step(value: LocalDate, unit: DateUnit, delta: Int, min: LocalDate?, max: LocalDate?): LocalDate {
        val moved = when (unit) {
            DateUnit.Year -> value.plusYears(delta.toLong())
            DateUnit.Month -> value.plusMonths(delta.toLong())
            DateUnit.Day -> value.plusDays(delta.toLong())
        }
        return clamp(moved, min, max)
    }

    fun clamp(value: LocalDate, min: LocalDate?, max: LocalDate?): LocalDate = when {
        max != null && value.isAfter(max) -> max
        min != null && value.isBefore(min) -> min
        else -> value
    }
}

/**
 * 날짜 고르기 (components.js `datePicker()`, voice.css `.ui-dp`, 설계 2.4 '날짜 고르기'): 연·월·일 세 칸(폭 1.25 : 1 : 1, 사이 24),
 * 칸마다 위아래 버튼(64×64)과 숫자(40/52 아주 굵게) + 단위. 키보드 없이 고른다(LC-2 연습면허 발급일).
 * 이미 [max]면 올리기, [min]이면 내리기가 테두리만 보이고, 누르면 [onLimit](참 = 위쪽 끝)으로 이유 토스트를 띄운다
 * (프로토타입: '오늘보다 뒤 날짜는 고를 수 없어요').
 */
@Composable
fun BaroDatePicker(
    value: LocalDate,
    onValueChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    min: LocalDate = DateStepper.DEFAULT_MIN,
    max: LocalDate? = null,
    onLimit: (atMax: Boolean) -> Unit = {},
) {
    val current = DateStepper.clamp(value, min, max)
    val atMax = max != null && !current.isBefore(max)
    val atMin = !current.isAfter(min)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
        DateColumn(
            number = current.year,
            unitLabel = stringResource(R.string.date_unit_year),
            name = stringResource(R.string.date_name_year),
            atMax = atMax,
            atMin = atMin,
            modifier = Modifier.weight(1.25f),
            onStep = { delta -> step(current, DateUnit.Year, delta, min, max, onValueChange, onLimit) },
        )
        DateColumn(
            number = current.monthValue,
            unitLabel = stringResource(R.string.date_unit_month),
            name = stringResource(R.string.date_name_month),
            atMax = atMax,
            atMin = atMin,
            modifier = Modifier.weight(1f),
            onStep = { delta -> step(current, DateUnit.Month, delta, min, max, onValueChange, onLimit) },
        )
        DateColumn(
            number = current.dayOfMonth,
            unitLabel = stringResource(R.string.date_unit_day),
            name = stringResource(R.string.date_name_day),
            atMax = atMax,
            atMin = atMin,
            modifier = Modifier.weight(1f),
            onStep = { delta -> step(current, DateUnit.Day, delta, min, max, onValueChange, onLimit) },
        )
    }
}

private fun step(
    current: LocalDate,
    unit: DateUnit,
    delta: Int,
    min: LocalDate,
    max: LocalDate?,
    onValueChange: (LocalDate) -> Unit,
    onLimit: (Boolean) -> Unit,
) {
    val next = DateStepper.step(current, unit, delta, min, max)
    if (next == current) onLimit(delta > 0) else onValueChange(next)
}

@Composable
private fun DateColumn(
    number: Int,
    unitLabel: String,
    name: String,
    atMax: Boolean,
    atMin: Boolean,
    modifier: Modifier,
    onStep: (Int) -> Unit,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    Column(
        modifier = modifier
            .background(colors.bgCard, shape)
            .border(2.dp, colors.cardLine, shape)
            .padding(horizontal = Dimens.space1, vertical = Dimens.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BaroIconButton(
            icon = R.drawable.ic_up,
            contentDescription = stringResource(R.string.date_up, name),
            onClick = { onStep(1) },
            size = BaroIconButtonSize.S,
            enabled = !atMax,
            onDisabledClick = { onStep(1) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                number.toString(),
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            )
            Text(
                unitLabel,
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.t3,
            )
        }
        BaroIconButton(
            icon = R.drawable.ic_up,
            contentDescription = stringResource(R.string.date_down, name),
            onClick = { onStep(-1) },
            modifier = Modifier.rotate(180f),
            size = BaroIconButtonSize.S,
            enabled = !atMin,
            onDisabledClick = { onStep(-1) },
        )
    }
}

@BaroPreviews
@Composable
private fun BaroDatePickerPreview() {
    BaroTheme {
        BaroDatePicker(
            value = LocalDate.of(2026, 4, 1),
            onValueChange = {},
            max = LocalDate.of(2026, 11, 11),
            modifier = Modifier.padding(Dimens.edge),
        )
    }
}
