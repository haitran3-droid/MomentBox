package com.ntq.training.momentbox.feature.crop

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class Point(val x: Double, val y: Double)

data class CropRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width
        get() = right - left

    val height
        get() = bottom - top

    val cx
        get() = (left + right) / 2

    val cy
        get() = (top + bottom) / 2

    fun corners() =
        listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))
}

data class Affine(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0
) {
    fun map(p: Point) = Point(a * p.x + c * p.y + tx, b * p.x + d * p.y + ty)
    operator fun times(o: Affine) = Affine(
        a * o.a + c * o.b,
        b * o.a + d * o.b,
        a * o.c + c * o.d,
        b * o.c + d * o.d,
        a * o.tx + c * o.ty + tx,
        b * o.tx + d * o.ty + ty
    )

    fun inverse(): Affine {
        val determinant = a * d - b * c; require(abs(determinant) > 1e-12); return Affine(
            d / determinant,
            -b / determinant,
            -c / determinant,
            a / determinant,
            (c * ty - d * tx) / determinant,
            (b * tx - a * ty) / determinant
        )
    }

    companion object {
        fun translation(x: Double, y: Double) = Affine(tx = x, ty = y)
        fun scale(x: Double, y: Double) = Affine(a = x, d = y)
        fun rotation(degrees: Double): Affine {
            val rad = Math.toRadians(degrees); return Affine(
                cos(rad),
                sin(rad),
                -sin(rad),
                cos(rad)
            )
        }
    }
}

object CropMath {
    /** Đổi góc crop về tọa độ ảnh; phóng đủ rồi tịnh tiến để mọi góc đều có pixel nguồn. */
    fun cover(matrix: Affine, imageWidth: Double, imageHeight: Double, crop: CropRect): Affine {
        var result = matrix
        fun points() = crop.corners().map(result.inverse()::map)
        var corners = points()
        val factor = maxOf(
            (corners.maxOf { it.x } - corners.minOf { it.x }) / imageWidth,
            (corners.maxOf { it.y } - corners.minOf { it.y }) / imageHeight,
            1.0
        )
        result = Affine.translation(crop.cx, crop.cy) * Affine.scale(
            factor,
            factor
        ) * Affine.translation(-crop.cx, -crop.cy) * result
        corners = points()
        val minX = corners.minOf { it.x };
        val maxX = corners.maxOf { it.x };
        val minY = corners.minOf { it.y };
        val maxY = corners.maxOf { it.y }
        val dx = when {
            minX < 0 -> -minX; maxX > imageWidth -> imageWidth - maxX; else -> 0.0
        }
        val dy = when {
            minY < 0 -> -minY; maxY > imageHeight -> imageHeight - maxY; else -> 0.0
        }
        return Affine.translation(
            -(result.a * dx + result.c * dy),
            -(result.b * dx + result.d * dy)
        ) * result
    }
}