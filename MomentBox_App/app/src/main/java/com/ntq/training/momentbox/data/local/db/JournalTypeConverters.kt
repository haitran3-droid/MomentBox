package com.ntq.training.momentbox.data.local.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class JournalTypeConverters {
    private val gson = Gson()
    private val listIntType = object : TypeToken<List<Int>>() {}.type

    @TypeConverter
    fun fromIntList(list: List<Int>?): String {
        if (list.isNullOrEmpty()) return "[]"
        return gson.toJson(list, listIntType)
    }

    @TypeConverter
    fun toIntList(json: String?): List<Int> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return try {
            gson.fromJson(json, listIntType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}