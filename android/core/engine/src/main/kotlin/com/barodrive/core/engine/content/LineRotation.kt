package com.barodrive.core.engine.content

/**
 * 같은 키 문장 번갈아 쓰기의 순번 (설계 6.6 '같은 키에 문장이 2개면 번갈아 쓴다', core.js `renderLine`의 `rotation` 객체).
 * 키마다 지금까지 그린 횟수를 세고, 다음 문장 = 횟수 % 문장 수. 코치와 상관없이 키 하나에 순번 하나다.
 *
 * 엔진 세션은 세션마다 하나씩 쓴다(core.js `S.rot`, [SessionLines]). 넘기지 않으면 공용 [shared]를 쓴다(core.js `ROTATION`).
 * 여러 스레드에서 같이 써도 된다.
 */
class LineRotation {
    private val counts = HashMap<String, Int>()

    /** [key]를 지금까지 그린 횟수(peek·index로 그린 것은 세지 않음). */
    @Synchronized
    fun count(key: String): Int = counts[key] ?: 0

    @Synchronized
    internal fun set(key: String, n: Int) {
        counts[key] = n
    }

    /** 순번을 모두 처음으로 (core.js `resetRotation`은 공용 순번에 이것을 부른다). */
    @Synchronized
    fun reset() = counts.clear()

    companion object {
        /** 공용 순번 (core.js `ROTATION`). 세션 밖에서 그리는 화면 글 등이 쓴다. */
        val shared: LineRotation = LineRotation()
    }
}
