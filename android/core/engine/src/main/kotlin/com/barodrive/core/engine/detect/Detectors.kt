package com.barodrive.core.engine.detect

import com.barodrive.core.engine.detect.basic.A1
import com.barodrive.core.engine.detect.basic.B1
import com.barodrive.core.engine.detect.basic.C1
import com.barodrive.core.engine.detect.basic.C2
import com.barodrive.core.engine.detect.basic.C4
import com.barodrive.core.engine.detect.basic.C5
import com.barodrive.core.engine.detect.basic.E1
import com.barodrive.core.engine.detect.basic.E2
import com.barodrive.core.engine.detect.basic.F1
import com.barodrive.core.engine.detect.basic.F2
import com.barodrive.core.engine.detect.basic.G1
import com.barodrive.core.engine.detect.basic.PB1
import com.barodrive.core.engine.detect.basic.PB2
import com.barodrive.core.engine.detect.hill.HL1
import com.barodrive.core.engine.detect.hill.HL2
import com.barodrive.core.engine.detect.hill.HL3
import com.barodrive.core.engine.detect.license.LICENSE_ALONE
import com.barodrive.core.engine.detect.license.LICENSE_COURSE
import com.barodrive.core.engine.detect.misc.E3
import com.barodrive.core.engine.detect.misc.K8
import com.barodrive.core.engine.detect.parking.PK1
import com.barodrive.core.engine.detect.parking.PK2
import com.barodrive.core.engine.detect.parking.PK3
import com.barodrive.core.engine.detect.safety3.C3
import com.barodrive.core.engine.detect.safety3.C6
import com.barodrive.core.engine.detect.safety3.J1
import com.barodrive.core.engine.detect.weather.J3

/**
 * 등록된 검출기, 프로토타입이 불러오는 순서 그대로 (index.html: basic.js → safety3.js → parking.js → hill.js → weather.js →
 * license.js → fallback.js → k8.js). 같은 사건은 이 순서로 검출기에 나눠 주고, 검출기 step도 이 순서로 부른다 — 말 후보·판정 순서가
 * 프로토타입과 같아야 하므로 순서를 바꾸지 않는다.
 */
object Detectors {
    val all: List<DetectorDef> = listOf(
        // basic.js
        A1, PB1, B1, C1, C2, C4, C5, F1, F2, E1, E2, G1, PB2,
        // safety3.js
        C3, C6, J1,
        // parking.js · hill.js
        PK1, PK2, PK3, HL1, HL2, HL3,
        // weather.js
        J3,
        // license.js
        LICENSE_COURSE, LICENSE_ALONE,
        // fallback.js · k8.js
        E3, K8,
    )
}
