package com.barodrive.app.di

import javax.inject.Qualifier

/** 앱이 살아 있는 동안 도는 코루틴 범위(세션 관리자·신호 수집). 화면과 상관없이 이어져야 하는 일에만 쓴다. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** CPU 일(엔진 걸음·시뮬레이터 시계). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** 파일 입출력(DataStore). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** 진짜 차 신호 출처. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CarSignal

/** 시뮬레이터 신호 출처. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SimSignal

/** 앱 상태(`baro.app.v1` — 프로필·진행·설정) DataStore. 앱 설정(자료 출처·소리) DataStore와 파일이 다르다. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppStateStore
