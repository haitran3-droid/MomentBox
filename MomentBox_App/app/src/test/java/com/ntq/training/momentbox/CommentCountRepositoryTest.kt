package com.ntq.training.momentbox

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.remote.api.DummyJsonApi
import com.ntq.training.momentbox.data.remote.api.FeedApi
import com.ntq.training.momentbox.data.repository.FeedRepositoryImpl
import com.ntq.training.momentbox.domain.model.FeedItem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class CommentCountRepositoryTest {
    @Test
    fun failedWriteThenSuccessfulRetryUpdatesFeedAndDetailThroughAnOlderRead() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var status = 200
            val post = """{"id":1,"commentCount":3}"""
            val data = when (request.url.encodedPath) {
                "/v1/posts/1" -> post
                "/v1/feed" -> {
                    reading.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    """[{"kind":"post","post":$post}]"""
                }
                "/v1/posts/1/comments" -> when (writes.incrementAndGet()) {
                    1 -> { status = 503; "null" }
                    2 -> """{"id":10,"postId":1,"postCommentCount":7}"""
                    else -> """{"id":11,"postId":1}""" // Older server omits the count.
                }
                else -> error(request.url)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("response")
                .body("""{"success":true,"data":$data}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = repository(client)
        assertEquals(3, repo.getPostDetail(1).getOrThrow().commentCount)
        val stale = async(Dispatchers.IO) { repo.getFeed(2).getOrThrow() }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertTrue(repo.postComment(1, "reply", 5).isFailure)
            assertEquals(3, repo.postsMapFlow.value.getValue(1).commentCount)
            repo.postComment(1, "reply", 5).getOrThrow()
            assertEquals(7, repo.postsMapFlow.value.getValue(1).commentCount)
            repo.postComment(1, "another").getOrThrow()
        } finally {
            release.countDown()
        }
        assertEquals(8, (stale.await().single() as FeedItem.PostItem).post.commentCount)
        assertEquals(8, repo.postsMapFlow.value.getValue(1).commentCount)
        assertEquals(3, repo.getPostDetail(1).getOrThrow().commentCount)
    }

    @Test
    fun forbiddenWriteRefreshesPermissionAndAnOlderFeedReadCannotReopenComments() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val detailReads = AtomicInteger()
        val writeStatus = AtomicInteger(503)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var status = 200
            val body = when (request.url.encodedPath) {
                "/v1/posts/1" -> {
                    val open = detailReads.incrementAndGet() < 3
                    """{"success":true,"data":{"id":1,"allowComments":$open}}"""
                }
                "/v1/feed" -> {
                    reading.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    """{"success":true,"data":[{"kind":"post","post":{"id":1,"allowComments":true}}]}"""
                }
                "/v1/posts/1/comments" -> {
                    status = writeStatus.get()
                    val code = if (status == 403) "FORBIDDEN" else "INTERNAL"
                    """{"success":false,"error":{"code":"$code","message":"write rejected"}}"""
                }
                else -> error(request.url)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("response")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = repository(client)
        assertTrue(repo.getPostDetail(1).getOrThrow().allowComments)
        val stale = async(Dispatchers.IO) { repo.getFeed(2).getOrThrow() }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertTrue(repo.postComment(1, "offline").isFailure)
            assertTrue(repo.postsMapFlow.value.getValue(1).allowComments)
            assertEquals(1, detailReads.get())
            writeStatus.set(403)
            assertTrue(repo.postComment(1, "forbidden but open").isFailure)
            assertTrue(repo.postsMapFlow.value.getValue(1).allowComments)
            assertTrue(repo.postComment(1, "forbidden and closed").isFailure)
            assertFalse(repo.postsMapFlow.value.getValue(1).allowComments)
        } finally {
            release.countDown()
        }
        assertFalse((stale.await().single() as FeedItem.PostItem).post.allowComments)
        assertFalse(repo.postsMapFlow.value.getValue(1).allowComments)
    }

    @Test
    fun retryAfter502SendsSameClientIdAndMapsServerCountsWithoutDoubling() = runBlocking {
        val seenClientIds = mutableListOf<String?>()
        val writes = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var status = 200
            val data = when (request.url.encodedPath) {
                "/v1/posts/1" -> """{"id":1,"commentCount":2}"""
                "/v1/posts/1/comments" -> {
                    val buf = okio.Buffer()
                    request.body?.writeTo(buf)
                    val bodyText = buf.readUtf8()
                    val matchedClientId = Regex("\"clientId\"\\s*:\\s*\"([^\"]+)\"")
                        .find(bodyText)
                        ?.groupValues
                        ?.get(1)
                    seenClientIds += matchedClientId

                    when (writes.incrementAndGet()) {
                        1 -> {
                            status = 502
                            "null"
                        }
                        else -> """{
                            "id": 55,
                            "postId": 1,
                            "parentId": 7,
                            "content": "910002",
                            "clientId": "$matchedClientId",
                            "postCommentCount": 3,
                            "parentReplyCount": 1
                        }"""
                    }
                }
                else -> error(request.url)
            }

            val okFlag = status < 400
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("response")
                .body("""{"success":$okFlag,"data":$data}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val repo = repository(client)
        assertEquals(2, repo.getPostDetail(1).getOrThrow().commentCount)

        val cid = "11111111-1111-4111-8111-111111111111"
        assertTrue(repo.postComment(1, "910002", 7, cid).isFailure)
        assertEquals(2, repo.postsMapFlow.value.getValue(1).commentCount)

        val confirmed = repo.postComment(1, "910002", 7, cid).getOrThrow()
        assertEquals(listOf(cid, cid), seenClientIds)
        assertEquals(cid, confirmed.clientId)
        assertEquals(1, confirmed.serverParentReplyCount)
        assertEquals(3, repo.postsMapFlow.value.getValue(1).commentCount)
    }

    private fun repository(client: OkHttpClient): FeedRepositoryImpl {
        val retrofit = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        val preferences = object : DataStore<Preferences> {
            override val data = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = error("Unexpected write")
        }
        return FeedRepositoryImpl(retrofit.create(FeedApi::class.java),
            retrofit.create(DummyJsonApi::class.java), SettingsDataStore(preferences))
    }
}
