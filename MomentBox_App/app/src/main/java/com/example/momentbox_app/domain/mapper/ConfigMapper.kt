package com.example.momentbox_app.domain.mapper

import com.example.momentbox_app.core.base.Mapper
import com.example.momentbox_app.data.remote.dto.ConfigDto
import com.example.momentbox_app.domain.model.AppConfig

class ConfigMapper : Mapper<ConfigDto, AppConfig>{
    override fun map(input: ConfigDto): AppConfig {
        return AppConfig(
            version = input.version ?: "1.0.0",
            minSupportedVersionCode = input.minSupportedVersionCode,
            serverTime = input.serverTime,
            socketUrl = input.socketUrl
        )
    }
}