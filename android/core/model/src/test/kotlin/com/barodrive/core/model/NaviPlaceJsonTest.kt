package com.barodrive.core.model

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 진짜 차 내비 장소 칸(navi): 비어 있으면 JSON에 쓰지 않아 프로토타입 JSON과 같고, 있으면 좌표째 오간다. */
class NaviPlaceJsonTest {
    private val place = NaviPlace(name = "우리 집", lat = 37.4401, lng = 127.1377, address = "성남시 수정구", poiId = "36khp37hh8gqcm5nqhe5")

    @Test
    fun emptyNaviIsNotWritten() {
        val mc = BaroJson.encodeToJsonElement(MyCourse.serializer(), MyCourse(id = "mc-mart", placeId = "mart")).jsonObject
        assertFalse("navi" in mc)
        val prep = BaroJson.encodeToJsonElement(PrepSession.serializer(), PrepSession(mode = 1, courseId = "c1-B")).jsonObject
        assertFalse("navi" in prep)
        val ranked = BaroJson.encodeToJsonElement(RankedCourse.serializer(), RankedCourse(id = "c1-B")).jsonObject
        assertFalse("navi" in ranked)
    }

    @Test
    fun naviRoundTrips() {
        val mc = MyCourse(id = "mc-navi", name = "우리 집", mode = 2, source = CourseSource.BOOKMARK, town = "home", navi = place)
        val back = BaroJson.decodeFromString(MyCourse.serializer(), BaroJson.encodeToString(MyCourse.serializer(), mc))
        assertEquals(mc, back)
        val prep = BaroJson.decodeFromString(PrepSession.serializer(), BaroJson.encodeToString(PrepSession.serializer(), PrepSession(mode = 2, navi = place)))
        assertEquals(place, prep.navi)
        assertNull("옛 JSON(navi 없음)도 열린다", BaroJson.decodeFromString(MyCourse.serializer(), """{"id":"mc-1","courseRef":"c2-A"}""").navi)
    }

    @Test
    fun keyIsThePoiIdOrTheCoordinates() {
        assertEquals("36khp37hh8gqcm5nqhe5", place.key)
        assertEquals("37.5,127.0", place.copy(poiId = "", lat = 37.5, lng = 127.0).key)
    }
}
