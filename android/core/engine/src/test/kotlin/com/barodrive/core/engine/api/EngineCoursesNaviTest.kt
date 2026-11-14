package com.barodrive.core.engine.api

import com.barodrive.core.model.CourseSource
import com.barodrive.core.model.Courses
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.NaviPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 진짜 차 내비에서 가져온 내 코스(MyCourse.navi — 프로토타입에 없음)의 코스 추천: 내 코스 줄로 나오고 좌표를 들고 가지만,
 * 거리·시간·혼잡을 몰라 '덜 막혀요'·처음 선택이 되지 않는다. 내비 코스가 없으면 결과는 전과 같다(프로토타입 테스트·골든 몫).
 */
class EngineCoursesNaviTest {
    private val home = NaviPlace(name = "우리 집", lat = 37.4401, lng = 127.1377, address = "성남시 수정구", poiId = "p-home")
    private val mc = MyCourse(id = "mc-navi", name = "우리 집", mode = 2, source = CourseSource.BOOKMARK, town = "home", navi = home)

    @Test
    fun naviCourseIsAMineRowWithoutCongestion() {
        val res = BaroEngine.courses.rank(2, RankOptions(town = "home", myCourses = listOf(mc), tripOk = true))
        val row = res.list.single { it.navi != null }
        assertEquals("mc-navi", row.id)
        assertEquals("mc-navi", row.myCourseId)
        assertEquals(home, row.navi)
        assertTrue(row.mine)
        assertEquals(CourseSource.BOOKMARK, row.source)
        assertNull("팀 코스·팀 장소가 아님", row.courseId ?: row.placeId)
        assertNull("혼잡을 모른다", row.congestion)
        assertNull(row.score)
        assertFalse(row.best)
        assertNotEquals(row.id, res.bestId)
        assertNotEquals("처음 선택은 거리를 아는 코스", row.id, res.selectedId)
        assertTrue("팀 코스와 겹치지 않는 글자", row.letter.isNotEmpty() && Courses.forMode(2).none { it.letter == row.letter })
    }

    @Test
    fun naviCourseFollowsTheTownAndTheMode() {
        assertTrue(BaroEngine.courses.rank(2, RankOptions(town = "moved", myCourses = listOf(mc))).list.none { it.navi != null })
        assertTrue(BaroEngine.courses.rank(3, RankOptions(town = "home", myCourses = listOf(mc))).list.none { it.navi != null })
    }

    @Test
    fun withoutTripTheNaviCourseIsStillListedLast() {
        val res = BaroEngine.courses.rank(2, RankOptions(town = "home", myCourses = listOf(mc), tripOk = false))
        assertTrue(res.list.any { it.navi != null })
        assertNotEquals(res.list.first { it.navi != null }.id, res.selectedId)
    }
}
