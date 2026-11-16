package com.ntq.training.momentbox.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ntq.training.momentbox.core.realtime.SocketTuning
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

interface SettingsStorage {
    val onboardingDoneFlow: Flow<Boolean>
    val dataSourceFlow: Flow<String>
    val debugDelayMsFlow: Flow<Int>
    val debugForce401Flow: Flow<Boolean>
    val configCacheFlow: Flow<String?>
    val autoplayVideoFlow: Flow<String> get() = flowOf("wifi")

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

    val socketTuningFlow: Flow<SocketTuning>
        get() = flowOf(
            SocketTuning()
        )

    suspend fun getSocketTuning(): SocketTuning =
        socketTuningFlow.first()

    suspend fun setSocketTuning(value: SocketTuning) {
        throw UnsupportedOperationException("SOCKET_OPTIONS_UNAVAILABLE")
    }

    val themeModeFlow: Flow<ThemeMode> get() = flowOf(ThemeMode.SYSTEM)
    suspend fun setThemeMode(mode: ThemeMode) {}
    val savedUserJsonFlow: Flow<String?>
        get() = kotlinx.coroutines.flow.flow {
            emit(
                getSavedUserJson()
            )
        }

    /** Adapter fake cũ vẫn dùng setter; DataStore thật override CAS trong cùng transaction. */
    suspend fun setSavedUserForGeneration(generation: Long, json: String): Boolean {
        setSavedUserJson(json)
        return true
    }

    suspend fun setSavedUserJson(json: String?)
    suspend fun getSavedUserJson(): String?
    suspend fun setAutoplayVideo(mode: String) {}
    suspend fun getAutoplayVideo(): String = "wifi"
}

/**
 * [SettingsDataStore] - Triển khai cài đặt bằng thư viện Jetpack Preferences DataStore.
 *
 * **Ưu điểm vượt trội so với SharedPreferences (Intern cần biết):**
 * - [SharedPreferences] cũ có thể gây giật lag hoặc treo ứng dụng (ANR) vì lệnh `commit()` ghi đĩa
 *   đồng bộ trên Main Thread, và `apply()` không có cơ chế bắt lỗi khi ghi thất bại.
 * - [DataStore] giải quyết hoàn toàn bằng cách xử lý bất đồng bộ qua Kotlin Coroutines và Flow,
 *   đảm bảo an toàn luồng (Thread-safety) và hỗ trợ giao dịch (Transactional updates).
 *
 * **Các cấu hình được quản lý:**
 * - `onboarding_done`: Người dùng đã hoàn thành onboarding hay chưa.
 * - `data_source`: Nguồn API được chọn (MB_SERVER, DUMMY_JSON, OFFLINE).
 * - `config_cache`: Bộ nhớ đệm JSON cấu hình máy chủ `/v1/config` trên đĩa.
 * - `debug_delay_ms` & `debug_force_401`: Các cấu hình phục vụ giả lập môi trường phát triển.
 * - `saved_user_json`: Thông tin người dùng đăng nhập gần nhất (phục vụ mô hình Cache-First).
 */
