package com.barodrive.app.data.navi.car

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.util.Log
import com.barodrive.app.data.navi.NaviController
import com.barodrive.app.data.navi.NaviDestination
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.NavStatus
import com.barodrive.core.model.NaviPlace
import com.barodrive.core.model.PlaceList
import com.barodrive.core.model.TbtItem
import com.barodrive.core.model.TurnDir
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pleos NaviHelper로 차 기본 내비에 붙는다 (API-레퍼런스 4.1·4.3·4.4). 콜백을 [NavSnapshot]으로 옮긴다:
 * 제한속도(`onCurrentRoadSpeedLimit`), 안내 상태(`onNavigationStatus`), 회전(`onTBTInfo`), 도착(`onDestinationArrived`).
 * 내비 앱이 없으면 연결 실패 로그만 남기고 [navState]는 null로 둔다.
 * 목적지 안내 부탁은 `requestRoute(RouteInfo)`(문서 예제처럼 좌표·이름·주소, 내비가 준 장소면 POI id도), 즐겨찾기·최근 목적지는
 * `getBookmarkInfo`·`getRecentDestinationInfo`를 부르고 그 답 콜백(`onBookmarkInfo`·`onRecentDestinationInfo`)을 기다린다(CR-2).
 *
 * TODO(port): cautions(`onCautionPointInfo`), inSchoolZone, congested(`onRouteStateInfo.trafficStatus`),
 *   voice(AudioFocus), heading(`onCurrentLocationInfo`), 재탐색(`onReRouteEnded`), 경유지(`onWayPointArrived`).
 */
