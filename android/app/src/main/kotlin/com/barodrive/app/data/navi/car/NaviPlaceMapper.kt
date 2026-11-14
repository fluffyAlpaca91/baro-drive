package com.barodrive.app.data.navi.car

import com.barodrive.core.model.NaviPlace

/**
 * 내비 장소 콜백 값(BookmarkInfo · RecentDestinationInfo의 칸) → [NaviPlace]. Pleos 타입을 받지 않아 단위 테스트로 본다.
 * 내비가 비워 둔 칸은 뺀다: 에뮬레이터 내비는 집·회사를 아직 정하지 않은 즐겨찾기를 이름·주소 없이 좌표 0,0으로 준다(에뮬레이터에서 확인) —
 * 안내를 부탁할 수 없는 곳이다. 이름이 비면 주소, 그것도 비면 [fallbackName](즐겨찾기 종류 '집'·'회사' 등).
 */
internal object NaviPlaceMapper {
    fun place(
        name: String?,
        lat: Double,
        lng: Double,
        address: String?,
        poiId: String?,
        poiSubId: String?,
        poiProvider: String?,
        fallbackName: String,
    ): NaviPlace? {
        if (!lat.isFinite() || !lng.isFinite() || (lat == 0.0 && lng == 0.0)) return null
        val addr = address.orEmpty().trim()
        return NaviPlace(
            name = name.orEmpty().trim().ifEmpty { addr }.ifEmpty { fallbackName },
            lat = lat,
            lng = lng,
            address = addr,
            poiId = poiId.orEmpty(),
            poiSubId = poiSubId.orEmpty(),
            poiProvider = poiProvider.orEmpty(),
        )
    }

    /** 즐겨찾기 종류 이름(`BookmarkType` 이름 → 글). */
    fun bookmarkLabel(type: String?): String = when (type) {
        "HOME" -> "집"
        "OFFICE" -> "회사"
        "EV_CHARGING_STATION" -> "충전소"
        else -> "즐겨찾기"
    }

    const val RECENT_LABEL = "최근 목적지"
}
