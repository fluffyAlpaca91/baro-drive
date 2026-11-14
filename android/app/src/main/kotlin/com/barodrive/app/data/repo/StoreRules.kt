package com.barodrive.app.data.repo

import com.barodrive.core.model.AppState
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionRecord
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 저장 규칙 (프로토타입 app/store.js `defaults`·`normalize`·`records.add`·`remove`, 설계 8.1). 순수 함수 — 메모리 구현과
 * 기기 저장 구현이 같이 쓴다. 기록은 프로필의 `sessions`(최신이 앞)로 다룬다.
 */
object StoreRules {
    /** 기록 수 상한(store.js `MAX_RECORDS`). */
    const val MAX_RECORDS = Profile.MAX_RECORDS

    /** 저장 키(store.js `KEY`) — 기기 저장 파일 이름에도 쓴다. */
    const val KEY = "baro.app.v1"

    private val ID_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val ISO_LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    /** 그 지역 시간대 ISO 글자 '2026-11-10T09:12:00+09:00'(util.js `isoLocal`). */
    fun isoLocal(now: ZonedDateTime): String = ISO_LOCAL.format(now)

    /** 기본 상태(store.js `defaults`): 온보딩 전, 프로필 p1 '운전자 1'. */
    fun defaults(now: ZonedDateTime? = null): AppState =
        AppState(profiles = mapOf("p1" to Profile.blank("p1", "운전자 1", now?.let(::isoLocal))))

    /**
     * 읽은 상태를 v1 모양으로(store.js `normalize`): 버전이 1이 아니면 null. 지금 프로필 id가 비면 'p1',
     * 그 프로필이 없으면 처음 상태로 만든다. (빠진 칸 채우기는 JSON 기본값이 한다.)
     */
    fun normalize(state: AppState, now: ZonedDateTime? = null): AppState? {
        if (state.version != 1) return null
        val active = state.activeProfileId.ifEmpty { "p1" }
        val profiles = if (active in state.profiles) state.profiles
        else state.profiles + (active to Profile.blank(active, createdAt = now?.let(::isoLocal)))
        return state.copy(activeProfileId = active, profiles = profiles)
    }

    /** 새 기록 id 바탕 'sess-20261110-0912'(store.js `records.add`). */
    fun recordIdBase(now: ZonedDateTime): String = "sess-" + ID_STAMP.format(now)

    /** 같은 id가 있으면 '-2', '-3' …을 붙인다(store.js `uniqueId`). */
    fun uniqueId(taken: Collection<String>, base: String): String {
        if (base !in taken) return base
        var k = 2
        while ("$base-$k" in taken) k++
        return "$base-$k"
    }

    /**
     * 기록을 맨 앞에 넣는다(store.js `records.add`). id가 없거나 비면 [now]로 만든다. 30개를 넘으면 오래된 것을 지우고
     * `shareQueue`에서도 뺀다. 바뀐 프로필과 id를 붙인 기록.
     */
    fun addRecord(profile: Profile, record: SessionRecord, now: ZonedDateTime): Pair<Profile, SessionRecord> {
        val base = record.id?.takeIf { it.isNotEmpty() } ?: recordIdBase(now)
        val saved = record.copy(id = uniqueId(profile.sessions.mapNotNull { it.id }, base))
        val all = listOf(saved) + profile.sessions
        val kept = all.take(MAX_RECORDS)
        val gone = all.drop(MAX_RECORDS).mapNotNull { it.id }.toSet()
        val queue = if (gone.isEmpty()) profile.shareQueue else profile.shareQueue.filterNot { it in gone }
        return profile.copy(sessions = kept, shareQueue = queue) to saved
    }

    /** 기록을 지운다(store.js `records.remove`): `shareQueue`에서도 뺀다. 없으면 null. */
    fun removeRecord(profile: Profile, id: String): Profile? {
        if (profile.sessions.none { it.id == id }) return null
        return profile.copy(sessions = profile.sessions.filterNot { it.id == id }, shareQueue = profile.shareQueue.filterNot { it == id })
    }

    /** 기록 하나를 고친다(store.js `records.update`). id는 그대로 둔다. 없으면 null. */
    fun updateRecord(profile: Profile, id: String, transform: (SessionRecord) -> SessionRecord): Profile? {
        if (profile.sessions.none { it.id == id }) return null
        return profile.copy(sessions = profile.sessions.map { if (it.id == id) transform(it).copy(id = id) else it })
    }

    /**
     * 기록을 모두 지운다: 기록과 보낼 줄만. 진행·기준값은 그대로(결정-90). ST '기록 지우기'가 진행까지 처음으로 돌리는 것
     * (settings.js `clearRecords`의 progress·sessionCount·fading …)은 화면이 [ProfileRepository.update]로 한다.
     */
    fun clearRecords(profile: Profile): Profile = profile.copy(sessions = emptyList(), shareQueue = emptyList())
}
