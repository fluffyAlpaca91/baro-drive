package com.barodrive.core.engine.content

import com.barodrive.core.model.Coach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 실제 주차 안내 단계 표 (`content/parking.json`, 프로토타입 js/data/lines/parking.js의 `BaroData.parking`,
 * 설계 6.12.1, 표 A-5·A-6, 목업 s08b). 엔진의 주차 검출기·PK-3 화면·S6-P 시나리오가 같이 쓴다.
 *
 * 종류 id는 'parallel'·'back'·'front', 칸 방향은 'L'·'R'(BaroSim.parking과 같은 글자). 단계 문장 대사 `pk1.<종류>.<n>`은
 * 이 표의 문장을 세 코치에 그대로 넣어 만든다([Lines]). 처음 쓸 때 한 번 읽는다(스레드 안전).
 */
object ParkingGuide {
    /** 칸 방향 (parking.js `SIDES`). */
    val SIDES: List<String> = listOf("L", "R")

    private val data: ParkingData by lazy { load() }

    /** 주차 종류, 표 순서 (parking.js `TYPES`: parallel 평행 · back 후방 · front 전방). */
    val TYPES: List<String> get() = data.types

    /** 다음 단계로 넘어가는 신호마다 PK-3 '다음 단계 신호' 줄과 못 읽으면 화면 누름이 되는 신호(설계 7.8) (parking.js `SIGNALS`). */
    val signals: Map<ParkingSignal, ParkingSignalInfo> get() = data.signals

    /** 신호를 못 읽어 화면 누름으로 넘기는 단계의 줄 (parking.js `PRESS_TEXT`). */
    val pressText: String get() = data.pressText

    /** [다음 단계] 단추 글자, 마지막 단계는 [ParkingButton.last] (parking.js `BUTTON`). */
    val button: ParkingButton get() = data.button

    /** 종류·방향 정리: 모르는 종류는 평행, 방향은 'L'·'LEFT'(대소문자 무시)만 왼쪽이고 나머지는 오른쪽 (parking.js `norm`). */
    fun norm(type: String?, side: String?): ParkingChoice {
        val t = if (type != null && type in data.table) type else DEFAULT_TYPE
        val s = when (side?.uppercase()) {
            "L", "LEFT" -> "L"
            else -> "R"
        }
        return ParkingChoice(t, s)
    }

    /** 'parallel-R' 꼴 키 (parking.js `key`). 정리한 뒤 만든다. */
    fun key(type: String?, side: String?): String = norm(type, side).key

    /** 단계 문장 대사 키 'pk1.parallel.4' (parking.js `lineKey`, 정리하지 않음). */
    fun lineKey(type: String, n: Int): String = "pk1.$type.$n"

    /** 그 종류의 단계 수: 평행 8 · 후방 7 · 전방 5 (parking.js `count`). */
    fun count(type: String?): Int = data.table.getValue(norm(type, null).type).size

    /**
     * 그 종류·방향의 단계들 (parking.js `steps`). 부를 때마다 새 목록이다.
     * 핸들을 끝까지 돌리는 단계의 방향은 고른 칸 쪽(평행 6단계만 반대쪽)이고, 문장의 `{dir}`에 그 방향 글자를 넣는다.
     * 왼쪽 칸은 핸들 그림의 회전도 좌우를 바꾼다.
     */
    fun steps(type: String?, side: String?): List<ParkingStep> {
        val choice = norm(type, side)
        return data.table.getValue(choice.type).mapIndexed { i, row ->
            val dir = when (row.dir) {
                ParkingDirRule.SIDE -> choice.side
                ParkingDirRule.OTHER -> if (choice.side == "L") "R" else "L"
                null -> null
            }
            var visual = row.visual
            if (visual.kind == ParkingVisualKind.WHEEL && choice.side == "L") visual = visual.copy(rot = -(visual.rot ?: 0))
            if (visual.label == FULL) visual = visual.copy(label = Words.dirName(dir) + " 끝까지")
            ParkingStep(
                n = i + 1,
                key = lineKey(choice.type, i + 1),
                vars = if (dir != null) mapOf("dir" to dir) else emptyMap(),
                signal = row.signal,
                dir = dir,
                text = DIR_RE.replace(row.text) { if (dir != null) Words.dirName(dir) else "" },
                signalText = data.signals.getValue(row.signal).text,
                visual = visual,
                tip = row.tip,
            )
        }
    }

    /** 대사 `pk1.<종류>.<n>`: 표 문장을 세 코치에 그대로(parking.js 끝의 `map`). */
    internal fun stepLines(): Map<String, Map<Coach, List<String>>> {
        val out = LinkedHashMap<String, Map<Coach, List<String>>>()
        for ((type, rows) in data.table) {
            rows.forEachIndexed { i, row -> out[lineKey(type, i + 1)] = Coach.entries.associateWith { listOf(row.text) } }
        }
        return out
    }

