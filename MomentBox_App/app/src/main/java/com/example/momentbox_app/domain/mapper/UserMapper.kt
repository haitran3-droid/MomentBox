package com.example.momentbox_app.domain.mapper

import com.example.momentbox_app.core.base.Mapper
import com.example.momentbox_app.data.remote.dto.DummyJsonLoginResponse
import com.example.momentbox_app.data.remote.dto.DummyJsonMeResponse
import com.example.momentbox_app.data.remote.dto.UserDto
import com.example.momentbox_app.domain.model.User

class UserMapper : Mapper<UserDto, User> {
    override fun map(input: UserDto): User {
        return User(
            id = input.id,
            username = input.username,
            displayName = input.displayName ?: input.username,
            email = input.email,
            avatarUrl = input.avatarUrl,
            verified = input.verified ?: false,
            bio = input.bio,
            location = input.location,
            postCount = input.postCount ?: 0,
            followerCount = input.followerCount ?: 0,
            followingCount = input.followingCount ?: 0
        )
    }

    /** Ánh xạ dữ liệu đăng nhập từ nguồn DummyJson */
    fun mapFromDummyJson(input: DummyJsonLoginResponse) : User{
        val fullName = listOfNotNull(input.firstName, input.lastName).joinToString(" ").ifBlank { input.username }
        return User(
            id = input.id,
            username = input.username,
            displayName = fullName,
            email = input.email,
            avatarUrl = input.image,
            verified = false
        )
    }

    /** Ánh xạ dữ liệu hồ sơ cá nhân từ nguồn DummyJSON */
    fun mapFromDummyJsonMe(input: DummyJsonMeResponse) : User{
        val fullName = listOfNotNull(input.firstName, input.lastName).joinToString(" ").ifBlank { input.username }
        return User(
            id = input.id,
            username = input.username,
            displayName = fullName,
            email = input.email,
            avatarUrl = input.image,
            verified = false
        )
    }
}