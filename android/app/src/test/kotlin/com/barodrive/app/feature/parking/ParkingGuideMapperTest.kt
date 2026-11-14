package com.barodrive.app.feature.parking

import com.barodrive.app.R
import com.barodrive.app.session.api.ActivePhase
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.core.engine.content.ParkingGuide as ParkingSteps
import com.barodrive.core.model.BaroJson
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Hints
import com.barodrive.core.model.ParkingHint
import com.barodrive.core.model.ParkingSetup
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.ParkingVisual
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PK-3 화면 값 (프로토타입 parking-guide.js `viewOf`·`update`·`stop`, 시험 t5b_guide '화면 힌트'·e2e t5b-guide 화면 글,
 * t5a '종류·방향 이름'). 엔진 힌트는 골든 m6-S6-P-normal의 힌트 그대로다.
 */
class ParkingGuideMapperTest {
    private val res = XmlTextRes

    private fun active(hint: ParkingHint, gear: Gear?, speed: Double? = 0.0) = DrivingState(
        phase = SessionPhase.ACTIVE,
        activePhase = ActivePhase.DRIVING,
        mode = 6,
        snapshot = VehicleSnapshot(t = 10.0, gear = gear, speed = speed),
        gear = gear,
        output = StepOutput(hints = Hints(parking = hint)),
    )

    @Test
    fun namesAndTitlesMatchTheSimulatorWords() {
        assertEquals("평행 · 오른쪽 칸", parkingTitle("parallel", "R", res))
        assertEquals("후방(후진) · 왼쪽", parkingLabel("back", "left", res))
        assertEquals("전방 · 왼쪽 칸", parkingTitle("front", "L", res))
        assertEquals("평행 · 오른쪽 칸", parkingTitle("nope", null, res))
        assertEquals("앞차 뒷범퍼 끝이 내 어깨와 나란히 오면 멈추세요.", parkingTipText("parallel", 0, res))
        assertEquals("주차할 칸 반대쪽 사이드미러에 뒤차 앞부분이 다 보이면 핸들을 똑바로 푸세요.", parkingTipText("parallel", 1, res))
        assertEquals("바퀴를 똑바로 두면 다음 출발이 편해요.", parkingTipText("front", 2, res))
        assertNull(parkingTipText("back", null, res))
    }

    @Test
    fun beforeTheSessionDrawsStepOneOfTheTableAndBlocksTheButton() {
        val ui = ParkingGuideMapper.map(DrivingState(snapshot = VehicleSnapshot(t = 0.0, gear = Gear.P, speed = 0.0)), "front", "L")
        assertFalse(ui.live)
        assertEquals(listOf(1, 5, 0, false, false), listOf(ui.step, ui.total, ui.index, ui.last, ui.done))
        assertEquals("주차할 칸을 찾으면 비상등을 켜세요.", ui.text)
        assertEquals(GuideVisual(GuideArt.HAZARD, label = "비상등 켜기"), ui.visual)
        assertEquals(GuideBlock.WAIT, ui.block)
        assertEquals("기어를 D로 바꾸면 안내를 시작해요", res.text(ui.block!!.text))
        assertEquals("다음 단계", ui.buttonLabel)
        assertFalse(ui.canPress)
        assertEquals("전방 · 왼쪽 칸", parkingTitle(ui.type, ui.side, res))
        assertEquals("주차할 쪽 사이드미러가 칸의 첫 번째 주차선을 지나면 핸들을 끝까지 돌리세요.", parkingTipText(ui.type, ui.tip, res))
    }

    @Test
    fun preparedParkingChoiceWinsOverTheRoute() {
        val prep = PrepSession(mode = 6, noInMode = 2, parking = ParkingSetup(ParkingType.BACK, TurnDir.LEFT))
        val ui = ParkingGuideMapper.map(DrivingState(phase = SessionPhase.READY, prep = prep, mode = 6), "parallel", "R")
        assertEquals(listOf("back", "L", "7"), listOf(ui.type, ui.side, ui.total.toString()))
        // 다른 모드의 준비 세션은 보지 않는다
        val other = ParkingGuideMapper.map(DrivingState(phase = SessionPhase.READY, prep = prep.copy(mode = 7), mode = 7), "parallel", "R")
        assertEquals("parallel", other.type)
    }

    @Test
    fun liveHintFromTheGoldenRunIsDrawnAsIs() {
        val hint = BaroJson.decodeFromString(ParkingHint.serializer(), GOLDEN_STEP1)
        val ui = ParkingGuideMapper.map(active(hint, Gear.D), "back", "L")
        assertTrue(ui.live)
        assertEquals(listOf("parallel", "R"), listOf(ui.type, ui.side))
        assertEquals(listOf(1, 8, 0), listOf(ui.step, ui.total, ui.index))
        assertEquals("주차할 곳을 찾으면 비상등을 켜세요.", ui.text)
        assertEquals("켰으면 다음 단계를 눌러 주세요", ui.signalText)
        assertNull(ui.block)
        assertTrue(ui.canPress)
        assertEquals(0, ui.tip)
        assertEquals(GuideVisual(GuideArt.HAZARD, label = "비상등 켜기"), ui.visual)
        assertEquals(Gear.D, ui.gear)
        assertFalse(ui.moving)
    }

