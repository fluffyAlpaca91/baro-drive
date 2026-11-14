package com.barodrive.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.barodrive.app.BuildConfig
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 차 신호를 어디서 받나: 시뮬레이터(시연) 또는 진짜 차. */
enum class DataSource { SIM, CAR }

/**
 * 앱 설정 (DataStore Preferences, 프로토타입 store.js의 패널·앱 설정 중 뼈대에 필요한 것).
 * 시연 도구를 뺀 빌드(DEMO_TOOLS=false)에는 시뮬레이터를 고를 곳이 없으므로 자료 출처는 늘 진짜 차다.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    // 파일을 못 읽으면 기본값으로 둔다(앱을 멈추지 않는다).
    private val prefs: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    /** 자료 출처. 기본은 시뮬레이터(시연 빌드). */
    val dataSource: Flow<DataSource> =
        if (!BuildConfig.DEMO_TOOLS) {
            flowOf(DataSource.CAR)
        } else {
            prefs.map { p -> DataSource.entries.firstOrNull { it.name == p[DATA_SOURCE] } ?: DataSource.SIM }
                .distinctUntilChanged()
        }

    /** 코치 소리를 낼지. 기본은 켬. */
    val voiceOn: Flow<Boolean> = prefs.map { it[VOICE_ON] ?: true }.distinctUntilChanged()

    suspend fun setDataSource(source: DataSource) {
        dataStore.edit { it[DATA_SOURCE] = source.name }
    }

    suspend fun setVoiceOn(on: Boolean) {
        dataStore.edit { it[VOICE_ON] = on }
    }

    private companion object {
        val DATA_SOURCE = stringPreferencesKey("data_source")
        val VOICE_ON = booleanPreferencesKey("voice_on")
    }
}
