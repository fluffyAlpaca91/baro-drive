package com.barodrive.app.data.navi

import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.core.model.NaviPlace
import com.barodrive.core.model.PlaceList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * CR-2 '내비에서 불러오기'가 보여 줄 장소의 출처 (설계 4.2 CR-2·8.2). 시뮬레이터 자료 출처면 프로토타입처럼 팀 장소(가상, 좌표 없음)를,
 * 진짜 차면 차 내비(NaviHelper)의 즐겨찾기·최근 목적지를 쓴다.
 */
interface NaviPlaceSource {
    suspend fun load(list: PlaceList): NaviPlaces

    /** 늘 시뮬레이터(팀 장소) — 테스트·미리보기 기본값. */
    object Simulator : NaviPlaceSource {
        override suspend fun load(list: PlaceList): NaviPlaces = NaviPlaces.Simulator
    }
}

/** [NaviPlaceSource.load] 결과. */
sealed interface NaviPlaces {
    /** 시뮬레이터 자료 출처: 팀 장소(`Courses.places`)를 쓴다. */
    data object Simulator : NaviPlaces

    /** 진짜 차: 차 내비가 준 장소(좌표 있음). */
    data class Car(val places: List<NaviPlace>) : NaviPlaces

    /** 진짜 차인데 내비가 답하지 않음(내비에 붙지 않음 · 시간 초과). */
    data object Unavailable : NaviPlaces
}

/** 지금 자료 출처를 보고 고른다: 진짜 차면 [NaviController.places]. */
@Singleton
class ActiveNaviPlaceSource @Inject constructor(
    private val settings: SettingsRepository,
    private val navi: NaviController,
) : NaviPlaceSource {
    override suspend fun load(list: PlaceList): NaviPlaces {
        if (settings.dataSource.first() != DataSource.CAR) return NaviPlaces.Simulator
        return navi.places(list)?.let { NaviPlaces.Car(it) } ?: NaviPlaces.Unavailable
    }
}
