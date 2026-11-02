package com.example.momentbox_app.data.remote.api

import com.example.momentbox_app.core.network.ApiResponse
import com.example.momentbox_app.data.remote.dto.BookmarkResponseDto
import com.example.momentbox_app.data.remote.dto.CommentDto
import com.example.momentbox_app.data.remote.dto.CommentLikeResponseDto
import com.example.momentbox_app.data.remote.dto.LikeResponseDto
import com.example.momentbox_app.data.remote.dto.PostCommentRequest
import com.example.momentbox_app.data.remote.dto.PostDto
import com.example.momentbox_app.data.remote.dto.RawFeedItemDto
import com.example.momentbox_app.data.remote.dto.StoryDto
import com.example.momentbox_app.data.remote.dto.UserDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface FeedApi {
    /** Lấy danh sách story ở đầu bảng tin */
    @GET("v1/stories")
    suspend fun getStories(): Response<ApiResponse<List<StoryDto>>>

    /** Lấy danh sách bài viết đa hình trên bảng tin theo phân trang */
    @GET("v1/feed")
    suspend fun getFeed(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int
    ): Response<ApiResponse<List<RawFeedItemDto>>>

    /** Lấy chi tiết một bài viết cụ thể */
    @GET("v1/posts/{id}")
    suspend fun getPostDetail(
        @Path("id") id: Long
    ): Response<ApiResponse<PostDto>>

    /** Thích một bài viết */
    @POST("v1/posts/{id}/like")
    suspend fun likePost(
        @Path("id") id: Long
    ): Response<ApiResponse<LikeResponseDto>>

    /** Bỏ thích một bài viết */
    @DELETE("v1/posts/{id}/like")
    suspend fun unlikePost(
        @Path("id") id: Long
    ): Response<ApiResponse<LikeResponseDto>>

    /** Lưu một bài viết vào bộ sưu tập cá nhân */
    @POST("v1/posts/{id}/bookmark")
    suspend fun bookmarkPost(
        @Path("id") id: Long
    ): Response<ApiResponse<BookmarkResponseDto>>

    /** Hủy lưu bài viết */
    @DELETE("v1/posts/{id}/bookmark")
    suspend fun unbookmarkPost(
        @Path("id") id: Long
    ): Response<ApiResponse<BookmarkResponseDto>>

    /** Xóa bài viết của chính mình */
    @DELETE("v1/posts/{id}")
    suspend fun deletePost(
        @Path("id") id: Long
    ): Response<ApiResponse<Unit>>

    /** Lấy danh sách bình luận của bài viết */
    @GET("v1/posts/{id}/comments")
    suspend fun getComments(
        @Path("id") id: Long,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("sort") sort: String? = null,
        @Query("parentId") parentId: Long? = null
    ): Response<ApiResponse<List<CommentDto>>>

    /** Gửi bình luận mới vào bài viết hoặc phản hồi bình luận khác */
    @POST("v1/posts/{id}/comments")
    suspend fun postComment(
        @Path("id") id: Long,
        @Body body: PostCommentRequest
    ): Response<ApiResponse<CommentDto>>

    /** Thích bình luận */
    @POST("v1/comments/{id}/like")
    suspend fun likeComment(
        @Path("id") id: Long
    ): Response<ApiResponse<CommentLikeResponseDto>>

    /** Bỏ thích bình luận */
    @DELETE("v1/comments/{id}/like")
    suspend fun unlikeComment(
        @Path("id") id: Long
    ): Response<ApiResponse<CommentLikeResponseDto>>

    /** Theo dõi người dùng */
    @POST("v1/users/{id}/follow")
    suspend fun followUser(
        @Path("id") id: Long
    ): Response<ApiResponse<UserDto>>

    /** Bỏ theo dõi người dùng */
    @DELETE("v1/users/{id}/follow")
    suspend fun unfollowUser(
        @Path("id") id: Long
    ): Response<ApiResponse<UserDto>>

    /** Lấy danh sách gợi ý bạn bè */
    @GET("v1/users/suggestions")
    suspend fun getSuggestions(): Response<ApiResponse<List<UserDto>>>
}