package com.barodrive.app.feature.settings

import androidx.annotation.DrawableRes
import com.barodrive.app.R
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.Mode
import com.barodrive.core.model.Profile
import com.barodrive.core.model.StageProgress
import com.barodrive.core.model.StageState

/** ST-1 줄 묶음: 코치(묶음 제목 없음) · '기록과 개인정보' · '차와 앱'. */
enum class SettingsGroup { COACH, DATA, CAR }

/** ST-1 줄을 누르면 갈 곳 (settings.js `go`·`action`). */
sealed interface SettingsTarget {
    data object CoachPicker : SettingsTarget

    /** CO-2 호칭 시트. */
    data object Honorific : SettingsTarget

    data class Feedback(val mode: Int) : SettingsTarget

    /** FB-2 관리만(출발 버튼 없이). */
    data object Presets : SettingsTarget

    data object MyCourses : SettingsTarget

    data object History : SettingsTarget

    data object Privacy : SettingsTarget

    data object About : SettingsTarget
}

/** ST-1 줄 하나 (settings.js `rows`): [coach]가 있으면 아이콘 대신 그 코치 얼굴. [target]이 null이면 누름 없음(차에서 읽음). */
data class SettingsRow(
    val id: String,
    val group: SettingsGroup,
    @param:DrawableRes val icon: Int,
    val coach: Coach? = null,
    val title: String,
    val value: String = "",
    val sub: String? = null,
    val target: SettingsTarget? = null,
)

/** 동의 스위치 이름 (profile.consent 칸). */
enum class ConsentKey { RECORD, FAMILY, ACADEMY, INSURER }

/** ST-4 동의 줄 (settings.js `CONSENTS`). */
data class ConsentRow(val key: ConsentKey, val title: String, val sub: String, val required: Boolean, val on: Boolean)

/**
 * 설정·공유와 개인정보의 화면 없는 계산 (프로토타입 screens/settings.js `BaroApp.settings`, 설계 4.2 ST-1·ST-4·ST-5, 5.10, 8.1 consent,
 * 결정-44·45·52, 결정사항 E T9-4·T9-5).
 */
object SettingsRules {
    const val NAME_MAX = 10
    const val DEFAULT_TO = "가족"
    const val VERSION = "프로토타입 0.9 · 2026-11"
    const val LATER = "연결할 곳이 생기면 그때 따로 동의를 받아요"

    private data class ConsentDef(val key: ConsentKey, val title: String, val sub: String, val required: Boolean = false)

    private val CONSENTS = listOf(
        ConsentDef(ConsentKey.RECORD, "연습 기록 저장", "회차 요약과 장면 기록을 이 차 안에만 최근 30회차까지 둬요. 위치 좌표는 밖으로 보내지 않아요.", required = true),
        ConsentDef(ConsentKey.FAMILY, "가족에게 요약 보내기", "보낼지는 회차마다 내가 골라요. 요약 숫자만 가요."),
        ConsentDef(ConsentKey.ACADEMY, "등록 학원과 연결", LATER),
        ConsentDef(ConsentKey.INSURER, "보험사에 요약 제공", "건마다 다시 물어요. $LATER"),
    )

    /** 차종 이름(sim/vehicle.js `models`, 결정-58). */
    private val MODELS = mapOf("avante" to "아반떼", "grandeur" to "그랜저", "tucson" to "투산")

    fun modelName(id: String?): String = MODELS[id] ?: MODELS.getValue("avante")

    fun coachName(c: Coach): String = c.title

    /** '우리 딸' · 직접 쓴 말 · '부르지 않기'(coach.js `honorificLabel`). */
    fun honorificLabel(h: Honorific?): String = when (h?.type) {
        HonorificType.CUSTOM -> h.text.trim().ifEmpty { HonorificType.NONE.label }
        HonorificType.DAUGHTER, HonorificType.SON -> h.type.label
        else -> HonorificType.NONE.label
    }

    private fun stageOf(p: Profile?): Int = p?.progress?.currentStage?.coerceIn(1, 4) ?: 1

