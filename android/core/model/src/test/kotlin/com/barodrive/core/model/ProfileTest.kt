package com.barodrive.core.model

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    /** 설계 8.1 `baro.app.v1` 예를 프로토타입 JSON 모양 그대로(손으로 씀). */
    private val example = """
        {
          "version": 1, "activeProfileId": "p1", "ui": { "lastScreen": "HM-1", "lastParams": {} },
          "profiles": { "p1": {
            "id": "p1", "name": "운전자 1", "createdAt": "2026-11-10T09:00:00+09:00",
            "onboarding": { "done": true, "answers": { "licenseAge": "over3", "monthlyDrives": "1-3" }, "recommendedStage": 2 },
            "consent": { "record": true, "family": false, "academy": false, "insurer": false, "familyName": "가족" },
            "coach": "dad", "honorific": { "type": "none", "text": "" },
            "progress": { "currentStage": 2, "unlockedStage": 2, "graduatedAt": null,
              "stages": { "1": { "qualified": 2, "passedAt": "2026-11-05T19:00:00+09:00" }, "2": { "qualified": 1, "passedAt": null },
                          "3": { "qualified": 0, "passedAt": null }, "4": { "qualified": 0, "passedAt": null } } },
            "sessionCount": { "1": 3, "2": 2, "3": 0, "4": 0, "5": 0, "6": 0, "7": 0, "8": 0 },
            "feedback": { "overrides": { "2": { "G1": "X" } } },
            "fading": { "2": { "B1": { "streak": 2, "faded": true } } },
            "myPresets": [ { "id": "ex-commute", "name": "출퇴근용", "example": true, "items": { "J3": "S", "A1": "S" } } ],
            "lastPresetId": "ex-commute",
            "myCourses": [ { "id": "mc-1", "name": "우리 동네 한 바퀴", "mode": 2, "source": "saved", "courseRef": "c2-A" },
                           { "id": "mc-mart", "name": "마트", "mode": 3, "source": "bookmark", "placeId": "mart", "town": "home", "savedAt": "2026-11-09T10:00:00+09:00" } ],
            "parking": { "done": { "parallel-R": true } },
            "license": { "issueDate": "2026-04-01", "coursesDone": ["A", "B"] },
            "g1Baseline": 1.1,
            "firstMetrics": { "c2Avg": 38, "stopFailRate": 0.375, "g1": 1.1 },
            "sessions": [],
            "shareQueue": [ "sess-20261110-0912" ]
          } }
        }
    """.trimIndent()

    @Test
    fun decodesTheDesignExample() {
        val st = BaroJson.decodeFromString(AppState.serializer(), example)
        val p = st.activeProfile

        assertEquals("HM-1", st.ui.lastScreen)
        assertEquals(OnboardingAnswers(LicenseAge.OVER_3, MonthlyDrives.FEW), p.onboarding.answers)
        assertEquals(2, p.onboarding.recommendedStage)
        assertEquals(StageState(qualified = 2, passedAt = "2026-11-05T19:00:00+09:00"), p.progress.stages[1])
        assertEquals(2, p.sessionCount[2])
        assertEquals(3, p.nextNoInMode(2))
        assertEquals(mapOf("G1" to Delivery.OFF), p.feedback.overrides[2])
        assertEquals(FadeState(streak = 2, faded = true), p.fading[2]?.get("B1"))
        assertEquals(Delivery.AFTER, p.myPresets.single().items["J3"])
        assertEquals("c2-A", p.myCourses[0].courseRef)
        assertEquals(CourseSource.BOOKMARK, p.myCourses[1].source)
        assertEquals("mart", p.myCourses[1].placeId)
        assertEquals(true, p.parking.done["parallel-R"])
        assertEquals(LicenseInfo("2026-04-01", listOf("A", "B")), p.license)
        assertEquals(1.1, p.g1Baseline)
        assertEquals(FirstMetrics(c2Avg = 38.0, stopFailRate = 0.375, g1 = 1.1), p.firstMetrics)
        assertEquals(listOf("sess-20261110-0912"), p.shareQueue)
    }

    @Test
    fun roundTripsWithModeKeysAsStrings() {
        val st = BaroJson.decodeFromString(AppState.serializer(), example)

        val text = BaroJson.encodeToString(AppState.serializer(), st)
        val p1 = BaroJson.parseToJsonElement(text).jsonObject.getValue("profiles").jsonObject.getValue("p1").jsonObject

        assertEquals(st, BaroJson.decodeFromString(AppState.serializer(), text))
        assertEquals(setOf("1", "2", "3", "4", "5", "6", "7", "8"), p1.getValue("sessionCount").jsonObject.keys)
        assertEquals(setOf("2"), p1.getValue("fading").jsonObject.keys)
    }

    @Test
    fun defaultsMatchStoreDefaultProfile() {
        val p = Profile()

        assertEquals("p1", p.id)
        assertEquals("운전자 1", p.name)
        assertEquals(false, p.onboarding.done)
        assertEquals(Consent(), p.consent)
        assertEquals("가족", p.consent.familyName)
        assertEquals(Coach.DAD, p.coach)
        assertEquals(Honorific(HonorificType.NONE, ""), p.honorific)
        assertEquals(StageProgress(currentStage = 1, unlockedStage = 1), p.progress)
        assertEquals((1..4).toSet(), p.progress.stages.keys)
        assertEquals((1..8).associateWith { 0 }, p.sessionCount)
        assertTrue(p.feedback.overrides.isEmpty() && p.fading.isEmpty() && p.myPresets.isEmpty() && p.myCourses.isEmpty())
        assertEquals(LicenseInfo(), p.license)
        assertTrue(p.sessions.isEmpty() && p.shareQueue.isEmpty())
        assertEquals(1, p.nextNoInMode(5))
    }

    @Test
    fun blankProfileIsNamedFromItsId() {
        assertEquals("운전자 3", Profile.blank("p3").name)
        assertEquals("운전자", Profile.nameFor("guest"))
        assertEquals(Profile.blank("p2"), AppState(activeProfileId = "p2").activeProfile)
    }

    @Test
    fun panelStateReadsPrototypePanelJson() {
        val text = """
            { "collapsed": false, "view": null, "mockupHero": false, "speed": 1, "autoplay": true, "skill": "normal",
              "caps": { "speed": true, "trip": false }, "network": true, "night": true, "town": "home", "congestionSeed": 1,
              "model": "avante", "logFilter": { "coach": true, "navi": true, "blocked": true }, "family": [], "profileId": "p1",
              "otherApp": false, "open": {} }
        """.trimIndent()

        assertEquals(DemoPanelState(caps = SignalCaps(trip = false)), BaroJson.decodeFromString(DemoPanelState.serializer(), text))
        assertEquals("배속 '실제' 칸이 없는 옛 저장은 실제", true, BaroJson.decodeFromString(DemoPanelState.serializer(), text).realtime)
        assertEquals(false, BaroJson.decodeFromString(DemoPanelState.serializer(), """{"speed":4,"realtime":false}""").realtime)
        assertEquals(DriverSkill.GOOD, BaroJson.decodeFromString(DemoPanelState.serializer(), """{"skill":"good"}""").skill)
        assertEquals(DemoPanel(network = false), BaroJson.decodeFromString(DemoPanel.serializer(), """{"network":false,"town":"home"}"""))
    }

    @Test
    fun emptyJsonIsTheFirstRunState() {
        assertEquals(Profile(), BaroJson.decodeFromString(Profile.serializer(), "{}"))
        assertEquals(AppState(), BaroJson.decodeFromString(AppState.serializer(), "{}"))
    }
}
