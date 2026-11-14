package com.barodrive.app.testing

import com.barodrive.app.data.navi.NaviController
import com.barodrive.app.data.navi.NaviDestination
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.NaviPlace
import com.barodrive.core.model.PlaceList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 차 내비 흉내([NaviController]). [connected]가 거짓이면 내비에 붙지 않은 것처럼 안내 부탁은 거짓, 장소 목록은 null.
 * 부탁한 목적지는 [requested]에 남는다.
 */
class FakeNaviController(
    var connected: Boolean = true,
    var bookmarks: List<NaviPlace> = emptyList(),
    var recent: List<NaviPlace> = emptyList(),
) : NaviController {
    val requested = mutableListOf<NaviDestination>()
    val asked = mutableListOf<PlaceList>()

    override val navState: StateFlow<NavSnapshot?> = MutableStateFlow(null)
    override val events: Flow<NavEvent> = emptyFlow()

    override fun start() = Unit

    override fun stop() = Unit

    override fun requestRoute(destination: NaviDestination): Boolean {
        if (!connected) return false
        requested += destination
        return true
    }

    override suspend fun places(list: PlaceList, timeoutMs: Long): List<NaviPlace>? {
        asked += list
        if (!connected) return null
        return if (list == PlaceList.RECENT) recent else bookmarks
    }
}
