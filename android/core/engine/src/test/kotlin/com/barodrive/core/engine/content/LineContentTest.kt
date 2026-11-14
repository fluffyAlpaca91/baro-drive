package com.barodrive.core.engine.content

import com.barodrive.core.model.Coach
import com.barodrive.core.model.Honorific
import com.barodrive.core.model.HonorificType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱 대사(resources/content/lines)가 설계 6.6.2·6.6.3 표 그대로인지 — 프로토타입 테스트의 대사 단언을 같은 입력·같은 기대 글로 옮겼다
 * (t2a_detectors · t3_safety3 · t3_stage · t6_hill · t5b_guide · t7_weather · t8_license · t2b_screens · t2c_session ·
 * t2d_review · t4_score · t10_presets).
 */
class LineContentTest {
    private val none = Honorific(HonorificType.NONE)
    private val daughter = Honorific(HonorificType.DAUGHTER)
    private val son = Honorific(HonorificType.SON)
    private val coaches = listOf(Coach.SIB, Coach.MOM, Coach.DAD)

    /** 프로토타입 테스트의 `line(key, coach, vars, hon)` = renderLine(…, { peek: true }). */
    private fun line(key: String, coach: Coach, vars: Map<String, Any?> = emptyMap(), hon: Honorific? = none) =
        Lines.render(key, coach, vars, hon, peek = true)

    /** `{ index: i }`로 그리기. */
    private fun at(key: String, coach: Coach, vars: Map<String, Any?> = emptyMap(), hon: Honorific? = null, index: Int = 0) =
        Lines.render(key, coach, vars, hon, index = index)

    private fun assertRendersCleanly(key: String, coach: Coach, text: String) =
        assertTrue("$key $coach → $text", text.length >= 4 && '{' !in text && '}' !in text)

    @Test
    fun drivingLinesT2a() {
        val keys = listOf("a1", "pb1.on", "b1.fix", "b1.ok", "c1", "c2.ok", "c2.almost", "c2.late", "c4.fix", "c5.fix", "f1.fix", "f1.ok", "e1", "e1.wx", "e2", "pb2")
        val vars = mapOf("N" to 55, "T" to 40, "L" to 30, "dir" to "R", "wx" to "rain")
        for (k in keys) for (c in coaches) {
            val list = Lines.sentences(k, c)
            assertTrue("$k $c", list.isNotEmpty() && list.all { it.isNotBlank() })
            list.indices.forEach { i -> assertRendersCleanly(k, c, at(k, c, vars, daughter, i)) }
        }
        assertEquals("{N} 10m 내림", "좋았어, 50미터 전에 켰어.", at("c2.ok", Coach.DAD, mapOf("N" to 57)))
        assertEquals("어머 잘했다 우리 딸! 딱 좋았어!", at("c2.ok", Coach.MOM, mapOf("N" to 55), daughter, index = 1))
        assertEquals("왼쪽 깜빡이 미리 켜 볼까?", at("c1", Coach.DAD, mapOf("dir" to "L")))
        assertEquals("비 오는 날은 40까지야, 조금 줄이자.", at("e1.wx", Coach.DAD, mapOf("wx" to "rain", "L" to 40)))
        assertEquals("야, 지금 켜면 늦어. 다음엔 110미터 전에 켜라.", at("c2.late", Coach.SIB, mapOf("T" to 110)))
        assertEquals("c2.ok만 문장이 둘(번갈아 쓰기)", listOf("c2.ok"), Lines.keys.filter { k -> coaches.any { Lines.sentences(k, it).size > 1 } })
    }

