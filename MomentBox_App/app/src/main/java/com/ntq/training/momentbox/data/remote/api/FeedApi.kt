package com.ntq.training.momentbox.data.remote.api

import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.remote.dto.BookmarkResponseDto
import com.ntq.training.momentbox.data.remote.dto.CommentDto
import com.ntq.training.momentbox.data.remote.dto.CommentLikeResponseDto
import com.ntq.training.momentbox.data.remote.dto.LikeResponseDto
import com.ntq.training.momentbox.data.remote.dto.PostCommentRequest
import com.ntq.training.momentbox.data.remote.dto.PostDto
import com.ntq.training.momentbox.data.remote.dto.RawFeedItemDto
import com.ntq.training.momentbox.data.remote.dto.StoryDto
import com.ntq.training.momentbox.data.remote.dto.UserDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

interface FeedApi {
    @Multipart
    @POST("v1/posts")
    suspend fun createPost(
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part media: List<MultipartBody.Part>,
        @Part voice: MultipartBody.Part?,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<PostDto>>

    /** Lấy danh sách story ở đầu bảng tin */
    @GET("v1/stories")
    suspend fun getStories(
        @Tag session: SessionRequest? = null
    ): Response<ApiResponse<List<StoryDto>>>

    /** Lấy danh sách bài viết đa hình trên bảng tin theo phân trang */
    @GET("v1/feed")
    suspend fun getFeed(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<List<RawFeedItemDto>>>

    /** Lấy chi tiết một bài viết cụ thể */
    @GET("v1/posts/{id}")
    suspend fun getPostDetail(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<PostDto>>

    /** Thích một bài viết */
    @POST("v1/posts/{id}/like")
    suspend fun likePost(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<LikeResponseDto>>

    /** Bỏ thích một bài viết */
    @DELETE("v1/posts/{id}/like")
    suspend fun unlikePost(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<LikeResponseDto>>

    /** Lưu một bài viết vào bộ sưu tập cá nhân */
    @POST("v1/posts/{id}/bookmark")
    suspend fun bookmarkPost(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<BookmarkResponseDto>>

    /** Hủy lưu bài viết */
    @DELETE("v1/posts/{id}/bookmark")
    suspend fun unbookmarkPost(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<BookmarkResponseDto>>

    /** Xóa bài viết của chính mình */
    @DELETE("v1/posts/{id}")
    suspend fun deletePost(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<Unit>>

    /** Lấy danh sách bình luận của bài viết */
    @GET("v1/posts/{id}/comments")
    suspend fun getComments(
        @Path("id") id: Long,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("sort") sort: String? = null,
        @Query("parentId") parentId: Long? = null,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<List<CommentDto>>>

    /** Gửi bình luận mới vào bài viết hoặc phản hồi bình luận khác */
    @POST("v1/posts/{id}/comments")
    suspend fun postComment(
        @Path("id") id: Long,
        @Body body: PostCommentRequest,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<CommentDto>>

    /** Thích bình luận */
    @POST("v1/comments/{id}/like")
    suspend fun likeComment(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<CommentLikeResponseDto>>

    /** Bỏ thích bình luận */
    @DELETE("v1/comments/{id}/like")
    suspend fun unlikeComment(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<CommentLikeResponseDto>>

    @GET("v1/users/{id}")
    suspend fun getUser(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<UserDto>>

    /** Theo dõi người dùng */
    @POST("v1/users/{id}/follow")
    suspend fun followUser(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<UserDto>>

    /** Bỏ theo dõi người dùng */
    @DELETE("v1/users/{id}/follow")
    suspend fun unfollowUser(
        @Path("id") id: Long,
        @Tag session: SessionRequest? = null,
    ): Response<ApiResponse<UserDto>>

    /** Lấy danh sách gợi ý bạn bè */
    @GET("v1/users/suggestions")
    suspend fun getSuggestions(
        @Tag session: SessionRequest? = null
    ): Response<ApiResponse<List<UserDto>>>
}
