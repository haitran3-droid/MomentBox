package com.example.momentbox_app.core.base

fun interface Mapper<in I, out O> {
    fun map(input: I): O
}