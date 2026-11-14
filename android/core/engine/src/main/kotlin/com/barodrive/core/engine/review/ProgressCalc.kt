package com.barodrive.core.engine.review

import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.fading.FadingCalc
import com.barodrive.core.model.FirstMetrics
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ProgressApplied
import com.barodrive.core.model.ProgressResult
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StageState
import com.barodrive.core.model.TodayTask
import com.barodrive.core.model.TodayTaskFrom

/**
 * 진행 반영과 오늘 과제 (프로토타입 js/engine/review.js `applyProgress`·`todayTasks`, 설계 6.10.4·5.2·6.7, 결정-76·89·90).
 * 프로토타입은 프로필·기록을 그 자리에서 고치는데, 여기서는 바뀐 프로필·기록을 새 값으로 돌려준다.
 */
internal object ProgressCalc {
    /**
     * 회차 끝 진행 반영 (review.js `applyProgress(profile, rec, { now })`). 같은 기록(`progress.applied`)은 두 번 반영하지 않는다.
     *
     * 1. 모드 회차 수 + 1
     * 2. 단계 통과(모드 1~4): 통과 회차(누적, 연속 아님)가 2가 되면 통과. 지금 단계면 다음 단계를 열고, 4단계면 졸업.
     *    지난 단계는 그 단계 카운트·통과한 때만(지금 단계·열린 단계는 그대로, 결정-89). 이미 통과한 단계는 그대로.
     * 3. 첫 회차 기준값: g1Baseline·firstMetrics는 지표마다 처음 나온 값(어떤 모드든, 결정-90)
     * 4. 도움 줄여 가기(6.7) — 기록에 fadedNow·fadedNote·progress를 남긴다.
     * passedAt = [now] › 기록 startedAt › 기록 id › '회차 {모드}-{회차}'.
     */
    fun applyProgress(profile: Profile, record: SessionRecord, now: String?): ProgressApplied {
        record.progress?.takeIf { it.applied }?.let { return ProgressApplied(profile, record, it) }
        val m = record.mode
        val need = Thresholds["pass.count"].toInt()
        var res = ProgressResult(applied = true, mode = m, stage = null, qualified = record.qualified, need = need)

        // 1. 회차 수
        val sessionCount = LinkedHashMap(profile.sessionCount)
        val count = (sessionCount[m] ?: 0) + 1
        sessionCount[m] = count
        var p = profile.copy(sessionCount = sessionCount)

        // 2. 단계 통과(모드 1~4)
        if (m in 1..4) {
            val pr = p.progress
            val st = pr.stages[m] ?: StageState()
            val cur = pr.currentStage.takeIf { it != 0 } ?: 1
            val before = st.qualified
            val alreadyPassed = !st.passedAt.isNullOrEmpty()
            res = res.copy(stage = m, qualifiedBefore = before, alreadyPassed = alreadyPassed, pastStage = m < cur)
            var stage = st
            var progress = pr
            if (!alreadyPassed && record.qualified) {
                stage = stage.copy(qualified = before + 1)
                if (stage.qualified >= need) {
                    val noInMode = record.noInMode?.takeIf { it != 0 } ?: count
                    val whenText = now.orNull() ?: record.startedAt.orNull() ?: record.id.orNull() ?: "회차 $m-$noInMode"
                    stage = stage.copy(passedAt = whenText)
                    res = res.copy(passedAt = whenText)
                    if (m == cur) {
                        res = res.copy(passed = true)
                        if (m < 4) {
                            val unlocked = maxOf(progress.unlockedStage.takeIf { it != 0 } ?: 1, m + 1)
                            progress = progress.copy(currentStage = m + 1, unlockedStage = unlocked)
                            res = res.copy(nextStage = m + 1)
                        } else {
                            progress = progress.copy(graduatedAt = progress.graduatedAt.orNull() ?: whenText)
                            res = res.copy(graduated = true)
                        }
                    }
                }
            }
            val stages = LinkedHashMap(progress.stages)
            stages[m] = stage
            p = p.copy(progress = progress.copy(stages = stages))
            res = res.copy(qualifiedAfter = stage.qualified)
        }

        // 3. 첫 회차 기준값(지표마다 처음 나온 값 — 결정-90: 30회차를 넘어 지워져도 남음)
        val mt = record.metrics
        if (p.g1Baseline == null && mt.g1 != null) p = p.copy(g1Baseline = mt.g1)
        p = p.copy(firstMetrics = firstMetrics(p.firstMetrics, record))

        // 4. 도움 줄여 가기(6.7)
        val f = FadingCalc.update(p, record)
        if (m in 1..8) p = p.copy(fading = LinkedHashMap(p.fading).apply { put(m, f.state) })
        res = res.copy(fadedNow = f.fadedNow, restoredNow = f.restoredNow)

        val rec = record.copy(fadedNow = res.fadedNow, fadedNote = FadingCalc.note(res.fadedNow), progress = res)
        return ProgressApplied(profile = p, record = rec, result = res)
    }

