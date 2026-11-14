package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 화면에서 엔진으로 넣는 사건 (설계 6.1.1 `session.input(ev)`, 프로토타입 core.js `input` · hill.js UI 사건).
 * JSON은 프로토타입처럼 `{ type: 'pkNext', step }` 모양이다. 엔진은 받은 사건을 검출기에 'input' 추적기 사건으로 넘긴다.
 */
@Serializable
sealed interface SessionInput {
    /** PR-1에서 사이드브레이크를 풀었다고 누름(PB1). */
    @Serializable
    @SerialName("pbConfirmed")
    data object PbConfirmed : SessionInput

    /** "방금 어땠어?" 운전자 질문(K8, 6.5.5). */
    @Serializable
    @SerialName("driverAsk")
    data object DriverAsk : SessionInput

    /** 주차 안내 다음 단계(화면 누름). [step]을 주면 그 단계일 때만 넘긴다(parking.js). */
    @Serializable
    @SerialName("pkNext")
    data class PkNext(val step: Int? = null) : SessionInput

    /** 언덕 주행 화면 [했어요] 누름. */
    @Serializable
    @SerialName("hillPress")
    data object HillPress : SessionInput

    /** 언덕 주차 상황 고름. */
    @Serializable
    @SerialName("hillSituation")
    data class HillSituation(val value: HillSlope) : SessionInput

    /** 언덕 연습 칩 고름(오르막 출발·내리막 감속·언덕 주차, 멈춰 있을 때만 받음 — hill.js). */
    @Serializable
    @SerialName("hillPractice")
    data class PickHillPractice(val practice: HillPractice) : SessionInput

    /** 언덕 단계 칸 누름(멈춰 있을 때만 받음 — hill.js). */
    @Serializable
    @SerialName("hillStep")
    data class HillStep(val step: Int) : SessionInput

    /** 소리 끄기: 안전만 말한다(6.5.2 규칙 2). */
    @Serializable
    @SerialName("mute")
    data object Mute : SessionInput

    /** 소리 다시 켜기. */
    @Serializable
    @SerialName("unmute")
    data object Unmute : SessionInput
}

/** 언덕 주차 상황 (프로토타입 'down'·'up'·'none' — 내리막·오르막·평지 비슷). */
@Serializable
enum class HillSlope {
    @SerialName("down")
    DOWN,

    @SerialName("up")
    UP,

    @SerialName("none")
    NONE,
}

/** 언덕 연습 셋 (프로토타입 hill.js `practice`: 'up' 오르막 출발 · 'down' 내리막 감속 · 'park' 언덕 주차, 표 A-8). */
@Serializable
enum class HillPractice {
    @SerialName("up")
    UP,

    @SerialName("down")
    DOWN,

    @SerialName("park")
    PARK,
}
