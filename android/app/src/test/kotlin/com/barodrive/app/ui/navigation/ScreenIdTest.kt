package com.barodrive.app.ui.navigation

import com.barodrive.app.feature.autonomous.AutonomousResult
import com.barodrive.app.feature.coach.CoachPicker
import com.barodrive.app.feature.coach.HonorificSheet
import com.barodrive.app.feature.course.CourseRecommend
import com.barodrive.app.feature.drive.DriveNotPracticing
import com.barodrive.app.feature.drive.DriveOneLine
import com.barodrive.app.feature.drive.DriveStopHere
import com.barodrive.app.feature.drive.DriveWaitCard
import com.barodrive.app.feature.feedback.FeedbackSettings
import com.barodrive.app.feature.feedback.MyFeedbackPresets
import com.barodrive.app.feature.hill.HillDrive
import com.barodrive.app.feature.hill.HillPark
import com.barodrive.app.feature.hill.HillReview
import com.barodrive.app.feature.history.History
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.license.LicenseCheck
import com.barodrive.app.feature.license.LicenseIssueDateSheet
import com.barodrive.app.feature.license.LicenseReview
import com.barodrive.app.feature.mycourses.MyCourses
import com.barodrive.app.feature.mycourses.NaviImport
import com.barodrive.app.feature.onboarding.OnboardingCoach
import com.barodrive.app.feature.onboarding.OnboardingConsent
import com.barodrive.app.feature.onboarding.OnboardingQuestions
import com.barodrive.app.feature.onboarding.OnboardingRecommend
import com.barodrive.app.feature.onboarding.OnboardingStart
import com.barodrive.app.feature.parking.ParkingDone
import com.barodrive.app.feature.parking.ParkingGuide
import com.barodrive.app.feature.parking.ParkingSelect
import com.barodrive.app.feature.parking.VirtualParking
import com.barodrive.app.feature.predrive.Predrive
import com.barodrive.app.feature.review.Review
import com.barodrive.app.feature.settings.About
import com.barodrive.app.feature.settings.Privacy
import com.barodrive.app.feature.settings.Settings
import com.barodrive.app.feature.share.ShareSheet
import com.barodrive.app.feature.stage.Graduation
import com.barodrive.app.feature.stage.StagePassed
import com.barodrive.app.feature.weather.SevereWeatherSheet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면 목록(설계 4.1)과 타입 안전 경로가 서로 맞는지. 경로 이름(@SerialName)이 화면 id라서 NavHost 경로로 지금 화면을 안다. */
class ScreenIdTest {
    /** 모든 경로 클래스의 직렬화기. 경로를 더하면 여기에도 더한다. */
    private val routeSerializers: List<KSerializer<*>> = listOf(
        serializer<OnboardingStart>(), serializer<OnboardingConsent>(), serializer<OnboardingQuestions>(),
        serializer<OnboardingRecommend>(), serializer<OnboardingCoach>(), serializer<Home>(),
        serializer<CoachPicker>(), serializer<HonorificSheet>(), serializer<FeedbackSettings>(), serializer<MyFeedbackPresets>(),
        serializer<CourseRecommend>(), serializer<NaviImport>(), serializer<MyCourses>(), serializer<Predrive>(),
        serializer<SevereWeatherSheet>(), serializer<LicenseCheck>(), serializer<LicenseIssueDateSheet>(),
        serializer<LicenseReview>(), serializer<DriveNotPracticing>(), serializer<DriveOneLine>(), serializer<DriveWaitCard>(),
        serializer<DriveStopHere>(), serializer<Review>(), serializer<StagePassed>(), serializer<Graduation>(),
        serializer<AutonomousResult>(), serializer<ShareSheet>(), serializer<ParkingSelect>(), serializer<VirtualParking>(),
        serializer<ParkingGuide>(), serializer<ParkingDone>(), serializer<HillDrive>(), serializer<HillPark>(),
        serializer<HillReview>(), serializer<History>(), serializer<Settings>(), serializer<Privacy>(), serializer<About>(),
    )

    @Test
    fun listsEveryScreenOfDesign41OnceAndTheSevereWeatherSheet() {
        val design41 = listOf(
            "ON-1", "ON-2", "ON-3", "ON-4", "ON-5", "HM-1", "CO-1", "CO-2", "FB-1", "FB-2", "CR-1", "CR-2", "PR-1", "LC-1", "LC-2",
            "DR-0", "DR-1", "DR-2", "DR-3", "RV-1", "RV-2", "RV-3", "RV-4", "RV-5", "RV-6", "RV-7", "PK-1", "PK-2", "PK-3", "PK-4",
            "HL-1", "HL-2", "ST-1", "ST-2", "ST-3", "ST-4", "ST-5",
        )
        assertEquals(37, design41.size)
        assertEquals(design41 + "PR-1-severe", ScreenId.entries.map { it.code }.sortedBy { order(it, design41) })
        assertEquals(ScreenId.entries.size, ScreenId.entries.map { it.code }.toSet().size)
    }

