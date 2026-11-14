package com.barodrive.core.engine.review

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.content.Items
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 리뷰가 읽는 과제 정의 사본(tasks.js)과 엔진 파사드 과제 정의가 어긋나지 않는지. 파사드가 아직 STUB이면 사본만 본다 —
 * 엔진 코어가 `content/tasks.json`을 채우면 이 시험이 둘이 같은지 확인한다(그 뒤 사본은 지워도 된다).
 */
class TaskTableTest {
    @Test
    fun copyFollowsTasksJsAndModeLists() {
        assertEquals(15, TaskTable.all.size)
        assertEquals(listOf("T-START", "T-STOP", "T-SIGNAL1"), TaskTable.modeTasks(1).map { it.id })
        assertEquals("2단계 T-STOP 문구", "멈출 때 부드럽게 멈추기", TaskTable.modeTasks(2).last().label)
        assertEquals("브레이크 부드럽게 밟아서 멈추기", TaskTable.def("T-STOP")?.label)
        assertTrue(TaskTable.modeTasks(5).isEmpty() && TaskTable.modeTasks(6).isEmpty())
        assertTrue("T-SCHOOL은 모드 과제가 아님", TaskTable.modeIds.values.none { "T-SCHOOL" in it })
        val itemIds = Items.all.map { it.id }.toSet()
        for (t in TaskTable.all) assertTrue("${t.id}의 항목이 항목 정의에 있음", t.items.all { it in itemIds })
    }

    @Test
    fun facadeAgreesWithTheCopyOnceImplemented() {
        for (m in 1..8) {
            val facade = BaroEngine.modeTasks(m)
            if (facade.isEmpty()) continue // STUB(또는 과제 없는 모드)
            assertEquals("모드 $m 과제", TaskTable.modeTasks(m), facade)
        }
        for (t in TaskTable.all) {
            val facade = BaroEngine.taskDef(t.id) ?: continue
            assertEquals(t.id, t, facade)
        }
        assertEquals(TaskTable.modeTasks(2), ReviewTasks.modeTasks(2))
    }
}
