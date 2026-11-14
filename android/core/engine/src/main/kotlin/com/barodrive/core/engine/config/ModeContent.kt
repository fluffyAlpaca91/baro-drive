package com.barodrive.core.engine.config

import com.barodrive.core.engine.content.ContentException
import com.barodrive.core.engine.content.ContentFiles
import com.barodrive.core.model.TaskDef
import kotlinx.serialization.Serializable

/**
 * 과제와 모드별 과제·코스 종류 글 (`content/tasks.json`·`content/modes.json`, 프로토타입 js/data/tasks.js·copy.js `modes[m].tasks`·
 * `courseKinds`, 설계 6.4·6.10.6). 처음 쓸 때 한 번 읽는다(스레드 안전).
 */
internal object ModeContent {
    private val tasks: List<TaskDef> by lazy { loadTasks() }
    private val byId: Map<String, TaskDef> by lazy { tasks.associateBy { it.id } }
    private val modes: Map<Int, ModeEntry> by lazy { loadModes() }

    /** 과제 정의(모드 과제 밖의 T-SCHOOL 포함). 모르는 id면 null. */
    fun task(id: String): TaskDef? = byId[id]

    /** 과제 전부, 파일 순서. */
    val allTasks: List<TaskDef> get() = tasks

    /** 모드 과제(설계 6.4, core.js `modeTasks`): 그 모드 순서대로, 그 모드 문구(labelByMode)를 반영한 label. */
    fun modeTasks(mode: Int): List<TaskDef> {
        require(mode in 1..8) { "모드는 1~8이에요: $mode" }
        return modes[mode]?.tasks.orEmpty().mapNotNull { id -> byId[id]?.let { t -> t.copy(label = t.labelByMode?.get(mode) ?: t.label) } }
    }

    /** CR-1 코스 종류 줄(copy.js `courseKinds[mode].kind`). */
    fun courseKind(mode: Int): String = modes[mode]?.courseKind.orEmpty()

    /** CR-1 안내 줄(copy.js `courseKinds[mode].note`). */
    fun courseNote(mode: Int): String = modes[mode]?.courseNote.orEmpty()

    private fun loadTasks(): List<TaskDef> {
        val list = ContentFiles.decode<TasksFile>(TASKS).tasks
        val seen = HashSet<String>()
        for (t in list) if (!seen.add(t.id)) throw ContentException("$TASKS: ${t.id} — id가 겹쳐요")
        return list
    }

    private fun loadModes(): Map<Int, ModeEntry> {
        val file = ContentFiles.decode<ModesFile>(MODES)
        val out = file.modes.mapKeys { (k, _) -> k.toIntOrNull() ?: throw ContentException("$MODES: 모드 키는 1~8이에요: $k") }
        val known = loadTasks().map { it.id }.toSet()
        for ((m, e) in out) e.tasks.firstOrNull { it !in known }?.let { throw ContentException("$MODES: 모드 $m — 모르는 과제 '$it'") }
        return out
    }

    private const val TASKS = "content/tasks.json"
    private const val MODES = "content/modes.json"
}

@Serializable
internal class TasksFile(val about: String = "", val tasks: List<TaskDef>)

@Serializable
internal class ModesFile(val about: String = "", val modes: Map<String, ModeEntry>)

/** 모드 하나: 과제 순서와 CR-1 줄. */
@Serializable
internal class ModeEntry(val tasks: List<String> = emptyList(), val courseKind: String = "", val courseNote: String = "")
