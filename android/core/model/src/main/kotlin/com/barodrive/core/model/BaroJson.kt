package com.barodrive.core.model

import kotlinx.serialization.json.Json

/**
 * 바로운전 공용 JSON 설정 (설계 8.1 `baro.app.v1` 모양, 프로토타입 출력 = 골든 픽스처를 그대로 읽고 쓰기 위한 것).
 *
 * - 모르는 키는 건너뛴다(프로토타입에만 있는 칸, 나중에 더할 칸).
 * - 기본값이 있는 칸에 null이나 모르는 enum 값이 오면 기본값으로 읽는다(`coerceInputValues`).
 * - 기본값도 늘 쓴다 — 저장한 기록의 뜻이 나중에 기본값을 바꿔도 흔들리지 않게.
 *
 * 모델 타입을 JSON으로 바꿀 때는 이것만 쓴다(앱 저장소·골든 테스트·시뮬레이터 리소스 모두).
 */
val BaroJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}
