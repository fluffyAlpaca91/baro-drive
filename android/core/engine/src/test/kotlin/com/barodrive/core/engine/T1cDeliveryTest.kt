package com.barodrive.core.engine

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.detect.CardSpec
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.model.BlockReason
import com.barodrive.core.model.BrakeEndEvent
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.TurnApproach
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.TurnPassed
import com.barodrive.core.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프로토타입 t1c_engine.test.js — 말 줄이기(설계 6.5.2)·만료(6.5.1)·신호 대기 카드(6.5.3). */
class T1cDeliveryTest {
    private val eps = 1e-9

    private fun cfg(mode: Int = 1, items: Map<String, Delivery> = emptyMap()) = SessionConfig(mode = mode, items = items)

    private fun cardAction(at: Double, item: String?, key: String?, title: String, category: CardCategory, detail: String = "") =
        Action(at) { ctx, _ -> ctx.card(item = item, key = key, title = title, category = category, detail = detail) }

    /** t1c `stopAt`: 30km/h에서 5초 동안 살살 멈추고(1초 6km/h) 2초 머묾 → 신호 대기는 t0 + 6초. */
    private fun stopAt(t0: Double, tpl: VehicleSnapshot = snap(gear = Gear.D)) =
        ramp(30.0, 0.0, 5.0, t0, tpl = tpl) + hold(0.0, 2.0, t0 + 5, brake = true, tpl = tpl)

    @Test
    fun rule1NonNowItemsAreBlockedAsSOrXButJudgmentsStay() {
        val s = sess(cfg(items = mapOf("C2" to Delivery.AFTER, "C4" to Delivery.OFF)), listOf(script(listOf(
            Action(0.5) { ctx, _ ->
                ctx.say("t1c.praise", item = "C2", category = Category.PRAISE)
                ctx.emit("C2", EventResult.OK, EventData(distance = 55.0))
            },
            Action(0.6) { ctx, _ ->
                ctx.say("t1c.fix", item = "C4", category = Category.FIX)
                ctx.emit("C4", EventResult.FAIL)
            },
        ))))
        val out = drive(s, tl(0.0, 1.0))
        assertEquals(0, out.say.size)
        assertTrue(blocked(out, "t1c.praise", BlockReason.AFTER))
        assertTrue(blocked(out, "t1c.fix", BlockReason.OFF))
        assertEquals("막힌 말도 문장이 있음", "좋았어.", out.blocked[0].utterance.text)
        assertEquals(listOf("C2" to EventResult.OK, "C4" to EventResult.FAIL), out.events.map { it.item to it.result })
        val rec = s.finish(arrived = true)
        assertEquals(listOf(Triple("t1c.praise", false, BlockReason.AFTER), Triple("t1c.fix", false, BlockReason.OFF)),
            rec.utterances.map { Triple(it.key, it.spoken, it.reason) })
    }

    @Test
    fun rule7GapTwentySecondsAfterTheLastLineEnds() {
        val s = sess(cfg(), listOf(script(listOf(
            sayAction(1.0, "t1c.praise", "C2", Category.PRAISE), sayAction(2.0, "t1c.info", "A1", Category.INFO),
            sayAction(3.0, "t1c.praise2", "C2", Category.PRAISE),
        ))))
        val out = drive(s, tl(0.0, 40.0))
        val end1 = 1 + BaroEngine.speakDur("좋았어.")
        assertEquals(listOf(1.0), out.sayAt("t1c.praise"))
        val t2 = out.sayAt("t1c.info")[0]
        assertTrue("두 번째 말 $t2", t2 >= end1 + 20 - eps && t2 < end1 + 20 + 0.1 + eps)
        assertTrue("칭찬은 8초 뒤 만료(gap)", blocked(out, "t1c.praise2", BlockReason.GAP))
        assertEquals(11.0, out.blockedOf("t1c.praise2")[0].utterance.t, 1e-9)
    }

