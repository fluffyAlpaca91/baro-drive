package com.barodrive.core.engine.content

import kotlinx.serialization.json.Json

/**
 * 콘텐츠 JSON(`resources/content/…`) 읽기 도우미. 프로토타입은 js/data 파일을 스크립트로 불러 `BaroData`에 넣었는데,
 * 앱은 같은 데이터를 JSON 리소스로 두고 처음 쓸 때 한 번 읽는다(아키텍처-세팅 6장 '기획자가 문장을 고치기 쉽게').
 *
 * 모르는 칸이 있으면 읽기가 실패한다(오타가 조용히 빠지는 일을 막음).
 */
internal object ContentFiles {
    val json: Json = Json { ignoreUnknownKeys = false }

    /** 리소스 [path](예: `content/items.json`)를 UTF-8 글로 읽는다. 없으면 [ContentException]. */
    fun read(path: String): String {
        val stream = ContentFiles::class.java.classLoader?.getResourceAsStream(path)
            ?: throw ContentException("콘텐츠 파일이 없어요: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** 리소스 [path]를 [T]로 읽는다. JSON 모양이 틀리면 파일 이름을 붙여 [ContentException]. */
    inline fun <reified T> decode(path: String): T {
        val text = read(path)
        return try {
            json.decodeFromString<T>(text)
        } catch (e: IllegalArgumentException) {
            throw ContentException("콘텐츠 파일을 읽지 못했어요: $path — ${e.message}", e)
        }
    }
}

/** 콘텐츠 JSON이 틀렸을 때(파일 없음, 모르는 칸, 겹친 대사 키 …). 그 콘텐츠를 처음 쓸 때 바로 던진다. */
class ContentException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
