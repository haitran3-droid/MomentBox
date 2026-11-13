package com.ntq.training.momentbox.feature.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.core.media.VoicePlaybackState
import com.ntq.training.momentbox.data.local.db.MessageEntity
import com.ntq.training.momentbox.data.repository.ChatMediaProgress
import com.ntq.training.momentbox.databinding.ItemChatMessageBinding
import com.ntq.training.momentbox.R
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

sealed interface ChatRow {
    val key: String

    data class Message(val value: MessageEntity, val owner: String, val grouped: Boolean) :
        ChatRow {
            override val key = messageKey(value)
        }

    data class Date(val day: LocalDate) : ChatRow {
        override val key = "date:$day"
    }
}

/** Adapter nhận newest-first với reverseLayout. Separator đứng sau tin cũ nhất của ngày
 * trong data để hiển thị phía trên nhóm. Key localId giữ nguyên qua ACK để không chớp row. */
class MessageAdapter(
    private val image: (MessageEntity) -> Unit,
    private val voice: (MessageEntity) -> Unit,
    private val seek: (MessageEntity, Float) -> Unit,
    private val cancel: (String) -> Unit,
    private val retry: (String) -> Unit
) : ListAdapter<ChatRow, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<ChatRow>() {
    override fun areItemsTheSame(a: ChatRow, b: ChatRow) = a.key == b.key
    override fun areContentsTheSame(a: ChatRow, b: ChatRow) = a == b
}) {
    private var voiceKey: String? = null
    private var playback = VoicePlaybackState()
    private var uploads: Map<String, ChatMediaProgress> =
        emptyMap()
    private var waveforms: Map<String, List<Float>> = emptyMap()
    fun playback(key: String?, value: VoicePlaybackState) {
        val previous = voiceKey
        voiceKey = key
        playback = value
        currentList.forEachIndexed { i, row ->
            if (row is ChatRow.Message && row.value.localId in setOf(
                    previous,
                    key
                )
            ) notifyItemChanged(i, "playback")
        }
    }

    fun uploads(values: Map<String, ChatMediaProgress>) {
        val affected =
            (uploads.keys + values.keys).filter { uploads[it] != values[it] }.toSet()
            uploads =
            values
        currentList.forEachIndexed { i, row ->
            if (row is ChatRow.Message && row.value.localId in affected) notifyItemChanged(
                i,
                "upload"
            )
        }
    }

    fun waveform(key: String, values: List<Float>) {
        waveforms = waveforms + (key to values)
        currentList.indexOfFirst { it.key == key }
            .takeIf { it >= 0 }?.let { notifyItemChanged(it, "waveform") }
    }

    class MessageHolder(val b: ItemChatMessageBinding) : RecyclerView.ViewHolder(b.root)
    class DateHolder(val frame: FrameLayout, val text: TextView) :
        RecyclerView.ViewHolder(frame)

    override fun getItemViewType(p: Int) = if (getItem(p) is ChatRow.Date) 1 else 0
    override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
        if (t == 0) return MessageHolder(
            ItemChatMessageBinding.inflate(
                LayoutInflater.from(p.context),
                p,
                false
            )
        )
        val d = p.resources.displayMetrics.density
        val frame = FrameLayout(p.context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            setPadding(
            0,
            (4 * d).toInt(),
            0,
            (4 * d).toInt()
        )
        }
        val text = TextView(p.context).apply {
            gravity = Gravity.CENTER
            textSize = 11.5f
            typeface =
            ResourcesCompat.getFont(context, R.font.be_vietnam_pro_medium)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    R.color.text_tertiary
                )
            )
            setPadding((10 * d).toInt(), (3 * d).toInt(), (10 * d).toInt(), (3 * d).toInt())
            background = GradientDrawable().apply {
                setColor(
                    ContextCompat.getColor(
                        context,
                        R.color.surface_variant
                    )
                )
                cornerRadius = 10 * d
            }
        }
        frame.addView(
            text,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        )
        return DateHolder(frame, text)
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
        val item = getItem(p)
        if (h is DateHolder && item is ChatRow.Date) {
            val c = h.text.context
            h.text.text = when (item.day) {
                LocalDate.now() -> c.getString(R.string.m5_chat_today)
                LocalDate.now()
                    .minusDays(1) -> c.getString(R.string.m5_chat_yesterday)
                    else -> item.day.format(
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                        .withLocale(c.resources.configuration.locales[0])
                )
            }
            return
        }
        if (h !is MessageHolder || item !is ChatRow.Message) return
        val message = item.value
        val own = message.senderId.toString() == item.owner
        val c = h.b.root.context
        with(h.b) {
            row.gravity = if (own) Gravity.END else Gravity.START
            bubble.setBackgroundResource(if (own) R.drawable.bg_chat_bubble_out else R.drawable.bg_chat_bubble_in)
            body.maximumWidth = ((root.width.takeIf { it > 0 }
                ?: (c.resources.displayMetrics.widthPixels - if (c.resources.configuration.smallestScreenWidthDp >= 600) (280 * c.resources.displayMetrics.density).toInt() else 0)) * .78f).toInt()
            val clock = chatClock(message.createdAt, c)
            body.bind(message.content, clock, message.status, own)
            body.isVisible =
            message.type == "text"
            time.isVisible = false
            time.text = clock
            time.setTextColor(
                if (own) 0xB3FFFFFF.toInt() else ContextCompat.getColor(
                    c,
                    R.color.text_tertiary
                )
            )
            imageFrame.isVisible = message.type == "image"
            image.isVisible =
            imageFrame.isVisible
            voice.isVisible = message.type == "voice"
            imageTime.text = clock
            val dp = c.resources.displayMetrics.density
            bubble.setPadding(
                if (message.type == "image") 0 else (if (message.type == "voice") 4 * dp else 12 * dp).toInt(),
                if (message.type == "text") (9 * dp).toInt() else 0,
                if (message.type == "image") 0 else (12 * dp).toInt(),
                if (message.type == "text") (9 * dp).toInt() else 0
            )
            if (message.type == "image") {
                val model = message.mediaUrl ?: message.ownedPath?.let {
                    Uri.fromFile(
                        File(it)
                    ).toString()
                }
                Glide.with(image).load(model).into(image)
                image.setOnClickListener { this@MessageAdapter.image(message) }
            } else Glide.with(image).clear(image)
            val playing = voiceKey == message.localId && playback.isPlaying
            voiceIcon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                if (own) Color.WHITE else ContextCompat.getColor(
                    c,
                    R.color.primary
                )
            )
            }
            voiceIcon.imageTintList = ColorStateList.valueOf(
                if (own) ContextCompat.getColor(
                    c,
                    R.color.primary
                ) else Color.WHITE
            )
            voiceIcon.setImageResource(if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play)
            voicePlay.setOnClickListener { this@MessageAdapter.voice(message) }
            voiceDuration.text = duration(
                if (voiceKey == message.localId && playback.currentPositionMs > 0) playback.currentPositionMs.toLong() else message.durationMs
                    ?: 0,
                c.resources.configuration.locales[0]
            )
            voiceDuration.setTextColor(
                if (own) Color.WHITE else ContextCompat.getColor(
                    c,
                    R.color.text_tertiary
                )
            )
            waveform.render(
                waveforms[messageKey(message)].orEmpty(),
                if (voiceKey == message.localId) playback.progress else 0f,
                own
            )
            waveform.seek = { this@MessageAdapter.seek(message, it) }
            voiceError.isVisible =
                voiceKey == message.localId && playback.errorMessage != null
                voiceError.setOnClickListener {
            this@MessageAdapter.voice(
                message
            )
        }
            val upload = uploads[message.localId]
            uploadProgress.isVisible = upload?.uploading == true
            uploadCancel.isVisible =
            uploadProgress.isVisible
            uploadProgress.progress =
                if (upload == null || upload.total == 0L) 0 else (upload.bytes * 100 / upload.total).toInt()
            uploadCancel.setOnClickListener { cancel(message.localId) }
            val d = c.resources.displayMetrics.density
            val large = 18 * d
            val small = 6 * d
            (bubble.background.mutate() as? GradientDrawable)?.cornerRadii =
                if (own)
                    floatArrayOf(
                        large,
                        large,
                        if (item.grouped) small else large,
                        if (item.grouped) small else large,
                        small,
                        small,
                        large,
                        large
                    )
                else floatArrayOf(
                    if (item.grouped) small else large,
                    if (item.grouped) small else large,
                    large,
                    large,
                    large,
                    large,
                    small,
                    small
                )
            failedIcon.isVisible = message.status == "FAILED"
            failure.isVisible =
            failedIcon.isVisible
            failure.setOnClickListener { retry(message.localId) }
            bubble.alpha = if (message.status == "FAILED") .75f else 1f
        }
    }

    companion object {
        fun rows(messages: List<MessageEntity>, owner: String): List<ChatRow> = buildList {
            messages.forEachIndexed { i, m ->
                val older = messages.getOrNull(i + 1)
                val day =
                    Instant.ofEpochMilli(m.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                val olderDay = older?.let {
                    Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                add(
                    ChatRow.Message(
                        m,
                        owner,
                        older != null && older.senderId == m.senderId && m.createdAt - older.createdAt in 0..120000 && olderDay == day
                    )
                )
                if (olderDay != day) add(ChatRow.Date(day))
            }
        }
    }
}

internal fun messageKey(message: MessageEntity) =
    message.ownerId + "|" + message.source + "|" + message.localId