    @Test
    fun everyRouteIsNamedByItsScreenIdAndEveryScreenHasOneRoute() {
        val names = routeSerializers.map { it.descriptor.serialName }
        assertEquals("경로 이름이 겹쳐요", names.size, names.toSet().size)
        names.forEach { name -> assertTrue("화면 목록에 없는 경로 이름 $name", ScreenId.of(name) != null) }
        assertEquals(ScreenId.entries.map { it.code }.toSet(), names.toSet())
    }

    @Test
    fun kindsAndHostsFollowTheDesign() {
        assertEquals(setOf(ScreenId.PK_3, ScreenId.HL_1, ScreenId.HL_2), ScreenId.entries.filter { it.kind == ScreenKind.MOVING }.toSet())
        assertEquals(setOf(ScreenId.DR_0, ScreenId.DR_1, ScreenId.DR_2), ScreenId.entries.filter { it.host == ScreenHost.COVER }.toSet())
        assertEquals(
            setOf(ScreenId.CO_2, ScreenId.LC_2, ScreenId.RV_7, ScreenId.PR_1_SEVERE),
            ScreenId.entries.filter { it.host == ScreenHost.SHEET }.toSet(),
        )
        assertEquals(ScreenKind.WAIT, ScreenId.DR_2.kind)
        // CR-2는 설계 4.1의 시트지만 프로토타입처럼 전체 화면(결정사항 E T9-1)
        assertEquals(ScreenHost.SCREEN, ScreenId.CR_2.host)
    }

    @Test
    fun findsTheScreenFromANavHostRoute() {
        assertEquals(ScreenId.HM_1, ScreenId.ofRoute("HM-1"))
        assertEquals(ScreenId.RV_1, ScreenId.ofRoute("RV-1/{recordId}?readOnly={readOnly}"))
        assertEquals(ScreenId.FB_1, ScreenId.ofRoute("FB-1?mode={mode}"))
        assertEquals(ScreenId.PR_1, ScreenId.ofRoute("PR-1"))
        assertEquals(ScreenId.PR_1_SEVERE, ScreenId.ofRoute("PR-1-severe"))
        assertNull(ScreenId.ofRoute("XX-9"))
        assertNull(ScreenId.ofRoute(null))
    }

    @Test
    fun coversParkScreensButNotMovingOnesWhenDriving() {
        assertTrue(coverWhenDriving(ScreenId.HM_1))
        assertTrue(coverWhenDriving(ScreenId.PR_1))
        assertTrue(coverWhenDriving(ScreenId.CO_2))
        assertTrue(coverWhenDriving(null))
        assertFalse(coverWhenDriving(ScreenId.PK_3))
        assertFalse(coverWhenDriving(ScreenId.HL_1))
        assertFalse(coverWhenDriving(ScreenId.HL_2))
    }

    @Test
    fun catalogOpensEveryRoutedScreenOnceInDesignOrder() {
        val routed = ScreenId.entries.filter { it.host != ScreenHost.COVER }
        assertEquals(routed, screenCatalog.map { it.screen })
        screenCatalog.forEach { entry ->
            val name = serializer(entry.route::class.javaObjectType).descriptor.serialName
            assertEquals("목록 경로가 화면과 달라요: ${entry.screen}", entry.screen.code, name)
        }
    }

    @Test
    fun reviewRouteFollowsTheMode() {
        assertEquals(Review("sess-7"), reviewRouteFor(mode = 2, recordId = "sess-7"))
        assertEquals(Review("sess-7", readOnly = true, focus = Review.FOCUS_F2), reviewRouteFor(1, "sess-7", readOnly = true, focus = "f2"))
        assertEquals(AutonomousResult("sess-7", readOnly = true), reviewRouteFor(mode = 5, recordId = "sess-7", readOnly = true))
        assertEquals(ParkingDone("sess-7"), reviewRouteFor(mode = 6, recordId = "sess-7"))
        assertEquals(HillReview("sess-7"), reviewRouteFor(mode = 7, recordId = "sess-7"))
        assertEquals(LicenseReview("sess-7"), reviewRouteFor(mode = 8, recordId = "sess-7"))
    }

    private fun order(code: String, design: List<String>): Int = design.indexOf(code).let { if (it < 0) design.size else it }
}