    @Test
    fun rule7ExceptionSameTurnC1C2PairSkipsTheGap() {
        fun run(pair2: String): Drive = drive(sess(cfg(), listOf(script(listOf(
            sayAction(1.0, "t1c.pre", "C1", Category.PRE, SayOpts(pairWith = "tx")),
            sayAction(1.5, "t1c.praise", "C2", Category.PRAISE, SayOpts(pairWith = pair2)),
        )))), tl(0.0, 12.0))
        var out = run("tx")
        val end1 = 1 + BaroEngine.speakDur("미리 켜 볼까?")
        assertEquals(listOf(1.0), out.sayAt("t1c.pre"))
        val t2 = out.sayAt("t1c.praise")[0]
        assertTrue("C1이 끝나자마자 C2 $t2", t2 >= end1 - eps && t2 < end1 + 0.1 + eps)
        out = run("ty")
        assertFalse(said(out, "t1c.praise"))
        assertTrue(blocked(out, "t1c.praise", BlockReason.GAP))
    }

    @Test
    fun safetyIgnoresGapAndCapAndAnswerIgnoresGap() {
        val out = drive(sess(cfg(), listOf(script(listOf(
            sayAction(1.0, "t1c.info", "A1", Category.INFO), sayAction(1.2, "t1c.safety", "E1", Category.SAFETY),
            sayAction(4.0, "t1c.info2", "K8", Category.INFO, SayOpts(answer = true)),
        )))), tl(0.0, 8.0))
        val end1 = 1 + BaroEngine.speakDur("벨트 매자.")
        val ts = out.sayAt("t1c.safety")[0]
        assertTrue("안전은 앞 말이 끝나자마자 $ts", ts >= end1 - eps && ts < end1 + 0.1 + eps)
        assertEquals("답은 간격 예외", listOf(4.0), out.sayAt("t1c.info2"))
    }

    @Test
    fun rule8CapSixInTenMinutesSafetyPassesButCounts() {
        val list = ArrayList<Action>()
        for (i in 0 until 6) list += sayAction(1.0 + i * 25, if (i % 2 != 0) "t1c.info" else "t1c.info2", "A1", Category.INFO)
        list += sayAction(151.0, "t1c.praise", "A1", Category.PRAISE)
        list += sayAction(160.0, "t1c.safety", "E2", Category.SAFETY)
        list += sayAction(300.0, "t1c.praise2", "A1", Category.PRAISE)
        list += sayAction(602.0, "t1c.fix", "A1", Category.INFO, SayOpts(expiresIn = 5.0))
        list += sayAction(627.0, "t1c.fix2", "A1", Category.INFO)
        val out = drive(sess(cfg(), listOf(script(list))), tl(0.0, 640.0))
        assertEquals(6, out.say.count { it.category == Category.INFO && it.t < 600 })
        assertTrue("7번째 막힘", blocked(out, "t1c.praise", BlockReason.CAP))
        assertEquals("안전은 상한에 막히지 않음", listOf(160.0), out.sayAt("t1c.safety"))
        assertTrue("안전도 수에 들어가 아직 7번", blocked(out, "t1c.praise2", BlockReason.CAP))
        assertTrue("602초: 아직 6번", blocked(out, "t1c.fix", BlockReason.CAP))
        assertEquals("627초: 다시 말함", listOf(627.0), out.sayAt("t1c.fix2"))
    }

    @Test
    fun rule9RatioFixesNeedPraise() {
        val s = sess(cfg(), listOf(script(listOf(
            sayAction(1.0, "t1c.fix", "C4", Category.FIX), sayAction(30.0, "t1c.fix2", "C4", Category.FIX),
            sayAction(60.0, "t1c.praise", "C2", Category.PRAISE), sayAction(85.0, "t1c.fix", "C4", Category.FIX),
            sayAction(110.0, "t1c.praise2", "C2", Category.PRAISE), sayAction(135.0, "t1c.fix2", "C4", Category.FIX),
        ))))
        val out = drive(s, tl(0.0, 160.0))
        assertEquals(listOf(1.0), out.sayAt("t1c.fix"))
        assertEquals(BlockReason.RATIO, out.blockedOf("t1c.fix2")[0].reason)
        assertEquals(BlockReason.RATIO, out.blockedOf("t1c.fix")[0].reason)
        assertEquals("칭찬 2번이면 교정 2번까지", listOf(135.0), out.sayAt("t1c.fix2"))
    }

