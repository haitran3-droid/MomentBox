package com.ntq.training.momentbox.core.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import okhttp3.Request
import okio.Buffer

internal class OfflineFeedState(
    private val pages: () -> List<String>,
    private val commentTemplate: () -> String,
    private val meTemplate: () -> String = { DEFAULT_ME_JSON },
    private val extraPosts: () -> List<String> = { emptyList() }
) {
    private val posts = linkedMapOf<Long, JsonObject>()
    private val users = linkedMapOf<Long, JsonObject>()
    private val following = mutableMapOf<Long, Boolean>()
    private val comments = mutableMapOf<Pair<Long, Long?>, JsonArray>()
    private var meUser: JsonObject? = null
    private var seeded = false
    private var nextCommentId = 10000L

    @Synchronized
    fun clear() {
        posts.clear()
        users.clear()
        following.clear()
        comments.clear()
        meUser = null
        seeded = false
        nextCommentId = 10000L
    }

    private fun seed() {
        if (seeded) return
        val parsedMe = runCatching {
            JsonParser.parseString(meTemplate()).asJsonObject.getAsJsonObject("data")?.deepCopy()
        }.getOrNull()
        if (parsedMe != null && parsedMe.has("id")) {
            meUser = parsedMe
            users[parsedMe.get("id").asLong] = parsedMe.deepCopy()
        }
        for (page in pages()) {
            for (entry in JsonParser.parseString(page).asJsonObject.getAsJsonArray("data")) {
                val item = entry.asJsonObject
                item.getAsJsonObject("post")?.let { post ->
                    posts[post.get("id").asLong] = post.deepCopy()
                    post.getAsJsonObject("author")?.let { author ->
                        val authorId = author.get("id").asLong
                        if (authorId != meId()) {
                            users.putIfAbsent(authorId, author.deepCopy())
                        }
                    }
                }
                item.getAsJsonArray("users")?.forEach { user ->
                    val u = user.asJsonObject
                    val uid = u.get("id").asLong
                    if (uid != meId()) {
                        users[uid] = u.deepCopy()
                    }
                }
            }
        }
        for (rawPost in extraPosts()) {
            runCatching { JsonParser.parseString(rawPost).asJsonObject }.getOrNull()?.let { post ->
                if (post.has("id")) {
                    posts.putIfAbsent(post.get("id").asLong, post.deepCopy())
                    post.getAsJsonObject("author")?.let { author ->
                        val authorId = author.get("id").asLong
                        if (authorId != meId()) {
                            users.putIfAbsent(authorId, author.deepCopy())
                        }
                    }
                }
            }
        }
        seeded = true
    }

    private fun meId(): Long = meUser?.get("id")?.asLong ?: 1L

    private fun authorIdOf(post: JsonObject): Long? =
        post.getAsJsonObject("author")?.get("id")?.takeUnless { it.isJsonNull }?.asLong

    private fun currentMeSnapshot(): JsonObject {
        val me = requireNotNull(meUser).deepCopy()
        val id = me.get("id").asLong
        me.addProperty("postCount", posts.values.count { authorIdOf(it) == id })
        me.addProperty("isMe", true)
        me.addProperty("isFollowing", false)
        return me
    }

    private fun userSnapshot(id: Long): JsonObject? {
        if (id == meId() && meUser != null) return currentMeSnapshot()
        val base = users[id]?.deepCopy() ?: return null
        val isFollowing = following[id] ?: base.get("isFollowing")?.asBoolean ?: false
        base.addProperty("isFollowing", isFollowing)
        base.addProperty("postCount", posts.values.count { authorIdOf(it) == id })
        if (!base.has("followerCount") || base.get("followerCount").isJsonNull) {
            base.addProperty("followerCount", 0)
        }
        if (!base.has("followingCount") || base.get("followingCount").isJsonNull) {
            base.addProperty("followingCount", 0)
        }
        base.remove("email")
        return base
    }

    private fun success(data: JsonElement? = null): Pair<Int, String> = 200 to JsonObject().apply {
        addProperty("success", true)
        add("data", data ?: JsonNull.INSTANCE)
    }.toString()

    private fun successWithMeta(
        data: JsonArray,
        page: Int,
        pageSize: Int,
        total: Int,
        hasMore: Boolean
    ): Pair<Int, String> = 200 to JsonObject().apply {
        addProperty("success", true)
        add("data", data)
        add(
            "meta",
            JsonObject().apply {
                addProperty("page", page)
                addProperty("pageSize", pageSize)
                addProperty("total", total)
                addProperty("hasMore", hasMore)
            }
        )
    }.toString()

    private fun paginatePosts(
        list: List<JsonObject>,
        request: Request
    ): Pair<Int, String> {
        val page = (request.url.queryParameter("page")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize =
            (request.url.queryParameter("pageSize")?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val total = list.size
        val start = ((page.toLong() - 1) * pageSize).coerceAtMost(total.toLong()).toInt()
        val slice = list.drop(start).take(pageSize)
        val hasMore = start + slice.size < total
        val data = JsonArray().apply {
            slice.forEach { add(it.deepCopy()) }
        }
        return successWithMeta(data, page, pageSize, total, hasMore)
    }

    private fun missing(): Pair<Int, String> =
        404 to """{"success":false,"error":{"code":"NOT_FOUND","message":"Offline fixture not found"}}"""

    private fun validationError(field: String): Pair<Int, String> =
        400 to """{"success":false,"error":{"code":"VALIDATION_ERROR","message":"Invalid $field"}}"""

    private fun requestBody(request: Request): JsonObject {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return runCatching { JsonParser.parseString(buffer.readUtf8()).asJsonObject }
            .getOrElse { JsonObject() }
    }

    @Synchronized
    fun resolve(request: Request, template: String): Pair<Int, String> {
        val path = request.url.encodedPath
        val method = request.method
        if (path.endsWith("/auth/login") || path.endsWith("/auth/logout")) {
            clear()
            return if (path.endsWith("/logout")) success(JsonObject()) else 200 to template
        }
        if (path.endsWith("/config") || path.endsWith("/auth/refresh") || path.endsWith("/stories")) {
            return 200 to template
        }
        seed()
        if (path.endsWith("/me/bookmarks")) {
            val bookmarked = posts.values.filter { it.get("isBookmarked")?.asBoolean == true }
            return paginatePosts(bookmarked, request)
        }
        if (path.endsWith("/me")) {
            val me = meUser ?: return 200 to template
            if (method == "PATCH") {
                val body = requestBody(request)
                if (body.has("displayName")) {
                    val displayName = body.get("displayName")
                        ?.takeUnless { it.isJsonNull }
                        ?.asString
                        ?.trim()
                        .orEmpty()
                    if (displayName.isEmpty()) return validationError("displayName")
                    me.addProperty("displayName", displayName)
                    val id = me.get("id").asLong
                    posts.values.forEach { post ->
                        if (authorIdOf(post) == id) {
                            post.getAsJsonObject("author")?.addProperty("displayName", displayName)
                        }
                    }
                }
                if (body.has("bio")) {
                    val bioElement = body.get("bio")
                    if (bioElement.isJsonNull) {
                        me.add("bio", JsonNull.INSTANCE)
                    } else {
                        me.addProperty("bio", bioElement.asString)
                    }
                }
                if (body.has("location")) {
                    val locationElement = body.get("location")
                    if (locationElement.isJsonNull) {
                        me.add("location", JsonNull.INSTANCE)
                    } else {
                        me.addProperty("location", locationElement.asString)
                    }
                }
                users[me.get("id").asLong] = me.deepCopy()
            }
            return success(currentMeSnapshot())
        }
        if (path.endsWith("/feed")) {
            val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
            val originalPages = pages()
            val data = JsonArray()
            if (page in 1..originalPages.size) {
                for (entry in JsonParser.parseString(originalPages[page - 1]).asJsonObject
                    .getAsJsonArray("data")
                ) {
                    val item = entry.asJsonObject.deepCopy()
                    val post = item.getAsJsonObject("post")
                    if (post != null) {
                        val current = posts[post.get("id").asLong] ?: continue
                        item.add("post", current.deepCopy())
                    } else {
                        item.getAsJsonArray("users")?.forEach { user ->
                            val u = user.asJsonObject
                            following[u.get("id").asLong]?.let { u.addProperty("isFollowing", it) }
                        }
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
                if (like && was != now) {
                    post.addProperty(
                        "likeCount",
                        (post.get("likeCount").asInt + if (now) 1 else -1).coerceAtLeast(0)
                    )
                }
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
                        if (parent == null) base.size()
                        else comments[id to null]?.asSequence()
                            ?.map { it.asJsonObject }
                            ?.firstOrNull { it.get("id").asLong == parent }
                            ?.get("replyCount")?.asInt ?: 0
                    JsonArray().apply {
                        base.take(count).forEach { templateRow ->
                            add(templateRow.deepCopy().asJsonObject.apply {
                                // Comment-like API nhận commentId toàn cục, không kèm postId: không được tái dùng 1/2 giữa các bài.
                                addProperty("id", ++nextCommentId)
                                addProperty("postId", id)
                                if (parent != null) addProperty("parentId", parent)
                            })
                        }
                    }
                }
                if (method == "POST") {
                    val clientId = body.get("clientId")
                        ?.takeUnless { it.isJsonNull }
                        ?.asString
                        ?.lowercase()

                    if (clientId != null) {
                        val existing = rows.asSequence()
                            .map { it.asJsonObject }
                            .firstOrNull { item ->
                                item.get("clientId")?.takeUnless { it.isJsonNull }?.asString == clientId
                            }
                        if (existing != null) {
                            return success(
                                existing.deepCopy().apply {
                                    addProperty("postCommentCount", post.get("commentCount").asInt)
                                    if (parent != null) {
                                        addProperty("parentReplyCount", rows.size())
                                    }
                                }
                            )
                        }
                    }

                    val createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date())
                    val row = JsonParser.parseString(template)
                        .asJsonObject
                        .getAsJsonObject("data")
                        .deepCopy()
                        .apply {
                            addProperty("id", ++nextCommentId)
                            addProperty("postId", id)
                            addProperty("content", body.get("content")?.asString.orEmpty())
                            if (clientId != null) addProperty("clientId", clientId)
                            addProperty("createdAt", createdAt)
                            if (parent == null) {
                                add("parentId", JsonNull.INSTANCE)
                            } else {
                                addProperty("parentId", parent)
                            }
                        }

                    rows.add(row)
                    if (parent != null) {
                        comments[id to null]?.forEach { root ->
                            val rootObj = root.asJsonObject
                            if (rootObj.get("id").asLong == parent) {
                                rootObj.addProperty("replyCount", rootObj.get("replyCount").asInt + 1)
                            }
                        }
                    }

                    val updatedPostCount = post.get("commentCount").asInt + 1
                    post.addProperty("commentCount", updatedPostCount)

                    return success(
                        row.deepCopy().apply {
                            addProperty("postCommentCount", updatedPostCount)
                            if (parent != null) {
                                addProperty("parentReplyCount", rows.size())
                            }
                        }
                    )
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
        val userPostsMatch = Regex(".*/users/(\\d+)/posts").matchEntire(path)
        if (userPostsMatch != null) {
            val id = userPostsMatch.groupValues[1].toLong()
            if (id != meId() && !users.containsKey(id)) return missing()
            val typeFilter = request.url.queryParameter("type")?.takeIf { it.isNotBlank() }
            val userPosts = posts.values.filter { post ->
                authorIdOf(post) == id &&
                    (typeFilter == null || post.get("type")?.asString.equals(typeFilter, ignoreCase = true))
            }
            return paginatePosts(userPosts, request)
        }
        val userMatch = Regex(".*/users/(\\d+)(/follow)?").matchEntire(path)
        if (userMatch != null) {
            val id = userMatch.groupValues[1].toLong()
            if (path.endsWith("/follow")) {
                if (id == meId()) return validationError("userId")
                val stored = users[id] ?: return missing()
                val wasFollowing = following[id] ?: stored.get("isFollowing")?.asBoolean ?: false
                val nowFollowing = method == "POST"
                following[id] = nowFollowing
                if (wasFollowing != nowFollowing) {
                    val delta = if (nowFollowing) 1 else -1
                    val currentFollowers = stored.get("followerCount")?.takeUnless { it.isJsonNull }?.asInt ?: 0
                    stored.addProperty("followerCount", (currentFollowers + delta).coerceAtLeast(0))
                    meUser?.let { me ->
                        val currentFollowing = me.get("followingCount")?.takeUnless { it.isJsonNull }?.asInt ?: 0
                        me.addProperty("followingCount", (currentFollowing + delta).coerceAtLeast(0))
                        users[meId()] = me.deepCopy()
                    }
                }
                return success(userSnapshot(id) ?: return missing())
            }
            val user = userSnapshot(id) ?: return missing()
            return success(user)
        }
        if (path.endsWith("/users/suggestions")) {
            return success(JsonArray().apply {
                users.values
                    .filter { it.get("id").asLong != meId() }
                    .forEach {
                        add(
                            it.deepCopy().apply {
                                addProperty(
                                    "isFollowing",
                                    following[get("id").asLong] ?: get("isFollowing")?.asBoolean ?: false
                                )
                                remove("email")
                            }
                        )
                    }
            })
        }
        val commentMatch = Regex(".*/comments/(\\d+)/like").matchEntire(path)
        if (commentMatch != null) {
            val id = commentMatch.groupValues[1].toLong()
            val row = comments.values.flatMap { it.toList() }.map { it.asJsonObject }
                .firstOrNull { it.get("id").asLong == id } ?: return missing()
            val now = method == "POST"
            val was = row.get("isLiked")?.asBoolean ?: false
            if (now != was) {
                row.addProperty(
                    "likeCount",
                    (row.get("likeCount").asInt + if (now) 1 else -1).coerceAtLeast(0)
                )
            }
            row.addProperty("isLiked", now)
            return success(JsonObject().apply {
                addProperty("commentId", id)
                addProperty("isLiked", now)
                addProperty("likeCount", row.get("likeCount").asInt)
            })
        }
        return missing()
    }

    companion object {
        private const val DEFAULT_ME_JSON = """
            {
              "success": true,
              "data": {
                "id": 1,
                "username": "minhanh",
                "displayName": "Minh Anh",
                "avatarUrl": null,
                "email": "minhanh@momentbox.dev",
                "bio": "Đang dùng chế độ offline",
                "location": "Hà Nội",
                "postCount": 0,
                "followerCount": 128,
                "followingCount": 45
              }
            }
        """
    }
}