    @Test
    fun safety3StageAndGraduationLinesT3() {
        for (k in listOf("c3", "c6", "j1", "rv.pass.1", "rv.pass.2", "rv.pass.3", "rv.grad")) for (c in coaches) {
            assertTrue("$k $c", Lines.sentences(k, c).let { l -> l.isNotEmpty() && l.all { it.isNotBlank() } })
            for (hon in listOf(none, daughter)) assertRendersCleanly(k, c, at(k, c, mapOf("dir" to "R"), hon))
        }
        assertEquals("오른쪽으로 가는 길이야. 깜빡이 바꿔 보자.", at("c3", Coach.DAD, mapOf("dir" to "R")))
        assertEquals("어머, 반대! 왼쪽으로 가는 길이야!", at("c3", Coach.MOM, mapOf("dir" to "L")))
        assertEquals("반대야. 왼쪽으로 가는 길이야.", at("c3", Coach.SIB, mapOf("dir" to "L")))
        assertEquals("깜빡이가 아직 켜져 있어. 꺼 볼까?", at("c6", Coach.DAD))
        assertEquals("깜빡이 아직 켜져 있다. 꺼.", at("c6", Coach.SIB))
        assertEquals("어머, 깜깜해! 전조등 켜자!", at("j1", Coach.MOM))
        assertEquals("전조등이 꺼져 있어. 켜 볼까?", at("j1", Coach.DAD))
        assertEquals("목업 LINES.pass", "1단계 통과 축하해. 골목도 지금처럼 천천히 가 보자.", at("rv.pass.1", Coach.DAD))
        assertEquals("2단계도 통과네. 큰길에선 제한속도 지켜.", at("rv.pass.2", Coach.SIB))
        assertEquals("어머 3단계도 통과야! 고속도로는 깜빡이 일찍!", at("rv.pass.3", Coach.MOM))
        // 호칭(설계 6.6.1): 졸업 대사는 엄마·아빠/배우자만 부르고 형제/자매는 안 부름
        assertEquals("4단계까지 다 마쳤어 우리 딸! 정말 고생 많았어.", at("rv.grad", Coach.DAD, hon = daughter))
        assertEquals("4단계까지 다 마쳤어! 정말 고생 많았어.", at("rv.grad", Coach.DAD))
        assertEquals("세상에, 다 끝냈어 지은아! 너무 대견해!", at("rv.grad", Coach.MOM, hon = Honorific(HonorificType.CUSTOM, "지은아")))
        assertEquals("다 했네. 이제 혼자서도 되겠다.", at("rv.grad", Coach.SIB, hon = son))
    }

    @Test
    fun hillLinesT6() {
        val steps = listOf("hl1.up.1", "hl1.up.2", "hl1.up.3", "hl1.up.4", "hl1.down.1", "hl1.down.2", "hl1.down.3", "hl1.park.1", "hl1.park.2", "hl1.park.3")
        for (k in listOf("hl2", "hl3.ok", "hl3.wrong", "hl1.cheer", "pb2.hill", "p.hill", "rv.hill") + steps) for (c in coaches) {
            assertTrue("$k $c", Lines.sentences(k, c).let { l -> l.isNotEmpty() && l.all { it.isNotBlank() } })
        }
        for (k in steps) assertEquals("$k 세 코치 같은 문장", 1, coaches.map { Lines.sentences(k, it)[0] }.toSet().size)
        // 표 A-8 그대로
        assertEquals("브레이크에서 엑셀로 발을 빠르게 옮기고, 엑셀을 살살 밟으세요.", Lines.sentences("hl1.up.3", Coach.DAD)[0])
        assertEquals("브레이크를 계속 밟지 말고 여러 번 나눠 밟으세요.", Lines.sentences("hl1.down.2", Coach.MOM)[0])
        // 설계 6.6.2 아래 줄의 예: 언덕 주차 2단계는 고른 상황의 방향을 넣는다
        assertEquals("브레이크를 밟은 채 앞바퀴를 연석 쪽(오른쪽)으로 돌리세요.", at("hl1.park.2", Coach.SIB, mapOf("side" to "연석 쪽(오른쪽)")))
        assertEquals("앞바퀴를 오른쪽으로 돌려 보자.", at("hl3.wrong", Coach.DAD, mapOf("dir" to "R")))
        assertEquals("바퀴 왼쪽으로 돌리자!", at("hl3.wrong", Coach.MOM, mapOf("dir" to "L")))
        assertEquals("언덕에선 사이드브레이크 꼭 채우자.", at("pb2.hill", Coach.DAD))
        // rv.hill: 과제 자리 + 호칭(엄마)
        assertEquals("어머 언덕도 해냈어 우리 딸! 다음엔 오르막 출발만 천천히!", at("rv.hill", Coach.MOM, mapOf("taskMom" to "오르막 출발만 천천히"), daughter))
        assertEquals("어머 언덕도 해냈어! 다음엔 언덕 주차만 꼼꼼히!", at("rv.hill", Coach.MOM, mapOf("taskMom" to "언덕 주차만 꼼꼼히"), none))
        assertEquals("언덕 연습 고생했어. 다음엔 내리막 브레이크 하나만 해 보자.", at("rv.hill", Coach.DAD, mapOf("taskDad" to "내리막 브레이크")))
    }