    /**
     * ST-1 줄 (settings.js `rows`): 코치 › · 부르는 말 › · 음성 피드백 설정 › · 내 음성 피드백 설정(자율) › | 내 코스 · 연습 기록 · 공유와 개인정보 |
     * 내 차(차에서 읽음) · 운전자 프로필(차 프로필을 따름) · 앱 안내 ›. 이 앱은 갈 화면이 모두 경로로 있어 줄이 늘 다 있다.
     */
    fun rows(p: Profile, model: String): List<SettingsRow> {
        val presets = p.myPresets.size
        return listOf(
            SettingsRow("coach", SettingsGroup.COACH, R.drawable.ic_user, coach = p.coach, title = "코치", value = coachName(p.coach), target = SettingsTarget.CoachPicker),
            SettingsRow("honorific", SettingsGroup.COACH, R.drawable.ic_speaker, title = "부르는 말", value = honorificLabel(p.honorific), target = SettingsTarget.Honorific),
            SettingsRow("feedback", SettingsGroup.COACH, R.drawable.ic_voice, title = "음성 피드백 설정", target = SettingsTarget.Feedback(stageOf(p))),
            SettingsRow(
                "presets", SettingsGroup.COACH, R.drawable.ic_flag, title = "내 음성 피드백 설정(자율)",
                value = if (presets > 0) "설정 ${presets}개" else "", target = SettingsTarget.Presets,
            ),
            SettingsRow("my-courses", SettingsGroup.DATA, R.drawable.ic_star, title = "내 코스", value = "${p.myCourses.size}개", target = SettingsTarget.MyCourses),
            SettingsRow("history", SettingsGroup.DATA, R.drawable.ic_history, title = "연습 기록", value = "${p.sessions.size}회차", target = SettingsTarget.History),
            SettingsRow(
                "privacy", SettingsGroup.DATA, R.drawable.ic_share, title = "공유와 개인정보",
                value = if (p.consent.family) "가족 공유 켬" else "가족 공유 끔", target = SettingsTarget.Privacy,
            ),
            SettingsRow("car", SettingsGroup.CAR, R.drawable.ic_car_top, title = "내 차", value = model, sub = "차에서 읽어요"),
            SettingsRow("driver", SettingsGroup.CAR, R.drawable.ic_user, title = "운전자 프로필", value = p.name, sub = "차 프로필을 따라요"),
            SettingsRow("about", SettingsGroup.CAR, R.drawable.ic_info, title = "앱 안내", target = SettingsTarget.About),
        )
    }

    /** 받는 사람 이름 다듬기: 공백 하나로, 앞뒤 공백 빼고 10자까지. */
    fun cleanName(text: String?): String = text.orEmpty().replace(Regex("\\s+"), " ").trim().take(NAME_MAX)

    /** 받는 사람 이름(비면 '가족'). */
    fun familyName(p: Profile?): String = cleanName(p?.consent?.familyName).ifEmpty { DEFAULT_TO }

    fun consentOn(p: Profile?, key: ConsentKey): Boolean {
        val c = p?.consent ?: return false
        return when (key) {
            ConsentKey.RECORD -> c.record
            ConsentKey.FAMILY -> c.family
            ConsentKey.ACADEMY -> c.academy
            ConsentKey.INSURER -> c.insurer
        }
    }

    /** 동의 하나를 바꾼 프로필. */
    fun withConsent(p: Profile, key: ConsentKey, on: Boolean): Profile = p.copy(
        consent = when (key) {
            ConsentKey.RECORD -> p.consent.copy(record = on)
            ConsentKey.FAMILY -> p.consent.copy(family = on)
            ConsentKey.ACADEMY -> p.consent.copy(academy = on)
            ConsentKey.INSURER -> p.consent.copy(insurer = on)
        },
    )

    fun consentRows(p: Profile?): List<ConsentRow> = CONSENTS.map { ConsentRow(it.key, it.title, it.sub, it.required, consentOn(p, it.key)) }

    /** 기록을 지울 때 돌아갈 단계: 처음 추천 단계 — 그보다 낮은 단계를 골라 아직 거기 있으면 그 단계. */
    fun restartStage(p: Profile?): Int {
        val rec = p?.onboarding?.recommendedStage ?: 1
        val open = p?.progress?.unlockedStage ?: 1
        return minOf(rec, open).coerceIn(1, 4)
    }

    /**
     * [모든 연습 기록 지우기]: 기록·진행만 지운다(설계 4.2 ST-4) — 코치·호칭·피드백 설정·내 설정·내 코스·동의·발급일은 남긴다.
     * 진행은 온보딩 직후로(결정사항 E T9-5). 기록 목록 자체는 [com.barodrive.app.data.repo.RecordRepository.clear]로 지운다.
     */
    fun clearRecords(p: Profile): Profile {
        val n = restartStage(p)
        return p.copy(
            sessions = emptyList(),
            shareQueue = emptyList(),
            progress = StageProgress(currentStage = n, unlockedStage = n, graduatedAt = null, stages = (1..4).associateWith { StageState() }),
            sessionCount = Mode.numbers.associateWith { 0 },
            fading = emptyMap(),
            g1Baseline = null,
            firstMetrics = null,
            parking = p.parking.copy(done = emptyMap()),
            license = p.license.copy(coursesDone = emptyList()),
        )
    }

    /** 기록 저장 동의 철회: 같은 id·이름의 처음 상태(온보딩 전) 프로필. */
    fun resetProfile(p: Profile): Profile = Profile.blank(p.id, p.name, p.createdAt)
}
