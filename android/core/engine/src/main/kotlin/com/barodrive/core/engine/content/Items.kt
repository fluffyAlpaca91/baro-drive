package com.barodrive.core.engine.content

import com.barodrive.core.model.Category
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.SignalCaps
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 코칭 항목 24개 (`content/items.json`, 프로토타입 js/data/items.js, 설계 6.3.1·6.3.2·7.8)와
 * core.js의 항목 함수 `itemIds`·`defaultFor`·`allowed`·`isFixed`·`itemsOffByCaps`.
 * 처음 쓸 때 한 번 읽는다(스레드 안전).
 *
 * 이 회차 전달 방식 정하기(core.js `resolveItems`·`feedback.resolve`·`examplePresets` — 사용자 변경 > 도움 줄여 가기 > C1 처음 2회차 > 기본값)는
 * 엔진 코어 config/ItemResolver가 이 API로 한다.
 */
object Items {
    /**
     * 안전3 스위치 (설계 12, 프로토타입 `BaroData.flags.EXTRA_SAFETY_ITEMS`, 결정-91). true면 항목 24개,
     * false면 C3·C6·J1([ItemDef.extra])이 빠져 21개로 돈다. 항목 목록을 돌려주는 함수는 `extraSafety` 인자로 이 값을 바꿔 볼 수 있다.
     */
    const val EXTRA_SAFETY_ITEMS: Boolean = true

    /** 신호 이름 12개 (core.js `CAP_KEYS`, [SignalCaps] 칸 이름과 같음). [ItemDef.needs]·[ItemDef.uses]는 이 안에서만 쓴다. */
    val CAP_KEYS: List<String> = listOf(
        "speed", "gear", "blinker", "steer", "nav", "passenger", "beltDriver", "handbrake", "wiper", "outsideTemp", "reverseSpeed", "trip",
    )

    /** 항목 정의 전부(안전3 포함), FB-1 줄 순서. */
    val all: List<ItemDef> by lazy { load() }

    private val byId: Map<String, ItemDef> by lazy { all.associateBy { it.id } }

    /** 지금 쓰는 항목 id, FB-1 줄 순서 (core.js `itemIds`). [extraSafety]가 false면 안전3이 빠진다. */
    fun ids(extraSafety: Boolean = EXTRA_SAFETY_ITEMS): List<String> = all.filter { extraSafety || !it.extra }.map { it.id }

    /** 항목 정의. 모르는 id면 null. 안전3 스위치와 상관없이 찾는다(core.js `itemDef`). */
    fun def(id: String): ItemDef? = byId[id]

    /** 모드 1~8의 기본 전달 방식 (core.js `defaultFor`, 설계 6.3.2 표). 모르는 항목이면 null, 모드가 1~8이 아니면 [IllegalArgumentException]. */
    fun defaultFor(id: String, mode: Int): Delivery? = def(id)?.defaultFor(mode)

    /** 사용자가 고를 수 있는 값 (core.js `allowed`, 결정-20). 고정 항목·모르는 항목은 빈 목록. */
    fun allowed(id: String): List<Delivery> = def(id)?.takeUnless { it.fixed }?.allowed.orEmpty()

    /** 고정 항목인지(안전 항목·F2 — 사용자 변경·도움 줄여 가기가 닿지 않음, core.js `isFixed`). */
    fun isFixed(id: String): Boolean = def(id)?.fixed == true

    /** 못 읽는 신호 때문에 꺼지는 항목, FB-1 줄 순서 (core.js `itemsOffByCaps`, 설계 7.8). [ItemDef.needs] 중 하나라도 못 읽으면 꺼진다. */
    fun itemsOffByCaps(caps: SignalCaps, extraSafety: Boolean = EXTRA_SAFETY_ITEMS): List<String> =
        itemsOffByCaps(caps.byName(), extraSafety)

    /** [itemsOffByCaps]의 프로토타입 꼴: 신호 이름 → 읽음. 값이 false인 신호만 못 읽는 것으로 본다(없는 신호는 읽음). */
    fun itemsOffByCaps(caps: Map<String, Boolean>, extraSafety: Boolean = EXTRA_SAFETY_ITEMS): List<String> =
        ids(extraSafety).filter { id -> byId.getValue(id).needs.any { caps[it] == false } }

    private fun load(): List<ItemDef> {
        val file = ContentFiles.decode<ItemsFile>(PATH)
        val seen = HashSet<String>()
        return file.items.mapIndexed { i, item ->
            fun bad(msg: String): Nothing = throw ContentException("$PATH: ${item.id} — $msg")
            if (!seen.add(item.id)) bad("id가 겹쳐요")
            if (item.defaults.length != 8 || item.defaults.any { it !in "OSX" }) bad("defaults는 O·S·X 8글자예요: ${item.defaults}")
            (item.needs + item.uses).firstOrNull { it !in CAP_KEYS }?.let { bad("모르는 신호 '$it'") }
            item.copy(order = i + 1)
        }
    }

    private const val PATH = "content/items.json"
}