    @Test
    fun rule10PickSafetyThenPreAndInfoThenPraiseThenFix() {
        val out = drive(sess(cfg(), listOf(script(listOf(
            sayAction(1.0, "t1c.fix", "C4", Category.FIX), sayAction(1.0, "t1c.praise", "C2", Category.PRAISE),
            sayAction(1.0, "t1c.info", "A1", Category.INFO), sayAction(1.0, "t1c.safety", "E1", Category.SAFETY),
            sayAction(1.5, "t1c.info2", "PB1", Category.INFO),
        )))), tl(0.0, 35.0))
        assertEquals(listOf("t1c.safety", "t1c.info"), out.say.map { it.key })
        assertTrue(blocked(out, "t1c.praise", BlockReason.GAP))
        assertTrue(blocked(out, "t1c.fix", BlockReason.GAP))
        assertTrue(blocked(out, "t1c.info2", BlockReason.GAP))
    }

    @Test
    fun rule4NavVoiceWaitsPlusOneSecondEvenForSafety() {
        val voice = { t: Double, s: VehicleSnapshot -> s.copy(nav = nav(voice = t >= 0.5 && t <= 3.0, status = com.barodrive.core.model.NavStatus.INACTIVE)) }
        val out = drive(sess(cfg(), listOf(script(listOf(sayAction(1.0, "t1c.info", "A1", Category.INFO))))), tl(0.0, 8.0, voice))
        assertEquals(listOf(4.0), out.sayAt("t1c.info"))
        val safety = drive(sess(cfg(), listOf(script(listOf(sayAction(1.0, "t1c.safety", "E1", Category.SAFETY, SayOpts(alive = { true })))))), tl(0.0, 8.0, voice))
        assertEquals("안전도 기다림(결정-27)", listOf(4.0), safety.sayAt("t1c.safety"))
    }

    @Test
    fun c1IsDroppedUnder50mAfterWaitingForNavVoice() {
        fun c1(voiceEnd: Double): Drive {
            val d = DetectorDef(id = "t1c.c1", items = listOf("C1")) { ctx ->
                ctx.on<TurnApproach> { a -> if (a.distance == 70.0) ctx.say("t1c.pre", item = "C1", category = Category.PRE, pairWith = a.id) }
                DetectorInstance.NONE
            }
            val snaps = tl(0.0, 11.0) { t, s ->
                val dd = 120 - Math.round(t * 10).toDouble()
                s.copy(speed = 36.0, nav = nav(tbt = if (dd > 0) listOf(tbt("t1", "TURN_RIGHT", dd, TurnDir.RIGHT)) else emptyList(), voice = t >= 4.5 && t <= voiceEnd))
            }
            return drive(sess(cfg(), listOf(d)), snaps)
        }
        var out = c1(7.0)
        assertFalse(said(out, "t1c.pre"))
        assertTrue(blocked(out, "t1c.pre", BlockReason.UNDER_50))
        out = c1(5.5)
        assertEquals(listOf(6.5), out.sayAt("t1c.pre"))
    }