    /** K4 기준(review.js applyProgress 3번): c2Avg·g1은 비어 있을 때 처음 값, 급정지는 횟수가 있는 첫 회차 값(비율 함께). */
    private fun firstMetrics(prev: FirstMetrics?, record: SessionRecord): FirstMetrics? {
        val mt = record.metrics
        var fm = prev ?: FirstMetrics()
        var touched = false
        if (fm.c2Avg == null && mt.c2Avg != null) {
            fm = fm.copy(c2Avg = mt.c2Avg)
            touched = true
        }
        if (fm.g1 == null && mt.g1 != null) {
            fm = fm.copy(g1 = mt.g1)
            touched = true
        }
        val total = mt.stopTotal
        if (fm.stopTotal == null && total != null && total > 0) {
            val fail = mt.stopFail ?: 0
            fm = fm.copy(stopTotal = total, stopFail = fail, stopFailRate = mt.stopFailRate ?: round3(fail.toDouble() / total))
            touched = true
        }
        return if (touched || prev != null) fm else null
    }

    /**
     * PR-1 오늘 과제 최대 3개 (review.js `todayTasks`, 결정-76): 1번 = 그 모드 지난 회차의 다음 과제,
     * 나머지 = 그 모드 과제 중 도움 줄여 가기로 내려가지 않은 것, 지난 회차 성공률 낮은 순(판정 없으면 맨 뒤, 같으면 과제 순서).
     * 세워 두고 연습 기록은 건너뛴다.
     */
    fun todayTasks(profile: Profile?, mode: Int): List<TodayTask> {
        val defs = ReviewTasks.modeTasks(mode)
        if (defs.isEmpty()) return emptyList()
        val last = profile?.sessions.orEmpty().firstOrNull { it.mode == mode && it.kind != RecordKind.VIRTUAL_PARKING }
        val out = ArrayList<TodayTask>()
        val nt = last?.nextTask
        if (nt != null && nt.id.isNotEmpty()) {
            val def = defs.firstOrNull { it.id == nt.id }
            out += TodayTask(
                id = nt.id,
                label = nt.label.orNull() ?: def?.label.orNull() ?: nt.id,
                short = nt.short.orNull() ?: def?.short.orEmpty(),
                from = TodayTaskFrom.NEXT,
                rate = null,
            )
        }
        val fad = profile?.fading?.get(mode).orEmpty()
        val lastRate = { id: String ->
            last?.tasks?.firstOrNull { it.id == id }?.takeIf { it.total > 0 }?.let { it.success.toDouble() / it.total }
        }
        val rest = defs.withIndex()
            .map { (i, t) -> Triple(t, i, lastRate(t.id)) }
            .filter { (t) -> out.none { it.id == t.id } && t.items.none { fad[it]?.faded == true } }
            .sortedWith { a, b -> sign((a.third ?: 1.0) - (b.third ?: 1.0)).takeIf { it != 0 } ?: (a.second - b.second) }
        for ((t, _, r) in rest) {
            if (out.size >= MAX_TODAY) break
            out += TodayTask(id = t.id, label = t.label, short = t.short, from = TodayTaskFrom.MODE, rate = r?.let(::round3))
        }
        return out
    }

    private const val MAX_TODAY = 3

    /** JS `x || 다음`처럼 빈 글자도 없는 것으로. */
    private fun String?.orNull(): String? = this?.takeIf { it.isNotEmpty() }
}
