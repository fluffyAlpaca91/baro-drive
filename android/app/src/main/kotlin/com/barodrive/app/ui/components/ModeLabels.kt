package com.barodrive.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.barodrive.app.R

/**
 * 모드 이름 (프로토타입 components.js `modeName(n)` · data/copy.js `modes[n].name`, 설계 0.2 용어).
 * 1 차량 감 익히기 · 2 동네 골목 다니기 · 3 큰 도로 달리기 · 4 고속도로 타 보기 · 5 자율 모드 · 6 주차 연습 · 7 언덕 주행 ·
 * 8 운전면허 연습 모드. 1~8 밖이면 숫자 그대로.
 */
@Composable
fun modeName(mode: Int): String = when (mode) {
    1 -> stringResource(R.string.mode_name_1)
    2 -> stringResource(R.string.mode_name_2)
    3 -> stringResource(R.string.mode_name_3)
    4 -> stringResource(R.string.mode_name_4)
    5 -> stringResource(R.string.mode_name_5)
    6 -> stringResource(R.string.mode_name_6)
    7 -> stringResource(R.string.mode_name_7)
    8 -> stringResource(R.string.mode_name_8)
    else -> mode.toString()
}
