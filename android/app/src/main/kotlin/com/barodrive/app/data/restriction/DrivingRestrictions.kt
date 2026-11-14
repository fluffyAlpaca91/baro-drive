package com.barodrive.app.data.restriction

import kotlinx.coroutines.flow.StateFlow

/**
 * 플랫폼 주행 규제 (Pleos UX 3.1, API-레퍼런스 10.2). 켜지면 기어와 상관없이 주행 화면만 보여야 한다.
 * 화면 쪽은 이 인터페이스만 본다 — CarUxRestrictionsManager는 `car/` 구현에만 있다.
 */
interface DrivingRestrictions {
    /** 주행 중 주의 분산 최적화가 필요함(CarUxRestrictions.isRequiresDistractionOptimization). */
    val restricted: StateFlow<Boolean>
}
