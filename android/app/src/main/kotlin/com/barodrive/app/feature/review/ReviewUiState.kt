package com.barodrive.app.feature.review

/** RV-1 화면 값: 리뷰 모델(기록이 없으면 null). */
data class ReviewUiState(
    val loaded: Boolean = false,
    val model: ReviewModel? = null,
)