    @Test
    fun leftSlotStepFourShowsTheMirroredWheel() {
        // t5b '화면 힌트': 평행 · 왼쪽 4단계 = 핸들 왼쪽 끝까지, 그림 −120°
        val st = ParkingSteps.steps("parallel", "L")[3]
        val hint = ParkingHint(
            type = ParkingType.PARALLEL, side = TurnDir.LEFT, key = "parallel-L", total = 8, step = 4, index = 3, text = st.text,
            signal = st.signal.id, dir = TurnDir.LEFT, via = "signal", signalText = st.signalText,
            visual = ParkingVisual(kind = "wheel", rot = -120.0, label = "왼쪽 끝까지"), tip = 1,
            vars = JsonObject(mapOf("dir" to JsonPrimitive("L"))),
        )
        val ui = ParkingGuideMapper.map(active(hint, Gear.R, -2.0), null, null)
        assertEquals("핸들을 왼쪽 끝까지 돌리고 천천히 후진하세요.", ui.text)
        assertEquals(GuideVisual(GuideArt.WHEEL, rot = -120f, label = "왼쪽 끝까지"), ui.visual)
        assertEquals("핸들을 끝까지 돌리면 다음으로 넘어가요", ui.signalText)
        assertTrue(ui.moving)
        assertEquals("평행 · 왼쪽 칸", parkingTitle(ui.type, ui.side, res))
        // 단계 표(회차 전)도 같은 그림
        assertEquals(GuideVisual(GuideArt.WHEEL, rot = -120f, label = "왼쪽 끝까지"), ParkingGuideMapper.visualOf(st.visual))
    }

    @Test
    fun doneStepWaitsForParkThenSaysItIsWrappingUp() {
        val hint = BaroJson.decodeFromString(ParkingHint.serializer(), GOLDEN_DONE)
        val moving = ParkingGuideMapper.map(active(hint, Gear.R), null, null)
        assertEquals(listOf(8, 8, true, true), listOf(moving.shownStep, moving.total, moving.last, moving.done))
        assertEquals(GuideBlock.DONE_MOVING, moving.block)
        assertEquals("기어를 P로 바꾸면 결과를 보여 드려요", res.text(moving.block!!.text))
        assertEquals("주차 마쳤어요", moving.buttonLabel)
        assertEquals(GuideVisual(GuideArt.FINAL, label = "사이드브레이크 채우기"), moving.visual)
        assertEquals("바퀴를 똑바로 두면 다음 출발이 편해요.", parkingTipText(moving.type, moving.tip, res))
        val parked = ParkingGuideMapper.map(active(hint, Gear.P), null, null)
        assertEquals(GuideBlock.DONE_PARKED, parked.block)
        assertEquals("결과를 정리하고 있어요", res.text(parked.block!!.text))
    }

    @Test
    fun stopFollowsGearAndProgress() {
        val done = BaroJson.decodeFromString(ParkingHint.serializer(), GOLDEN_DONE)
        val step1 = BaroJson.decodeFromString(ParkingHint.serializer(), GOLDEN_STEP1)
        fun stop(s: DrivingState) = ParkingGuideMapper.stopOf(s, ParkingGuideMapper.map(s, null, null))
        assertEquals(GuideStop.FINISHING, stop(active(done, Gear.P)))
        assertEquals(GuideStop.MOVING, stop(active(done, Gear.R)))
        assertEquals(GuideStop.MOVING, stop(active(step1, Gear.D)))
        assertEquals(GuideStop.PARKED, stop(active(step1, Gear.P)))
        assertEquals(GuideStop.PARKED, stop(active(step1, null)))
        assertEquals(GuideStop.PARKED, stop(DrivingState()))
    }

    @Test
    fun blockedReasonsUseThePrototypeSentences() {
        assertEquals(R.string.pk3_wait, GuideBlock.WAIT.text)
        assertEquals("마지막 단계 · 기어 P, ", res.text(R.string.pk3_last_head))
        assertEquals("/ 8", res.text(R.string.pk3_of, 8))
        assertEquals("단계마다 한 문장씩 알려 줘요", res.text(R.string.pk3_sub))
    }

    companion object {
        /** 골든 m6-S6-P-normal 첫 힌트(출력 0). */
        const val GOLDEN_STEP1 = """{"type":"parallel","side":"R","key":"parallel-R","total":8,"step":1,"index":0,"last":false,"done":false,
            "line":"pk1.parallel.1","vars":{},"text":"주차할 곳을 찾으면 비상등을 켜세요.","signal":"press","dir":null,"via":"press",
            "signalText":"켰으면 다음 단계를 눌러 주세요","visual":{"kind":"hazard","label":"비상등 켜기"},"tip":0,"pk2":0,"startedAt":0,"stepAt":0,"doneAt":null}"""

        /** 골든 m6-S6-P-normal 마지막 힌트(8단계 다 마침). */
        const val GOLDEN_DONE = """{"type":"parallel","side":"R","key":"parallel-R","total":8,"step":8,"index":7,"last":true,"done":true,
            "line":"pk1.parallel.8","vars":{},"text":"핸들을 똑바로 풀고 기어를 P로 바꾸세요. 사이드브레이크를 채우고 비상등을 끄세요.","signal":"gearP",
            "dir":null,"via":"signal","signalText":"기어가 P가 되면 끝나요","visual":{"kind":"final","label":"사이드브레이크 채우기"},"tip":2,"pk2":1,
            "startedAt":0,"stepAt":71.1,"doneAt":83.5}"""
    }
}
