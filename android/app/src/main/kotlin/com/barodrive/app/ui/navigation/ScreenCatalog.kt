package com.barodrive.app.ui.navigation

import com.barodrive.app.feature.autonomous.autonomousCatalog
import com.barodrive.app.feature.coach.coachCatalog
import com.barodrive.app.feature.course.courseCatalog
import com.barodrive.app.feature.drive.driveCatalog
import com.barodrive.app.feature.feedback.feedbackCatalog
import com.barodrive.app.feature.hill.hillCatalog
import com.barodrive.app.feature.history.historyCatalog
import com.barodrive.app.feature.home.homeCatalog
import com.barodrive.app.feature.license.licenseCatalog
import com.barodrive.app.feature.mycourses.myCoursesCatalog
import com.barodrive.app.feature.onboarding.onboardingCatalog
import com.barodrive.app.feature.parking.parkingCatalog
import com.barodrive.app.feature.predrive.predriveCatalog
import com.barodrive.app.feature.review.reviewCatalog
import com.barodrive.app.feature.settings.settingsCatalog
import com.barodrive.app.feature.share.shareCatalog
import com.barodrive.app.feature.stage.stageCatalog
import com.barodrive.app.feature.weather.weatherCatalog

/**
 * 시연 패널 '화면 목록'(시연 빌드만): 경로가 있는 모든 화면·시트를 설계 4.1 순서로. 누르면 그 경로로 바로 간다(예시 인자).
 * 덮개 화면(DR-0~2)은 경로가 아니라 기어로만 열린다. 각 기능의 `<기능>Catalog`를 모은다.
 */
val screenCatalog: List<CatalogEntry> by lazy {
    listOf(
        onboardingCatalog, homeCatalog, coachCatalog, feedbackCatalog, courseCatalog, myCoursesCatalog, predriveCatalog,
        weatherCatalog, licenseCatalog, driveCatalog, reviewCatalog, stageCatalog, autonomousCatalog, shareCatalog,
        parkingCatalog, hillCatalog, historyCatalog, settingsCatalog,
    ).flatten().sortedBy { it.screen.ordinal }
}
