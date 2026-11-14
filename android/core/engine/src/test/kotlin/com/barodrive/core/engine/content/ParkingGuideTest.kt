package com.barodrive.core.engine.content

import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 주차 단계 표 (프로토타입 t5b_guide.test.js 'T5b 단계 표', 설계 6.12.1, 표 A-5 오른쪽·왼쪽 열). */
class ParkingGuideTest {
    private val kinds = listOf("parallel" to "R", "parallel" to "L", "back" to "R", "back" to "L", "front" to "R", "front" to "L")

    /** 표 A-5 (종합정리 10장) 오른쪽 칸 · 왼쪽 칸 열 그대로. */
    private val a5 = mapOf(
        "parallel" to mapOf(
            "R" to listOf(
                "주차할 곳을 찾으면 비상등을 켜세요.", "주차 칸 앞차와 나란히, 50cm~1m 떨어져 서세요.", "기어를 R로 바꾸세요.",
                "핸들을 오른쪽 끝까지 돌리고 천천히 후진하세요.", "차가 비스듬히 들어가면 멈추고 핸들을 똑바로 푸세요.",
                "천천히 후진하다가 앞차를 지나면 핸들을 왼쪽 끝까지 돌리세요.", "차가 길과 나란해지면 멈추세요.",
                "핸들을 똑바로 풀고 기어를 P로 바꾸세요. 사이드브레이크를 채우고 비상등을 끄세요.",
            ),
            "L" to listOf(
                "주차할 곳을 찾으면 비상등을 켜세요.", "주차 칸 앞차와 나란히, 50cm~1m 떨어져 서세요.", "기어를 R로 바꾸세요.",
                "핸들을 왼쪽 끝까지 돌리고 천천히 후진하세요.", "차가 비스듬히 들어가면 멈추고 핸들을 똑바로 푸세요.",
                "천천히 후진하다가 앞차를 지나면 핸들을 오른쪽 끝까지 돌리세요.", "차가 길과 나란해지면 멈추세요.",
                "핸들을 똑바로 풀고 기어를 P로 바꾸세요. 사이드브레이크를 채우고 비상등을 끄세요.",
            ),
        ),
        "back" to mapOf(
            "R" to listOf(
                "주차할 칸을 찾으면 비상등을 켜세요.", "칸 반대쪽으로 붙어서 지나가다가, 주차할 칸을 조금 지나 멈추세요.", "기어를 R로 바꾸세요.",
                "핸들을 오른쪽 끝까지 돌리고 천천히 후진하세요.", "차가 칸과 나란해지면 핸들을 똑바로 푸세요.",
                "똑바로 천천히 후진하다가 멈춤턱 앞에서 멈추세요.", "기어를 P로 바꾸세요. 사이드브레이크를 채우고 비상등을 끄세요.",
            ),
            "L" to listOf(
                "주차할 칸을 찾으면 비상등을 켜세요.", "칸 반대쪽으로 붙어서 지나가다가, 주차할 칸을 조금 지나 멈추세요.", "기어를 R로 바꾸세요.",
                "핸들을 왼쪽 끝까지 돌리고 천천히 후진하세요.", "차가 칸과 나란해지면 핸들을 똑바로 푸세요.",
                "똑바로 천천히 후진하다가 멈춤턱 앞에서 멈추세요.", "기어를 P로 바꾸세요. 사이드브레이크를 채우고 비상등을 끄세요.",
            ),
        ),
        "front" to mapOf(
            "R" to listOf(
                "주차할 칸을 찾으면 비상등을 켜세요.", "칸 반대쪽으로 붙어서 다가가다가, 칸 입구에서 핸들을 오른쪽 끝까지 돌리세요.",
                "차가 칸과 나란해지면 핸들을 똑바로 푸세요.", "천천히 들어가다가 멈춤턱 앞에서 멈추세요.",
                "기어를 P로 바꾸세요. 사이드브레이크를 채우고 비상등을 끄세요.",
            ),
            "L" to listOf(
                "주차할 칸을 찾으면 비상등을 켜세요.", "칸 반대쪽으로 붙어서 다가가다가, 칸 입구에서 핸들을 왼쪽 끝까지 돌리세요.",
                "차가 칸과 나란해지면 핸들을 똑바로 푸세요.", "천천히 들어가다가 멈춤턱 앞에서 멈추세요.",
                "기어를 P로 바꾸세요. 사이드브레이크를 채우고 비상등을 끄세요.",
            ),
        ),
    )

    private val signals = mapOf(
        "parallel" to listOf("press", "stop", "gearR", "steerFull", "steerStraight", "steerFull", "stop", "gearP"),
        "back" to listOf("press", "stop", "gearR", "steerFull", "steerStraight", "stop", "gearP"),
        "front" to listOf("press", "steerFull", "steerStraight", "stop", "gearP"),
    )