    @Test
    fun parkingLinesT5b() {
        fun all(key: String) = coaches.map { line(key, it, hon = daughter) }
        assertEquals(listOf("천천히.", "천천히, 천천히!", "천천히."), all("pk2"))
        assertEquals(listOf("바퀴 똑바로 해 둬. 다음 출발이 편해.", "바퀴 똑바로! 다음에 출발하기 편해!", "바퀴를 똑바로 두면 다음 출발이 편해."), all("pk3"))
        assertEquals(listOf("주차 끝. 생각보다 잘하네.", "어머 주차 성공이야! 대단해!", "주차 잘 마쳤어. 고생했어."), all("pk.done"))
        assertEquals(
            "주차 단계 문장은 단계 표에서 만든다(8 + 7 + 5)",
            20,
            Lines.keys.count { it.startsWith("pk1.") },
        )
    }

    @Test
    fun weatherLinesT7() {
        fun l(key: String, coach: Coach = Coach.DAD, vars: Map<String, Any?> = emptyMap()) = Lines.render(key, coach, vars, none, peek = true, index = 0)
        val spoken = mapOf(
            "j3.rain" to listOf("비 온다. 평소보다 천천히 가.", "어머 비 와! 평소보다 천천히 가자, 응?", "비가 오네. 평소보다 천천히 가 보자."),
            "j3.snow" to listOf("눈길이다. 살살, 천천히 가.", "어머 눈길이야! 살살 천천히 가자!", "눈길이야. 살살 천천히 가 보자."),
            "j3.cold" to listOf("길 얼었을 수 있어. 천천히 가.", "어머 추워라! 길 얼었을 수 있어, 천천히!", "길이 얼었을 수 있어. 천천히 가 보자."),
            "j3.slip" to listOf("미끄러운 길이다. 살살.", "어머 미끄러운 길이래! 살살!", "미끄러운 길이야. 살살 가자."),
        )
        for ((k, want) in spoken) coaches.forEachIndexed { i, c -> assertEquals("$k $c", want[i], l(k, c)) }
        val rain = listOf(
            "평소보다 속도를 줄여요.", "앞차와 거리를 평소보다 넓혀요.", "브레이크는 일찍, 여러 번 나눠 밟아요.",
            "엑셀에서 발을 일찍 떼고 엔진브레이크를 같이 써요.", "물웅덩이 앞에서는 미리 속도를 줄여요.",
        )
        val snow = listOf(
            "엑셀을 아주 살살 밟아 출발해요. 스노 모드가 있으면 켜요.", "앞차와 거리를 평소의 2배 이상 둬요.",
            "엔진브레이크로 천천히 속도를 줄여요. 급브레이크를 밟으면 바퀴가 잠겨 미끄러져요.", "핸들은 급하게 돌리지 말고 천천히 돌려요.",
            "내리막 앞에서는 기어를 낮추고 미리 속도를 줄여요.",
        )
        for (c in coaches) {
            rain.forEachIndexed { i, t -> assertEquals(t, l("j3.tip.rain.${i + 1}", c)) }
            snow.forEachIndexed { i, t -> assertEquals(t, l("j3.tip.snow.${i + 1}", c)) }
        }
        assertEquals("목업 s05 WX.rain.tip", "빗길 회차예요. 빗길에선 브레이크를 일찍, 나눠서 밟아요.", l("j3.review.rain"))
        assertEquals("목업 s05 WX.snow.tip", "눈길 회차예요. 눈길은 엔진브레이크로 천천히 속도를 줄여요.", l("j3.review.snow"))
        assertEquals("아주 나쁜 날 회차예요. 제한속도 기준을 50% 낮춰서 봤어요.", l("j3.review.severe"))
        assertEquals("아주 나쁜 날이라 제한속도 기준을 50% 낮춰서 봤어요.", l("j3.review.severe50"))
        // t10_presets: 리뷰 팁 줄은 코치·호칭과 상관없이 같은 글
        assertEquals(l("j3.review.rain"), Lines.render("j3.review.rain", Coach.MOM, emptyMap(), daughter, peek = true, index = 0))
        // e1.wx(T2a)의 {wx} 자리: 비·눈·아주 나쁜 날
        assertEquals("비 오는 날은 40까지야, 조금 줄이자.", l("e1.wx", Coach.DAD, mapOf("wx" to "rain", "L" to 40)))
        assertEquals("오늘 같은 날은 25까지다. 발 떼.", l("e1.wx", Coach.SIB, mapOf("wx" to "severe", "L" to 25)))
    }