    @Test
    fun rule5BigSteerSilencesEvenSafety() {
        val out = drive(sess(cfg(), listOf(script(listOf(
            sayAction(2.0, "t1c.info", "A1", Category.INFO), sayAction(2.0, "t1c.safety", "E1", Category.SAFETY, SayOpts(alive = { true })),
        )))), tl(0.0, 30.0) { t, s -> s.copy(steer = if (t >= 1 && t <= 4) 15.0 else 0.0, speed = 15.0) })
        assertEquals("조향이 10° 아래로 풀린 첫 스냅숏", listOf(4.1), out.sayAt("t1c.safety"))
        val ti = out.sayAt("t1c.info")[0]
        val want = 4.1 + BaroEngine.speakDur("조심하자.") + 20
        assertTrue("안내는 안전 말이 끝나고 20초 뒤 $ti", ti >= want - eps && ti < want + 0.1 + eps)
        assertEquals(0, out.steps.first { it.t == 3.0 }.say.size)
    }

    @Test
    fun rule5HardBrakeSilencesThirtySeconds() {
        val s = sess(cfg(), listOf(script(listOf(
            sayAction(3.0, "t1c.info", "A1", Category.INFO), sayAction(6.0, "t1c.safety", "E1", Category.SAFETY, SayOpts(alive = { true })),
        )), Recorder().def))
        val out = drive(s, hold(40.0, 2.0) + ramp(40.0, 0.0, 40.0 / 14, 2.0) + hold(0.0, 3.0, 4.9, brake = true) + hold(20.0, 40.0, 7.9))
        assertEquals(0, out.events.size)
        assertTrue("급제동 중 생긴 말은 침묵으로 막힘", blocked(out, "t1c.info", BlockReason.SILENCE))
        val ts = out.sayAt("t1c.safety")[0]
        assertTrue("안전은 침묵이 끝난 뒤 $ts", ts >= 35.9 - eps && ts <= 36.0 + eps)
    }

    @Test
    fun rule5MergeSilenceAfterIcEnterAndCtxSilence() {
        val d = DetectorDef(id = "t1c.merge") { ctx ->
            ctx.on<TurnPassed> { ctx.say("t1c.info", item = "A1", category = Category.INFO, expiresIn = 60.0) }
            DetectorInstance.NONE
        }
        val snaps = tl(0.0, 40.0) { t, s ->
            s.copy(speed = 72.0, nav = nav(tbt = if (t < 10) listOf(tbt("ic", "TURN_ENTER_IC_R", Math.round((10 - t) * 20).toDouble(), TurnDir.RIGHT)) else emptyList()))
        }
        assertEquals("진입(10초) 뒤 20초 침묵", listOf(30.0), drive(sess(cfg(), listOf(d)), snaps).sayAt("t1c.info"))
        val out2 = drive(sess(cfg(), listOf(script(listOf(Action(1.0) { ctx, _ -> ctx.silence(5.0, "test") }, sayAction(2.0, "t1c.info", "A1", Category.INFO))))), tl(0.0, 10.0))
        assertEquals(listOf(6.0), out2.sayAt("t1c.info"))
    }

    @Test
    fun rule6JamOnlySafetyAndPreAndExpiredFixBecomesACard() {
        val jam = { _: Double, s: VehicleSnapshot -> s.copy(nav = nav(congested = true, status = com.barodrive.core.model.NavStatus.INACTIVE)) }
        val s = sess(cfg(), listOf(script(listOf(
            sayAction(1.0, "t1c.praise", "C2", Category.PRAISE),
            sayAction(1.0, "t1c.fix", "C2", Category.FIX, SayOpts(cardable = true, card = CardSpec(title = "깜빡이가 늦었어요"))),
            sayAction(1.0, "t1c.safety", "E1", Category.SAFETY), sayAction(30.0, "t1c.pre", "C1", Category.PRE),
        ))))
        val jamTpl = snap(gear = Gear.D, nav = nav(congested = true, status = com.barodrive.core.model.NavStatus.INACTIVE))
        val out = drive(s, tl(0.0, 40.0, jam) + stopAt(40.0, jamTpl))
        assertEquals(listOf(1.0), out.sayAt("t1c.safety"))
        assertEquals(listOf(30.0), out.sayAt("t1c.pre"))
        assertTrue(blocked(out, "t1c.praise", BlockReason.JAM))
        assertTrue(blocked(out, "t1c.fix", BlockReason.JAM))
        assertEquals("만료된 교정은 다음 신호 대기 카드로", 1, out.cards.size)
        assertEquals("깜빡이가 늦었어요", out.cards[0].title)
        assertEquals("살살 해 보자.", out.cards[0].text)
    }

