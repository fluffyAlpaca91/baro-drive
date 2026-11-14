package com.barodrive.app.feature.drive

import com.barodrive.core.model.Card
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Gear
import com.barodrive.core.model.WeatherBadge

/** 주행 덮개 화면(DR-0·DR-1·DR-2)에 보일 값 (drive.js 세 화면의 update가 그리는 것). */
data class DriveUiState(
    val screen: DriveCoverScreen = DriveNotPracticing,
    val gear: Gear? = null,
    val gearLabel: DriveGearLabel = DriveGearLabel.STOPPED,
    /** 머리줄 글('1단계 · 2회차'). DR-0은 ''. */
    val sub: String = "",
    /** 소리 끔(DR-1·DR-2 버튼). */
    val muted: Boolean = false,
    /** 날씨 배지(엔진 hints.badge). */
    val badge: WeatherBadge? = null,
    val hero: DriveHero = DriveHero.Task("", ""),
    /** 신호 대기 카드(DR-2, 최대 2장). */
    val cards: List<Card> = emptyList(),
    /** 아래 코치 말풍선(마지막 코치 문장 8 sim초). DR-2는 카드가 있으면 없음. */
    val say: String? = null,
    val coach: Coach = Coach.DAD,
    /** 회전 히어로를 목업 s04 모양으로(시연 패널 '보기' [목업 원안 보기], 결정-92). 기본은 깜빡이 중심. */
    val mockupHero: Boolean = false,
)

/** DR-3 '여기서 마칠까요?'에 보일 값. */
data class StopHereUiState(
    /** 회차가 진행 중(아니면 버튼 대신 [홈으로]). */
    val active: Boolean = false,
    val sub: String = "",
    val remain: String = "",
    /** [이어서 달리기]를 누름 — 기어를 D로 바꾸길 기다린다. */
    val waiting: Boolean = false,
    /** [여기서 마치기]를 누르고 저장을 기다리는 중. */
    val ending: Boolean = false,
)
