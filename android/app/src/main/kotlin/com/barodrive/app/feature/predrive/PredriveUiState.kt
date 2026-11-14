package com.barodrive.app.feature.predrive

import com.barodrive.core.model.Coach
import com.barodrive.core.model.PassengerAnswer
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.TodayTask

/** PR-1 하단 주요 버튼 (predrive.js `primaryModel`). */
sealed interface PredrivePrimary {
    /** 변형 버튼(주차 [주차 안내 시작]). */
    data class Variant(val primary: VariantPrimary) : PredrivePrimary

    /** 보낸 뒤 '기어를 D로 바꾸면 시작해요'(막힘 모양, 누르면 [hint] 토스트). [hint]가 null이면 보낸 경로 글을 모른다. */
    data class Sent(val hint: String?, val hasRoute: Boolean) : PredrivePrimary

    /** [내비로 보내고 출발]. */
    data object Send : PredrivePrimary

    /** 코스·장소가 없으면 [내비에서 목적지 고르기]. */
    data object Pick : PredrivePrimary
}

/** PR-1 연습 시작 전 화면 값 (predrive.js가 그리는 카드들). */
data class PredriveUiState(
    val loaded: Boolean = false,
    /** 준비 세션이 없음 → CR-1(지금 단계)로 돌려보낸다. */
    val noPrep: Boolean = false,
    val stage: Int = 1,
    val prep: PrepSession? = null,
    val mode: Int = 1,
    val subtitle: String = "",
    val coach: Coach = Coach.DAD,
    /** 지금 프로필(변형 맨 위 카드 — 자율 설정 이름 등). */
    val profile: Profile? = null,
    /** 변형의 맨 위 카드가 있나. */
    val variantTop: Boolean = false,
    val showTasks: Boolean = true,
    val tasks: List<TodayTask> = emptyList(),
    val belt: BeltStatus = BeltStatus.UNKNOWN,
    val handbrake: HandbrakeStatus = HandbrakeStatus.ASK,
    val mirror: Boolean = false,
    val showPassenger: Boolean = true,
    /** 변형이 고정한 조수석 카드. */
    val fixedPassenger: FixedPassenger? = null,
    /** 고정 카드의 동승자 역할 문장(L1). */
    val fixedQuote: String? = null,
    val passenger: PassengerStatus = PassengerStatus.UNKNOWN,
    /** 동승자 역할 문장(L1, 탑승 · '바로'일 때). */
    val l1Quote: String? = null,
    /** '3·4단계는 가족이 옆에 타면 더 좋아요'. */
    val l2: Boolean = false,
    /** 착석 신호를 못 읽을 때 '조수석에 누가 탔나요?'(demo.js passenger-ask). */
    val passengerAsk: Boolean = false,
    val passengerAnswer: PassengerAnswer? = null,
    /** L1이 '바로'(답 줄 글이 달라짐). */
    val l1Now: Boolean = true,
    /** 날씨 줄 자리(feature/weather `WeatherRules.orderFor`: 맨 위 −1 · 보통 40). 날씨 줄이 없는 모드(6)는 null. */
    val weatherOrder: Int? = null,
    val showCourse: Boolean = true,
    val course: PredriveCourse? = null,
    val courseNote: String? = null,
    val signals: SignalLine = SignalLine(true, emptyList(), "", ""),
    val block: StartBlock? = null,
    val primary: PredrivePrimary = PredrivePrimary.Pick,
)
