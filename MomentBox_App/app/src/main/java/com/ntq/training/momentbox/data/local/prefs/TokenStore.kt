package com.ntq.training.momentbox.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ntq.training.momentbox.core.network.AuthInterceptor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "momentbox_settings")

/**
 * [TokenSnapshot] - Ảnh chụp nhanh (Snapshot) bất biến chứa toàn bộ trạng thái phiên tại một thời điểm:
 * @property generation Số thế hệ phiên (tăng mỗi khi đăng nhập mới hoặc đăng xuất)
 * @property accessToken Token truy cập ngắn hạn (gửi kèm header Authorization)
 * @property refreshToken Token làm mới dài hạn (dùng để xin cấp lại accessToken khi bị 401)
 * @property rememberMe Cờ ghi nhớ đăng nhập (quyết định token có được lưu xuống đĩa hay không)
 */
data class TokenSnapshot(
    val generation: Long,
    val accessToken: String?,
    val refreshToken: String?,
    val rememberMe: Boolean
)

/** Dữ liệu token thực sự được lưu trữ bền vững (Persisted) xuống đĩa */
data class PersistedTokens(
    val accessToken: String?,
    val refreshToken: String?,
    val rememberMe: Boolean,
    val generation: Long = 0
)

/**
 * Giao diện trừu tượng hóa ranh giới lưu đĩa (Disk Boundary).
 * Cho phép Unit Test thay thế bằng bộ nhớ giả lập mà không phụ thuộc vào Android Context hay DataStore thật.
 */
interface TokenPersistence {
    suspend fun read(): PersistedTokens
    suspend fun write(tokens: PersistedTokens)
}

/**
 * Giao diện quản lý lưu trữ Token cho toàn bộ ứng dụng.
 */
interface TokenStorage {
    /** Quan sát snapshot mới; callback không được chặn hoặc gọi ngược thao tác lấy mutex token. */
    fun addSnapshotListener(listener: (TokenSnapshot) -> Unit): AutoCloseable = AutoCloseable { }

    /** Chốt side effect RAM cùng khóa với đổi phiên; block phải ngắn, không suspend hoặc gọi lại TokenStore. */
    suspend fun runIfGenerationMatches(expectedGeneration: Long, block: () -> Unit): Boolean {
        if (getSessionGeneration() != expectedGeneration) return false
        block()
        return true
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String, rememberMe: Boolean)
    suspend fun updateTokens(accessToken: String, refreshToken: String)
    suspend fun updateTokensIfGenerationMatches(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String
    ): Boolean

    suspend fun getAccessToken(): String?
    suspend fun getRefreshToken(): String?
    suspend fun getRememberMe(): Boolean
    fun cachedAccessToken(): String? = null
    fun getAccessTokenSync(): String?
    fun getRefreshTokenSync(): String?
    fun getSessionGeneration(): Long
    fun incrementSessionGeneration(): Long
    suspend fun clear()
    suspend fun snapshot(): TokenSnapshot =
        TokenSnapshot(getSessionGeneration(), getAccessToken(), getRefreshToken(), getRememberMe())

    fun snapshotSync(): TokenSnapshot = runBlocking { snapshot() }
    suspend fun saveTokensIfGenerationMatches(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        rememberMe: Boolean
    ): Boolean {
        if (getSessionGeneration() != expectedGeneration) return false
        saveTokens(accessToken, refreshToken, rememberMe)
        return true
    }

    suspend fun clearIfGenerationMatches(expectedGeneration: Long): Boolean {
        if (getSessionGeneration() != expectedGeneration) return false
        clear()
        return true
    }
}

