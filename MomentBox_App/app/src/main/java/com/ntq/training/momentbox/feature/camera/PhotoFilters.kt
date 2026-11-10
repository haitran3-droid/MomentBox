package com.ntq.training.momentbox.feature.camera

enum class PhotoFilter {
    ORIGINAL,
    WARM,
    COOL,
    MONO,
    VINTAGE,
    NEGATIVE,
}

object PhotoFilters {
    fun matrix(filter: PhotoFilter): FloatArray =
        when (filter) {
            PhotoFilter.ORIGINAL ->
                floatArrayOf(
                    1f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )

            PhotoFilter.WARM ->
                floatArrayOf(
                    1.08f,
                    .08f,
                    0f,
                    0f,
                    5f,
                    .03f,
                    1.01f,
                    0f,
                    0f,
                    2f,
                    0f,
                    .02f,
                    .85f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )

            PhotoFilter.COOL ->
                floatArrayOf(
                    .9f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1.02f,
                    .02f,
                    0f,
                    0f,
                    0f,
                    .06f,
                    1.1f,
                    0f,
                    3f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )

            PhotoFilter.MONO ->
                floatArrayOf(
                    .213f,
                    .715f,
                    .072f,
                    0f,
                    0f,
                    .213f,
                    .715f,
                    .072f,
                    0f,
                    0f,
                    .213f,
                    .715f,
                    .072f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )

            PhotoFilter.VINTAGE ->
                floatArrayOf(
                    .393f,
                    .769f,
                    .189f,
                    0f,
                    0f,
                    .349f,
                    .686f,
                    .168f,
                    0f,
                    0f,
                    .272f,
                    .534f,
                    .131f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )

            PhotoFilter.NEGATIVE ->
                floatArrayOf(
                    -1f,
                    0f,
                    0f,
                    0f,
                    255f,
                    0f,
                    -1f,
                    0f,
                    0f,
                    255f,
                    0f,
                    0f,
                    -1f,
                    0f,
                    255f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )
        }

    fun channels(filter: PhotoFilter, r: Float, g: Float, b: Float): FloatArray {
        val m = matrix(filter)
        return FloatArray(3) { i ->
            (m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 4]).coerceIn(0f, 255f)
        }
    }
}
