package com.barodrive.app.data.navi

import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.NaviPlace
import com.barodrive.core.model.PlaceList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 내비 목적지 (설계 8.2 내비 장소, Pleos `RouteInfo`). [poiId]·[poiSubId]·[poiProvider]는 내비가 준 장소면 그대로 돌려준다 —
 * 좌표만으로 안내가 되는지는 문서에 없다(API-레퍼런스 12장 7번).
 */
data class NaviDestination(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String = "",
    val poiId: String = "",
    val poiSubId: String = "",
    val poiProvider: String = "",
) {
    companion object {
        /** 내비에서 가져온 장소 그대로. */
        fun of(place: NaviPlace) = NaviDestination(
            name = place.name, latitude = place.lat, longitude = place.lng, address = place.address,
            poiId = place.poiId, poiSubId = place.poiSubId, poiProvider = place.poiProvider,
        )
    }
}

/**
 * 차의 기본 내비 (프로토타입 sim/navi.js가 흉내 내는 것, 설계 6.1.2 `nav.*`·7.5). 진짜 차 구현은 Pleos NaviHelper(`data/navi/car/`).
 * 내비 앱에 붙어 있는 동안만 값이 온다: [start]로 붙고 [stop]으로 떨어진다(진짜 차 자료 출처를 모으는 동안).
 */
interface NaviController {
    /** 지금 내비 값. 내비에 붙지 않았으면 null. 한 번씩 오는 사건은 여기 싣지 않는다 — [events]를 본다. */
    val navState: StateFlow<NavSnapshot?>

    /** 재탐색·도착 같은 한 번씩 오는 사건. 스냅숏을 만드는 쪽이 다음 스냅숏 하나에 싣는다. */
    val events: Flow<NavEvent>

    fun start()

    fun stop()

    /**
     * 목적지 안내를 부탁한다(`requestRoute`). 내비에 붙지 않았거나 부탁이 실패하면 거짓. 참이어도 안내가 시작된 것은 아니다 —
     * 시작되면 [navState]의 status가 ROUTE_DRIVE가 된다(문서: `onRouteStarted` → `onRoutingCompleted`).
     */
    fun requestRoute(destination: NaviDestination): Boolean

    /**
     * 내비의 즐겨찾기([PlaceList.BOOKMARK], `getBookmarkInfo`) · 최근 목적지([PlaceList.RECENT], `getRecentDestinationInfo`).
     * 답은 콜백(`onBookmarkInfo` · `onRecentDestinationInfo`)으로 오므로 [timeoutMs]까지 기다린다. 내비에 붙지 않았거나 답이 없으면 null.
     */
    suspend fun places(list: PlaceList, timeoutMs: Long = PLACES_TIMEOUT_MS): List<NaviPlace>?

    companion object {
        /** 장소 목록 답을 기다리는 시간. */
        const val PLACES_TIMEOUT_MS: Long = 3_000L
    }
}
