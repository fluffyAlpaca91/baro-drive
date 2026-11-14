package com.barodrive.core.engine

import com.barodrive.core.engine.content.LineBook
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.Detectors
import com.barodrive.core.model.SessionConfig

/**
 * 코칭 엔진 입구 (설계 6.1.1, 프로토타입 `BaroEngine.createSession(cfg, { detectors })`).
 * 순수 Kotlin이다: 안드로이드, 벽시계, 시드 없는 난수를 쓰지 않는다. 시간은 스냅숏의 `t`뿐이다.
 * 앱은 파사드 `BaroEngine.createSession(cfg)`로 부른다.
 */
class CoachEngine {
    /**
     * 회차 하나를 위한 새 세션. 검출기는 상태를 들고 있으므로 세션마다 새로 만든다.
     * [detectors]를 주면 등록된 검출기([Detectors.all]) 대신 그것만 쓴다(테스트용), [lines]는 대사 표(시험 대사를 더할 때).
     */
    fun createSession(
        config: SessionConfig,
        detectors: List<DetectorDef> = Detectors.all,
        lines: LineBook = Lines.book,
    ): CoachSession = CoachSession(config, detectors, lines)
}