    @Test
    fun licenseLineT8() {
        val want = listOf("조수석이 비었어. 혼자 연습하면 안 돼.", "어머, 옆자리가 비었어! 혼자 연습하면 안 돼!", "조수석이 비었어. 동승자 없이 연습하면 안 돼.")
        coaches.forEachIndexed { i, c -> assertEquals(c.name, want[i], at("lic.alone", c, hon = daughter)) }
    }

    @Test
    fun coachAndCourseLinesT2b() {
        val vars = mapOf("co.preview" to mapOf("T" to 40), "p.course" to mapOf("X" to "B"))
        for (k in listOf("co.preview", "co.honor", "p.course", "p.course.none")) for (c in coaches) for (hon in listOf(none, daughter)) {
            assertRendersCleanly(k, c, line(k, c, vars[k].orEmpty(), hon))
        }
        assertEquals("지금은 B 코스가 덜 막혀.", line("p.course", Coach.DAD, mapOf("X" to "B")))
        assertEquals("어머, 지금은 C 코스가 덜 막힌대!", line("p.course", Coach.MOM, mapOf("X" to "C")))
        assertEquals("이 근처엔 아직 코스가 없어. 연습한 길을 내 코스로 저장해 보자.", line("p.course.none", Coach.DAD))
        assertEquals("야, 지금 켜면 늦어. 다음엔 40미터 전에 켜라.", line("co.preview", Coach.SIB, mapOf("T" to 40)))
        // co.preview는 c2.late와 같은 문장({T} = 40, 설계 6.6.3)
        for (c in coaches) assertEquals(c.name, line("c2.late", c, mapOf("T" to 40)), line("co.preview", c, mapOf("T" to 40)))
        // 호칭 미리보기(co.honor): 엄마는 칭찬에, 아빠는 졸업 문장에 넣고, 형제/자매는 넣지 않는다(6.6.1)
        assertEquals("어머 잘했다 우리 딸! 딱 좋았어!", line("co.honor", Coach.MOM, hon = daughter))
        assertEquals("어머 잘했다! 딱 좋았어!", line("co.honor", Coach.MOM, hon = none))
        assertEquals("어머 잘했다 지은아! 딱 좋았어!", line("co.honor", Coach.MOM, hon = Honorific(HonorificType.CUSTOM, "  지은아 ")))
        assertEquals("빈 직접 쓰기 = 부르지 않기", "어머 잘했다! 딱 좋았어!", line("co.honor", Coach.MOM, hon = Honorific(HonorificType.CUSTOM, "")))
        assertEquals("4단계까지 다 마쳤어 우리 아들! 정말 고생 많았어.", line("co.honor", Coach.DAD, hon = son))
        assertEquals("오, 웬일. 잘했네.", line("co.honor", Coach.SIB, hon = daughter))
        assertEquals("괜찮아, 다음엔 40미터쯤 전에 켜 보자.", line("co.preview", Coach.DAD, mapOf("T" to 40), null))
    }

    @Test
    fun predriveLinesT2c() {
        val v = mapOf("nWord" to Words.numWord(3), "list" to "엑셀 살살 밟아서 출발하기, 브레이크 부드럽게 밟아서 멈추기, 핸들 돌리기 전에 깜빡이 켜기")
        for (k in listOf("l1", "pb1.ask", "p.tasks")) for (c in coaches) assertRendersCleanly(k, c, line(k, c, v))
        assertEquals("오늘 과제는 세 가지야. ${v["list"]}.", line("p.tasks", Coach.DAD, v))
        assertEquals("오늘 할 거 세 개. ${v["list"]}.", line("p.tasks", Coach.SIB, v))
        assertEquals("오늘 과제는 세 개야! ${v["list"]}. 할 수 있지?", line("p.tasks", Coach.MOM, v))
        assertEquals("조수석에 계신 분은 위험할 때만 말씀해 주세요. 칭찬과 지적은 제가 할게요.", line("l1", Coach.DAD))
        assertEquals("사이드브레이크 풀었어? 확인!", line("pb1.ask", Coach.MOM))
    }

    /** 과제 문구 자리 변수(리뷰 화면 taskVars와 같은 모양): 대사 rv.task.<자리>의 코치별 글. */
    private fun taskVars(slot: String) = mapOf(
        "taskSib" to Lines.sentences("rv.task.$slot", Coach.SIB)[0],
        "taskMom" to Lines.sentences("rv.task.$slot", Coach.MOM)[0],
        "taskDad" to Lines.sentences("rv.task.$slot", Coach.DAD)[0],
    )

