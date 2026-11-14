package com.barodrive.app.feature.share

import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.RecordRepository
import com.barodrive.app.di.ApplicationScope
import com.barodrive.app.session.api.SessionController
import com.barodrive.core.model.Gear
import com.barodrive.core.model.ShareStatus
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 보내기 결과 (share.js `send`): 끊겨서 보낼 예정으로 둠 · 보냄(받는 사람). */
sealed interface ShareSendResult {
    data object Queued : ShareSendResult

    data class Sent(val to: String) : ShareSendResult
}

/**
 * 가족에게 요약 보내기 (프로토타입 screens/share.js `send`·`flush`·`cancelQueued`·`wire`, 설계 4.2 RV-7·9, 결정사항 E T9-3·T9-6).
 * - 연결(시연 패널 '네트워크')이면 [ShareRules.SEND_MS] 뒤 보냄 + 가족 휴대폰 카드, 끊김이면 보낼 예정(shareQueue).
 * - 보낼 예정 요약은 연결이 돌아올 때(정차 중이면 바로) 또는 다음에 기어가 P가 될 때 모두 보낸다 — '바뀔 때'만 보낸다.
 * 앱에 하나. 처음 주입될 때 기어 감시를 시작한다 — 앱 뿌리(RootViewModel)가 주입받아 앱 화면이 뜰 때 만들어 두므로, 공유 화면을
 * 열기 전에도 다음 P에서 보낸다.
 */
@Singleton
class ShareCenter @Inject constructor(
    private val profiles: ProfileRepository,
    private val records: RecordRepository,
    private val session: SessionController,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _online = MutableStateFlow(true)

    /** 네트워크 연결. 시연 패널 '네트워크' [연결 | 끊김]이 [setOnline]으로 바꾼다(demo/DemoViewModel). */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _family = MutableStateFlow<List<FamilyCard>>(emptyList())

    /** 가족 휴대폰에 도착한 카드, 새 것이 앞(최대 20장, 시연 패널 '가족 휴대폰 미리보기'). */
    val familyCards: StateFlow<List<FamilyCard>> = _family.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 화면 밖에서 보낸 일의 토스트 글('보내지 못한 요약 2건을 보냈어요'). 앱 뿌리(BaroRoot)가 받아 토스트로 띄운다. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    init {
        scope.launch {
            var last: Gear? = null
            session.state.map { it.gear }.distinctUntilChanged().collect { g ->
                if (g == Gear.P && last != null && last != Gear.P) autoFlush()
                last = g
            }
        }
    }

    /** 정차 중인가(share.js `parked`): 기어 P, 기어를 못 읽으면 차가 서 있을 때. */
    fun parked(): Boolean {
        val s = session.state.value
        val g = s.gear ?: return (s.speed ?: 0.0) <= 0.0
        return g == Gear.P
    }

    /** 연결 바꾸기. 끊김 → 연결로 바뀌면 보낼 예정 요약을 보낸다(정차 중일 때만). */
    fun setOnline(on: Boolean) {
        val back = on && !_online.value
        _online.value = on
        if (back) scope.launch { autoFlush() }
    }

    /** 받은 카드 지우기(시연 패널). */
    fun clearFamily() {
        _family.value = emptyList()
    }

    /** 시트 [보내기](share.js `send`): 끊겼으면 보낼 예정으로, 연결이면 잠깐 뒤 보낸다. 기록이 없으면 null. */
    suspend fun send(recordId: String): ShareSendResult? {
        val rec = records.get(recordId) ?: return null
        val to = ShareRules.familyName(profiles.current())
        if (!online.value) {
            val now = now()
            val p = profiles.update { cur -> ShareRules.markQueued(cur, rec, to, now).first }
            records.update(recordId) { r -> ShareRules.markQueued(p, r, to, now).second }
            return ShareSendResult.Queued
        }
        delay(ShareRules.SEND_MS)
        val cur = records.get(recordId) ?: return null
        val now = now()
        val p = profiles.update { pr -> ShareRules.markSent(pr, cur, to, now).first }
        val sent = ShareRules.markSent(p, cur, to, now).second
        records.update(recordId) { sent }
        addCards(listOf(ShareRules.card(sent, p, to, now)))
        return ShareSendResult.Sent(to)
    }

    /** 보낼 예정 요약을 모두 보낸다(share.js `flush`): 연결 + 정차 중일 때만. 보낸 수. */
    suspend fun flush(): Int {
        if (!online.value || !parked()) return 0
        val p0 = profiles.current()
        val queue = p0.shareQueue
        if (queue.isEmpty()) return 0
        val now = now()
        val cards = ArrayList<FamilyCard>()
        for (id in queue) {
            val r = records.get(id) ?: continue
            val to = r.share.to?.takeIf { it.isNotEmpty() } ?: ShareRules.familyName(p0)
            val sent = ShareRules.markSent(p0, r, to, now).second
            records.update(id) { sent }
            cards += ShareRules.card(sent, p0, to, now)
        }
        profiles.update { it.copy(shareQueue = emptyList()) }
        addCards(cards)
        return cards.size
    }

    /** 가족 공유 동의를 끌 때(ST-4): 보낼 예정이던 요약을 거둔다. 거둔 수. */
    suspend fun cancelQueued(): Int {
        var n = 0
        for (r in records.list()) {
            val id = r.id ?: continue
            if (ShareRules.status(r) == ShareStatus.QUEUED) {
                records.update(id) { ShareRules.cancelQueued(listOf(it)).first.single() }
                n++
            }
        }
        profiles.update { it.copy(shareQueue = emptyList()) }
        return n
    }

    private suspend fun autoFlush() {
        val n = flush()
        if (n > 0) _notices.emit("보내지 못한 요약 ${n}건을 보냈어요")
    }

    private fun addCards(cards: List<FamilyCard>) {
        if (cards.isEmpty()) return
        _family.update { (cards + it).take(ShareRules.FAMILY_MAX) }
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)
}
