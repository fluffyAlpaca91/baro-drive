package com.barodrive.app.testing

import com.barodrive.app.session.NavVoice
import com.barodrive.app.session.RouteSummary
import com.barodrive.app.session.ScenarioState
import com.barodrive.app.session.SimLink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** 시나리오 재생을 손으로 바꾸는 시뮬레이터 연결. [sent]에 보낸 코스·장소가 남는다. */
class FakeSimLink(var summary: RouteSummary? = RouteSummary(name = "경로", waypoints = 0)) : SimLink {
    override val scenario = MutableStateFlow(ScenarioState.IDLE)
    override val navVoice = MutableSharedFlow<NavVoice>(extraBufferCapacity = 8)
    val sent = mutableListOf<Pair<String?, String?>>()
    var pauses = 0
    var resumes = 0

    override fun send(courseId: String?, placeId: String?): RouteSummary? {
        sent += courseId to placeId
        if (summary != null) scenario.value = ScenarioState.LOADED
        return summary
    }

    override fun pause() {
        pauses++
        if (scenario.value == ScenarioState.PLAYING) scenario.value = ScenarioState.PAUSED
    }

    override fun resume() {
        resumes++
        if (scenario.value == ScenarioState.PAUSED) scenario.value = ScenarioState.PLAYING
    }
}
