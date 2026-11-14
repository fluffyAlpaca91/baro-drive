package com.barodrive.core.engine.review

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.TaskDef

/**
 * 리뷰·단계 계산이 읽는 과제 정의 (설계 6.4). 하나뿐인 출처는 엔진 파사드 [BaroEngine.modeTasks]·[BaroEngine.taskDef]
 * (엔진 코어가 `content/tasks.json`으로 옮긴 data/tasks.js)다. 파사드가 빈 목록·null을 줄 때만(과제 없는 모드 5·6, 실패)
 * [TaskTable](tasks.js를 그대로 옮긴 사본)로 대신한다 — 사본과 파사드가 같은지는 시험(TaskTableTest)이 본다.
 */
internal object ReviewTasks {
    /** 그 모드 과제(모드 문구 반영, review.js `modeTasks` — 실패하면 []). 5·6은 []. */
    fun modeTasks(mode: Int): List<TaskDef> =
        runCatching { BaroEngine.modeTasks(mode) }.getOrDefault(emptyList()).ifEmpty { TaskTable.modeTasks(mode) }

    /** 과제 정의(모드 문구 반영 전, `BaroData.tasks[id]`). 모르면 null. */
    fun def(id: String?): TaskDef? = id?.let { runCatching { BaroEngine.taskDef(it) }.getOrNull() ?: TaskTable.def(it) }
}

/**
 * 과제 15개 (프로토타입 js/data/tasks.js)와 모드별 과제 목록(copy.js `modes[m].tasks`, 설계 6.4 아래 표) 사본.
 * [ReviewTasks]가 엔진 파사드의 과제 정의가 비어 있을 때만 쓴다.
 */
internal object TaskTable {
    val all: List<TaskDef> = listOf(
        TaskDef("T-START", "엑셀 살살 밟아서 출발하기", "출발", "출발마다", "B1 성공", listOf("B1")),
        TaskDef(
            "T-STOP", "브레이크 부드럽게 밟아서 멈추기", "멈출 때 브레이크", "평가 제동 구간마다", "F1 성공(F2 제외)", listOf("F1"),
            labelByMode = mapOf(2 to "멈출 때 부드럽게 멈추기"),
        ),
        TaskDef("T-SIGNAL1", "핸들 돌리기 전에 깜빡이 켜기", "깜빡이", "조향 회전마다", "조향 시작 전에 맞는 쪽 깜빡이가 켜짐", listOf("C5")),
        TaskDef("T-30", "시속 30 넘지 않기", "30 구역", "제한 30 이하 구역마다", "E1 없음", listOf("E1"), nextLabel = "30 구역 지키기"),
        TaskDef("T-SIGNAL30", "꺾기 30m 전에 깜빡이 켜기", "깜빡이", "내비 회전마다", "C2 성공(40m 이상, 결정-37)", listOf("C2")),
        TaskDef("T-WAIT3", "깜빡이 켜자마자 꺾지 않기", "깜빡이 켜고 셋 세기", "깜빡이 켠 회전마다", "C4 성공", listOf("C4")),
        TaskDef("T-LIMIT", "제한속도 지키기", "제한속도", "구역마다", "E1 없음", listOf("E1"), nextLabel = "제한속도 지키기"),
        TaskDef("T-SIGNALPRE", "꺾기 전에 미리 깜빡이 켜기", "깜빡이", "내비 회전마다", "C2 성공", listOf("C2")),
        TaskDef("T-STOPSIG", "신호 앞에서 부드럽게 멈추기", "멈출 때 브레이크", "제동 구간마다", "F1 성공", listOf("F1")),
        TaskDef("T-IC", "들어갈 때·나갈 때 깜빡이 미리 켜기", "깜빡이", "IC 진입·진출마다", "C2(110m) 성공", listOf("C2")),
        TaskDef("T-NOBRAKE", "갑자기 브레이크 밟지 않기", "브레이크", "제동 구간마다", "F1 성공", listOf("F1")),
        TaskDef("T-HILLUP", "오르막에서 뒤로 밀리지 않고 출발하기", "오르막 출발", "오르막 출발 시도마다", "HL2 없음", listOf("HL2")),
        TaskDef(
            "T-HILLDOWN", "내리막에서 브레이크 나눠 밟기", "내리막 브레이크", "내리막 구간마다",
            "2번 이상 나눠 밟고 8초 넘게 계속 밟지 않음", listOf("HL1"),
        ),
        TaskDef(
            "T-HILLPARK", "언덕에 세울 때 바퀴 방향 맞추고 사이드브레이크 채우기", "언덕 주차", "언덕 주차마다",
            "HL3 맞음 + 사이드브레이크 채움(못 읽으면 바퀴만)", listOf("HL3", "PB2"),
        ),
        TaskDef(
            "T-SCHOOL", "어린이보호구역 30 지키기", "어린이보호구역", "어린이보호구역마다", "E2 없음", listOf("E2"),
            nextLabel = "어린이보호구역 30 지키기", safetyOnly = true,
        ),
    )

    /** copy.js `modes[m].tasks`. */
    val modeIds: Map<Int, List<String>> = mapOf(
        1 to listOf("T-START", "T-STOP", "T-SIGNAL1"),
        2 to listOf("T-30", "T-SIGNAL30", "T-WAIT3", "T-STOP"),
        3 to listOf("T-LIMIT", "T-SIGNALPRE", "T-STOPSIG"),
        4 to listOf("T-IC", "T-LIMIT", "T-NOBRAKE"),
        5 to emptyList(),
        6 to emptyList(),
        7 to listOf("T-HILLUP", "T-HILLDOWN", "T-HILLPARK"),
        8 to listOf("T-LIMIT", "T-SIGNAL30", "T-WAIT3", "T-STOPSIG"),
    )

    private val byId: Map<String, TaskDef> = all.associateBy { it.id }

    fun def(id: String): TaskDef? = byId[id]

    /** core.js `modeTasks`: 모드 목록 순서, `labelByMode`가 있으면 그 모드 문구로. */
    fun modeTasks(mode: Int): List<TaskDef> = modeIds[mode].orEmpty().mapNotNull { id ->
        byId[id]?.let { t -> t.labelByMode?.get(mode)?.let { t.copy(label = it) } ?: t }
    }
}
