package com.ntq.training.momentbox

import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.safeApiCall
import com.ntq.training.momentbox.data.remote.dto.CommentLikeResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class CommentLikeResponseTest {
    @Test
    fun successfulLegacyCommentLikeWithNullDataUsesRequestedState() = runTest {
        val result = safeApiCall(emptySuccess = { CommentLikeResponseDto(isLiked = true) }) {
            Response.success(ApiResponse<CommentLikeResponseDto>(success = true, data = null))
        }

        assertTrue(result.getOrThrow().isLiked)
    }

    @Test
    fun serverErrorsStillFailWhenEmptySuccessIsAllowed() = runTest {
        val result = safeApiCall(emptySuccess = { CommentLikeResponseDto(isLiked = true) }) {
            Response.success(ApiResponse<CommentLikeResponseDto>(success = false, data = null))
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun emptySuccessStillFailsWithoutExplicitFallback() = runTest {
        val result = safeApiCall {
            Response.success(ApiResponse<CommentLikeResponseDto>(success = true, data = null))
        }

        assertFalse(result.isSuccess)
    }
}
