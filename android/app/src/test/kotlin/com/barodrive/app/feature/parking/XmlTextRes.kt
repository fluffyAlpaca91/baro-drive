package com.barodrive.app.feature.parking

import com.barodrive.app.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 시험용 [TextRes]: Android 없이 `src/main/res/values/strings_*.xml`을 읽어 `getString(id, args)`처럼 글을 만든다.
 * 그래서 화면 글을 만드는 순수 함수를 프로토타입 글과 글자 그대로 맞춰 볼 수 있다(리소스 이름 ↔ id는 R 클래스에서).
 */
object XmlTextRes : TextRes {
    private val byName: Map<String, String> by lazy {
        val dir = listOf(File("src/main/res/values"), File("app/src/main/res/values")).first { it.isDirectory }
        val out = HashMap<String, String>()
        dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val list = doc.getElementsByTagName("string")
            for (i in 0 until list.length) {
                val el = list.item(i) as Element
                out[el.getAttribute("name")] = unescape(el.textContent)
            }
        }
        out
    }

    private val nameById: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    override fun text(id: Int, vararg args: Any): String {
        val name = nameById[id] ?: error("모르는 문자열 id $id")
        val raw = byName[name] ?: error("strings xml에 없는 이름 $name")
        return if (args.isEmpty()) raw else String.format(Locale.KOREA, raw, *args)
    }

    /** Android 문자열 규칙: 통째로 감싼 큰따옴표는 벗기고(안의 빈칸은 그대로), \' \" \n \\ 를 푼다. */
    private fun unescape(s: String): String {
        val trimmed = s.trim()
        val body = if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) trimmed.substring(1, trimmed.length - 1) else trimmed
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (val n = body[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