/**
 * [TokenStore] - Triển khai chính của [TokenStorage] kết hợp Bộ nhớ đệm RAM và Lưu đĩa Jetpack DataStore.
 *
 * **Các cơ chế kỹ thuật cốt lõi (Intern cần hiểu sâu):**
 * 1. **RAM Cache vs Disk Persistence:**
 *    - `@Volatile private var state`: Lưu snapshot ngay trong RAM để [AuthInterceptor]
 *      đọc token đồng bộ cực nhanh (Microsecond) mà không phải chờ I/O đĩa bất đồng bộ ở mỗi request.
 *    - Khi khởi động app, dữ liệu được tải từ DataStore lên RAM một lần duy nhất qua [loadLocked].
 * 2. **Cơ chế "Ghi nhớ đăng nhập" (Remember Me):**
 *    - Nếu `rememberMe == true`: Cặp token được ghi xuống Jetpack DataStore trên đĩa. Khi tắt app hoặc
 *      bị hệ điều hành kill process (Process Death), mở lại app vẫn giữ được phiên đăng nhập.
 *    - Nếu `rememberMe == false`: Token CHỈ nằm trong RAM. Trên đĩa ghi `null`. Khi process bị tạo lại,
 *      app sẽ bắt người dùng đăng nhập lại từ đầu.
 * 3. **Phạm vi Mutex và tính toàn vẹn (Atomic Operations):**
 *    - Toàn bộ thao tác kiểm tra generation, ghi đĩa DataStore và cập nhật RAM đều nằm trọn trong CÙNG MỘT
 *      vùng khóa [mutex.withLock].
 *    - Nếu tách kiểm tra generation ở một bước và ghi đĩa ở bước sau mà không giữ Mutex, Thread khác có thể
 *      nhảy vào giữa và làm sai lệch trạng thái phiên (Race Condition).
 */
class TokenStore(private val persistence: TokenPersistence) : TokenStorage {
    constructor(context: Context) : this(DataStoreTokenPersistence(context))

    /** Khóa Mutex đồng bộ bảo vệ toàn bộ các thao tác đọc ghi và kiểm tra thế hệ phiên */
    private val mutex = Mutex()

    /** Snapshot phiên hiện thời trong RAM, đánh dấu @Volatile để đảm bảo hiển thị tức thì giữa các Thread */
    @Volatile
    private var state = TokenSnapshot(0, null, null, true)

    /** Đánh dấu dữ liệu đã được tải từ đĩa lên RAM hay chưa */
    private var loaded = false
    private val snapshotListeners = linkedSetOf<(TokenSnapshot) -> Unit>()

    /** Listener nhận cả token refresh cùng generation; đóng subscription khi owner không còn dùng. */
    override fun addSnapshotListener(listener: (TokenSnapshot) -> Unit): AutoCloseable {
        synchronized(snapshotListeners) {
            snapshotListeners.add(listener)
            runCatching { listener(state) }
        }
        return AutoCloseable { synchronized(snapshotListeners) { snapshotListeners.remove(listener)
        Unit } }
    }

    private fun publishSnapshot(next: TokenSnapshot) = synchronized(snapshotListeners) {
        state = next
        // Observer lỗi không được làm transaction token đã ghi đĩa trở thành thất bại giả.
        snapshotListeners.toList().forEach { listener -> runCatching { listener(next) } }
    }

    override suspend fun runIfGenerationMatches(
        expectedGeneration: Long,
        block: () -> Unit
    ): Boolean = mutex.withLock {
        loadLocked()
        if (state.generation != expectedGeneration) return@withLock false
        block()
        true
    }

    companion object {
        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_REMEMBER_ME = booleanPreferencesKey("remember_me")

        // S19: PendingIntent có thể sống lâu hơn main process. Epoch phải bền vững;
        // thiếu key ở bản cũ được đọc là0, không xóa token hoặc buộc reset DataStore.
        val KEY_SESSION_GENERATION = longPreferencesKey("session_generation")
    }

    /** Tải dữ liệu từ đĩa lên RAM nếu chưa tải (chỉ gọi bên trong khối có khóa mutex) */
    private suspend fun loadLocked() {
        if (!loaded) {
            val disk = persistence.read()
            publishSnapshot(
                state.copy(
                    generation = maxOf(state.generation, disk.generation),
                    accessToken = disk.accessToken,
                    refreshToken = disk.refreshToken,
                    rememberMe = disk.rememberMe
                )
            )
            loaded = true
        }
    }

    /**
     * Ghi trạng thái mới xuống đĩa và cập nhật RAM (chỉ gọi bên trong khối có khóa mutex).
     * Chú ý: Nếu rememberMe là false, token trên đĩa sẽ là null!
     */
    private suspend fun writeLocked(next: TokenSnapshot) {
        persistence.write(
            PersistedTokens(
                next.accessToken.takeIf { next.rememberMe },
                next.refreshToken.takeIf { next.rememberMe },
                next.rememberMe,
                next.generation
            )
        )
        publishSnapshot(next)
        loaded = true
    }

