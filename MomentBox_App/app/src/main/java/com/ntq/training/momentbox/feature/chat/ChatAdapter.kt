package com.ntq.training.momentbox.feature.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.data.local.db.ChatUserEntity
import com.ntq.training.momentbox.data.local.db.ConversationPreview
import com.ntq.training.momentbox.databinding.ItemChatConversationBinding
import com.ntq.training.momentbox.databinding.ItemChatOnlineBinding
import com.ntq.training.momentbox.R
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class ConversationItem(
    val preview: ConversationPreview,
    val online: Boolean,
    val typing: Boolean,
    val owner: String
)

/** DiffUtil thay đúng row thay đổi. Payload không bind lại avatar khi chỉ unread/typing/status đổi;
 * Glide request theo lifecycle của ImageView, không giữ Fragment/Activity trong singleton. */
class ConversationAdapter(private val click: (Long) -> Unit) :
    ListAdapter<ConversationItem, ConversationAdapter.Holder>(object :
        DiffUtil.ItemCallback<ConversationItem>() {
            override fun areItemsTheSame(a: ConversationItem, b: ConversationItem) =
            a.preview.conversation.id == b.preview.conversation.id && a.preview.conversation.ownerId == b.preview.conversation.ownerId && a.preview.conversation.source == b.preview.conversation.source

            override fun areContentsTheSame(a: ConversationItem, b: ConversationItem) = a == b
            override fun getChangePayload(a: ConversationItem, b: ConversationItem): Any? =
            if (a.preview.conversation.avatarUrl == b.preview.conversation.avatarUrl) "state" else null
        }) {
            class Holder(val b: ItemChatConversationBinding) : RecyclerView.ViewHolder(b.root)

            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
        ItemChatConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
    )

            override fun onBindViewHolder(h: Holder, position: Int) = bind(h, getItem(position), true)
            override fun onBindViewHolder(h: Holder, position: Int, payloads: MutableList<Any>) =
        bind(h, getItem(position), payloads.isEmpty())

            private fun bind(h: Holder, item: ConversationItem, avatar: Boolean) {
                with(h.b) {
            val row = item.preview.conversation
            val c = root.context
            if (avatar) Glide.with(this.avatar).load(row.avatarUrl)
                .placeholder(ChatAvatarDrawable(row.title)).circleCrop().into(this.avatar)
            title.text = row.title
            online.isVisible = item.online && row.type == "direct"
            val unread = row.unreadCount > 0
            root.setBackgroundColor(
                ContextCompat.getColor(
                    c,
                    if (unread) R.color.chat_unread else R.color.surface
                )
            )
            title.typeface = ResourcesCompat.getFont(
                c,
                if (unread) R.font.be_vietnam_pro_bold else R.font.be_vietnam_pro_semibold
            )
            time.text = if (row.updatedAt > 0) chatTime(row.updatedAt, c) else ""
            time.setTextColor(
                ContextCompat.getColor(
                    c,
                    if (unread) R.color.primary else R.color.text_tertiary
                )
            )
            this.unread.isVisible = unread
            this.unread.text =
            NumberFormat.getIntegerInstance().format(row.unreadCount)
            preview.text = when {
                item.typing -> c.getString(R.string.m5_chat_typing)
                row.lastMessageType == "image" -> c.getString(
                    R.string.m5_chat_image
                )
                row.lastMessageType == "voice" -> c.getString(R.string.m5_chat_voice) + " · " + duration(
                    item.preview.duration ?: 0
                )
                else -> row.lastMessagePreview
            }
            preview.setTypeface(
                null,
                if (item.typing) Typeface.ITALIC else if (unread) Typeface.BOLD else Typeface.NORMAL
            )
            preview.setTextColor(
                ContextCompat.getColor(
                    c,
                    if (item.typing) R.color.primary else if (unread) R.color.text_primary else R.color.text_secondary
                )
            )
            mediaType.isVisible = !item.typing && row.lastMessageType in setOf("image", "voice")
            mediaType.setImageResource(if (row.lastMessageType == "voice") R.drawable.ic_mic else R.drawable.ic_photo_camera)
            status.isVisible =
                !item.typing && row.lastSenderId?.toString() == item.owner && item.preview.status != null
            status.setImageResource(
                when (item.preview.status) {
                    "SENDING" -> R.drawable.ic_chat_pending
                    "FAILED" -> R.drawable.ic_error
                    "SENT" -> R.drawable.ic_chat_done
                    else -> R.drawable.ic_chat_done_all
                }
            )
            status.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    c,
                    when (item.preview.status) {
                        "SEEN" -> R.color.primary
                        "FAILED" -> R.color.error
                        else -> R.color.text_tertiary
                    }
                )
            )
            root.setOnClickListener { click(row.id) }
        }
            }
        }

class OnlineAdapter(private val click: (Long) -> Unit) :
    ListAdapter<ChatUserEntity, OnlineAdapter.Holder>(object :
        DiffUtil.ItemCallback<ChatUserEntity>() {
            override fun areItemsTheSame(a: ChatUserEntity, b: ChatUserEntity) =
            a.id == b.id && a.ownerId == b.ownerId && a.source == b.source

            override fun areContentsTheSame(a: ChatUserEntity, b: ChatUserEntity) = a == b
        }) {
            class Holder(val b: ItemChatOnlineBinding) : RecyclerView.ViewHolder(b.root)

            override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        Holder(ItemChatOnlineBinding.inflate(LayoutInflater.from(p.context), p, false))

            override fun onBindViewHolder(h: Holder, p: Int) {
                val row = getItem(p)
                h.b.name.text = row.displayName
                Glide.with(h.b.avatar)
            .load(row.avatarUrl).placeholder(ChatAvatarDrawable(row.displayName)).circleCrop()
            .into(h.b.avatar)
                h.b.root.setOnClickListener { click(row.id) }
            }
        }

internal fun duration(ms: Long): String =
    String.format(Locale.getDefault(), "%d:%02d", ms / 60000, ms / 1000 % 60)

internal fun chatTime(ms: Long, c: Context): String {
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when (date.toLocalDate()) {
        today -> date.format(
            DateTimeFormatter.ofPattern(
                "HH:mm",
                Locale.getDefault()
            )
        )
        today.minusDays(1) -> c.getString(R.string.m5_chat_yesterday)
        else -> date.format(
            DateTimeFormatter.ofPattern("dd/MM", Locale.getDefault())
        )
    }
}
