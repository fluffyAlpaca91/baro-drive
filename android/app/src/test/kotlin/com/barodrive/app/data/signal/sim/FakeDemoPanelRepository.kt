package com.barodrive.app.data.signal.sim

import com.barodrive.app.demo.DemoPanelRepository
import com.barodrive.app.demo.PanelSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 메모리 안 시연 패널 설정(시험용). */
class FakeDemoPanelRepository(initial: PanelSettings = PanelSettings()) : DemoPanelRepository {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<PanelSettings> = flow

    override suspend fun update(transform: (PanelSettings) -> PanelSettings): PanelSettings {
        val next = transform(flow.value)
        flow.value = next
        return next
    }

    override suspend fun reset(): PanelSettings {
        flow.value = PanelSettings()
        return flow.value
    }
}