    @Test
    fun rule2MuteOnlySafetySpeaks() {
        val s = sess(cfg(), listOf(script(listOf(
            sayAction(1.0, "t1c.info", "A1", Category.INFO), sayAction(1.0, "t1c.safety", "E1", Category.SAFETY),
            sayAction(40.0, "t1c.info2", "A1", Category.INFO),
        ))))
        drive(s, tl(0.0, 0.5))
        assertTrue(s.input(SessionInput.Mute).muted)
        val out = drive(s, tl(0.5, 30.0))
        assertEquals(listOf(1.0), out.sayAt("t1c.safety"))
        assertTrue(blocked(out, "t1c.info", BlockReason.MUTED))
        s.input(SessionInput.Unmute)
        assertEquals(listOf(40.0), drive(s, tl(30.0, 45.0)).sayAt("t1c.info2"))
    }

    @Test
    fun linesInParkSkipDrivingRulesAndK6() {
        val s = sess(cfg(), listOf(script(listOf(sayAction(1.0, "t1c.info", "A1", Category.INFO), sayAction(4.0, "t1c.info2", "PB2", Category.INFO)))))
        val out = drive(s, tl(0.0, 3.0) + tl(3.0, 8.0) { _, sn -> sn.copy(gear = Gear.P, speed = 0.0, steer = 20.0) })
        assertEquals(listOf(4.0), out.sayAt("t1c.info2"))
        assertEquals("P에서 한 말은 K6에서 뺌", 1, s.finish(arrived = true).metrics.spoken)
    }

    @Test
    fun guideItemsSpeakDuringManoeuvres() {
        val s = sess(cfg(mode = 6), listOf(script(listOf(
            sayAction(1.0, "t1c.info", "PK1", Category.INFO), sayAction(3.0, "t1c.info2", "PK1", Category.INFO),
            sayAction(5.0, "t1c.fix", "PK2", Category.FIX), sayAction(7.0, "t1c.fix2", "PK2", Category.FIX),
        ))))
        val out = drive(s, tl(0.0, 10.0) { _, sn -> sn.copy(gear = Gear.R, speed = -4.0, steer = 34.0) })
        assertEquals(listOf("t1c.info" to 1.0, "t1c.info2" to 3.0, "t1c.fix" to 5.0, "t1c.fix2" to 7.0), out.say.map { it.key to it.t })
    }

    @Test
    fun expiryByCategoryAndDuplicateCandidates() {
        val s = sess(cfg(), listOf(script(listOf(
            sayAction(1.0, "t1c.info", "A1", Category.INFO), sayAction(2.0, "t1c.praise", "C2", Category.PRAISE),
            sayAction(2.0, "t1c.fix", "C4", Category.FIX),
            Action(2.0) { ctx, _ ->
                ctx.say("t1c.info2", item = "PB1")
                ctx.say("t1c.info2", item = "PB1")
            },
        ))))
        val out = drive(s, tl(0.0, 40.0))
        assertEquals(listOf(10.0 to BlockReason.GAP), out.blockedOf("t1c.praise").map { it.utterance.t to it.reason })
        assertEquals(listOf(17.0 to BlockReason.GAP), out.blockedOf("t1c.fix").map { it.utterance.t to it.reason })
        assertEquals("같은 키 후보는 하나만", 1, out.sayAt("t1c.info2").size)
        assertEquals("분류를 빼면 항목 기본 분류", Category.INFO, out.say.first { it.key == "t1c.info2" }.category)
        val o2 = drive(sess(cfg(), listOf(script(listOf(sayAction(1.0, "t1c.info", "A1", Category.INFO), sayAction(2.0, "t1c.info2", "A1", Category.INFO))))), tl(0.0, 40.0))
        val want = 1 + BaroEngine.speakDur("벨트 매자.") + 20
        val got = o2.sayAt("t1c.info2")[0]
        assertTrue("안내는 30초 안이면 기다렸다 말함 $got", got >= want - eps && got < want + 0.1 + eps)
    }

