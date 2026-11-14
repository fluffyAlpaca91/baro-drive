package com.barodrive.core.engine.content

import com.barodrive.core.model.HillSlope
import kotlinx.serialization.Serializable

/**
 * 언덕 연습 모양 (`content/hill.json`, 프로토타입 js/data/lines/hill.js의 `BaroData.hill`, 설계 4.2 HL-1·HL-2, 표 A-8,
 * 결정사항 E T6-1). HL-1·HL-2 화면과 엔진의 언덕 단계 기계가 같이 읽는다. 처음 쓸 때 한 번 읽는다(스레드 안전).
 * 단계 문장은 대사 `hl1.<연습>.<n>`(코치와 상관없이 같은 문장)이다.
 */
object HillGuide {
    private val data: HillFile by lazy { ContentFiles.decode<HillFile>("content/hill.json") }

    /** 연습 셋: up 오르막 출발 · down 내리막 감속 · park 언덕 주차. */
    val practices: List<HillPracticeDef> get() = data.practices

    /** 언덕 주차 상황 셋(down · up · none)과 맞는 앞바퀴 방향(종합 4장 표, 목업 s13b). */
    val situations: List<HillSituationDef> get() = data.situations

    /** 화면 글(계속 밟음 줄, 뒤로 밀림을 못 읽을 때, PR-1 안전 카드, 연습을 마친 뒤 글 …). */
    val text: HillText get() = data.text

    /** id로 연습 찾기 (hill.js `practice`). 없으면 null. */
    fun practice(id: String?): HillPracticeDef? = data.practices.firstOrNull { it.id == id }

    /** id로 상황 찾기 (hill.js `situation`). 없으면 null. */
    fun situation(id: String?): HillSituationDef? = data.situations.firstOrNull { it.id == id }

    /** 엔진 입력의 상황 값으로 찾기(DOWN → 'down' …). */
    fun situation(slope: HillSlope): HillSituationDef? = situation(slope.name.lowercase())

    /** 연습 [practiceId]의 [n]번째 단계 (hill.js `step`). 없으면 null. */
    fun step(practiceId: String?, n: Int): HillStepDef? = practice(practiceId)?.steps?.firstOrNull { it.n == n }
}

/** 언덕 연습 하나: 연습 칩 [name], 앱 바 부제 [sub], 단계들. */
@Serializable
data class HillPracticeDef(
    val id: String,
    val name: String,
    val sub: String,
    val steps: List<HillStepDef>,
)

/**
 * 언덕 단계 하나 (목업 s13a HILL).
 * @property key 단계 문장 대사 키. [label] 단계 칸 글자. [sig] 다음 단계로 넘어가는 신호 안내. [tap] 화면 누름 단계([했어요]).
 */
@Serializable
data class HillStepDef(
    val n: Int,
    val key: String,
    val label: String,
    val icon: String,
    val sig: String,
    val tap: Boolean,
)

/**
 * 언덕 주차 상황 하나.
 * @property side 맞는 앞바퀴 방향 'L'·'R'. [sideText] 단계 문장 `hl1.park.2`의 `{side}`에 넣는 글('연석 쪽(오른쪽)').
 * @property dirName 방향 이름. [curb] 연석이 있는지. [why] 까닭. [front] 차 앞쪽 설명.
 */
@Serializable
data class HillSituationDef(
    val id: String,
    val label: String,
    val side: String,
    val sideText: String,
    val dirName: String,
    val curb: Boolean,
    val why: String,
    val front: String,
)

/** 언덕 화면 글. [done]은 연습 id → 마친 뒤 글. */
@Serializable
data class HillText(
    val holdWarn: String,
    val rollbackOff: String,
    val safety: String,
    val retry: String,
    val done: Map<String, String>,
    val wheelStraight: String,
    val wheelOk: String,
    val wheelWrong: String,
)

/** hill.json 모양. */
@Serializable
internal class HillFile(
    val about: String = "",
    val practices: List<HillPracticeDef>,
    val situations: List<HillSituationDef>,
    val text: HillText,
)
