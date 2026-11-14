package com.barodrive.app.feature.home

import com.barodrive.core.model.Coach

/** HM-1 홈에 보일 값 (home.js 화면: 코치 칩 + [HomeSummary]). */
data class HomeUiState(
    /** 프로필을 읽었나(읽기 전엔 빈 화면). */
    val loaded: Boolean = false,
    val coach: Coach = Coach.DAD,
    val summary: HomeSummary = HomeSummary(),
)
