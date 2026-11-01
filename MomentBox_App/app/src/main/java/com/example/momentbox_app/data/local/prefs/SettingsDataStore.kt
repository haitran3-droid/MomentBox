package com.example.momentbox_app.data.local.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface SettingsStorage {
    val onboardingDoneFlow: Flow<Boolean>
    val dataSourceFlow: Flow<String>
    val debugDelayMsFlow: Flow<Int>
    val debugForce401Flow: Flow<Boolean>
    val configCacheFlow: Flow<String?>

    suspend fun setOnboardingDone(done: Boolean)
    suspend fun isOnboardingDone(): Boolean
    fun cachedOnboardingDone(): Boolean? = null
    suspend fun setDataSource(source: String)
    suspend fun getDataSource(): String
    suspend fun setConfigCache(json: String)
    suspend fun getConfigCache(): String?
    suspend fun setDebugDelay(delayMs: Int)
    suspend fun getDebugDelay(): Int = 0
    suspend fun setDebugForce401(force: Boolean)
    suspend fun isDebugForce401(): Boolean
    suspend fun consumeDebugForce401(): Boolean {
        val pending = isDebugForce401()
        if (pending) setDebugForce401(false)
        return pending
    }

    suspend fun setSavedUserJson(json: String?)
    suspend fun getSavedUserJson(): String?
}

class SettingsDataStore(private val context: Context) : SettingsStorage {

    /** Cache trong RAM của trạng thái onboarding giúp Splash kiểm tra tức thì mà không cần đợi I/O đĩa */
    @Volatile private var onboardingCache: Boolean? = null

    override fun cachedOnboardingDone(): Boolean? = onboardingCache

    companion object {
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_DATA_SOURCE = stringPreferencesKey("data_source")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_CONFIG_CACHE = stringPreferencesKey("config_cache")
        val KEY_DEBUG_DELAY_MS = intPreferencesKey("debug_delay_ms")
        val KEY_DEBUG_FORCE_401 = booleanPreferencesKey("debug_force_401")
        val KEY_SAVED_USER_JSON = stringPreferencesKey("saved_user_json")

        /** Nguồn 1: Máy chủ chính MomentBox Mock Server */
        const val SOURCE_MB_SERVER = "MB_SERVER"
        /** Nguồn 2: Máy chủ phụ DummyJSON (dùng để kiểm thử tích hợp nguồn thứ 3) */
        const val SOURCE_DUMMY_JSON = "DUMMY_JSON"
        /** Nguồn 3: Dữ liệu giả lập ngoại tuyến (chạy không cần mạng qua MockInterceptor) */
        const val SOURCE_OFFLINE = "OFFLINE"
    }

    override val onboardingDoneFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_DONE] ?: false
    }

    override val dataSourceFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DATA_SOURCE] ?: SOURCE_MB_SERVER
    }

    override val debugDelayMsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEBUG_DELAY_MS] ?: 0
    }

    override val debugForce401Flow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEBUG_FORCE_401] ?: false
    }

    override val configCacheFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_CONFIG_CACHE]
    }

    override suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_DONE] = done
        }
        onboardingCache = done
    }

    override suspend fun isOnboardingDone(): Boolean {
        return onboardingDoneFlow.first().also { onboardingCache = it }
    }

    override suspend fun setDataSource(source: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DATA_SOURCE] = source
        }
    }

    override suspend fun getDataSource(): String {
        return dataSourceFlow.first()
    }

    override suspend fun setConfigCache(json: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONFIG_CACHE] = json
        }
    }

    override suspend fun getConfigCache(): String? {
        return configCacheFlow.first()
    }

    override suspend fun setDebugDelay(delayMs: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEBUG_DELAY_MS] = delayMs
        }
    }

    override suspend fun getDebugDelay(): Int {
        return debugDelayMsFlow.first()
    }

    override suspend fun setDebugForce401(force: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEBUG_FORCE_401] = force
        }
    }

    override suspend fun isDebugForce401(): Boolean {
        return debugForce401Flow.first()
    }

    override suspend fun consumeDebugForce401(): Boolean {
        var pending = false
        context.dataStore.edit { prefs ->
            pending = prefs[KEY_DEBUG_FORCE_401] ?: false
            prefs[KEY_DEBUG_FORCE_401] = false
        }
        return pending
    }

    override suspend fun setSavedUserJson(json: String?) {
        context.dataStore.edit { prefs ->
            if (json != null) {
                prefs[KEY_SAVED_USER_JSON] = json
            } else {
                prefs.remove(KEY_SAVED_USER_JSON)
            }
        }
    }

    override suspend fun getSavedUserJson(): String? {
        return context.dataStore.data.map { it[KEY_SAVED_USER_JSON] }.first()
    }
}
