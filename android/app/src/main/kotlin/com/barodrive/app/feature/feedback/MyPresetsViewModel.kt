package com.barodrive.app.feature.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barodrive.app.data.repo.ProfileRepository
import com.barodrive.app.data.repo.selectPreset
import com.barodrive.app.session.api.SessionController
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.DeliveryReason
import com.barodrive.core.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 저장된 설정 칩 하나: 이름('출퇴근용 (바꿈)')과 '예시' 태그. */
data class PresetChipUi(val id: String, val name: String, val example: Boolean, val selected: Boolean)

/** 이름 바꾸기·지우기 시트 (feedback.js `openEdit`). 새 이름 칸 글은 [MyPresetsViewModel.editField], [confirmDelete]면 지우기 확인. */
data class PresetEditUi(val id: String, val name: String, val confirmDelete: Boolean = false)

/** FB-2 내 음성 피드백 설정에 그릴 값 (feedback.js FB-2 `paint`). */
data class MyPresetsUiState(
    val loading: Boolean = true,
    val chips: List<PresetChipUi> = emptyList(),
    /** '새로 만들기' 칩이 골라져 있음. */
    val newSelected: Boolean = false,
    val desc: String = "",
    /** 안전 항목 고정 카드 이름 'E1 · E2 · …'. */
    val safeNames: String = "",
    val rows: List<FeedbackRow> = emptyList(),
    /** [이 설정으로 출발]을 그림(설정에서 연 관리 화면이 아닐 때). */
    val canStart: Boolean = false,
    val edit: PresetEditUi? = null,
)

/** FB-2가 화면에 시키는 일. */
sealed interface MyPresetsEffect {
    data class Toast(val text: String) : MyPresetsEffect

    /** 준비 세션을 만들었으니 PR-1(자율 변형)로. */
    data object GoPredrive : MyPresetsEffect
}

/**
 * FB-2 내 음성 피드백 설정(자율) (프로토타입 screens/feedback.js FB-2, 설계 4.2 FB-2·5.5, 목업 s12).
 * 저장된 설정 칩 + '새로 만들기', 설명 줄, 안전 항목 고정 카드, [기본값으로 되돌리기], 항목 12개, 이름 + [저장], [이 설정으로 출발].
 * 고른 칩·편집 중인 값은 [PresetDrafts]에 프로필별로 남는다. 설정(ST-1)에서 열면([MyFeedbackPresets.manage]) 출발 버튼 없이 관리만.
 * 글 입력 칸(이름 · 시트의 새 이름)은 커서가 튀지 않게 Compose 상태([nameText]·[editField])로 바로 바꾼다.
 */
