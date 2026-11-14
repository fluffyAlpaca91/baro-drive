package com.barodrive.core.engine.content

import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific

/**
 * 세션 하나의 대사 그리기 (core.js `createSession` 안의 `render(key, vars, peek)` · `session.renderLine`).
 * 코치·호칭은 세션 설정 값으로 고정하고, 번갈아 쓰기 순번은 세션마다 따로 센다(core.js `S.rot`).
 * 한 세션 안에서 차례로 부른다(엔진 세션과 같은 스레드).
 */
class SessionLines(
    val coach: Coach,
    val honorific: Honorific?,
    private val book: LineBook = Lines.book,
) {
    /** 이 세션의 순번. */
    val rotation: LineRotation = LineRotation()

    /** 이 세션 코치·호칭으로 [key]를 그린다. [peek]이면 순번을 넘기지 않는다. 규칙은 [LineBook.render]. */
    fun render(key: String, vars: Map<String, Any?> = emptyMap(), peek: Boolean = false): String =
        book.render(key, coach, vars, honorific, rotation, peek)
}
