package com.ntq.training.momentbox.feature.player

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.remote.dto.PostDto
import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.MomentBoxApp
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Tag
import retrofit2.Response

private val Context.playerPreferences by preferencesDataStore("player_preferences")

interface PlayerLibraryApi {
    @GET("v1/me/bookmarks")
    suspend fun saved(
        @Query("page") page: Int,
        @Query("pageSize") size: Int,
        @Tag session: SessionRequest
    ): Response<ApiResponse<List<PostDto>>>
}

data class PlayerLibrary(
    val tracks: List<Track>,
    val scope: String,
    val generation: Long,
    val remoteError: Boolean
)

class PlayerLibraryRepository(private val app: MomentBoxApp, private val api: PlayerLibraryApi) {
    private data class Identity(val ownerId: String, val source: String, val generation: Long)

    /** Journal không phụ thuộc nguồn chat MB; vẫn hoạt động khi app chọn Offline/DummyJSON.
     * Tag/fence dùng generation và backend thật, không đưa credentials vào Track/IPC. */
    private suspend fun identity(): Identity? {
        val before = app.tokenStore.snapshot()
        if (before.accessToken.isNullOrBlank()) return null
        val user = runCatching {
            Gson().fromJson(
                app.settingsDataStore.getSavedUserJson(),
                User::class.java
            )
        }.getOrNull() ?: return null
        val source =
            app.settingsDataStore.getDataSource() + "|" + app.socketManager.resolveSocketOrigin()
        if (app.tokenStore.snapshot().generation != before.generation) return null
        return Identity(user.id.toString(), source, before.generation)
    }

    private fun key(scope: String) = stringSetPreferencesKey(
        "favorites_" + MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray()).joinToString("") { "%02x".format(it) })

    suspend fun load(preferredTrackId: String? = null): PlayerLibrary =
        withContext(Dispatchers.IO) {
            val active = identity() ?: return@withContext PlayerLibrary(emptyList(), "", -1, true)
            val scope = active.ownerId + "|" + active.source
            val favorites = app.playerPreferences.data.first()[key(scope)].orEmpty()
            val dao = app.database.journalDao()
            val selectedId =
                preferredTrackId?.takeIf { it.startsWith("journal:") }?.removePrefix("journal:")
                    ?.toLongOrNull()?.takeIf { it > 0 }
            val selectedEntry = selectedId?.let { dao.audioEntryByAttachment(it) }
            val rows =
                (dao.audioLibrary(50) + listOfNotNull(selectedEntry)).distinctBy { it.entry.id }
            val local = rows.flatMap { row ->
                row.attachments.filter { it.type == "audio" }.map { audio ->
                    Track(
                        "journal:${audio.id}",
                        if (audio.localPath.startsWith("content:")) audio.localPath else Uri.fromFile(
                            File(audio.localPath)
                        ).toString(),
                        row.entry.title,
                        "journal",
                        row.entry.createdAt,
                        row.attachments.firstOrNull { it.type == "image" }?.localPath?.let {
                            if (it.startsWith(
                                    "content:"
                                ) || it.startsWith("file:")
                            ) it else Uri.fromFile(File(it)).toString()
                        },
                        false,
                        audio.waveform.take(120).map { (it / 100f).coerceIn(0f, 1f) },
                        scope
                    )
                }
            }
            val saved = mutableListOf<Track>()
            var remoteError = false
            try {
                var page = 1
                while (active.source.substringBefore('|') == SettingsDataStore.SOURCE_MB_SERVER && saved.size < 50) {
                    val response = api.saved(page, 30, SessionRequest(active.generation))
                    val body = response.body()
                    check(response.isSuccessful && body?.success == true)
                    val posts = body!!.data.orEmpty()
                    posts.forEach { post ->
                        post.media.orEmpty()
                            .filter {
                                it.type.equals("audio", true) || it.type.equals(
                                    "voice",
                                    true
                                )
                            }
                            .forEachIndexed { i, media ->
                                media.url?.let { uri ->
                                    saved.add(
                                        Track(
                                            "saved:${post.id}:$i",
                                            uri,
                                            post.caption.orEmpty().take(500),
                                            "saved",
                                            runCatching {
                                                Instant.parse(post.createdAt).toEpochMilli()
                                            }.getOrDefault(0),
                                            post.media?.firstOrNull { it.type == "image" }?.url,
                                            false,
                                            emptyList(),
                                            scope
                                        )
                                    )
                                }
                            }
                    }
                    if (posts.isEmpty() || body.meta?.hasMore != true) break
                    page++
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                remoteError = true
            }
            if (identity() != active) return@withContext PlayerLibrary(emptyList(), "", -1, true)
            val ordered = (local + saved).distinctBy { it.id }.sortedByDescending { it.dateEpochMs }
            val selected = ordered.firstOrNull { it.id == preferredTrackId }
            // Giữ nguyên giới hạn IPC: dành một chỗ cho bài được user chọn thay vì bỏ qua ID cũ.
            // Chỉ metadata đổi vị trí; file vẫn thuộc Journal và không bị player xóa hay sao chép.
            val bounded = if (selected != null && ordered.take(50)
                    .none { it.id == selected.id }
            ) ordered.take(49) + selected else ordered.take(50)
            PlayerLibrary(
                bounded.map { it.copy(favorite = it.id in favorites) },
                scope,
                active.generation,
                remoteError
            )
        }

    suspend fun favorite(scope: String, id: String, value: Boolean) = withContext(Dispatchers.IO) {
        val active = identity()
        check(active != null && active.ownerId + "|" + active.source == scope)
        app.playerPreferences.edit { prefs ->
            val k = key(scope)
            val set = prefs[k].orEmpty()
            prefs[k] = if (value) set + id else set - id
        }
    }
}