@HiltViewModel
class MyPresetsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val session: SessionController,
    private val drafts: PresetDrafts,
) : ViewModel() {
    private val manage: Boolean = savedStateHandle.get<Boolean>(KEY_MANAGE) ?: false
    private val version = MutableStateFlow(0)
    private val edit = MutableStateFlow<PresetEditUi?>(null)
    private val _effects = Channel<MyPresetsEffect>(Channel.BUFFERED)

    val effects: Flow<MyPresetsEffect> = _effects.receiveAsFlow()

    /** 이름 칸 글(편집 값의 이름과 같게 둔다). */
    var nameText: String by mutableStateOf("")
        private set

    /** 이름 바꾸기 시트의 새 이름 칸 글. */
    var editField: String by mutableStateOf("")
        private set

    val uiState: StateFlow<MyPresetsUiState> = combine(profiles.profile, version, edit) { p, _, e -> build(p, drafts.draft(p), e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MyPresetsUiState())

    init {
        viewModelScope.launch {
            var shownProfile: String? = null
            profiles.profile.collect { p ->
                // 프로필이 바뀌면 그 프로필의 편집 값 이름으로(feedback.js paint의 nameField.value = d.name)
                if (p.id != shownProfile) {
                    shownProfile = p.id
                    nameText = drafts.draft(p).name
                }
            }
        }
    }

    /** 칩 누름: 그 설정을 편집 값으로(이미 그 설정이고 바꾸지 않았으면 그대로). */
    fun selectPreset(id: String) {
        viewModelScope.launch { selectNow(id) }
    }

    /** '새로 만들기' 칩. */
    fun selectNew() {
        viewModelScope.launch {
            val p = profiles.current()
            if (drafts.draft(p).selId == null) return@launch
            nameText = drafts.setTo(p, null).name
            bump()
        }
    }

    /** 칸 고르기(편집 값만 바꾼다 — 저장은 [save]). */
    fun choose(item: String, value: Delivery) {
        viewModelScope.launch {
            drafts.update(profiles.current()) { it.copy(items = it.items + (item to value)) }
            bump()
        }
    }

    /** 막힌 칸을 누름 → 이유 토스트. */
    fun blocked(row: FeedbackRow, value: Delivery) {
        _effects.trySend(MyPresetsEffect.Toast(row.lockText(value)))
    }

    /** [기본값으로 되돌리기]: 편집 값을 자율 모드 기본값으로. */
    fun resetItems() {
        viewModelScope.launch {
            val p = profiles.current()
            drafts.update(p) { it.copy(items = PresetRules.base(p)) }
            bump()
            _effects.send(MyPresetsEffect.Toast("자율 모드 기본값으로 되돌렸어요"))
        }
    }

    /** 이름 칸 글. */
    fun setName(text: String) {
        nameText = text
        viewModelScope.launch { drafts.update(profiles.current()) { it.copy(name = text) } }
    }

    /** [저장]: 고른 설정과 같은 이름이면 덮어쓰고, 아니면 새 이름으로 저장한다. */
    fun save() {
        viewModelScope.launch {
            val p0 = profiles.current()
            val d = drafts.draft(p0)
            val name = PresetRules.cleanName(nameText)
            val sel = PresetRules.find(p0, d.selId)
            val over = sel != null && name == PresetRules.cleanName(sel.name)
            var res: PresetResult? = null
            val p = profiles.update { cur ->
                val r = if (over) PresetRules.saveOver(cur, sel.id, d.items) else PresetRules.saveNew(cur, name, d.items)
                res = r
                (r as? PresetResult.Ok)?.profile ?: cur
            }
            when (val r = res) {
                is PresetResult.Error -> _effects.send(MyPresetsEffect.Toast(r.message))
                is PresetResult.Ok -> {
                    nameText = drafts.setTo(p, r.preset.id).name
                    bump()
                    val text = if (over) {
                        "'$name'에 저장했어요"
                    } else {
                        "'${r.preset.name}'${Josa.pick(r.preset.name, "으로", "로")} 저장했어요"
                    }
                    _effects.send(MyPresetsEffect.Toast(text))
                }
                null -> Unit
            }
        }
    }

    /** [이 설정으로 출발] → 준비 세션(모드 5) → PR-1(자율 변형). 회차가 진행 중이면 토스트만. */
    fun start() {
        viewModelScope.launch {
            if (session.state.value.active) {
                _effects.send(MyPresetsEffect.Toast("주행이 끝난 뒤에 출발할 수 있어요"))
                return@launch
            }
            val p = profiles.current()
            val prep = PresetRules.startPrep(p, drafts.draft(p))
            prep.presetId?.let { profiles.selectPreset(it) }
            session.prepare(prep)
            _effects.send(MyPresetsEffect.GoPredrive)
        }
    }

    /** 칩 옆 연필 또는 칩 길게 누르기 → 이름 바꾸기·지우기 시트. */
    fun openEdit(id: String) {
        viewModelScope.launch {
            val p = profiles.current()
            val pr = PresetRules.find(p, id) ?: return@launch
            if (drafts.draft(p).selId != id) selectNow(id)
            val name = PresetRules.cleanName(pr.name)
            editField = name
            edit.value = PresetEditUi(id = id, name = name)
        }
    }

    fun setEditName(text: String) {
        editField = text
    }

    fun closeEdit() {
        edit.value = null
    }

    /** 시트 [지우기] → 확인 대화상자(시트는 고를 때까지 열어 둔다). */
    fun askDelete() = edit.update { it?.copy(confirmDelete = true) }

    fun cancelDelete() = edit.update { it?.copy(confirmDelete = false) }

    /** 지우기 확인. 고른 설정이었으면 남은 설정(또는 새로 만들기)을 다시 고른다. */
    fun confirmDelete() {
        val e = edit.value ?: return
        viewModelScope.launch {
            val p = profiles.update { cur -> PresetRules.remove(cur, e.id) ?: cur }
            // 고른 설정을 지웠으면 draft가 남은 첫 설정(또는 새로 만들기)을 다시 고른다
            nameText = drafts.draft(p).name
            edit.value = null
            bump()
            _effects.send(MyPresetsEffect.Toast("'${e.name}' 설정을 지웠어요"))
        }
    }

    /** 시트 [이름 바꾸기]. 같은 이름이면 말없이 닫고, 안 되면 토스트만(시트는 그대로). */
    fun rename() {
        val e = edit.value ?: return
        viewModelScope.launch {
            var res: PresetResult? = null
            val p = profiles.update { cur ->
                val r = PresetRules.rename(cur, e.id, editField)
                res = r
                (r as? PresetResult.Ok)?.profile ?: cur
            }
            when (val r = res) {
                is PresetResult.Error -> _effects.send(MyPresetsEffect.Toast(r.message))
                is PresetResult.Ok -> {
                    if (!r.same) {
                        if (drafts.draft(p).selId == e.id) nameText = drafts.update(p) { it.copy(name = r.preset.name) }.name
                        _effects.send(MyPresetsEffect.Toast("이름을 '${r.preset.name}'${Josa.pick(r.preset.name, "으로", "로")} 바꿨어요"))
                    }
                    edit.value = null
                    bump()
                }
                null -> Unit
            }
        }
    }

    private suspend fun selectNow(id: String) {
        val p = profiles.current()
        val d = drafts.draft(p)
        if (id == d.selId && !PresetRules.isModified(p, d)) return
        nameText = drafts.setTo(p, id).name
        profiles.selectPreset(id)
        bump()
    }

    private fun bump() = version.update { it + 1 }

    private fun build(p: Profile, d: PresetDraft, e: PresetEditUi?): MyPresetsUiState {
        val modified = PresetRules.isModified(p, d)
        val chips = PresetRules.list(p).map { pr ->
            val on = pr.id == d.selId
            PresetChipUi(id = pr.id, name = PresetRules.chipName(pr, on && modified), example = pr.example, selected = on)
        }
        val rows = PresetRules.ids().mapNotNull { id ->
            val it = BaroEngine.itemDef(id) ?: return@mapNotNull null
            val v = d.items[id] ?: Delivery.OFF
            FeedbackRow(
                id = id, name = it.name, kind = FeedbackRowKind.SEG, value = v, reason = DeliveryReason.PRESET, def = null,
                allowed = it.allowed, locked = false, lock = FeedbackRules.lockTexts(it, v),
                note = if (id == J3 && v != Delivery.OFF) it.note?.get("wx").orEmpty() else "",
            )
        }
        val ids = BaroEngine.itemIds()
        val safeNames = SAFE_IDS.filter { it in ids }.mapNotNull { BaroEngine.itemDef(it)?.name }.joinToString(" · ")
        return MyPresetsUiState(
            loading = false, chips = chips, newSelected = d.selId == null, desc = PresetRules.desc(PresetRules.find(p, d.selId), d.items),
            safeNames = safeNames, rows = rows, canStart = !manage, edit = e,
        )
    }

    companion object {
        /** 경로 인자 이름([MyFeedbackPresets.manage]). */
        const val KEY_MANAGE = "manage"
        private const val J3 = "J3"

        /** 안전 항목 고정 카드(설계 4.2 FB-2): 안전3 스위치를 끄면 E1·E2만. */
        private val SAFE_IDS = listOf("E1", "E2", "C3", "C6", "J1")
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
