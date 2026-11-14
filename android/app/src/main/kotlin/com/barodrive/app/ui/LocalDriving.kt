package com.barodrive.app.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * 지금 주행 중 화면 문이 닫혀 있는지([DrivingGate.driving], 설계 2.5·3.1). [BaroRoot]가 깐다.
 * 시트·확인 대화상자는 이 값이 참이 되는 순간 스스로 닫힌다(설계 2.5 '기어가 P를 벗어나는 순간 시트·대화상자를 닫는다').
 * 미리보기·시험에서는 거짓(정차)이다.
 */
val LocalDriving = compositionLocalOf { false }
