package com.barodrive.app.feature.home

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.Profile
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** 단계 카드 상태 (home.js `summary().stages[].state`): 지금 · 통과 · 열림(시작할 때 건너뜀, 결정-89) · 잠김. */
enum class StageCardState { NOW, PASSED, OPEN, LOCKED }

/** 진행 카드 뒷글 (home.js `progressTail`). 졸업이면 [NONE]. */
enum class ProgressTail {
    NONE,

    /** '한 번만 더 하면 통과예요'. */
    ONE_MORE,

    /** '두 번 해내면 모든 단계를 마쳐요'(4단계). */
    ALL_STAGES,

    /** '두 번 해내면 다음 단계가 열려요'. */
    NEXT_STAGE,
}

/** 확인 안 한 급제동이 있는 회차 하나 (home.js `f2.records[]`). */
data class F2Ref(val recordId: String, val mode: Int?, val n: Int)

/**
 * 홈 상태 (프로토타입 home.js `BaroApp.home.summary(profile, today)`, 설계 4.2 HM-1·5.2). 저장된 상태만 보고 계산한다.
 */
data class HomeSummary(
    /** 지금 단계 1~4. */
    val stage: Int = 1,
    /** 지금 단계 통과 회차 0~1(졸업이면 2). */
    val k: Int = 0,
    val graduated: Boolean = false,
    val unlocked: Int = 1,
    val tail: ProgressTail = ProgressTail.NEXT_STAGE,
    /** 단계 1~4 카드 상태. */
    val stages: List<StageCardState> = listOf(StageCardState.NOW, StageCardState.LOCKED, StageCardState.LOCKED, StageCardState.LOCKED),
    /** 확인 안 한 급제동 수(모든 회차). */
    val f2Total: Int = 0,
    /** 그 회차들, 최신이 앞. */
    val f2Records: List<F2Ref> = emptyList(),
    /** 보내지 못한 요약 수(shareQueue). */
    val share: Int = 0,
    val licenseIssueDate: String? = null,
    /** 연습면허 유효기간 끝 'YYYY-MM-DD'(발급일 + 1년 − 1일). */
    val licenseExpiresOn: String? = null,
    /** 유효기간이 지났나(만료일 다음 날부터). */
    val licenseExpired: Boolean = false,
)

/** 홈 계산 (home.js `summary`·`licenseExpiry`·`licenseExpired`). 순수 함수 — 오늘 날짜는 부르는 쪽이 넘긴다. */
object HomeRules {
    private fun clampStage(n: Int): Int = n.coerceIn(1, 4)

    fun summary(p: Profile, today: LocalDate): HomeSummary {
        val pr = p.progress
        fun passed(n: Int): Boolean = !pr.stages[n]?.passedAt.isNullOrEmpty()
        val graduated = !pr.graduatedAt.isNullOrEmpty() || passed(4)
        val unlocked = clampStage(pr.unlockedStage)
        val stage = clampStage(pr.currentStage)
        val k = if (graduated) 2 else (pr.stages[stage]?.qualified ?: 0).coerceIn(0, 1)
        val tail = when {
            graduated -> ProgressTail.NONE
            k >= 1 -> ProgressTail.ONE_MORE
            stage >= 4 -> ProgressTail.ALL_STAGES
            else -> ProgressTail.NEXT_STAGE
        }
        val stages = (1..4).map { n ->
            when {
                passed(n) -> StageCardState.PASSED
                !graduated && n == stage -> StageCardState.NOW
                n <= maxOf(unlocked, stage) -> StageCardState.OPEN
                else -> StageCardState.LOCKED
            }
        }
        val f2 = p.sessions.mapNotNull { r ->
            val n = r.f2.count { it.choice == null }
            val id = r.id
            if (n > 0 && id != null) F2Ref(id, r.mode, n) else null
        }
        val issue = p.license.issueDate
        return HomeSummary(
            stage = stage, k = k, graduated = graduated, unlocked = unlocked, tail = tail, stages = stages,
            f2Total = f2.sumOf { it.n }, f2Records = f2, share = p.shareQueue.size,
            licenseIssueDate = issue, licenseExpiresOn = licenseExpiry(issue), licenseExpired = licenseExpired(issue, today),
        )
    }

    /** 연습면허 유효기간 끝 = 발급일 + 1년 − 1일 'YYYY-MM-DD'(설계 4.2 LC-2). 못 읽으면 null. */
    fun licenseExpiry(issueDate: String?): String? = BaroEngine.license.expiry(issueDate)

    /** 오늘이 유효기간 끝 다음 날 이후인가. 발급일이 없으면 거짓. */
    fun licenseExpired(issueDate: String?, today: LocalDate): Boolean {
        val exp = licenseExpiry(issueDate) ?: return false
        return try {
            today.isAfter(LocalDate.parse(exp))
        } catch (_: DateTimeParseException) {
            false
        }
    }
}
