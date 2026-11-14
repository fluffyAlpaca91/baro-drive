package com.barodrive.app.feature.parking

import com.barodrive.core.model.ParkingSetup
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.TurnDir
import org.junit.Assert.assertEquals
import org.junit.Test

/** PR-1 주차 변형 조각 (parking-guide.js `Baro.predriveVariant(6)`, e2e t5b-guide 'PR-1 주차 변형' 글). */
class ParkingPredriveTest {
    @Test
    fun subtitleAndStartRouteFollowThePreparedChoice() {
        val prep = PrepSession(mode = 6, noInMode = 1, parking = ParkingSetup(ParkingType.PARALLEL, TurnDir.RIGHT))
        assertEquals("주차 연습 · 평행 · 오른쪽 칸 · 1회차", ParkingPredrive.subtitle(prep, XmlTextRes))
        assertEquals(ParkingGuide("parallel", "R"), ParkingPredrive.startRoute(prep))
        val back = prep.copy(noInMode = 3, parking = ParkingSetup(ParkingType.BACK, TurnDir.LEFT))
        assertEquals("주차 연습 · 후방(후진) · 왼쪽 칸 · 3회차", ParkingPredrive.subtitle(back, XmlTextRes))
        assertEquals(ParkingGuide("back", "L"), ParkingPredrive.startRoute(back))
        assertEquals("고른 것이 없으면 평행 · 오른쪽", ParkingSetup(ParkingType.PARALLEL, TurnDir.RIGHT), ParkingPredrive.setup(PrepSession(mode = 6)))
        assertEquals("주차장에 도착하면 주차 안내를 시작해요", XmlTextRes.text(ParkingPredrive.COURSE_NOTE))
        assertEquals("주차 안내 시작", XmlTextRes.text(ParkingPredrive.START))
    }
}