    @Test
    fun reviewEndLinesT2d() {
        val v = taskVars("z30")
        assertEquals(mapOf("taskSib" to "30 구역이나 지켜", "taskMom" to "30 구역만 지키자", "taskDad" to "30 구역"), v)
        val want = mapOf(
            "rv.end.good" to listOf("뭐, 오늘은 봐줄 만했다. 다음엔 30 구역이나 지켜.", "세상에 오늘 너무 잘했어! 다음엔 30 구역만 지키자!", "오늘 고생 많았어. 다음엔 30 구역 하나만 신경 써 보자."),
            "rv.end.ok" to listOf("음, 그럭저럭이네. 다음엔 30 구역이나 지켜.", "어머 오늘도 고생했어! 다음엔 30 구역만 지키자!", "오늘도 수고했어. 다음엔 30 구역 하나만 신경 써 보자."),
            "rv.end.hard" to listOf(
                "오늘은 좀 힘들었지? 그래도 끝까지 했네. 다음엔 30 구역이나 지켜.", "아이고 오늘 힘들었지? 괜찮아, 다음엔 30 구역만 지키자!",
                "오늘 쉽지 않았지? 잘 버텼어. 다음엔 30 구역 하나만 해 보자.",
            ),
        )
        for ((k, texts) in want) coaches.forEachIndexed { i, c -> assertEquals("$k $c", texts[i], line(k, c, v)) }
        assertEquals("세상에 오늘 너무 잘했어 우리 딸! 다음엔 30 구역만 지키자!", line("rv.end.good", Coach.MOM, v, daughter))
        assertEquals("어머 오늘도 고생했어 지은아! 다음엔 30 구역만 지키자!", line("rv.end.ok", Coach.MOM, v, Honorific(HonorificType.CUSTOM, "지은아")))
        assertEquals("형제/자매는 호칭을 안 씀", want.getValue("rv.end.good")[0], line("rv.end.good", Coach.SIB, v, daughter))
        // 다음 과제가 없는 회차(결정사항 E T2d-2)
        assertEquals("뭐, 오늘도 끝까지 했네.", line("rv.end.none", Coach.SIB, hon = son))
        assertEquals("어머 오늘도 고생했어!", line("rv.end.none", Coach.MOM))
        assertEquals("오늘도 수고 많았어 우리 아들! 다음에 또 해 보자.", line("rv.end.none", Coach.DAD, hon = son))
        assertEquals("오늘도 수고 많았어! 다음에 또 해 보자.", line("rv.end.none", Coach.DAD))
        // 리뷰 히어로 문장(RV-1): 목업 s05 문장, 언덕 회차 끝, 지금 코치·호칭
        assertEquals("오늘 고생 많았어. 다음엔 출발 하나만 신경 써 보자.", line("rv.end.good", Coach.DAD, taskVars("start")))
        assertEquals("언덕 연습 고생했어. 다음엔 오르막 출발 하나만 해 보자.", line("rv.hill", Coach.DAD, taskVars("hillup")))
        assertEquals("세상에 오늘 너무 잘했어 우리 딸! 다음엔 출발만 조금 살살!", line("rv.end.good", Coach.MOM, taskVars("start"), daughter))
    }

    @Test
    fun taskSlotTableT2d() {
        val table = mapOf(
            "start" to listOf("출발이나 살살 해", "출발만 조금 살살", "출발"),
            "stop" to listOf("브레이크나 미리 살살 밟아", "브레이크만 미리 살살", "멈출 때 브레이크"),
            "signal" to listOf("깜빡이나 일찍 켜", "깜빡이만 조금 일찍", "깜빡이"),
            "wait3" to listOf("깜빡이 켜고 셋은 세고 꺾어", "깜빡이 켜고 셋만 세자", "깜빡이 켜고 셋 세기"),
            "z30" to listOf("30 구역이나 지켜", "30 구역만 지키자", "30 구역"),
            "limit" to listOf("제한속도나 지켜", "제한속도만 지키자", "제한속도"),
            "school" to listOf("어린이보호구역에선 30 아래로 가", "어린이보호구역만 조심하자", "어린이보호구역"),
            "nobrake" to listOf("브레이크나 미리 살살 밟아", "브레이크만 미리 살살", "갑자기 밟지 않기"),
            "hillup" to listOf("오르막에선 발 빨리 옮겨", "오르막 출발만 천천히", "오르막 출발"),
            "hilldown" to listOf("내리막에선 나눠 밟아", "내리막 브레이크만 나눠서", "내리막 브레이크"),
            "hillpark" to listOf("언덕 주차 바퀴나 맞춰", "언덕 주차만 꼼꼼히", "언덕 주차"),
        )
        for ((slot, texts) in table) coaches.forEachIndexed { i, c -> assertEquals("$slot $c", texts[i], line("rv.task.$slot", c)) }
        assertEquals(table.keys, Lines.keys.filter { it.startsWith("rv.task.") }.map { it.removePrefix("rv.task.") }.toSet())
    }

