package com.barodrive.core.sim.vehicle

/**
 * 차종 (프로토타입 sim/vehicle.js `BaroSim.models`, 설계 7.1·결정-58). 길이·폭·축거 m, 최대 앞바퀴각 °.
 * 가상 주차 모델(설계 7.9)과 운전자 모델의 핸들 각(atan(축거 / 반지름))이 쓴다.
 */
data class CarModel(
    val id: String,
    val name: String,
    val length: Double,
    val width: Double,
    val wheelbase: Double,
    val maxSteer: Double,
) {
    companion object {
        val AVANTE = CarModel("avante", "아반떼", 4.71, 1.825, 2.72, 34.0)
        val GRANDEUR = CarModel("grandeur", "그랜저", 5.035, 1.88, 2.895, 33.0)
        val TUCSON = CarModel("tucson", "투산", 4.63, 1.865, 2.755, 34.0)

        /** id → 차종(데이터 순서: 아반떼 · 그랜저 · 투산). */
        val ALL: Map<String, CarModel> = linkedMapOf(AVANTE.id to AVANTE, GRANDEUR.id to GRANDEUR, TUCSON.id to TUCSON)

        /** id로(모르면 null). */
        fun of(id: String?): CarModel? = id?.let { ALL[it] }
    }
}