@Singleton
class CarNaviController @Inject constructor(
    private val helpers: Provider<NaviHelper>,
) : NaviController {
    private val _navState = MutableStateFlow<NavSnapshot?>(null)
    override val navState: StateFlow<NavSnapshot?> = _navState.asStateFlow()

    private val _events = MutableSharedFlow<NavEvent>(extraBufferCapacity = 16)
    override val events: Flow<NavEvent> = _events.asSharedFlow()

    /** 장소 목록 답(콜백 스레드에서 온다). 기다리는 쪽이 먼저 구독하고 부탁한다. */
    private val bookmarkReplies = MutableSharedFlow<List<NaviPlace>>(extraBufferCapacity = 1)
    private val recentReplies = MutableSharedFlow<List<NaviPlace>>(extraBufferCapacity = 1)

    /** 지금 붙어 있는 NaviHelper. release한 객체는 다시 못 쓰므로 붙을 때마다 새로 받는다. */
    private var helper: NaviHelper? = null

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = update { it.copy(limit = speedLimit) }

        override fun onNavigationStatus(status: NavigationStatus) = update { it.copy(status = statusOf(status)) }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = update { it.copy(tbt = tbtOf(tbtInfoList)) }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            _events.tryEmit(NavEvent.Arrived)
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            bookmarkReplies.tryEmit(bookmarkInfoList.mapNotNull(::placeOf))
        }

        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            recentReplies.tryEmit(recentDestinationInfoList.mapNotNull(::placeOf))
        }
    }

    @Synchronized
    override fun start() {
        if (helper != null) return
        val h = try {
            helpers.get()
        } catch (e: RuntimeException) {
            Log.w(TAG, "NaviHelper를 만들지 못했어요", e)
            return
        }
        helper = h
        try {
            h.initialize(
                onConnected = { attach(h) },
                onFailed = { e -> Log.w(TAG, "내비에 연결하지 못했어요(내비 앱 없음?)", e) },
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "NaviHelper.initialize 실패", e)
            helper = null
        }
    }

    @Synchronized
    private fun attach(h: NaviHelper) {
        if (helper !== h) return // 연결되기 전에 stop()됨
        try {
            h.addListener(listener)
            _navState.value = NavSnapshot()
            h.getTBTInfo() // 이미 안내 중이면 지금 회전을 바로 받는다(onTBTInfo)
        } catch (e: RuntimeException) {
            Log.w(TAG, "내비 리스너 등록 실패", e)
        }
    }

    @Synchronized
    override fun stop() {
        val h = helper ?: return
        helper = null
        _navState.value = null
        try {
            h.removeListener(listener)
            h.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "NaviHelper 해제 실패", e)
        }
    }

    override fun requestRoute(destination: NaviDestination): Boolean {
        val h = connected()
        if (h == null) {
            Log.w(TAG, "내비에 붙지 않아 경로를 부탁하지 못했어요")
            return false
        }
        return try {
            // TODO(Pleos 확인): 내비가 준 장소가 아니면 poiId가 비어 있다 — 좌표만으로 안내가 되는지(API-레퍼런스 12장 7번).
            h.requestRoute(
                RouteInfo(
                    latitude = destination.latitude,
                    longitude = destination.longitude,
                    poiName = destination.name,
                    poiId = destination.poiId,
                    address = destination.address,
                    poiSubId = destination.poiSubId,
                    poiProvider = destination.poiProvider,
                ),
            )
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "경로 요청 실패", e)
            false
        }
    }

    override suspend fun places(list: PlaceList, timeoutMs: Long): List<NaviPlace>? {
        val h = connected() ?: return null
        val replies = if (list == PlaceList.RECENT) recentReplies else bookmarkReplies
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                // 답을 놓치지 않게 먼저 구독하고(UNDISPATCHED — 첫 중단까지 바로 돈다) 부탁한다.
                val reply = async(start = CoroutineStart.UNDISPATCHED) { replies.first() }
                try {
                    if (list == PlaceList.RECENT) h.getRecentDestinationInfo() else h.getBookmarkInfo()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "장소 목록 요청 실패: $list", e)
                    reply.cancel()
                    return@coroutineScope null
                }
                reply.await()
            }
        }.also { if (it == null) Log.w(TAG, "내비가 장소 목록($list)에 답하지 않았어요") }
    }

    /** 리스너까지 붙은 NaviHelper. 아직 연결 중이거나 떨어졌으면 null. */
    private fun connected(): NaviHelper? = synchronized(this) { helper?.takeIf { _navState.value != null } }

    /** 붙어 있을 때만 바꾼다(떨어진 뒤 늦게 온 콜백은 버린다). */
    private fun update(change: (NavSnapshot) -> NavSnapshot) {
        _navState.update { current -> current?.let(change) }
    }

    /** 즐겨찾기 하나(비워 둔 칸이면 null — [NaviPlaceMapper]). */
    private fun placeOf(b: BookmarkInfo): NaviPlace? = NaviPlaceMapper.place(
        b.name, b.latitude, b.longitude, b.address, b.poiId, b.poiSubId, b.poiProvider, NaviPlaceMapper.bookmarkLabel(b.category.name),
    )

    /** 최근 목적지 하나(비워 둔 칸이면 null). */
    private fun placeOf(r: RecentDestinationInfo): NaviPlace? = NaviPlaceMapper.place(
        r.name, r.latitude, r.longitude, r.address, r.poiId, r.poiSubId, r.poiProvider, NaviPlaceMapper.RECENT_LABEL,
    )

    private fun statusOf(status: NavigationStatus): NavStatus =
        if (status == NavigationStatus.ROUTE_DRIVE) NavStatus.ROUTE_DRIVE else NavStatus.INACTIVE

    /** 가까운 순 2개(설계 6.1.2). 실제 TBT에는 회전 id가 없어 종류와 순번으로 만든다. */
    private fun tbtOf(list: List<TBTInfo>): List<TbtItem> =
        list.sortedBy { it.distance }.take(2).mapIndexed { index, info ->
            val type = info.type.name
            // TODO(port): 같은 회전을 이어 알아보는 id(남은 거리가 줄어드는 연속성으로 묶기). 지금은 순번이라 회전을 지나면 바뀐다.
            TbtItem(id = "$type#$index", type = type, distance = info.distance, dir = dirOf(type))
        }

    /** core.js turnInfo와 같은 규칙: 이름에 LEFT·_L이면 왼쪽, RIGHT·_R이면 오른쪽, 유턴은 왼쪽. */
    private fun dirOf(type: String): TurnDir? = when {
        "UTURN" in type -> TurnDir.LEFT
        "LEFT" in type || type.endsWith("_L") -> TurnDir.LEFT
        "RIGHT" in type || type.endsWith("_R") -> TurnDir.RIGHT
        else -> null
    }

    private companion object {
        const val TAG = "CarNavi"
    }
}
