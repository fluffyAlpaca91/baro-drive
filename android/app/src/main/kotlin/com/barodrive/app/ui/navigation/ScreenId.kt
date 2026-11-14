package com.barodrive.app.ui.navigation

import androidx.annotation.StringRes
import com.barodrive.app.R

/**
 * 화면 상태 (설계 2.5, 프로토타입 router.js `kind`). 정차 화면은 기어가 P일 때만 보이고, 주행 중에는 [com.barodrive.app.ui.BaroRoot]의
 * 주행 덮개가 덮는다. 움직이는 중 화면(PK-3·HL-1·HL-2)은 주행 중에도 덮지 않는다 — 큰 문장 하나 + 그림 + 버튼 하나로 만든
 * 주행용 화면이기 때문이다(설계 2.5 마지막 줄). 신호 대기·주행 화면(DR-0~2)은 덮개 안에서만 그린다.
 */
enum class ScreenKind { PARK, WAIT, DRIVE, MOVING }

/**
 * 화면이 뜨는 자리: [SCREEN] NavHost 경로 · [SHEET] 시트 경로([sheet], 정차 화면 위에 겹침) · [COVER] 주행 덮개 안(경로 아님).
 */
enum class ScreenHost { SCREEN, SHEET, COVER }

/**
 * 화면 목록 (설계 4.1 표 37개 + 프로토타입에만 있는 시트 1개). [code]는 설계의 화면 id이자 타입 안전 경로의 `@SerialName`이라서
 * NavHost 경로 글자가 이 id로 시작한다(예: `RV-1/{recordId}?readOnly={readOnly}`). 그래서 [ofRoute]로 지금 화면을 알 수 있다.
 * [title]은 화면 이름(각 기능의 `strings_<기능>.xml`). 경로 클래스는 기능마다 `feature/<기능>/<기능>Navigation.kt`에 있다.
 *
 * | 묶음(프로토타입 js/app/screens) | 경로 파일 · 그래프 함수 |
 * |---|---|
 * | onboarding.js ON-1~5 | `feature/onboarding/OnboardingNavigation.kt` · `onboardingGraph` |
 * | home.js HM-1 | `feature/home/HomeNavigation.kt` · `homeGraph` |
 * | coach.js CO-1 · CO-2(시트) | `feature/coach/CoachNavigation.kt` · `coachGraph` |
 * | feedback.js FB-1·2 | `feature/feedback/FeedbackNavigation.kt` · `feedbackGraph` |
 * | course.js CR-1 | `feature/course/CourseNavigation.kt` · `courseGraph` |
 * | mycourses.js ST-3 · CR-2 | `feature/mycourses/MyCoursesNavigation.kt` · `myCoursesGraph` |
 * | predrive.js PR-1 | `feature/predrive/PredriveNavigation.kt` · `predriveGraph` |
 * | weather.js PR-1 아주 나쁜 날(시트) | `feature/weather/WeatherNavigation.kt` · `weatherGraph` |
 * | license.js LC-1 · LC-2(시트) · RV-5 | `feature/license/LicenseNavigation.kt` · `licenseGraph` |
 * | drive.js DR-0~2(덮개) · DR-3 | `feature/drive/DriveNavigation.kt` · `driveGraph` |
 * | review.js RV-1 | `feature/review/ReviewNavigation.kt` · `reviewGraph` |
 * | stage.js RV-2·3 | `feature/stage/StageNavigation.kt` · `stageGraph` |
 * | autonomous.js RV-4 | `feature/autonomous/AutonomousNavigation.kt` · `autonomousGraph` |
 * | share.js RV-7(시트) | `feature/share/ShareNavigation.kt` · `shareGraph` |
 * | parking-*.js PK-1~4 | `feature/parking/ParkingNavigation.kt` · `parkingGraph` |
 * | hill.js HL-1·2 · RV-6 | `feature/hill/HillNavigation.kt` · `hillGraph` |
 * | history.js ST-2 | `feature/history/HistoryNavigation.kt` · `historyGraph` |
 * | settings.js ST-1·4·5 | `feature/settings/SettingsNavigation.kt` · `settingsGraph` |
 */
