package com.barodrive.app.di

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import com.barodrive.app.data.navi.NaviController
import com.barodrive.app.data.navi.car.CarNaviController
import com.barodrive.app.data.restriction.DrivingRestrictions
import com.barodrive.app.data.restriction.car.CarDrivingRestrictions
import com.barodrive.app.data.signal.SignalSource
import com.barodrive.app.data.signal.SpeedPermission
import com.barodrive.app.data.signal.car.CarSignalSource
import com.barodrive.app.data.signal.car.CarSpeedPermission
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * 진짜 차 쪽 연결. Pleos SDK·android.car 타입은 이 모듈과 `data/…/car/` 패키지에만 나온다.
 * SDK 객체는 범위 없이 만든다(받을 때마다 새것): 연결을 끊은(release) 객체는 다시 쓸 수 없어서
 * 쓰는 쪽이 `Provider`로 연결할 때마다 새로 받는다(API-레퍼런스 4.3 NaviHelper.release).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CarModule {
    @Binds
    @CarSignal
    abstract fun carSignalSource(impl: CarSignalSource): SignalSource

    @Binds
    abstract fun speedPermission(impl: CarSpeedPermission): SpeedPermission

    @Binds
    abstract fun naviController(impl: CarNaviController): NaviController

    @Binds
    abstract fun drivingRestrictions(impl: CarDrivingRestrictions): DrivingRestrictions

    companion object {
        @Provides
        fun vehicle(@ApplicationContext context: Context): Vehicle = Vehicle(context)

        @Provides
        fun naviHelper(@ApplicationContext context: Context): NaviHelper = NaviHelper(context)
    }
}