/**
 * 코칭 항목 하나 (프로토타입 `BaroData.items[id]`). JSON에서 빠진 칸은 여기 기본값이다.
 *
 * @property order FB-1 줄 순서(1부터, 목업 s10 ALERTS 순서, 안전3은 E2 뒤). JSON 배열 순서로 정한다.
 * @property name FB-1 줄 이름(목업 s10 글자). [short] 주행 화면 히어로에 쓰는 짧은 이름. [icon] 심볼 id(index.html).
 * @property group safety 안전 항목(늘 바로) · rule 늘 지키는 규칙(F2) · drive · parking · hill.
 * @property cats 말 분류 후보(첫째가 기본). 빈 목록 = 말하지 않는 항목(F2·G1).
 * @property defaults 모드 1~8 기본 전달 방식 글자 8개(설계 6.3.2 표, 표 A-4). 값은 [defaultFor].
 * @property allowed 사용자가 고를 수 있는 값(결정-20). [fixed]면 빈 목록 — 기본값 그대로.
 * @property fixed 고정(안전 항목·F2). 사용자 변경·도움 줄여 가기가 닿지 않는다.
 * @property extra 안전3(C3·C6·J1). [Items.EXTRA_SAFETY_ITEMS]가 false면 항목에서 빠진다(설계 12).
 * @property needs 이 신호 중 하나라도 못 읽으면 항목이 꺼진다(설계 7.8 '꺼지는 항목'). 이름은 [Items.CAP_KEYS].
 * @property uses 쓰지만 못 읽어도 대안이 있는 신호(사이드브레이크 → 말로 묻기 등).
 * @property fade 도움 줄여 가기 대상(설계 6.7: 과제와 이어진 B1·C2·C4·C5·F1).
 * @property first2 '처음 2회차만 O'인 모드(C1: 2·8, 설계 6.7). 없으면 null.
 * @property guide 단계 안내처럼 조작 중에 바로 말해야 하는 항목(간격·상한·비율·큰 조향 침묵을 받지 않음, 결정사항 E T1c-3).
 * @property card 교정을 신호 대기 카드로 보여 주는 항목(설계 6.5.1).
 * @property fb2 자율 '내 음성 피드백 설정'(FB-2) 목록에 있는 항목(결정-23).
 * @property lock 고를 수 없는 칸을 눌렀을 때 토스트 글. 없으면 null.
 * @property note FB-1 줄 아래 작은 글(조건은 화면이 판단). 없으면 null.
 */
@Serializable
data class ItemDef(
    val id: String,
    val name: String,
    val short: String,
    val icon: String,
    val group: ItemGroup,
    val cats: List<Category>,
    val defaults: String,
    val allowed: List<Delivery>,
    val fixed: Boolean = false,
    val extra: Boolean = false,
    val needs: List<String> = emptyList(),
    val uses: List<String> = emptyList(),
    val fade: Boolean = false,
    val first2: List<Int>? = null,
    val guide: Boolean = false,
    val card: Boolean = false,
    val fb2: Boolean = false,
    val lock: ItemLock? = null,
    val note: ItemNote? = null,
    @Transient val order: Int = 0,
) {
    /** 모드 1~8의 기본 전달 방식. 모드가 1~8이 아니면 [IllegalArgumentException]('모드는 1~8이에요: 9'). */
    fun defaultFor(mode: Int): Delivery {
        require(mode in 1..8) { "모드는 1~8이에요: $mode" }
        return when (defaults[mode - 1]) {
            'O' -> Delivery.NOW
            'S' -> Delivery.AFTER
            else -> Delivery.OFF
        }
    }
}

/** 항목 묶음 (items.js `group`). */
@Serializable
enum class ItemGroup {
    /** 안전 항목: 늘 바로 말한다(E1·E2·C3·C6·J1). */
    @SerialName("safety")
    SAFETY,

    /** 늘 지키는 규칙(F2). */
    @SerialName("rule")
    RULE,

    @SerialName("drive")
    DRIVE,

    @SerialName("parking")
    PARKING,

    @SerialName("hill")
    HILL,
}

/**
 * 고를 수 없는 칸을 눌렀을 때 토스트 글 (items.js `lock`, 결정-20).
 * @property now O(바로) 칸 · [after] S(완료 후) 칸 · [all] 고정 항목의 모든 칸 · [off] 고정 항목이 그 모드에서 X일 때.
 */
@Serializable
data class ItemLock(
    @SerialName("O") val now: String? = null,
    @SerialName("S") val after: String? = null,
    val all: String? = null,
    val off: String? = null,
)

/**
 * FB-1 줄 아래 작은 글 (items.js `note`). 어느 글을 보일지는 화면이 정한다.
 * @property wx J3 · [first2On]·[first2Off] C1 처음 2회차 안·뒤 · [rule] F2 늘 지키는 규칙 · [needs] HL2 신호를 못 읽을 때.
 */
@Serializable
data class ItemNote(
    val wx: String? = null,
    val first2On: String? = null,
    val first2Off: String? = null,
    val rule: String? = null,
    val needs: String? = null,
)

/** items.json 모양. */
@Serializable
internal class ItemsFile(
    val about: String = "",
    val items: List<ItemDef>,
)

/** 신호 이름 → 읽음 (core.js caps 객체 꼴). */
private fun SignalCaps.byName(): Map<String, Boolean> = mapOf(
    "speed" to speed, "gear" to gear, "blinker" to blinker, "steer" to steer, "nav" to nav, "passenger" to passenger,
    "beltDriver" to beltDriver, "handbrake" to handbrake, "wiper" to wiper, "outsideTemp" to outsideTemp,
    "reverseSpeed" to reverseSpeed, "trip" to trip,
)