    @Test
    fun stepsMatchTableA5AndEveryCoachSaysTheSameSentence() {
        assertEquals(listOf("parallel", "back", "front"), ParkingGuide.TYPES)
        assertEquals(listOf("L", "R"), ParkingGuide.SIDES)
        val daughter = Honorific(HonorificType.DAUGHTER)
        for ((type, side) in kinds) {
            val steps = ParkingGuide.steps(type, side)
            assertEquals(type + side, a5.getValue(type).getValue(side).size, steps.size)
            assertEquals(steps.size, ParkingGuide.count(type))
            assertEquals("$type$side 문장", a5.getValue(type).getValue(side), steps.map { it.text })
            assertEquals("$type$side 신호", signals.getValue(type), steps.map { it.signal.id })
            steps.forEachIndexed { i, s ->
                assertEquals(i + 1, s.n)
                assertEquals("pk1.$type.${i + 1}", s.key)
                for (c in Coach.entries) {
                    assertEquals("${s.key} · $c", s.text, Lines.render(s.key, c, s.vars, daughter, peek = true))
                }
                assertEquals(ParkingGuide.signals.getValue(s.signal).text, s.signalText)
                assertTrue(s.tip in 0..2)
                if (s.signal == ParkingSignal.STEER_FULL) assertTrue(s.dir == "L" || s.dir == "R") else assertNull(s.dir)
            }
            assertEquals("마지막 단계 꿀팁 = 바퀴 문장", 2, steps.last().tip)
        }
    }

    @Test
    fun steeringDirectionAndPictures() {
        // 평행 6단계는 반대쪽, 나머지 끝까지 단계는 고른 칸 쪽
        assertEquals("R", ParkingGuide.steps("parallel", "R")[3].dir)
        assertEquals("L", ParkingGuide.steps("parallel", "R")[5].dir)
        assertEquals("R", ParkingGuide.steps("parallel", "L")[5].dir)
        assertEquals("L", ParkingGuide.steps("front", "L")[1].dir)
        // 그림(목업 s08b)
        val pr = ParkingGuide.steps("parallel", "R")
        val pl = ParkingGuide.steps("parallel", "L")
        assertEquals(
            listOf("hazard", "wheel", "gear", "wheel", "wheel", "wheel", "wheel", "final"),
            pr.map { it.visual.kind.name.lowercase() },
        )
        assertEquals(ParkingVisual(ParkingVisualKind.WHEEL, rot = 120, label = "오른쪽 끝까지"), pr[3].visual)
        assertEquals(ParkingVisual(ParkingVisualKind.WHEEL, rot = -120, label = "왼쪽 끝까지"), pr[5].visual)
        assertEquals(ParkingVisual(ParkingVisualKind.WHEEL, rot = -120, label = "왼쪽 끝까지"), pl[3].visual)
        assertEquals("왼쪽 칸은 0도도 0", 0, pl[1].visual.rot)
        assertEquals(ParkingVisual(ParkingVisualKind.GEAR, gear = "R", label = "기어 R"), pr[2].visual)
        assertEquals("목업 PARK tip 순서", listOf(0, 0, 0, 1, 1, 1, 1, 2), pr.map { it.tip })
        assertEquals(mapOf("dir" to "R"), pr[3].vars)
        assertEquals(emptyMap<String, String>(), pr[0].vars)
    }

    @Test
    fun normKeysAndTexts() {
        assertEquals(ParkingChoice("parallel", "L"), ParkingGuide.norm("nope", "left"))
        assertEquals(ParkingChoice("parallel", "R"), ParkingGuide.norm(null, null))
        assertEquals(ParkingChoice("back", "R"), ParkingGuide.norm("back", "x"))
        assertEquals("front-L", ParkingGuide.key("front", "L"))
        assertEquals("parallel-R", ParkingGuide.key("nope", null))
        assertEquals("pk1.back.3", ParkingGuide.lineKey("back", 3))
        assertEquals(listOf(8, 7, 5, 8), listOf("parallel", "back", "front", "nope").map { ParkingGuide.count(it) })
        assertEquals("다 했으면 다음 단계를 눌러 주세요", ParkingGuide.pressText)
        assertEquals(ParkingButton(next = "다음 단계", last = "주차 마쳤어요"), ParkingGuide.button)
        assertEquals(ParkingSignal.entries.toSet(), ParkingGuide.signals.keys)
        assertEquals(listOf("gear"), ParkingGuide.signals.getValue(ParkingSignal.GEAR_R).caps)
        assertEquals(listOf("steer"), ParkingGuide.signals.getValue(ParkingSignal.STEER_STRAIGHT).caps)
        assertEquals("핸들을 끝까지 돌리면 다음으로 넘어가요", ParkingGuide.signals.getValue(ParkingSignal.STEER_FULL).text)
    }
}
