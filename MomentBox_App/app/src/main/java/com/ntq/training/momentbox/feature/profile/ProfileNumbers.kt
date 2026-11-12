package com.ntq.training.momentbox.feature.profile

import java.text.NumberFormat
import java.util.Locale

object ProfileNumbers {
    fun format(count: Int, locale: Locale): String {
        val value = count.coerceAtLeast(0)
        val divisor = when {
            value >= 1000000 -> 1000000
            value >= 1000 -> 1000
            else -> 1
        }
        val number = NumberFormat.getNumberInstance(locale)
            .apply { isGroupingUsed = false
            maximumFractionDigits = if (divisor == 1) 0 else 1 }
        return number.format(value.toDouble() / divisor) + when (divisor) {
            1000000 -> "M"
            1000 -> "K"
            else -> ""
        }
    }
}