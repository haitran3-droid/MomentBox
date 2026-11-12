package com.ntq.training.momentbox.core.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Request
import okio.Buffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class OfflineFeedState(
    private val pages: () -> List<String>,
    private val commentTemplate: () -> String
) {
    private val posts = linkedMapOf<Long, JsonObject>()
    private val users = linkedMapOf<Long, JsonObject>()
    private val following = mutableMapOf<Long, Boolean>()
    private val comments = mutableMapOf<Pair<Long, Long?>, JsonArray>()
    private var seeded = false
    private var nextCommentId = 10000L

    @Synchronized
    fun clear() {
        posts.clear()
        users.clear()
        following.clear()
        comments.clear()
        seeded = false
        nextCommentId = 10000L
    }

    private fun seed() {
        if (seeded) return
        for (page in pages()) for (entry in JsonParser.parseString(page).asJsonObject.getAsJsonArray(
            "data"
        )) {
            val item = entry.asJsonObject
            item.getAsJsonObject("post")?.let { post ->
                posts[post.get("id").asLong] = post.deepCopy()
                post.getAsJsonObject("author")?.let { users[it.get("id").asLong] = it.deepCopy() }
            }
            item.getAsJsonArray("users")?.forEach { user ->
                users[user.asJsonObject.get("id").asLong] = user.asJsonObject.deepCopy()
            }
        }
        seeded = true
    }

    private fun success(data: JsonElement? = null): Pair<Int, String> = 200 to JsonObject().apply {
        addProperty("success", true)
        add("data", data ?: JsonNull.INSTANCE)
    }.toString()

    private fun missing(): Pair<Int, String> =
        404 to """{"success":false,"error":{"code":"NOT_FOUND","message":"Offline fixture not found"}}"""

    private fun requestBody(request: Request): JsonObject {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return runCatching { JsonParser.parseString(buffer.readUtf8()).asJsonObject }.getOrElse { JsonObject() }
    }

    @Synchronized
    fun resolve(request: Request, template: String): Pair<Int, String> {
        val path = request.url.encodedPath
        val method = request.method
        if (path.endsWith("/auth/login") || path.endsWith("/auth/logout")) {
            clear()
            return if (path.endsWith("/logout")) success(JsonObject()) else 200 to template
        }
        if (path.endsWith("/config") || path.endsWith("/auth/refresh") || path.endsWith("/me") || path.endsWith(
                "/stories"
            )
        ) {
            return 200 to template
        }
        seed()
        if (path.endsWith("/feed")) {
            val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
            val originalPages = pages()
            val data = JsonArray()
            if (page in 1..originalPages.size) {
                for (entry in JsonParser.parseString(originalPages[page - 1]).asJsonObject.getAsJsonArray(
                    "data"
                )) {
                    val item = entry.asJsonObject.deepCopy()
                    val post = item.getAsJsonObject("post")
                    if (post != null) {
                        val current = posts[post.get("id").asLong] ?: continue
                        item.add("post", current.deepCopy())
                    } else item.getAsJsonArray("users")?.forEach { user ->
                        val u = user.asJsonObject
                        following[u.get("id").asLong]?.let { u.addProperty("isFollowing", it) }
                    }
                    data.add(item)
                }
            }
            return success(data)
        }
        val postMatch = Regex(".*/posts/(\\d+)(?:/.*)?").matchEntire(path)
        if (postMatch != null) {
            val id = postMatch.groupValues[1].toLong()
            val post = posts[id] ?: return missing()
            if (path.endsWith("/like") || path.endsWith("/bookmark")) {
                val like = path.endsWith("/like")
                val field = if (like) "isLiked" else "isBookmarked"
                val was = post.get(field)?.asBoolean ?: false
                val now = method == "POST"
                post.addProperty(field, now)
                if (like && was != now) post.addProperty(
                    "likeCount",
                    (post.get("likeCount").asInt + if (now) 1 else -1).coerceAtLeast(0)
                )
                return success(JsonObject().apply {
                    addProperty("postId", id)
                    addProperty(field, now)
                    if (like) addProperty("likeCount", post.get("likeCount").asInt)
                })
            }
            if (path.endsWith("/comments")) {
                val body = if (method == "POST") requestBody(request) else JsonObject()
                val parent =
                    if (method == "POST") body.get("parentId")?.takeUnless { it.isJsonNull }?.asLong
                    else request.url.queryParameter("parentId")?.toLongOrNull()
                val rows = comments.getOrPut(id to parent) {
                    val base =
                        JsonParser.parseString(commentTemplate()).asJsonObject.getAsJsonArray("data")
                    val count =
                        if (parent == null) base.size() else comments[id to null]?.asSequence()
                            ?.map { it.asJsonObject }?.firstOrNull { it.get("id").asLong == parent }
                            ?.get("replyCount")?.asInt ?: 0
                    JsonArray().apply {
                        base.take(count).forEach { templateRow ->
                            add(templateRow.deepCopy().asJsonObject.apply {
                                // Comment-like API nhận commentId toàn cục, không kèm postId: không được tái dùng1/2 giữa các bài.
                                addProperty("id", ++nextCommentId)
                                addProperty("postId", id)
                                if (parent != null) addProperty("parentId", parent)
                            })
                        }
                    }
                }
                if (method == "POST") {
                    val row = JsonParser.parseString(template).asJsonObject.getAsJsonObject("data")
                        .deepCopy().apply {
                        addProperty("id", ++nextCommentId)
                        addProperty("postId", id)
                        addProperty("content", body.get("content")?.asString.orEmpty())
                        addProperty(
                            "createdAt",
                            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }.format(Date())
                        )
                        if (parent == null) add(
                            "parentId",
                            JsonNull.INSTANCE
                        ) else addProperty("parentId", parent)
                    }
                    rows.add(row)
                    if (parent != null) comments[id to null]?.forEach { root ->
                        root.asJsonObject.let {
                            if (it.get("id").asLong == parent) it.addProperty(
                                "replyCount",
                                it.get("replyCount").asInt + 1
                            )
                        }
                    }
                    post.addProperty("commentCount", post.get("commentCount").asInt + 1)
                    return success(row)
                }
                val ordered =
                    if (request.url.queryParameter("sort") == "newest") rows.reversed() else rows.toList()
                val page = (request.url.queryParameter("page")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                val size =
                    (request.url.queryParameter("pageSize")?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val start = ((page.toLong() - 1) * size).coerceAtMost(ordered.size.toLong()).toInt()
                return success(JsonArray().apply {
                    ordered.drop(start).take(size).forEach { add(it.deepCopy()) }
                })
            }
            if (method == "DELETE") {
                posts.remove(id)
                return success()
            }
            if (method == "GET") return success(post.deepCopy())
        }
        val userMatch = Regex(".*/users/(\\d+)(/follow)?").matchEntire(path)
        if (userMatch != null) {
            val id = userMatch.groupValues[1].toLong()
            val user = users[id]?.deepCopy() ?: return missing()
            if (path.endsWith("/follow")) following[id] = method == "POST"
            user.addProperty(
                "isFollowing",
                following[id] ?: user.get("isFollowing")?.asBoolean ?: false
            )
            return success(user)
        }
        if (path.endsWith("/users/suggestions")) return success(JsonArray().apply {
            users.values.forEach {
                add(
                    it.deepCopy().apply {
                        addProperty(
                            "isFollowing",
                            following[get("id").asLong] ?: get("isFollowing")?.asBoolean ?: false
                        )
                    })
            }
        })
        val commentMatch = Regex(".*/comments/(\\d+)/like").matchEntire(path)
        if (commentMatch != null) {
            val id = commentMatch.groupValues[1].toLong()
            val row = comments.values.flatMap { it.toList() }.map { it.asJsonObject }
                .firstOrNull { it.get("id").asLong == id } ?: return missing()
            val now = method == "POST"
            val was = row.get("isLiked")?.asBoolean ?: false
            if (now != was) row.addProperty(
                "likeCount",
                (row.get("likeCount").asInt + if (now) 1 else -1).coerceAtLeast(0)
            )
            row.addProperty("isLiked", now)
            return success(JsonObject().apply {
                addProperty("commentId", id)
                addProperty(
                "isLiked",
                now
            )
            addProperty("likeCount", row.get("likeCount").asInt)
            })
        }
        return missing()
    }
}