    override suspend fun snapshot(): TokenSnapshot = mutex.withLock {
        loadLocked()
        state
    }

    override suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        rememberMe: Boolean
    ) = mutex.withLock {
        loadLocked()
        // Tăng generation để đánh dấu phiên đăng nhập hoàn toàn mới
        writeLocked(TokenSnapshot(state.generation + 1, accessToken, refreshToken, rememberMe))
    }

    override suspend fun saveTokensIfGenerationMatches(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        rememberMe: Boolean
    ): Boolean = mutex.withLock {
        loadLocked()
        if (state.generation != expectedGeneration) return@withLock false
        writeLocked(TokenSnapshot(state.generation + 1, accessToken, refreshToken, rememberMe))
        true
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) = mutex.withLock {
        loadLocked()
        // Cập nhật token mới nhưng giữ nguyên generation vì vẫn thuộc cùng một phiên làm việc
        writeLocked(state.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    /**
     * Cập nhật cặp token mới với điều kiện generation phải khớp (Compare-And-Set).
     * @return true nếu ghi thành công; false nếu phiên đã bị thay đổi (ví dụ: đã logout hoặc đổi tài khoản)
     */
    override suspend fun updateTokensIfGenerationMatches(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String
    ): Boolean = mutex.withLock {
        loadLocked()
        if (state.generation != expectedGeneration) return@withLock false
        writeLocked(state.copy(accessToken = accessToken, refreshToken = refreshToken))
        true
    }

    /**
     * Đăng xuất / Xóa phiên: Tăng generation và xóa sạch token trong cả RAM và Đĩa
     */
    override suspend fun clear() = mutex.withLock {
        loadLocked()
        writeLocked(
            state.copy(
                generation = state.generation + 1,
                accessToken = null,
                refreshToken = null
            )
        )
    }

    override suspend fun clearIfGenerationMatches(expectedGeneration: Long): Boolean =
        mutex.withLock {
            loadLocked()
            if (state.generation != expectedGeneration) return@withLock false
            writeLocked(
                state.copy(
                    generation = state.generation + 1,
                    accessToken = null,
                    refreshToken = null
                )
            )
            true
        }

    override fun getSessionGeneration(): Long = state.generation
    override fun incrementSessionGeneration(): Long = runBlocking {
        mutex.withLock {
            loadLocked()
            publishSnapshot(state.copy(generation = state.generation + 1))
            state.generation
        }
    }

    override fun cachedAccessToken(): String? = state.accessToken
    override suspend fun getAccessToken(): String? = snapshot().accessToken
    override suspend fun getRefreshToken(): String? = snapshot().refreshToken
    override suspend fun getRememberMe(): Boolean = snapshot().rememberMe
    override fun getAccessTokenSync(): String? = snapshotSync().accessToken
    override fun getRefreshTokenSync(): String? = snapshotSync().refreshToken
}

private class DataStoreTokenPersistence(private val context: Context) : TokenPersistence {
    override suspend fun read(): PersistedTokens {
        val prefs = context.dataStore.data.first()
        val rememberMe = prefs[TokenStore.KEY_REMEMBER_ME] ?: true
        return PersistedTokens(
            prefs[TokenStore.KEY_ACCESS_TOKEN].takeIf { rememberMe },
            prefs[TokenStore.KEY_REFRESH_TOKEN].takeIf { rememberMe },
            rememberMe,
            prefs[TokenStore.KEY_SESSION_GENERATION] ?: 0L
        )
    }

    override suspend fun write(tokens: PersistedTokens) {
        context.dataStore.edit { prefs ->
            prefs[TokenStore.KEY_REMEMBER_ME] = tokens.rememberMe
            prefs[TokenStore.KEY_SESSION_GENERATION] = tokens.generation
            if (tokens.accessToken != null) prefs[TokenStore.KEY_ACCESS_TOKEN] = tokens.accessToken
            else prefs.remove(TokenStore.KEY_ACCESS_TOKEN)
            if (tokens.refreshToken != null) prefs[TokenStore.KEY_REFRESH_TOKEN] =
                tokens.refreshToken
            else prefs.remove(TokenStore.KEY_REFRESH_TOKEN)
        }
    }
}
