package com.example.momentbox_app.core.network

import com.example.momentbox_app.data.remote.dto.PostDto
import com.example.momentbox_app.data.remote.dto.RawFeedItemDto
import com.example.momentbox_app.data.remote.dto.UserDto
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

class RawFeedItemDeserializer : JsonDeserializer<RawFeedItemDto> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): RawFeedItemDto? {
        if (json == null || !json.isJsonObject || context == null) {
            return RawFeedItemDto(kind = "unknown")
        }

        return try {
            val obj = json.asJsonObject
            val kind = obj.get("kind")?.asString ?: "unknown"
            when (kind) {
                "post" -> {
                    val postElement = obj.get("post")
                    val postDto = if (postElement != null && postElement.isJsonObject) {
                        context.deserialize<PostDto>(postElement, PostDto::class.java)
                    } else null
                    RawFeedItemDto(kind = "post", post = postDto)
                }

                "suggestions" -> {
                    val usersElement = obj.get("users")
                    val usersList = if (usersElement != null && usersElement.isJsonArray) {
                        val list = mutableListOf<UserDto>()
                        for (userEl in usersElement.asJsonArray) {
                            try {
                                val u = context.deserialize<UserDto>(userEl, UserDto::class.java)
                                if (u != null) list.add(u)
                            } catch (_: Exception) {
                            }
                        }
                        list
                    } else emptyList()
                    RawFeedItemDto(kind = "suggestions", users = usersList)
                }

                else -> {
                    RawFeedItemDto(kind = "unknown")
                }
            }
        } catch (e: Exception) {
            RawFeedItemDto(kind = "unknown")
        }
    }
}