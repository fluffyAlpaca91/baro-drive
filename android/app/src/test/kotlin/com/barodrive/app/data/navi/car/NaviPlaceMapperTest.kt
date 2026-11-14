package com.barodrive.app.data.navi.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 내비 장소 콜백 값 → NaviPlace: 비워 둔 칸(좌표 0,0 — 에뮬레이터 내비의 정하지 않은 집·회사)은 빼고, 빈 이름은 주소 · 종류 이름으로. */
class NaviPlaceMapperTest {
    @Test
    fun unsetSlotsAreDropped() {
        assertNull(NaviPlaceMapper.place("", 0.0, 0.0, "", "", null, "", "집"))
        assertNull(NaviPlaceMapper.place("이름", Double.NaN, 127.0, "", "", null, "", "집"))
    }

    @Test
    fun namesFallBackToTheAddressThenTheKind() {
        val named = NaviPlaceMapper.place(" 마트 ", 37.4, 127.1, "강변로 1", "p-1", null, "42dot", "즐겨찾기")
        assertEquals("마트", named?.name)
        assertEquals("p-1", named?.poiId)
        assertEquals("", named?.poiSubId)
        assertEquals("42dot", named?.poiProvider)
        assertEquals("강변로 1", NaviPlaceMapper.place("", 37.4, 127.1, "강변로 1", null, null, null, "회사")?.name)
        assertEquals("회사", NaviPlaceMapper.place(null, 37.4, 127.1, null, null, null, null, NaviPlaceMapper.bookmarkLabel("OFFICE"))?.name)
        assertEquals("집", NaviPlaceMapper.bookmarkLabel("HOME"))
        assertEquals("즐겨찾기", NaviPlaceMapper.bookmarkLabel("DEFAULT"))
    }
}
