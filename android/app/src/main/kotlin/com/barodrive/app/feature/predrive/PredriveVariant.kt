package com.barodrive.app.feature.predrive

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.VehicleSnapshot
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * PR-1 변형 (프로토타입 `Baro.predriveVariant(mode, def)`, 결정사항 E T2c-1): 모드 5 자율·6 주차·7 언덕·8 면허가 PR-1 기본 화면을 바꾸는 칸.
 * 모든 칸은 선택이고, 변형이 없으면 기본 PR-1과 같다. 그 모드 기능이 Hilt `@IntoSet`으로 넣는다:
 * ```
 * @Module @InstallIn(SingletonComponent::class)
 * abstract class LicensePredriveModule { @Binds @IntoSet abstract fun v(impl: LicensePredriveVariant): PredriveVariant }
 * ```
 */
interface PredriveVariant {
    /** 그 모드(5~8). */
    val mode: Int

    /** 앱 바 부제(predrive.js `subtitle`). null이면 기본('1단계 · 차량 감 익히기 · 2회차'). */
    fun subtitle(prep: PrepSession): String? = null

    /** 오늘 과제 카드(없으면 정차 중 'p.tasks'도 읽지 않음 — 자율·주차는 거짓). */
    val tasks: Boolean get() = true

    /** 코스 카드(자율은 거짓). */
    val course: Boolean get() = true

    /** 조수석 가족 카드(주차는 거짓). */
    val passenger: Boolean get() = true

    /** 조수석 카드를 그 글로 고정(면허 '동승자 확인 끝'). null이면 착석 신호를 따르는 기본 카드. */
    fun passengerCard(prep: PrepSession, snapshot: VehicleSnapshot?): FixedPassenger? = null

    /** 코스 카드 아래 짧은 줄(면허 '3구간으로 나눠 안내해요'). */
    fun courseNote(prep: PrepSession): String? = null

    /** 이 준비 세션으로 처음 들어올 때 한 번(오늘 과제 읽기 다음, l1 앞) — 언덕 'p.hill' 정차 중 말 등. */
    fun onEnter(prep: PrepSession) = Unit

    /** 하단 주요 버튼 바꾸기(주차 [주차 안내 시작]). null이면 기본([내비로 보내고 출발]). */
    fun primary(prep: PrepSession): VariantPrimary? = null

    /** 맨 위 카드(자리 0): 자율 '목적지는 내비에서 직접 넣어요' · 언덕 안전 카드. */
    @Composable
    fun TopCard(prep: PrepSession, profile: Profile) = Unit

    /** 맨 위 카드가 있나([TopCard]를 그릴지). */
    val hasTopCard: Boolean get() = false
}

/** 고정 조수석 카드 글 (predrive.js 변형 `passenger` 객체 { title, label, sub, icon, ok, quote }). */
data class FixedPassenger(
    val title: String,
    val label: String,
    val sub: String? = null,
    @param:DrawableRes val icon: Int? = null,
    /** 보조 글을 민트(확인됨)로. 거짓이면 회색. */
    val ok: Boolean = true,
    /** 동승자 역할 문장(L1)을 붙인다 — PR-1이 L1이 '바로'이고 조수석에 앉아 있을 때 지금 코치로 그린다. */
    val l1Quote: Boolean = false,
)

/** 변형 하단 버튼 (predrive.js 변형 `primary`): [route]로 간다. [onClick]은 가기 전에 할 일(시나리오 불러오기 등). */
data class VariantPrimary(
    val label: String,
    @param:DrawableRes val icon: Int? = null,
    val enabled: Boolean = true,
    val hint: String? = null,
    val route: Any? = null,
    val onClick: (suspend (PrepSession) -> Unit)? = null,
)

/** 변형이 하나도 없어도 주입되게(빈 묶음). */
@Module
@InstallIn(SingletonComponent::class)
abstract class PredriveVariantModule {
    @Multibinds
    abstract fun predriveVariants(): Set<PredriveVariant>
}