    @Test
    fun cardsMaxTwoSafetyThenFixThenTipAndUnshownFixesGoToTheReport() {
        val s = sess(cfg(items = mapOf("C4" to Delivery.NOW, "B1" to Delivery.NOW, "C5" to Delivery.NOW)), listOf(script(listOf(
            cardAction(1.0, "C4", "t1c.fix", "교정 A", CardCategory.FIX), cardAction(2.0, "B1", "t1c.fix2", "교정 B", CardCategory.FIX),
            cardAction(3.0, "J3", null, "요령", CardCategory.TIP, "브레이크는 일찍"), cardAction(4.0, null, "t1c.safety", "안전", CardCategory.SAFETY),
            cardAction(5.0, "C5", "t1c.fix", "교정 C", CardCategory.FIX),
        ))))
        var out = drive(s, tl(0.0, 6.0) + stopAt(6.0))
        assertEquals(listOf("안전", "교정 A"), out.cards.map { it.title })
        assertEquals("정지(11초) 1초 뒤 신호 대기", 12.0, out.steps.first { it.cards.isNotEmpty() }.t, 1e-9)
        assertEquals("보이는 카드는 매 출력에", 2, out.steps.last().cards.size)
        out = drive(s, ramp(0.0, 30.0, 3.0, 13.0) + stopAt(16.0))
        assertEquals("교정 B·C는 리포트로, 요령은 다음 대기에", listOf("요령"), out.steps.last().cards.map { it.title })
    }

    @Test
    fun onlyTheFrontCardSpeaksAndBlockedSpeechStillShowsTheCard() {
        val s = sess(cfg(), listOf(script(listOf(
            sayAction(1.0, "t1c.fix2", "C4", Category.FIX), cardAction(25.0, "C4", "t1c.fix", "첫째", CardCategory.FIX),
            cardAction(26.0, "F1", "t1c.praise", "둘째", CardCategory.FIX),
        ))))
        val out = drive(s, tl(0.0, 27.0) + stopAt(27.0))
        assertEquals(listOf("첫째", "둘째"), out.cards.map { it.title })
        assertEquals("살살 해 보자.", out.cards[0].text)
        assertFalse(said(out, "t1c.fix"))
        assertFalse("둘째 카드는 말하지 않음", said(out, "t1c.praise"))
        assertEquals("말이 막혀도 카드는 보임", 2, out.steps.last().cards.size)
        val o2 = drive(sess(cfg(), listOf(script(listOf(
            cardAction(1.0, "C4", "t1c.fix", "C4", CardCategory.FIX), cardAction(1.5, null, "t1c.safety", "동승자", CardCategory.SAFETY),
        )))), tl(0.0, 2.0) + stopAt(2.0))
        assertEquals("안전 카드면 안전 분류로 말함", listOf(Triple("t1c.safety", Category.SAFETY, 8.0)), o2.say.map { Triple(it.key, it.category, it.t) })
    }

