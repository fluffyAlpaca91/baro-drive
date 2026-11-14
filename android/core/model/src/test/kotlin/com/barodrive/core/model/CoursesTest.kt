package com.barodrive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 코스·장소 리소스가 프로토타입 data/courses.js(설계 8.2, 목업 s11)와 같은지 본다. */
class CoursesTest {
    @Test
    fun loadsEveryCoursePlaceAndTown() {
        assertEquals(19, Courses.all.size)
        assertEquals(8, Courses.places.size)
        assertEquals(listOf(Town("home", "우리 동네", true), Town("moved", "이사 간 동네", false)), Courses.towns)
        assertEquals(Courses.all.size, Courses.all.map { it.id }.toSet().size)
        assertTrue(Courses.all.all { it.town == "home" })
        assertEquals(mapOf(1 to 3, 2 to 3, 3 to 3, 4 to 2, 6 to 2, 7 to 2, 8 to 4), Courses.all.groupingBy { it.mode }.eachCount())
    }

    @Test
    fun courseValuesMatchThePrototype() {
        assertEquals(
            Course(id = "c2-B", mode = 2, letter = "B", name = "초등학교 뒤 골목", km = 3.2, min = 11, congestion = 0, routeOption = "편안한 길",
                straightKm = 1.1, story = "경유지 2곳, 회전 6, 어린이보호구역 1"),
            Courses.course("c2-B"),
        )
        assertEquals("원활", Courses.course("c2-B")?.congestionLabel)
        assertEquals(listOf("c1-C", "c2-A"), Courses.all.filter { it.own }.map { it.id })
        assertEquals("북IC → 동IC 한 구간", Courses.course("c4-A")?.name)
        assertEquals("고속도로 우선", Courses.course("c4-A")?.routeOption)
        val c8 = Courses.testCourses
        assertEquals(listOf("A", "B", "C", "D"), c8.map { it.letter })
        assertEquals(listOf(5.1 to 16, 4.8 to 17, 5.3 to 15, 4.9 to 18), c8.map { it.km to it.min })
        assertTrue(c8.all { it.segments == 3 && it.checkedAt == "2026-11-08" && it.noHighway })
        assertNull(Courses.course("nope"))
        assertNull(Courses.course(null))
    }

    @Test
    fun placeValuesMatchThePrototype() {
        assertEquals(
            Place(id = "mart", name = "마트", list = PlaceList.BOOKMARK, km = 12.4, min = 31, congestion = 1, addr = "강변로 대형마트", straightKm = 8.9),
            Courses.place("mart"),
        )
        assertEquals("집", Courses.here?.name)
        assertEquals(true, Courses.place("daycare")?.hill)
        assertEquals(listOf("gym", "clinic", "cafe"), Courses.places.filter { it.list == PlaceList.RECENT }.map { it.id })
        assertEquals("역 앞 카페", Courses.place("cafe")?.name)
        assertEquals(2, Courses.place("cafe")?.congestion)
    }
}