    private fun load(): ParkingData {
        val file = ContentFiles.decode<ParkingFile>(PATH)
        if (DEFAULT_TYPE !in file.table) throw ContentException("$PATH: 기본 종류 $DEFAULT_TYPE 표가 없어요")
        val signals = LinkedHashMap<ParkingSignal, ParkingSignalInfo>()
        for ((name, info) in file.signals) {
            val signal = ParkingSignal.entries.firstOrNull { it.id == name } ?: throw ContentException("$PATH: 모르는 신호 '$name'")
            signals[signal] = info
        }
        for ((type, rows) in file.table) {
            if (rows.isEmpty()) throw ContentException("$PATH: $type 단계가 없어요")
            rows.firstOrNull { it.signal !in signals }?.let { throw ContentException("$PATH: $type — signals에 ${it.signal.id}가 없어요") }
        }
        return ParkingData(file.table.keys.toList(), file.table, signals, file.pressText, file.button)
    }

    private const val PATH = "content/parking.json"
    private const val DEFAULT_TYPE = "parallel"
    private const val FULL = "full"
}

/** 정리한 주차 종류·방향 (parking.js `norm`의 결과). */
data class ParkingChoice(val type: String, val side: String) {
    /** 'parallel-R' 꼴 (parking.js `key`). */
    val key: String get() = "$type-$side"
}

/**
 * 주차 단계 하나 (parking.js `steps`의 항목).
 * @property n 1부터. [key] 단계 문장 대사 키. [vars] 그 대사를 그릴 변수(방향이 있으면 `dir`).
 * @property dir 그 단계에서 핸들을 돌릴 쪽 'L'·'R'([ParkingSignal.STEER_FULL]만, 그 밖 null).
 * @property text `{dir}`를 채운 단계 문장. [signalText] PK-3 '다음 단계 신호' 줄.
 * @property tip 기준점 꿀팁 번호: 0·1 = 표 A-6 그 종류의 첫째·둘째 문장, 2 = 바퀴 문장.
 */
data class ParkingStep(
    val n: Int,
    val key: String,
    val vars: Map<String, String>,
    val signal: ParkingSignal,
    val dir: String?,
    val text: String,
    val signalText: String,
    val visual: ParkingVisual,
    val tip: Int,
)

/** 다음 단계로 넘어가는 신호 (설계 6.12.1, parking.js `SIGNALS` 키). */
@Serializable
enum class ParkingSignal(val id: String) {
    /** 화면 누름(비상등 단계). */
    @SerialName("press")
    PRESS("press"),

    /** 차가 멈춤. */
    @SerialName("stop")
    STOP("stop"),

    @SerialName("gearR")
    GEAR_R("gearR"),

    @SerialName("gearP")
    GEAR_P("gearP"),

    /** 핸들을 끝까지 돌림. */
    @SerialName("steerFull")
    STEER_FULL("steerFull"),

    /** 핸들을 똑바로 풂. */
    @SerialName("steerStraight")
    STEER_STRAIGHT("steerStraight"),
}

/** 신호 하나의 화면 줄과 못 읽으면 화면 누름이 되는 신호 이름([Items.CAP_KEYS]). */
@Serializable
data class ParkingSignalInfo(
    val text: String,
    val caps: List<String> = emptyList(),
)

/** [다음 단계] 단추 글자. */
@Serializable
data class ParkingButton(
    val next: String,
    val last: String,
)

/**
 * 단계 그림 (목업 s08b). [rot]은 [ParkingVisualKind.WHEEL] 그림 속 핸들 회전°(오른쪽 +), [gear]는 [ParkingVisualKind.GEAR]의 기어 글자.
 * JSON의 label 'full'은 steps()에서 '<방향> 끝까지'로 바뀐다.
 */
@Serializable
data class ParkingVisual(
    val kind: ParkingVisualKind,
    val rot: Int? = null,
    val gear: String? = null,
    val label: String,
)

/** 단계 그림 종류 (목업 s08b): 비상등 · 핸들 · 기어 · 마무리(사이드브레이크). */
@Serializable
enum class ParkingVisualKind {
    @SerialName("hazard")
    HAZARD,

    @SerialName("wheel")
    WHEEL,

    @SerialName("gear")
    GEAR,

    @SerialName("final")
    FINAL,
}

/** 표의 dir 칸: side = 고른 칸 쪽, other = 반대쪽. */
@Serializable
internal enum class ParkingDirRule {
    @SerialName("side")
    SIDE,

    @SerialName("other")
    OTHER,
}

/** 단계 표 한 줄 (parking.js `TABLE` 항목, 표 A-5 오른쪽 칸 기준). */
@Serializable
internal class ParkingRow(
    val text: String,
    val signal: ParkingSignal,
    val dir: ParkingDirRule? = null,
    val visual: ParkingVisual,
    val tip: Int,
)

/** parking.json 모양. */
@Serializable
internal class ParkingFile(
    val about: String = "",
    val table: Map<String, List<ParkingRow>>,
    val signals: Map<String, ParkingSignalInfo>,
    val pressText: String,
    val button: ParkingButton,
)

/** 읽어서 검사한 parking.json(신호 이름을 [ParkingSignal]로 바꿈). */
internal class ParkingData(
    val types: List<String>,
    val table: Map<String, List<ParkingRow>>,
    val signals: Map<ParkingSignal, ParkingSignalInfo>,
    val pressText: String,
    val button: ParkingButton,
)