    @Test
    fun cardsCloseWhenMovingTipsAreSilentAndLeftoversGoToTheReport() {
        val s = sess(cfg(), listOf(script(listOf(
            cardAction(1.0, "J3", null, "요령", CardCategory.TIP, "앞차와 거리를 넓혀요"), cardAction(1.0, "C4", "t1c.fix", "교정", CardCategory.FIX),
        ))))
        var out = drive(s, tl(0.0, 2.0) + stopAt(2.0))
        assertEquals(2, out.steps.last().cards.size)
        out = drive(s, ramp(0.0, 10.0, 1.0, 9.0))
        assertEquals("속도가 0을 넘으면 닫힘", 0, out.steps[0].cards.size)
        out = drive(s, ramp(10.0, 0.0, 2.0, 10.0) + hold(0.0, 2.0, 12.0, brake = true))
        assertEquals("다시 보이지 않음", 0, out.cards.size)
        assertEquals(0, s.cards.size)
        val o2 = drive(sess(cfg(), listOf(script(listOf(cardAction(1.0, "J3", null, "요령", CardCategory.TIP))))), tl(0.0, 2.0) + stopAt(2.0))
        assertEquals(CardCategory.TIP, o2.cards[0].category)
        assertEquals(0, o2.say.size)
        val s3 = sess(cfg(), listOf(script(listOf(cardAction(1.0, "C4", "t1c.fix", "남음", CardCategory.FIX)))))
        drive(s3, tl(0.0, 3.0))
        s3.finish(arrived = true)
        assertEquals(0, s3.cards.size)
    }

    @Test
    fun cardsForNonNowItemsAreHiddenAndLoggedAsBlocked() {
        val s = sess(cfg(items = mapOf("B1" to Delivery.AFTER, "C5" to Delivery.OFF)), listOf(script(listOf(
            cardAction(1.0, "B1", "t1c.fix", "B1", CardCategory.FIX), cardAction(1.0, "C5", "t1c.fix2", "C5", CardCategory.FIX),
        ))))
        val out = drive(s, tl(0.0, 2.0) + stopAt(2.0))
        assertEquals(0, out.cards.size)
        assertTrue(blocked(out, "t1c.fix", BlockReason.AFTER))
        assertTrue(blocked(out, "t1c.fix2", BlockReason.OFF))
    }

    @Test
    fun praiseCardOnlyWithoutFixAndOnlyTheLatest() {
        fun run(list: List<Action>) = drive(sess(cfg(), listOf(script(list))), tl(0.0, 4.0) + stopAt(4.0))
        var out = run(listOf(cardAction(1.0, "C2", "t1c.praise", "칭찬 1", CardCategory.PRAISE), cardAction(2.0, "C2", "t1c.praise2", "칭찬 2", CardCategory.PRAISE)))
        assertEquals(listOf("칭찬 2"), out.cards.map { it.title })
        assertEquals(listOf("t1c.praise2" to Category.PRAISE), out.say.map { it.key to it.category })
        out = run(listOf(cardAction(1.0, "C2", "t1c.praise", "칭찬", CardCategory.PRAISE), cardAction(2.0, "C4", "t1c.fix", "교정", CardCategory.FIX)))
        assertEquals(listOf("교정"), out.cards.map { it.title })
    }

    @Test
    fun brakeEndCardShowsAtTheSameSnapshotAsTheWait() {
        val f1 = DetectorDef(id = "t1c.f1", items = listOf("F1")) { ctx ->
            ctx.on<BrakeEndEvent> { b ->
                if (b.eval && b.maxDrop1s >= ctx.th("F1.drop")) {
                    ctx.card(item = "F1", key = "t1c.fix", icon = "i-brake", title = "조금 급하게 섰어요", detail = "${num(b.v0)}km/h에서 ${num(b.dur)}초 만에 정지")
                }
            }
            DetectorInstance.NONE
        }
        val out = drive(sess(cfg(), listOf(f1)), hold(28.0, 1.0) + ramp(28.0, 0.0, 2.8, 1.0) + hold(0.0, 1.5, 3.8, brake = true))
        val first = out.steps.first { it.cards.isNotEmpty() }
        assertEquals(4.8, first.t, 1e-9)
        assertTrue(first.waiting)
        assertEquals("28km/h에서 2.8초 만에 정지", out.cards[0].detail)
        assertEquals("i-brake", out.cards[0].icon)
        assertEquals(listOf(4.8), out.sayAt("t1c.fix"))
    }
}
