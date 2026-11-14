package com.barodrive.app.feature.predrive

import android.content.Context
import androidx.compose.runtime.Composable
import com.barodrive.app.R
import com.barodrive.app.feature.autonomous.AutonomousText
import com.barodrive.app.feature.autonomous.AutonomousTopCard
import com.barodrive.app.feature.hill.HILL_PREDRIVE_LINE
import com.barodrive.app.feature.hill.HillSafetyCard
import com.barodrive.app.feature.license.LicenseText
import com.barodrive.app.feature.parking.ParkingPredrive
import com.barodrive.app.session.api.SessionController
import com.barodrive.app.session.api.VoiceLog
import com.barodrive.core.model.Category
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.VehicleSnapshot
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

// PR-1 모드 변형 4개 (프로토타입 `Baro.predriveVariant(5|6|7|8)` — autonomous.js · parking-guide.js · hill.js · license.js).
// 화면 조각과 글은 그 모드 기능(feature/autonomous·parking·hill·license)이 내놓은 것을 쓰고, 여기서는 PR-1 자리에 잇기만 한다.

/** 모드 5 자율(autonomous.js): 오늘 과제·코스 카드 없음, 맨 위 '목적지는 내비에서 직접 넣어요' + 고른 설정 이름. 하단 버튼은 기본. */
class AutonomousPredriveVariant @Inject constructor() : PredriveVariant {
    override val mode: Int = 5
    override val tasks: Boolean = false
    override val course: Boolean = false
    override val hasTopCard: Boolean = true

    @Composable
    override fun TopCard(prep: PrepSession, profile: Profile) {
        AutonomousTopCard(AutonomousText.topModel(prep, profile))
    }
}

/**
 * 모드 6 실제 주차 안내(parking-guide.js): 오늘 과제·조수석 카드 없음, 점검·코스 카드만, 부제 '주차 연습 · 평행 · 오른쪽 칸 · 1회차',
 * 코스 아래 '주차장에 도착하면 주차 안내를 시작해요', 하단 [주차 안내 시작] → 준비 세션 주차 값을 정리하고 보낸 뒤(session.send —
 * 시연 시뮬레이터는 그 종류·방향을 고르고 S6-P를 불러 자동 재생, parking-guide.js) PK-3(기어 그대로).
 */
class ParkingPredriveVariant @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val session: SessionController,
) : PredriveVariant {
    override val mode: Int = 6
    override val tasks: Boolean = false
    override val passenger: Boolean = false

    override fun subtitle(prep: PrepSession): String = ParkingPredrive.subtitle(prep) { id, args -> context.getString(id, *args) }

    override fun courseNote(prep: PrepSession): String = context.getString(ParkingPredrive.COURSE_NOTE)

    override fun primary(prep: PrepSession): VariantPrimary = VariantPrimary(
        label = context.getString(ParkingPredrive.START),
        icon = ParkingPredrive.START_ICON,
        route = ParkingPredrive.startRoute(prep),
        onClick = {
            session.updatePrep { it.copy(parking = ParkingPredrive.setup(it)) }
            session.send()
        },
    )
}

/** 모드 7 언덕(hill.js): 맨 위 안전 카드 + 들어올 때 코치가 'p.hill'을 한 번(오늘 과제 읽기 다음). 나머지는 기본 PR-1. */
class HillPredriveVariant @Inject constructor(private val voice: VoiceLog) : PredriveVariant {
    override val mode: Int = 7
    override val hasTopCard: Boolean = true

    override fun onEnter(prep: PrepSession) {
        voice.sayLine(HILL_PREDRIVE_LINE, category = Category.INFO)
    }

    @Composable
    override fun TopCard(prep: PrepSession, profile: Profile) {
        HillSafetyCard(onListen = { voice.sayLine(HILL_PREDRIVE_LINE, category = Category.INFO) })
    }
}

/**
 * 모드 8 운전면허 연습(license.js, 결정사항 E T8-Q1): 부제 '운전면허 연습 · C 코스 · 3구간' · 코스 아래 '3구간으로 나눠 안내해요' ·
 * 동승자 카드는 '동승자 확인 끝'으로 고정(조수석이 비면 주의 글, L1이 '바로'면 동승자 역할 문장).
 * 앱에서는 모드 8 준비 세션이 LC-1 시작 체크를 거쳐서만 만들어져 늘 '체크함'으로 본다(프로토타입 `prep.license.checked`).
 */
class LicensePredriveVariant @Inject constructor(@param:ApplicationContext private val context: Context) : PredriveVariant {
    override val mode: Int = 8

    override fun subtitle(prep: PrepSession): String = LicenseText.predriveSubtitle(prep)

    override fun courseNote(prep: PrepSession): String? = LicenseText.predriveCourseNote(prep)

    override fun passengerCard(prep: PrepSession, snapshot: VehicleSnapshot?): FixedPassenger? =
        LicenseText.predrivePassenger(checked = true, seat = snapshot?.passenger)?.let { c ->
            FixedPassenger(
                title = context.getString(R.string.pr_license_passenger),
                label = c.label,
                sub = c.sub,
                icon = R.drawable.ic_seat,
                ok = c.ok,
                l1Quote = true,
            )
        }
}

/** 변형 넷을 PR-1 변형 묶음([PredriveVariant] 집합)에 넣는다. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PredriveVariantsModule {
    @Binds
    @IntoSet
    abstract fun autonomous(v: AutonomousPredriveVariant): PredriveVariant

    @Binds
    @IntoSet
    abstract fun parking(v: ParkingPredriveVariant): PredriveVariant

    @Binds
    @IntoSet
    abstract fun hill(v: HillPredriveVariant): PredriveVariant

    @Binds
    @IntoSet
    abstract fun license(v: LicensePredriveVariant): PredriveVariant
}
