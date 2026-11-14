package com.barodrive.core.model

import kotlinx.serialization.Serializable

/**
 * 기어 (설계 6.1.2 `gear`, AAOS `GEAR_SELECTION`). JSON 값은 프로토타입과 같은 'P'·'R'·'N'·'D'.
 * 차가 주는 1~9단(수동·스포츠 모드)은 차 쪽 출처가 D로 접어서 넘긴다.
 */
@Serializable
enum class Gear { P, R, N, D }
