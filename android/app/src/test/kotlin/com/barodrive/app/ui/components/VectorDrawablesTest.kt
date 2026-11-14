package com.barodrive.app.ui.components

import androidx.compose.ui.graphics.vector.PathParser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 프로토타입 index.html 심볼을 옮긴 벡터 그림(res/drawable: ic_* · av_* · logo_mark)이 읽히는지. pathData가 깨지면 앱이 그 그림을
 * 처음 그릴 때 멈추므로 여기서 미리 본다: 모든 path가 읽히고, 점이 화면틀(viewport) 안에 있다.
 */
class VectorDrawablesTest {
    private val drawableDir = File("src/main/res/drawable")
    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun everyPortedIconParsesInsideItsViewport() {
        val files = drawableDir.listFiles { f -> f.name.matches(Regex("(ic_|av_|logo_).*\\.xml")) && f.name != "ic_launcher_foreground.xml" }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("옮긴 그림이 없어요: ${drawableDir.absolutePath}", files.size >= 51)
        for (file in files) {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val root = factory.newDocumentBuilder().parse(file).documentElement
            assertTrue("${file.name}: vector가 아니에요", root.tagName == "vector")
            val width = root.getAttributeNS(android, "viewportWidth").toFloat()
            val height = root.getAttributeNS(android, "viewportHeight").toFloat()
            val paths = root.getElementsByTagName("path")
            assertTrue("${file.name}: path가 없어요", paths.length > 0)
            for (i in 0 until paths.length) {
                val data = (paths.item(i) as Element).getAttributeNS(android, "pathData")
                val nodes = PathParser().pathStringToNodes(data)
                assertTrue("${file.name}: 빈 pathData", nodes.isNotEmpty())
                val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(data).map { it.value.toFloat() }.toList()
                assertTrue("${file.name}: 숫자가 아닌 값", numbers.none { it.isNaN() || it.isInfinite() })
                assertTrue("${file.name}: 화면틀 밖으로 크게 나간 값", numbers.all { it in -2 * width..2 * maxOf(width, height) })
            }
        }
    }
}
