package com.ntq.training.momentbox.data.local.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.ntq.training.momentbox.feature.create.SelectedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

enum class GalleryFilter {
    ALL,
    PHOTO,
    VIDEO,
    FAVORITE,
}

data class MediaAlbum(val id: String?, val name: String, val count: Int, val cover: String?)

/** Cursor được đóng ngay sau trang60, chỉ giữ metadata, thumbnail do Glide quản lý. */
class MediaStoreRepository(private val resolver: ContentResolver) {
    private val collection = MediaStore.Files.getContentUri("external")
    private val projection =
        arrayOf(
            "_id",
            "_display_name",
            "bucket_id",
            "bucket_display_name",
            "date_added",
            "date_modified",
            "mime_type",
            "duration",
            "width",
            "height",
        )

    private fun where(
        filter: GalleryFilter,
        album: String?,
        imagesOnly: Boolean,
    ): Pair<String, Array<String>> {
        val clauses =
            mutableListOf(
                if (imagesOnly || filter == GalleryFilter.PHOTO) "media_type=1"
                else if (filter == GalleryFilter.VIDEO) "media_type=3" else "media_type IN (1,3)"
            )
        val args = mutableListOf<String>()
        if (album != null) {
            clauses.add("bucket_id=?")
            args.add(album)
        }
        if (filter == GalleryFilter.FAVORITE) {
            // IS_FAVORITE không tồn tại trước API30, UI cũng ẩn mục này.
            clauses.add(if (Build.VERSION.SDK_INT >= 30) "is_favorite=1" else "0")
        }
        if (Build.VERSION.SDK_INT >= 29) clauses.add("is_pending=0")
        if (Build.VERSION.SDK_INT >= 30) clauses.add("is_trashed=0")
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    suspend fun page(
        filter: GalleryFilter,
        album: String?,
        imagesOnly: Boolean,
        offset: Int,
    ): List<SelectedMedia> =
        withContext(Dispatchers.IO) {
            val (selection, args) = where(filter, album, imagesOnly)
            val cursor =
                if (Build.VERSION.SDK_INT >= 26)
                    resolver.query(
                        collection,
                        projection,
                        Bundle().apply {
                            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                            putString(
                                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                                "date_added DESC, _id DESC",
                            )
                            putInt(ContentResolver.QUERY_ARG_LIMIT, 60)
                            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                        },
                        null,
                    )
                else
                    resolver.query(
                        collection,
                        projection,
                        selection,
                        args,
                        "date_added DESC, _id DESC LIMIT 60 OFFSET $offset",
                    )
            buildList {
                cursor?.use { c ->
                    while (c.moveToNext() && size < 60) {
                        val id = c.getLong(0)

                        val mime = c.getString(6) ?: continue
                        val uri =
                            ContentUris.withAppendedId(
                                if (mime.startsWith("video/"))
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id,
                            )
                        add(
                            SelectedMedia(
                                uri.toString(),
                                uri.toString(),
                                mime,
                                if (c.isNull(7)) null else c.getLong(7),
                                c.getInt(8),
                                c.getInt(9),
                                c.getLong(5),
                                c.getString(2),
                                c.getString(3),
                            )
                        )
                    }
                }
            }
        }

    suspend fun albums(imagesOnly: Boolean): List<MediaAlbum> =
        withContext(Dispatchers.IO) {
            // Quét metadata để đếm album, không decode ảnh hoặc giữ1000bitmap trong RAM.
            val (selection, args) = where(GalleryFilter.ALL, null, imagesOnly)
            val groups = linkedMapOf<String, MediaAlbum>()

            var total = 0

            var cover: String? = null
            resolver
                .query(
                    collection,
                    arrayOf("_id", "bucket_id", "bucket_display_name", "mime_type"),
                    selection,
                    args,
                    "date_added DESC, _id DESC",
                )
                ?.use { c ->
                    while (c.moveToNext()) {
                        val bucket = c.getString(1) ?: ""

                        val mime = c.getString(3) ?: continue
                        val uri =
                            ContentUris.withAppendedId(
                                    if (mime.startsWith("video/"))
                                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    c.getLong(0),
                                )
                                .toString()
                        if (cover == null) cover = uri
                        total++
                        val old = groups[bucket]
                        groups[bucket] =
                            MediaAlbum(
                                bucket,
                                c.getString(2) ?: "",
                                (old?.count ?: 0) + 1,
                                old?.cover ?: uri,
                            )
                    }
                }
            listOf(MediaAlbum(null, "", total, cover)) + groups.values
        }

    fun changes() = callbackFlow {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
        resolver.registerContentObserver(collection, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
}
