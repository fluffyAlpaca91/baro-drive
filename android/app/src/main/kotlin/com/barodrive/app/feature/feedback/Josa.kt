package com.barodrive.app.feature.feedback

/**
 * 조사 고르기 (프로토타입 core/util.js `josa`·`josa.pick`). 받침이 있으면 앞 글, 없으면 뒤 글을 붙인다.
 * '으로/로'는 ㄹ 받침도 '로'. 숫자는 읽는 소리로(3 → 삼, 10 → 십), 영어 끝 글자 l·m·n은 받침으로 본다.
 * 화면 토스트("1 · 차량 감 익히기를 …", "'주말'로 저장했어요")에 쓴다.
 */
internal object Josa {
    private val DIGIT_JONG = intArrayOf(21, 8, 0, 16, 0, 0, 1, 8, 8, 0)
    private const val TRAILING = " \t\n\"'”’)]}>.!?,…·"

    /** [word] + 조사([withJong]/[withoutJong] 중 하나). */
    fun attach(word: String, withJong: String, withoutJong: String): String = word + pick(word, withJong, withoutJong)

    /** 조사만. */
    fun pick(word: String, withJong: String, withoutJong: String): String {
        val j = lastJong(word)
        if (withJong == "으로") return if (j == 0 || j == 8) withoutJong else withJong
        return if (j == 0) withoutJong else withJong
    }

    /** 끝 글자의 받침 번호(0 = 없음, 8 = ㄹ). 끝의 따옴표·괄호·문장 부호는 건너뛴다. */
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
            return when (digits.length - digits.trimEnd('0').length) {
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
