package com.barodrive.app.data.repo

import com.barodrive.core.model.AppState
import com.barodrive.core.model.Profile
import kotlinx.coroutines.flow.Flow

/**
 * 프로필·진행·설정 저장소 (프로토타입 app/store.js `profile`·`update`·`setProfile`·`reset`·`replace`·`defaults`, 설계 8.1 `baro.app.v1`).
 *
 * 패키지: 화면이 쓰는 앱 계약은 `data.repo`(저장소 인터페이스 + 메모리 구현 [InMemoryBaroStore])와 `session.api`에만 둔다.
 * 기기 저장 구현(DataStore JSON + Room)은 `data.store`에 있고 같은 규칙([StoreRules])을 쓴다.
 *
 * [profile]·[state]의 `sessions`는 [RecordRepository]의 회차 기록으로 채워 준다(최신이 앞, 최대 30) — 그래서 엔진 파사드
 * (`buildConfig`·`review.todayTasks`·`applyProgress` …)에 그대로 넘겨도 된다. 반대로 [update]가 돌려준 프로필의
 * `sessions`는 무시한다: 기록은 [RecordRepository]로만 바꾼다.
 * 자주 쓰는 고치기는 [setCoach]·[setConsent] 같은 확장 함수(ProfileOps.kt)로 있다.
 */
interface ProfileRepository {
    /** 앱 상태 전체(store.js `get()`). 모든 프로필의 `sessions`를 채운다. */
    val state: Flow<AppState>

    /** 지금 프로필(store.js `profile()`). */
    val profile: Flow<Profile>

    /** 지금 프로필 한 번 읽기. */
    suspend fun current(): Profile

    /** 지금 프로필을 고쳐 저장하고 고친 값을 돌려준다(store.js `update(fn)`). [transform]은 짧고 순수하게. */
    suspend fun update(transform: (Profile) -> Profile): Profile

    /**
     * 앱 상태 전체를 고친다(ui 기억, 여러 프로필 — store.js `update((state) => …)`). [update]와 달리 프로필의 `sessions`도
     * 그대로 반영한다(ST 프로필 처음으로·탈퇴처럼 기록까지 바꾸는 일). 지금 프로필 기록만 바꿀 때는 [RecordRepository]를 쓴다.
     */
    suspend fun updateState(transform: (AppState) -> AppState): AppState

    /** 프로필 바꾸기(store.js `setProfile`). 없으면 '운전자 N' 처음 상태로 만든다. */
    suspend fun setActiveProfile(id: String): Profile

    /** 처음 상태로(store.js `reset`): 기록까지 모두 지우고 `defaults()`. */
    suspend fun reset(): AppState

    /**
     * 상태 전체 바꾸기(store.js `replace` — 데모 상태 적용). 프로필의 `sessions`는 기록 저장소로 옮긴다.
     * v1 모양이 아니면(version ≠ 1) [IllegalArgumentException].
     */
    suspend fun replace(state: AppState): AppState
}
