package com.example.momentbox_app.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "momentbox_settings")

data class TokenSnapshot(
    val generation: Long,
    val accessToken: String?,
    val refreshToken: String?,
    val rememberMe: Boolean
)

data class PersistedTokens(
    val accessToken: String?,
    val refreshToken: String?,
    val rememberMe: Boolean
)

interface TokenPersistence {
    suspend fun read(): PersistedTokens
    suspend fun write(tokens: PersistedTokens)
}

interface TokenStorage {
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

class TokenStore(private val persistence: TokenPersistence) : TokenStorage {
    constructor(context: Context) : this(DataStoreTokenPersistence(context))

    /** Khóa Mutex đồng bộ bảo vệ toàn bộ các thao tác đọc ghi và kiểm tra thế hệ phiên */
    private val mutex = Mutex()

    /** Snapshot phiên hiện thời trong RAM, đánh dấu @Volatile để đảm bảo hiển thị tức thì giữa các Thread */
    @Volatile
    private var state = TokenSnapshot(0, null, null, true)

    /** Đánh dấu dữ liệu đã được tải từ đĩa lên RAM hay chưa */
    private var loaded = false

    companion object {
        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_REMEMBER_ME = booleanPreferencesKey("remember_me")
    }

    /** Tải dữ liệu từ đĩa lên RAM nếu chưa tải (chỉ gọi bên trong khối có khóa mutex) */
    private suspend fun loadLocked() {
        if (!loaded) {
            val disk = persistence.read()
            state = state.copy(
                accessToken = disk.accessToken,
                refreshToken = disk.refreshToken,
                rememberMe = disk.rememberMe
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
                next.rememberMe
            )
        )
        state = next
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
        // Tăng generation để đánh dấu phiên đăng nhập hoàn toàn mới
        writeLocked(TokenSnapshot(state.generation + 1, accessToken, refreshToken, rememberMe))
    }

    override suspend fun saveTokensIfGenerationMatches(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        rememberMe: Boolean
    ): Boolean = mutex.withLock {
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
            state = state.copy(generation = state.generation + 1)
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
            rememberMe
        )
    }

    override suspend fun write(tokens: PersistedTokens) {
        context.dataStore.edit { prefs ->
            prefs[TokenStore.KEY_REMEMBER_ME] = tokens.rememberMe
            if (tokens.accessToken != null) prefs[TokenStore.KEY_ACCESS_TOKEN] = tokens.accessToken
            else prefs.remove(TokenStore.KEY_ACCESS_TOKEN)
            if (tokens.refreshToken != null) prefs[TokenStore.KEY_REFRESH_TOKEN] =
                tokens.refreshToken
            else prefs.remove(TokenStore.KEY_REFRESH_TOKEN)
        }
    }
}