enum class ScreenId(
    val code: String,
    @param:StringRes val title: Int,
    val kind: ScreenKind,
    val host: ScreenHost = ScreenHost.SCREEN,
) {
    ON_1("ON-1", R.string.screen_on_1, ScreenKind.PARK),
    ON_2("ON-2", R.string.screen_on_2, ScreenKind.PARK),
    ON_3("ON-3", R.string.screen_on_3, ScreenKind.PARK),
    ON_4("ON-4", R.string.screen_on_4, ScreenKind.PARK),
    ON_5("ON-5", R.string.screen_on_5, ScreenKind.PARK),
    HM_1("HM-1", R.string.screen_hm_1, ScreenKind.PARK),
    CO_1("CO-1", R.string.screen_co_1, ScreenKind.PARK),
    CO_2("CO-2", R.string.screen_co_2, ScreenKind.PARK, ScreenHost.SHEET),
    FB_1("FB-1", R.string.screen_fb_1, ScreenKind.PARK),
    FB_2("FB-2", R.string.screen_fb_2, ScreenKind.PARK),
    CR_1("CR-1", R.string.screen_cr_1, ScreenKind.PARK),

    /** 설계 4.1은 시트지만 프로토타입은 전체 화면으로 만들었다(결정사항 E T9-1: 탭·장소 목록·모드 고르기가 시트에 넘친다). */
    CR_2("CR-2", R.string.screen_cr_2, ScreenKind.PARK),
    PR_1("PR-1", R.string.screen_pr_1, ScreenKind.PARK),

    /** PR-1 날씨 줄의 [아주 나쁜 날 ▾] 시트(설계 4.2 PR-1, 프로토타입 weather.js). 설계 4.1 표에는 id가 없다. */
    PR_1_SEVERE("PR-1-severe", R.string.screen_pr_1_severe, ScreenKind.PARK, ScreenHost.SHEET),
    LC_1("LC-1", R.string.screen_lc_1, ScreenKind.PARK),
    LC_2("LC-2", R.string.screen_lc_2, ScreenKind.PARK, ScreenHost.SHEET),
    DR_0("DR-0", R.string.screen_dr_0, ScreenKind.DRIVE, ScreenHost.COVER),
    DR_1("DR-1", R.string.screen_dr_1, ScreenKind.DRIVE, ScreenHost.COVER),
    DR_2("DR-2", R.string.screen_dr_2, ScreenKind.WAIT, ScreenHost.COVER),
    DR_3("DR-3", R.string.screen_dr_3, ScreenKind.PARK),
    RV_1("RV-1", R.string.screen_rv_1, ScreenKind.PARK),
    RV_2("RV-2", R.string.screen_rv_2, ScreenKind.PARK),
    RV_3("RV-3", R.string.screen_rv_3, ScreenKind.PARK),
    RV_4("RV-4", R.string.screen_rv_4, ScreenKind.PARK),
    RV_5("RV-5", R.string.screen_rv_5, ScreenKind.PARK),
    RV_6("RV-6", R.string.screen_rv_6, ScreenKind.PARK),
    RV_7("RV-7", R.string.screen_rv_7, ScreenKind.PARK, ScreenHost.SHEET),
    PK_1("PK-1", R.string.screen_pk_1, ScreenKind.PARK),
    PK_2("PK-2", R.string.screen_pk_2, ScreenKind.PARK),
    PK_3("PK-3", R.string.screen_pk_3, ScreenKind.MOVING),
    PK_4("PK-4", R.string.screen_pk_4, ScreenKind.PARK),
    HL_1("HL-1", R.string.screen_hl_1, ScreenKind.MOVING),
    HL_2("HL-2", R.string.screen_hl_2, ScreenKind.MOVING),
    ST_1("ST-1", R.string.screen_st_1, ScreenKind.PARK),
    ST_2("ST-2", R.string.screen_st_2, ScreenKind.PARK),
    ST_3("ST-3", R.string.screen_st_3, ScreenKind.PARK),
    ST_4("ST-4", R.string.screen_st_4, ScreenKind.PARK),
    ST_5("ST-5", R.string.screen_st_5, ScreenKind.PARK),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun of(code: String): ScreenId? = byCode[code]

        /** NavHost 경로 글자(`NavDestination.route`)에서 화면 id를 찾는다. 경로 인자(`/{…}`·`?…`) 앞까지가 id다. */
        fun ofRoute(route: String?): ScreenId? {
            if (route == null) return null
            val end = route.indexOfFirst { it == '/' || it == '?' }
            return of(if (end < 0) route else route.substring(0, end))
        }
    }
}

/**
 * 주행 중일 때 맨 위 화면을 주행 덮개로 덮는지 (프로토타입 router `onGear`: kind 'park' 화면만 숨긴다). 움직이는 중 화면만 덮지 않고,
 * 어떤 화면인지 모르면 덮는다(안전 쪽).
 */
fun coverWhenDriving(screen: ScreenId?): Boolean = screen?.kind != ScreenKind.MOVING

/** 리뷰 화면인가 (session.js `isReview`: RV-* 화면과 모드 리뷰 화면 PK-4). 시트(RV-7)는 아니다. */
val ScreenId.isReview: Boolean
    get() = host == ScreenHost.SCREEN && (code.startsWith("RV-") || this == ScreenId.PK_4)
