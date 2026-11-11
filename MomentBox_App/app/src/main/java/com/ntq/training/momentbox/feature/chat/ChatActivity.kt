package com.ntq.training.momentbox.feature.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.ntq.training.momentbox.databinding.ActivityChatBinding
import com.ntq.training.momentbox.feature.tabs.ChatFragment
import com.ntq.training.momentbox.MomentBoxApp
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved); enableEdgeToEdge();
        val b = ActivityChatBinding.inflate(layoutInflater); setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        lifecycleScope.launch { (application as MomentBoxApp).chatRuntime.activate() }
        val room = intent.getLongExtra("conversationId", -1); if (room <= 0) {
            finish(); return
        }
        if (saved == null) {
            supportFragmentManager.beginTransaction().replace(
                b.chatContainer.id,
                ChatRoomFragment.newInstance(room, intent.getStringExtra("draft")),
                "room"
            )
                .add(
                    b.chatListContainer.id,
                    ChatFragment()
                        .apply { arguments = Bundle().apply { putBoolean("listOnly", true) } },
                    "list"
                ).commit()
        }
        supportFragmentManager.findFragmentByTag("list")?.let {
            supportFragmentManager.beginTransaction().setMaxLifecycle(
                it,
                if (resources.configuration.smallestScreenWidthDp >= 600) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
            ).commit()
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        val room = intent.getLongExtra("conversationId", -1); if (room <= 0) return
        select(room)
    }

    fun select(room: Long) {
        if (room > 0) {
            intent.putExtra(
                "conversationId",
                room
            ); (supportFragmentManager.findFragmentByTag("room") as? ChatRoomFragment)?.select(room)
        }
    }

    override fun finish() {
        val room = supportFragmentManager.findFragmentByTag("room") as? ChatRoomFragment
        val session =
            (application as MomentBoxApp).chatRuntime.session.value
        if (room != null && session != null) setResult(
            RESULT_OK,
            Intent().putExtra("conversationId", room.currentConversation())
                .putExtra("draft", room.currentDraft().take(5000))
                .putExtra("owner", session.ownerId).putExtra("source", session.source)
        )
        super.finish()
    }

    companion object {
        fun intent(c: Context, room: Long, draft: String? = null) =
            Intent(c, ChatActivity::class.java).putExtra("conversationId", room)
                .putExtra("draft", draft?.take(5000)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}