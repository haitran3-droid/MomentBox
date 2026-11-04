package com.ntq.training.momentbox.domain.mapper

import com.ntq.training.momentbox.core.base.Mapper
import com.ntq.training.momentbox.data.remote.dto.ConfigDto
import com.ntq.training.momentbox.domain.model.AppConfig

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