package com.ntq.training.momentbox.feature.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ntq.training.momentbox.data.remote.dto.ChatMemberDto
import com.ntq.training.momentbox.R
import java.text.NumberFormat

internal fun members(state: RoomState): List<ChatMemberDto> = runCatching {
    Gson().fromJson<List<ChatMemberDto>>(state.room?.membersJson,
        object : TypeToken<List<ChatMemberDto>>() {}.type)
}.getOrNull().orEmpty()

internal fun presenceLabel(
    c: Context,
    state: RoomState,
    now: Long = System.currentTimeMillis()
): Pair<Boolean, String> {
    val members = members(state)
    val numbers = NumberFormat.getIntegerInstance(c.resources.configuration.locales[0])
    if (state.room?.type == "group") return false to c.getString(
        R.string.m5_chat_members,
        numbers.format(members.size)
    )
    val peer =
        state.users.firstOrNull { it.id.toString() != state.owner && members.any { member -> member.id == it.id } }
            ?: return false to ""
    if (peer.online) return true to c.getString(R.string.m5_chat_online)
    val last = peer.lastSeenAt ?: return false to ""
    val minutes = ((now - last).coerceAtLeast(0) / 60000)
    val text = when {
        minutes < 1 -> c.getString(R.string.m5_chat_recent)
        minutes < 60 -> c.getString(
            R.string.m5_chat_minutes,
            numbers.format(minutes)
        )
        minutes < 1440 -> c.getString(
            R.string.m5_chat_hours,
            numbers.format(minutes / 60)
        )
        else -> c.getString(
            R.string.m5_chat_days,
            numbers.format(minutes / 1440)
        )
    }
    return false to text
}
