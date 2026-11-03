package com.example.momentbox_app.data.repository

import com.example.momentbox_app.core.network.safeApiCall
import com.example.momentbox_app.data.local.prefs.SettingsStorage
import com.example.momentbox_app.data.remote.api.ConfigApi
import com.example.momentbox_app.data.remote.dto.ConfigDto
import com.example.momentbox_app.domain.mapper.ConfigMapper
import com.example.momentbox_app.domain.model.AppConfig
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface ConfigRepository {
    suspend fun getConfig(): Result<AppConfig>
    fun cachedConfig(): AppConfig? = null
}

class ConfigRepositoryImpl(
    private val configApi: ConfigApi,
    private val settingsDataStore: SettingsStorage,
    private val configMapper: ConfigMapper = ConfigMapper(),
    private val gson: Gson = Gson(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ConfigRepository {
    @Volatile private var memoryCache: AppConfig? = null

    override fun cachedConfig(): AppConfig? = memoryCache

    override suspend fun getConfig(): Result<AppConfig> {
        try {
            if (memoryCache == null) settingsDataStore.getConfigCache()?.let { json ->
                memoryCache = configMapper.map(gson.fromJson(json, ConfigDto::class.java))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }

        val result = safeApiCall(dispatcher = dispatcher, gson = gson) {
            configApi.getConfig()
        }
        return if (result.isSuccess) {
            val dto = result.getOrThrow()
            memoryCache = configMapper.map(dto)
            try { settingsDataStore.setConfigCache(gson.toJson(dto)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {  }
            Result.success(memoryCache!!)
        } else {
            Result.success(memoryCache ?: AppConfig("1.0.0", 1, null, null))
        }
    }
}