    @Test
    fun scoreLinesT4() {
        val fixes = listOf("C2", "C4", "C5", "BF", "E1", "G1")
        for (k in fixes) for (c in coaches) {
            val l = Lines.sentences("rv.score.fix.$k", c)
            assertTrue("$k $c", l.size == 1 && l[0].isNotBlank())
        }
        /** score.js `line(sc, { dest })`의 변수: 감점 문구는 rv.score.fix.<곳>의 코치별 첫 글. */
        fun scoreVars(s: Int, fix: String, dest: String = "마트") = mapOf(
            "S" to s, "dest" to dest,
            "fixSib" to Lines.sentences("rv.score.fix.$fix", Coach.SIB)[0],
            "fixMom" to Lines.sentences("rv.score.fix.$fix", Coach.MOM)[0],
            "fixDad" to Lines.sentences("rv.score.fix.$fix", Coach.DAD)[0],
        )
        for (k in listOf("rv.score.hi", "rv.score.mid", "rv.score.lo")) for (c in coaches) for (fix in fixes) for (hon in listOf(none, daughter)) {
            val t = line(k, c, scoreVars(86, fix), hon)
            assertTrue("$k $c $fix: $t", t.length > 4 && '{' !in t && '}' !in t)
        }
        val mid = scoreVars(86, "C2")
        assertEquals(
            mapOf("S" to 86, "dest" to "마트", "fixSib" to "깜빡이나 일찍 켜", "fixMom" to "깜빡이만 조금 일찍 켜자", "fixDad" to "깜빡이만 조금 일찍 켜 보자"),
            mid,
        )
        assertEquals("목업 s07", "마트까지 잘 왔어. 깜빡이만 조금 일찍 켜 보자.", line("rv.score.mid", Coach.DAD, mid))
        assertEquals("어머 86점이야! 깜빡이만 조금 일찍 켜자, 응?", line("rv.score.mid", Coach.MOM, mid))
        assertEquals("86점이면 봐줄 만하네. 깜빡이나 일찍 켜.", line("rv.score.mid", Coach.SIB, mid))
        assertEquals("오늘은 69점이야. 다음엔 깜빡이만 조금 일찍 켜 보자.", line("rv.score.lo", Coach.DAD, scoreVars(69, "C2", "회사")))
        assertEquals("100점이야. 마트까지 아주 안전하게 왔어.", line("rv.score.hi", Coach.DAD, scoreVars(100, "C2")))
        assertEquals("어머 100점! 너무 잘했어 우리 딸!", line("rv.score.hi", Coach.MOM, scoreVars(100, "C2"), daughter))
    }

    @Test
    fun driverQuestionAnswersK8() {
        // 설계 6.6.2 'k8.*' 표 그대로(프로토타입에는 이 대사만 따로 본 테스트가 없어 표 글로 확인)
        val want = mapOf(
            "k8.turn.ok" to listOf("방금 회전? 50미터 전에 켰어. 괜찮았어.", "방금 회전 좋았어! 50미터 전에 켰어!", "방금 회전은 50미터 전에 깜빡이를 켰어. 좋았어."),
            "k8.turn.late" to listOf("방금 깜빡이 좀 늦었어. 다음엔 일찍.", "방금 깜빡이가 조금 늦었어! 다음엔 일찍!", "방금은 깜빡이가 조금 늦었어. 다음엔 일찍 켜 보자."),
            "k8.stop.ok" to listOf("방금 정지 부드러웠어.", "방금 정말 부드럽게 섰어!", "방금 정지 부드러웠어."),
            "k8.stop.fix" to listOf("방금 좀 세게 섰어.", "방금 조금 세게 섰어, 살살!", "방금은 조금 급하게 섰어. 미리 살살 밟아 보자."),
            "k8.none" to listOf("아직 별일 없어.", "아직은 다 좋아!", "아직 알려 줄 게 없어. 잘하고 있어."),
        )
        for ((k, texts) in want) coaches.forEachIndexed { i, c -> assertEquals("$k $c", texts[i], line(k, c, mapOf("N" to 57), daughter)) }
    }
}
