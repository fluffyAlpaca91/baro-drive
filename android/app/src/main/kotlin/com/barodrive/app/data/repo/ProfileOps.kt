package com.barodrive.app.data.repo

import com.barodrive.core.model.Coach
import com.barodrive.core.model.Consent
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.FeedbackPreset
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.LicenseInfo
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.Onboarding
import com.barodrive.core.model.Profile
import com.barodrive.core.model.StageProgress
import com.barodrive.core.model.UiMemory
import kotlinx.serialization.json.JsonObject

// 자주 쓰는 프로필 고치기 (프로토타입 화면들의 `store.update((s, p) => { p.x = … })`). 모두 [ProfileRepository.update] 한 번이다 —
// 어느 구현(기기 저장·메모리)에서도 같게 돈다. 값의 규칙(기본값과 같으면 지우기, 최대 개수, 이름 규칙 …)은 화면 몫이다.

/** ON-1~5 온보딩 결과(onboarding.js `apply`의 `p.onboarding`). */
suspend fun ProfileRepository.updateOnboarding(transform: (Onboarding) -> Onboarding): Profile =
    update { it.copy(onboarding = transform(it.onboarding)) }

/** 동의(ST-4·온보딩·공유 — `p.consent`). */
suspend fun ProfileRepository.updateConsent(transform: (Consent) -> Consent): Profile =
    update { it.copy(consent = transform(it.consent)) }

/** 코치(CO-1·ST-1 — `p.coach`). */
suspend fun ProfileRepository.setCoach(coach: Coach): Profile = update { it.copy(coach = coach) }

/** 호칭(CO-1 — `p.honorific`). */
suspend fun ProfileRepository.setHonorific(honorific: Honorific): Profile = update { it.copy(honorific = honorific) }

/** 연습면허 정보(LC-2 발급일·완주 코스 — `p.license`). */
suspend fun ProfileRepository.updateLicense(transform: (LicenseInfo) -> LicenseInfo): Profile =
    update { it.copy(license = transform(it.license)) }

/** 단계 진행(온보딩 추천 단계·ST 처음부터 — `p.progress`). 회차 끝 진행 반영은 세션 관리자가 엔진으로 한다. */
suspend fun ProfileRepository.updateProgress(transform: (StageProgress) -> StageProgress): Profile =
    update { it.copy(progress = transform(it.progress)) }

/** 모드별 끝낸 회차 수(`p.sessionCount`). */
suspend fun ProfileRepository.updateSessionCount(transform: (Map<Int, Int>) -> Map<Int, Int>): Profile =
    update { it.copy(sessionCount = transform(it.sessionCount)) }

/** 그 모드 도움 줄여 가기 상태(`p.fading[mode]`). 빈 맵이면 그 모드 칸을 지운다. */
suspend fun ProfileRepository.setFading(mode: Int, items: Map<String, FadeState>): Profile = update {
    it.copy(fading = if (items.isEmpty()) it.fading - mode else it.fading + (mode to items))
}

/** FB-1 사용자 변경 하나(`p.feedback.overrides[mode][item]`). [value]가 null이면 지운다. 모드 칸이 비면 그 칸도 지운다. */
suspend fun ProfileRepository.setOverride(mode: Int, item: String, value: Delivery?): Profile = update { p ->
    val mine = p.feedback.overrides[mode].orEmpty().let { if (value == null) it - item else it + (item to value) }
    val all = if (mine.isEmpty()) p.feedback.overrides - mode else p.feedback.overrides + (mode to mine)
    p.copy(feedback = p.feedback.copy(overrides = all))
}

/** FB-1 [기본값으로](feedback.js `resetMode`): 그 모드 사용자 변경을 모두 지운다. */
suspend fun ProfileRepository.resetOverrides(mode: Int): Profile = update { p ->
    p.copy(feedback = p.feedback.copy(overrides = p.feedback.overrides - mode))
}

/** FB-2 내 음성 피드백 설정 목록(`p.myPresets`) — 저장·이름 바꾸기·지우기. */
suspend fun ProfileRepository.updatePresets(transform: (List<FeedbackPreset>) -> List<FeedbackPreset>): Profile =
    update { it.copy(myPresets = transform(it.myPresets)) }

/** 마지막으로 고른 내 설정(`p.lastPresetId`, feedback.js `select`). 목록에 없는 id면 null로. */
suspend fun ProfileRepository.selectPreset(id: String?): Profile = update { p ->
    p.copy(lastPresetId = id?.takeIf { want -> p.myPresets.any { it.id == want } })
}

/** 내 코스(`p.myCourses` — CR-1 별·CR-2 가져오기·ST-3·리뷰 '내 코스로 저장'). */
suspend fun ProfileRepository.updateMyCourses(transform: (List<MyCourse>) -> List<MyCourse>): Profile =
    update { it.copy(myCourses = transform(it.myCourses)) }

/** 세워 두고 연습 성공(`p.parking.done['parallel-R']`). */
suspend fun ProfileRepository.setParkingDone(key: String, done: Boolean = true): Profile = update { p ->
    p.copy(parking = p.parking.copy(done = if (done) p.parking.done + (key to true) else p.parking.done - key))
}

/** 보내지 못한 요약(`p.shareQueue`, share.js). */
suspend fun ProfileRepository.updateShareQueue(transform: (List<String>) -> List<String>): Profile =
    update { it.copy(shareQueue = transform(it.shareQueue)) }

/** 마지막 정차 화면 기억(router.js — 새로고침 복원, `state.ui`). */
suspend fun ProfileRepository.rememberScreen(id: String?, params: JsonObject = JsonObject(emptyMap())) {
    updateState { it.copy(ui = UiMemory(lastScreen = id, lastParams = params)) }
}
