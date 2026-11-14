package com.barodrive.app.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pleos 눌림 (Pleos UX 2.4 '모든 터치 요소가 95%로 줄어들고 배경색이 바뀐다', 프로토타입 components.css 맨 끝
 * `:active{transform:scale(.95);filter:brightness(.92)}`). 누르는 동안 요소가 95%로 줄고 그 요소의 그림만 8% 어두워진다.
 * 그리기 단계에서만 바뀌어 다시 구성하지 않는다(0.25초 안 반응). [BaroTheme]이 기본 눌림(LocalIndication)으로 깐다.
 * 스크롤 안에서는 Compose가 누름 알림을 조금 늦게 보내 짧은 탭은 누름과 뗌이 함께 온다 — 그래도 눌림이 보이게 뗀 뒤 최소
 * [MIN_PRESSED_MS]만큼은 눌린 모양을 둔다. 스크롤로 취소된 누름은 바로 푼다.
 * 누를 수 없는 요소(enabled = false)는 눌림 신호가 없어 그대로다. 막힌 칸처럼 눌러도 이유만 알리는 요소는 indication = null로 둔다.
 */
object BaroPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = javaClass.hashCode()
}

private const val PRESSED_SCALE = 0.95f
private const val PRESSED_DARKEN = 0.08f

/** 짧은 탭도 눌린 모양이 보이게 두는 가장 짧은 시간(밀리초). */
private const val MIN_PRESSED_MS = 100L

private class PressNode(private val interactionSource: InteractionSource) : Modifier.Node(), DrawModifierNode {
    /** 눌린 누름 → 누른 때(System.nanoTime). */
    private val presses = mutableMapOf<PressInteraction.Press, Long>()

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> update { presses[interaction] = System.nanoTime() }
                    is PressInteraction.Release -> release(interaction.press)
                    is PressInteraction.Cancel -> update { presses -= interaction.press }
                }
            }
        }
    }

    override fun onDetach() {
        presses.clear()
    }

    private fun release(press: PressInteraction.Press) {
        val startedAt = presses[press] ?: return
        val shownMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        if (shownMs >= MIN_PRESSED_MS) {
            update { presses -= press }
        } else {
            coroutineScope.launch {
                delay(MIN_PRESSED_MS - shownMs)
                update { presses -= press }
            }
        }
    }

    private inline fun update(change: () -> Unit) {
        val before = presses.isNotEmpty()
        change()
        if (presses.isNotEmpty() != before) invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        if (presses.isEmpty()) {
            drawContent()
            return
        }
        scale(PRESSED_SCALE) {
            // 요소 그림만 어둡게(brightness .92): 따로 그린 층에서 그 위에만(SrcAtop) 검정 8%를 덮는다.
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                this@draw.drawContent()
                drawRect(Color.Black.copy(alpha = PRESSED_DARKEN), blendMode = BlendMode.SrcAtop)
                canvas.restore()
            }
        }
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
