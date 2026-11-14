package com.barodrive.app.session

import com.barodrive.core.model.Courses
import com.barodrive.core.model.PrepSession

/** PR-1 [내비로 보내고 출발] 토스트 글 (session.js `sentToast`). */
internal object SendToast {
    /** 진짜 차: 고른 코스가 좌표 없는 팀 코스·팀 장소(프로토타입의 가상 코스)라 차 내비로 보낼 수 없음. */
    const val NO_COORDS = "이 코스는 좌표가 없어 내비로 보낼 수 없어요. 내비에서 목적지를 넣고 출발해 주세요"

    /** 진짜 차: 내비에 붙지 않았거나 안내 부탁이 실패함. */
    const val NAVI_FAILED = "내비에 목적지를 넣지 못했어요. 내비에서 목적지를 넣고 출발해 주세요"

    /** '내비에 A 코스를 넣었어요 · 경유지 2곳' · '내비에 OO공원을 넣었어요'. [waypoints]는 내비 경로 요약의 경유지 수. */
    fun text(prep: PrepSession, routeName: String? = null, waypoints: Int = 0): String {
        prep.navi?.let { return "내비에 ${Josa.attach(it.name.ifEmpty { "목적지" }, "을", "를")} 넣었어요" }
        if (prep.courseId != null) {
            val c = Courses.course(prep.courseId)
            val name = if (c != null && c.letter.isNotEmpty()) "${c.letter} 코스" else routeName ?: "코스"
            val wp = if (waypoints > 0) " · 경유지 ${waypoints}곳" else ""
            return "내비에 ${Josa.attach(name, "을", "를")} 넣었어요$wp"
        }
        val name = Courses.place(prep.placeId)?.name ?: routeName ?: "목적지"
        return "내비에 ${Josa.attach(name, "을", "를")} 넣었어요"
    }
}

/** 조사 고르기 (util.js `josa` — 받침 규칙. 숫자는 읽는 소리로, 영어 끝 글자 l·m·n은 받침으로). */
internal object Josa {
    private val DIGIT_JONG = intArrayOf(21, 8, 0, 16, 0, 0, 1, 8, 8, 0)
    private const val TRAILING = " \t\n\"'”’)]}>.!?,…·"

    /** [word] + 받침이 있으면 [withJong], 없으면 [withoutJong]. '으로/로'는 ㄹ 받침도 '로'. */
    fun attach(word: String, withJong: String, withoutJong: String): String = word + pick(word, withJong, withoutJong)

    fun pick(word: String, withJong: String, withoutJong: String): String {
        val j = lastJong(word)
        if (withJong == "으로") return if (j == 0 || j == 8) withoutJong else withJong
        return if (j == 0) withoutJong else withJong
    }

    /** 끝 글자의 받침 번호(0 = 없음, 8 = ㄹ). */
    fun lastJong(word: String): Int {
        var i = word.length - 1
        while (i >= 0 && word[i] in TRAILING) i--
        if (i < 0) return 0
        val ch = word[i]
        if (ch in '가'..'힣') return (ch.code - 0xAC00) % 28
        if (ch in '0'..'9') {
            var j = i
            while (j >= 0 && word[j] in '0'..'9') j--
            val digits = word.substring(j + 1, i + 1).trimStart('0').ifEmpty { "0" }
            if (digits == "0") return 21
            val zeros = digits.length - digits.trimEnd('0').length
            return when (zeros) {
                0 -> DIGIT_JONG[ch - '0']
                1 -> 17 // 십(ㅂ)
                2 -> 1 // 백(ㄱ)
                else -> 4 // 천·만(ㄴ)
            }
        }
        return when (ch) {
            'l', 'L' -> 8
            'm', 'M' -> 16
            'n', 'N' -> 4
            else -> 0
        }
    }
}
