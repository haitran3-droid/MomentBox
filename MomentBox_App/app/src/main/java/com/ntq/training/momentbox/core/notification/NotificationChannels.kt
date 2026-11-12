package com.ntq.training.momentbox.core.notification

import android.media.AudioAttributes
import androidx.annotation.RequiresApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.ntq.training.momentbox.R

object NotificationChannels {
    const val PLAYBACK = "momentbox.playback"
    const val RECORDING = "momentbox.recording"
    const val UPLOAD = "momentbox.upload"
    const val CHAT = "momentbox.chat"
    const val REMINDER = "momentbox.reminder"
    private val names = mapOf(
        PLAYBACK to R.string.m5_player_channel,
        RECORDING to R.string.m4_record_title,
        UPLOAD to R.string.m4_upload,
        CHAT to R.string.m5_channel_chat,
        REMINDER to R.string.m5_channel_reminder
    )

    fun channelEnabled(context: Context, id: String): Boolean {
        require(id in names)
        if (Build.VERSION.SDK_INT < 26) return true
        return context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(id)?.importance?.let { it > NotificationManager.IMPORTANCE_NONE } == true
    }

    @RequiresApi(26)
    internal fun defaultChannel(context: Context, id: String): NotificationChannel {
        require(id in names)
        val importance =
            if (id == CHAT || id == REMINDER) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW
        return NotificationChannel(id, context.getString(names.getValue(id)), importance).apply {
            setShowBadge(id == CHAT)
            if (id == REMINDER) setSound(
                null, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
        }
    }

    fun ensure(context: Context) {
        names.keys.forEach { ensureChannel(context, it) }
    }

    fun ensureChannel(context: Context, id: String) {
        require(id in names)
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val previous = manager.getNotificationChannel(id)
        // Lấy channel hiện có rồi chỉ cập nhật tên: không hạ importance hoặc thay sound đã tùy chỉnh.
        val channel = previous ?: defaultChannel(context, id)
        channel.name = context.getString(names.getValue(id))
        manager.createNotificationChannel(channel)
    }
}