class SettingsDataStore(private val preferences: DataStore<Preferences>) :
    SettingsStorage {
    /** Context constructor vẫn dùng cùng momentbox_settings với token/reminder. Nhận store
     * qua constructor giúp kiểm transaction trên file cách ly mà không thay logic sản phẩm. */
    constructor(context: Context) : this(context.dataStore)

    /** Cache trong RAM của trạng thái onboarding giúp Splash kiểm tra tức thì mà không cần đợi I/O đĩa */
    @Volatile
    private var onboardingCache: Boolean? = null

    override fun cachedOnboardingDone(): Boolean? = onboardingCache

    companion object {
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_DATA_SOURCE = stringPreferencesKey("data_source")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_CONFIG_CACHE = stringPreferencesKey("config_cache")
        val KEY_DEBUG_DELAY_MS = intPreferencesKey("debug_delay_ms")
        val KEY_DEBUG_FORCE_401 = booleanPreferencesKey("debug_force_401")
        val KEY_SAVED_USER_JSON = stringPreferencesKey("saved_user_json")
        private val KEY_SAVED_USER_GENERATION =
            longPreferencesKey("saved_user_generation")
        private val KEY_NOTIFICATION_PROMPTED =
            booleanPreferencesKey("notification_permission_prompted")
        private val KEY_SOCKET_TIMEOUT =
            longPreferencesKey("m5_socket_timeout_ms")
        private val KEY_SOCKET_ATTEMPTS = intPreferencesKey("m5_socket_attempts")
        private val KEY_SOCKET_MIN =
            longPreferencesKey("m5_socket_delay_min_ms")
        private val KEY_SOCKET_MAX =
            longPreferencesKey("m5_socket_delay_max_ms")
        private val KEY_SOCKET_RANDOMIZATION =
            doublePreferencesKey("m5_socket_randomization")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_AUTOPLAY_VIDEO = stringPreferencesKey("autoplay_video")

        /** Chế độ tự động phát video trong feed: Luôn phát */
        const val AUTOPLAY_ALWAYS = "ALWAYS"

        /** Chế độ tự động phát video trong feed: Chỉ khi kết nối Wi-Fi (Mặc định) */
        const val AUTOPLAY_WIFI_ONLY = "WIFI_ONLY"

        /** Chế độ tự động phát video trong feed: Không bao giờ tự phát */
        const val AUTOPLAY_NEVER = "NEVER"

        /** Nguồn 1: Máy chủ chính MomentBox Mock Server */
        const val SOURCE_MB_SERVER = "MB_SERVER"

        /** Nguồn 2: Máy chủ phụ DummyJSON (dùng để kiểm thử tích hợp nguồn thứ 3) */
        const val SOURCE_DUMMY_JSON = "DUMMY_JSON"

        /** Nguồn 3: Dữ liệu giả lập ngoại tuyến (chạy không cần mạng qua MockInterceptor) */
        const val SOURCE_OFFLINE = "OFFLINE"
    }

    override val onboardingDoneFlow: Flow<Boolean> = preferences.data.map { prefs ->
        prefs[KEY_ONBOARDING_DONE] ?: false
    }

    override val dataSourceFlow: Flow<String> = preferences.data.map { prefs ->
        prefs[KEY_DATA_SOURCE] ?: SOURCE_MB_SERVER
    }

    override val debugDelayMsFlow: Flow<Int> = preferences.data.map { prefs ->
        prefs[KEY_DEBUG_DELAY_MS]?.takeIf { it in 0..5000 } ?: 0
    }

    override val debugForce401Flow: Flow<Boolean> = preferences.data.map { prefs ->
        prefs[KEY_DEBUG_FORCE_401] ?: false
    }

    override val configCacheFlow: Flow<String?> = preferences.data.map { prefs ->
        prefs[KEY_CONFIG_CACHE]
    }

    /** Một DataStore edit ghi đủ options; collector không nhìn thấy min của lần cũ với
     * max của lần mới. Prefs hỏng/phiên bản lạ trở về default BA, không crash khi cold start. */
    override val socketTuningFlow: Flow<SocketTuning> =
        preferences.data.map { p ->
            runCatching {
                SocketTuning(
                    p[KEY_SOCKET_TIMEOUT] ?: 20000L,
                    p[KEY_SOCKET_ATTEMPTS] ?: 5,
                    p[KEY_SOCKET_MIN] ?: 1000L,
                    p[KEY_SOCKET_MAX] ?: 5000L,
                    p[KEY_SOCKET_RANDOMIZATION] ?: 0.5
                )
            }
                .getOrElse { SocketTuning() }
        }.distinctUntilChanged()

    override suspend fun setSocketTuning(value: SocketTuning) {
        preferences.edit { p ->
            p[KEY_SOCKET_TIMEOUT] = value.timeoutMs
            p[KEY_SOCKET_ATTEMPTS] = value.attempts
            p[KEY_SOCKET_MIN] = value.delayMinMs
            p[KEY_SOCKET_MAX] =
            value.delayMaxMs
            p[KEY_SOCKET_RANDOMIZATION] = value.randomization
        }
    }

    /** Giá trị lạ từ bản mới hơn trở về SYSTEM, không làm app crash khi đọc preferences cũ. */
    override val themeModeFlow: Flow<ThemeMode> =
        preferences.data.map { ThemeMode.fromStored(it[KEY_THEME_MODE]) }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        preferences.edit { it[KEY_THEME_MODE] = mode.name }
    }

    override val autoplayVideoFlow: Flow<String> = preferences.data.map { prefs ->
        prefs[KEY_AUTOPLAY_VIDEO].takeIf {
            it in setOf(
                AUTOPLAY_ALWAYS,
                AUTOPLAY_WIFI_ONLY,
                AUTOPLAY_NEVER
            )
        } ?: AUTOPLAY_WIFI_ONLY
    }

    override suspend fun setOnboardingDone(done: Boolean) {
        preferences.edit { prefs ->
            prefs[KEY_ONBOARDING_DONE] = done
        }
        onboardingCache = done
    }

    override suspend fun isOnboardingDone(): Boolean {
        return onboardingDoneFlow.first().also { onboardingCache = it }
    }

    override suspend fun setDataSource(source: String) {
        preferences.edit { prefs ->
            prefs[KEY_DATA_SOURCE] = source
        }
    }

    override suspend fun getDataSource(): String {
        return dataSourceFlow.first()
    }

    override suspend fun setConfigCache(json: String) {
        preferences.edit { prefs ->
            prefs[KEY_CONFIG_CACHE] = json
        }
    }

    override suspend fun getConfigCache(): String? {
        return configCacheFlow.first()
    }

    override suspend fun setDebugDelay(delayMs: Int) {
        require(delayMs in 0..5000) { "DEBUG_DELAY_OUT_OF_RANGE" }
        preferences.edit { prefs ->
            prefs[KEY_DEBUG_DELAY_MS] = delayMs
        }
    }

    suspend fun preferenceNames(): List<String> =
        preferences.data.first().asMap().keys.map { it.name }.sorted()

    override suspend fun getDebugDelay(): Int {
        return debugDelayMsFlow.first()
    }

    override suspend fun setDebugForce401(force: Boolean) {
        preferences.edit { prefs ->
            prefs[KEY_DEBUG_FORCE_401] = force
        }
    }

    override suspend fun isDebugForce401(): Boolean {
        return debugForce401Flow.first()
    }

    override suspend fun consumeDebugForce401(): Boolean {
        var pending = false
        preferences.edit { prefs ->
            pending = prefs[KEY_DEBUG_FORCE_401] ?: false
            prefs[KEY_DEBUG_FORCE_401] = false
        }
        return pending
    }

    /** JSON và epoch cùng một DataStore edit. Token clear/refresh cũng dùng store này;
     * không để ACK avatar của phiên cũ ghi profile vào sau transaction logout.
     * Cache bản cũ thiếu key epoch vẫn đọc được, setter kế tiếp sẽ gắn epoch hiện tại. */
    override val savedUserJsonFlow: Flow<String?> = preferences.data.map { prefs ->
        val current = prefs[TokenStore.KEY_SESSION_GENERATION] ?: 0L
        val cached = prefs[KEY_SAVED_USER_GENERATION] ?: current
        prefs[KEY_SAVED_USER_JSON].takeIf { cached == current }
    }.distinctUntilChanged()

    override suspend fun setSavedUserForGeneration(generation: Long, json: String): Boolean {
        var committed = false
        preferences.edit { prefs ->
            if ((prefs[TokenStore.KEY_SESSION_GENERATION] ?: 0L) == generation) {
                prefs[KEY_SAVED_USER_JSON] = json
                prefs[KEY_SAVED_USER_GENERATION] = generation
                committed = true
            }
        }
        return committed
    }

    override suspend fun setSavedUserJson(json: String?) {
        preferences.edit { prefs ->
            if (json != null) {
                prefs[KEY_SAVED_USER_JSON] = json
                prefs[KEY_SAVED_USER_GENERATION] = prefs[TokenStore.KEY_SESSION_GENERATION] ?: 0L
            } else {
                prefs.remove(KEY_SAVED_USER_JSON)
                prefs.remove(KEY_SAVED_USER_GENERATION)
            }
        }
    }

    override suspend fun getSavedUserJson(): String? = savedUserJsonFlow.first()

    override suspend fun setAutoplayVideo(mode: String) {
        require(
            mode in setOf(
                AUTOPLAY_ALWAYS,
                AUTOPLAY_WIFI_ONLY,
                AUTOPLAY_NEVER
            )
        ) { "Unknown autoplay mode" }
        preferences.edit { prefs ->
            prefs[KEY_AUTOPLAY_VIDEO] = mode
        }
    }

    override suspend fun getAutoplayVideo(): String {
        return autoplayVideoFlow.first()
    }

    // Lưu lựa chọn giải thích ở cấp ứng dụng: đổi tài khoản không được hỏi quyền OS lặp lại.
    suspend fun notificationPrompted(): Boolean =
        preferences.data.first()[KEY_NOTIFICATION_PROMPTED] ?: false

    suspend fun setNotificationPrompted(value: Boolean) {
        preferences.edit { it[KEY_NOTIFICATION_PROMPTED] = value }
    }
}
