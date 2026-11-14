package com.barodrive.core.engine.content

import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import kotlinx.serialization.Serializable

/**
 * 대사 자리에 들어가는 글자 (`content/words.json`). 프로토타입 js/data/copy.js의 `dir`·`weather.wx`·`honorifics.maxLength`·
 * `numWord`와 core.js `honorificText`의 '우리 딸'·'우리 아들'을 옮겼다(설계 6.6·6.6.1).
 * 처음 쓸 때 한 번 읽는다(스레드 안전).
 */
object Words {
    private val data: WordsFile by lazy { load() }

    /** 'L'·'R' → '왼쪽'·'오른쪽' (copy.js `dir`). */
    val dir: Map<String, String> get() = data.dir

    /** `{wx}` 자리 글자: 'rain'·'snow'·'severe' → '비 오는 날은'·'눈 오는 날은'·'오늘 같은 날은' (copy.js `weather.wx`). */
    val wx: Map<String, String> get() = data.wx

    /** 직접 쓴 호칭의 최대 글자 수(설계 6.6.1: 8자). */
    val honorificMaxLength: Int get() = data.honorific.maxLength

    /** 방향 글자. 모르는 값이면 'L'만 '왼쪽', 나머지는 '오른쪽'(프로토타입 여러 파일의 `dirName`). */
    fun dirName(dir: String?): String = data.dir[dir] ?: if (dir == "L") "왼쪽" else "오른쪽"

    /** 고유어 수 관형형: 3 → '세'("과제 세 개"). 표 밖이면 숫자 그대로(session.js의 `p.tasks` 변수 `{nWord}`). */
    fun numWord(n: Int): String = data.numWord.getOrNull(n) ?: n.toString()

    /**
     * 문장에 넣을 호칭 글자 (core.js `honorificText`, 설계 6.6.1). 형제/자매 코치이거나 호칭이 없으면 ''.
     * 딸·아들은 '우리 딸'·'우리 아들', 직접 쓴 말은 앞뒤 공백을 빼고 8자까지.
     */
    fun honorificText(honorific: Honorific?, coach: Coach): String {
        if (coach == Coach.SIB || honorific == null) return ""
        return when (honorific.type) {
            HonorificType.DAUGHTER -> data.honorific.daughter
            HonorificType.SON -> data.honorific.son
            HonorificType.CUSTOM -> honorific.text.jsTrim().take(data.honorific.maxLength)
            else -> ""
        }
    }

    private fun load(): WordsFile {
        val words = ContentFiles.decode<WordsFile>(PATH)
        for (d in listOf("L", "R")) {
            if (words.dir[d].isNullOrBlank()) throw ContentException("$PATH: dir.$d 글자가 없어요")
        }
        if (words.honorific.maxLength < 1) throw ContentException("$PATH: honorific.maxLength는 1 이상이에요")
        return words
    }

    private const val PATH = "content/words.json"
}

/** words.json 모양. */
@Serializable
internal class WordsFile(
    val about: String = "",
    val dir: Map<String, String>,
    val wx: Map<String, String>,
    val honorific: HonorificWords,
    val numWord: List<String>,
)

/** words.json의 호칭 글자: 딸·아들에 넣는 말과 직접 쓴 말의 최대 글자 수. */
@Serializable
internal class HonorificWords(
    val daughter: String,
    val son: String,
    val maxLength: Int,
)
