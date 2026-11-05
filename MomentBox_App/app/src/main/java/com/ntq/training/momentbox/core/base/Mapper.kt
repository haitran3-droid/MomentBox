package com.ntq.training.momentbox.core.base

fun interface Mapper<in I, out O> {
    fun map(input: I): O
}