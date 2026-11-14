package com.barodrive.app.feature.feedback

import com.barodrive.core.model.Profile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FB-2에서 고른 칩·편집 중인 값 (feedback.js `drafts`·`draft`·`setDraftTo`·`dropDraft`): 앱이 떠 있는 동안 프로필마다 기억한다
 * (화면을 나갔다 다시 들어와도 그대로). 고른 설정이 지워졌으면 다시 고른다. 저장소가 아니라 메모리에만 둔다.
 */
@Singleton
class PresetDrafts @Inject constructor() {
    private val drafts = HashMap<String, PresetDraft>()

    /** 지금 편집 값. 없거나, 고른 설정이 지워졌거나, [reset]이면 [PresetRules.pick]으로 새로 만든다. */
    @Synchronized
    fun draft(p: Profile, reset: Boolean = false): PresetDraft {
        val d = drafts[p.id]
        val stale = d?.selId != null && PresetRules.find(p, d.selId) == null
        if (d != null && !stale && !reset) return d
        return PresetRules.draftFor(p, PresetRules.pick(p)).also { drafts[p.id] = it }
    }

    /** 그 설정(null = 새로 만들기)으로 편집 값을 바꾼다. */
    @Synchronized
    fun setTo(p: Profile, id: String?): PresetDraft = PresetRules.draftFor(p, id).also { drafts[p.id] = it }

    /** 편집 값을 고친다(칸 바꾸기·이름 쓰기·기본값으로 되돌리기). */
    @Synchronized
    fun update(p: Profile, transform: (PresetDraft) -> PresetDraft): PresetDraft = transform(draft(p)).also { drafts[p.id] = it }

    /** 그 프로필 편집 값을 버린다(ST-4 기록 저장 동의 철회). */
    @Synchronized
    fun drop(profileId: String) {
        drafts.remove(profileId)
    }
}
